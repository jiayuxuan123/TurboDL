"""Local paired Range comparisons, including the unmodified Wenxi helper.

Raw evidence goes on D: by default; small speed-sensitive outputs use the same
SSD directory and are deleted after every SHA-256 check.
"""

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import queue
import random
import shutil
import statistics
import subprocess
import threading
import time
import urllib.request
import uuid

import psutil

ROOT = Path(__file__).resolve().parents[2]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def percentile(values, p):
    values = sorted(values)
    position = (len(values) - 1) * p
    lo, hi = math.floor(position), math.ceil(position)
    return values[lo] + (values[hi] - values[lo]) * (position - lo)


def get_json(url):
    # Bypass desktop proxy settings for this explicitly local fixture.
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(url, timeout=5) as response:
        return json.load(response)


class ResourceSampler:
    def __init__(self, process):
        self.process = psutil.Process(process.pid)
        self.rss = self.threads = 0
        self.cpu = 0.0
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.sample, daemon=True)

    def sample(self):
        while not self.stop.is_set():
            try:
                self.rss = max(self.rss, self.process.memory_info().rss)
                self.threads = max(self.threads, self.process.num_threads())
                cpu = self.process.cpu_times()
                self.cpu = max(self.cpu, cpu.user + cpu.system)
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                return
            self.stop.wait(.01)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stop.set()
        self.thread.join(2)


class Wenxi:
    def __init__(self, executable, log):
        self.stderr = log.open("w", encoding="utf-8")
        self.process = subprocess.Popen([str(executable)], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self.stderr, text=True, encoding="utf-8", bufsize=1)
        self.responses = queue.Queue()
        self.seq = 0

        def read():
            for line in self.process.stdout:
                try:
                    self.responses.put(json.loads(line))
                except json.JSONDecodeError:
                    self.responses.put({"error": "invalid JSON output"})
            self.responses.put({"error": "helper closed stdout"})

        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()

    def call(self, method, **args):
        self.seq += 1
        self.process.stdin.write(json.dumps({"seq": self.seq, "method": method, "args": args}) + "\n")
        self.process.stdin.flush()
        response = self.responses.get(timeout=15)
        if response.get("error") or response.get("seq") != self.seq:
            raise RuntimeError(response)
        return response.get("result")

    def close(self):
        try:
            if self.process.poll() is None:
                self.call("close")
                self.process.wait(timeout=5)
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait(timeout=5)
            self.reader.join(2)
            self.process.stdin.close()
            self.process.stdout.close()
            self.stderr.close()


def sample(args, label, profile, connections, pair, info, output, scratch):
    name = f"{profile}-c{connections}-p{pair}-{label}-{uuid.uuid4().hex[:8]}"
    work = scratch / name
    work.mkdir()
    target = work / "result.bin"
    url = f"{args.server}/{profile}.bin?run={name}"
    begun = time.perf_counter()
    completed = False
    extra = {}
    try:
        if label == "wenxi":
            # The desktop/Android host owns and creates the direct output handle.
            target.touch()
            helper = Wenxi(args.wenxi, output / (name + ".stderr.log"))
            try:
                with ResourceSampler(helper.process) as resources:
                    helper.call("open", storageDir=str(work / "db"), cacheDir=str(work / "cache"), key="")
                    started = time.perf_counter()
                    helper.call("begin", id="benchmark", url=url, connections=connections, retries=3,
                                connectionProfile="generic", outputPath=str(target), outputKey="benchmark")
                    peak = 0
                    while time.perf_counter() - started < 60:
                        state = helper.call("snapshot", id="benchmark")
                        peak = max(peak, state.get("activeConnections", 0))
                        if state.get("status") in ("done", "completed"):
                            break
                        if state.get("status") == "error":
                            raise RuntimeError(state)
                        time.sleep(.02)
                    else:
                        raise TimeoutError("Wenxi download exceeded 60 seconds")
                    engine_ms = (time.perf_counter() - started) * 1000
                    extra = {"snapshot": state, "reported_peak_connections": peak,
                             "poll_interval_ms": 20}
            finally:
                helper.close()
            completed = helper.process.returncode == 0
        else:
            core = args.baseline if label == "baseline" else args.candidate
            command = [str(args.java), "-Xms64m", "-Xmx512m", "-cp", os.pathsep.join([str(core), str(args.cli)]),
                       "dev.turbodl.cli.MainKt", url, "-o", str(target), "-c", str(connections),
                       "--known-size", str(info["size"]),
                       "--no-warmup", "--no-slow-start", "--adaptive", "--timing", "--spc",
                       "1" if profile in ("latency", "tail") else "4"]
            if profile == "latency":
                command.append("--no-tail-assist")
            log = output / (name + ".stdout.log")
            with log.open("w", encoding="utf-8") as stream:
                process = subprocess.Popen(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
                try:
                    with ResourceSampler(process) as resources:
                        code = process.wait(timeout=60)
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.wait(timeout=5)
            events = []
            for line in log.read_text(encoding="utf-8").splitlines():
                try:
                    events.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
            ends = [event for event in events if event.get("type") == "completed"]
            completed = code == 0 and len(ends) == 1 and ends[0].get("sha256") == info["sha256"]
            timings = [event for event in events if event.get("type") == "timing"]
            marks = {event.get("state"): event["t_ms"] for event in timings if event.get("stage") == "state"}
            engine_ms = marks.get("COMPLETED")
            if engine_ms is None:
                raise RuntimeError(f"completion timing missing: {log}")
            extra = {"timings": timings, "reported_peak_connections": max(
                [event.get("activeConnections", 0) for event in events if event.get("type") == "progress"] or [0])}
        wall_ms = (time.perf_counter() - begun) * 1000
        if not completed or not target.is_file() or digest(target) != info["sha256"]:
            raise RuntimeError(f"failed download or SHA-256 mismatch: {name}")
        stats = get_json(f"{args.server}/stats?run={name}")
        record = {"engine": label, "pair": pair, "profile": profile, "connections": connections,
                  "size": info["size"], "sha256": info["sha256"], "hash_matched": True,
                  "engine_ms": engine_ms, "wall_ms": wall_ms, "peak_rss_bytes": resources.rss,
                  "cpu_seconds": resources.cpu, "peak_threads": resources.threads, "server": stats,
                  "mib_per_second": info["size"] / 1024**2 / (engine_ms / 1000), **extra}
        with (output / "raw.jsonl").open("a", encoding="utf-8") as raw:
            raw.write(json.dumps(record) + "\n")
        return record
    finally:
        # This unique directory was created above under the designated benchmark root.
        if work.parent.resolve() != scratch.resolve():
            raise ValueError("refusing cleanup outside benchmark scratch")
        shutil.rmtree(work)


def summarize(records):
    groups = {}
    for record in records:
        if record["pair"] < 0:
            continue
        key = f"{record['profile']}-c{record['connections']}"
        groups.setdefault(key, {}).setdefault(record["engine"], []).append(record)
    result = {}
    for key, engines in groups.items():
        result[key] = {}
        for engine, rows in engines.items():
            times = [row["engine_ms"] for row in rows]
            cv = statistics.stdev(times) / statistics.mean(times) if len(times) > 1 else 0
            result[key][engine] = {"n": len(times), "p50_ms": statistics.median(times),
                "p95_ms": percentile(times, .95), "cv": cv,
                "peak_rss_p50_bytes": statistics.median(row["peak_rss_bytes"] for row in rows),
                "cpu_p50_seconds": statistics.median(row["cpu_seconds"] for row in rows),
                "server_peak": sorted(set(row["server"]["peak"] for row in rows)),
                "requests_p50": statistics.median(row["server"]["requests"] for row in rows),
                "all_sha256_matched": all(row["hash_matched"] for row in rows),
                "timing_evidence": "CONTROLLED_LOCAL_STABLE" if cv <= .15 and len(times) >= 5 else "INCONCLUSIVE"}
        labels = list(engines)
        if len(labels) == 2:
            first, second = labels
            pairs = {label: {row["pair"]: row["engine_ms"] for row in engines[label]} for label in labels}
            ratios = [value / pairs[second][pair] for pair, value in pairs[first].items()]
            rng = random.Random(741)
            boot = [statistics.median(rng.choices(ratios, k=len(ratios))) for _ in range(5000)]
            result[key]["comparison"] = {"ratio": f"{first}_ms / {second}_ms",
                "p50_ratio": result[key][first]["p50_ms"] / result[key][second]["p50_ms"],
                "paired_ratio_95ci": [percentile(boot, .025), percentile(boot, .975)]}
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["ab", "wenxi", "smoke"], required=True)
    parser.add_argument("--java", type=Path, default=Path(r"D:\env\jdk-21.0.12+8\bin\java.exe"))
    parser.add_argument("--cli", type=Path, default=ROOT / "turbodl-cli/build/libs/turbodl-cli-all.jar")
    parser.add_argument("--baseline", type=Path, default=Path(r"D:\CodexResearch\turbodl-maintenance-20261110\baseline\turbodl-core-0.5.0.1.jar"))
    parser.add_argument("--candidate", type=Path, default=ROOT / "turbodl-core/build/libs/turbodl-core-0.5.0.1.jar")
    parser.add_argument("--wenxi", type=Path, default=Path(r"D:\CodexResearch\wenxi-28e7b457-20261110\wenxi-helper.exe"))
    parser.add_argument("--server", default="http://127.0.0.1:8845")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, default=ROOT / "_bench/industrial/.runtime/range-maintenance")
    args = parser.parse_args()
    if args.server != "http://127.0.0.1:8845":
        parser.error("this runner only supports the local, non-metered fixture on port 8845")
    output, scratch = args.output.resolve(), args.scratch.resolve()
    output.mkdir(parents=True, exist_ok=True)
    scratch.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        parser.error("use a fresh evidence directory")
    info = get_json(args.server + "/info")
    variants = ["baseline", "candidate"] if args.mode == "ab" else ["candidate", "wenxi"]
    cases = [("latency", 8), ("tail", 2), ("unlimited", 16), ("paced", 64)] if args.mode == "ab" else [("unlimited", 16), ("paced", 64)]
    if args.mode == "smoke":
        variants, cases = ["wenxi"], [("latency", 8)]
    paths = {"cli": args.cli, "baseline": args.baseline, "candidate": args.candidate, "wenxi": args.wenxi}
    manifest = {"mode": args.mode, "local_server": args.server, "cases": cases,
        "artifacts": {name: {"path": str(path), "sha256": digest(path)} for name, path in paths.items()},
        "head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "source": {name: digest(ROOT / name) for name in [
            "turbodl-core/src/main/kotlin/dev/turbodl/core/SegmentDownloader.kt",
            "turbodl-core/src/main/kotlin/dev/turbodl/core/SegmentScheduler.kt"]},
        "limits": {"max_pairs": 9, "cv_cutoff": .15, "per_sample_timeout_seconds": 60},
        "notes": ["Wenxi includes probe and 20ms snapshot polling; JVM process startup is reported separately in wall_ms.",
                  "engine_ms ends at engine completion; all outputs then receive an independent SHA-256 check.",
                  "RSS/CPU cover the entire child process, not just transfer or Android runtime.",
                  "Latency A/B disables tail assistance and uses one initial segment per connection.",
                  "Payload files are deleted after each independent SHA-256 check; no public traffic."]}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    records = []
    for pair in range(-1, 9 if args.mode != "smoke" else 0):
        for profile, connections in cases:
            order = variants if pair % 2 == 0 else variants[::-1]
            for label in order:
                row = sample(args, label, profile, connections, pair, info[profile], output, scratch)
                records.append(row)
                print(f"pair={pair} {profile} c={connections} {label} engine_ms={row['engine_ms']:.1f} SHA256=PASS", flush=True)
        summary = summarize(records)
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        if pair >= 4 and all(item[engine]["cv"] <= .15 for item in summary.values() for engine in variants):
            break
    print(json.dumps(summarize(records), indent=2))


if __name__ == "__main__":
    main()
