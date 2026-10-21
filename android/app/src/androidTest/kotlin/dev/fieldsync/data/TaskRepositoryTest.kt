package dev.fieldsync.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.fieldsync.core.*
import dev.fieldsync.sync.HttpTransport
import dev.fieldsync.sync.PeerServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class TaskRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: FieldDatabase
    private lateinit var repository: TaskRepository
    private val name = "test-${UUID.randomUUID()}.db"

    @Before fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.databaseBuilder(context, FieldDatabase::class.java, name).build()
        repository = TaskRepository(database)
    }
    @After fun close() { database.close(); context.deleteDatabase(name) }

    private fun op(id: String = "remote-1", clock: Long = 10, field: String = "title", value: String = "Inspect pump") =
        Operation(id, "task", "remote", clock, field, value)

    @Test fun outboxClockAndActorSurviveDatabaseReopen() = runBlocking {
        repository.receivePeer(listOf(op()))
        repository.edit("task", "completed", "true")
        val before = repository.state()
        assertEquals(11L, before.clock)
        database.close()
        database = Room.databaseBuilder(context, FieldDatabase::class.java, name).build()
        repository = TaskRepository(database)
        assertEquals(before, repository.state())
        assertEquals(2, repository.pending().size)
        assertEquals("true", repository.tasks.first().single().completed.value)
    }

    @Test fun duplicatesAndReorderingConverge() = runBlocking {
        repository.receivePeer(listOf(op("new", 20), op("old", 10, value = "Old title"), op("new", 20)))
        assertEquals(2, repository.peerPage(0).operations.size)
        assertEquals("Inspect pump", repository.tasks.first().single().title.value)
    }

    @Test fun concurrentLocalEditsGetDistinctLogicalTimestamps() = runBlocking {
        coroutineScope {
            repeat(30) { index ->
                launch(Dispatchers.IO) { repository.edit("task", "title", "Inspection $index") }
            }
        }
        val operations = repository.peerPage(0).operations
        assertEquals(30, operations.size)
        assertEquals(30, operations.map { it.clock }.toSet().size)
        assertEquals(30L, repository.state().clock)
    }

    @Test fun peerPaginationReturnsEveryOperationOnce() = runBlocking {
        val operations = (1L..205L).map { clock -> op("op-$clock", clock) }
        for (batch in operations.chunked(PAGE_SIZE)) repository.receivePeer(batch)
        val received = mutableListOf<Operation>()
        var cursor = 0L
        do {
            val page = repository.peerPage(cursor)
            received.addAll(page.operations)
            cursor = page.cursor
        } while (page.hasMore)
        assertEquals(operations, received)
    }

    @Test fun collisionRollsBackTheEntireBatch() = runBlocking {
        repository.receivePeer(listOf(op()))
        try {
            repository.receivePeer(listOf(op("valid", 11), op(value = "Conflicting content")))
            fail("Collision should fail")
        } catch (_: IllegalArgumentException) { /* expected */ }
        assertEquals(1, repository.peerPage(0).operations.size)
        assertEquals(10L, repository.state().clock)
    }

    @Test fun cloudAcknowledgmentsAndCursorCommitTogether() = runBlocking {
        repository.receivePeer(listOf(op()))
        val sent = repository.pending()
        repository.receiveCloud(SyncResponse(listOf("remote-1"), listOf(op()), 7, false), sent, 0)
        assertTrue(repository.pending().isEmpty())
        assertEquals(7L, repository.state().cursor)
        repository.changeServer("http://localhost:8081")
        assertEquals(0L, repository.state().cursor)
        assertEquals(1, repository.pending().size)
    }

    @Test fun failedCloudValidationDoesNotDiscardPendingOperations() = runBlocking {
        repository.receivePeer(listOf(op()))
        try {
            repository.receiveCloud(SyncResponse(emptyList(), emptyList(), 4, false), repository.pending(), 0)
            fail("Missing acknowledgment should fail")
        } catch (_: IllegalArgumentException) { /* expected */ }
        assertEquals(0L, repository.state().cursor)
        assertEquals(1, repository.pending().size)
    }

    @Test fun directPeerHTTPImportsWithoutCloudAndCanForwardLater() = runBlocking {
        val imported = AtomicInteger()
        val server = PeerServer(repository) { imported.incrementAndGet() }
        server.start()
        try {
            val transport = HttpTransport()
            transport.pushPeer("http://127.0.0.1:8787", listOf(op()))
            val page = transport.pullPeer("http://127.0.0.1:8787", 0)
            assertEquals(listOf(op()), page.operations)
            assertEquals(1, repository.pending().size)
            assertEquals(1, imported.get())
        } finally { server.stop() }
    }
}

