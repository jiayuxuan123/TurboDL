from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import traffic_budget as tb


MODULE_PATH = Path(__file__).resolve().with_name("traffic_budget.py")


class TrafficBudgetTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.data_dir = Path(self._tmp.name) / "ledger"

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_default_data_dir_is_the_single_canonical_ledger(self) -> None:
        expected = (
            Path(__file__).resolve().parent.parent
            / "results"
            / "traffic-budget"
        )
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("TURBODL_BENCH_TRAFFIC_BUDGET_DIR", None)
            self.assertEqual(tb.default_data_dir(), expected)
        with mock.patch.dict(
            os.environ,
            {"TURBODL_BENCH_TRAFFIC_BUDGET_DIR": str(self.data_dir)},
        ):
            self.assertEqual(tb.default_data_dir(), self.data_dir)

    def test_reserve_reconcile_and_duplicate_after_reload(self) -> None:
        budget = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        reservation = budget.reserve("run-a", 400, {"case": "warm"})
        self.assertFalse(reservation.duplicate)
        self.assertEqual(reservation.remaining_bytes, 600)

        result = budget.reconcile("run-a", 250, status="completed")
        self.assertEqual(result.consumed_bytes, 250)
        self.assertEqual(result.reserved_bytes, 0)
        self.assertEqual(result.remaining_bytes, 750)
        self.assertFalse(result.overrun)

        reloaded = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        duplicate = reloaded.reconcile("run-a", 250, status="completed")
        self.assertTrue(duplicate.duplicate)
        self.assertEqual(reloaded.report().consumed_bytes, 250)

        with self.assertRaises(tb.ReconciliationConflict):
            reloaded.reconcile("run-a", 251, status="completed")
        self.assertEqual(reloaded.report().consumed_bytes, 250)

    def test_insufficient_budget_is_rejected(self) -> None:
        budget = tb.TrafficBudget(self.data_dir, total_bytes=100)
        budget.reserve("first", 80)
        with self.assertRaises(tb.BudgetExceeded) as caught:
            budget.reserve("second", 21)
        self.assertEqual(caught.exception.remaining_bytes, 20)
        report = budget.report()
        self.assertEqual(report.reserved_bytes, 80)
        self.assertEqual(report.remaining_bytes, 20)
        self.assertEqual(report.open_reservation_count, 1)

    def test_concurrent_reserve_processes_do_not_oversell(self) -> None:
        commands = []
        for index in range(12):
            commands.append(
                [
                    sys.executable,
                    str(MODULE_PATH),
                    "--data-dir",
                    str(self.data_dir),
                    "--total-bytes",
                    "3000",
                    "--json",
                    "reserve",
                    "--run-id",
                    f"run-{index}",
                    "--estimated-bytes",
                    "1000",
                ]
            )

        processes = [
            subprocess.Popen(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
            )
            for command in commands
        ]
        completed = [
            (process, *process.communicate(timeout=30)) for process in processes
        ]

        successes = []
        failures = []
        for process, stdout, stderr in completed:
            payload = json.loads(stdout)
            if process.returncode == 0:
                successes.append(payload)
            else:
                self.assertEqual(process.returncode, 2, stderr)
                self.assertEqual(payload["error"], "budget_exceeded")
                failures.append(payload)

        self.assertEqual(len(successes), 3)
        self.assertEqual(len(failures), 9)
        report = tb.TrafficBudget(self.data_dir, total_bytes=3000).report()
        self.assertEqual(report.reserved_bytes, 3000)
        self.assertEqual(report.remaining_bytes, 0)
        self.assertEqual(report.open_reservation_count, 3)

    def test_abnormal_exit_is_recovered_from_ledger(self) -> None:
        first_process = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        first_process.reserve("crashed-run", 400, {"phase": "download"})
        del first_process

        recovered = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        report = recovered.report()
        self.assertEqual(report.open_reservation_count, 1)
        self.assertEqual(report.reserved_bytes, 400)
        self.assertEqual(report.remaining_bytes, 600)

        result = recovered.reconcile("crashed-run", 125, status="failed")
        self.assertEqual(result.status, "failed")
        self.assertEqual(result.reserved_bytes, 0)
        self.assertEqual(result.remaining_bytes, 875)

    def test_partial_tail_write_is_discarded(self) -> None:
        budget = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        budget.reserve("run-tail", 300)
        with budget.ledger_path.open("ab") as handle:
            handle.write(b'{"checksum":"incomplete')

        recovered = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        report = recovered.report()
        self.assertEqual(report.reserved_bytes, 300)
        recovered.reconcile("run-tail", 100)
        self.assertEqual(recovered.remaining_bytes(), 900)

    def test_damaged_checksum_is_rejected_without_new_charge(self) -> None:
        budget = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        budget.reserve("run-damaged", 100)
        raw = budget.ledger_path.read_text(encoding="utf-8")
        damaged = raw.replace('"estimated_bytes":100', '"estimated_bytes":900', 1)
        self.assertNotEqual(raw, damaged)
        budget.ledger_path.write_text(damaged, encoding="utf-8")

        with self.assertRaises(tb.LedgerCorrupted):
            budget.report()
        with self.assertRaises(tb.LedgerCorrupted):
            budget.reconcile("run-damaged", 100)

    def test_grant_is_idempotent_and_does_not_rewrite_init(self) -> None:
        budget = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        first = budget.grant(400, "authorization-a")
        self.assertFalse(first.duplicate)
        self.assertEqual(first.total_bytes, 1400)
        self.assertEqual(first.remaining_bytes, 1400)

        reloaded = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        duplicate = reloaded.grant(400, "authorization-a")
        self.assertTrue(duplicate.duplicate)
        self.assertEqual(duplicate.total_bytes, 1400)
        alias_duplicate = reloaded.extend(400, "authorization-a")
        self.assertTrue(alias_duplicate.duplicate)

        payloads = [
            json.loads(line)["payload"]
            for line in reloaded.ledger_path.read_text(
                encoding="utf-8"
            ).splitlines()
        ]
        self.assertEqual(payloads[0]["event"], "init")
        self.assertEqual(payloads[0]["total_bytes"], 1000)
        self.assertEqual(
            [
                event
                for event in payloads
                if event["event"] in {"grant", "extend"}
            ],
            [
                {
                    "event": "grant",
                    "authorization_id": "authorization-a",
                    "added_bytes": 400,
                    "granted_at": first.granted_at,
                }
            ],
        )
        report = reloaded.report()
        self.assertEqual(report.granted_bytes, 400)
        self.assertEqual(report.total_bytes, 1400)
        self.assertEqual(len(report.grants), 1)

    def test_grant_conflict_does_not_extend_budget(self) -> None:
        budget = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        budget.grant(400, "shared-authorization")
        with self.assertRaises(tb.GrantConflict):
            budget.grant(401, "shared-authorization")
        report = budget.report()
        self.assertEqual(report.granted_bytes, 400)
        self.assertEqual(report.total_bytes, 1400)

    def test_grant_preserves_reserved_bytes_and_balance(self) -> None:
        budget = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        budget.reserve("existing-run", 700)
        grant = budget.grant(500, "reserved-extension")
        self.assertEqual(grant.total_bytes, 1500)
        self.assertEqual(grant.remaining_bytes, 800)

        report = budget.report()
        self.assertEqual(report.reserved_bytes, 700)
        self.assertEqual(report.used_bytes, 700)
        self.assertEqual(report.remaining_bytes, 800)
        budget.reserve("post-grant-run", 800)
        self.assertEqual(budget.remaining_bytes(), 0)

    def test_old_reserve_reconcile_ledger_remains_compatible(self) -> None:
        self.data_dir.mkdir(parents=True)
        old_events = [
            {
                "event": "init",
                "schema_version": tb.SCHEMA_VERSION,
                "total_bytes": 1000,
                "created_at": "2026-01-01T00:00:00.000000Z",
            },
            {
                "event": "reserve",
                "run_id": "old-run",
                "estimated_bytes": 100,
                "metadata": {"case": "old-ledger"},
                "reserved_at": "2026-01-01T00:00:01.000000Z",
            },
            {
                "event": "reconcile",
                "run_id": "old-run",
                "actual_response_bytes": 80,
                "status": "completed",
                "reconciled_at": "2026-01-01T00:00:02.000000Z",
            },
        ]
        ledger_path = self.data_dir / tb.LEDGER_FILENAME
        ledger_path.write_bytes(
            b"".join(tb._encode_record(event) for event in old_events)
        )

        budget = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        report = budget.report()
        self.assertEqual(report.total_bytes, 1000)
        self.assertEqual(report.consumed_bytes, 80)
        self.assertEqual(report.reserved_bytes, 0)
        self.assertEqual(report.granted_bytes, 0)

        budget.grant(200, "old-ledger-extension")
        updated = budget.report()
        self.assertEqual(updated.total_bytes, 1200)
        self.assertEqual(updated.consumed_bytes, 80)
        self.assertEqual(updated.remaining_bytes, 1120)

    def test_damaged_grant_record_is_rejected_without_append(self) -> None:
        budget = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        budget.grant(100, "damaged-grant")
        raw = budget.ledger_path.read_text(encoding="utf-8")
        damaged = raw.replace('"added_bytes":100', '"added_bytes":900', 1)
        self.assertNotEqual(raw, damaged)
        budget.ledger_path.write_text(damaged, encoding="utf-8")

        with self.assertRaises(tb.LedgerCorrupted):
            budget.report()
        before = budget.ledger_path.read_bytes()
        with self.assertRaises(tb.LedgerCorrupted):
            budget.grant(100, "damaged-grant")
        self.assertEqual(budget.ledger_path.read_bytes(), before)

    def test_cli_grant_uses_default_initial_eight_gib(self) -> None:
        ledger_path = self.data_dir / tb.LEDGER_FILENAME
        base = [
            sys.executable,
            str(MODULE_PATH),
            "--ledger",
            str(ledger_path),
            "--json",
        ]
        added = 4 * 1024 * 1024 * 1024
        authorization = "release-0.5.0.3-20261111-4g"
        first = subprocess.run(
            base
            + [
                "grant",
                "--bytes",
                str(added),
                "--authorization",
                authorization,
            ],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        first_payload = json.loads(first.stdout)
        self.assertFalse(first_payload["duplicate"])
        self.assertEqual(first_payload["added_bytes"], added)
        self.assertEqual(first_payload["total_bytes"], 12 * 1024 * 1024 * 1024)

        duplicate = subprocess.run(
            base
            + [
                "grant",
                "--bytes",
                str(added),
                "--authorization",
                authorization,
            ],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        self.assertTrue(json.loads(duplicate.stdout)["duplicate"])

        conflict = subprocess.run(
            base
            + [
                "grant",
                "--bytes",
                str(added - 1),
                "--authorization",
                authorization,
            ],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        self.assertEqual(conflict.returncode, tb.GrantConflict.exit_code)
        self.assertEqual(
            json.loads(conflict.stdout)["error"], "GrantConflict"
        )

        report = subprocess.run(
            base + ["report"],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        report_payload = json.loads(report.stdout)["report"]
        self.assertEqual(
            report_payload["total_bytes"], 12 * 1024 * 1024 * 1024
        )
        self.assertEqual(report_payload["granted_bytes"], added)

        event_payloads = [
            json.loads(line)["payload"]
            for line in ledger_path.read_text(encoding="utf-8").splitlines()
        ]
        self.assertEqual(event_payloads[0]["event"], "init")
        self.assertEqual(
            event_payloads[0]["total_bytes"], tb.DEFAULT_TOTAL_BYTES
        )
        self.assertEqual(
            len(
                [
                    event
                    for event in event_payloads
                    if event["event"] in {"grant", "extend"}
                ]
            ),
            1,
        )

    def test_concurrent_grants_with_same_authorization_are_idempotent(self) -> None:
        commands = [
            [
                sys.executable,
                str(MODULE_PATH),
                "--ledger",
                str(self.data_dir),
                "--total-bytes",
                "1000",
                "--json",
                "grant",
                "--bytes",
                "100",
                "--authorization",
                "concurrent-authorization",
            ]
            for _ in range(8)
        ]
        processes = [
            subprocess.Popen(
                command,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
            )
            for command in commands
        ]
        completed = [
            (process, *process.communicate(timeout=30)) for process in processes
        ]

        duplicates = 0
        for process, stdout, stderr in completed:
            self.assertEqual(process.returncode, 0, stderr)
            payload = json.loads(stdout)
            self.assertEqual(payload["added_bytes"], 100)
            duplicates += int(payload["duplicate"])
        self.assertEqual(duplicates, len(commands) - 1)

        report = tb.TrafficBudget(self.data_dir, total_bytes=1000).report()
        self.assertEqual(report.total_bytes, 1100)
        self.assertEqual(report.granted_bytes, 100)
        self.assertEqual(len(report.grants), 1)

    def test_overrun_signal_starts_above_twenty_percent(self) -> None:
        budget = tb.TrafficBudget(self.data_dir, total_bytes=1000)
        budget.reserve("at-threshold", 100)
        exact = budget.reconcile("at-threshold", 120)
        self.assertFalse(exact.overrun)
        self.assertEqual(exact.overrun_bytes, 20)

        budget.reserve("over-threshold", 100)
        result = budget.reconcile("over-threshold", 121)
        self.assertTrue(result.overrun)
        self.assertEqual(result.overrun_bytes, 21)
        self.assertAlmostEqual(result.overrun_ratio, 1.21)

        duplicate = budget.reconcile("over-threshold", 121)
        self.assertTrue(duplicate.duplicate)
        self.assertTrue(duplicate.overrun)

    def test_cli_remaining_and_report_json(self) -> None:
        base = [
            sys.executable,
            str(MODULE_PATH),
            "--data-dir",
            str(self.data_dir),
            "--total-bytes",
            "500",
            "--json",
        ]
        reserved = subprocess.run(
            base
            + [
                "reserve",
                "--run-id",
                "cli-run",
                "--estimated-bytes",
                "200",
            ],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        self.assertTrue(json.loads(reserved.stdout)["ok"])

        remaining = subprocess.run(
            base + ["remaining"],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        self.assertEqual(json.loads(remaining.stdout)["remaining_bytes"], 300)

        report = subprocess.run(
            base[:-1] + ["report", "--json"],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        payload = json.loads(report.stdout)["report"]
        self.assertEqual(payload["open_reservation_count"], 1)
        self.assertEqual(payload["reserved_bytes"], 200)

    def test_cli_reconcile_overrun_exit_code(self) -> None:
        base = [
            sys.executable,
            str(MODULE_PATH),
            "--data-dir",
            str(self.data_dir),
            "--total-bytes",
            "1000",
            "--json",
        ]
        subprocess.run(
            base
            + [
                "reserve",
                "--run-id",
                "overrun-cli",
                "--estimated-bytes",
                "100",
            ],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        result = subprocess.run(
            base
            + [
                "reconcile",
                "--run-id",
                "overrun-cli",
                "--actual-response-bytes",
                "121",
            ],
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        self.assertEqual(result.returncode, 3)
        payload = json.loads(result.stdout)
        self.assertTrue(payload["overrun"])
        self.assertTrue(payload["ok"])


if __name__ == "__main__":
    unittest.main()
