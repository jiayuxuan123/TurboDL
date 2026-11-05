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
