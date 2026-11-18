"""Run the native development suite with an explicit completion record."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

import psutil
from local_run_lock import acquire_local_run_lock


IDENTITY_FILES = (
    "engineering/rebuild/2026-11-16/compare_local_engines.py",
    "engineering/rebuild/2026-11-16/local_run_lock.py",
    "engineering/rebuild/2026-11-16/reference_hosts.py",
    "engineering/rebuild/2026-11-16/run_local_gate.py",
    "engineering/rebuild/2026-11-16/run_serial_local.ps1",
    "_bench/maintenance/compare_ranges.py",
    "_bench/maintenance/compare_warm_sdk.py",
    "_bench/maintenance/download_profiles.mjs",
    "turbodl-core/src/test/kotlin/dev/turbodl/core/WarmRangeBench.kt",
    "native/engine/CMakeLists.txt",
)
IDENTITY_SUFFIXES = {".py", ".cpp", ".hpp", ".h", ".java", ".kt", ".txt", ".json"}


def file_sha256(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def source_identity(root):
    paths = {root / relative for relative in IDENTITY_FILES}
    for directory in ("native/engine", "turbodl-native/src"):
        for path in (root / directory).rglob("*"):
            if path.is_file() and path.suffix.lower() in IDENTITY_SUFFIXES:
                paths.add(path)
    missing = sorted(str(path) for path in paths if not path.is_file())
    if missing:
        raise FileNotFoundError("missing identity inputs: " + ", ".join(missing))
    hashes = {}
    aggregate = hashlib.sha256()
    for path in sorted(paths):
        relative = path.relative_to(root).as_posix()
        digest = file_sha256(path)
        hashes[relative] = digest
        aggregate.update(relative.encode("utf-8"))
        aggregate.update(b"\0")
        aggregate.update(digest.encode("ascii"))
        aggregate.update(b"\n")
    return {"sha256": aggregate.hexdigest(), "files": hashes}


def build_identity(build):
    return {path.name: file_sha256(path) for path in sorted(build.iterdir())
            if path.is_file() and path.suffix.lower() in (".dll", ".exe")}


def remaining_children(timeout=2.0):
    deadline = time.monotonic() + timeout
    while True:
        children = psutil.Process().children(recursive=True)
        if not children or time.monotonic() >= deadline:
            break
        time.sleep(0.05)
    result = []
    for child in children:
        try:
            result.append({
                "pid": child.pid,
                "name": child.name(),
                "cmdline": child.cmdline(),
            })
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            result.append({"pid": child.pid})
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--build", type=Path)
    parser.add_argument("--result", type=Path)
    parser.add_argument("--scratch-root", type=Path)
    parser.add_argument("--print-identity", action="store_true")
    parser.add_argument("--tests", nargs="+", help="Run named Python tests for development only")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    identity = source_identity(root)
    if args.print_identity:
        print(json.dumps(identity, indent=2, sort_keys=True))
        return 0
    if not args.cache or not args.build or not args.result:
        parser.error("--cache, --build and --result are required unless --print-identity is used")

    acquire_local_run_lock("native-local-gate")
    cache = args.cache.resolve()
    build = args.build.resolve()
    binaries = build_identity(build)
    parent = args.scratch_root.resolve() if args.scratch_root else cache / "test-runs"
    parent.mkdir(parents=True, exist_ok=True)
    deps = cache / "deps/test-protocols"
    sys.path[:0] = [str(deps), str(deps / "pyftpdlib-2.2.0")]
    os.environ.update(
        TD_ENGINE_LIBRARY=str(build / "turbodl_engine.dll"),
        TD_TORRENT_FIXTURE=str(build / "td-torrent-fixture.exe"),
        TD_MEDIA_FIXTURE=str(build / "td-media-fixture.exe"),
        TD_JAVA_HOME=r"D:\env\jdk-21.0.12+8",
        PATH=str(build) + os.pathsep + os.environ["PATH"],
    )
    started = time.monotonic()
    scratch = Path(tempfile.mkdtemp(prefix="local-gate-", dir=parent)).resolve()
    record = {
        "status": "interrupted",
        "success": False,
        "public_network_test": False,
        "library_sha256": binaries["turbodl_engine.dll"],
        "binary_hashes": binaries,
        "source_identity_sha256": identity["sha256"],
        "source_hashes": identity["files"],
        "scratch": str(scratch),
    }
    cleanup_errors = []
    try:
        tempfile.tempdir = str(scratch)
        os.environ["TMP"] = str(scratch)
        os.environ["TEMP"] = str(scratch)
        os.environ["TD_MEDIA_TEST_ROOT"] = str(scratch)
        tests = root / "native/engine/tests"
        sys.path.insert(0, str(tests))
        if not args.tests or any("jni" in name for name in args.tests):
            classes = scratch / "java-classes"
            classes.mkdir()
            java_sources = sorted((root / "turbodl-native/src/main/java").rglob("*.java"))
            java_sources += sorted(tests.glob("*.java"))
            subprocess.run(
                [str(Path(os.environ["TD_JAVA_HOME"]) / "bin/javac.exe"),
                 "-encoding", "UTF-8", "-d", str(classes), *map(str, java_sources)],
                check=True, timeout=60,
            )
            os.environ["TD_JAVA_CLASSES"] = str(classes)
        components = {}
        if args.tests:
            suite = unittest.defaultTestLoader.loadTestsFromNames(args.tests)
        else:
            for name in ("td-hls-manifest-tests.exe", "td-ed2k-p0-tests.exe"):
                completed = subprocess.run([str(build / name)], timeout=30, check=False)
                components[name] = completed.returncode
            suite = unittest.defaultTestLoader.discover(str(tests), pattern="test_*.py")
        result = unittest.TextTestRunner(verbosity=2).run(suite)
        record.update(
            status="finished",
            success=result.wasSuccessful() and not result.skipped and not any(components.values()),
            scope="targeted" if args.tests else "full",
            components=components,
            tests=result.testsRun,
            skipped=len(result.skipped),
            failures=[str(case) for case, _ in result.failures],
            errors=[str(case) for case, _ in result.errors],
        )
    except BaseException as error:
        record.update(
            status="error",
            success=False,
            fatal_error=repr(error),
        )
    finally:
        try:
            record["inputs_unchanged"] = (
                binaries == build_identity(build) and identity == source_identity(root)
            )
        except OSError:
            record["inputs_unchanged"] = False
        if not record["inputs_unchanged"]:
            record["success"] = False
        children = remaining_children()
        if children:
            cleanup_errors.append("test child processes still running")
        record["remaining_child_processes"] = children
        if scratch.exists():
            try:
                resolved = scratch.resolve()
                if resolved.parent != parent:
                    cleanup_errors.append("unsafe scratch cleanup target")
                else:
                    shutil.rmtree(resolved)
            except Exception as error:
                cleanup_errors.append(f"remove scratch: {error!r}")
        scratch_cleaned = not scratch.exists()
        if not scratch_cleaned:
            cleanup_errors.append(f"scratch directory still exists: {scratch}")
        record["cleanup_errors"] = cleanup_errors
        record["scratch_cleaned"] = scratch_cleaned
        record["cleanup_ok"] = not cleanup_errors
        if cleanup_errors:
            record["success"] = False
        record["seconds"] = time.monotonic() - started
        args.result.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.result.with_suffix(".tmp")
        temporary.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        temporary.replace(args.result)
    return 0 if record["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
