#!/usr/bin/env python3
"""Assemble the local, self-contained TurboDL 0.5.0.1 polyglot product."""

from __future__ import annotations

import hashlib
import zipfile
from pathlib import Path


ROOT = Path(r"C:\Users\38476\Desktop\yunluo")
TURBO = ROOT / "TurboDL"
PROBES = ROOT / "_research" / "turbodl-lang-probes"
VERSION = "0.5.0.1"
OUT = TURBO / "build" / f"TurboDL-v{VERSION}-polyglot-local.zip"

LAUNCHER = PROBES / "platform-artifacts" / "launcher"
DATAPLANE = PROBES / "platform-artifacts" / "dataplane"
JAR = TURBO / "turbodl-cli" / "build" / "libs" / "turbodl-cli-all.jar"

PLATFORMS = {
    "windows-x86_64": ("windows-x86_64/bin/turbodl.exe", "windows-x86_64/zig-probe.exe", "turbodl.exe", "tdl-native.exe"),
    "windows-arm64": ("windows-arm64/bin/turbodl.exe", "windows-arm64/bin/zig-probe.exe", "turbodl.exe", "tdl-native.exe"),
    "linux-x86_64-glibc": ("linux-x86_64-glibc/bin/turbodl", "linux-x86_64-glibc/bin/zig-probe", "turbodl", "tdl-native"),
    "linux-x86_64-musl": ("linux-x86_64-musl/bin/turbodl", "linux-x86_64-musl/bin/zig-probe", "turbodl", "tdl-native"),
    "linux-arm64-glibc": ("linux-arm64-glibc/bin/turbodl", "linux-arm64-glibc/bin/zig-probe", "turbodl", "tdl-native"),
    "linux-arm64-musl": ("linux-arm64-musl/bin/turbodl", "linux-arm64-musl/bin/zig-probe", "turbodl", "tdl-native"),
    "macos-x86_64": ("macos-x86_64/bin/turbodl", "macos-x86_64/bin/zig-probe", "turbodl", "tdl-native"),
    "macos-arm64": ("macos-arm64/bin/turbodl", "macos-arm64/bin/zig-probe", "turbodl", "tdl-native"),
    "macos-universal2": ("macos-universal2/turbodl", "macos-universal2/zig-probe", "turbodl", "tdl-native"),
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def add(entries: list[tuple[Path, str]], source: Path, archive: str) -> None:
    if not source.is_file():
        raise FileNotFoundError(source)
    entries.append((source, archive))


def main() -> int:
    entries: list[tuple[Path, str]] = []
    for platform, (launcher_rel, dataplane_rel, launcher_name, dataplane_name) in PLATFORMS.items():
        add(entries, LAUNCHER / launcher_rel, f"platforms/{platform}/{launcher_name}")
        add(entries, DATAPLANE / dataplane_rel, f"platforms/{platform}/{dataplane_name}")
        add(entries, JAR, f"platforms/{platform}/turbodl-cli-all.jar")

    add(entries, TURBO / "build" / f"TurboDL-v{VERSION}-mavenLocal.zip", f"engine/TurboDL-v{VERSION}-mavenLocal.zip")

    product = PROBES / "product"
    for name in ("PRODUCT.md", "AB_REPORT.md", "PLATFORM_MATRIX.md"):
        add(entries, product / name, f"docs/{name}")
    gate = TURBO / "_audit" / "final-0501-test-results.json"
    add(entries, gate, "docs/TEST_REPORT.json")

    for label, path in (
        ("32m", PROBES / "runs" / "ab-native-jvm-32m" / "ab.json"),
        ("256m", PROBES / "runs" / "ab-native-jvm-256m" / "ab.json"),
        ("2g", PROBES / "runs" / "ab-native-jvm-2g-v2" / "ab.json"),
    ):
        add(entries, path, f"evidence/ab-{label}.json")
    add(entries, PROBES / "native" / "hash_native_256m.txt", "evidence/native-sha-256m.txt")
    add(entries, PROBES / "native" / "hash_jvm_256m.txt", "evidence/jvm-sha-256m.txt")

    hashes = [f"{sha256(source)}  {archive}" for source, archive in entries]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        for source, archive in entries:
            bundle.write(source, archive)
        bundle.writestr("SHA256SUMS.txt", "\n".join(hashes) + "\n")
    print(f"wrote {OUT}")
    print(f"  {len(entries)} files, {OUT.stat().st_size} bytes")
    print(f"  sha256={sha256(OUT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
