"""Interleave frozen and candidate HLS backends with the same benchmark main."""

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import random
import re
import statistics
import subprocess
import time

import psutil


ROOT = Path(__file__).resolve().parents[2]
EXPECTED = {
    "plain-many-parts": "8cc7a8a7ea8aeddef62606396378097a348f1e7c7365bcaa8bb46a677d88022d",
    "aes-large-part": "127bb2be7cfaa0e40f04ed29ab99227a63f73f615936d668b67f6efcf78606e3",
    "aes-rotating-keys": "bb97456036cea720201e6d92712faf90357fef35fe453d8d768dd80b6acc9895",
}


def percentile(values, fraction):
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    low, high = math.floor(position), math.ceil(position)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def run_one(java, classpath, output, label, pair):
    stem = output / f"{pair:02}-{label}"
    sample_file = stem.with_suffix(".json")
    log_file = stem.with_suffix(".log")
    classes_file = stem.with_suffix(".classes.log")
    class_log = classes_file.relative_to(ROOT).as_posix()
    command = [str(java), "-Xms128m", "-Xmx512m", f"-Xlog:class+load=info:file={class_log}",
               "-cp", classpath, "dev.turbodl.plugin.hls.HlsMaintenanceBenchKt", str(sample_file), "1"]
    peak_rss, peak_threads, cpu_seconds = 0, 0, 0.0
    started = time.perf_counter()
    with log_file.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        observed = psutil.Process(process.pid)
        try:
            while process.poll() is None:
                if time.perf_counter() - started > 60:
                    process.kill()
                    raise TimeoutError(f"{label} pair {pair} exceeded 60 seconds")
                try:
                    peak_rss = max(peak_rss, observed.memory_info().rss)
                    peak_threads = max(peak_threads, observed.num_threads())
                    cpu = observed.cpu_times()
                    cpu_seconds = max(cpu_seconds, cpu.user + cpu.system)
                except psutil.NoSuchProcess:
                    break
                time.sleep(0.01)
        finally:
            if process.poll() is None:
                process.kill()
            code = process.wait()
    if code:
        raise RuntimeError(f"{label} pair {pair} exited {code}: {log_file}")
    samples = json.loads(sample_file.read_text(encoding="utf-8"))
    if {sample["scenario"] for sample in samples} != set(EXPECTED) or len(samples) != 3:
        raise ValueError(f"missing or duplicated scenarios: {sample_file}")
    for sample in samples:
        if sample["sha256"] != EXPECTED[sample["scenario"]]:
            raise ValueError(f"wrong output SHA-256: {sample_file}")
    sources = {}
    text = classes_file.read_text(encoding="utf-8")
    for name in ["dev.turbodl.core.TurboConfig", "dev.turbodl.plugin.hls.HlsBackend"]:
        match = re.search(r"\b" + re.escape(name) + r" source: (.+)", text)
        if match is None:
            raise ValueError(f"no loaded-code provenance for {name}")
        sources[name] = match.group(1).strip()
        frozen = "maintenance-20261109/baseline-jars/" in sources[name]
        if frozen != (label == "baseline"):
            raise ValueError(f"wrong {label} implementation loaded: {sources[name]}")
    return {"variant": label, "pair": pair, "exit_code": code, "wall_ms": (time.perf_counter() - started) * 1000,
            "peak_rss_bytes": peak_rss, "peak_threads": peak_threads, "process_cpu_seconds": cpu_seconds,
            "sources": sources, "samples": samples, "command": command}


def summarize(records):
    result = {}
    for scenario in EXPECTED:
        variants = {}
        paired = {}
        for label in ["baseline", "candidate"]:
            samples = [(record["pair"], sample) for record in records if record["variant"] == label
                       for sample in record["samples"] if sample["scenario"] == scenario]
            times = [sample["elapsed_ms"] for _, sample in samples]
            allocations = [sample["allocated_bytes"] for _, sample in samples]
            variants[label] = {"n": len(times), "p50_ms": statistics.median(times), "p95_ms": percentile(times, .95),
                               "cv": statistics.stdev(times) / statistics.mean(times) if len(times) > 1 else 0,
                               "allocated_p50_bytes": statistics.median(allocations),
                               "key_peak": sorted(set(sample["key_peak"] for _, sample in samples)),
                               "key_requests": sorted(set(sample["key_requests"] for _, sample in samples))}
            for pair, sample in samples:
                paired.setdefault(pair, {})[label] = sample["elapsed_ms"]
        ratios = [pair["baseline"] / pair["candidate"] for pair in paired.values()]
        generator = random.Random(741)
        boot = [statistics.median(generator.choices(ratios, k=len(ratios))) for _ in range(5000)]
        result[scenario] = {**variants, "p50_speedup": variants["baseline"]["p50_ms"] / variants["candidate"]["p50_ms"],
                            "paired_speedup_95ci": [percentile(boot, .025), percentile(boot, .975)],
                            "timing_evidence": "CONTROLLED_LOCAL_STABLE" if len(ratios) >= 5 and all(variants[label]["cv"] <= .15 for label in variants) else "INCONCLUSIVE_HIGH_VARIANCE",
                            "allocation_reduction": 1 - variants["candidate"]["allocated_p50_bytes"] / variants["baseline"]["allocated_p50_bytes"],
                            "sha256": EXPECTED[scenario]}
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--classpath", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("use a fresh output directory; previous evidence must be retained")
    candidate_cp = args.classpath.read_text(encoding="utf-8").strip()
    baseline_root = ROOT / "_bench/results/maintenance-20261109/baseline-jars"
    jars = [baseline_root / name for name in ["turbodl-core-0.5.0.1.jar", "turbo-plugin-hls-0.5.0.1.jar"]]
    classpaths = {"baseline": os.pathsep.join([*(str(path) for path in jars), candidate_cp]), "candidate": candidate_cp}
    source_files = [ROOT / name for name in [
        "turbodl-core/src/main/kotlin/dev/turbodl/core/HttpClientFactory.kt",
        "turbo-plugin-hls/src/main/kotlin/dev/turbodl/plugin/hls/HlsBackend.kt",
        "turbo-plugin-hls/src/main/kotlin/dev/turbodl/plugin/hls/HlsWorkBuffers.kt",
        "turbo-plugin-hls/src/test/kotlin/dev/turbodl/plugin/hls/HlsMaintenanceBench.kt",
    ]]
    manifest = {"head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                "baseline_jars": {path.name: digest(path) for path in jars},
                "candidate_source": {path.relative_to(ROOT).as_posix(): digest(path) for path in source_files},
                "java": str(args.java), "heap": "128m initial / 512m max", "network": "local loopback only",
                "allocation_scope": "HotSpot live-thread allocated-byte deltas; not RSS",
                "rss_scope": "sampled whole child JVM, includes warmups and fixture generation"}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    records = []
    for pair in range(9):
        for label in (["baseline", "candidate"] if pair % 2 == 0 else ["candidate", "baseline"]):
            record = run_one(args.java, classpaths[label], output, label, pair)
            records.append(record)
            with (output / "raw.jsonl").open("a", encoding="utf-8") as raw:
                raw.write(json.dumps(record) + "\n")
            print(f"pair={pair} variant={label} wall_ms={record['wall_ms']:.1f} all_sha256=PASS", flush=True)
        summary = summarize(records)
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        if pair >= 4 and all(entry[label]["cv"] <= .15 for entry in summary.values() for label in ["baseline", "candidate"]):
            break
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
