"""One bounded, local JFR capture; every completed fixture is independently hashed."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--core', type=Path, required=True)
    parser.add_argument('--cli', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    with urlopen('http://127.0.0.1:8845/info', timeout=5) as response:
        fixture = json.load(response)['paced']
    scratch_root = ROOT / '_bench/runs'
    scratch_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='jfr-', dir=scratch_root) as temporary:
        scratch = Path(temporary)
        targets = [scratch / f'{index}.bin' for index in range(3)]
        frames = ['\t'.join(map(str, [index, f'http://127.0.0.1:8845/paced.bin?run=jfr-{scratch.name}-{index}',
                  target, fixture['size'], 256, 4, 262144, '', '', 16777216]))
                  for index, target in enumerate(targets)]
        cp = os.pathsep.join(map(str, (args.core, ROOT / 'turbodl-core/build/classes/kotlin/test', args.cli)))
        command = [r'D:\env\jdk-21.0.12+8\bin\java.exe', '-Xms64m', '-Xmx512m',
                   '-Xlog:jfr+startup=off',
                   f'-XX:StartFlightRecording=settings=profile,filename={args.output / "profile.jfr"},dumponexit=true',
                   '-cp', cp, 'dev.turbodl.core.NetworkRangeBenchKt', '--local-fixture']
        result = subprocess.run(command, input='\n'.join(frames + ['close', '']),
                                capture_output=True, encoding='utf-8', errors='replace', timeout=60)
        (args.output / 'host.stderr.log').write_text(result.stderr, encoding='utf-8')
        rows = [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
        if result.returncode or len(rows) != len(targets) or not all(r.get('ok') and not r.get('partial') for r in rows):
            raise RuntimeError('JFR workload failed; not performance evidence')
        for row, target in zip(rows, targets):
            with target.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            row['sha256_matched'] = digest == fixture['sha256'] and target.stat().st_size == fixture['size']
            if not row['sha256_matched']:
                raise RuntimeError('profile fixture integrity failure')
        (args.output / 'workload.json').write_text(json.dumps(rows, indent=2), encoding='utf-8')
        print(json.dumps({'ok': True, 'transfers': len(rows), 'bytes_per_transfer': fixture['size'],
                          'sha256_matched': True, 'comparison_evidence': False}))


if __name__ == '__main__':
    main()
