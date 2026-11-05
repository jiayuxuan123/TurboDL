#!/usr/bin/env python3
"""Persistent traffic budget ledger for public-network benchmark runs.

The ledger is an append-only JSONL file. Each line contains an event payload
and a SHA-256 checksum, so a damaged record is rejected instead of being
silently interpreted as a different charge. A small OS-level file lock makes
reserve/reconcile operations atomic across processes.
"""

from __future__ import annotations

import argparse
import contextlib
import errno
import hashlib
import hmac
import json
import os
import sys
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator, Mapping, Sequence


SCHEMA_VERSION = 1
MIB = 1024 * 1024
DEFAULT_TOTAL_BYTES = 8192 * MIB
LEDGER_FILENAME = "traffic-ledger.jsonl"
LOCK_FILENAME = "traffic-ledger.lock"
VALID_STATUSES = frozenset({"completed", "failed", "aborted"})


class BudgetError(Exception):
    """Base class for budget failures."""

    exit_code = 1


class LedgerCorrupted(BudgetError):
    """Raised when the ledger cannot be trusted."""


class BudgetExceeded(BudgetError):
    """Raised when a reservation would exceed the total budget."""

    exit_code = 2

    def __init__(
        self,
        *,
        total_bytes: int,
        used_bytes: int,
        remaining_bytes: int,
        requested_bytes: int,
    ) -> None:
        self.total_bytes = total_bytes
        self.used_bytes = used_bytes
        self.remaining_bytes = remaining_bytes
        self.requested_bytes = requested_bytes
        super().__init__(
            "budget exceeded: "
            f"requested={requested_bytes} remaining={remaining_bytes} "
            f"used={used_bytes} total={total_bytes}"
        )


class ReservationConflict(BudgetError):
    """Raised when a run ID is reused with different reservation data."""

    exit_code = 4


class ReconciliationConflict(BudgetError):
    """Raised when a finished run is reconciled with different values."""

    exit_code = 4


class RunNotFound(BudgetError):
    """Raised when reconciling a run that was never reserved."""

    exit_code = 4


class BudgetConfigurationError(BudgetError):
    """Raised when a data directory is opened with a different total."""


class BudgetLockTimeout(BudgetError):
    """Raised when another process holds the ledger lock for too long."""


@dataclass(frozen=True)
class Reservation:
    run_id: str
    estimated_bytes: int
    metadata: dict[str, Any]
    reserved_at: str
    remaining_bytes: int
    duplicate: bool


@dataclass(frozen=True)
class ReconcileResult:
    run_id: str
    estimated_bytes: int
    actual_response_bytes: int
    status: str
    reconciled_at: str
    overrun: bool
    overrun_bytes: int
    overrun_ratio: float | None
    duplicate: bool
    total_bytes: int
    consumed_bytes: int
    reserved_bytes: int
    remaining_bytes: int
    over_budget: bool


@dataclass(frozen=True)
class OpenReservation:
    run_id: str
    estimated_bytes: int
    metadata: dict[str, Any]
    reserved_at: str


@dataclass(frozen=True)
class BudgetReport:
    total_bytes: int
    consumed_bytes: int
    reserved_bytes: int
    used_bytes: int
    remaining_bytes: int
    open_reservation_count: int
    reconciled_run_count: int
    open_reservations: tuple[OpenReservation, ...]


@dataclass
class _RunState:
    reserve_event: dict[str, Any]
    reconcile_event: dict[str, Any] | None = None


@dataclass
class _LedgerState:
    total_bytes: int
    runs: dict[str, _RunState]

    @property
    def consumed_bytes(self) -> int:
        return sum(
            int(run.reconcile_event["actual_response_bytes"])
            for run in self.runs.values()
            if run.reconcile_event is not None
        )

    @property
    def reserved_bytes(self) -> int:
        return sum(
            int(run.reserve_event["estimated_bytes"])
            for run in self.runs.values()
            if run.reconcile_event is None
        )

    @property
    def used_bytes(self) -> int:
        return self.consumed_bytes + self.reserved_bytes

    @property
    def remaining_bytes(self) -> int:
        return self.total_bytes - self.used_bytes

    @property
    def reconciled_run_count(self) -> int:
        return sum(run.reconcile_event is not None for run in self.runs.values())


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="microseconds").replace(
        "+00:00", "Z"
    )


def _canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _validate_total_bytes(value: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError("total_bytes must be a non-negative integer")
    return value


def _validate_bytes(name: str, value: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{name} must be a non-negative integer")
    return value


def _validate_run_id(run_id: str) -> str:
    if not isinstance(run_id, str) or not run_id or run_id != run_id.strip():
        raise ValueError("run_id must be a non-empty string without edge whitespace")
    if "\n" in run_id or "\r" in run_id:
        raise ValueError("run_id must not contain line breaks")
    return run_id


def _normalize_metadata(metadata: Mapping[str, Any] | None) -> dict[str, Any]:
    if metadata is None:
        return {}
    if not isinstance(metadata, Mapping):
        raise ValueError("metadata must be a JSON object")
    if any(not isinstance(key, str) for key in metadata):
        raise ValueError("metadata keys must be strings")
    try:
        return json.loads(_canonical_json(dict(metadata)))
    except (TypeError, ValueError) as exc:
        raise ValueError(f"metadata is not JSON serializable: {exc}") from exc


def _validate_status(status: str) -> str:
    if status not in VALID_STATUSES:
        allowed = ", ".join(sorted(VALID_STATUSES))
        raise ValueError(f"status must be one of: {allowed}")
    return status


def _encode_record(event: dict[str, Any]) -> bytes:
    payload = _canonical_json(event).encode("utf-8")
    record = {
        "checksum": hashlib.sha256(payload).hexdigest(),
        "payload": event,
    }
    return (_canonical_json(record) + "\n").encode("utf-8")


def _decode_record(line: bytes, *, path: Path, line_number: int) -> dict[str, Any]:
    try:
        record = json.loads(line.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise LedgerCorrupted(
            f"{path}: invalid JSON on line {line_number}: {exc}"
        ) from exc
    if not isinstance(record, dict) or set(record) != {"checksum", "payload"}:
        raise LedgerCorrupted(
            f"{path}: line {line_number} is not a checksummed ledger record"
        )
    payload = record["payload"]
    checksum = record["checksum"]
    if not isinstance(payload, dict) or not isinstance(checksum, str):
        raise LedgerCorrupted(f"{path}: malformed record on line {line_number}")
    actual = hashlib.sha256(_canonical_json(payload).encode("utf-8")).hexdigest()
    if not hmac.compare_digest(checksum, actual):
        raise LedgerCorrupted(f"{path}: checksum mismatch on line {line_number}")
    return payload


def _event_int(event: Mapping[str, Any], field: str, *, path: Path, line: int) -> int:
    value = event.get(field)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise LedgerCorrupted(f"{path}: invalid {field} on line {line}")
    return value


def _event_string(event: Mapping[str, Any], field: str, *, path: Path, line: int) -> str:
    value = event.get(field)
    if not isinstance(value, str) or not value:
        raise LedgerCorrupted(f"{path}: invalid {field} on line {line}")
    return value


class TrafficBudget:
    """Atomic, persistent traffic ledger."""

    def __init__(
        self,
        data_dir: str | os.PathLike[str],
        *,
        total_bytes: int = DEFAULT_TOTAL_BYTES,
        lock_timeout_s: float = 15.0,
    ) -> None:
        self.data_dir = Path(data_dir)
        self.total_bytes = _validate_total_bytes(total_bytes)
        if lock_timeout_s <= 0:
            raise ValueError("lock_timeout_s must be positive")
        self.lock_timeout_s = float(lock_timeout_s)
        self.ledger_path = self.data_dir / LEDGER_FILENAME
        self.lock_path = self.data_dir / LOCK_FILENAME

    @contextlib.contextmanager
    def _exclusive_lock(self) -> Iterator[None]:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        handle = self.lock_path.open("a+b")
        try:
            handle.seek(0, os.SEEK_END)
            if handle.tell() == 0:
                handle.write(b"\0")
                handle.flush()
                os.fsync(handle.fileno())

            deadline = time.monotonic() + self.lock_timeout_s
            if os.name == "nt":
                import msvcrt

                while True:
                    try:
                        handle.seek(0)
                        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                        break
                    except OSError as exc:
                        if time.monotonic() >= deadline:
                            raise BudgetLockTimeout(
                                f"timed out waiting for ledger lock: {self.lock_path}"
                            ) from exc
                        time.sleep(0.01)
                try:
                    yield
                finally:
                    handle.seek(0)
                    msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                while True:
                    try:
                        fcntl.flock(
                            handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB
                        )
                        break
                    except OSError as exc:
                        if exc.errno not in (errno.EACCES, errno.EAGAIN):
                            raise
                        if time.monotonic() >= deadline:
                            raise BudgetLockTimeout(
                                f"timed out waiting for ledger lock: {self.lock_path}"
                            ) from exc
                        time.sleep(0.01)
                try:
                    yield
                finally:
                    fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        finally:
            handle.close()

    def _read_events_locked(self) -> list[dict[str, Any]]:
        if not self.ledger_path.exists():
            return []
        raw = self.ledger_path.read_bytes()
        lines = raw.splitlines(keepends=True)
        events: list[dict[str, Any]] = []
        offset = 0
        for index, line in enumerate(lines, start=1):
            if not line.endswith(b"\n"):
                if index != len(lines):
                    raise LedgerCorrupted(
                        f"{self.ledger_path}: missing newline on line {index}"
                    )
                # Writers append a whole line in one call. A final unterminated
                # fragment is therefore an interrupted append, not a charge.
                with self.ledger_path.open("r+b") as handle:
                    handle.truncate(offset)
                    handle.flush()
                    os.fsync(handle.fileno())
                break
            events.append(
                _decode_record(
                    line[:-1], path=self.ledger_path, line_number=index
                )
            )
            offset += len(line)
        return events

    def _append_event_locked(self, event: dict[str, Any]) -> None:
        line = _encode_record(event)
        with self.ledger_path.open("ab") as handle:
            handle.write(line)
            handle.flush()
            os.fsync(handle.fileno())

    def _build_state_locked(
        self, events: Sequence[dict[str, Any]]
    ) -> _LedgerState:
        if not events:
            raise LedgerCorrupted(f"{self.ledger_path}: empty ledger")
        init = events[0]
        if init.get("event") != "init" or init.get("schema_version") != SCHEMA_VERSION:
            raise LedgerCorrupted(
                f"{self.ledger_path}: first record must be a supported init event"
            )
        total = _event_int(init, "total_bytes", path=self.ledger_path, line=1)
        if total != self.total_bytes:
            raise BudgetConfigurationError(
                f"ledger total is {total} bytes, requested {self.total_bytes} bytes"
            )

        runs: dict[str, _RunState] = {}
        for index, event in enumerate(events[1:], start=2):
            kind = event.get("event")
            if kind == "init":
                raise LedgerCorrupted(
                    f"{self.ledger_path}: duplicate init event on line {index}"
                )
            run_id = _event_string(
                event, "run_id", path=self.ledger_path, line=index
            )
            if kind == "reserve":
                if run_id in runs:
                    raise LedgerCorrupted(
                        f"{self.ledger_path}: duplicate reserve for {run_id!r}"
                    )
                estimated = _event_int(
                    event, "estimated_bytes", path=self.ledger_path, line=index
                )
                metadata = event.get("metadata")
                if not isinstance(metadata, dict):
                    raise LedgerCorrupted(
                        f"{self.ledger_path}: invalid metadata on line {index}"
                    )
                _event_string(
                    event, "reserved_at", path=self.ledger_path, line=index
                )
                runs[run_id] = _RunState(
                    reserve_event={
                        "estimated_bytes": estimated,
                        "metadata": metadata,
                        "reserved_at": event["reserved_at"],
                    }
                )
            elif kind == "reconcile":
                run = runs.get(run_id)
                if run is None:
                    raise LedgerCorrupted(
                        f"{self.ledger_path}: reconcile before reserve on line {index}"
                    )
                if run.reconcile_event is not None:
                    raise LedgerCorrupted(
                        f"{self.ledger_path}: duplicate reconcile for {run_id!r} "
                        f"on line {index}"
                    )
                actual = _event_int(
                    event, "actual_response_bytes", path=self.ledger_path, line=index
                )
                status = _event_string(
                    event, "status", path=self.ledger_path, line=index
                )
                if status not in VALID_STATUSES:
                    raise LedgerCorrupted(
                        f"{self.ledger_path}: invalid status on line {index}"
                    )
                _event_string(
                    event, "reconciled_at", path=self.ledger_path, line=index
                )
                run.reconcile_event = {
                    "actual_response_bytes": actual,
                    "status": status,
                    "reconciled_at": event["reconciled_at"],
                }
            else:
                raise LedgerCorrupted(
                    f"{self.ledger_path}: unknown event {kind!r} on line {index}"
                )
        return _LedgerState(total_bytes=total, runs=runs)

    def _load_or_init_locked(self) -> _LedgerState:
        events = self._read_events_locked()
        if not events:
            init = {
                "event": "init",
                "schema_version": SCHEMA_VERSION,
                "total_bytes": self.total_bytes,
                "created_at": _utc_now(),
            }
            self._append_event_locked(init)
            events = [init]
        return self._build_state_locked(events)

    def reserve(
        self,
        run_id: str,
        estimated_bytes: int,
        metadata: Mapping[str, Any] | None = None,
    ) -> Reservation:
        """Atomically reserve budget for a run.

        Repeating a reserve with the same run ID, estimate, and metadata is
        idempotent while that run is still open.
        """

        run_id = _validate_run_id(run_id)
        estimated = _validate_bytes("estimated_bytes", estimated_bytes)
        normalized_metadata = _normalize_metadata(metadata)
        metadata_key = _canonical_json(normalized_metadata)

        with self._exclusive_lock():
            state = self._load_or_init_locked()
            existing = state.runs.get(run_id)
            if existing is not None:
                if existing.reconcile_event is not None:
                    raise ReservationConflict(
                        f"run {run_id!r} is already reconciled"
                    )
                old_estimate = int(existing.reserve_event["estimated_bytes"])
                old_key = _canonical_json(existing.reserve_event["metadata"])
                if old_estimate != estimated or old_key != metadata_key:
                    raise ReservationConflict(
                        f"run {run_id!r} already has a different reservation"
                    )
                return Reservation(
                    run_id=run_id,
                    estimated_bytes=old_estimate,
                    metadata=existing.reserve_event["metadata"],
                    reserved_at=existing.reserve_event["reserved_at"],
                    remaining_bytes=state.remaining_bytes,
                    duplicate=True,
                )

            if estimated > state.remaining_bytes:
                raise BudgetExceeded(
                    total_bytes=state.total_bytes,
                    used_bytes=state.used_bytes,
                    remaining_bytes=state.remaining_bytes,
                    requested_bytes=estimated,
                )

            reserved_at = _utc_now()
            event = {
                "event": "reserve",
                "run_id": run_id,
                "estimated_bytes": estimated,
                "metadata": normalized_metadata,
                "reserved_at": reserved_at,
            }
            self._append_event_locked(event)
            return Reservation(
                run_id=run_id,
                estimated_bytes=estimated,
                metadata=normalized_metadata,
                reserved_at=reserved_at,
                remaining_bytes=state.remaining_bytes - estimated,
                duplicate=False,
            )

    def reconcile(
        self,
        run_id: str,
        actual_response_bytes: int,
        *,
        status: str = "completed",
    ) -> ReconcileResult:
        """Close a reservation using the measured response-body byte count.

        Duplicate calls with identical values are idempotent. Conflicting calls
        are rejected and never append another charge.
        """

        run_id = _validate_run_id(run_id)
        actual = _validate_bytes("actual_response_bytes", actual_response_bytes)
        status = _validate_status(status)

        with self._exclusive_lock():
            state = self._load_or_init_locked()
            run = state.runs.get(run_id)
            if run is None:
                raise RunNotFound(f"run {run_id!r} has no reservation")
            estimated = int(run.reserve_event["estimated_bytes"])
            overrun = actual * 5 > estimated * 6
            overrun_bytes = max(0, actual - estimated)
            overrun_ratio = (
                actual / estimated
                if estimated > 0
                else (0.0 if actual == 0 else None)
            )

            if run.reconcile_event is not None:
                old_actual = int(run.reconcile_event["actual_response_bytes"])
                old_status = str(run.reconcile_event["status"])
                if old_actual != actual or old_status != status:
                    raise ReconciliationConflict(
                        f"run {run_id!r} was already reconciled as "
                        f"actual={old_actual} status={old_status}"
                    )
                return ReconcileResult(
                    run_id=run_id,
                    estimated_bytes=estimated,
                    actual_response_bytes=actual,
                    status=status,
                    reconciled_at=str(run.reconcile_event["reconciled_at"]),
                    overrun=overrun,
                    overrun_bytes=overrun_bytes,
                    overrun_ratio=overrun_ratio,
                    duplicate=True,
                    total_bytes=state.total_bytes,
                    consumed_bytes=state.consumed_bytes,
                    reserved_bytes=state.reserved_bytes,
                    remaining_bytes=state.remaining_bytes,
                    over_budget=state.remaining_bytes < 0,
                )

            reconciled_at = _utc_now()
            event = {
                "event": "reconcile",
                "run_id": run_id,
                "actual_response_bytes": actual,
                "status": status,
                "reconciled_at": reconciled_at,
            }
            self._append_event_locked(event)

            consumed = state.consumed_bytes + actual
            reserved = state.reserved_bytes - estimated
            remaining = state.total_bytes - consumed - reserved
            return ReconcileResult(
                run_id=run_id,
                estimated_bytes=estimated,
                actual_response_bytes=actual,
                status=status,
                reconciled_at=reconciled_at,
                overrun=overrun,
                overrun_bytes=overrun_bytes,
                overrun_ratio=overrun_ratio,
                duplicate=False,
                total_bytes=state.total_bytes,
                consumed_bytes=consumed,
                reserved_bytes=reserved,
                remaining_bytes=remaining,
                over_budget=remaining < 0,
            )

    def remaining_bytes(self) -> int:
        with self._exclusive_lock():
            return self._load_or_init_locked().remaining_bytes

    def report(self) -> BudgetReport:
        with self._exclusive_lock():
            state = self._load_or_init_locked()
            open_items = tuple(
                sorted(
                    (
                        OpenReservation(
                            run_id=run_id,
                            estimated_bytes=int(run.reserve_event["estimated_bytes"]),
                            metadata=run.reserve_event["metadata"],
                            reserved_at=str(run.reserve_event["reserved_at"]),
                        )
                        for run_id, run in state.runs.items()
                        if run.reconcile_event is None
                    ),
                    key=lambda item: (item.reserved_at, item.run_id),
                )
            )
            return BudgetReport(
                total_bytes=state.total_bytes,
                consumed_bytes=state.consumed_bytes,
                reserved_bytes=state.reserved_bytes,
                used_bytes=state.used_bytes,
                remaining_bytes=state.remaining_bytes,
                open_reservation_count=len(open_items),
                reconciled_run_count=state.reconciled_run_count,
                open_reservations=open_items,
            )


def default_data_dir() -> Path:
    override = os.environ.get("TURBODL_BENCH_TRAFFIC_BUDGET_DIR")
    if override:
        return Path(override)
    # One canonical ledger for every public-network experiment. Keeping the
    # default under results avoids splitting the hard 8 GiB budget between an
    # old results ledger and a fresh .runtime directory.
    return Path(__file__).resolve().parent.parent / "results" / "traffic-budget"


def _add_json_flag(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--json",
        action="store_true",
        default=argparse.SUPPRESS,
        help="emit compact machine-readable JSON",
    )


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Persistent byte-budget ledger for benchmark runs."
    )
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=None,
        help="ledger directory (default: _bench/results/traffic-budget)",
    )
    total_group = parser.add_mutually_exclusive_group()
    total_group.add_argument(
        "--total-mib",
        type=int,
        default=None,
        help="total budget in MiB (default: 8192)",
    )
    total_group.add_argument(
        "--total-bytes",
        type=int,
        default=None,
        help="total budget in bytes (overrides --total-mib)",
    )
    _add_json_flag(parser)

    commands = parser.add_subparsers(dest="command", required=True)

    reserve = commands.add_parser("reserve", help="reserve bytes for a run")
    reserve.add_argument("--run-id", required=True)
    reserve.add_argument("--estimated-bytes", type=int, required=True)
    reserve.add_argument(
        "--metadata",
        default="{}",
        help="JSON object; do not include URLs or secrets",
    )
    _add_json_flag(reserve)

    reconcile = commands.add_parser(
        "reconcile", help="close a reservation with actual traffic"
    )
    reconcile.add_argument("--run-id", required=True)
    reconcile.add_argument("--actual-response-bytes", type=int, required=True)
    reconcile.add_argument(
        "--status", choices=sorted(VALID_STATUSES), default="completed"
    )
    _add_json_flag(reconcile)

    remaining = commands.add_parser("remaining", help="print remaining bytes")
    _add_json_flag(remaining)

    report = commands.add_parser("report", help="show budget and open reservations")
    _add_json_flag(report)
    return parser


def _print_json(payload: dict[str, Any], *, stream: Any = sys.stdout) -> None:
    print(
        json.dumps(payload, ensure_ascii=False, allow_nan=False, separators=(",", ":")),
        file=stream,
    )


def _budget_from_args(args: argparse.Namespace) -> TrafficBudget:
    if args.total_bytes is not None:
        total_bytes = args.total_bytes
    elif args.total_mib is not None:
        total_bytes = args.total_mib * MIB
    else:
        total_bytes = DEFAULT_TOTAL_BYTES
    return TrafficBudget(args.data_dir or default_data_dir(), total_bytes=total_bytes)


def main(argv: Sequence[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    emit_json = bool(getattr(args, "json", False))

    try:
        budget = _budget_from_args(args)
        if args.command == "reserve":
            try:
                metadata = json.loads(args.metadata)
            except json.JSONDecodeError as exc:
                raise ValueError(f"invalid --metadata JSON: {exc}") from exc
            reservation = budget.reserve(
                args.run_id, args.estimated_bytes, metadata=metadata
            )
            if emit_json:
                _print_json(
                    {"ok": True, "action": "reserve", "reservation": asdict(reservation)}
                )
            else:
                print(
                    f"reserved run={reservation.run_id} "
                    f"estimated={reservation.estimated_bytes} "
                    f"remaining={reservation.remaining_bytes} "
                    f"duplicate={str(reservation.duplicate).lower()}"
                )
            return 0

        if args.command == "reconcile":
            result = budget.reconcile(
                args.run_id,
                args.actual_response_bytes,
                status=args.status,
            )
            if emit_json:
                payload = {"ok": True, "action": "reconcile", **asdict(result)}
                _print_json(payload)
            else:
                print(
                    f"reconciled run={result.run_id} actual={result.actual_response_bytes} "
                    f"status={result.status} overrun={str(result.overrun).lower()} "
                    f"remaining={result.remaining_bytes} "
                    f"duplicate={str(result.duplicate).lower()}"
                )
            return 3 if result.overrun else 0

        if args.command == "remaining":
            value = budget.remaining_bytes()
            if emit_json:
                _print_json({"ok": True, "remaining_bytes": value})
            else:
                print(value)
            return 0

        if args.command == "report":
            result = budget.report()
            if emit_json:
                _print_json({"ok": True, "report": asdict(result)})
            else:
                print(
                    f"total={result.total_bytes} used={result.used_bytes} "
                    f"consumed={result.consumed_bytes} "
                    f"reserved={result.reserved_bytes} "
                    f"remaining={result.remaining_bytes} "
                    f"open={result.open_reservation_count}"
                )
                for item in result.open_reservations:
                    print(
                        f"open run={item.run_id} estimated={item.estimated_bytes} "
                        f"reserved_at={item.reserved_at}"
                    )
            return 0
    except BudgetExceeded as exc:
        if emit_json:
            _print_json(
                {
                    "ok": False,
                    "error": "budget_exceeded",
                    "message": str(exc),
                    "total_bytes": exc.total_bytes,
                    "used_bytes": exc.used_bytes,
                    "remaining_bytes": exc.remaining_bytes,
                    "requested_bytes": exc.requested_bytes,
                }
            )
        else:
            print(str(exc), file=sys.stderr)
        return exc.exit_code
    except BudgetError as exc:
        if emit_json:
            _print_json({"ok": False, "error": type(exc).__name__, "message": str(exc)})
        else:
            print(str(exc), file=sys.stderr)
        return exc.exit_code
    except (OSError, ValueError) as exc:
        if emit_json:
            _print_json({"ok": False, "error": type(exc).__name__, "message": str(exc)})
        else:
            print(str(exc), file=sys.stderr)
        return 1

    parser.error("unknown command")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
