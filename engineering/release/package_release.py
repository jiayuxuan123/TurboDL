"""Package verified Maven publications, CLI, and native platforms for a release."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
MODULES = ['turbodl-core', 'turbo-plugin-runtime', 'turbo-plugin-bootstrap',
           'turbo-plugin-hls', 'turbo-plugin-parser', 'turbo-plugin-js']
TARGETS = ['windows-x86_64', 'windows-arm64', 'linux-x86_64-glibc', 'linux-x86_64-musl',
           'linux-arm64-glibc', 'linux-arm64-musl', 'macos-x86_64', 'macos-arm64']


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(1024 * 1024):
            value.update(block)
    return value.hexdigest()


def check_binary(path, target):
    data = path.read_bytes()
    if target.startswith('windows'):
        assert data[:2] == b'MZ', path
        offset = struct.unpack_from('<I', data, 0x3c)[0]
        assert data[offset:offset + 4] == b'PE\0\0', path
        expected = 0xaa64 if 'arm64' in target else 0x8664
        assert struct.unpack_from('<H', data, offset + 4)[0] == expected, path
    elif target.startswith('linux'):
        assert data[:6] == b'\x7fELF\x02\x01', path
        assert struct.unpack_from('<H', data, 18)[0] == (183 if 'arm64' in target else 62), path
    else:
        assert struct.unpack_from('<I', data)[0] == 0xfeedfacf, path
        assert struct.unpack_from('<I', data, 4)[0] == (0x100000c if 'arm64' in target else 0x1000007), path


def universal(thin):
    offset = 16384
    headers, payloads = [], []
    for path in thin:
        data = path.read_bytes()
        cpu, subtype = struct.unpack_from('<II', data, 4)
        headers.append(struct.pack('>IIIII', cpu, subtype, offset, len(data), 14))
        payloads.append((offset, data))
        offset = (offset + len(data) + 16383) & ~16383
    result = bytearray(struct.pack('>II', 0xcafebabe, len(thin)) + b''.join(headers))
    for offset, data in payloads:
        result.extend(b'\0' * (offset - len(result)))
        result.extend(data)
        assert result[offset:offset + len(data)] == data
    return bytes(result)


def archive(path, entries):
    hashes = []
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for source, name, executable in entries:
            data = source if isinstance(source, bytes) else source.read_bytes()
            info = zipfile.ZipInfo(name)
            info.create_system = 3
            info.external_attr = (0o100755 if executable else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            bundle.writestr(info, data)
            hashes.append(hashlib.sha256(data).hexdigest() + '  ' + name)
        bundle.writestr('SHA256SUMS.txt', '\n'.join(hashes) + '\n')
    with zipfile.ZipFile(path) as bundle:
        assert bundle.testzip() is None, path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--test-report', type=Path, required=True)
    parser.add_argument('--ab-report', type=Path, required=True)
    parser.add_argument('--platform-report', type=Path, required=True)
    args = parser.parse_args()
    version = args.version
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    if any(out.iterdir()):
        parser.error('use a fresh staging directory')
    files, maven = [], []
    for module in MODULES:
        directory = Path.home() / '.m2/repository/dev/turbodl' / module / version
        for suffix in ['jar', 'module', 'pom']:
            source = directory / f'{module}-{version}.{suffix}'
            assert source.is_file(), source
            target = out / source.name
            shutil.copy2(source, target)
            files.append(target)
            maven.append((source, f'{module}/{version}/{source.name}', False))
            if suffix == 'jar':
                built = ROOT / module / 'build/libs' / source.name
                assert digest(built) == digest(source), f'stale Maven publication: {module}'
            elif suffix == 'module':
                assert json.loads(source.read_text())['component']['version'] == version
            else:
                assert ET.parse(source).getroot().find('{http://maven.apache.org/POM/4.0.0}version').text == version
    cli = out / 'turbodl-cli-all.jar'
    shutil.copy2(ROOT / 'turbodl-cli/build/libs' / cli.name, cli)
    files.append(cli)
    offline = out / f'TurboDL-v{version}-mavenLocal.zip'
    archive(offline, maven)
    files.append(offline)
    ab = out / f'TurboDL-v{version}-AB-REPORT.md'
    platform = out / f'TurboDL-v{version}-PLATFORM-MATRIX.md'
    shutil.copy2(args.ab_report, ab)
    shutil.copy2(args.platform_report, platform)
    files.extend([ab, platform])
    entries = [(offline, 'engine/' + offline.name, False),
               (ab, 'docs/AB_REPORT.md', False), (platform, 'docs/PLATFORM_MATRIX.md', False),
               (args.test_report, 'docs/TEST_REPORT.json', False)]
    native = {}
    for target in TARGETS:
        exe = '.exe' if target.startswith('windows') else ''
        binaries = [ROOT / 'native/dist/launcher' / target / 'bin' / ('turbodl' + exe),
                    ROOT / 'native/dist/dataplane' / target / 'bin' / ('zig-probe' + exe)]
        for binary in binaries:
            check_binary(binary, target)
        native[target] = binaries
        for source, name in zip(binaries, ['turbodl' + exe, 'tdl-native' + exe]):
            entries.append((source, f'platforms/{target}/{name}', True))
        entries.append((cli, f'platforms/{target}/{cli.name}', False))
    for index, name in enumerate(['turbodl', 'tdl-native']):
        data = universal([native['macos-x86_64'][index], native['macos-arm64'][index]])
        entries.append((data, 'platforms/macos-universal2/' + name, True))
    entries.append((cli, 'platforms/macos-universal2/' + cli.name, False))
    product = out / f'TurboDL-v{version}-polyglot-local.zip'
    archive(product, entries)
    files.append(product)
    checksums = out / f'TurboDL-v{version}-SHA256SUMS.txt'
    checksums.write_text('\n'.join(digest(p) + '  ' + p.name for p in files) + '\n', encoding='utf-8')
    files.append(checksums)
    assert len(files) == 24
    print(json.dumps({'version': version, 'assets': len(files), 'output': str(out)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
