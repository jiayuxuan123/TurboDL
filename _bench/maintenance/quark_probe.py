"""Resolve public, redistributable fixtures; credentials and signed links stay private."""
from __future__ import annotations

import argparse
import ipaddress
import json
import socket
import sys
from pathlib import Path
from urllib.parse import urlparse

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'industrial'))
from traffic_budget import TrafficBudget

API = 'https://drive-pc.quark.cn/1/clouddrive/'
LEGACY_UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) '
             'quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.12-a038f7b798 Safari/537.36 Channel/pckk_other_ch')
MODERN_UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) '
             'Chrome/130.0.0.0 Safari/537.36 QuarkPC/6.9.7.761 QuarkCloudDrivePC/6.9.7.761 quark-cloud-drive/2.5.40')


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--session-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--ledger', type=Path, required=True)
    parser.add_argument('--proxy', default='')
    parser.add_argument('--share', default='e08667c64c7a')
    parser.add_argument('--profile', choices=('legacy', 'modern'), default='legacy')
    args = parser.parse_args()
    session = requests.Session()
    session.trust_env = False
    if args.proxy:
        proxy = urlparse(args.proxy)
        if proxy.scheme != 'http' or proxy.hostname != '127.0.0.1' or proxy.port != 7897:
            raise ValueError('unapproved proxy')
        session.proxies = {'https': args.proxy, 'http': args.proxy}
    credential = json.loads(args.session_file.read_text(encoding='utf-8'))['cookie']
    ua = LEGACY_UA if args.profile == 'legacy' else MODERN_UA
    version = '3.23.2' if args.profile == 'legacy' else '6.9.7.761'
    session.headers.update({'User-Agent': ua, 'Referer': 'https://pan.quark.cn/', 'Cookie': credential})
    budget = TrafficBudget(args.ledger)
    run = 'quark-fixture-preflight-' + args.output.stem
    maximum = 1024 * 1024
    budget.reserve(run, maximum, {'purpose': 'public fixture metadata', 'provider': 'quark'})
    received = 0
    report = {'provider': 'quark', 'profile': args.profile, 'steps': [], 'fixture_count': 0}

    def call(method, url, **kwargs):
        nonlocal received
        host = urlparse(url).hostname
        if host not in ('drive-pc.quark.cn', 'pan.quark.cn') or not url.startswith('https://'):
            raise ValueError('unapproved metadata origin')
        for address in socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM):
            if not ipaddress.ip_address(address[4][0]).is_global:
                raise ValueError('metadata origin resolved to nonpublic address')
        with session.request(method, url, timeout=(5, 10), allow_redirects=False, stream=True, **kwargs) as response:
            chunks = []
            for chunk in response.iter_content(16 * 1024):
                received += len(chunk)
                if received > maximum:
                    raise ValueError('metadata response budget exceeded')
                chunks.append(chunk)
            try:
                body = json.loads(b''.join(chunks))
            except ValueError:
                body = {}
            report['steps'].append({'http': response.status_code, 'status': body.get('status'), 'code': body.get('code')})
            return body

    try:
        account = call('GET', 'https://pan.quark.cn/account/info', headers={'User-Agent': LEGACY_UA})
        report['authenticated'] = account.get('data') is not None and account.get('status') in (None, 200)
        token = call('POST', API + 'share/sharepage/token?pr=ucpro&fr=pc',
                     json={'pwd_id': args.share, 'passcode': '', 'support_visit_limit_private_share': True})
        stoken = (token.get('data') or {}).get('stoken')
        if not stoken:
            report['result'] = 'share_token_unavailable'
            return 2
        found = []
        folders = ['0']
        for _ in range(8):
            if not folders:
                break
            parent = folders.pop(0)
            listing = call('GET', API + 'share/sharepage/detail', params={
                'pr': 'ucpro', 'fr': 'pc', 'pwd_id': args.share, 'stoken': stoken,
                'pdir_fid': parent, 'force': 0, '_page': 1, '_size': 50,
                '_sort': 'file_type:asc,file_name:asc', '_fetch_total': 1})
            for item in (listing.get('data') or {}).get('list', []):
                if item.get('dir') or item.get('file_type') == 0:
                    folders.append(item['fid'])
                else:
                    found.append(item)
        report['fixture_count'] = len(found)
        candidates = sorted(found, key=lambda x: (not (64 * 1024**2 <= x.get('size', 0) <= 128 * 1024**2), x.get('size', 0)))
        if not candidates:
            report['result'] = 'no_public_fixture'
            return 2
        item = candidates[0]
        link = call('POST', API + 'file/download?pr=ucpro&fr=pc&sys=win32&ve=' + version, json={
            'fids': [item['fid']], 'fids_token': [item.get('share_fid_token', '')],
            'pwd_id': args.share, 'stoken': stoken, 'speedup_session': '', 'token': ''})
        data = link.get('data') or []
        if not isinstance(data, list) or not data or not data[0].get('download_url'):
            report['result'] = 'authenticated_download_link_unavailable'
            return 3
        record = {'url': data[0]['download_url'], 'size': item.get('size'), 'name': item.get('file_name'),
                  'md5': item.get('md5'), 'profile': args.profile,
                  'headers': {'Cookie': credential, 'User-Agent': ua, 'Referer': 'https://pan.quark.cn/'}}
        args.output.write_text(json.dumps(record, ensure_ascii=False), encoding='utf-8')
        report.update(result='resolved', size=record['size'], name=record['name'])
        return 0
    except Exception as error:
        report.update(result='request_failed', error_type=type(error).__name__)
        return 4
    finally:
        budget.reconcile(run, received, status='completed' if report.get('result') == 'resolved' else 'failed')
        print(json.dumps(report, ensure_ascii=False))
        session.close()


if __name__ == '__main__':
    raise SystemExit(main())
