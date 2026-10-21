package dev.fieldsync.sync

import android.util.Base64
import dev.fieldsync.core.*
import dev.fieldsync.data.EvidenceStore
import dev.fieldsync.data.SyncRun
import dev.fieldsync.data.TaskRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.util.UUID

/** One request/reply protocol works over Nearby; the radio API stays outside merge logic. */
@Serializable
data class PeerMessage(
    val kind: String,
    val workspace: String,
    val requestId: String = "",
    val reply: Boolean = false,
    val cursor: Long = 0,
    val hasMore: Boolean = false,
    val operations: List<Operation> = emptyList(),
    val hash: String = "",
    val photo: String = "",
    val error: String = "",
)

fun interface PeerLink { suspend fun request(message: PeerMessage): PeerMessage }

class PeerHandler(private val repository: TaskRepository, private val evidence: EvidenceStore? = null) {
    private var started = 0L
    private var received = 0
    private var sent = 0
    private var bytes = 0L

    suspend fun handle(message: PeerMessage): PeerMessage {
        require(message.workspace == repository.state().workspace) { "Select the same team on both devices" }
        require(!message.reply)
        bytes += wireJson.encodeToString(message).toByteArray().size
        val response = when (message.kind) {
            "hello" -> {
                started = System.currentTimeMillis(); received = 0; sent = 0; bytes = 0
                message.copy(reply = true)
            }
            "push" -> {
                repository.receivePeer(message.operations, message.workspace)
                received += message.operations.size
                message.copy(reply = true, operations = emptyList())
            }
            "pull" -> {
                val page = repository.peerPage(message.cursor)
                sent += page.operations.size
                message.copy(reply = true, operations = page.operations, cursor = page.cursor, hasMore = page.hasMore)
            }
            "photo_put" -> {
                val store = requireNotNull(evidence) { "Photo transfer unavailable" }
                store.receive(message.hash, Base64.decode(message.photo, Base64.NO_WRAP))
                message.copy(reply = true, photo = "")
            }
            "photo_get" -> {
                require(photoHash.matches(message.hash))
                val file = evidence?.file(message.hash)
                message.copy(reply = true, photo = if (file?.isFile == true) Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) else "")
            }
            "done" -> {
                repository.recordRun(SyncRun(transport = "nearby", startedAt = started,
                    durationMs = System.currentTimeMillis() - started, uploaded = sent, downloaded = received,
                    bytes = bytes, success = true, message = "Peer exchange complete", workspace = message.workspace))
                message.copy(reply = true)
            }
            else -> error("Unknown peer message")
        }
        bytes += wireJson.encodeToString(response).toByteArray().size
        return response
    }
}

class PeerSession(private val repository: TaskRepository, private val evidence: EvidenceStore? = null) {
    suspend fun exchange(link: PeerLink) {
        val workspace = repository.state().workspace
        val started = System.currentTimeMillis()
        var uploaded = 0; var downloaded = 0; var bytes = 0L
        suspend fun request(input: PeerMessage): PeerMessage {
            val message = input.copy(requestId = UUID.randomUUID().toString())
            require(repository.state().workspace == workspace) { "Team changed; reconnect" }
            val response = link.request(message)
            require(response.workspace == workspace && response.reply && response.requestId == message.requestId) { "Unexpected peer response" }
            check(response.error.isEmpty()) { response.error }
            bytes += wireJson.encodeToString(message).toByteArray().size + wireJson.encodeToString(response).toByteArray().size
            return response
        }
        try {
            request(PeerMessage("hello", workspace))
            var cursor = 0L
            do {
                val page = repository.peerPage(cursor)
                if (page.operations.isNotEmpty()) request(PeerMessage("push", workspace, operations = page.operations))
                uploaded += page.operations.size
                cursor = page.cursor
            } while (page.hasMore)
            if (evidence != null) for (photo in evidence.available()) {
                val encoded = Base64.encodeToString(evidence.file(photo.hash).readBytes(), Base64.NO_WRAP)
                request(PeerMessage("photo_put", workspace, hash = photo.hash, photo = encoded))
            }
            cursor = 0L
            do {
                val page = request(PeerMessage("pull", workspace, cursor = cursor))
                require(page.cursor >= cursor && (!page.hasMore || page.cursor > cursor)) { "Peer made no progress" }
                repository.receivePeer(page.operations, workspace)
                downloaded += page.operations.size
                cursor = page.cursor
            } while (page.hasMore)
            if (evidence != null) for (photo in evidence.missing()) {
                val response = request(PeerMessage("photo_get", workspace, hash = photo.hash))
                if (response.photo.isNotEmpty()) evidence.receive(photo.hash, Base64.decode(response.photo, Base64.NO_WRAP))
            }
            request(PeerMessage("done", workspace))
            repository.recordRun(SyncRun(transport = "nearby", startedAt = started,
                durationMs = System.currentTimeMillis() - started, uploaded = uploaded, downloaded = downloaded,
                bytes = bytes, success = true, message = "Peer exchange complete", workspace = workspace))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            repository.recordRun(SyncRun(transport = "nearby", startedAt = started,
                durationMs = System.currentTimeMillis() - started, uploaded = uploaded, downloaded = downloaded,
                bytes = bytes, success = false, message = e.message.orEmpty(), workspace = workspace))
            throw e
        }
    }
}
