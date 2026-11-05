#!/usr/bin/env python3
"""Compare curl, TurboDL, and aria2 against one real HTTP(S) object.

The runner is deliberately process-isolated: every sample starts a fresh
engine process and writes to a unique output/log pair. Traffic is reserved in
the persistent ledger before any payload request and reconciled even when a
download fails or the runner is interrupted.

The source URL is treated as a secret. It is never written to raw JSONL,
summary JSON/CSV, or console output. Only SHA-256 digests of the requested
URL variants are published.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import ipaddress
import json
import os
import random
import re
import shutil
import socket
import statistics
import subprocess
import sys
import time
import uuid
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

import traffic_budget as tb


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_TURBO_JAR = ROOT / "turbodl-cli" / "build" / "libs" / "turbodl-cli-all.jar"
DEFAULT_RUNTIME_DIR = Path(__file__).resolve().parent / ".runtime"
DEFAULT_CONNECTIONS = "1,8,16"
DEFAULT_REPEATS = 3
DEFAULT_SEED = 20261104
SUPPORTED_ENGINES = ("curl", "turbo", "aria2")
ARIA2_MAX_CONNECTION_PER_SERVER = 16

EXIT_OK = 0
EXIT_PARAMETER = 2
EXIT_VALIDATION = 3
EXIT_BUDGET = 4

_SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
_CURL_SIZE_RE = re.compile(r"^__WB_SIZE_DOWNLOAD__=(\d+)$", re.MULTILINE)
_CURL_CONNECTS_RE = re.compile(r"^__WB_NUM_CONNECTS__=(\d+)$", re.MULTILINE)
_ARIA_CN_RE = re.compile(r"\bCN:(\d+)\b")
_ARIA_PROGRESS_RE = re.compile(
    r"(?<![\w.])(\d+(?:\.\d+)?)\s*([KMGT]?i?B)\s*/\s*"
    r"\d+(?:\.\d+)?\s*[KMGT]?i?B"
)
_URL_RE = re.compile(r"https?://[^\s\"'<>]+", re.IGNORECASE)


class ParameterError(ValueError):
    """Invalid command-line or engine configuration."""


@dataclass(frozen=True)
class EngineSpec:
    name: str
    executable: Path
    version: str
    binary_sha256: str
    runtime: str = ""
    runtime_sha256: str = ""


@dataclass(frozen=True)
class DownloadSpec:
    engine: EngineSpec
    java: Path | None
    turbo_jar: Path | None
    url: str
    output_path: Path
    log_path: Path
    connections: int
    proxy: str | None
    turbo_mode: str = "fixed"
    known_size: int | None = None


@dataclass(frozen=True)
class ProcessOutcome:
    exit_code: int
    stdout: str
    stderr: str
    elapsed_s: float
    timed_out: bool = False


@dataclass(frozen=True)
class BudgetAllocation:
    run_id: str
    estimated_bytes: int
    kind: str
    engine: str
    connections: int
    attempts: int

    def metadata(self) -> dict[str, Any]:
        return {
            "kind": self.kind,
            "engine": self.engine,
            "connections": self.connections,
            "attempts": self.attempts,
            "estimated_bytes": self.estimated_bytes,
        }


@dataclass
class LeaseState:
    allocation: BudgetAllocation
    reservation: tb.Reservation | None = None
    actual_response_bytes: int = 0
    reconcile: tb.ReconcileResult | None = None
    status: str = "aborted"


class BudgetSession:
    """Reserve all planned traffic up front and reconcile every lease."""

    def __init__(
        self,
        budget: tb.TrafficBudget,
        allocations: Sequence[BudgetAllocation],
    ) -> None:
        self.budget = budget
        self.leases = {
            allocation.run_id: LeaseState(allocation=allocation)
            for allocation in allocations
        }
        if len(self.leases) != len(allocations):
            raise ParameterError("budget run IDs must be unique")

    @property
    def total_estimate_bytes(self) -> int:
        return sum(item.allocation.estimated_bytes for item in self.leases.values())

    def reserve_all(self) -> None:
        reserved: list[LeaseState] = []
        try:
            for lease in self.leases.values():
                allocation = lease.allocation
                lease.reservation = self.budget.reserve(
                    allocation.run_id,
                    allocation.estimated_bytes,
                    metadata=allocation.metadata(),
                )
                reserved.append(lease)
        except Exception:
            for lease in reserved:
                if lease.reconcile is None:
                    lease.reconcile = self.budget.reconcile(
                        lease.allocation.run_id,
                        0,
                        status="aborted",
                    )
                    lease.status = "aborted"
            raise

    def add_actual(self, run_id: str, response_bytes: int) -> None:
        if response_bytes < 0:
            raise ValueError("response_bytes must be non-negative")
        lease = self._lease(run_id)
        if lease.reconcile is not None:
            raise ValueError(f"budget lease already reconciled: {run_id}")
        lease.actual_response_bytes += response_bytes

    def reconcile(self, run_id: str, status: str) -> tb.ReconcileResult:
        if status not in tb.VALID_STATUSES:
            raise ValueError(f"invalid reconcile status: {status}")
        lease = self._lease(run_id)
        lease.status = status
        if lease.reconcile is None:
            lease.reconcile = self.budget.reconcile(
                run_id,
                lease.actual_response_bytes,
                status=status,
            )
        return lease.reconcile

    def reconcile_open(self, status: str = "aborted") -> None:
        for lease in self.leases.values():
            if lease.reconcile is None:
                self.reconcile(lease.allocation.run_id, status)

    def _lease(self, run_id: str) -> LeaseState:
        try:
            return self.leases[run_id]
        except KeyError as exc:
            raise ParameterError(f"unknown budget run ID: {run_id}") from exc


def parse_ints(value: str) -> list[int]:
    try:
        values = [int(part.strip()) for part in value.split(",") if part.strip()]
    except ValueError as exc:
        raise ParameterError(f"invalid integer list: {value!r}") from exc
    if not values:
        raise ParameterError("at least one integer is required")
    return values


def parse_engines(value: str) -> list[str]:
    engines = [part.strip().lower() for part in value.split(",") if part.strip()]
    if not engines:
        raise ParameterError("at least one engine is required")
    unknown = sorted(set(engines) - set(SUPPORTED_ENGINES))
    if unknown:
        raise ParameterError(
            f"unsupported engines: {', '.join(unknown)}; "
            f"choose from {', '.join(SUPPORTED_ENGINES)}"
        )
    if len(set(engines)) != len(engines):
        raise ParameterError("engines must not contain duplicates")
    return engines


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def validate_url_syntax(url: str) -> None:
    try:
        parts = urlsplit(url)
        _ = parts.port
    except ValueError as exc:
        raise ParameterError(f"invalid URL: {exc}") from exc
    if parts.scheme.lower() not in ("http", "https") or not parts.hostname:
        raise ParameterError("URL must be an absolute http:// or https:// URL")


def build_cache_busted_url(
    url: str,
    *,
    param: str | None,
    value: str,
) -> str:
    if not param:
        return url
    validate_url_syntax(url)
    parts = urlsplit(url)
    query = parse_qsl(parts.query, keep_blank_values=True)
    query.append((param, value))
    return urlunsplit(
        (parts.scheme, parts.netloc, parts.path, urlencode(query), parts.fragment)
    )


def validate_public_http_url(
    url: str,
    *,
    resolver: Callable[..., Sequence[Any]] = socket.getaddrinfo,
) -> None:
    validate_url_syntax(url)
    parts = urlsplit(url)
    hostname = parts.hostname.rstrip(".").lower()
    if hostname == "localhost" or hostname.endswith(".localhost"):
        raise ParameterError("localhost URLs are not allowed")

    try:
        resolved_port = parts.port or (80 if parts.scheme.lower() == "http" else 443)
        records = resolver(hostname, resolved_port, type=socket.SOCK_STREAM)
    except (OSError, socket.gaierror) as exc:
        raise ParameterError(f"cannot resolve URL host: {exc}") from exc

    addresses = []
    for record in records:
        sockaddr = record[4] if len(record) >= 5 else ()
        if sockaddr:
            addresses.append(str(sockaddr[0]))
    if not addresses:
        raise ParameterError("URL host resolved to no addresses")

    for address in addresses:
        try:
            ip = ipaddress.ip_address(address.split("%", 1)[0])
        except ValueError as exc:
            raise ParameterError(f"URL host returned an invalid address: {address}") from exc
        if not ip.is_global:
            raise ParameterError(
                "URL host resolves to a loopback/private/reserved address"
            )


def _engine_binary_sha(executable: Path) -> str:
    if not executable.is_file():
        raise ParameterError(f"engine executable does not exist: {executable}")
    return sha256_file(executable)


def _resolve_executable(value: str | Path, *, label: str) -> Path:
    candidate = Path(value)
    if candidate.is_file():
        return candidate.resolve()
    found = shutil.which(str(value))
    if not found:
        raise ParameterError(f"{label} executable not found: {value}")
    return Path(found).resolve()


def _probe_version(command: Sequence[str], *, fallback: str) -> str:
    try:
        completed = subprocess.run(
            list(command),
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=15,
        )
    except (OSError, subprocess.SubprocessError):
        return fallback
    output = (completed.stdout + "\n" + completed.stderr).strip()
    for line in output.splitlines():
        if line.strip():
            return line.strip()
    return fallback


def resolve_engine_specs(
    *,
    engines: Sequence[str],
    curl_exe: str | None,
    java_exe: str,
    turbo_jar: Path,
    aria2_exe: str | None,
) -> dict[str, EngineSpec]:
    specs: dict[str, EngineSpec] = {}
    if "curl" in engines:
        executable = _resolve_executable(curl_exe or "curl", label="curl")
        specs["curl"] = EngineSpec(
            name="curl",
            executable=executable,
            version=_probe_version(
                [str(executable), "--version"], fallback="curl unknown"
            ),
            binary_sha256=_engine_binary_sha(executable),
        )

    if "turbo" in engines:
        jar = turbo_jar.resolve()
        if not jar.is_file():
            raise ParameterError(f"TurboDL fat JAR not found: {jar}")
        java = _resolve_executable(java_exe, label="java")
        specs["turbo"] = EngineSpec(
            name="turbo",
            executable=jar,
            version=_probe_version(
                [str(java), "-jar", str(jar), "--version"],
                fallback=f"TurboDL {jar.name}",
            ),
            binary_sha256=sha256_file(jar),
            runtime=str(java),
            runtime_sha256=sha256_file(java),
        )

    if "aria2" in engines:
        if not aria2_exe:
            raise ParameterError("--aria2-exe is required when aria2 is enabled")
        executable = _resolve_executable(aria2_exe, label="aria2")
        specs["aria2"] = EngineSpec(
            name="aria2",
            executable=executable,
            version=_probe_version(
                [str(executable), "--version"], fallback="aria2 unknown"
            ),
            binary_sha256=_engine_binary_sha(executable),
        )
    return specs


def _proxy_environment(proxy: str | None) -> dict[str, str]:
    env = os.environ.copy()
    proxy_keys = (
        "HTTP_PROXY",
        "HTTPS_PROXY",
        "ALL_PROXY",
        "http_proxy",
        "https_proxy",
        "all_proxy",
        "NO_PROXY",
        "no_proxy",
    )
    for key in proxy_keys:
        env.pop(key, None)
    if proxy:
        for key in proxy_keys:
            env[key] = proxy
    return env


def build_engine_command(spec: DownloadSpec) -> list[str]:
    engine = spec.engine.name
    if engine == "curl":
        command = [
            str(spec.engine.executable),
            "--fail",
            "--location",
            "--silent",
            "--show-error",
            "--http1.1",
            "--retry",
            "0",
            "--output",
            str(spec.output_path),
            "--write-out",
            (
                "\n__WB_SIZE_DOWNLOAD__=%{size_download}"
                "\n__WB_NUM_CONNECTS__=%{num_connects}\n"
            ),
        ]
        if spec.proxy:
            command.extend(["--proxy", spec.proxy])
        command.extend(["--url", spec.url])
        return command

    if engine == "turbo":
        if spec.java is None or spec.turbo_jar is None:
            raise ParameterError("turbo command requires java and fat JAR")
        command = [
            str(spec.java),
            "-jar",
            str(spec.turbo_jar),
            spec.url,
            "-o",
            str(spec.output_path),
            "-c",
            str(spec.connections),
            "--http-policy",
            "http1",
            "--progress-interval",
            "200",
            "--json",
            "--timing",
        ]
        if spec.turbo_mode == "fixed":
            command.extend(
                ["--no-warmup", "--no-slow-start", "--no-adaptive", "--no-tail-assist"]
            )
        elif spec.turbo_mode != "production":
            raise ParameterError(f"unsupported turbo mode: {spec.turbo_mode}")
        if spec.known_size is not None:
            command.extend(["--known-size", str(spec.known_size)])
        if spec.proxy:
            command.extend(["--proxy", spec.proxy])
        return command

    if engine == "aria2":
        if not 1 <= spec.connections <= ARIA2_MAX_CONNECTION_PER_SERVER:
            raise ParameterError(
                "aria2 --max-connection-per-server must be in 1..16"
            )
        command = [
            str(spec.engine.executable),
            "--allow-overwrite=true",
            "--auto-file-renaming=false",
            "--continue=false",
            "--file-allocation=none",
            "--max-concurrent-downloads=1",
            "--split",
            str(spec.connections),
            "--max-connection-per-server",
            str(spec.connections),
            "--min-split-size=1M",
            "--connect-timeout=30",
            "--timeout=60",
            "--max-tries=1",
            "--retry-wait=0",
            "--console-log-level=notice",
            "--summary-interval=1",
            "--dir",
            str(spec.output_path.parent),
            "--out",
            spec.output_path.name,
        ]
        if spec.proxy:
            command.extend(["--all-proxy", spec.proxy])
        command.append(spec.url)
        return command

    raise ParameterError(f"unsupported engine: {engine}")


def run_process(
    command: Sequence[str],
    *,
    env: Mapping[str, str],
    timeout_s: float,
) -> ProcessOutcome:
    started = time.monotonic()
    process = subprocess.Popen(
        list(command),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=dict(env),
    )
    timed_out = False
    try:
        stdout, stderr = process.communicate(timeout=timeout_s)
    except subprocess.TimeoutExpired:
        timed_out = True
        process.kill()
        stdout, stderr = process.communicate()
    except BaseException:
        process.kill()
        process.communicate()
        raise
    return ProcessOutcome(
        exit_code=process.returncode,
        stdout=stdout or "",
        stderr=stderr or "",
        elapsed_s=time.monotonic() - started,
        timed_out=timed_out,
    )


def _parse_curl_telemetry(stdout: str) -> tuple[int | None, int | None]:
    sizes = [int(value) for value in _CURL_SIZE_RE.findall(stdout)]
    connects = [int(value) for value in _CURL_CONNECTS_RE.findall(stdout)]
    return (
        sizes[-1] if sizes else None,
        connects[-1] if connects else None,
    )


def _parse_turbo_telemetry(
    stdout: str,
) -> tuple[int | None, dict[str, Any], str]:
    downloaded_values: list[int] = []
    connection_values: list[int] = []
    completed_sha256 = ""
    for line in stdout.splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(event, dict):
            continue
        downloaded = event.get("downloaded")
        if isinstance(downloaded, int) and downloaded >= 0:
            downloaded_values.append(downloaded)
        connections = event.get("connections")
        if isinstance(connections, int) and connections >= 0:
            connection_values.append(connections)
        if event.get("type") == "completed":
            sha = event.get("sha256")
            if isinstance(sha, str):
                completed_sha256 = sha.lower()
    telemetry: dict[str, Any] = {
        "source": "turbo-json-progress",
        "max_connections": max(connection_values, default=0),
        "median_connections": (
            statistics.median(connection_values) if connection_values else 0
        ),
        "connection_samples": len(connection_values),
    }
    return (
        max(downloaded_values, default=None),
        telemetry,
        completed_sha256,
    )


def _human_bytes(value: str, unit: str) -> int:
    number = float(value)
    normalized = unit.upper()
    factors = {
        "B": 1,
        "KB": 1000,
        "KIB": 1024,
        "MB": 1000**2,
        "MIB": 1024**2,
        "GB": 1000**3,
        "GIB": 1024**3,
        "TB": 1000**4,
        "TIB": 1024**4,
    }
    return int(number * factors[normalized])


def _parse_aria2_telemetry(stdout: str) -> tuple[int | None, dict[str, Any]]:
    downloaded_values = [
        _human_bytes(value, unit)
        for value, unit in _ARIA_PROGRESS_RE.findall(stdout)
    ]
    connection_values = [int(value) for value in _ARIA_CN_RE.findall(stdout)]
    telemetry: dict[str, Any] = {
        "source": "aria2-console",
        "max_connections": max(connection_values, default=0),
        "median_connections": (
            statistics.median(connection_values) if connection_values else 0
        ),
        "connection_samples": len(connection_values),
    }
    return (
        max(downloaded_values, default=None),
        telemetry,
    )


def _redact_urls(text: str, secret_urls: Iterable[str]) -> str:
    sanitized = text
    for secret in secret_urls:
        if secret:
            sanitized = sanitized.replace(secret, "[redacted-url]")
    return _URL_RE.sub("[redacted-url]", sanitized)


def _sanitize_diagnostic(text: str, secret_urls: Iterable[str]) -> str:
    sanitized = _redact_urls(text, secret_urls)
    lines = [line.strip() for line in sanitized.splitlines() if line.strip()]
    return "\n".join(lines[-12:])[-2000:]


def execute_sample(
    spec: DownloadSpec,
    *,
    sample_id: str,
    budget_run_id: str,
    phase: str,
    repeat: int | None,
    expected_size: int,
    expected_sha256: str,
    cache_busting_url_sha256: str,
    timeout_s: float,
    proxy: str | None,
    process_runner: Callable[..., ProcessOutcome] = run_process,
) -> dict[str, Any]:
    command = build_engine_command(spec)
    spec.output_path.parent.mkdir(parents=True, exist_ok=True)
    spec.log_path.parent.mkdir(parents=True, exist_ok=True)
    outcome = ProcessOutcome(
        exit_code=127,
        stdout="",
        stderr="",
        elapsed_s=0.0,
    )
    launch_error = ""
    try:
        outcome = process_runner(
            command,
            env=_proxy_environment(proxy),
            timeout_s=timeout_s,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        launch_error = f"{type(exc).__name__}: {exc}"

    log_text = (
        f"command_engine={spec.engine.name}\n"
        f"exit_code={outcome.exit_code}\n"
        f"timed_out={str(outcome.timed_out).lower()}\n"
        f"elapsed_s={outcome.elapsed_s:.6f}\n"
        f"--- stdout ---\n{_redact_urls(outcome.stdout, (spec.url,))}\n"
        f"--- stderr ---\n{_redact_urls(outcome.stderr, (spec.url,))}\n"
    )
    if launch_error:
        log_text += (
            "--- launch error ---\n"
            f"{_redact_urls(launch_error, (spec.url,))}\n"
        )
    spec.log_path.write_text(log_text, encoding="utf-8", errors="replace")

    actual_size = spec.output_path.stat().st_size if spec.output_path.exists() else 0
    actual_sha256 = sha256_file(spec.output_path) if spec.output_path.exists() else ""

    telemetry: dict[str, Any] = {"source": "unavailable"}
    parsed_response_bytes: int | None = None
    engine_completed_sha256 = ""
    if spec.engine.name == "curl":
        parsed_response_bytes, num_connects = _parse_curl_telemetry(outcome.stdout)
        telemetry = {
            "source": "curl-write-out",
            "num_connects": num_connects,
            "requested_streams": 1,
        }
    elif spec.engine.name == "turbo":
        (
            parsed_response_bytes,
            telemetry,
            engine_completed_sha256,
        ) = _parse_turbo_telemetry(outcome.stdout)
    elif spec.engine.name == "aria2":
        parsed_response_bytes, telemetry = _parse_aria2_telemetry(outcome.stdout)

    actual_response_bytes = max(actual_size, parsed_response_bytes or 0)
    exit_ok = outcome.exit_code == 0 and not outcome.timed_out and not launch_error
    size_ok = actual_size == expected_size
    sha_ok = actual_sha256 == expected_sha256
    engine_sha_ok = not engine_completed_sha256 or (
        engine_completed_sha256 == expected_sha256
    )
    ok = exit_ok and size_ok and sha_ok and engine_sha_ok
    goodput = (
        actual_size / (1024 * 1024) / outcome.elapsed_s
        if ok and outcome.elapsed_s > 0
        else 0.0
    )
    error_text = ""
    if not ok:
        error_text = _sanitize_diagnostic(
            "\n".join(
                part
                for part in (outcome.stderr, outcome.stdout, launch_error)
                if part
            ),
            (spec.url,),
        )
    return {
        "run_id": sample_id,
        "timestamp": _utc_now(),
        "phase": phase,
        "repeat": repeat,
        "engine": spec.engine.name,
        "version": spec.engine.version,
        "binary_sha256": spec.engine.binary_sha256,
        "runtime": spec.engine.runtime,
        "runtime_sha256": spec.engine.runtime_sha256,
        "connections": spec.connections,
        "turbo_mode": spec.turbo_mode if spec.engine.name == "turbo" else None,
        "known_size_bytes": spec.known_size if spec.engine.name == "turbo" else None,
        "cache_busting_url_sha256": cache_busting_url_sha256,
        "elapsed_s": outcome.elapsed_s,
        "goodput_mib_s": goodput,
        "exit_code": outcome.exit_code,
        "timed_out": outcome.timed_out,
        "expected_size_bytes": expected_size,
        "actual_size_bytes": actual_size,
        "actual_response_bytes": actual_response_bytes,
        "expected_sha256": expected_sha256,
        "actual_sha256": actual_sha256,
        "engine_completed_sha256": engine_completed_sha256,
        "connection_telemetry": telemetry,
        "budget_run_id": budget_run_id,
        "log_path": str(spec.log_path.resolve()),
        "output_path": str(spec.output_path.resolve()),
        "ok": ok,
        "validation": {
            "exit_ok": exit_ok,
            "size_ok": size_ok,
            "sha256_ok": sha_ok,
            "engine_sha256_ok": engine_sha_ok,
        },
        "error": error_text,
    }


def build_allocations(
    *,
    run_id: str,
    size_bytes: int,
    engines: Sequence[str],
    connections: Sequence[int],
    repeats: int,
    prewarm_engines: Sequence[str] = (),
) -> list[BudgetAllocation]:
    allocations: list[BudgetAllocation] = []
    for prewarm_engine in prewarm_engines:
        allocations.append(
            BudgetAllocation(
                run_id=f"{run_id}-prewarm-{prewarm_engine}",
                estimated_bytes=size_bytes,
                kind="prewarm",
                engine=prewarm_engine,
                connections=1,
                attempts=1,
            )
        )
    for engine in engines:
        for connection_count in connections:
            allocations.append(
                BudgetAllocation(
                    run_id=f"{run_id}-{engine}-c{connection_count}",
                    estimated_bytes=size_bytes * repeats,
                    kind="compare",
                    engine=engine,
                    connections=connection_count,
                    attempts=repeats,
                )
            )
    return allocations


def build_schedule(
    *,
    engines: Sequence[str],
    connections: Sequence[int],
    repeats: int,
    seed: int,
) -> list[tuple[str, int, int]]:
    # Keep every engine's sample for one (repeat, connection) adjacent in
    # time. Shuffling the whole matrix moves "curl c16" and "aria2 c16"
    # minutes apart, so proxy/CDN drift masquerades as an engine difference.
    # Randomize only the order inside each small comparison block.
    schedule: list[tuple[str, int, int]] = []
    rng = random.Random(seed)
    for repeat in range(repeats):
        for connection_count in connections:
            block = [
                (engine, connection_count, repeat)
                for engine in engines
            ]
            rng.shuffle(block)
            schedule.extend(block)
    return schedule


def _percentile_nearest_rank(values: Sequence[float], percentile: float) -> float:
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(len(ordered) * percentile - 1e-12)))
    return ordered[index]


def summarize_rows(rows: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    grouped: dict[tuple[str, int], list[Mapping[str, Any]]] = {}
    for row in rows:
        if row.get("phase") != "compare":
            continue
        key = (str(row["engine"]), int(row["connections"]))
        grouped.setdefault(key, []).append(row)

    summary: list[dict[str, Any]] = []
    for (engine, connection_count), samples in grouped.items():
        good = [row for row in samples if bool(row.get("ok"))]
        goodputs = [float(row["goodput_mib_s"]) for row in good]
        elapsed = [float(row["elapsed_s"]) for row in good]
        summary.append(
            {
                "engine": engine,
                "version": str(samples[0].get("version", "")),
                "binary_sha256": str(samples[0].get("binary_sha256", "")),
                "turbo_mode": samples[0].get("turbo_mode"),
                "connections": connection_count,
                "samples": len(samples),
                "successes": len(good),
                "failures": len(samples) - len(good),
                "ok": len(good) == len(samples),
                "median_goodput_mib_s": (
                    statistics.median(goodputs) if goodputs else 0.0
                ),
                "p95_goodput_mib_s": (
                    _percentile_nearest_rank(goodputs, 0.95) if goodputs else 0.0
                ),
                "min_goodput_mib_s": min(goodputs, default=0.0),
                "max_goodput_mib_s": max(goodputs, default=0.0),
                "median_elapsed_s": (
                    statistics.median(elapsed) if elapsed else 0.0
                ),
                "actual_sha256": sorted(
                    {str(row.get("actual_sha256", "")) for row in samples}
                ),
                "cache_busting_url_sha256s": sorted(
                    {
                        str(row.get("cache_busting_url_sha256", ""))
                        for row in samples
                    }
                ),
                "budget_run_ids": sorted(
                    {str(row.get("budget_run_id", "")) for row in samples}
                ),
                "log_paths": [str(row.get("log_path", "")) for row in samples],
                "connection_telemetry": [
                    row.get("connection_telemetry", {}) for row in samples
                ],
            }
        )
    summary.sort(
        key=lambda row: (
            -float(row["median_goodput_mib_s"]),
            str(row["engine"]),
            int(row["connections"]),
        )
    )
    return summary


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="microseconds").replace(
        "+00:00", "Z"
    )


def _write_jsonl_line(handle: Any, row: Mapping[str, Any]) -> None:
    handle.write(
        json.dumps(
            row,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
        )
        + "\n"
    )
    handle.flush()


def _write_summary_files(
    *,
    run_dir: Path,
    run_metadata: Mapping[str, Any],
    rows: Sequence[Mapping[str, Any]],
    budget_report: tb.BudgetReport,
    budget_leases: Mapping[str, LeaseState],
) -> tuple[Path, Path]:
    ranking = summarize_rows(rows)
    failures = [
        {
            key: row.get(key)
            for key in (
                "run_id",
                "phase",
                "engine",
                "connections",
                "repeat",
                "exit_code",
                "actual_size_bytes",
                "actual_sha256",
                "budget_run_id",
                "log_path",
                "error",
            )
        }
        for row in rows
        if not bool(row.get("ok"))
    ]
    budget_leases_payload = [
        {
            "run_id": lease.allocation.run_id,
            "kind": lease.allocation.kind,
            "engine": lease.allocation.engine,
            "connections": lease.allocation.connections,
            "estimated_bytes": lease.allocation.estimated_bytes,
            "actual_response_bytes": lease.actual_response_bytes,
            "status": lease.status,
            "overrun": bool(lease.reconcile and lease.reconcile.overrun),
            "remaining_bytes": (
                lease.reconcile.remaining_bytes if lease.reconcile else None
            ),
        }
        for lease in sorted(
            budget_leases.values(), key=lambda item: item.allocation.run_id
        )
    ]
    payload = {
        "run": dict(run_metadata),
        "ranking": ranking,
        "failures": failures,
        "prewarm": [row for row in rows if row.get("phase") == "prewarm"],
        "budget": {
            "report": asdict(budget_report),
            "leases": budget_leases_payload,
            "overrun": any(
                lease.reconcile is not None and lease.reconcile.overrun
                for lease in budget_leases.values()
            ),
        },
    }
    summary_json = run_dir / "summary.json"
    summary_csv = run_dir / "summary.csv"
    summary_json.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
        encoding="utf-8",
    )
    csv_fields = (
        "engine",
        "version",
        "binary_sha256",
        "turbo_mode",
        "connections",
        "samples",
        "successes",
        "failures",
        "ok",
        "median_goodput_mib_s",
        "p95_goodput_mib_s",
        "min_goodput_mib_s",
        "max_goodput_mib_s",
        "median_elapsed_s",
        "actual_sha256",
        "cache_busting_url_sha256s",
        "budget_run_ids",
        "log_paths",
        "connection_telemetry",
    )
    with summary_csv.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=csv_fields)
        writer.writeheader()
        for row in ranking:
            csv_row = dict(row)
            for key in (
                "actual_sha256",
                "cache_busting_url_sha256s",
                "budget_run_ids",
                "log_paths",
                "connection_telemetry",
            ):
                csv_row[key] = json.dumps(
                    csv_row[key], ensure_ascii=False, separators=(",", ":")
                )
            writer.writerow(csv_row)
    return summary_json, summary_csv


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Run curl, TurboDL, and aria2 against one real HTTP(S) object "
            "with persistent traffic reservation and integrity checks."
        )
    )
    parser.add_argument("--url", required=True, help="source URL (not logged)")
    parser.add_argument("--size", type=int, required=True, help="expected bytes")
    parser.add_argument(
        "--sha256",
        required=True,
        help="independently known full-file SHA-256",
    )
    parser.add_argument(
        "--engines",
        default="curl,turbo,aria2",
        help="comma-separated subset of curl,turbo,aria2",
    )
    parser.add_argument(
        "--connections",
        default=DEFAULT_CONNECTIONS,
        help="comma-separated connection counts (aria2 max is 16)",
    )
    parser.add_argument("--repeats", type=int, default=DEFAULT_REPEATS)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument(
        "--proxy",
        help="same proxy URL passed explicitly to every engine",
    )
    parser.add_argument(
        "--cache-bust-param",
        help="query parameter used to add a per-repeat cache buster",
    )
    parser.add_argument("--prewarm", action="store_true")
    parser.add_argument(
        "--prewarm-engine",
        choices=SUPPORTED_ENGINES,
        default=None,
        help="optional single-engine prewarm override; default warms all selected engines",
    )
    parser.add_argument("--timeout", type=float, default=900.0)
    parser.add_argument("--curl-exe", help="curl executable (default: PATH)")
    parser.add_argument("--java-exe", default="java")
    parser.add_argument("--turbo-jar", type=Path, default=DEFAULT_TURBO_JAR)
    parser.add_argument(
        "--turbo-mode",
        choices=("production", "fixed"),
        default="production",
        help="TurboDL production policy or the fixed A/B control policy",
    )
    parser.add_argument(
        "--known-size",
        type=int,
        help="exact object size passed to TurboDL as an app-style metadata hint",
    )
    parser.add_argument("--aria2-exe", help="explicit aria2c executable")
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=DEFAULT_RUNTIME_DIR,
        help="parent directory for immutable run artifacts",
    )
    parser.add_argument(
        "--budget-data-dir",
        type=Path,
        default=tb.default_data_dir(),
        help="persistent traffic ledger directory",
    )
    total_group = parser.add_mutually_exclusive_group()
    total_group.add_argument("--total-mib", type=int)
    total_group.add_argument("--total-bytes", type=int)
    parser.add_argument("--dry-run", action="store_true")
    return parser


def _validate_args(
    parser: argparse.ArgumentParser,
    args: argparse.Namespace,
) -> tuple[list[str], list[int]]:
    if args.size <= 0:
        parser.error("--size must be a positive integer")
    expected_sha = str(args.sha256).lower()
    if not _SHA256_RE.fullmatch(expected_sha):
        parser.error("--sha256 must be 64 hexadecimal characters")
    args.sha256 = expected_sha
    if args.repeats <= 0:
        parser.error("--repeats must be positive")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    if args.known_size is not None and args.known_size != args.size:
        parser.error("--known-size must equal --size when supplied")
    try:
        engines = parse_engines(args.engines)
        connections = parse_ints(args.connections)
    except ParameterError as exc:
        parser.error(str(exc))
    if any(value <= 0 for value in connections):
        parser.error("--connections values must be positive")
    if "aria2" in engines and any(
        value > ARIA2_MAX_CONNECTION_PER_SERVER for value in connections
    ):
        parser.error("aria2 requires --connections values in 1..16")
    if args.prewarm and args.prewarm_engine is not None and args.prewarm_engine not in engines:
        parser.error("--prewarm-engine must be one of --engines")
    if args.proxy:
        try:
            validate_url_syntax(args.proxy)
        except ParameterError as exc:
            parser.error(f"--proxy: {exc}")
    return engines, connections


def _remaining_bytes(budget: tb.TrafficBudget) -> int:
    return budget.remaining_bytes()


def _print_dry_run(
    *,
    estimate_bytes: int,
    remaining_bytes: int,
    allocation_count: int,
    sample_count: int,
) -> bool:
    sufficient = estimate_bytes <= remaining_bytes
    print(
        f"dry-run estimate={estimate_bytes} bytes "
        f"remaining={remaining_bytes} bytes "
        f"allocations={allocation_count} samples={sample_count} "
        f"sufficient={str(sufficient).lower()}"
    )
    return sufficient


def _make_engine_specs(
    args: argparse.Namespace,
    engines: Sequence[str],
) -> dict[str, EngineSpec]:
    return resolve_engine_specs(
        engines=engines,
        curl_exe=args.curl_exe,
        java_exe=args.java_exe,
        turbo_jar=args.turbo_jar,
        aria2_exe=args.aria2_exe,
    )


def _sample_id(run_id: str, phase: str, engine: str, connections: int, repeat: int) -> str:
    return f"{run_id}-{phase}-{engine}-c{connections}-r{repeat}"


def _round_url_sha(
    *,
    url: str,
    cache_bust_param: str | None,
    seed: int,
    repeat: int,
    run_id: str,
) -> tuple[str, str]:
    token = hashlib.sha256(
        f"{seed}:{sha256_text(url)}:{repeat}:{run_id}".encode("utf-8")
    ).hexdigest()[:24]
    requested = build_cache_busted_url(
        url,
        param=cache_bust_param,
        value=token,
    )
    return requested, sha256_text(requested)


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    engines, connections = _validate_args(parser, args)

    if args.total_bytes is not None:
        total_bytes = args.total_bytes
    elif args.total_mib is not None:
        total_bytes = args.total_mib * tb.MIB
    else:
        total_bytes = tb.DEFAULT_TOTAL_BYTES
    try:
        budget = tb.TrafficBudget(args.budget_data_dir, total_bytes=total_bytes)
    except (OSError, ValueError) as exc:
        print(f"budget configuration error: {exc}", file=sys.stderr)
        return EXIT_PARAMETER

    run_id = (
        datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        + "-"
        + uuid.uuid4().hex[:8]
    )
    prewarm_engines = (
        ((args.prewarm_engine,) if args.prewarm_engine else tuple(engines))
        if args.prewarm
        else ()
    )
    allocations = build_allocations(
        run_id=run_id,
        size_bytes=args.size,
        engines=engines,
        connections=connections,
        repeats=args.repeats,
        prewarm_engines=prewarm_engines,
    )
    estimate_bytes = sum(item.estimated_bytes for item in allocations)
    compare_sample_count = len(engines) * len(connections) * args.repeats

    try:
        remaining_bytes = _remaining_bytes(budget)
    except tb.BudgetError as exc:
        print(f"budget error: {exc}", file=sys.stderr)
        return EXIT_PARAMETER
    if args.dry_run:
        sufficient = _print_dry_run(
            estimate_bytes=estimate_bytes,
            remaining_bytes=remaining_bytes,
            allocation_count=len(allocations),
            sample_count=compare_sample_count + len(prewarm_engines),
        )
        return EXIT_OK if sufficient else EXIT_BUDGET
    if estimate_bytes > remaining_bytes:
        print(
            f"budget insufficient: requested={estimate_bytes} "
            f"remaining={remaining_bytes}",
            file=sys.stderr,
        )
        return EXIT_BUDGET

    try:
        validate_url_syntax(args.url)
        validate_public_http_url(args.url)
        specs = _make_engine_specs(args, engines)
    except ParameterError as exc:
        print(f"parameter error: {exc}", file=sys.stderr)
        return EXIT_PARAMETER

    budget_session = BudgetSession(budget, allocations)
    try:
        budget_session.reserve_all()
    except tb.BudgetExceeded as exc:
        print(f"budget insufficient: {exc}", file=sys.stderr)
        return EXIT_BUDGET
    except tb.BudgetError as exc:
        print(f"budget error: {exc}", file=sys.stderr)
        return EXIT_PARAMETER

    run_dir = args.output_dir.resolve() / f"compare-{run_id}"
    try:
        run_dir.mkdir(parents=True, exist_ok=False)
    except OSError as exc:
        budget_session.reconcile_open("aborted")
        print(f"cannot create output directory: {exc}", file=sys.stderr)
        return EXIT_PARAMETER

    raw_path = run_dir / "raw.jsonl"
    rows: list[dict[str, Any]] = []
    successful = True
    budget_overrun = False
    compare_completed: dict[str, int] = {}
    compare_expected = {
        allocation.run_id: allocation.attempts
        for allocation in allocations
        if allocation.kind == "compare"
    }

    try:
        with raw_path.open("w", encoding="utf-8", newline="\n") as raw_handle:
            for allocation in (
                item for item in allocations if item.kind == "prewarm"
            ):
                requested_url, requested_url_sha = _round_url_sha(
                    url=args.url,
                    cache_bust_param=args.cache_bust_param,
                    seed=args.seed,
                    repeat=-1,
                    run_id=run_id,
                )
                sample_id = _sample_id(
                    run_id, "prewarm", allocation.engine, 1, 0
                )
                row = execute_sample(
                    DownloadSpec(
                        engine=specs[allocation.engine],
                        java=(
                            Path(specs["turbo"].runtime)
                            if "turbo" in specs and specs["turbo"].runtime
                            else None
                        ),
                        turbo_jar=args.turbo_jar.resolve()
                        if "turbo" in specs
                        else None,
                        url=requested_url,
                        output_path=run_dir
                        / f"prewarm-{allocation.engine}-c1.bin",
                        log_path=run_dir
                        / f"prewarm-{allocation.engine}-c1.log",
                        connections=1,
                        proxy=args.proxy,
                        turbo_mode=args.turbo_mode,
                        known_size=args.known_size,
                    ),
                    sample_id=sample_id,
                    budget_run_id=allocation.run_id,
                    phase="prewarm",
                    repeat=None,
                    expected_size=args.size,
                    expected_sha256=args.sha256,
                    cache_busting_url_sha256=requested_url_sha,
                    timeout_s=args.timeout,
                    proxy=args.proxy,
                )
                rows.append(row)
                _write_jsonl_line(raw_handle, row)
                budget_session.add_actual(
                    allocation.run_id, int(row["actual_response_bytes"])
                )
                reconcile = budget_session.reconcile(
                    allocation.run_id,
                    "completed" if row["ok"] else "failed",
                )
                budget_overrun = budget_overrun or reconcile.overrun
                successful = successful and bool(row["ok"])

            schedule = build_schedule(
                engines=engines,
                connections=connections,
                repeats=args.repeats,
                seed=args.seed,
            )
            round_urls: dict[int, tuple[str, str]] = {}
            for repeat in range(args.repeats):
                round_urls[repeat] = _round_url_sha(
                    url=args.url,
                    cache_bust_param=args.cache_bust_param,
                    seed=args.seed,
                    repeat=repeat,
                    run_id=run_id,
                )

            for engine, connection_count, repeat in schedule:
                allocation = budget_session.leases[
                    f"{run_id}-{engine}-c{connection_count}"
                ].allocation
                requested_url, requested_url_sha = round_urls[repeat]
                sample_id = _sample_id(
                    run_id, "compare", engine, connection_count, repeat
                )
                row = execute_sample(
                    DownloadSpec(
                        engine=specs[engine],
                        java=(
                            Path(specs["turbo"].runtime)
                            if "turbo" in specs and specs["turbo"].runtime
                            else None
                        ),
                        turbo_jar=args.turbo_jar.resolve()
                        if "turbo" in specs
                        else None,
                        url=requested_url,
                        output_path=run_dir
                        / f"{sample_id}.bin",
                        log_path=run_dir / f"{sample_id}.log",
                        connections=connection_count,
                        proxy=args.proxy,
                        turbo_mode=args.turbo_mode,
                        known_size=args.known_size,
                    ),
                    sample_id=sample_id,
                    budget_run_id=allocation.run_id,
                    phase="compare",
                    repeat=repeat,
                    expected_size=args.size,
                    expected_sha256=args.sha256,
                    cache_busting_url_sha256=requested_url_sha,
                    timeout_s=args.timeout,
                    proxy=args.proxy,
                )
                rows.append(row)
                _write_jsonl_line(raw_handle, row)
                budget_session.add_actual(
                    allocation.run_id, int(row["actual_response_bytes"])
                )
                compare_completed[allocation.run_id] = (
                    compare_completed.get(allocation.run_id, 0) + 1
                )
                if (
                    compare_completed[allocation.run_id]
                    == compare_expected[allocation.run_id]
                ):
                    reconcile = budget_session.reconcile(
                        allocation.run_id,
                        "completed"
                        if all(
                            sample["ok"]
                            for sample in rows
                            if sample["budget_run_id"] == allocation.run_id
                        )
                        else "failed",
                    )
                    budget_overrun = budget_overrun or reconcile.overrun
                successful = successful and bool(row["ok"])
    except BaseException:
        budget_session.reconcile_open("aborted")
        raise
    else:
        budget_session.reconcile_open("aborted")

    budget_report = budget.report()
    budget_overrun = (
        budget_overrun
        or budget_report.remaining_bytes < 0
        or any(
            lease.reconcile is not None and lease.reconcile.overrun
            for lease in budget_session.leases.values()
        )
    )
    run_metadata = {
        "run_id": run_id,
        "generated_at": _utc_now(),
        "size_bytes": args.size,
        "expected_sha256": args.sha256,
        "base_url_sha256": sha256_text(args.url),
        "engines": engines,
        "connections": connections,
        "repeats": args.repeats,
        "seed": args.seed,
        "proxy_configured": bool(args.proxy),
        "cache_busting": bool(args.cache_bust_param),
        "prewarm": bool(args.prewarm),
        "prewarm_engines": list(prewarm_engines),
        "prewarm_engine": args.prewarm_engine if args.prewarm else None,
        "turbo_mode": args.turbo_mode,
        "known_size_bytes": args.known_size,
        "output_dir": str(run_dir),
        "raw_jsonl": str(raw_path),
    }
    try:
        summary_json, summary_csv = _write_summary_files(
            run_dir=run_dir,
            run_metadata=run_metadata,
            rows=rows,
            budget_report=budget_report,
            budget_leases=budget_session.leases,
        )
    except OSError as exc:
        print(f"cannot write summary: {exc}", file=sys.stderr)
        return EXIT_VALIDATION

    print(f"raw: {raw_path}")
    print(f"summary: {summary_json}")
    print(f"csv: {summary_csv}")
    print(
        f"budget remaining={budget_report.remaining_bytes} "
        f"consumed={budget_report.consumed_bytes}"
    )
    if budget_overrun:
        return EXIT_BUDGET
    return EXIT_OK if successful else EXIT_VALIDATION


if __name__ == "__main__":
    raise SystemExit(main())
