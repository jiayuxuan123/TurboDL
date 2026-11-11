# TurboDL 0.5.0.3 / YunGet 2.7.30 work record

Status: release candidate complete. TurboDL and YunGet release assets are packaged and
verified locally; GitHub publication remains.

- Baselines: TurboDL a7b7cd6 / 0.5.0.2; YunGet b2eca340 / 2.7.29 (77).
- Both previous Cloudflare production sites were synchronized with Wrangler and checked through the production deployment API.
- Baseline artifacts and hashes: D:/CodexResearch/releases/TurboDL-0.5.0.3-work/BASELINE.json.
- Existing traffic ledger received the idempotent authorization `release-0.5.0.3-20261111-4g`; total 12 GiB. Do not grant again under another ID.
- Public fixture: OrcaSlicer Linux Ubuntu 24.04 v2.3.2, 124672504 bytes; official SHA-256 `c64336ceec37d941766e675cbaaaf5124e184402bf18177fbf81ba5102734ad8`.
- Metadata resolution succeeded using the user-authorized account. Credentials and signed URLs are held outside the repositories, under a current-user-only ACL.
- Initial whole-file SDK and Wenxi downloads passed the official SHA-256. Early SDK comparison files were written on D: and are integrity evidence only; final performance samples must use C: SSD scratch, deleted after each round.
- A 180-second curl direct sample received 46464423 bytes (258114 B/s), but did not complete. It is a partial throughput observation, not an integrity pass.
- A 64 KiB request-window exploration took 177.89 seconds at c64. It is not selected as a default and needs comparison under identical conditions.
- Wenxi generic c64 on C: completed in 63.39 seconds with the correct SHA-256. This does not establish a universal engine ranking.

Implemented and validated changes:

- Additive task policy builder and task details; legacy request/event contracts remain intact.
- Shared source cooldown and recovery admission for explicitly grouped tasks.
- YunGet Room migration 24 to 25, source owner/reference/policy snapshots, pipeline phases.
- Generation-checked completion, storage-only retry, bounded pipeline admission, original-owner URL refresh.
- Save destination commit failures propagate; storage copying is cancellable.

Final validation:

- TurboDL full gate: 115 suites, 629 tests, 0 failures, 0 skipped; Python experiment
  tooling: 36 tests passed.
- YunGet: 57 app suites and 414 tests passed; 26 app core tests passed; screenshot,
  host compilation, release build, version, ZIP and signing checks passed.
- TurboDL release package: 24 verified assets.
- YunGet release package: 6 verified assets, with the universal APK first for the
  legacy updater.
- Controlled source-recovery A/B: 9 pairs, 1.6401x median speedup, 18/18 checksums
  passed.
- Public Quark comparison: 9/9 full-file checksums passed. The candidate was level
  with the stable build on that path; no universal public-network speedup is claimed.
- Native: 8 target triples produced launcher and dataplane binaries; Windows x64
  launcher and JVM smoke tests passed. Other targets remain compile/header checks.

GitHub releases, website Release data, Cloudflare Pages production verification and
the in-app announcement remain outstanding.
