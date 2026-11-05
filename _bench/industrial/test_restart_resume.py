from __future__ import annotations

import struct
import tempfile
import unittest
import urllib.request
from pathlib import Path

import restart_resume as rr


class RestartResumeTests(unittest.TestCase):
    def test_payload_and_hash_are_deterministic(self) -> None:
        first = rr.build_payload(128 * 1024)
        second = rr.build_payload(128 * 1024)
        self.assertEqual(first, second)
        self.assertEqual(rr.sha256_bytes(first), rr.sha256_bytes(second))
        self.assertNotEqual(first[:4096], first[4096:8192])

    def test_single_range_parser(self) -> None:
        self.assertIsNone(rr.parse_single_range(None, 100))
        self.assertEqual(rr.parse_single_range("bytes=0-0", 100), (0, 0))
        self.assertEqual(rr.parse_single_range("bytes=10-", 100), (10, 99))
        self.assertEqual(rr.parse_single_range("bytes=10-999", 100), (10, 99))
        with self.assertRaises(ValueError):
            rr.parse_single_range("bytes=100-", 100)
        with self.assertRaises(ValueError):
            rr.parse_single_range("bytes=20-10", 100)
        with self.assertRaises(ValueError):
            rr.parse_single_range("bytes=0-1,3-4", 100)

    def test_server_serves_exact_ranges_and_counts_bytes(self) -> None:
        payload = bytes(range(256)) * 1024
        server = rr.ProbeServer(
            payload,
            etag='"unit"',
            last_modified="Wed, 04 Nov 2026 08:00:00 GMT",
            chunk_delay_ms=0,
        )
        server.start()
        try:
            request = urllib.request.Request(
                server.url,
                headers={"Range": "bytes=200-2000"},
            )
            with urllib.request.urlopen(request, timeout=5) as response:
                body = response.read()
                self.assertEqual(response.status, 206)
                self.assertEqual(response.headers["Content-Range"], "bytes 200-2000/262144")
            self.assertEqual(body, payload[200:2001])
            self.assertEqual(server.bytes_sent, 1801)
            records = server.records()
            self.assertEqual(len(records), 1)
            self.assertEqual(records[0]["sent_bytes"], 1801)
            self.assertEqual(records[0]["status"], 206)
        finally:
            server.stop()

    def test_resume_note_parser_requires_reuse_and_no_discard(self) -> None:
        reused = (
            '{"type":"metadata","resume":"parts=true weak=false '
            'lenChanged=false validDiff=false print=MATCH discard=false"}'
        )
        discarded = (
            '{"type":"metadata","resume":"parts=true weak=true '
            'lenChanged=false validDiff=true print=MISMATCH discard=true"}'
        )
        self.assertTrue(rr.resume_was_reused(reused))
        self.assertFalse(rr.resume_was_reused(discarded))
        self.assertFalse(rr.resume_was_reused("no metadata"))

    def test_command_uses_same_stable_output_for_both_runs(self) -> None:
        command = rr.build_cli_command(
            java_exe="java",
            jar=Path("turbodl.jar"),
            url="http://127.0.0.1/f",
            destination=Path("out.bin"),
            connections=8,
            expected_sha256="a" * 64,
            progress_interval_ms=100,
        )
        self.assertIn("--sha256", command)
        self.assertIn("--timing", command)
        self.assertEqual(command[command.index("-o") + 1], "out.bin")
        self.assertEqual(command[command.index("-c") + 1], "8")

    def test_sha256_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "data.bin"
            payload = rr.build_payload(8192)
            path.write_bytes(payload)
            self.assertEqual(rr.sha256_file(path), rr.sha256_bytes(payload))

    def test_progress_sidecar_parser(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "progress.tdlp"
            path.write_bytes(
                b"TDLP"
                + struct.pack(">iqi", 1, 1000, 2)
                + struct.pack(">qqq", 0, 499, 500)
                + struct.pack(">qqq", 500, 999, 125)
            )
            snapshot = rr.parse_progress_sidecar(path)
            self.assertEqual(snapshot["total"], 1000)
            self.assertEqual(snapshot["pieces"], 2)
            self.assertEqual(snapshot["covered_bytes"], 1000)
            self.assertEqual(snapshot["completed_bytes"], 625)
            path.write_bytes(b"bad")
            with self.assertRaises(ValueError):
                rr.parse_progress_sidecar(path)

    def test_progress_sidecar_parser_v2(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "progress.tdlp"
            identity = b"file-identity"
            path.write_bytes(
                b"TDLP" + struct.pack(">ii", 2, len(identity)) + identity
                + struct.pack(">qi", 1000, 2)
                + struct.pack(">qqq", 0, 499, 500)
                + struct.pack(">qqq", 500, 999, 125)
            )
            self.assertEqual(rr.parse_progress_sidecar(path)["completed_bytes"], 625)
            path.write_bytes(b"TDLP" + struct.pack(">ii", 2, 100) + b"short")
            with self.assertRaises(ValueError):
                rr.parse_progress_sidecar(path)


if __name__ == "__main__":
    unittest.main()
