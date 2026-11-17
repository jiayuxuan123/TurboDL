"""Freeze published sources and hash existing release files without redownloading."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

def digest(path: Path) -> str:
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):h.update(block)
    return h.hexdigest()

def main() -> None:
    parser=argparse.ArgumentParser()
    parser.add_argument('--workspace',type=Path,required=True)
    parser.add_argument('--artifacts',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    entries=[]
    for project,version in [('TurboDL','0.5.0.6'),('YunGet','2.7.39')]:
        repo=args.workspace/project
        commit=subprocess.check_output(['git','-C',str(repo),'rev-parse',f'v{version}^{{commit}}'],text=True).strip()
        archive=args.output/f'{project}-{version}-source.zip'
        if not archive.exists():
            subprocess.run(['git','-C',str(repo),'archive','--format=zip',f'--output={archive}',f'v{version}'],check=True)
        files=[]
        for file in sorted((args.artifacts/f'{project}-{version}').iterdir()):
            if file.is_file():files.append({'name':file.name,'path':str(file),'bytes':file.stat().st_size,'sha256':digest(file)})
        if not files:raise RuntimeError(f'No release artifacts for {project}')
        entries.append({'project':project,'version':version,'source_commit':commit,'source_archive':str(archive),'source_sha256':digest(archive),'artifacts':files})
    result={'schema':1,'baselines':entries,'includes_private_credentials':False}
    manifest=args.output/'BASELINE.json'
    if manifest.exists() and json.loads(manifest.read_text(encoding='utf-8'))!=result:
        raise RuntimeError('Baseline changed: existing manifest is immutable')
    manifest.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'manifest':str(manifest),'projects':len(entries),'artifacts':sum(len(p['artifacts']) for p in entries)},indent=2))

if __name__=='__main__':main()
