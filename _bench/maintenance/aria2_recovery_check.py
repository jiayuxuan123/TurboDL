"""Local real-process check for aria2-next task identity recovery."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import urllib.request

SOURCE = "http://127.0.0.1:8843"
RPC = "http://127.0.0.1:8844/jsonrpc"
EXPECTED = "de276d64dd3dc10dea453b5776e489d999f083f96ae20292fc4ba042e2356989"
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def rpc(method, params=()):
    payload = {"jsonrpc": "2.0", "id": "fixture", "method": "aria2." + method,
               "params": ["token:fixture-only", *params]}
    request = urllib.request.Request(RPC, json.dumps(payload).encode(), {"Content-Type": "application/json"})
    with OPENER.open(request, timeout=2) as response:
        result = json.load(response)
    if "error" in result:
        raise RuntimeError(str(result["error"]))
    return result.get("result")


def info():
    with OPENER.open(SOURCE + "/info", timeout=2) as response:
        return json.load(response)


def check(binary, root, stable):
    variant = "stable-gid" if stable else "random-gid"
    folder = root / variant
    folder.mkdir()
    with OPENER.open(urllib.request.Request(SOURCE + "/reset", method="POST"), timeout=2):
        pass
    args = [str(binary), "--no-conf", "-n", f"--dir={folder}", "--out=fixture.bin", "-c",
            f"--state-dir={folder / 'state'}", "--state-save-interval=1", "--stream-max-connections=16",
            "--file-allocation=none", "--allow-overwrite=true", "--auto-file-renaming=false", "--console-log-level=warn"]
    if stable:
        args.append("--gid=0000000000000007")
    process = None
    with (folder / "first.log").open("wb") as log:
        try:
            process = subprocess.Popen(args + ["--enable-rpc=true", "--rpc-listen-all=false", "--rpc-listen-port=8844",
                                               "--rpc-secret=fixture-only", SOURCE + "/fixture.bin"],
                                       stdout=log, stderr=subprocess.STDOUT, cwd=folder)
            deadline = time.monotonic() + 12
            task = None
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("first process exited before pause")
                try:
                    active = rpc("tellActive", [["gid", "completedLength", "status"]])
                    task = next((item for item in active if int(item.get("completedLength", 0)) >= 1024 * 1024), None)
                    if task:
                        break
                except (OSError, RuntimeError):
                    pass
                time.sleep(.08)
            if task is None:
                raise RuntimeError("no partial checkpoint")
            rpc("pause", [task["gid"]])
            time.sleep(.15)
            rpc("shutdown")
            process.wait(timeout=12)
            process = None
            before = info()["sent"]
            resumed = subprocess.run(args + [SOURCE + "/fixture.bin"], capture_output=True, timeout=20, cwd=folder)
            (folder / "second.log").write_bytes(resumed.stdout + resumed.stderr)
            with (folder / "fixture.bin").open("rb") as source:
                digest = hashlib.file_digest(source, "sha256").hexdigest()
            result = {"variant": variant, "first_completed": int(task["completedLength"]),
                      "second_response_bytes": info()["sent"] - before, "total_size": 16777216,
                      "exit_code": resumed.returncode, "sha256_matched": digest == EXPECTED}
            assert resumed.returncode == 0 and digest == EXPECTED
            return result
        finally:
            if process is not None and process.poll() is None:
                try:
                    rpc("forceShutdown")
                    process.wait(timeout=3)
                except Exception:
                    process.kill()
                    process.wait()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    assert hashlib.sha256(args.binary.read_bytes()).hexdigest() == "08afaf2a44811d38e7ce538da719ab06d6925bcaad1231ee7b92c497f58e5aac"
    assert info()["sha256"] == EXPECTED
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=False)
    results = []
    for stable in [False, True]:
        result = check(args.binary, root, stable)
        results.append(result)
        print(json.dumps(result), flush=True)
        (root / "summary.json").write_text(json.dumps(results, indent=2) + "\n")
    assert results[0]["second_response_bytes"] == results[0]["total_size"]
    assert results[1]["second_response_bytes"] < results[1]["total_size"]


if __name__ == "__main__":
    main()
