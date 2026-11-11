"""Budgeted public SDK/reference comparison; all authentication enters over stdin."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import queue
import socket
import ipaddress
import subprocess
import sys
import threading
import time
from urllib.parse import urlparse

import psutil
from compare_ranges import Wenxi, ResourceSampler

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / '_bench/industrial'))
from traffic_budget import TrafficBudget


class Host:
    def __init__(self, core, cli, origin, log):
        self.log = log.open('wb')
        env = os.environ.copy()
        for key in list(env):
            if key.lower() in ('http_proxy', 'https_proxy', 'all_proxy'):
                env.pop(key)
        env['TURBODL_DEBUG_SCHED'] = '0'
        cp = os.pathsep.join(map(str, (core, ROOT / 'turbodl-core/build/classes/kotlin/test', cli)))
        self.process = subprocess.Popen([r'D:\env\jdk-21.0.12+8\bin\java.exe', '-Xms64m', '-Xmx512m',
            '-cp', cp, 'dev.turbodl.core.NetworkRangeBenchKt', '--origin', origin],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log, env=env,
            text=True, encoding='utf-8', bufsize=1)
        self.responses = queue.Queue()
        self.seq = 0
        def reader():
            for line in self.process.stdout:
                try:
                    self.responses.put(json.loads(line))
                except ValueError:
                    self.responses.put({'ok': False, 'exception': 'InvalidFrame'})
            self.responses.put({'ok': False, 'exception': 'HostClosed'})
        self.thread = threading.Thread(target=reader, daemon=True)
        self.thread.start()

    def download(self, spec, target, connections, minimum, block, observation_ms=None):
        self.seq += 1
        raw = '\0'.join(value for item in spec['headers'].items() for value in item).encode('utf-8')
        fields = [self.seq, spec['url'], target, spec['size'], connections, 4, minimum,
                  base64.b64encode(raw).decode('ascii'), '', block]
        if observation_ms is not None:
            fields.append(observation_ms)
        self.process.stdin.write('\t'.join(map(str, fields)) + '\n')
        self.process.stdin.flush()
        frame = self.responses.get(timeout=190)
        if not frame.get('ok') or frame.get('seq') != self.seq:
            raise RuntimeError(frame.get('exception', 'SDK failed'))
        return frame

    def close(self):
        try:
            if self.process.poll() is None:
                self.process.stdin.write('close\n')
                self.process.stdin.flush()
                self.process.wait(timeout=8)
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait(timeout=5)
            self.thread.join(2)
            self.log.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--private-work', type=Path, required=True)
    parser.add_argument('--scratch', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--cli', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--engines', default='baseline,candidate')
    parser.add_argument('--connections', default='64')
    parser.add_argument('--minimum', type=int, default=256 * 1024)
    parser.add_argument('--block', type=int, default=16 * 1024 * 1024)
    parser.add_argument('--repeats', type=int, default=1)
    parser.add_argument('--observe-seconds', type=int, default=0)
    args = parser.parse_args()
    if not 0 <= args.observe_seconds <= 60 or (args.observe_seconds and 'wenxi' in args.engines):
        parser.error('observation is 1..60 seconds and supported by SDK hosts only')
    spec = json.loads(args.fixture.read_text(encoding='utf-8'))
    parsed = urlparse(spec['url'])
    if parsed.scheme != 'https' or not parsed.hostname.endswith('.quark.cn'):
        raise ValueError('unapproved fixture origin')
    for entry in socket.getaddrinfo(parsed.hostname, 443, type=socket.SOCK_STREAM):
        if not ipaddress.ip_address(entry[4][0]).is_global:
            raise ValueError('download resolved to nonpublic address')
    origin = parsed.scheme + '://' + parsed.netloc
    args.private_work.mkdir(parents=True, exist_ok=True)
    args.scratch.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    budget = TrafficBudget(ROOT / '_bench/results/traffic-budget')
    hosts = {}
    records = []
    deferred_cleanup = []
    try:
        for engine in args.engines.split(','):
            if engine in ('baseline', 'candidate'):
                hosts[engine] = Host(getattr(args, engine), args.cli, origin, args.private_work / (engine + '.log'))
            elif engine == 'wenxi':
                hosts[engine] = Wenxi(Path(r'D:\CodexResearch\wenxi-28e7b457-20261110\wenxi-helper.exe'),
                                      args.private_work / 'wenxi.log')
                hosts[engine].call('open', storageDir=str(args.private_work / 'wenxi-db'),
                                  cacheDir=str(args.private_work / 'wenxi-cache'), key='')
            else:
                raise ValueError('unsupported engine')
        for pair in range(args.repeats):
            for connections in map(int, args.connections.split(',')):
                order = list(hosts) if pair % 2 == 0 else list(reversed(hosts))
                for engine in order:
                    run = f'{args.label}-p{pair}-c{connections}-{engine}'
                    work = args.scratch / run
                    work.mkdir()
                    target = work / 'out.bin'
                    budget.reserve(run, spec['size'] * 2 + 1024 * 1024, {'engine': engine, 'provider': 'quark'})
                    started = time.perf_counter()
                    ok = False
                    frame = {}
                    try:
                        cpu_times = psutil.Process(hosts[engine].process.pid).cpu_times()
                        cpu_before = cpu_times.user + cpu_times.system
                        with ResourceSampler(hosts[engine].process) as resources:
                            if engine == 'wenxi':
                                target.touch()
                                hosts[engine].call('begin', id=run, url=spec['url'], headers=spec['headers'],
                                    connections=connections, retries=3, connectionProfile='generic',
                                    outputPath=str(target), outputKey=run)
                                deadline = time.monotonic() + 180
                                while time.monotonic() < deadline:
                                    state = hosts[engine].call('snapshot', id=run)
                                    if state.get('status') == 'done':
                                        hosts[engine].call('pause', id=run)
                                        break
                                    if state.get('status') == 'error':
                                        raise RuntimeError('reference transfer failed')
                                    time.sleep(.02)
                                else:
                                    hosts[engine].call('pause', id=run)
                                    raise TimeoutError('reference transfer timeout')
                                frame = {'engine_ms': (time.perf_counter() - started) * 1000}
                            else:
                                frame = hosts[engine].download(spec, target, connections, args.minimum, args.block,
                                    args.observe_seconds * 1000 if args.observe_seconds else None)
                        cpu_times = psutil.Process(hosts[engine].process.pid).cpu_times()
                        cpu_seconds = max(0.0, cpu_times.user + cpu_times.system - cpu_before)
                        partial = frame.get('partial') is True
                        if partial:
                            actual = None
                            ok = int(frame.get('observedBytes') or 0) > 0
                        else:
                            with target.open('rb') as stream:
                                actual = hashlib.file_digest(stream, 'sha256').hexdigest()
                            ok = actual == args.sha256 and target.stat().st_size == spec['size']
                        record = {'run': run, 'engine': engine, 'connections': connections,
                                  'minimum': args.minimum, 'block': args.block, 'engine_ms': frame['engine_ms'],
                                  'end_to_end_ms': (time.perf_counter() - started) * 1000,
                                  'sha256': actual, 'hash_matched': ok if not partial else None,
                                  'partial': partial, 'observedBytes': frame.get('observedBytes'),
                                  'rss_peak': resources.rss, 'thread_peak': resources.threads,
                                  'cpu_seconds': cpu_seconds,
                                  'timeToFirstByteMs': frame.get('timeToFirstByteMs'),
                                  'peakActiveRequests': frame.get('peakActiveRequests'),
                                  'disk': str(args.scratch.anchor)}
                    except Exception as error:
                        record = {'run': run, 'engine': engine, 'ok': False, 'error_type': type(error).__name__}
                    finally:
                        # Old versions lack body-byte telemetry; keep their full reservation conservative.
                        received = frame.get('receivedBytes') or (spec['size'] * 2 + 1024 * 1024)
                        budget.reconcile(run, int(received), status='completed' if ok else 'failed')
                        record['accounted_bytes'] = int(received)
                        record['accounting'] = 'measured_body' if frame.get('receivedBytes') else 'conservative_upper_bound'
                        if target.exists():
                            for attempt in range(10):
                                try:
                                    target.unlink()
                                    break
                                except PermissionError:
                                    time.sleep(.1)
                            else:
                                deferred_cleanup.append(target)
                                record['cleanup'] = 'deferred_until_host_close'
                    records.append(record)
                    args.report.write_text(json.dumps(records, indent=2), encoding='utf-8')
                    print(json.dumps(record), flush=True)
                    if deferred_cleanup:
                        raise RuntimeError('sample saved; close host before deleting its locked output')
                    if not ok:
                        raise RuntimeError('sample did not pass independent integrity check')
    finally:
        for host in hosts.values():
            host.close()
        for target in deferred_cleanup:
            target.unlink(missing_ok=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
