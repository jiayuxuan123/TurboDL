"""Interleaved localhost comparison for the native rebuild and frozen references.

This harness is deliberately separate from production scheduling code. It never
uses public network access and never treats a localhost result as parity proof.
"""
import argparse
import ctypes as C
import hashlib
import json
import math
import os
from pathlib import Path
import queue
import shutil
import statistics
import subprocess
import sys
import threading
import time
import uuid

import psutil
from local_run_lock import acquire_local_run_lock
from reference_hosts import GopeedHost, Aria2Host

ROOT = Path(__file__).resolve().parents[3]
BENCH = ROOT / "_bench" / "maintenance"
NATIVE_TESTS = ROOT / "native" / "engine" / "tests"
sys.path.insert(0, str(BENCH))
sys.path.insert(0, str(NATIVE_TESTS))

from compare_ranges import ResourceSampler, Wenxi, digest, get_json  # noqa: E402
from compare_warm_sdk import SdkHost  # noqa: E402
from test_engine import ABI, COMPLETED, FAILED, Options, Snapshot, TaskOptions, load  # noqa: E402

SERVER = "http://127.0.0.1:8845"
EXTERNAL_PROXY_KEYS = ("http_proxy", "https_proxy", "all_proxy")


def clean_environment():
    env = os.environ.copy()
    for key in list(env):
        if key.lower() in EXTERNAL_PROXY_KEYS:
            env.pop(key, None)
    return env


def sha256(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


class NativeHost:
    def __init__(self, journal):
        self.lib = load()
        self.lib.td_task_submit.argtypes = [C.c_void_p, C.POINTER(TaskOptions), C.POINTER(C.c_uint64)]
        self.lib.td_task_forget.argtypes = [C.c_void_p, C.c_uint64]
        options = Options(C.sizeof(Options), ABI, os.fsencode(journal), 1, 256, 128 * 1024 * 1024, 1, 0)
        self.engine = C.c_void_p()
        if self.lib.td_engine_create(C.byref(options), C.byref(self.engine)) != 0:
            raise RuntimeError("native engine initialization failed")

    def download(self, url, target, size, connections, expected):
        key = f"local-{uuid.uuid4().hex}".encode()
        task = TaskOptions(
            C.sizeof(TaskOptions),
            ABI,
            url.encode(),
            str(target).encode(),
            key,
            expected.encode(),
            None,
            0,
            connections,
            256 * 1024,
            1500,
            15000,
        )
        task_id = C.c_uint64()
        if self.lib.td_task_submit(self.engine, C.byref(task), C.byref(task_id)) != 0:
            raise RuntimeError("native task rejected")
        started = time.perf_counter()
        peak = 0
        last = None
        phases = {}
        process = psutil.Process()
        before_cpu = sum(process.cpu_times()[:2])
        with ResourceSampler(process) as resources:
            while time.perf_counter() - started < 90:
                snapshot = Snapshot()
                snapshot.struct_size = C.sizeof(Snapshot)
                snapshot.abi_version = ABI
                if self.lib.td_task_query(self.engine, task_id, C.byref(snapshot)) != 0:
                    raise RuntimeError("native task query failed")
                last = snapshot
                elapsed_ms = (time.perf_counter() - started) * 1000
                phases.setdefault(str(snapshot.state), elapsed_ms)
                if snapshot.total_bytes > 0 and snapshot.downloaded_bytes == snapshot.total_bytes:
                    phases.setdefault("all_bytes_written", elapsed_ms)
                if snapshot.total_bytes > 0 and snapshot.durable_bytes == snapshot.total_bytes:
                    phases.setdefault("all_bytes_durable", elapsed_ms)
                peak = max(peak, snapshot.active_requests)
                if snapshot.state in (COMPLETED, FAILED):
                    break
                time.sleep(0.002)
            else:
                raise TimeoutError("native task exceeded timeout")
        elapsed = (time.perf_counter() - started) * 1000
        try:
            if last.state != COMPLETED:
                message = bytes(last.message).split(b"\0", 1)[0].decode(errors="replace")
                raise RuntimeError(f"native failure state={last.state} error={last.error}: {message}")
        finally:
            if self.lib.td_task_forget(self.engine, task_id) != 0:
                raise RuntimeError("native task retirement failed")
        return {
            "engine_ms": elapsed,
            "observed_phases_ms": phases,
            "reported_requests": int(last.request_count),
            "reported_peak": int(peak),
            "reported_peak_connections": int(peak),
            "cpu_seconds": max(0.0, resources.cpu - before_cpu),
            "peak_rss_bytes": resources.rss,
            "peak_threads": resources.threads,
        }

    def close(self):
        if self.engine:
            if self.lib.td_engine_destroy(self.engine) != 0:
                raise RuntimeError("native engine shutdown failed")
            self.engine = C.c_void_p()


class NativeProcessHost:
    def __init__(self, executable, journal, log):
        self.log = log.open('w', encoding='utf-8')
        self.process = subprocess.Popen([str(executable), str(journal)], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=self.log, text=True,
                                        encoding='utf-8', env=clean_environment(), bufsize=1)
        self.responses = queue.Queue()
        self.seq = 0
        def read():
            for line in self.process.stdout:
                try:self.responses.put(json.loads(line))
                except json.JSONDecodeError:self.responses.put({'error': 'invalid native measurement frame'})
            self.responses.put({'error': 'native measurement host exited'})
        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()

    def download(self, url, target, size, connections, expected):
        self.seq += 1
        process = psutil.Process(self.process.pid)
        before_cpu = sum(process.cpu_times()[:2])
        with ResourceSampler(self.process) as resources:
            self.process.stdin.write('\t'.join(map(str, [self.seq, url, target, connections, size, expected])) + '\n')
            self.process.stdin.flush()
            frame = self.responses.get(timeout=100)
            if frame.get('seq') != self.seq or not frame.get('ok'):
                raise RuntimeError(frame)
        frame.update(cpu_seconds=max(0, sum(process.cpu_times()[:2]) - before_cpu),
                     peak_rss_bytes=resources.rss, peak_threads=resources.threads)
        return frame

    def close(self):
        try:
            if self.process.poll() is None:
                self.process.stdin.write('close\n');self.process.stdin.flush()
                self.process.wait(timeout=5)
        finally:
            if self.process.poll() is None:
                self.process.kill();self.process.wait(timeout=5)
            self.reader.join(2)
            self.process.stdin.close();self.process.stdout.close();self.log.close()


def run_external(command, timeout=90, cwd=None):
    env = clean_environment()
    started = time.perf_counter()
    process = subprocess.Popen(
        [str(item) for item in command],
        cwd=str(cwd) if cwd else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
    )
    before_cpu = sum(psutil.Process(process.pid).cpu_times()[:2])
    with ResourceSampler(process) as resources:
        try:
            stdout, stderr = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            stdout, stderr = process.communicate()
            raise TimeoutError("external engine exceeded timeout")
    elapsed = (time.perf_counter() - started) * 1000
    if process.returncode != 0:
        raise RuntimeError(f"external engine failed rc={process.returncode}: {stderr[-500:]}")
    return {
        "engine_ms": elapsed,
        "cpu_seconds": max(0.0, resources.cpu - before_cpu),
        "peak_rss_bytes": resources.rss,
        "peak_threads": resources.threads,
        "stdout_tail": stdout[-300:],
        "stderr_tail": stderr[-300:],
    }


def run_wenxi(host, url, target, connections, size, expected):
    target.touch()
    task_id = uuid.uuid4().hex
    started = time.perf_counter()
    peak = 0
    before_cpu = sum(psutil.Process(host.process.pid).cpu_times()[:2])
    with ResourceSampler(host.process) as resources:
        host.call(
            "begin",
            id=task_id,
            url=url,
            connections=connections,
            retries=3,
            connectionProfile="generic",
            outputPath=str(target),
            outputKey=task_id,
        )
        while time.perf_counter() - started < 90:
            state = host.call("snapshot", id=task_id)
            peak = max(peak, int(state.get("activeConnections", 0)))
            if state.get("status") in ("done", "completed"):
                break
            if state.get("status") == "error":
                raise RuntimeError(state)
            time.sleep(0.002)
        else:
            raise TimeoutError("wenxi task exceeded timeout")
    elapsed = (time.perf_counter() - started) * 1000
    retirement_started = time.perf_counter()
    try:
        host.call("pause", id=task_id)
    finally:
        host.call("remove", id=task_id)
    return {
        "engine_ms": elapsed,
        "retirement_ms": (time.perf_counter() - retirement_started) * 1000,
        "reported_peak": peak,
        "reported_peak_connections": peak,
        "cpu_seconds": max(0.0, resources.cpu - before_cpu),
        "peak_rss_bytes": resources.rss,
        "peak_threads": resources.threads,
    }


def start_fixture(output):
    node = shutil.which("node")
    if not node:
        raise RuntimeError("node is not available")
    log = (output / "fixture.log").open("w", encoding="utf-8")
    process = subprocess.Popen(
        [node, str(BENCH / "download_profiles.mjs"), "8845"],
        cwd=str(ROOT),
        stdout=log,
        stderr=subprocess.STDOUT,
        env=clean_environment(),
    )
    try:
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("local fixture exited")
            try:
                return process, log, get_json(SERVER + "/info")
            except OSError:
                time.sleep(0.05)
        raise TimeoutError("local fixture did not become ready")
    except BaseException:
        stop_fixture(process, log)
        raise


def stop_fixture(process, log):
    try:
        if process.poll() is None:
            process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    finally:
        log.close()


def ratio_interval(native, reference):
    logs = [math.log(a / b) for a, b in zip(native, reference) if a > 0 and b > 0]
    if not logs:
        return None
    center = statistics.mean(logs)
    if len(logs) == 1:
        return {"ratio": math.exp(center), "low": None, "high": None}
    critical = {1: 12.706, 2: 4.303, 3: 3.182, 4: 2.776, 5: 2.571,
                6: 2.447, 7: 2.365, 8: 2.306}.get(len(logs) - 1, 2.262)
    margin = critical * statistics.stdev(logs) / math.sqrt(len(logs))
    return {"ratio": math.exp(center), "low": math.exp(center - margin), "high": math.exp(center + margin)}


def summarize(records, cases, engines):
    summary = {}
    for case in cases:
        name = case["name"]
        summary[name] = {}
        native_rows = {
            row["pair"]: row
            for row in records
            if row["case"] == name and row["engine"] == "native" and row["ok"]
        }
        for engine in case["engines"]:
            rows = [row for row in records if row["case"] == name and row["engine"] == engine]
            if not rows:
                continue
            successful = [row for row in rows if row["ok"]]
            if len(successful) != len(rows):
                summary[name][engine] = {
                    "runs": len(rows),
                    "failed": len(rows) - len(successful),
                }
                continue
            engine_values = [row["engine_ms"] for row in successful]
            end_to_end_values = [row["end_to_end_ms"] for row in successful]
            median_engine = statistics.median(engine_values)
            median_end_to_end = statistics.median(end_to_end_values)
            cv_engine = (
                statistics.stdev(engine_values) / statistics.mean(engine_values)
                if len(engine_values) > 1 else 0
            )
            cv_end_to_end = (
                statistics.stdev(end_to_end_values) / statistics.mean(end_to_end_values)
                if len(end_to_end_values) > 1 else 0
            )
            actual_peaks = [int(row["server"].get("peak", 0)) for row in rows]
            peak_mismatch_pairs = [
                row["pair"] for row in rows
                if int(row["server"].get("peak", 0)) != case["connections"]
            ]
            comparison = None
            end_to_end_comparison = None
            paired_peak_match = None
            if engine != "native":
                reference_pairs = {
                    row["pair"]: row
                    for row in rows
                    if row["ok"] and row["pair"] in native_rows
                }
                pairs = sorted(reference_pairs)
                paired_native = [native_rows[pair]["engine_ms"] for pair in pairs]
                paired_reference = [reference_pairs[pair]["engine_ms"] for pair in pairs]
                comparison = ratio_interval(paired_native, paired_reference)
                end_to_end_comparison = ratio_interval(
                    [native_rows[pair]["end_to_end_ms"] for pair in pairs],
                    [reference_pairs[pair]["end_to_end_ms"] for pair in pairs],
                )
                paired_peak_match = all(
                    int(native_rows[pair]["server"].get("peak", 0))
                    == int(reference_pairs[pair]["server"].get("peak", 0))
                    for pair in pairs
                )
            same_concurrency = (
                not peak_mismatch_pairs and paired_peak_match is not False
            )
            comparison_warning = None
            if peak_mismatch_pairs:
                comparison_warning = (
                    f"actual peak differs from requested {case['connections']} "
                    f"on pairs {peak_mismatch_pairs}"
                )
            elif paired_peak_match is False:
                comparison_warning = "native/reference actual peak differs within paired runs"
            summary[name][engine] = {
                "runs": len(successful),
                "median_ms": median_engine,
                "median_end_to_end_ms": median_end_to_end,
                "cv": cv_end_to_end,
                "cv_end_to_end_ms": cv_end_to_end,
                "cv_engine_ms": cv_engine,
                "mib_per_second": case["size"] / 1024**2 / (median_engine / 1000),
                "mib_per_second_end_to_end": case["size"] / 1024**2 / (median_end_to_end / 1000),
                "median_requests": statistics.median(row["server"].get("requests", 0) for row in rows),
                "requested_connections": case["connections"],
                "median_server_peak": statistics.median(actual_peaks),
                "actual_peak_connections": {
                    str(row["pair"]): int(row["server"].get("peak", 0)) for row in rows
                },
                "actual_peak_matches_requested": not peak_mismatch_pairs,
                "connection_peak_mismatch_pairs": peak_mismatch_pairs,
                "paired_peak_matches_native": paired_peak_match,
                "same_concurrency": same_concurrency,
                "comparison_eligible": same_concurrency,
                "comparison_warning": comparison_warning,
                "median_cpu_seconds": statistics.median(row["cpu_seconds"] for row in rows if "cpu_seconds" in row) if any("cpu_seconds" in row for row in rows) else None,
                "median_peak_rss_bytes": statistics.median(row["peak_rss_bytes"] for row in rows if "peak_rss_bytes" in row) if any("peak_rss_bytes" in row for row in rows) else None,
                "native_vs_reference_ci": comparison,
                "native_vs_reference_end_to_end_ci": end_to_end_comparison,
            }
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--warmups", type=int, default=1)
    parser.add_argument("--pairs", type=int, default=5)
    parser.add_argument("--max-pairs", type=int, default=9)
    parser.add_argument("--cases", nargs="+", choices=["single", "unlimited", "paced", "paced16", "latency", "tail", "straggler"])
    parser.add_argument("--engines", nargs="+", choices=["native", "sdk", "wenxi", "gopeed", "aria2", "curl"])
    parser.add_argument("--scratch-root", type=Path, default=Path(r"D:\CodexResearch\turbodl-rebuild-20261116\test-runs"))
    parser.add_argument("--metadata", choices=["known", "unknown"], default="unknown")
    parser.add_argument("--java", type=Path, default=Path(r"D:\env\jdk-21.0.12+8\bin\java.exe"))
    parser.add_argument("--sdk-core", type=Path, default=ROOT / "turbodl-core/build/libs/turbodl-core-0.5.0.6.jar")
    parser.add_argument("--sdk-cli", type=Path, default=ROOT / "turbodl-cli/build/libs/turbodl-cli-all.jar")
    parser.add_argument("--wenxi", type=Path, default=Path(r"D:\CodexResearch\turbodl-rebuild-20261116\references\wenxi\unpacked\asterlink_gopeed.exe"))
    parser.add_argument("--aria2", type=Path, default=Path(r"D:\CodexResearch\turbodl-rebuild-20261116\references\aria2\unpacked\aria2-1.37.0-win-64bit-build1\aria2c.exe"))
    parser.add_argument("--gopeed", type=Path, default=Path(r"D:\CodexResearch\turbodl-rebuild-20261116\references\gopeed\unpacked\gopeed-web-v1.9.3-windows-amd64\gopeed.exe"))
    parser.add_argument("--curl", type=Path, default=Path(r"D:\CodexResearch\turbodl-rebuild-20261116\deps\curl-8.23.0_9-win64-mingw\bin\curl.exe"))
    parser.add_argument("--native-library", type=Path, default=Path(r"D:\CodexResearch\turbodl-rebuild-20261116\build-release\turbodl_engine.dll"))
    args = parser.parse_args()
    if not 1 <= args.pairs <= args.max_pairs <= 9 or not 0 <= args.warmups <= 3:
        parser.error('require 1 <= pairs <= max-pairs <= 9 and 0 <= warmups <= 3')

    acquire_local_run_lock("local-engine-comparison")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    scratch_root = args.scratch_root.resolve()
    scratch = scratch_root / ("compare-" + uuid.uuid4().hex)
    for required in (args.java, args.sdk_core, args.sdk_cli, args.wenxi, args.aria2, args.curl, args.native_library):
        if not required.is_file():
            raise FileNotFoundError(required)
    scratch.mkdir(parents=True)
    os.environ["TD_ENGINE_LIBRARY"] = str(args.native_library)

    cases = [
        {"name": "single", "profile": "unlimited", "connections": 1, "engines": ["native", "sdk", "wenxi", "aria2", "curl"]},
        {"name": "unlimited", "profile": "unlimited", "connections": 16, "engines": ["native", "sdk", "wenxi", "aria2"]},
        {"name": "paced", "profile": "paced", "connections": 64, "engines": ["native", "sdk", "wenxi", "aria2"]},
        {"name": "paced16", "profile": "paced", "connections": 16, "engines": ["native", "sdk", "wenxi", "aria2"]},
        {"name": "latency", "profile": "latency", "connections": 8, "engines": ["native", "sdk", "wenxi", "aria2"]},
        {"name": "tail", "profile": "tail", "connections": 2, "engines": ["native", "sdk", "wenxi", "aria2"]},
        {"name": "straggler", "profile": "straggler", "connections": 8, "engines": ["native", "sdk", "wenxi", "aria2"]},
    ]
    for case in cases:
        case['engines'].append('gopeed')
    if args.cases:
        cases = [case for case in cases if case["name"] in args.cases]
    if args.engines:
        for case in cases:
            case["engines"] = [name for name in case["engines"] if name in args.engines]
    fixture_process = fixture_log = native = sdk = wenxi = gopeed = aria2 = None
    tracked_processes = {}
    records = []
    try:
        fixture_process, fixture_log, info = start_fixture(output)
        for case in cases:
            case["size"] = info[case["profile"]]["size"]
            case["sha256"] = info[case["profile"]]["sha256"]
        manifest = {
            "schema": 1,
            "scope": "localhost development comparison; not parity proof",
            "server": SERVER,
            "warmups": args.warmups,
            "initial_pairs": args.pairs,
            "maximum_pairs": args.max_pairs,
            "cases": cases,
            "fixtures": info,
            "scratch_root": str(scratch_root),
            "native_resources_comparable": True,
            "metadata": args.metadata,
            "verification": "native and SDK receive expected SHA-256; every engine independently hashed after completion",
            "primary_comparison_metric": "end_to_end_ms including independent verification",
            "extension_cv_metric": "end_to_end_ms",
            "harness_sha256": sha256(Path(__file__)),
            "reference_adapters_sha256": sha256(Path(__file__).with_name('reference_hosts.py')),
            "fixture_sha256": sha256(BENCH / 'download_profiles.mjs'),
            "range_adapter_sha256": sha256(BENCH / 'compare_ranges.py'),
            "sdk_adapter_sha256": sha256(BENCH / 'compare_warm_sdk.py'),
            "artifacts": {
                "native": {"path": str(args.native_library), "sha256": sha256(args.native_library)},
                "sdk_core": {"path": str(args.sdk_core), "sha256": sha256(args.sdk_core)},
                "sdk_cli": {"path": str(args.sdk_cli), "sha256": sha256(args.sdk_cli)},
                "wenxi": {"path": str(args.wenxi), "sha256": sha256(args.wenxi)},
                "aria2": {"path": str(args.aria2), "sha256": sha256(args.aria2)},
                "curl": {"path": str(args.curl), "sha256": sha256(args.curl)},
                "gopeed": {"path": str(args.gopeed), "sha256": sha256(args.gopeed)},
                "native_host": {"path": str(args.native_library.parent / 'td-transfer-bench.exe'), "sha256": sha256(args.native_library.parent / 'td-transfer-bench.exe')},
                "sdk_host_class_sha256": sha256(ROOT / 'turbodl-core/build/classes/kotlin/test/dev/turbodl/core/WarmRangeBenchKt.class'),
            },
            "notes": [
                "Every request gets a new output and run ID.",
                "Warmups are not recorded.",
                "Native, SDK and Wenxi keep their engine processes alive across requests.",
                "aria2 uses persistent loopback RPC; curl alone is a separately labelled cold-process single-stream baseline.",
                "Independent SHA-256 and exact size are checked before a sample is accepted.",
                "The verified-output timer excludes task retirement and fixture diagnostics.",
                "Requests, actual peak and sent bytes come from the local fixture.",
            ],
        }
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

        native = NativeProcessHost(args.native_library.parent / 'td-transfer-bench.exe', scratch / "native-state", output / 'native.stderr.log')
        sdk = SdkHost(args.java, args.sdk_core, args.sdk_cli, output / "sdk.stderr.log")
        wenxi = Wenxi(args.wenxi, output / "wenxi.stderr.log")
        wenxi.call("open", storageDir=str(scratch / "wenxi-state"), cacheDir=str(scratch / "wenxi-cache"), key="")
        if any('gopeed' in case['engines'] for case in cases):
            gopeed = GopeedHost(args.gopeed, scratch / 'gopeed-state', scratch, output / 'gopeed.log')
            (output / 'gopeed-version.json').write_text(json.dumps(gopeed.info, indent=2) + '\n')
        if any('aria2' in case['engines'] for case in cases):
            aria2 = Aria2Host(args.aria2, scratch / 'aria2-state', output / 'aria2.log')
            (output / 'aria2-version.json').write_text(json.dumps(aria2.info, indent=2) + '\n')
        hosts = {"native": native, "sdk": sdk, "wenxi": wenxi}
        tracked_processes = {
            "fixture": fixture_process,
            "native": native.process,
            "sdk": sdk.process,
            "wenxi": wenxi.process,
            **({"gopeed": gopeed.process} if gopeed is not None else {}),
            **({"aria2": aria2.process} if aria2 is not None else {}),
        }

        def run_one(engine, case, pair):
            run_id = f'{case["profile"]}-c{case["connections"]}-p{pair}-{engine}-{uuid.uuid4().hex[:8]}'
            work = scratch / run_id
            work.mkdir()
            target = work / "result.bin"
            if engine == 'gopeed':
                target = scratch / (run_id + '-result.bin')
            url = f'{SERVER}/{case["profile"]}.bin?run={run_id}'
            sample_started = time.perf_counter()
            try:
                if engine == "native":
                    result = native.download(url, target, case["size"] if args.metadata == 'known' else -1, case["connections"], case["sha256"])
                elif engine == "sdk":
                    process = psutil.Process(sdk.process.pid)
                    before_cpu = sum(process.cpu_times()[:2])
                    sdk.seq += 1
                    sdk.process.stdin.write('\t'.join(map(str, [sdk.seq, url, target, case["size"] if args.metadata == 'known' else -1, case["connections"], 4, 262144, case["sha256"]])) + '\n')
                    sdk.process.stdin.flush()
                    with ResourceSampler(sdk.process) as resources:
                        frame = sdk.responses.get(timeout=90)
                    if frame.get("seq") != sdk.seq or not frame.get("ok"):
                        raise RuntimeError(frame)
                    result = {
                        "engine_ms": frame["engine_ms"],
                        "reported_requests": None,
                        "reported_peak": None,
                        "reported_peak_connections": None,
                        "cpu_seconds": max(0, sum(process.cpu_times()[:2]) - before_cpu),
                        "peak_rss_bytes": resources.rss,
                        "peak_threads": resources.threads,
                    }
                elif engine == "wenxi":
                    result = run_wenxi(wenxi, url, target, case["connections"], case["size"], case["sha256"])
                elif engine == 'gopeed':
                    result = gopeed.download(url, target, case['connections'], ResourceSampler)
                elif engine == "aria2":
                    result = aria2.download(url, target, case['connections'], ResourceSampler)
                elif engine == "curl":
                    result = run_external([
                        args.curl,
                        "--fail",
                        "--location",
                        "--silent",
                        "--show-error",
                        "--output",
                        target,
                        url,
                    ])
                else:
                    raise ValueError(engine)
                hash_started = time.perf_counter()
                if not target.is_file():
                    raise ValueError("engine did not create output")
                actual = sha256(target)
                if target.stat().st_size != case["size"] or actual != case["sha256"]:
                    raise ValueError("output size or SHA-256 mismatch")
                verified_at = time.perf_counter()
                hash_ms = (verified_at - hash_started) * 1000
                stats = get_json(SERVER + "/stats?run=" + run_id)
                return {
                    "ok": True,
                    "case": case["name"],
                    "profile": case["profile"],
                    "connections": case["connections"],
                    "pair": pair,
                    "engine": engine,
                    "size": case["size"],
                    "sha256": actual,
                    "independent_hash_ms": hash_ms,
                    "end_to_end_ms": (verified_at - sample_started) * 1000 - result.get("retirement_ms", 0),
                    "server": stats,
                    **result,
                }
            except Exception as error:
                return {
                    "ok": False,
                    "case": case["name"],
                    "profile": case["profile"],
                    "connections": case["connections"],
                    "pair": pair,
                    "engine": engine,
                    "error": repr(error),
                    "server": get_json(SERVER + "/stats?run=" + run_id) if run_id else {},
                }
            finally:
                if engine == 'gopeed' and target.parent.resolve() == scratch.resolve():
                    target.unlink(missing_ok=True)
                if work.resolve().parent != scratch.resolve():
                    raise RuntimeError("unsafe cleanup target")
                shutil.rmtree(work)

        for pair in range(-args.warmups, 0):
            for case in cases:
                for engine in case["engines"]:
                    result = run_one(engine, case, pair)
                    if not result["ok"]:
                        raise RuntimeError(result)

        pair = 0
        pending_cases = cases
        while pair < args.max_pairs:
            for case_index, case in enumerate(pending_cases):
                engines = case["engines"]
                offset = (pair + case_index) % len(engines)
                ordered = engines[offset:] + engines[:offset]
                for engine in ordered:
                    result = run_one(engine, case, pair)
                    records.append(result)
                    with (output / "raw.jsonl").open("a", encoding="utf-8") as stream:
                        stream.write(json.dumps(result) + "\n")
                    if not result["ok"]:
                        raise RuntimeError(result)
                    requests = result["server"].get("requests", 0)
                    peak = result["server"].get("peak", 0)
                    print(
                        f'{pair} {case["name"]} {engine} '
                        f'engine={result["engine_ms"]:.2f}ms '
                        f'e2e={result["end_to_end_ms"]:.2f}ms '
                        f'requests={requests} peak={peak}/{case["connections"]} SHA256=PASS',
                        flush=True,
                    )
            pair += 1
            summary = summarize(records, cases, [name for case in cases for name in case["engines"]])
            (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
            if pair >= args.pairs:
                pending_cases = [case for case in cases if any(values.get('cv', 1) > .15 for values in summary[case['name']].values())]
                if not pending_cases:
                    break
        summary = summarize(records, cases, [name for case in cases for name in case["engines"]])
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(summary, indent=2))
    finally:
        original_error = sys.exc_info()[1]
        cleanup_errors = []

        def attempt(label, callback):
            try:
                callback()
            except Exception as error:
                cleanup_errors.append(f"{label}: {error!r}")

        for label, host in (("sdk", sdk), ("wenxi", wenxi),
                            ("gopeed", gopeed), ("aria2", aria2), ("native", native)):
            if host is not None:
                attempt(f"close {label}", host.close)
        if fixture_process is not None and fixture_log is not None:
            attempt("stop fixture", lambda: stop_fixture(fixture_process, fixture_log))

        for label, process in tracked_processes.items():
            try:
                if process.poll() is None:
                    cleanup_errors.append(f"{label} process {process.pid} is still running")
            except Exception as error:
                cleanup_errors.append(f"inspect {label} process: {error!r}")

        leftover_children = []
        deadline = time.monotonic() + 2
        while True:
            leftover_children = psutil.Process().children(recursive=True)
            if not leftover_children or time.monotonic() >= deadline:
                break
            time.sleep(0.05)
        for child in leftover_children:
            try:
                cleanup_errors.append(
                    f"untracked child process pid={child.pid} name={child.name()} "
                    f"cmdline={child.cmdline()}"
                )
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                cleanup_errors.append(f"untracked child process pid={child.pid}")

        scratch_cleaned = False
        if scratch.exists():
            try:
                if scratch.resolve().parent != scratch_root:
                    cleanup_errors.append("unsafe scratch cleanup target")
                else:
                    shutil.rmtree(scratch)
            except Exception as error:
                cleanup_errors.append(f"remove scratch: {error!r}")
        scratch_cleaned = not scratch.exists()
        if not scratch_cleaned:
            cleanup_errors.append(f"scratch directory still exists: {scratch}")

        cleanup_record = {
            "ok": not cleanup_errors,
            "errors": cleanup_errors,
            "scratch": str(scratch),
            "scratch_cleaned": scratch_cleaned,
            "tracked_processes": {
                label: process.pid for label, process in tracked_processes.items()
            },
        }
        (output / "cleanup.json").write_text(
            json.dumps(cleanup_record, indent=2) + "\n", encoding="utf-8"
        )
        if cleanup_errors:
            message = "comparison cleanup failed: " + "; ".join(cleanup_errors)
            if original_error is not None:
                raise RuntimeError(message) from original_error
            raise RuntimeError(message)


if __name__ == "__main__":
    main()
