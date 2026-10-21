package dev.fieldsync.sync

import dev.fieldsync.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Test
import java.io.IOException
import kotlin.test.*

class HttpTransportTest {
    @Test fun `lost response can be retried with the same operation identity`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val op = Operation("op-1", "task", "alice", 1, "title", "Inspect pump")
            val request = SyncRequest(cursor = 0, operations = listOf(op))
            val transport = HttpTransport()
            val url = server.url("/").toString().removeSuffix("/")
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            assertFailsWith<IOException> { transport.cloud(url, request) }
            server.enqueue(MockResponse().setBody(wireJson.encodeToString(SyncResponse(listOf(op.id), listOf(op), 1, false))))
            assertEquals(1L, transport.cloud(url, request).cursor)
            assertEquals(server.takeRequest().body.readUtf8(), server.takeRequest().body.readUtf8())
        }
    }

    @Test fun `HTTP errors remain failures so caller keeps the outbox`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))
            assertFailsWith<IOException> { HttpTransport().cloud(server.url("/").toString().removeSuffix("/"), SyncRequest(cursor = 0, operations = emptyList())) }
        }
    }

    @Test fun `base URLs reject hidden path and credential configuration`() {
        assertEquals("http://localhost:8080", normalizeServerUrl(" http://localhost:8080/ "))
        listOf("http://localhost/v1", "http://user:secret@localhost", "http://localhost?x=1").forEach {
            assertFails { normalizeServerUrl(it) }
        }
    }
}

