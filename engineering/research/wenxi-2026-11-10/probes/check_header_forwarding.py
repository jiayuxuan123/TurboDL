"""Observe credential-header policy between two local fixture origins."""
import argparse
import hashlib
import http.server
import json
from pathlib import Path
import shutil
import sys
import threading
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[4] / '_bench/maintenance'))
from compare_ranges import Wenxi

parser = argparse.ArgumentParser()
parser.add_argument('--evidence-root', type=Path, required=True)
args = parser.parse_args()
root = args.evidence_root.resolve()
root.mkdir(parents=True, exist_ok=True)
work = root / 'header-runtime'
work.mkdir(exist_ok=False)
payload = bytes((i * 17 + (i >> 8)) & 255 for i in range(512 * 1024))
observations = []


class FixtureServer(http.server.ThreadingHTTPServer):
    def handle_error(self, request, address):
        if not isinstance(sys.exception(), (ConnectionResetError, BrokenPipeError)):
            super().handle_error(request, address)


class Front(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(302)
        self.send_header('Location', 'http://localhost:8848/data.bin')
        self.send_header('Content-Length', '0')
        self.end_headers()

    def log_message(self, *_):
        pass


class Target(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        value = self.headers.get('Range', '')
        a, _, b = value.removeprefix('bytes=').partition('-')
        a, b = int(a or 0), int(b or len(payload) - 1)
        observations.append({'range': value, 'authorization_present': 'Authorization' in self.headers,
                             'cookie_present': 'Cookie' in self.headers, 'custom_key_present': 'X-Api-Key' in self.headers})
        self.send_response(206 if value else 200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(b - a + 1))
        if value:
            self.send_header('Content-Range', f'bytes {a}-{b}/{len(payload)}')
        self.end_headers()
        try:
            self.wfile.write(payload[a:b + 1])
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *_):
        pass


servers = [FixtureServer(('127.0.0.1', 8847), Front), FixtureServer(('127.0.0.1', 8848), Target)]
threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in servers]
for thread in threads:
    thread.start()
helper = Wenxi(root.parent / 'wenxi-helper.exe', root / 'header-helper.log')
try:
    helper.call('open', storageDir=str(work / 'db'), cacheDir=str(work / 'cache'), key='')
    helper.call('begin', id='fixture', url='http://127.0.0.1:8847/start.bin', connections=4, retries=0,
                headers={'Authorization': 'Bearer test-fixture-only', 'Cookie': 'fixture=not-an-account',
                         'X-Api-Key': 'synthetic-test-key'})
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        state = helper.call('snapshot', id='fixture')
        if state['status'] == 'done':
            assert hashlib.sha256(Path(state['path']).read_bytes()).digest() == hashlib.sha256(payload).digest()
            break
        if state['status'] == 'error':
            raise AssertionError(state)
        time.sleep(.02)
    else:
        raise AssertionError('fixture did not complete')
    result = {'scope': 'unmodified Windows helper; generic built-in HTTP without a source network policy',
              'all_requests_local': True, 'real_credentials_used': False, 'sha256_matched': True,
              'different_hosts': True, 'observations': observations}
    (root / 'header-result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result))
finally:
    helper.close()
    for server in servers:
        server.shutdown()
        server.server_close()
    for thread in threads:
        thread.join(2)
    if work.resolve().parent != root:
        raise ValueError('refusing cleanup outside the selected evidence directory')
    shutil.rmtree(work)
