package dev.fieldsync.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.fieldsync.core.Operation
import dev.fieldsync.core.PAGE_SIZE
import dev.fieldsync.core.Task
import dev.fieldsync.sync.HttpTransport
import dev.fieldsync.sync.PeerServer
import dev.fieldsync.sync.SyncCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Exercises the complete peer coordinator with independent Room replicas and real HTTP. */
@RunWith(AndroidJUnit4::class)
class PeerIntegrationTest {
    private lateinit var databaseA: FieldDatabase
    private lateinit var databaseB: FieldDatabase
    private lateinit var a: TaskRepository
    private lateinit var b: TaskRepository
    private lateinit var syncA: SyncCoordinator
    private var receiver: PeerServer? = null
    private val peerUrl = "http://127.0.0.1:8787"

    @Before fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseA = Room.inMemoryDatabaseBuilder(context, FieldDatabase::class.java).build()
        databaseB = Room.inMemoryDatabaseBuilder(context, FieldDatabase::class.java).build()
        a = TaskRepository(databaseA)
        b = TaskRepository(databaseB)
        syncA = SyncCoordinator(a, HttpTransport())
    }

    @After fun close() {
        receiver?.stop()
        databaseA.close()
        databaseB.close()
    }

    private fun startReceiver() {
        receiver = PeerServer(b) {}.also { it.start() }
    }

    private suspend fun task(repository: TaskRepository): Task = repository.tasks.first().single()

    private suspend fun operationCount(repository: TaskRepository): Int {
        var cursor = 0L
        var count = 0
        do {
            val page = repository.peerPage(cursor)
            count += page.operations.size
            cursor = page.cursor
        } while (page.hasMore)
        return count
    }

    @Test fun concurrentOfflineChangesConvergeThroughPeerHTTP() = runBlocking {
        val initial = Operation("seed-1", "task", "seed", 1, "title", "Inspect pump")
        a.receivePeer(listOf(initial))
        b.receivePeer(listOf(initial))

        // Neither replica calls the cloud. Each makes changes during the partition.
        a.edit("task", "title", "Alice's title")
        b.edit("task", "title", "Bob's title")
        a.edit("task", "deleted", "true")
        b.edit("task", "completed", "true")
        val expectedTitle = if (a.state().actorId > b.state().actorId) "Alice's title" else "Bob's title"

        startReceiver()
        syncA.peer(peerUrl)
        assertEquals(task(a), task(b))
        assertEquals(expectedTitle, task(a).title.value)
        assertEquals("true", task(a).completed.value)
        assertFalse(task(a).isVisible)

        a.edit("task", "deleted", "false")
        syncA.peer(peerUrl)
        syncA.peer(peerUrl) // Full-history replay must be idempotent.
        assertEquals(task(a), task(b))
        assertTrue(task(a).isVisible)
        assertEquals(6, operationCount(a))
        assertEquals(6, operationCount(b))
        assertEquals(6, a.pending().size)
        assertEquals(6, b.pending().size)
    }

    @Test fun paginatedExchangeRecoversAfterPeerDisconnect() = runBlocking {
        for ((repository, actor) in listOf(a to "peer-a", b to "peer-b")) {
            val operations = (1L..123L).map { clock ->
                Operation("$actor-$clock", "task", actor, clock, "title", "$actor title $clock")
            }
            for (batch in operations.chunked(PAGE_SIZE)) repository.receivePeer(batch)
        }
        startReceiver()
        syncA.peer(peerUrl)
        assertEquals(task(a), task(b))
        assertEquals("peer-b title 123", task(a).title.value)
        assertEquals(246, operationCount(a))
        assertEquals(246, operationCount(b))

        receiver?.stop()
        receiver = null
        a.edit("task", "completed", "true")
        try {
            syncA.peer(peerUrl)
            fail("An unavailable peer must report a failed exchange")
        } catch (_: IOException) { /* The edit and outbox must survive. */ }
        assertEquals("true", task(a).completed.value)
        assertEquals(247, operationCount(a))
        assertEquals(246, operationCount(b))

        startReceiver()
        syncA.peer(peerUrl)
        syncA.peer(peerUrl)
        assertEquals(task(a), task(b))
        assertEquals(247, operationCount(a))
        assertEquals(247, operationCount(b))
    }
}
