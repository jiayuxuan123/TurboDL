"""Pinned local-only H2/H3 fixture dependencies; existing traffic ledger required."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import urllib.parse
import urllib.request
import zipfile
from pip._vendor.packaging.tags import sys_tags
from pip._vendor.packaging.utils import parse_wheel_filename

ROOT=Path(__file__).resolve().parents[3]
TARGET=Path(sys.argv[1]).resolve()
RUN=sys.argv[2] if len(sys.argv)>2 else 'rebuild-h2-h3-fixtures-20261117'
PINS={'h2':'4.3.0','hpack':'4.1.0','hyperframe':'6.1.0','aioquic':'1.3.0','pylsqpack':'0.3.23','service-identity':'24.2.0','pyasn1':'0.6.1','pyasn1-modules':'0.4.2','pyopenssl':'25.3.0','cryptography':'46.0.3'}
LEDGER=ROOT/'_bench/industrial/traffic_budget.py'
tags=list(sys_tags());actual=0;evidence=[]

def budget(*args):
    subprocess.run([sys.executable,str(LEDGER),*args,'--json'],cwd=ROOT,check=True)
def fetch(url,maximum):
    global actual
    parsed=urllib.parse.urlsplit(url)
    if parsed.scheme!='https' or parsed.hostname not in {'pypi.org','files.pythonhosted.org'}:
        raise RuntimeError('Unexpected test dependency origin')
    with urllib.request.urlopen(url,timeout=30) as response:
        if urllib.parse.urlsplit(response.url).hostname!=parsed.hostname:raise RuntimeError('Unexpected redirect')
        result=bytearray()
        while chunk:=response.read(65536):
            actual+=len(chunk);result.extend(chunk)
            if len(result)>maximum:raise RuntimeError('Dependency exceeds reservation')
        return bytes(result)

TARGET.mkdir(parents=True,exist_ok=True)
prior=TARGET/'h2-h3-evidence.json'
if prior.is_file():
    record=json.loads(prior.read_text())
    if record.get('status')=='completed' and {d['name']:d['version'] for d in record['dependencies']}==PINS:
        for item in record['dependencies']:
            if hashlib.sha256((TARGET/item['file']).read_bytes()).hexdigest()!=item['sha256']:raise RuntimeError('Cached fixture dependency changed')
        print('Verified existing fixture dependencies; no network or ledger mutation')
        sys.exit(0)
    if len(sys.argv)<3:raise RuntimeError('Changed/failed fixture setup requires a new run ID; do not reuse a reconciled reservation')
budget('reserve','--run-id',RUN,'--estimated-bytes',str(16*1024*1024))
status='failed'
try:
    for name,version in PINS.items():
        metadata=json.loads(fetch(f'https://pypi.org/pypi/{name}/{version}/json',2*1024*1024))
        choices=[]
        for item in metadata['urls']:
            if not item['filename'].endswith('.whl'):continue
            _,_,_,wheel_tags=parse_wheel_filename(item['filename'])
            rank=next((i for i,tag in enumerate(tags) if tag in wheel_tags),None)
            if rank is not None:choices.append((rank,item))
        if not choices:raise RuntimeError(f'No compatible wheel for {name}')
        item=min(choices,key=lambda row:row[0])[1]
        archive=TARGET/item['filename']
        if archive.is_file():data=archive.read_bytes()
        else:data=fetch(item['url'],8*1024*1024)
        digest=hashlib.sha256(data).hexdigest()
        if digest!=item['digests']['sha256']:raise RuntimeError(f'{name} wheel SHA-256 differs')
        archive.write_bytes(data)
        with zipfile.ZipFile(archive) as z:
            for entry in z.infolist():
                path=(TARGET/entry.filename).resolve()
                if not path.is_relative_to(TARGET):raise RuntimeError('Unsafe wheel path')
            z.extractall(TARGET)
        evidence.append({'name':name,'version':version,'file':item['filename'],'bytes':len(data),'sha256':digest})
    status='completed'
finally:
    (TARGET/'h2-h3-evidence.json').write_text(json.dumps({'actual_bytes':actual,'status':status,'dependencies':evidence},indent=2)+'\n')
    budget('reconcile','--run-id',RUN,'--actual-response-bytes',str(actual),'--status',status)
