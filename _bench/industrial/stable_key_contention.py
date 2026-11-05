#!/usr/bin/env python3
"""Verify cross-process stableKey ownership and recovery with the real CLI."""

from __future__ import annotations

import argparse
import json
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

import restart_resume as rr


def run(args: argparse.Namespace) -> dict[str, object]:
    jar = args.jar.resolve()
    if not jar.is_file():
        raise rr.ProbeError(f"CLI jar does not exist: {jar}")
    payload = rr.build_payload(args.size)
    expected = rr.sha256_bytes(payload)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    run_dir = args.output_dir.resolve() / f"stable-key-contention-{stamp}"
    run_dir.mkdir(parents=True, exist_ok=False)
    destination = run_dir / "payload.bin"
    server = rr.ProbeServer(
        payload,
        etag='"stable-key-contention"',
        last_modified="Wed, 04 Nov 2026 08:00:00 GMT",
        chunk_delay_ms=args.chunk_delay_ms,
    )
    server.start()
    command = rr.build_cli_command(
        java_exe=args.java,
        jar=jar,
        url=server.url,
        destination=destination,
        connections=args.connections,
        expected_sha256=expected,
        progress_interval_ms=100,
    )
    first: subprocess.Popen[str] | None = None
    second_output = ""
    second_exit: int | None = None
    resumed_output = ""
    resumed_exit: int | None = None
    try:
        first = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        threshold = max(1, int(args.size * args.ready_percent / 100))
        rr._wait_for_kill_point(
            first,
            server,
            threshold,
            max(1, threshold // 2),
            destination,
            args.timeout_sec,
        )

        second_exit, second_output = rr._run_process(
            command,
            timeout_s=min(args.timeout_sec, 30),
        )
        if second_exit == 0:
            raise rr.ProbeError(
                "second process unexpectedly acquired the same stableKey"
            )
        if "STABLE_KEY_BUSY" not in second_output:
            raise rr.ProbeError(
                "second process failed without the stable-key ownership error: "
                f"{second_output[-2000:]}"
            )

        rr.kill_exact_process_tree(first.pid, process=first)
        first_output, _ = first.communicate(timeout=10)
        first = None

        resumed_exit, resumed_output = rr._run_process(
            command,
            timeout_s=args.timeout_sec,
        )
        if resumed_exit != 0:
            raise rr.ProbeError(
                f"recovery after contention failed: {resumed_output[-2000:]}"
            )
        actual = rr.sha256_file(destination)
        if actual != expected:
            raise rr.ProbeError(
                f"final SHA-256 mismatch: expected={expected} actual={actual}"
            )
        if not rr.resume_was_reused(resumed_output):
            raise rr.ProbeError("post-contention run did not reuse durable parts")

        result: dict[str, object] = {
            "status": "PASS",
            "jar": str(jar),
            "jar_sha256": rr.sha256_file(jar),
            "payload_bytes": args.size,
            "payload_sha256": expected,
            "second_exit": second_exit,
            "second_rejected_with_owner_error": True,
            "resumed_exit": resumed_exit,
            "resume_metadata_reused": True,
            "command": command,
            "requests": server.records(),
            "first_output_tail": first_output[-3000:],
            "second_output_tail": second_output[-3000:],
            "resumed_output_tail": resumed_output[-3000:],
        }
        (run_dir / "result.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return result
    finally:
        if first is not None and first.poll() is None:
            rr.kill_exact_process_tree(first.pid, process=first)
            first.communicate(timeout=10)
        server.stop()


def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="Two real TurboDL CLI processes contend for one stableKey."
    )
    p.add_argument("--java", default="java")
    p.add_argument("--jar", type=Path, default=rr.DEFAULT_JAR)
    p.add_argument("--output-dir", type=Path, default=rr.DEFAULT_OUTPUT_DIR)
    p.add_argument("--size", type=int, default=16 * 1024 * 1024)
    p.add_argument("--connections", type=int, default=8)
    p.add_argument("--ready-percent", type=float, default=20.0)
    p.add_argument("--chunk-delay-ms", type=int, default=100)
    p.add_argument("--timeout-sec", type=float, default=180.0)
    return p


def main(argv: Sequence[str] | None = None) -> int:
    p = parser()
    args = p.parse_args(argv)
    if args.size <= 0:
        p.error("--size must be positive")
    if not 1 <= args.connections <= 256:
        p.error("--connections must be 1..256")
    if not 0 < args.ready_percent < 95:
        p.error("--ready-percent must be in (0, 95)")
    if args.chunk_delay_ms < 0:
        p.error("--chunk-delay-ms cannot be negative")
    try:
        result = run(args)
    except (rr.ProbeError, OSError, ValueError) as exc:
        print(json.dumps({"status": "FAIL", "error": str(exc)}, ensure_ascii=True))
        return 1
    print(
        json.dumps(
            {
                "status": result["status"],
                "payload_bytes": result["payload_bytes"],
                "second_exit": result["second_exit"],
                "resumed_exit": result["resumed_exit"],
                "payload_sha256": result["payload_sha256"],
            },
            ensure_ascii=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
