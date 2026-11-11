"""One ordinary-download baseline. The curl config travels over stdin, never argv."""
import argparse
import hashlib
import ipaddress
import json
import subprocess
import socket
import sys
import time
from pathlib import Path
from urllib.parse import urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'industrial'))
from traffic_budget import TrafficBudget


def quote(value):
    if '\n' in value or '\r' in value:
        raise ValueError('newline in private request config')
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--ledger', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--proxy', default='')
    parser.add_argument('--max-seconds', type=int, default=180)
    args = parser.parse_args()
    if not 1 <= args.max_seconds <= 180:
        parser.error('max-seconds must be 1..180')
    spec = json.loads(args.fixture.read_text(encoding='utf-8'))
    host = urlparse(spec['url'])
    if host.scheme != 'https' or not host.hostname.endswith('.quark.cn'):
        raise ValueError('unexpected download origin')
    if any(not ipaddress.ip_address(a[4][0]).is_global
           for a in socket.getaddrinfo(host.hostname, 443, type=socket.SOCK_STREAM)):
        raise ValueError('download resolved to nonpublic address')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    budget = TrafficBudget(args.ledger)
    budget.reserve(args.run_id, spec['size'] + 1024 * 1024, {'engine': 'curl', 'provider': 'quark'})
    config = 'url = ' + quote(spec['url']) + '\n'
    for key, value in spec['headers'].items():
        config += 'header = ' + quote(key + ': ' + value) + '\n'
    command = ['curl.exe', '--config', '-', '--output', str(args.output), '--write-out', '%{json}',
               '--max-time', str(args.max_seconds), '--silent', '--show-error', '--fail']
    if args.proxy:
        command += ['--proxy', args.proxy]
    started = time.perf_counter()
    if not args.proxy:
        command += ['--noproxy', '*']
    result = subprocess.run(command, input=config.encode('utf-8'), capture_output=True, timeout=args.max_seconds + 10)
    try:
        info = json.loads((result.stdout or b'').decode('utf-8', errors='replace'))
    except (ValueError, TypeError):
        info = {}
    received = int(info.get('size_download', args.output.stat().st_size if args.output.exists() else spec['size']))
    budget.reconcile(args.run_id, received, status='completed' if result.returncode == 0 else 'failed')
    digest = None
    if args.output.exists():
        with args.output.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    report = {'engine': 'curl', 'exit': result.returncode, 'http': info.get('http_code'), 'bytes': received,
              'elapsed_ms': (time.perf_counter() - started) * 1000, 'transfer_seconds': info.get('time_total'),
              'first_byte_seconds': info.get('time_starttransfer'), 'speed_bytes_per_sec': info.get('speed_download'),
              'partial': result.returncode != 0, 'sha256': digest,
              'hash_matched': digest == args.sha256 if result.returncode == 0 else None}
    if args.output.exists():
        args.output.unlink()
    print(json.dumps(report))
    return 0 if result.returncode == 0 and digest == args.sha256 else 1


if __name__ == '__main__':
    raise SystemExit(main())
