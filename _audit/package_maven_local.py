"""Package the 0.3.0.0 mavenLocal publication into a release zip.

Mirrors the layout used by build/TurboDL-v0.2.2.0-mavenLocal.zip:
    <module>/<version>/<module>-<version>.{jar,module,pom}
"""
import os
import zipfile
from pathlib import Path

VERSION = "0.3.0.0"
MODULES = [
    "turbodl-core",
    "turbo-plugin-runtime",
    "turbo-plugin-js",
    "turbo-plugin-hls",
    "turbo-plugin-bootstrap",
    "turbo-plugin-parser",
]
M2 = Path.home() / ".m2" / "repository" / "dev" / "turbodl"
OUT = Path(__file__).resolve().parent.parent / "build" / f"TurboDL-v{VERSION}-mavenLocal.zip"

missing = []
entries = []
for mod in MODULES:
    d = M2 / mod / VERSION
    if not d.is_dir():
        missing.append(str(d))
        continue
    for ext in ("jar", "module", "pom"):
        f = d / f"{mod}-{VERSION}.{ext}"
        if f.is_file():
            entries.append((f, f"{mod}/{VERSION}/{f.name}"))
        else:
            missing.append(str(f))

if missing:
    raise SystemExit("missing artifacts:\n  " + "\n  ".join(missing))

OUT.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
    for src, arc in entries:
        z.write(src, arc)

print(f"wrote {OUT}")
print(f"  {len(entries)} files, {OUT.stat().st_size} bytes")
