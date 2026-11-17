"""Download fixed official reference tools to an isolated D: cache, never install."""
import hashlib
import json
from pathlib import Path
import stat
import subprocess
import sys
import urllib.parse
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[3]
BASE=Path(sys.argv[1]).resolve()
METADATA=BASE/'baseline/REFERENCES.json'
RUN='rebuild-official-reference-assets-20261117'
CHOICES={
    'aria2':'aria2-1.37.0-win-64bit-build1.zip',
    'gopeed':'gopeed-web-v1.9.3-windows-amd64.zip',
    'wenxi':'asterlink-2.3.5+235-windows-x64.zip',
}
records=json.loads(METADATA.read_text())['references']
ledger=ROOT/'_bench/industrial/traffic_budget.py'
destination=BASE/'references';destination.mkdir(exist_ok=True)
evidence=destination/'ASSETS.json';actual=0;results=[]

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        while block:=f.read(1048576):h.update(block)
    return h.hexdigest()
def budget(*args):subprocess.run([sys.executable,str(ledger),*map(str,args),'--json'],cwd=ROOT,check=True)

if evidence.is_file():
    old=json.loads(evidence.read_text())
    if old['status']!='completed':raise RuntimeError('Preserve the incomplete run; use a separately accounted retry')
    for row in old['references']:
        if digest(destination/row['name']/row['archive'])!=row['sha256']:raise RuntimeError('Reference archive changed')
    print('Existing reference archives verified; no network or ledger mutation')
    sys.exit(0)

selected=[]
for row in records:
    candidates=[a for a in row['assets'] if a['name']==CHOICES[row['name']]]
    if len(candidates)!=1:raise RuntimeError('Fixed reference asset absent')
    selected.append((row,candidates[0]))
budget('reserve','--run-id',RUN,'--estimated-bytes',sum(a['bytes'] for _,a in selected)+1048576)
status='failed'
try:
    for row,asset in selected:
        target=destination/row['name'];target.mkdir(exist_ok=True)
        archive=target/asset['name']
        url=urllib.parse.urlsplit(asset['download_url'])
        prefix='/'+row['repository']+'/releases/download/'
        if url.scheme!='https' or url.hostname!='github.com' or not url.path.startswith(prefix):raise RuntimeError('Unexpected reference origin')
        if not archive.is_file():
            temporary=archive.with_suffix(archive.suffix+'.part')
            try:
                with urllib.request.urlopen(urllib.request.Request(asset['download_url'],headers={'User-Agent':'TurboDL-reference-freeze'}),timeout=30) as response,temporary.open('wb') as f:
                    host=urllib.parse.urlsplit(response.url).hostname
                    if host not in {'github.com','release-assets.githubusercontent.com','objects.githubusercontent.com'}:raise RuntimeError('Unexpected reference redirect')
                    while block:=response.read(1048576):
                        actual+=len(block);f.write(block)
                        if f.tell()>asset['bytes']:raise RuntimeError('Reference exceeded pinned size')
                if temporary.stat().st_size!=asset['bytes']:raise RuntimeError('Incomplete reference archive')
                temporary.replace(archive)
            except BaseException:
                # Keep failed partials and their traffic in this run's evidence.
                raise
        sha=digest(archive)
        expected=asset['publisher_digest']
        if expected and expected!='sha256:'+sha:raise RuntimeError('Publisher reference digest mismatch')
        unpacked=target/'unpacked';unpacked.mkdir(exist_ok=True)
        with zipfile.ZipFile(archive) as z:
            if z.testzip() is not None:raise RuntimeError('Reference ZIP CRC failed')
            for item in z.infolist():
                out=(unpacked/item.filename).resolve()
                mode=item.external_attr>>16
                if not out.is_relative_to(unpacked) or stat.S_ISLNK(mode):raise RuntimeError('Unsafe reference archive path')
            z.extractall(unpacked)
        binaries=[{'path':str(p.relative_to(unpacked)),'bytes':p.stat().st_size,'sha256':digest(p)} for p in sorted(unpacked.rglob('*.exe'))]
        results.append({'name':row['name'],'tag':row['tag'],'source_commit':row['source_commit'],
            'archive':asset['name'],'bytes':archive.stat().st_size,'sha256':sha,
            'publisher_digest_verified':bool(expected),'binaries':binaries,'runtime_verified':False})
        print(json.dumps({'reference':row['name'],'archive_bytes':asset['bytes'],'verified':True}),flush=True)
    status='completed'
finally:
    evidence.write_text(json.dumps({'schema':1,'status':status,'actual_response_bytes':actual,'references':results},indent=2)+'\n')
    budget('reconcile','--run-id',RUN,'--actual-response-bytes',actual,'--status',status)
