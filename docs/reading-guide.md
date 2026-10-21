# Read the project in this order

Start with basic Kotlin/Go, JSON, and SQL. The app uses direct constructors and a
small dependency container; there is no dependency-injection or use-case framework.

1. **`android/core/.../Protocol.kt`**: one operation changes one task field. Its
   UUID is identity; its actor/clock pair orders changes. `Task.kt` implements LWW
   fields and the status multi-value register. `Collaboration.kt` holds simple models.
2. **`testdata/crdt.json` and `testdata/collaboration.json`**: read the shared edits,
   then the Kotlin/Go convergence tests. Scrambling and duplicating delivery must
   preserve the final state. Try leaving out the explicit status resolution.
3. **`data/FieldDatabase.kt`**: task snapshots, operation log/outbox, replica state,
   photo-transfer records, and sync history. Read the version 1→2 migration too.
4. **`data/TaskRepository.kt`**: follow `editFields()`, `applyOperations()`, and
   `receiveCloud()`. Transactions keep snapshots, logical clocks, IDs, and cursors
   consistent. `EvidenceStore.kt` separates binary files from operation metadata.
5. **`sync/SyncCoordinator.kt`**: one cloud upload/download loop. `HttpTransport.kt`
   delegates HTTP to OkHttp, and `SyncWorker.kt` delegates retry scheduling to Android.
6. **`sync/PeerSession.kt`**, then **`NearbyManager.kt`**: understand the request/reply
   exchange before reading the Google Nearby callbacks. The former can be tested
   with two Room replicas without real radios. `PeerServer.kt` is only the demo LAN fallback.
7. **`backend/internal/api/handler.go`**: trace a sync request into `store/memory.go`,
   then `store/postgres.go`. Read their common store tests. `auth/service.go` and
   `api/accounts.go` add account/team permissions; `media/` delegates storage to files or S3.
8. **`ui/BoardViewModel.kt`**, then **`BoardScreen.kt`**: local flows drive Compose.
   Each screen has a focused file: task editing/details, Nearby, teams, sync, and camera.
   `FieldSyncApp.kt` wires concrete dependencies together.
9. **`backend/internal/simulation/`**: a seeded faulty link loses requests and
   responses. Recovery must drain outboxes and make all replica snapshots equal.
10. **`app/src/androidTest/.../CollaborationIntegrationTest.kt`** and
    **`ui/GenericWorkflowTest.kt`**: persistence migration, private cloud collaboration,
    photos, status decisions, and real UI interaction.

An easy exercise: add another checklist result in both protocol validators, the UI,
and shared fixtures. A larger exercise: make project labels a separate synchronized
entity. Keep each change visible in both Kotlin and Go; neither platform should
accept an operation the other cannot read.
