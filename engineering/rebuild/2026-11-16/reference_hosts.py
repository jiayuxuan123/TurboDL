"""Local-only adapters for frozen, unmodified reference executables."""
import contextlib
import http.client
import json
import os
from pathlib import Path
import subprocess
import time
import uuid

import psutil


class Aria2Host:
    def __init__(self, executable, directory, log, port=8847):
        self.log = Path(log).open('w', encoding='utf-8')
        self.process = None;self.seq = 0;self.token = uuid.uuid4().hex
        self.api = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
        self.root = Path(directory).resolve();self.root.mkdir()
        env = {key: value for key, value in os.environ.items()
               if key.lower() not in ('http_proxy', 'https_proxy', 'all_proxy')}
        try:
            self.process = subprocess.Popen([str(executable), '--enable-rpc=true',
                '--rpc-listen-all=false', '--rpc-listen-port=' + str(port),
                '--rpc-secret=' + self.token, '--enable-dht=false', '--enable-dht6=false',
                '--bt-enable-lpd=false', '--summary-interval=0', '--console-log-level=warn',
                '--file-allocation=none', '--auto-file-renaming=false', '--all-proxy='],
                cwd=self.root, stdout=self.log, stderr=subprocess.STDOUT, env=env,
                creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            deadline = time.monotonic() + 10
            while True:
                if self.process.poll() is not None:raise RuntimeError('aria2 local RPC exited')
                try:self.info = self.call('getVersion');break
                except (OSError, http.client.HTTPException):
                    self.api.close()
                    if time.monotonic() >= deadline:raise TimeoutError('aria2 RPC startup timed out')
                    time.sleep(.05)
            if self.info.get('version') != '1.37.0':raise RuntimeError('aria2 reference version differs from lock')
        except BaseException:
            self.close();raise

    def call(self, method, *parameters):
        self.seq += 1
        payload = json.dumps({'jsonrpc': '2.0', 'id': self.seq, 'method': 'aria2.' + method,
                              'params': ['token:' + self.token, *parameters]}).encode()
        for attempt in range(2):
            try:
                self.api.request('POST', '/jsonrpc', payload, {'Content-Type': 'application/json'})
                response = self.api.getresponse();body = response.read(2 * 1024 * 1024 + 1)
                break
            except (OSError, http.client.HTTPException):
                self.api.close()
                if attempt or method not in ('getVersion', 'tellStatus'):raise
        if response.status != 200 or len(body) > 2 * 1024 * 1024:raise RuntimeError('aria2 RPC bounds/status failure')
        result = json.loads(body)
        if 'error' in result:raise RuntimeError({'aria2_rpc_error': result['error']})
        return result['result']

    def download(self, url, target, connections, sampler):
        target = Path(target).resolve()
        if not target.is_relative_to(self.root.parent) or not url.startswith('http://127.0.0.1:8845/'):
            raise ValueError('aria2 task must stay inside its localhost fixture')
        process = psutil.Process(self.process.pid);cpu_start = sum(process.cpu_times()[:2])
        started = time.perf_counter();task = None;peak = 0
        try:
            with sampler(self.process) as resources:
                # A read-only round trip renews an idle RPC socket before the
                # non-idempotent submission, which must never be blindly replayed.
                self.call('getVersion')
                task = self.call('addUri', [url], {'dir': str(target.parent), 'out': target.name,
                    'split': str(connections), 'max-connection-per-server': str(min(16, connections)),
                    'min-split-size': '1M', 'allow-overwrite': 'false'})
                while time.perf_counter() - started < 90:
                    status = self.call('tellStatus', task, ['status', 'errorCode', 'connections'])
                    peak = max(peak, int(status.get('connections', 0)))
                    if status['status'] == 'complete':break
                    if status['status'] in ('error', 'removed'):raise RuntimeError({'aria2_task': status})
                    time.sleep(.002)
                else:raise TimeoutError('aria2 transfer exceeded 90 seconds')
                elapsed = (time.perf_counter() - started) * 1000
            timing = {'engine_ms': elapsed, 'reported_peak_connections': peak,
                    'effective_connection_ceiling': min(16, connections),
                    'cpu_seconds': max(0, sum(process.cpu_times()[:2]) - cpu_start),
                    'peak_rss_bytes': resources.rss, 'peak_threads': resources.threads}
        finally:
            if task:
                retirement_started = time.perf_counter()
                with contextlib.suppress(Exception):self.call('removeDownloadResult', task)
        timing['retirement_ms'] = (time.perf_counter() - retirement_started) * 1000
        return timing

    def close(self):
        if self.process is not None and self.process.poll() is None:
            with contextlib.suppress(Exception):self.call('shutdown')
            with contextlib.suppress(subprocess.TimeoutExpired):self.process.wait(timeout=5)
            if self.process.poll() is None:self.process.terminate();self.process.wait(timeout=5)
        self.api.close();self.log.close()


class GopeedHost:
    def __init__(self, executable, directory, allowed_root, log, port=8846):
        self.log = Path(log).open('w', encoding='utf-8')
        self.process = None
        self.api = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
        self.allowed_root = Path(allowed_root).resolve()
        self.token = uuid.uuid4().hex
        env = {key: value for key, value in os.environ.items()
               if key.lower() not in ('http_proxy', 'https_proxy', 'all_proxy')}
        Path(directory).mkdir()
        try:
            self.process = subprocess.Popen(
                [str(executable), '-A', '127.0.0.1', '-P', str(port),
                 '-d', str(directory), '-p', self.token, '-T', self.token,
                 '-w', str(self.allowed_root)],
                cwd=str(directory), stdout=self.log, stderr=subprocess.STDOUT,
                env=env, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            deadline = time.monotonic() + 12
            while True:
                if self.process.poll() is not None:
                    raise RuntimeError('Gopeed local API exited during startup')
                try:
                    self.info = self.call('GET', '/info')
                    break
                except (OSError, http.client.HTTPException):
                    self.api.close()
                    if time.monotonic() >= deadline:
                        raise TimeoutError('Gopeed local API startup timed out')
                    time.sleep(.05)
            if self.info.get('version', '').lstrip('v') != '1.9.3':
                raise RuntimeError('Gopeed reference version does not match its lock')
        except BaseException:
            self.close()
            raise

    def call(self, method, path, data=None):
        if not path.startswith('/') or path.startswith('//'):
            raise ValueError('invalid local API path')
        payload = None if data is None else json.dumps(data).encode()
        for attempt in range(2):
            try:
                self.api.request(method, '/api/v1' + path, body=payload,
                                 headers={'Content-Type': 'application/json', 'X-Api-Token': self.token})
                response = self.api.getresponse()
                body = response.read(2 * 1024 * 1024 + 1)
                break
            except (OSError, http.client.HTTPException):
                self.api.close()
                if attempt or method != 'GET':raise
        if len(body) > 2 * 1024 * 1024 or response.status != 200:
            raise RuntimeError('Gopeed API response failed its bounds/status check')
        result = json.loads(body)
        if result.get('code') != 0:
            raise RuntimeError({'gopeed_error': result.get('code'), 'message': result.get('msg')})
        return result.get('data')

    def download(self, url, target, connections, sampler):
        target = Path(target).resolve()
        if not target.is_relative_to(self.allowed_root) or not url.startswith('http://127.0.0.1:8845/'):
            raise ValueError('reference task must stay inside the local fixture')
        before = psutil.Process(self.process.pid)
        cpu_start = sum(before.cpu_times()[:2])
        started = time.perf_counter()
        task = None
        try:
            with sampler(self.process) as resources:
                self.call('GET', '/info')
                task = self.call('POST', '/tasks', {
                    'req': {'url': url, 'proxy': {'mode': 'none'}},
                    'opts': {'name': target.name, 'path': str(target.parent),
                            'extra': {'connections': connections, 'autoTorrent': False}}})
                if not isinstance(task, str) or not task:
                    raise RuntimeError('Gopeed did not return a task ID')
                while time.perf_counter() - started < 90:
                    state = self.call('GET', '/tasks/' + task)
                    if state.get('status') == 'done':
                        break
                    if state.get('status') == 'error':
                        raise RuntimeError('Gopeed task reported failure')
                    time.sleep(.002)
                else:
                    raise TimeoutError('Gopeed transfer exceeded 90 seconds')
                elapsed = (time.perf_counter() - started) * 1000
            timing = {'engine_ms': elapsed,
                    'cpu_seconds': max(0, sum(before.cpu_times()[:2]) - cpu_start),
                    'peak_rss_bytes': resources.rss, 'peak_threads': resources.threads}
        finally:
            if task:
                retirement_started = time.perf_counter()
                self.call('DELETE', '/tasks/' + task + '?force=false')
        timing['retirement_ms'] = (time.perf_counter() - retirement_started) * 1000
        return timing

    def close(self):
        self.api.close()
        if self.process is not None:
            if self.process.poll() is None:
                self.process.terminate()
            with contextlib.suppress(subprocess.TimeoutExpired):
                self.process.wait(timeout=5)
            if self.process.poll() is None:
                self.process.kill();self.process.wait(timeout=5)
        self.log.close()
