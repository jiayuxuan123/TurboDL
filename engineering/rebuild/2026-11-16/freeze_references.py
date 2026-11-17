"""Freeze official comparison refs; remote asset digests are not local verification."""
import json
from pathlib import Path
import subprocess
import sys
import urllib.parse

ROOT=Path(__file__).resolve().parents[3]
TARGET=Path(sys.argv[1]).resolve()
RUN=sys.argv[2] if len(sys.argv)>2 else 'rebuild-reference-lock-20261117'
GH='C:/Program Files/GitHub CLI/gh.exe'
LEDGER=ROOT/'_bench/industrial/traffic_budget.py'
REFERENCES=[('aria2','aria2/aria2','release-1.37.0'),('gopeed','GopeedLab/gopeed','v1.9.3'),('wenxi','z7786/wenxi','v2.3.5+235')]
actual=0;records=[]

def budget(*args):subprocess.run([sys.executable,str(LEDGER),*map(str,args),'--json'],cwd=ROOT,check=True)
def api(path):
    global actual
    result=subprocess.run([GH,'api',path],capture_output=True,timeout=30,check=True)
    actual+=len(result.stdout)
    return json.loads(result.stdout)

if TARGET.is_file():
    record=json.loads(TARGET.read_text())
    if record.get('status')=='completed':
        print('Existing immutable reference metadata retained; no network')
        sys.exit(0)
    raise RuntimeError('Prior incomplete lock must be retained; choose a new output and run ID')

TARGET.parent.mkdir(parents=True,exist_ok=True)
budget('reserve','--run-id',RUN,'--estimated-bytes',4*1024*1024)
status='failed'
try:
    for name,repo,tag in REFERENCES:
        encoded=urllib.parse.quote(tag,safe='')
        release=api(f'repos/{repo}/releases/tags/{encoded}')
        ref=api(f'repos/{repo}/git/ref/tags/{encoded}')
        obj=ref['object'];depth=0
        while obj['type']=='tag':
            depth+=1
            if depth>4:raise RuntimeError('Unexpected nested tag depth')
            obj=api(f'repos/{repo}/git/tags/{obj["sha"]}')['object']
        if obj['type']!='commit' or release['tag_name']!=tag:raise RuntimeError('Reference tag mismatch')
        records.append({'name':name,'repository':repo,'tag':tag,'source_commit':obj['sha'],
            'release_id':release['id'],'release_url':release['html_url'],
            'published_at':release['published_at'],'prerelease':release['prerelease'],
            'local_binary_verified':False,
            'assets':[{'name':a['name'],'bytes':a['size'],'publisher_digest':a.get('digest'),
                       'download_url':a['browser_download_url']} for a in release['assets']]})
    status='completed'
finally:
    result={'schema':1,'status':status,'actual_response_bytes':actual,'references':records,
            'scope':'official source refs and publisher asset metadata only; no comparison run'}
    TARGET.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    budget('reconcile','--run-id',RUN,'--actual-response-bytes',actual,'--status',status)
    print(json.dumps({'status':status,'references':len(records),'response_bytes':actual}))
