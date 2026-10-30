#!/usr/bin/env python3
"""Run TurboDL's connection-count curve against one HTTP(S) resource.

This is intentionally process-isolated: every case launches a fresh JVM so a
previous connection pool, adaptive controller, or warmed disk cache cannot leak
into the next sample.

Example:
    python _bench/run_concurrency_matrix.py \
      --url https://example.com/file.bin \
      --size 158224448 \
      --proxy http://127.0.0.1:7897 \
      --connections 8,16,32,64,128 \
      --mode fixed --repeats 2
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import statistics
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "turbodl-cli" / "build" / "libs" / "turbodl-cli-all.jar"
OUTPUT_DIR = ROOT / "_bench" / "runs"
RESULT_DIR = ROOT / "_bench" / "results"


def parse_ints(value: str) -> list[int]:
    return [int(part.strip()) for part in value.split(",") if part.strip()]


def run_case(
    *,
    url: str,
    expected_size: int,
    connections: int,
    mode: str,
    proxy: str | None,
    timeout: int,
    keep_files: bool,
) -> dict:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    output = OUTPUT_DIR / f"{mode}-c{connections}-{stamp}.bin"
    command = [
        "java",
        "-jar",
        str(JAR),
        url,
        "-o",
        str(output),
        "-c",
        str(connections),
        "--no-warmup",
        "--progress-interval",
        "200",
        "--json",
    ]
    if proxy:
        command.extend(["--proxy", proxy])
    if mode == "fixed":
        command.extend(["--no-slow-start", "--no-adaptive", "--no-tail-assist"])

    started = time.monotonic()
    completed = subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        env=os.environ.copy(),
    )
    elapsed = time.monotonic() - started
    actual_size = output.stat().st_size if output.exists() else 0
    ok = (
        completed.returncode == 0
        and actual_size == expected_size
    )
    completed_event = None
    connection_values: list[int] = []
    for line in completed.stdout.splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if event.get("type") == "completed":
            completed_event = event
        if event.get("type") == "progress":
            active = event.get("connections")
            if isinstance(active, int) and active > 0:
                connection_values.append(active)

    result = {
        "mode": mode,
        "connections": connections,
        "ok": ok,
        "exit_code": completed.returncode,
        "elapsed_s": elapsed,
        "bytes": actual_size,
        "mb_per_s": actual_size / (1024 * 1024) / elapsed if elapsed > 0 else 0.0,
        "sha256": (completed_event or {}).get("sha256", ""),
        "max_connections": max(connection_values, default=0),
        "median_connections": statistics.median(connection_values) if connection_values else 0,
        "error": "" if ok else completed.stdout[-2000:],
    }
    if not keep_files:
        output.unlink(missing_ok=True)
    return result


def median_results(samples: list[dict]) -> dict:
    elapsed = statistics.median(row["elapsed_s"] for row in samples)
    size = statistics.median(row["bytes"] for row in samples)
    return {
        "mode": samples[0]["mode"],
        "connections": samples[0]["connections"],
        "ok": all(row["ok"] for row in samples),
        "elapsed_s": elapsed,
        "bytes": size,
        "mb_per_s": size / (1024 * 1024) / elapsed if elapsed > 0 else 0.0,
        "samples_mb_s": [row["mb_per_s"] for row in samples],
        "max_connections": max((row["max_connections"] for row in samples), default=0),
        "median_connections": statistics.median(
            [row["median_connections"] for row in samples]
        ) if samples else 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--size", type=int, required=True)
    parser.add_argument("--connections", default="8,16,32,64,128")
    parser.add_argument("--mode", choices=("fixed", "adaptive", "both"), default="both")
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--proxy")
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--keep-files", action="store_true")
    parser.add_argument("--tag", default="network")
    args = parser.parse_args()

    if not JAR.is_file():
        print(f"missing CLI jar: {JAR}", file=sys.stderr)
        return 2
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    RESULT_DIR.mkdir(parents=True, exist_ok=True)

    modes = ("fixed", "adaptive") if args.mode == "both" else (args.mode,)
    raw_rows: list[dict] = []
    summaries: list[dict] = []
    for mode in modes:
        for connections in parse_ints(args.connections):
            samples = []
            for repeat in range(args.repeats):
                row = run_case(
                    url=args.url,
                    expected_size=args.size,
                    connections=connections,
                    mode=mode,
                    proxy=args.proxy,
                    timeout=args.timeout,
                    keep_files=args.keep_files,
                )
                samples.append(row)
                raw_rows.append(row)
                print(
                    f"{mode:8s} c={connections:<3d} run={repeat + 1}/{args.repeats} "
                    f"{row['mb_per_s']:7.2f} MB/s conn~{row['median_connections']:.0f} "
                    f"peak={row['max_connections']} ok={row['ok']}"
                )
                if not row["ok"]:
                    break
            summaries.append(median_results(samples))

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    raw_path = RESULT_DIR / f"{args.tag}-{stamp}-raw.json"
    summary_path = RESULT_DIR / f"{args.tag}-{stamp}-summary.json"
    csv_path = RESULT_DIR / f"{args.tag}-{stamp}-summary.csv"
    raw_path.write_text(json.dumps(raw_rows, indent=2), encoding="utf-8")
    summary_path.write_text(json.dumps(summaries, indent=2), encoding="utf-8")
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=(
                "mode",
                "connections",
                "ok",
                "elapsed_s",
                "bytes",
                "mb_per_s",
                "samples_mb_s",
                "max_connections",
                "median_connections",
            ),
        )
        writer.writeheader()
        writer.writerows(summaries)

    print("\nSummary (median):")
    for row in summaries:
        print(
            f"  {row['mode']:8s} c={row['connections']:<3d} "
            f"{row['mb_per_s']:7.2f} MB/s conn~{row['median_connections']:.0f} "
            f"peak={row['max_connections']}  samples={row['samples_mb_s']}"
        )
    print(f"\nraw:     {raw_path}")
    print(f"summary: {summary_path}")
    print(f"csv:     {csv_path}")
    return 0 if all(row["ok"] for row in summaries) else 1


if __name__ == "__main__":
    raise SystemExit(main())
