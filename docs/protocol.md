# FieldSync version 2 protocol

This protocol is intentionally a small teaching example. Kotlin and Go share JSON
fixtures in `testdata/`; update both validators when adding fields. Both phones
should run version 2. Old persisted Room data migrates; an old app cannot read new fields.

## One operation

```json
{
  "id": "operation-uuid",
  "taskId": "task-uuid",
  "actorId": "device-uuid",
  "clock": 12,
  "field": "description",
  "value": "Discuss the next release",
  "createdAt": 1792530000000,
  "author": "Sam"
}
```

IDs contain 1–80 ASCII letters, digits, `_` or `-`. Clock range is
1–9,007,199,254,740,991. The device persists its actor/clock and advances to at
least every received clock before editing again. `createdAt` and `author` are
display metadata, not authenticated authorship or conflict ordering. Old operations
without them default to zero and empty string.

An operation ID and `(actorId, clock)` must each identify identical content within
a workspace. Replaying identical operations is accepted; conflicting reuse rejects
the batch atomically. Ordinary fields choose maximum `(clock, actorId)`.

| Field | String value |
| --- | --- |
| `title` | Nonblank, at most 200 characters |
| `description`, `project`, `notes`, `createdBy` | Up to 2,000 characters |
| `priority` | `low`, `normal`, `high` |
| `dueDate` | Empty or valid `YYYY-MM-DD` |
| `assignee` | Empty or user ID; private cloud uploads require a team member |
| `deleted`, legacy `completed` | `true` or `false` |
| `check:ID:label` | Nonblank, up to 200 characters |
| `check:ID:result` | `unchecked`, `pass`, `fail` |
| `check:ID:note` | Up to 2,000 characters |
| `check:ID:deleted` | `true` or `false` |
| `status` | Encoded JSON status change |
| `gps` | Empty, or encoded JSON location |
| `photo:SHA256` | Empty removal tombstone, or encoded JSON photo metadata |

Structured values are JSON **inside a string** to keep the operation envelope small:

```json
{"value":"completed","observed":["prior-status-operation-id"]}
```

Status values: `open`, `in_progress`, `blocked`, `completed`. `observed` lists up to
100 unique versions seen by the author. Applying status adds the new version and
unions observed IDs into a superseded set, independent of arrival order. More than
one distinct active value is a visible conflict; selecting a status observes all
active IDs and resolves it. Sets/history remain retained in this learning version.

```json
{"latitude":33.45,"longitude":-112.07,"accuracy":12.0,"recordedAt":1792530000000}
```

Coordinates must be finite and within geographic bounds; accuracy is nonnegative
meters. Location is optional and captured only after permission and a valid fix.

```json
{"hash":"64-lowercase-hex-characters","size":12345,"caption":"Draft sketch","mimeType":"image/jpeg"}
```

The metadata hash equals the field suffix. Maximum file size is 8 MiB; allowed types
are JPEG, PNG, WebP. Android imports convert images to JPEG. The sample hash above
is illustrative; real values must match the SHA-256 hex format.

## Cloud API

`POST /v1/sync` takes `{workspace, cursor, operations}` and returns
`{accepted, operations, cursor, hasMore}`. Each direction is limited to 100 operations;
request body limit is 2 MiB. Cursor is the last returned server sequence, not a
device clock. Download queries use `seq > cursor`, allowing rolled-back sequence gaps.
Repeat until the outbox and remaining pages are empty.

Android validates responses and commits imported operations, accepted IDs, and cursor
in one transaction. A response lost after server commit leaves local operations
pending; the next identical request safely deduplicates. Cloud operations are
acknowledged; peer operations remain pending for relay.

| Endpoint | Purpose |
| --- | --- |
| `POST /v1/auth/register` | `{name,email,password}` → `{user,token}` |
| `POST /v1/auth/login` | `{email,password}` → `{user,token}` |
| `GET /v1/teams` | Teams visible to the bearer account |
| `POST /v1/teams` | `{name}` creates; `{inviteCode}` joins as viewer |
| `GET /v1/teams/{team}/members` | List team members |
| `PUT /v1/teams/{team}/members` | Owner sets `{userId,role}` to editor/viewer |
| `PUT /v1/media/{workspace}/{hash}` | Upload raw image, editor/owner required |
| `GET /v1/media/{workspace}/{hash}` | Download image, viewers allowed |

Use `Authorization: Bearer TOKEN` for private teams. Accounts require PostgreSQL.
`demo` sync/media is public. Private endpoints check current membership and upload
role, rather than relying on a client's claimed workspace or cached role. Owners
cannot demote their own owner membership. Private assignees must belong to the team.
There is no user deletion/account recovery flow in this version.

Photos upload before metadata operations and download after log exchange. PUT verifies
SHA-256, size, and image type. Retries address the same object, so no duplicate file
identities are created. Local files use temporary-write/rename; S3 uses the SDK.

## Nearby protocol

Both devices advertise/discover `dev.fieldsync.v2.WORKSPACE` using Google's point-to-point
strategy. Both users approve the matching authentication digits before exchanging.
A discovered name/workspace is not proof of server membership; the peer is trusted
by explicit user pairing.

`PeerMessage` contains `kind`, `workspace`, `requestId`, `reply`, optional `cursor`,
`hasMore`, `operations`, `hash`, `photo` (base64), and `error`. Each request uses a
UUID; its reply echoes that ID and workspace. Requests time out after 60 seconds.
One JSON frame is one STREAM payload, bounded to 12 MiB including base64 overhead.

1. `hello`: begin exchange.
2. `push`: import batches of up to 100 local operations.
3. `photo_put`: transfer each locally available attachment with its hash.
4. `pull`: fetch peer log pages from cursor zero, importing the union of edits.
5. `photo_get`: fetch missing referenced images; verify bytes before saving.
6. `done`: record successful session metrics on both devices.

The entire log and available photos are replayed each session. This trades bandwidth
for simple recovery without per-peer checkpoints. Interruptions retry the session;
operations deduplicate and existing hash files stay valid. Edits made while a session
is running may require another session. Streams/base64 are easy to follow, but not
optimized for large photo libraries. Progress describes the current payload.

The older public-demo LAN fallback uses NanoHTTPD on port 8787 and paginated operation
POST/GET. It has no photo transfer or private team access and requires a shared LAN.
