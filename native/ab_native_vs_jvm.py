#!/usr/bin/env python3
"""Interleaved A/B: native data-plane fast path vs full Kotlin/JVM engine."""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
import subprocess
import time
from pathlib import Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def pct(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def run_once(
    mode: str,
    launcher: Path,
    url: str,
    sha256: str,
    size: int,
    connections: int,
    run_dir: Path,
) -> dict:
    run_dir.mkdir(parents=True, exist_ok=False)
    output = run_dir / "out.bin"
    workdir = run_dir / "parts"
    common = [
        "--output", str(output),
        "--connections", str(connections),
        "--retry", "3",
        "--sha256", sha256,
        "--known-size", str(size),
        "--json",
        url,
    ]
    if mode == "native":
        command = [str(launcher), "native", "--workdir", str(workdir), *common]
    else:
        command = [str(launcher), *common]
    stdout_path = run_dir / "stdout.log"
    stderr_path = run_dir / "stderr.log"
    started = time.perf_counter()
    with stdout_path.open("w", encoding="utf-8") as out, stderr_path.open(
        "w", encoding="utf-8"
    ) as err:
        process = subprocess.run(command, cwd=run_dir, stdout=out, stderr=err)
    wall_ms = (time.perf_counter() - started) * 1000
    actual = sha256_file(output) if output.exists() else None
    return {
        "mode": mode,
        "exit": process.returncode,
        "wall_ms": wall_ms,
        "actual_sha256": actual,
        "hash_matched": actual == sha256,
        "stdout": str(stdout_path),
        "stderr": str(stderr_path),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--launcher", type=Path, required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--size", type=int, required=True)
    parser.add_argument("--connections", type=int, default=16)
    parser.add_argument("--rounds", type=int, default=5)
    args = parser.parse_args()
    args.root = args.root.resolve()
    args.launcher = args.launcher.resolve()
    args.root.mkdir(parents=True, exist_ok=True)
    rows = []
    for round_index in range(args.rounds):
        order = ["native", "jvm"] if round_index % 2 == 0 else ["jvm", "native"]
        for mode in order:
            rows.append(
                run_once(
                    mode,
                    args.launcher,
                    args.url,
                    args.sha256,
                    args.size,
                    args.connections,
                    args.root / f"round-{round_index:02d}-{mode}",
                )
            )
    summary = {}
    for mode in ("native", "jvm"):
        values = [row["wall_ms"] for row in rows if row["mode"] == mode and row["hash_matched"]]
        summary[mode] = {
            "runs": len(values),
            "p50_ms": pct(values, 0.5),
            "p95_ms": pct(values, 0.95),
            "min_ms": min(values),
            "max_ms": max(values),
        }
    summary["improvement_ratio_jvm_over_native"] = (
        summary["jvm"]["p50_ms"] / summary["native"]["p50_ms"]
        if summary["native"]["p50_ms"]
        else None
    )
    output = {"rows": rows, "summary": summary}
    (args.root / "ab.json").write_text(json.dumps(output, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))
    failed = [row for row in rows if row["exit"] != 0 or not row["hash_matched"]]
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
