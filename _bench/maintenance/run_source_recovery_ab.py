"""Interleave released and grouped-source SDK binaries against the same HTTP model."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--pairs', type=int, default=5)
    p.add_argument('--max-pairs', type=int, default=9)
    args = p.parse_args()
    if not 1 <= args.pairs <= args.max_pairs <= 9:
        p.error('require 1 <= pairs <= max-pairs <= 9')
    release = Path(r'D:\CodexResearch\releases\TurboDL-0.5.0.2-publish')
    binaries = {'baseline': release / 'turbodl-core-0.5.0.2.jar',
                'candidate': ROOT / 'turbodl-core/build/libs/turbodl-core-0.5.0.3.jar'}
    rows = []
    args.output.parent.mkdir(parents=True, exist_ok=True)
    for pair in range(args.max_pairs):
        order = ('baseline', 'candidate') if pair % 2 == 0 else ('candidate', 'baseline')
        for engine in order:
            cp = os.pathsep.join(map(str, (binaries[engine], ROOT / 'turbodl-core/build/classes/kotlin/test',
                                         release / 'turbodl-cli-all.jar')))
            command = [r'D:\env\jdk-21.0.12+8\bin\java.exe', '-Xms64m', '-Xmx512m', '-cp', cp,
                       'dev.turbodl.core.SourceRecoveryBench', '--rounds', '1']
            if engine == 'candidate':
                command += ['--grouped']
            run = subprocess.run(command, capture_output=True, encoding='utf-8', errors='replace', timeout=25)
            frames = [json.loads(line) for line in run.stdout.splitlines() if line.startswith('{')]
            if run.returncode or len(frames) != 1 or not frames[0].get('ok') or not frames[0].get('hash_matched'):
                raise RuntimeError(f'{engine} did not complete an independently verified transfer: {frames}')
            row = dict(frames[0], engine=engine, pair=pair)
            rows.append(row)
            args.output.write_text(json.dumps({'raw': rows}, indent=2), encoding='utf-8')
            print(json.dumps(row), flush=True)
        if pair + 1 >= args.pairs:
            spread = []
            for label in binaries:
                values = [r['elapsed_ms'] for r in rows if r['engine'] == label]
                spread.append(statistics.stdev(values) / statistics.mean(values) if len(values) > 1 else 0)
            if max(spread) <= .15:
                break
    base = [r['elapsed_ms'] for r in rows if r['engine'] == 'baseline']
    cand = [r['elapsed_ms'] for r in rows if r['engine'] == 'candidate']
    ratios = [a / b for a, b in zip(base, cand)]
    rng = random.Random(20261111)
    bootstrap = sorted(statistics.median(rng.choices(ratios, k=len(ratios))) for _ in range(4000))
    result = {'raw': rows, 'baseline_p50_ms': statistics.median(base), 'candidate_p50_ms': statistics.median(cand),
              'paired_speedup_p50': statistics.median(ratios), 'paired_ci95': [bootstrap[100], bootstrap[3900]],
              'cv_baseline': statistics.stdev(base) / statistics.mean(base) if len(base) > 1 else 0,
              'cv_candidate': statistics.stdev(cand) / statistics.mean(cand) if len(cand) > 1 else 0,
              'binary_sha256': {k: hashlib.file_digest(v.open('rb'), 'sha256').hexdigest() for k, v in binaries.items()}}
    args.output.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k != 'raw'}))


if __name__ == '__main__':
    main()
