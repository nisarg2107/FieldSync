# FieldSync

A generic offline-first Android app for collaborative tasks and projects.
Built with Kotlin, Jetpack Compose, Room, Go, and PostgreSQL as a readable learning project.

## Features

- Offline tasks with project labels, priority, due dates, assignment, notes, and history.
- Checklists, photos from CameraX or the gallery, and optional location attachments.
- Nearby device discovery, pairing-code confirmation, and two-way synchronization.
- Cloud synchronization with accounts and owner/editor/viewer team permissions.
- CRDT field merging, logical clocks, idempotent operation logs, and explicit status-conflict resolution.
- Task dashboard and recent sync activity, with background cloud retries through WorkManager.

## Run the backend

With Docker Desktop running:

```sh
docker compose up --build
```

The API runs at `http://localhost:8080`. PostgreSQL and photos use persistent volumes.
The Compose credentials and HTTP configuration are for a local demo.

Alternatively, run Go 1.24+ against your own PostgreSQL database:

```powershell
cd backend
$env:DATABASE_URL = 'postgres://fieldsync:fieldsync@localhost:5432/fieldsync?sslmode=disable'
$env:JWT_SECRET = 'a-local-development-secret'
go run ./cmd/server
```

Without `DATABASE_URL`, only the public demo is available and its operation log is
held in memory. Accounts and private teams require PostgreSQL. Photos default to
`./data/media`; optional S3 storage uses `S3_BUCKET`, standard AWS credentials/region,
and optional `S3_ENDPOINT` for a compatible endpoint.

## Run Android

Open `android/` in Android Studio with JDK 17, SDK 36, and Build Tools 35.0.0.
Let Android Studio configure the SDK path, then run `app` on Android 6/API 23 or newer.
Nearby and location require Google Play services.

```powershell
cd android
.\gradlew.bat :app:assembleDebug
```

On macOS/Linux, use `bash ./gradlew :app:assembleDebug`.
Build outputs are ignored by Git; GitHub Actions also uploads an APK build artifact.

In **Sync**, save the server URL: `http://10.0.2.2:8080` for an emulator, or
`http://YOUR-COMPUTER-LAN-IP:8080` for a phone on the computer's network.
In **Team**, register and create a team, or join using its invite code. New members
are viewers; the owner grants editor access. Refresh teams and members afterward.
The separate Public demo works without an account.

## Try offline collaboration

1. Install the app on two phones and select the same team or Public demo. Sync once.
2. Disconnect internet, keeping Bluetooth and Wi-Fi on. Edit tasks on both phones.
3. Open **Nearby** on both, allow permissions, connect from one phone, and confirm
   the matching pairing codes. Keep both apps visible while exchanging.
4. Choose different statuses offline, exchange, then resolve the displayed conflict
   and exchange again. Checklists and photos also transfer.
5. Restore internet and sync both phones with the server.

Older Android versions require location permission for discovery; Android 12/12L
request it alongside Bluetooth, while Android 13+ use Nearby Wi-Fi permission.
Some older phones also need the system Location toggle enabled.
Interrupted sessions can be retried safely. An operations-only public-demo LAN
fallback is available under **Sync → Advanced** for emulator use.

## Verify

```sh
cd backend
go vet ./...
go test ./...
go run ./cmd/simulate -seed 42 -replicas 3 -edits 120 -loss 0.6 -delay 2ms
```

Set `TEST_DATABASE_URL` to include PostgreSQL/account/team tests.

```powershell
cd android
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
# With a device/emulator and the PostgreSQL-backed API running:
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.cloudUrl=http://10.0.2.2:8080
```

The two live cloud tests skip when `cloudUrl` is omitted.
See [validation](docs/validation.md) for test results and hardware checks still needed.

## Learn the code

- [Reading guide](docs/reading-guide.md): where to start and how the files connect.
- [Architecture](docs/architecture.md): local edits, CRDT merging, and synchronization.
- [Protocol](docs/protocol.md): operations, endpoints, and peer messages.
- [Libraries](docs/dependencies.md): infrastructure reused from existing projects.
- [Benchmarks](docs/benchmarks.md): reproducible fault tests and measurement scope.
- [Screenshots](docs/screenshots/): actual emulator screens.

The app uses two Android modules and a small manual dependency container. Projects
are labels; notes merge as whole fields. Logs, tombstones, and photo files are retained
without garbage collection. Nearby pairing trusts the teammate you approve; cloud
access checks current team membership. See the architecture for these tradeoffs.
