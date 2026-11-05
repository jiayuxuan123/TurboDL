from __future__ import annotations

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import run_engine_compare as runner
import traffic_budget as tb


class RunEngineCompareTests(unittest.TestCase):
    def _engine(self, name: str, executable: str) -> runner.EngineSpec:
        return runner.EngineSpec(
            name=name,
            executable=Path(executable),
            version=f"{name} 1.0",
            binary_sha256="a" * 64,
        )

    def _spec(
        self,
        engine: str,
        *,
        root: Path,
        connections: int = 8,
        proxy: str | None = "http://127.0.0.1:7897",
    ) -> runner.DownloadSpec:
        return runner.DownloadSpec(
            engine=self._engine(engine, f"/bin/{engine}"),
            java=Path("/bin/java") if engine == "turbo" else None,
            turbo_jar=Path("/tmp/turbodl-cli-all.jar")
            if engine == "turbo"
            else None,
            url="https://downloads.example.com/object.bin?X-Amz-Signature=secret",
            output_path=root / f"{engine}.bin",
            log_path=root / f"{engine}.log",
            connections=connections,
            proxy=proxy,
        )

    def test_command_builders_share_proxy_and_use_independent_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            proxy = "http://127.0.0.1:7897"
            curl = runner.build_engine_command(
                self._spec("curl", root=root, connections=1, proxy=proxy)
            )
            turbo = runner.build_engine_command(
                self._spec("turbo", root=root, connections=8, proxy=proxy)
            )
            aria2 = runner.build_engine_command(
                self._spec("aria2", root=root, connections=16, proxy=proxy)
            )

        self.assertIn("--http1.1", curl)
        self.assertEqual(curl[curl.index("--proxy") + 1], proxy)
        self.assertNotIn("--parallel", curl)
        self.assertEqual(curl[curl.index("--output") + 1], str(root / "curl.bin"))

        self.assertEqual(
            turbo[:3],
            [str(Path("/bin/java")), "-jar", str(Path("/tmp/turbodl-cli-all.jar"))],
        )
        self.assertEqual(turbo[turbo.index("-c") + 1], "8")
        self.assertEqual(turbo[turbo.index("--proxy") + 1], proxy)
        self.assertIn("http1", turbo)
        self.assertIn("--no-adaptive", turbo)
        production = runner.build_engine_command(
            runner.DownloadSpec(
                **{
                    **self._spec("turbo", root=root, connections=8, proxy=proxy).__dict__,
                    "turbo_mode": "production",
                }
            )
        )
        self.assertNotIn("--no-adaptive", production)
        self.assertNotIn("--no-slow-start", production)
        self.assertNotIn("--no-tail-assist", production)
        self.assertNotIn("--no-warmup", production)
        known_size = runner.build_engine_command(
            runner.DownloadSpec(
                **{
                    **self._spec("turbo", root=root, connections=8, proxy=proxy).__dict__,
                    "turbo_mode": "production",
                    "known_size": 32241380,
                }
            )
        )
        self.assertEqual(known_size[known_size.index("--known-size") + 1], "32241380")
        self.assertIn("--timing", known_size)

        self.assertEqual(
            aria2[aria2.index("--max-connection-per-server") + 1], "16"
        )
        self.assertEqual(aria2[aria2.index("--split") + 1], "16")
        self.assertEqual(aria2[aria2.index("--all-proxy") + 1], proxy)
        self.assertTrue(aria2[-1].startswith("https://"))

    def test_aria2_rejects_connection_count_above_sixteen(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            spec = self._spec("aria2", root=Path(tmp), connections=17)
            with self.assertRaisesRegex(runner.ParameterError, "1\\.\\.16"):
                runner.build_engine_command(spec)

    def test_budget_estimates_prewarm_and_attempts_then_reconciles(self) -> None:
        allocations = runner.build_allocations(
            run_id="run-a",
            size_bytes=100,
            engines=("curl", "turbo"),
            connections=(1, 8),
            repeats=3,
            prewarm_engines=("curl",),
        )
        self.assertEqual(sum(item.estimated_bytes for item in allocations), 1300)
        self.assertEqual(allocations[0].estimated_bytes, 100)
        self.assertTrue(
            all(
                item.estimated_bytes == 300
                for item in allocations
                if item.kind == "compare"
            )
        )

        with tempfile.TemporaryDirectory() as tmp:
            budget = tb.TrafficBudget(Path(tmp) / "ledger", total_bytes=2000)
            session = runner.BudgetSession(budget, allocations)
            session.reserve_all()
            self.assertEqual(
                budget.report().reserved_bytes,
                1300,
            )
            session.add_actual("run-a-prewarm-curl", 100)
            session.reconcile("run-a-prewarm-curl", "completed")
            session.add_actual("run-a-curl-c1", 280)
            session.reconcile("run-a-curl-c1", "completed")
            session.reconcile_open("aborted")
            report = budget.report()
            self.assertEqual(report.consumed_bytes, 380)
            self.assertEqual(report.reserved_bytes, 0)
            self.assertEqual(report.open_reservation_count, 0)

    def test_budget_reservation_rolls_back_prior_leases_on_exceeded(self) -> None:
        allocations = runner.build_allocations(
            run_id="run-b",
            size_bytes=100,
            engines=("curl", "turbo"),
            connections=(1, 8),
            repeats=2,
            prewarm_engines=("curl",),
        )
        with tempfile.TemporaryDirectory() as tmp:
            budget = tb.TrafficBudget(Path(tmp) / "ledger", total_bytes=400)
            session = runner.BudgetSession(budget, allocations)
            with self.assertRaises(tb.BudgetExceeded):
                session.reserve_all()
            report = budget.report()
            self.assertEqual(report.reserved_bytes, 0)
            self.assertEqual(report.consumed_bytes, 0)
            self.assertEqual(report.open_reservation_count, 0)

    def test_prewarm_all_engines_uses_independent_leases(self) -> None:
        allocations = runner.build_allocations(
            run_id="run-warm",
            size_bytes=100,
            engines=("curl", "turbo"),
            connections=(8,),
            repeats=2,
            prewarm_engines=("curl", "turbo"),
        )
        prewarms = [item for item in allocations if item.kind == "prewarm"]
        self.assertEqual(
            [item.run_id for item in prewarms],
            ["run-warm-prewarm-curl", "run-warm-prewarm-turbo"],
        )
        self.assertEqual(sum(item.estimated_bytes for item in prewarms), 200)

    def test_schedule_is_seeded_and_contains_every_sample(self) -> None:
        first = runner.build_schedule(
            engines=("curl", "turbo", "aria2"),
            connections=(1, 8),
            repeats=2,
            seed=123,
        )
        second = runner.build_schedule(
            engines=("curl", "turbo", "aria2"),
            connections=(1, 8),
            repeats=2,
            seed=123,
        )
        self.assertEqual(first, second)
        self.assertEqual(len(first), 12)
        self.assertEqual(
            set(first),
            {
                (engine, connections, repeat)
                for engine in ("curl", "turbo", "aria2")
                for connections in (1, 8)
                for repeat in range(2)
            },
        )
        block_size = 3
        for offset in range(0, len(first), block_size):
            block = first[offset:offset + block_size]
            self.assertEqual(len({item[0] for item in block}), 3)
            self.assertEqual(len({item[1] for item in block}), 1)
            self.assertEqual(len({item[2] for item in block}), 1)

    def test_dry_run_prints_estimate_and_rejects_insufficient_budget(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            common_prefix = [
                "--url",
                "https://files.example.com/object.bin",
                "--size",
                "100",
                "--sha256",
                "a" * 64,
                "--engines",
                "curl",
                "--connections",
                "1",
                "--repeats",
                "2",
                "--prewarm",
                "--output-dir",
                str(root / "artifacts"),
                "--dry-run",
            ]
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                exit_code = runner.main(
                    common_prefix
                    + [
                        "--budget-data-dir",
                        str(root / "ledger-a"),
                        "--total-bytes",
                        "100",
                    ]
                )
            self.assertEqual(exit_code, runner.EXIT_BUDGET)
            self.assertIn("estimate=300", stdout.getvalue())
            self.assertIn("sufficient=false", stdout.getvalue())
            self.assertFalse((root / "artifacts").exists())

            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                exit_code = runner.main(
                    common_prefix
                    + [
                        "--budget-data-dir",
                        str(root / "ledger-b"),
                        "--total-bytes",
                        "300",
                    ]
                )
            self.assertEqual(exit_code, runner.EXIT_OK)
            self.assertIn("sufficient=true", stdout.getvalue())

    def test_summary_sorts_by_goodput_and_keeps_failures(self) -> None:
        rows = [
            {
                "phase": "compare",
                "engine": "turbo",
                "version": "1",
                "binary_sha256": "a" * 64,
                "connections": 8,
                "ok": True,
                "goodput_mib_s": 2.0,
                "elapsed_s": 10.0,
                "actual_sha256": "b" * 64,
                "cache_busting_url_sha256": "c" * 64,
                "budget_run_id": "run-turbo-8",
                "log_path": "turbo.log",
                "connection_telemetry": {"max_connections": 8},
            },
            {
                "phase": "compare",
                "engine": "curl",
                "version": "1",
                "binary_sha256": "d" * 64,
                "connections": 8,
                "ok": True,
                "goodput_mib_s": 9.0,
                "elapsed_s": 4.0,
                "actual_sha256": "e" * 64,
                "cache_busting_url_sha256": "f" * 64,
                "budget_run_id": "run-curl-8",
                "log_path": "curl.log",
                "connection_telemetry": {"num_connects": 1},
            },
            {
                "phase": "compare",
                "engine": "aria2",
                "version": "1",
                "binary_sha256": "0" * 64,
                "connections": 8,
                "ok": False,
                "goodput_mib_s": 0.0,
                "elapsed_s": 2.0,
                "actual_sha256": "",
                "cache_busting_url_sha256": "1" * 64,
                "budget_run_id": "run-aria2-8",
                "log_path": "aria2.log",
                "connection_telemetry": {"max_connections": 8},
            },
            {
                "phase": "prewarm",
                "engine": "curl",
                "connections": 1,
                "ok": True,
                "goodput_mib_s": 999.0,
            },
        ]
        summary = runner.summarize_rows(rows)
        self.assertEqual([row["engine"] for row in summary], ["curl", "turbo", "aria2"])
        self.assertEqual(summary[2]["failures"], 1)
        self.assertEqual(summary[2]["samples"], 1)
        self.assertEqual(summary[2]["median_goodput_mib_s"], 0.0)

    def test_summary_writer_emits_json_and_csv_without_source_url(self) -> None:
        row = {
            "run_id": "sample-a",
            "phase": "compare",
            "engine": "curl",
            "version": "curl 1",
            "binary_sha256": "a" * 64,
            "connections": 1,
            "samples": 1,
            "successes": 1,
            "failures": 0,
            "ok": True,
            "goodput_mib_s": 5.0,
            "elapsed_s": 2.0,
            "actual_sha256": "b" * 64,
            "cache_busting_url_sha256": "c" * 64,
            "budget_run_id": "budget-a",
            "log_path": "sample-a.log",
            "connection_telemetry": {"num_connects": 1},
        }
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            budget = tb.TrafficBudget(root / "ledger", total_bytes=1000)
            summary_json, summary_csv = runner._write_summary_files(
                run_dir=root,
                run_metadata={"base_url_sha256": "d" * 64},
                rows=[row],
                budget_report=budget.report(),
                budget_leases={},
            )
            payload = json.loads(summary_json.read_text(encoding="utf-8"))
            self.assertEqual(payload["ranking"][0]["engine"], "curl")
            self.assertNotIn("https://", summary_json.read_text(encoding="utf-8"))
            self.assertNotIn("https://", summary_csv.read_text(encoding="utf-8"))
            self.assertIn("median_goodput_mib_s", summary_csv.read_text(encoding="utf-8"))

    def test_failed_sample_keeps_partial_output_log_and_redacts_url(self) -> None:
        secret_url = "https://downloads.example.com/file.bin?X-Amz-Signature=SECRET"

        def fake_runner(
            command: list[str],
            *,
            env: dict[str, str],
            timeout_s: float,
        ) -> runner.ProcessOutcome:
            del command, env, timeout_s
            spec.output_path.write_bytes(b"bad")
            return runner.ProcessOutcome(
                exit_code=22,
                stdout=f"failed while reading {secret_url}",
                stderr="HTTP 500",
                elapsed_s=0.5,
            )

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            spec = self._spec("curl", root=root, connections=1)
            spec = runner.DownloadSpec(
                engine=spec.engine,
                java=None,
                turbo_jar=None,
                url=secret_url,
                output_path=root / "partial.bin",
                log_path=root / "partial.log",
                connections=1,
                proxy=None,
            )
            row = runner.execute_sample(
                spec,
                sample_id="sample-1",
                budget_run_id="budget-1",
                phase="compare",
                repeat=0,
                expected_size=10,
                expected_sha256="0" * 64,
                cache_busting_url_sha256="2" * 64,
                timeout_s=10,
                proxy=None,
                process_runner=fake_runner,
            )
            self.assertTrue(spec.output_path.exists())
            self.assertTrue(spec.log_path.exists())
            self.assertFalse(row["ok"])
            self.assertEqual(row["actual_size_bytes"], 3)
            self.assertEqual(row["actual_response_bytes"], 3)
            self.assertIn("[redacted-url]", row["error"])
            self.assertNotIn(secret_url, json.dumps(row))
            self.assertNotIn(secret_url, spec.log_path.read_text(encoding="utf-8"))

    def test_public_url_guard_rejects_private_resolution(self) -> None:
        def private_resolver(*args, **kwargs):
            del args, kwargs
            return [(2, 1, 6, "", ("127.0.0.1", 443))]

        with self.assertRaisesRegex(runner.ParameterError, "loopback/private"):
            runner.validate_public_http_url(
                "https://files.example.com/object.bin",
                resolver=private_resolver,
            )


if __name__ == "__main__":
    unittest.main()
