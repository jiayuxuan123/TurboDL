"""Interleave persistent SDK hosts; retain cold-CLI comparisons separately."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import statistics
import subprocess
import threading
import time
import uuid

import psutil
from compare_ranges import ROOT, ResourceSampler, Wenxi, digest, get_json, summarize


class SdkHost:
    def __init__(self, java, core, cli, log):
        classes = ROOT / 'turbodl-core/build/classes/kotlin/test'
        self.log = log.open('w', encoding='utf-8')
        self.command = [str(java), '-Xms64m', '-Xmx512m', '-cp',
                        os.pathsep.join(map(str, [core, classes, cli])),
                        'dev.turbodl.core.WarmRangeBenchKt']
        self.process = subprocess.Popen(self.command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self.log, text=True, encoding='utf-8', bufsize=1)
        self.responses = queue.Queue()
        self.seq = 0

        def read():
            for line in self.process.stdout:
                try:
                    self.responses.put(json.loads(line))
                except json.JSONDecodeError:
                    self.responses.put({'error': 'invalid SDK frame'})
            self.responses.put({'error': 'SDK host closed stdout'})

        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()

    def download(self, url, target, size, connections, pieces, minimum_kib):
        self.seq += 1
        self.process.stdin.write('\t'.join(map(str, [self.seq, url, target, size, connections, pieces, minimum_kib * 1024])) + '\n')
        self.process.stdin.flush()
        frame = self.responses.get(timeout=65)
        if frame.get('seq') != self.seq or not frame.get('ok'):
            raise RuntimeError(frame)
        return frame

    def close(self):
        try:
            if self.process.poll() is None:
                self.process.stdin.write('close\n')
                self.process.stdin.flush()
                self.process.wait(timeout=5)
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait(timeout=5)
            self.reader.join(2)
            self.process.stdin.close()
            self.process.stdout.close()
            self.log.close()


def cpu_total(process):
    cpu = psutil.Process(process.pid).cpu_times()
    return cpu.user + cpu.system


def stop_fixture(process, log):
    try:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=5)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
        log.close()


def main(cleanup):
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=['ab', 'reference'], required=True)
    parser.add_argument('--java', type=Path, default=Path(r'D:\env\jdk-21.0.12+8\bin\java.exe'))
    parser.add_argument('--baseline', type=Path, default=Path(r'D:\CodexResearch\turbodl-maintenance-20261110\before-tail-cost\turbodl-core.jar'))
    version = re.search(r'^\s*version\s*=\s*"([^"]+)"', (ROOT / 'build.gradle.kts').read_text(encoding='utf-8'), re.M).group(1)
    parser.add_argument('--candidate', type=Path, default=ROOT / f'turbodl-core/build/libs/turbodl-core-{version}.jar')
    parser.add_argument('--cli', type=Path, default=Path(r'D:\CodexResearch\turbodl-maintenance-20261110\before-tail-cost\turbodl-cli-all.jar'))
    parser.add_argument('--wenxi', type=Path, default=Path(r'D:\CodexResearch\wenxi-28e7b457-20261110\wenxi-helper.exe'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--warmups', type=int, default=3)
    parser.add_argument('--sdk-spc', type=int, choices=range(1, 65), default=4)
    parser.add_argument('--baseline-spc', type=int, choices=range(1, 65), default=4)
    parser.add_argument('--sdk-min-kib', type=int, choices=[16, 32, 64, 128, 256, 512, 1024], default=256)
    parser.add_argument('--baseline-min-kib', type=int, choices=[16, 32, 64, 128, 256, 512, 1024], default=64)
    parser.add_argument('--cases', nargs='+', choices=['unlimited', 'paced', 'latency', 'tail', 'straggler', 'large'])
    parser.add_argument('--manage-fixture', action='store_true')
    parser.add_argument('--latency-connections', type=int, choices=[4, 8], default=8)
    parser.add_argument('--tail-connections', type=int, choices=[1, 2], default=2)
    parser.add_argument('--smoke', action='store_true')
    parser.add_argument('--diagnostic', action='store_true')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        parser.error('use a fresh evidence directory')
    scratch = (ROOT / '_bench/industrial/.runtime' / ('warm-' + uuid.uuid4().hex)).resolve()
    scratch.mkdir()
    server = 'http://127.0.0.1:8845'
    if args.manage_fixture:
        log = (output / 'fixture.log').open('w', encoding='utf-8')
        try:
            fixture_args = ['node', str(Path(__file__).with_name('download_profiles.mjs')), '8845']
            if args.cases and 'large' in args.cases:
                fixture_args.append('--large')
            fixture = subprocess.Popen(fixture_args,
                                       stdout=log, stderr=subprocess.STDOUT)
        except BaseException:
            log.close()
            raise
        cleanup.callback(stop_fixture, fixture, log)
        deadline = time.monotonic() + 5
        while True:
            if fixture.poll() is not None:
                raise RuntimeError('local fixture failed; see fixture.log')
            try:
                get_json(server + '/info')
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.05)
    info = get_json(server + '/info')
    labels = ['baseline', 'candidate'] if args.mode == 'ab' else ['candidate', 'wenxi']
    cases = [('unlimited', 16), ('paced', 64), ('latency', args.latency_connections),
             ('tail', args.tail_connections), ('straggler', 8), ('large', 16)]
    if args.cases:
        cases = [(name, connections) for name, connections in cases if name in args.cases]
    else:
        cases = [(name, connections) for name, connections in cases if name != 'large']
    if args.smoke:
        cases = [('tail', 2)]
    manifest = {'mode': args.mode, 'runtime': 'persistent SDK hosts, process initialization excluded',
        'warmups_per_case_per_engine': args.warmups, 'cases': cases,
        'max_measured_pairs': 1 if args.diagnostic else 9, 'diagnostic_only': args.diagnostic,
        'jdk': '21.0.12', 'go': '1.25.7', 'reference_commit': '28e7b4576f5c3a600a44240967c03e21ef579a1f',
        'poll_interval_ms': 2, 'network': 'loopback only', 'sdk_segments_per_connection': args.sdk_spc,
        'baseline_segments_per_connection': args.baseline_spc,
        'sdk_min_segment_kib': args.sdk_min_kib, 'baseline_min_segment_kib': args.baseline_min_kib,
        'fixtures': info,
        'artifacts': {key: {'path': str(path), 'sha256': digest(path)} for key, path in [
            ('baseline', args.baseline), ('candidate', args.candidate), ('cli', args.cli), ('wenxi', args.wenxi)]},
        'notes': ['SDK and Go core persist across requests, matching an application host.',
                  'engine_ms excludes the independent final hash; end_to_end_ms includes it for every engine.',
                  'Per-task CPU is the whole host CPU delta; RSS is sampled whole-host resident memory.',
                  'SDK remains adaptive; no explicit slow start or connection prewarm. Native Go policy is unmodified.',
                  'Each result uses a new task/output; bytes and work files are removed after SHA verification.']}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    hosts = {}
    records = []
    try:
        for label in labels:
            if label == 'wenxi':
                host = Wenxi(args.wenxi, output / 'wenxi.stderr.log')
                hosts[label] = host
                host.call('open', storageDir=str(scratch / 'native-db'), cacheDir=str(scratch / 'native-cache'), key='')
            else:
                hosts[label] = SdkHost(args.java, args.baseline if label == 'baseline' else args.candidate,
                                       args.cli, output / (label + '.stderr.log'))
        rounds = range(-args.warmups, 0 if args.smoke else (1 if args.diagnostic else 9))
        for pair in rounds:
            for profile, connections in cases:
                for label in (labels if pair % 2 == 0 else labels[::-1]):
                    name = f'{profile}-c{connections}-p{pair}-{label}-{uuid.uuid4().hex[:8]}'
                    work = scratch / name
                    work.mkdir()
                    target = work / 'result.bin'
                    host = hosts[label]
                    url = f'{server}/{profile}.bin?run={name}'
                    before_cpu = cpu_total(host.process)
                    started = time.perf_counter()
                    observed_peak = 0
                    try:
                        with ResourceSampler(host.process) as resources:
                            if label == 'wenxi':
                                target.touch()
                                host.call('begin', id=name, url=url, connections=connections, retries=3,
                                          outputPath=str(target), outputKey=name)
                                deadline = time.monotonic() + 60
                                while time.monotonic() < deadline:
                                    state = host.call('snapshot', id=name)
                                    observed_peak = max(observed_peak, state.get('activeConnections', 0))
                                    if state['status'] == 'done':
                                        host.call('pause', id=name)
                                        break
                                    if state['status'] == 'error':
                                        raise RuntimeError(state)
                                    time.sleep(.002)
                                else:
                                    raise TimeoutError('reference download exceeded 60 seconds')
                                engine_ms = (time.perf_counter() - started) * 1000
                            else:
                                pieces = args.baseline_spc if label == 'baseline' else args.sdk_spc
                                minimum = args.baseline_min_kib if label == 'baseline' else args.sdk_min_kib
                                frame = host.download(url, target, info[profile]['size'], connections, pieces, minimum)
                                engine_ms = frame['engine_ms']
                            hash_start = time.perf_counter()
                            actual = digest(target)
                            hash_ms = (time.perf_counter() - hash_start) * 1000
                            elapsed_ms = (time.perf_counter() - started) * 1000
                        if target.stat().st_size != info[profile]['size'] or actual != info[profile]['sha256']:
                            raise ValueError('payload SHA-256/size mismatch')
                        stats = get_json(server + '/stats?run=' + name)
                        record = {'engine': label, 'pair': pair, 'profile': profile, 'connections': connections,
                            'engine_ms': engine_ms, 'wall_ms': elapsed_ms, 'end_to_end_ms': elapsed_ms,
                            'hash_ms': hash_ms, 'cpu_seconds': max(0, resources.cpu - before_cpu),
                            'peak_rss_bytes': resources.rss, 'peak_threads': resources.threads,
                            'hash_matched': True, 'sha256': actual, 'size': info[profile]['size'], 'server': stats,
                            'reported_peak_connections': observed_peak,
                            'mib_per_second': info[profile]['size'] / 1024**2 / (engine_ms / 1000)}
                        records.append(record)
                        with (output / 'raw.jsonl').open('a', encoding='utf-8') as stream:
                            stream.write(json.dumps(record) + '\n')
                        print(f'pair={pair} {profile} c={connections} {label} ms={engine_ms:.2f} requests={stats["requests"]} SHA256=PASS', flush=True)
                        if label == 'wenxi':
                            host.call('remove', id=name)
                    finally:
                        if work.resolve().parent != scratch:
                            raise ValueError('unsafe cleanup target')
                        shutil.rmtree(work)
            summary = summarize(records)
            (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
            if pair >= 4 and all(item[label]['cv'] <= .15 for item in summary.values() for label in labels):
                break
        print(json.dumps(summarize(records), indent=2))
    finally:
        for host in hosts.values():
            host.close()
        expected_parent = (ROOT / '_bench/industrial/.runtime').resolve()
        if scratch.parent != expected_parent:
            raise ValueError('unsafe scratch root')
        shutil.rmtree(scratch)


if __name__ == '__main__':
    with contextlib.ExitStack() as cleanup:
        main(cleanup)
