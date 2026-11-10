"""Interleave the same durable checkpoint workload against two core artifacts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    java = Path(r'D:\env\jdk-21.0.12+8\bin\java.exe')
    cli = Path(r'D:\CodexResearch\turbodl-maintenance-20261110\before-tail-cost\turbodl-cli-all.jar')
    variants = {'baseline': args.baseline.resolve(), 'candidate': args.candidate.resolve()}
    manifest = {name: {'path': str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
                for name, path in variants.items()}
    (args.output / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    rows = []
    summary = {}
    for pair in range(9):
        order = list(variants) if pair % 2 == 0 else list(variants)[::-1]
        for label in order:
            classpath = os.pathsep.join(map(str, [variants[label], ROOT / 'turbodl-core/build/classes/kotlin/test', cli]))
            result = subprocess.run([str(java), '-Xms64m', '-Xmx256m', '-cp', classpath,
                'dev.turbodl.core.CheckpointMaintenanceBenchKt', '1'], cwd=ROOT,
                capture_output=True, text=True, encoding='utf-8', timeout=30, check=True)
            (args.output / f'{pair}-{label}.jsonl').write_text(result.stdout, encoding='utf-8')
            for line in result.stdout.splitlines():
                row = json.loads(line)
                if not row['sha256_matched'] or not row['v2_format_matched']:
                    raise ValueError('checkpoint correctness failed')
                rows.append({'pair': pair, 'variant': label, **row})
        for count in [64, 256, 1024]:
            for label in variants:
                values = [r['samples_ms'][0] for r in rows if r['pieces'] == count and r['variant'] == label]
                mean = statistics.mean(values)
                summary[f'{count}-{label}'] = {'count': len(values), 'p50_ms': statistics.median(values),
                    'cv': statistics.stdev(values) / mean if len(values) > 1 else 0}
        if pair >= 4 and all(row['cv'] <= .15 for row in summary.values()):
            break
    (args.output / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
