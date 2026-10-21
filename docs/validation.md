# Validation

Test environment: October 21, 2026; JDK 17, an Android API 30 Google APIs emulator,
and a local Go server backed by PostgreSQL 17.11.

| Check | Result |
| --- | --- |
| Go formatting, `go vet`, and uncached backend tests | Passed, including real PostgreSQL/account/team tests |
| Kotlin/JVM tests | 13 passed |
| Android build and lint | Succeeded; 0 lint errors, 30 warnings |
| Android integration/UI tests with live cloud URL | 16 passed |
| Fault simulation, 360 operations across 3 replicas | Converged |
| Fault simulation, 1,000 operations across 5 replicas | Converged |

## What the tests cover

Room transactions and database reopen, persisted actor/clock identity, migration
from schema version 1, operation identity collisions, acknowledgments and cursors,
workspace isolation, team roles, private cloud sync, LAN exchange and recovery,
photo transfer/integrity, and Compose task/checklist interaction.

Shared Kotlin/Go fixtures test 200 shuffled and duplicated delivery orders. The
Nearby protocol test uses independent Room databases and photo folders to merge
checklists, preserve concurrent statuses, resolve a conflict, and retry after a
lost reply. It tests the application protocol, without exercising physical radios.
The UI test searches for its own task so existing device data cannot hide it.

The optional S3 adapter is tested against a local HTTP endpoint with dummy credentials.
The saved [sample](sample-benchmark.json) and [stress](stress-benchmark.json) reports
provide reproducible benchmark evidence; timings vary by host.

## Checks still requiring hardware or external services

- Nearby discovery, code confirmation, Bluetooth/Wi-Fi transfer, and reconnection
  between two physical phones.
- Camera/photo picker behavior, image orientation, GPS fixes, and permission denial
  on the phones and Android versions used for the demonstration.
- A real S3 bucket, if that optional storage mode is selected.
- Docker Compose and GitHub Actions on their actual runners. Local checks used
  native Go/PostgreSQL and Gradle; the CI workflow is configured to run the live tests.

The source and tests support the offline persistence, CRDT, logical-clock,
operation-log, idempotency, cloud sync, and convergence claims. Complete the
two-phone demonstration before describing Nearby radio behavior as hardware-verified.
