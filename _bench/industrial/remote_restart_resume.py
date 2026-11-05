#!/usr/bin/env python3
"""Force-kill and resume the real CLI against one already-authorized public URL."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

import restart_resume as rr
import run_engine_compare as compare
import traffic_budget as tb


DEFAULT_OUTPUT_DIR = rr.ROOT / "_bench" / "results" / "remote-restart-resume"


def sanitize(text: str, source_url: str) -> str:
    return text.replace(source_url, "<source>")


def wait_for_durable(
    process: subprocess.Popen[str],
    destination: Path,
    threshold: int,
    timeout_s: float,
) -> int:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        sidecar = rr.find_progress_sidecar(destination)
        if sidecar is not None:
            try:
                completed = rr.parse_progress_sidecar(sidecar)["completed_bytes"]
            except (OSError, ValueError):
                completed = 0
            if completed >= threshold:
                return completed
        if process.poll() is not None:
            output = process.stdout.read() if process.stdout else ""
            raise rr.ProbeError(
                f"first process exited before kill threshold: {output[-2000:]}"
            )
        time.sleep(0.02)
    raise rr.ProbeError(
        f"timed out waiting for {threshold} durable bytes"
    )


def run(args: argparse.Namespace) -> dict[str, object]:
    compare.validate_public_http_url(args.url)
    jar = args.jar.resolve()
    if not jar.is_file():
        raise rr.ProbeError(f"CLI jar does not exist: {jar}")
    expected = args.sha256.lower()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    run_id = f"remote-restart-{stamp}"
    output_dir = args.output_dir.resolve()
    run_dir = output_dir / run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    destination = run_dir / "payload.bin"
    allocation = args.size * 4
    budget = tb.TrafficBudget(tb.default_data_dir())
    budget.reserve(
        run_id,
        allocation,
        metadata={
            "kind": "remote-restart-resume",
            "source_sha256": compare.sha256_text(args.url),
            "payload_size": args.size,
            "connections": args.connections,
        },
    )
    reconciled = False
    command = rr.build_cli_command(
        java_exe=args.java,
        jar=jar,
        url=args.url,
        destination=destination,
        connections=args.connections,
        expected_sha256=expected,
        progress_interval_ms=100,
    )
    if args.proxy:
        command += ["--proxy", args.proxy]

    first_output = ""
    second_output = ""
    first_exit: int | None = None
    second_exit: int | None = None
    first_durable = 0
    try:
        first = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        try:
            threshold = max(1, int(args.size * args.durable_percent / 100))
            first_durable = wait_for_durable(
                first,
                destination,
                threshold,
                args.timeout_sec,
            )
            rr.kill_exact_process_tree(first.pid, process=first)
            first_output, _ = first.communicate(timeout=10)
            first_exit = first.returncode
            if first_exit == 0:
                raise rr.ProbeError(
                    "download completed before the process kill; "
                    "increase size or lower durable-percent"
                )
        finally:
            if first.poll() is None:
                rr.kill_exact_process_tree(first.pid, process=first)
                first.communicate(timeout=10)

        second_exit, second_output = rr._run_process(
            command,
            timeout_s=args.timeout_sec,
        )
        if second_exit != 0:
            raise rr.ProbeError(
                f"resumed CLI failed: {sanitize(second_output[-2000:], args.url)}"
            )
        actual = rr.sha256_file(destination)
        if actual != expected:
            raise rr.ProbeError(
                f"final SHA-256 mismatch: expected={expected} actual={actual}"
            )
        if not rr.resume_was_reused(second_output):
            raise rr.ProbeError(
                "resumed CLI did not report parts=true/discard=false"
            )

        # No packet capture here; charge a conservative two full payloads for
        # the partial first run plus resumed range traffic and probes.
        charge = min(allocation, args.size * 2)
        budget.reconcile(run_id, charge, status="completed")
        reconciled = True
        result = {
            "status": "PASS",
            "run_id": run_id,
            "jar": str(jar),
            "jar_sha256": rr.sha256_file(jar),
            "source_sha256": compare.sha256_text(args.url),
            "payload_bytes": args.size,
            "payload_sha256": expected,
            "first_exit": first_exit,
            "first_durable_bytes": first_durable,
            "second_exit": second_exit,
            "resume_metadata_reused": True,
            "charged_budget_bytes": charge,
            "first_output_tail": sanitize(first_output[-3000:], args.url),
            "second_output_tail": sanitize(second_output[-3000:], args.url),
        }
        (run_dir / "result.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return result
    except Exception as exc:
        if not reconciled:
            try:
                budget.reconcile(run_id, allocation, status="failed")
            except Exception:
                pass
        raise


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Force-kill and resume the real CLI on a public HTTP source."
    )
    p.add_argument("--url", required=True)
    p.add_argument("--size", type=int, required=True)
    p.add_argument("--sha256", required=True)
    p.add_argument("--connections", type=int, default=16)
    p.add_argument("--proxy")
    p.add_argument("--java", default="java")
    p.add_argument("--jar", type=Path, default=rr.DEFAULT_JAR)
    p.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    p.add_argument("--durable-percent", type=float, default=20.0)
    p.add_argument("--timeout-sec", type=float, default=600.0)
    return p


def main(argv: Sequence[str] | None = None) -> int:
    p = parser()
    args = p.parse_args(argv)
    if args.size <= 0:
        p.error("--size must be positive")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.sha256):
        p.error("--sha256 must be 64 hex characters")
    if not 1 <= args.connections <= 256:
        p.error("--connections must be 1..256")
    if not 0 < args.durable_percent < 95:
        p.error("--durable-percent must be in (0, 95)")
    try:
        result = run(args)
    except (rr.ProbeError, tb.BudgetError, OSError, ValueError) as exc:
        print(json.dumps({"status": "FAIL", "error": str(exc)}, ensure_ascii=True))
        return 1
    print(
        json.dumps(
            {
                "status": result["status"],
                "payload_bytes": result["payload_bytes"],
                "first_durable_bytes": result["first_durable_bytes"],
                "second_exit": result["second_exit"],
                "charged_budget_bytes": result["charged_budget_bytes"],
                "payload_sha256": result["payload_sha256"],
            },
            ensure_ascii=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
