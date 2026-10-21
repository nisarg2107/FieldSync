package dev.fieldsync.sync

import dev.fieldsync.core.*
import dev.fieldsync.data.TaskRepository
import dev.fieldsync.data.EvidenceStore
import dev.fieldsync.data.SyncRun
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException

class SyncCoordinator(
    private val repository: TaskRepository,
    private val transport: HttpTransport,
    private val evidence: EvidenceStore? = null,
) {
    private val mutex = Mutex()
    private val message = MutableStateFlow("Edits save on this device immediately")
    val status = message.asStateFlow()

    suspend fun changeServer(url: String) = mutex.withLock { repository.changeServer(normalizeServerUrl(url)) }
    suspend fun useTeam(team: Team, members: List<Member> = emptyList()) = mutex.withLock { repository.useTeam(team, members) }
    suspend fun signOut() = mutex.withLock { repository.signOut() }

    suspend fun cloud() = mutex.withLock {
        report("Syncing with cloud…", "Cloud sync complete") {
            val started = System.currentTimeMillis()
            var uploaded = 0; var downloaded = 0; var bytes = 0L
            val initial = repository.state()
            try {
            // Upload files separately so replaying an operation never duplicates binary data.
            if (evidence != null && initial.role != "viewer") for (photo in repository.pendingMedia()) {
                val file = evidence.file(photo.hash)
                if (file.isFile) {
                    transport.uploadPhoto(initial.serverUrl, initial.token, initial.workspace, photo.hash, file)
                    repository.acknowledgeMedia(photo.hash)
                    bytes += file.length()
                }
            }
            var more: Boolean
            do {
                val state = repository.state()
                val pending = if (state.role == "viewer") emptyList() else repository.pending()
                val request = SyncRequest(workspace = state.workspace, cursor = state.cursor, operations = pending)
                val response = transport.cloud(state.serverUrl, request, state.token)
                repository.receiveCloud(response, pending, state.cursor)
                uploaded += pending.size; downloaded += response.operations.size
                bytes += wireJson.encodeToString(request).toByteArray().size + wireJson.encodeToString(response).toByteArray().size
                more = response.hasMore || (state.role != "viewer" && repository.pending().isNotEmpty())
            } while (more)
            if (evidence != null) for (photo in evidence.missing()) {
                val data = transport.downloadPhoto(initial.serverUrl, initial.token, initial.workspace, photo.hash)
                if (data != null) { evidence.receive(photo.hash, data, fromCloud = true); bytes += data.size }
            }
            repository.recordRun(SyncRun(transport = "cloud", startedAt = started,
                durationMs = System.currentTimeMillis() - started, uploaded = uploaded, downloaded = downloaded,
                bytes = bytes, success = true, message = "Complete", workspace = initial.workspace))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                repository.recordRun(SyncRun(transport = "cloud", startedAt = started,
                    durationMs = System.currentTimeMillis() - started, uploaded = uploaded, downloaded = downloaded,
                    bytes = bytes, success = false, message = e.message.orEmpty(), workspace = initial.workspace))
                throw e
            }
        }
    }

    /** Peer cursors are session-local: replay the log to repair either device after data loss. */
    suspend fun peer(input: String) = mutex.withLock {
        report("Exchanging with peer…", "Peer exchange complete") {
            require(repository.state().workspace == WORKSPACE) { "Use Nearby for private team exchange" }
            val url = normalizeServerUrl(input)
            var cursor = 0L
            do {
                val page = repository.peerPage(cursor)
                if (page.operations.isNotEmpty()) transport.pushPeer(url, page.operations)
                cursor = page.cursor
            } while (page.hasMore)
            cursor = 0L
            do {
                val page = transport.pullPeer(url, cursor)
                require(page.cursor >= cursor && (!page.hasMore || page.cursor > cursor)) { "Peer cursor made no progress" }
                repository.receivePeer(page.operations)
                cursor = page.cursor
            } while (page.hasMore)
        }
    }

    private suspend fun report(start: String, success: String, block: suspend () -> Unit) {
        message.value = start
        try {
            block()
            message.value = success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message.value = "Saved locally. Sync failed: ${e.message}"
            throw e
        }
    }
}

