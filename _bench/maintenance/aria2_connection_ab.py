"""Same aria2-next binary, same Range fixture; isolate the old application's 16-connection clamp."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import time
import urllib.request


BASE = "http://127.0.0.1:8843"
EXPECTED = "de276d64dd3dc10dea453b5776e489d999f083f96ae20292fc4ba042e2356989"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def info():
    with OPENER.open(BASE + "/info", timeout=3) as response:
        return json.load(response)


def sample(binary, root, pair, variant, connections):
    folder = root / f"{pair:02}-{variant}"
    folder.mkdir()
    for _ in range(30):
        if info()["active"] == 0:
            break
        time.sleep(.05)
    with OPENER.open(urllib.request.Request(BASE + "/reset", method="POST"), timeout=3):
        pass
    command = [str(binary), "--no-conf", "-n", f"--dir={folder}", "--out=fixture.bin",
               f"--state-dir={folder / 'state'}", f"--stream-max-connections={connections}",
               "--file-allocation=none", "--auto-file-renaming=false", "--allow-overwrite=true",
               "--show-console-readout=true", "--summary-interval=0", "--human-readable=false",
               "--console-log-level=warn", f"--checksum=sha-256={EXPECTED}", BASE + "/fixture.bin"]
    started = time.perf_counter()
    process = subprocess.run(command, capture_output=True, timeout=45, cwd=folder)
    elapsed = time.perf_counter() - started
    log = (process.stdout + process.stderr).decode("utf-8", "replace")
    (folder / "console.log").write_text(log, encoding="utf-8")
    if process.returncode:
        raise RuntimeError(f"{variant} exit {process.returncode}: {folder / 'console.log'}")
    output = folder / "fixture.bin"
    with output.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    assert digest == EXPECTED, "independent output hash failed"
    metrics = info()
    readout = [int(value) for value in re.findall(r"\bCN:(\d+)", log)]
    result = {"pair": pair, "variant": variant, "connections": connections, "elapsed_ms": elapsed * 1000,
              "throughput_mib_s": output.stat().st_size / elapsed / 1024**2, "sha256": digest,
              "bytes": output.stat().st_size, "source_peak": metrics["peak"], "source_sent": metrics["sent"],
              "source_requests": metrics["requests"], "readout_peak": max(readout, default=None), "command": command}
    output.unlink()
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    assert info()["sha256"] == EXPECTED
    binary_hash = hashlib.sha256(args.binary.read_bytes()).hexdigest()
    assert binary_hash == "08afaf2a44811d38e7ce538da719ab06d6925bcaad1231ee7b92c497f58e5aac"
    (root / "manifest.json").write_text(json.dumps({"binary_sha256": binary_hash, "source": info(),
        "scenario": "same selected value 64; old bridge clamps to 16, candidate forwards 64",
        "scope": "Windows same-version native kernel, HTTP loopback; not an Android or cloud-drive speed claim"}, indent=2))
    records = []
    # One separate warmup for each ceiling, retained but excluded from summaries.
    for variant, connections in [("warmup-old", 16), ("warmup-new", 64)]:
        sample(args.binary, root, 99, variant, connections)
    for pair in range(5):
        variants = [("old-cap16", 16), ("new-request64", 64)]
        if pair % 2:
            variants.reverse()
        for variant, connections in variants:
            record = sample(args.binary, root, pair, variant, connections)
            records.append(record)
            with (root / "raw.jsonl").open("a") as output:
                output.write(json.dumps(record) + "\n")
            print(f"pair={pair} {variant} {record['throughput_mib_s']:.2f} MiB/s peak={record['source_peak']} SHA256=PASS", flush=True)
    summary = {}
    for variant in ["old-cap16", "new-request64"]:
        chosen = [record for record in records if record["variant"] == variant]
        rates = [record["throughput_mib_s"] for record in chosen]
        summary[variant] = {"n": len(chosen), "median_mib_s": statistics.median(rates),
                            "cv": statistics.stdev(rates) / statistics.mean(rates),
                            "source_peaks": [record["source_peak"] for record in chosen],
                            "all_sha256_matched": True}
    summary["speedup"] = summary["new-request64"]["median_mib_s"] / summary["old-cap16"]["median_mib_s"]
    (root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
