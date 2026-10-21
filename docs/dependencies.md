# Existing libraries and ideas

FieldSync delegates general infrastructure to existing libraries. The custom code
is the small operation format, task-specific merge rule, and coordination between
these components. Versions are deliberately pinned; they are not advertised as
the newest versions available.

| Component | Reused project / documentation | Purpose |
| --- | --- | --- |
| Android UI | [Jetpack Compose](https://developer.android.com/develop/ui/compose/documentation) | Reactive screen and Material components |
| Local persistence | [Room](https://developer.android.com/training/data-storage/room) | SQLite mapping, transactions, observable queries |
| Background retries | [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) | Persistent jobs, connectivity constraints, backoff |
| Nearby phones | [Google Nearby Connections](https://developers.google.com/nearby/connections/overview) | Discovery, pairing codes, encrypted radio connection, stream payloads |
| Camera | [CameraX](https://developer.android.com/media/camera/camerax) | Preview, lifecycle handling, JPEG image capture |
| Location | [Fused Location Provider](https://developers.google.com/location-context/fused-location-provider) | Optional current location with user permission |
| Image display | [Coil](https://coil-kt.github.io/coil/compose/) | Local photo loading in Compose |
| Android HTTP client | [OkHttp](https://square.github.io/okhttp/) | Connections, request bodies, timeouts; MockWebServer for tests |
| Peer HTTP server | [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) | Embedded Java HTTP server |
| JSON on Android | [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Typed JSON encoding and decoding |
| Go HTTP and tests | [Go standard library](https://pkg.go.dev/net/http) | Routing, JSON, HTTP test harness, benchmarks |
| PostgreSQL driver | [pgx](https://github.com/jackc/pgx) | Connection pooling and transactions |
| Password hashing | [Go bcrypt](https://pkg.go.dev/golang.org/x/crypto/bcrypt) | Existing password hash implementation |
| Account tokens | [golang-jwt/jwt](https://github.com/golang-jwt/jwt) | JWT signing and validation |
| Optional S3 storage | [AWS SDK for Go v2](https://docs.aws.amazon.com/sdk-for-go/v2/developer-guide/welcome.html) | Credentials, signing, S3 PUT/GET |
| Database | [PostgreSQL advisory locks](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS) | Serialize a workspace's cursor-visible commits |
| Conflict resolution | [Shapiro et al., CRDT survey, LWW register](https://asc.di.fct.unl.pt/~nmp/pubs/tr-inria-2011.pdf) | Established register merge rule, implemented in Kotlin and Go |

The field reducers explicitly implement established LWW and multi-value register
ideas using a version comparison and set union. Their small task model keeps the
learning exercise readable. A general collaborative-document framework would add
a second data model. No custom database, password hashing algorithm, HTTP parser,
retry scheduler, camera framework, or radio discovery protocol is implemented.

Android's [offline-first architecture guide](https://developer.android.com/topic/architecture/data-layer/offline-first)
informs the local database/repository arrangement. Nearby's
[payload guide](https://developers.google.com/nearby/connections/android/exchange-data)
documents the stream API used here. FieldSync reads streams off the callback thread
and waits for EOF before decoding a complete JSON frame. Versions are pinned in
`android/app/build.gradle.kts`, the root Gradle file, and `backend/go.mod`.

