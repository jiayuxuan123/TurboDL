"""Fetch a signed Botan source release using the existing traffic ledger."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import urllib.parse
import urllib.request

ROOT=Path(__file__).resolve().parents[3]
TARGET=Path(sys.argv[1]).resolve()
VERSION='3.14.1'
RUN='rebuild-botan-sha-20261117'
FINGERPRINT='621DAF6411E1851C4CF9A2E16211EBF1EFBADFBC'
GPG=Path(r'C:\Program Files\Git\usr\bin\gpg.exe')
LEDGER=ROOT/'_bench/industrial/traffic_budget.py'
actual=0
TARGET.mkdir(parents=True,exist_ok=True)
evidence=TARGET/'BOTAN-SOURCE.json'

def budget(*args):
    subprocess.run([sys.executable,str(LEDGER),*map(str,args),'--json'],cwd=ROOT,check=True)

def fetch(path,name,limit):
    global actual
    url='https://botan.randombit.net/'+path
    request=urllib.request.Request(url,headers={'User-Agent':'TurboDL-dependency-freeze'})
    with urllib.request.urlopen(request,timeout=30) as response:
        final=urllib.parse.urlsplit(response.url)
        if final.scheme!='https' or final.hostname!='botan.randombit.net':raise RuntimeError('unexpected dependency redirect')
        target=TARGET/name
        with target.open('wb') as stream:
            total=0
            while block:=response.read(65536):
                actual+=len(block);total+=len(block)
                if total>limit:raise RuntimeError('dependency exceeds reservation')
                stream.write(block)
        return target

if evidence.exists():
    prior=json.loads(evidence.read_text())
    if prior.get('status')!='completed':raise RuntimeError('retain failed dependency evidence; do not reuse a reconciled run')
    archive=TARGET/prior['archive']
    if hashlib.sha256(archive.read_bytes()).hexdigest()!=prior['sha256']:raise RuntimeError('cached dependency changed')
    print('Verified existing signed source; no network or ledger mutation')
    raise SystemExit(0)
if not GPG.is_file():raise RuntimeError('existing signature verifier unavailable')
budget('reserve','--run-id',RUN,'--estimated-bytes',20*1024*1024)
status='failed';record={}
try:
    archive=fetch(f'releases/Botan-{VERSION}.tar.xz',f'Botan-{VERSION}.tar.xz',19*1024*1024)
    signature=fetch(f'releases/Botan-{VERSION}.tar.xz.asc',archive.name+'.asc',65536)
    key=fetch('pgpkey.txt','release-public-keys.txt',65536)
    keyring=TARGET/'signature-keyring';keyring.mkdir()
    subprocess.run([str(GPG),'--batch','--homedir',str(keyring),'--import',str(key)],capture_output=True,check=True)
    verified=subprocess.run([str(GPG),'--batch','--homedir',str(keyring),'--no-auto-key-retrieve','--status-fd=1','--verify',str(signature),str(archive)],capture_output=True,text=True,check=True)
    if '[GNUPG:] VALIDSIG '+FINGERPRINT+' ' not in verified.stdout:raise RuntimeError('release signer differs from the official pinned fingerprint')
    with tarfile.open(archive) as content:
        if any(not (TARGET/m.name).resolve().is_relative_to(TARGET) for m in content.getmembers()):raise RuntimeError('unsafe source archive path')
        content.extractall(TARGET,filter='data')
    status='completed'
    record={'name':'botan','version':VERSION,'archive':archive.name,
            'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),
            'verified_signer':FINGERPRINT,'license':'BSD-2-Clause',
            'source_url':f'https://botan.randombit.net/releases/{archive.name}'}
finally:
    evidence.write_text(json.dumps({**record,'status':status,'actual_response_bytes':actual},indent=2)+'\n')
    budget('reconcile','--run-id',RUN,'--actual-response-bytes',actual,'--status',status)
