# YunGet integration requirements

Production baseline: 2.7.39. The native-core default switch and the next formal
release remain gated by the accepted protocol, platform and comparison matrix.

## Anonymous analytics migration (user instruction, 2026-11-17)

During the app integration phase, replace the analytics ingestion API and its
storage path. Do not create another event table in the community D1 database.
Community traffic must remain independent of analytics quota and failures.

Select the replacement against the actual volume, retention, cost and available
account permissions when that phase starts. Preserve explicit consent, the
off switch, bounded local retry, event deduplication, and the existing prohibition
on uploading account credentials, file names, download URLs or raw error text.
Update the developer dashboard, privacy text, website and upgrade notice together.

This migration is deferred until app integration; native engine work continues.
