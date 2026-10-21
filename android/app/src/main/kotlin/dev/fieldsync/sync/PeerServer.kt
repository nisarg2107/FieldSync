package dev.fieldsync.sync

import dev.fieldsync.core.*
import dev.fieldsync.data.TaskRepository
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

/** Start explicitly from the screen; stop when the Activity leaves the foreground. */
class PeerServer(private val repository: TaskRepository, private val onImported: () -> Unit) : NanoHTTPD(8787) {
    override fun serve(session: IHTTPSession): Response = try {
        require(runBlocking(Dispatchers.IO) { repository.state().workspace } == WORKSPACE) { "Use confirmed Nearby connections for private teams" }
        require(session.uri == "/peer/operations") { "Unknown peer route" }
        when (session.method) {
            Method.GET -> {
                require(session.parameters["workspace"]?.firstOrNull() == WORKSPACE) { "Wrong workspace" }
                val cursor = session.parameters["cursor"]?.firstOrNull()?.toLongOrNull() ?: 0L
                val page = runBlocking(Dispatchers.IO) { repository.peerPage(cursor) }
                json(Response.Status.OK, wireJson.encodeToString(page))
            }
            Method.POST -> {
                val length = session.headers["content-length"]?.toLongOrNull()
                require(length != null && length in 1..2097152) { "Expected a body up to 2 MiB" }
                val files = mutableMapOf<String, String>()
                session.parseBody(files)
                val batch = wireJson.decodeFromString<PeerBatch>(files["postData"] ?: error("Missing JSON"))
                require(batch.workspace == WORKSPACE) { "Wrong workspace" }
                runBlocking(Dispatchers.IO) { repository.receivePeer(batch.operations) }
                onImported()
                json(Response.Status.OK, "{}")
            }
            else -> json(Response.Status.METHOD_NOT_ALLOWED, "{}")
        }
    } catch (e: Exception) {
        json(Response.Status.BAD_REQUEST, wireJson.encodeToString(mapOf("error" to (e.message ?: "Invalid peer request"))))
    }

    private fun json(status: Response.Status, text: String): Response = newFixedLengthResponse(status, "application/json", text)
}

