#!/usr/bin/env python3
"""Process-restart resume probe for the TurboDL CLI.

The probe serves a deterministic Range-capable file on loopback, starts the
real CLI in a child process, kills that process without a graceful shutdown,
then starts the same command again. It verifies the final SHA-256 and records
how many response-body bytes each run received.

This is intentionally a separate process. Dropping a TurboClient inside one
JVM does not prove that progress survives an Android process death or a
desktop process kill.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import re
import struct
import subprocess
import sys
import threading
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Sequence


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_JAR = ROOT / "turbodl-cli" / "build" / "libs" / "turbodl-cli-all.jar"
DEFAULT_OUTPUT_DIR = ROOT / "_bench" / "results" / "restart-resume"
RANGE_RE = re.compile(r"^bytes=(\d+)-(\d*)$")


class ProbeError(RuntimeError):
    """A restart-resume acceptance check failed."""


def build_payload(size: int) -> bytes:
    if size <= 0:
        raise ValueError("size must be positive")
    # Non-periodic at ordinary block sizes so an offset error cannot hide.
    return random.Random(0x9E3779B9).randbytes(size)


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            block = handle.read(1024 * 1024)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


def parse_progress_sidecar(path: Path) -> dict[str, int]:
    """Parse TDLP v1/v2 sidecars and return grid/durable byte counts."""
    raw = path.read_bytes()
    if len(raw) < 20 or raw[:4] != b"TDLP":
        raise ValueError(f"invalid progress sidecar header: {path}")
    version = struct.unpack_from(">i", raw, 4)[0]
    offset = 8
    if version == 2:
        identity_length = struct.unpack_from(">i", raw, offset)[0]
        offset += 4
        if not 1 <= identity_length <= 512 or len(raw) < offset + identity_length + 12:
            raise ValueError(f"invalid progress sidecar identity: {path}")
        identity = raw[offset : offset + identity_length]
        if not identity.decode("utf-8", errors="replace").strip():
            raise ValueError(f"empty progress sidecar identity: {path}")
        offset += identity_length
    elif version != 1:
        raise ValueError(f"unsupported progress sidecar version {version}: {path}")
    total, count = struct.unpack_from(">qi", raw, offset)
    offset += 12
    if count < 0 or count > 2_000_000:
        raise ValueError(f"invalid progress sidecar piece count: {path}")
    expected = offset + count * 24
    if len(raw) != expected:
        raise ValueError(
            f"invalid progress sidecar length {len(raw)} != {expected}: {path}"
        )
    completed = 0
    covered = 0
    for _ in range(count):
        start, end, written = struct.unpack(">qqq", raw[offset : offset + 24])
        offset += 24
        if start < 0 or end < start or written < 0 or written > end - start + 1:
            raise ValueError(f"invalid progress piece in {path}")
        covered += end - start + 1
        completed += written
    return {
        "total": total,
        "pieces": count,
        "covered_bytes": covered,
        "completed_bytes": completed,
    }


def find_progress_sidecar(destination: Path) -> Path | None:
    parts_dir = destination.parent / ".turbodl-parts"
    candidates = sorted(parts_dir.glob("*/progress.tdlp"))
    return candidates[0] if len(candidates) == 1 else None


def parse_single_range(header: str | None, size: int) -> tuple[int, int] | None:
    """Return inclusive (start, end), or None when no Range header is present."""
    if not header:
        return None
    match = RANGE_RE.fullmatch(header.strip())
    if not match:
        raise ValueError("unsupported Range header")
    start = int(match.group(1))
    end = int(match.group(2)) if match.group(2) else size - 1
    if start < 0 or start >= size or end < start:
        raise ValueError("unsatisfiable Range header")
    return start, min(end, size - 1)


def resume_was_reused(timing_output: str) -> bool:
    """Read the CLI's metadata note instead of guessing from final file size."""
    notes = re.findall(r'\\?"resume\\?":\\?"([^"\\]*)', timing_output)
    return any("parts=true" in note and "discard=false" in note for note in notes)


@dataclass
class RequestRecord:
    range_header: str | None
    status: int
    sent_bytes: int
    started_at: str


class ProbeServer:
    def __init__(
        self,
        payload: bytes,
        *,
        etag: str | None,
        last_modified: str | None,
        chunk_delay_ms: int,
    ) -> None:
        self.payload = payload
        self.etag = etag
        self.last_modified = last_modified
        self.chunk_delay_s = chunk_delay_ms / 1000.0
        self._lock = threading.Lock()
        self._records: list[RequestRecord] = []
        self.bytes_sent = 0
        owner = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt: str, *args: Any) -> None:
                return

            def handle(self) -> None:
                try:
                    super().handle()
                except (ConnectionResetError, ConnectionAbortedError, OSError):
                    # Force-killing the client is the point of this probe.
                    return

            def do_HEAD(self) -> None:
                self.send_response(200)
                self._headers(len(owner.payload))
                self.end_headers()

            def do_GET(self) -> None:
                started_at = time.strftime(
                    "%Y-%m-%dT%H:%M:%SZ", time.gmtime()
                )
                if self.path.split("?", 1)[0] != "/payload.bin":
                    self.send_response(404)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return

                range_header = self.headers.get("Range")
                try:
                    selected = parse_single_range(range_header, len(owner.payload))
                except ValueError:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{len(owner.payload)}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return

                if selected is None:
                    start, end = 0, len(owner.payload) - 1
                    status = 200
                    self.send_response(status)
                    self._headers(len(owner.payload))
                else:
                    start, end = selected
                    status = 206
                    length = end - start + 1
                    self.send_response(status)
                    self._headers(length)
                    self.send_header(
                        "Content-Range",
                        f"bytes {start}-{end}/{len(owner.payload)}",
                    )
                self.end_headers()

                sent = 0
                try:
                    position = start
                    while position <= end:
                        count = min(64 * 1024, end - position + 1)
                        self.wfile.write(owner.payload[position : position + count])
                        self.wfile.flush()
                        sent += count
                        with owner._lock:
                            owner.bytes_sent += count
                        position += count
                        if owner.chunk_delay_s > 0:
                            time.sleep(owner.chunk_delay_s)
                except (BrokenPipeError, ConnectionResetError, OSError):
                    pass
                finally:
                    with owner._lock:
                        owner._records.append(
                            RequestRecord(
                                range_header=range_header,
                                status=status,
                                sent_bytes=sent,
                                started_at=started_at,
                            )
                        )

            def _headers(self, content_length: int) -> None:
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(content_length))
                if owner.etag:
                    self.send_header("ETag", owner.etag)
                if owner.last_modified:
                    self.send_header("Last-Modified", owner.last_modified)

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(
            target=self.httpd.serve_forever,
            name="turbodl-restart-probe-http",
            daemon=True,
        )

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.httpd.server_port}/payload.bin"

    def start(self) -> None:
        self.thread.start()

    def stop(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=5)

    def records(self) -> list[dict[str, Any]]:
        with self._lock:
            return [asdict(record) for record in self._records]


def build_cli_command(
    *,
    java_exe: str,
    jar: Path,
    url: str,
    destination: Path,
    connections: int,
    expected_sha256: str,
    progress_interval_ms: int,
) -> list[str]:
    return [
        java_exe,
        "-jar",
        str(jar),
        url,
        "-o",
        str(destination),
        "-c",
        str(connections),
        "--no-warmup",
        "--no-slow-start",
        "--no-adaptive",
        "--sha256",
        expected_sha256,
        "--progress-interval",
        str(progress_interval_ms),
        "--timing",
    ]


def kill_exact_process_tree(
    pid: int,
    *,
    process: subprocess.Popen[str] | None = None,
) -> None:
    if os.name == "nt":
        completed = subprocess.run(
            ["taskkill", "/PID", str(pid), "/T", "/F"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )
        if completed.returncode not in (0, 128):
            if process is not None and process.poll() is not None:
                return
            raise ProbeError(
                f"taskkill failed for exact PID {pid}: {completed.stdout.strip()}"
            )
    else:
        os.kill(pid, 9)


def _wait_for_kill_point(
    process: subprocess.Popen[str],
    server: ProbeServer,
    threshold: int,
    persisted_threshold: int,
    destination: Path,
    timeout_s: float,
) -> int:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        sidecar = find_progress_sidecar(destination)
        if sidecar is not None:
            try:
                snapshot = parse_progress_sidecar(sidecar)
            except (OSError, ValueError):
                snapshot = None
            if (
                snapshot is not None
                and server.bytes_sent >= threshold
                and snapshot["completed_bytes"] >= persisted_threshold
            ):
                return snapshot["completed_bytes"]
        if process.poll() is not None:
            output = process.stdout.read() if process.stdout else ""
            raise ProbeError(
                "first CLI process exited before force-kill threshold; "
                f"exit={process.returncode}, output={output[-2000:]}"
            )
        time.sleep(0.02)
    raise ProbeError(
        f"timed out waiting for server_bytes>={threshold} and "
        f"durable_sidecar_bytes>={persisted_threshold}"
    )


def _run_process(
    command: Sequence[str],
    *,
    timeout_s: float,
) -> tuple[int, str]:
    process = subprocess.Popen(
        list(command),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    try:
        output, _ = process.communicate(timeout=timeout_s)
    except subprocess.TimeoutExpired:
        kill_exact_process_tree(process.pid)
        output, _ = process.communicate(timeout=10)
        raise ProbeError(
            f"CLI process timed out after {timeout_s:.0f}s; "
            f"tail={output[-2000:]}"
        )
    return process.returncode, output


def _sidecars(destination: Path) -> list[dict[str, Any]]:
    parts_dir = destination.parent / ".turbodl-parts"
    if not parts_dir.exists():
        return []
    items: list[dict[str, Any]] = []
    for path in sorted(parts_dir.rglob("*")):
        if path.is_file():
            items.append(
                {
                    "path": str(path.relative_to(parts_dir)),
                    "bytes": path.stat().st_size,
                }
            )
    return items


def run_probe(args: argparse.Namespace) -> dict[str, Any]:
    jar = args.jar.resolve()
    if not jar.is_file():
        raise ProbeError(f"CLI jar does not exist: {jar}")
    payload = build_payload(args.size)
    expected = sha256_bytes(payload)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    run_dir = args.output_dir.resolve() / f"restart-resume-{stamp}"
    run_dir.mkdir(parents=True, exist_ok=False)
    destination = run_dir / "payload.bin"
    command = build_cli_command(
        java_exe=args.java,
        jar=jar,
        url="",  # filled after the server binds
        destination=destination,
        connections=args.connections,
        expected_sha256=expected,
        progress_interval_ms=args.progress_interval_ms,
    )

    server = ProbeServer(
        payload,
        etag=args.etag,
        last_modified=args.last_modified,
        chunk_delay_ms=args.chunk_delay_ms,
    )
    server.start()
    command[3] = server.url
    first_output = ""
    second_output = ""
    first_exit: int | None = None
    first_bytes = 0
    second_bytes = 0
    first_durable_bytes = 0
    durable_after_kill = 0
    partial_target_bytes = 0
    sidecars_after_kill: list[dict[str, Any]] = []
    second_exit: int | None = None
    result: dict[str, Any] = {
        "status": "FAIL",
        "jar": str(jar),
        "jar_sha256": sha256_file(jar),
        "payload_bytes": args.size,
        "payload_sha256": expected,
        "first_exit": None,
        "first_response_bytes": 0,
        "first_durable_bytes": 0,
        "partial_target_bytes_after_kill": 0,
        "durable_bytes_after_kill": 0,
        "sidecars_after_kill": [],
        "second_exit": None,
        "second_response_bytes": 0,
        "resume_metadata_reused": False,
        "command": command,
    }
    try:
        first = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
        threshold = max(1, int(args.size * args.kill_at_percent / 100))
        persisted_threshold = max(1, threshold // 2)
        try:
            first_durable_bytes = _wait_for_kill_point(
                first,
                server,
                threshold,
                persisted_threshold,
                destination,
                args.timeout_sec,
            )
            kill_exact_process_tree(first.pid, process=first)
            first_output, _ = first.communicate(timeout=10)
            first_exit = first.returncode
            if first_exit == 0:
                raise ProbeError(
                    "first CLI process completed before force-kill; "
                    "increase --chunk-delay-ms or --size"
                )
        finally:
            if first.poll() is None:
                kill_exact_process_tree(first.pid, process=first)
                first.communicate(timeout=10)
        first_bytes = server.bytes_sent
        if destination.exists():
            partial_target_bytes = destination.stat().st_size
        sidecars_after_kill = _sidecars(destination)
        sidecar = find_progress_sidecar(destination)
        if sidecar is None:
            raise ProbeError(
                "no durable sidecar exists after force-kill; there is nothing "
                "for the restarted process to resume from"
            )
        durable_after_kill = parse_progress_sidecar(sidecar)["completed_bytes"]

        server.bytes_sent = 0
        second_exit, second_output = _run_process(
            command,
            timeout_s=args.timeout_sec,
        )
        second_bytes = server.bytes_sent
        if second_exit != 0:
            raise ProbeError(
                f"resumed CLI failed with exit={second_exit}; "
                f"tail={second_output[-2000:]}"
            )
        if not destination.is_file():
            raise ProbeError("resumed CLI returned success without destination")
        actual = sha256_file(destination)
        if actual != expected:
            raise ProbeError(
                f"final SHA-256 mismatch: expected={expected} actual={actual}"
            )
        if not resume_was_reused(second_output):
            raise ProbeError(
                "CLI metadata did not report parts=true/discard=false after restart"
            )
        # Some bytes in flight at force-kill time may not be durable. The
        # sidecar gives the exact durable floor; allow half a checkpoint tail
        # of repull, but not a full redownload.
        maximum_second = args.size - durable_after_kill // 2
        if second_bytes > maximum_second:
            raise ProbeError(
                f"resume re-fetched too much: first={first_bytes} "
                f"second={second_bytes} size={args.size} "
                f"allowed_second<={maximum_second}"
            )

        result.update(
            {
                "status": "PASS",
                "first_exit": first_exit,
                "first_response_bytes": first_bytes,
                "first_durable_bytes": first_durable_bytes,
                "partial_target_bytes_after_kill": partial_target_bytes,
                "durable_bytes_after_kill": durable_after_kill,
                "sidecars_after_kill": sidecars_after_kill,
                "second_exit": second_exit,
                "second_response_bytes": second_bytes,
                "resume_metadata_reused": True,
                "requests": server.records(),
                "first_output_tail": first_output[-4000:],
                "second_output_tail": second_output[-4000:],
            }
        )
        (run_dir / "result.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        return result
    except Exception as exc:
        result.update(
            {
                "status": "FAIL",
                "error": f"{type(exc).__name__}: {exc}",
                "first_exit": first_exit,
                "first_response_bytes": first_bytes or server.bytes_sent,
                "first_durable_bytes": first_durable_bytes,
                "partial_target_bytes_after_kill": partial_target_bytes,
                "durable_bytes_after_kill": durable_after_kill,
                "sidecars_after_kill": sidecars_after_kill,
                "second_exit": second_exit,
                "second_response_bytes": second_bytes,
                "requests": server.records(),
                "first_output_tail": first_output[-4000:],
                "second_output_tail": second_output[-4000:],
            }
        )
        (run_dir / "result.json").write_text(
            json.dumps(result, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        raise
    finally:
        server.stop()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Kill the real TurboDL CLI mid-download and verify resume."
    )
    parser.add_argument("--java", default="java")
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT_DIR)
    parser.add_argument("--size", type=int, default=32 * 1024 * 1024)
    parser.add_argument("--connections", type=int, default=8)
    parser.add_argument("--kill-at-percent", type=float, default=30.0)
    parser.add_argument("--chunk-delay-ms", type=int, default=80)
    parser.add_argument("--progress-interval-ms", type=int, default=100)
    parser.add_argument("--timeout-sec", type=float, default=180.0)
    parser.add_argument("--etag", default='"restart-probe-v1"')
    parser.add_argument(
        "--last-modified",
        default="Wed, 04 Nov 2026 08:00:00 GMT",
    )
    return parser


def validate_args(parser: argparse.ArgumentParser, args: argparse.Namespace) -> None:
    if args.size <= 0:
        parser.error("--size must be positive")
    if not 1 <= args.connections <= 256:
        parser.error("--connections must be 1..256")
    if not 0 < args.kill_at_percent < 95:
        parser.error("--kill-at-percent must be in (0, 95)")
    if args.chunk_delay_ms < 0:
        parser.error("--chunk-delay-ms cannot be negative")
    if args.progress_interval_ms < 100:
        parser.error("--progress-interval-ms must be >=100")
    if args.timeout_sec <= 0:
        parser.error("--timeout-sec must be positive")


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    validate_args(parser, args)
    try:
        result = run_probe(args)
    except (ProbeError, OSError, ValueError) as exc:
        print(json.dumps({"status": "FAIL", "error": str(exc)}, ensure_ascii=True))
        return 1
    print(
        json.dumps(
            {
                "status": result["status"],
                "payload_bytes": result["payload_bytes"],
                "first_response_bytes": result["first_response_bytes"],
                "second_response_bytes": result["second_response_bytes"],
                "payload_sha256": result["payload_sha256"],
            },
            ensure_ascii=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
