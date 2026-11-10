"""Exercise the original helper's native task queue using only local bytes."""
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
work = root / 'queue-runtime'
work.mkdir(exist_ok=False)
payload = bytes((i * 47 + (i >> 9)) & 255 for i in range(64 * 1024))
expected = hashlib.sha256(payload).hexdigest()
release = threading.Event()
three_started = threading.Event()
lock = threading.Lock()
active = peak = 0


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def do_GET(self):
        global active, peak
        value = self.headers.get('Range', '')
        start, _, end = value.removeprefix('bytes=').partition('-')
        start, end = int(start or 0), int(end or len(payload) - 1)
        self.send_response(206 if value else 200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('ETag', '"queue-probe-v1"')
        self.send_header('Content-Length', str(end - start + 1))
        if value:
            self.send_header('Content-Range', f'bytes {start}-{end}/{len(payload)}')
        self.end_headers()
        data_request = end - start + 1 > 1
        if data_request:
            with lock:
                active += 1
                peak = max(peak, active)
                if active == 3:
                    three_started.set()
        try:
            if data_request:
                release.wait(10)
            self.wfile.write(payload[start:end + 1])
            self.wfile.flush()
        finally:
            if data_request:
                with lock:
                    active -= 1

    def log_message(self, *_):
        pass


server = http.server.ThreadingHTTPServer(('127.0.0.1', 8846), Handler)
thread = threading.Thread(target=server.serve_forever, daemon=True)
thread.start()
helper = Wenxi(root.parent / 'wenxi-helper.exe', root / 'queue-helper.log')
snapshots = []
try:
    helper.call('open', storageDir=str(work / 'db'), cacheDir=str(work / 'cache'), key='')
    for i in range(4):
        helper.call('begin', id=f'task{i}', url=f'http://127.0.0.1:8846/task{i}.bin',
                    connections=1, retries=0)
    assert three_started.wait(5), 'the first three transfers must start'
    time.sleep(.15)
    snapshots = [helper.call('snapshot', id=f'task{i}') for i in range(4)]
    with lock:
        held_peak = peak
    assert held_peak == 3, f'expected native MaxRunning=3, observed {held_peak}'
    assert sum(x['activeConnections'] > 0 for x in snapshots) == 3, snapshots
    release.set()
    for i in range(4):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            state = helper.call('snapshot', id=f'task{i}')
            if state['status'] == 'done':
                assert hashlib.sha256(Path(state['path']).read_bytes()).hexdigest() == expected
                break
            if state['status'] == 'error':
                raise AssertionError(state)
            time.sleep(.02)
        else:
            raise AssertionError('queued transfer did not complete')
    (root / 'queue-result.json').write_text(json.dumps({
        'submitted': 4, 'requested_connections_each': 1, 'native_active_tasks_before_release': held_peak,
        'snapshots_before_release': snapshots, 'completed': 4, 'all_sha256_matched': True,
        'scope': 'local Windows original helper; no account/network provider calls',
    }, indent=2) + '\n', encoding='utf-8')
    print('native task queue: 4 submitted / 3 active / 4 completed / SHA256 PASS')
finally:
    release.set()
    helper.close()
    server.shutdown()
    server.server_close()
    thread.join(2)
    if work.resolve().parent != root:
        raise ValueError('refusing cleanup outside the selected evidence directory')
    shutil.rmtree(work)
