package dev.fieldsync.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.fieldsync.sync.HttpTransport
import dev.fieldsync.sync.SyncCoordinator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Optional live Go API test: pass -e cloudUrl http://10.0.2.2:8080 to instrumentation. */
@RunWith(AndroidJUnit4::class)
class CloudIntegrationTest {
    @Test fun twoAndroidReplicasConvergeThroughTheGoAPI() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("cloudUrl")
        assumeTrue("Provide cloudUrl to run the live cloud integration", url != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseA = Room.inMemoryDatabaseBuilder(context, FieldDatabase::class.java).build()
        val databaseB = Room.inMemoryDatabaseBuilder(context, FieldDatabase::class.java).build()
        try {
            val a = TaskRepository(databaseA)
            val b = TaskRepository(databaseB)
            val syncA = SyncCoordinator(a, HttpTransport())
            val syncB = SyncCoordinator(b, HttpTransport())
            syncA.changeServer(checkNotNull(url))
            syncB.changeServer(url)
            val taskId = UUID.randomUUID().toString()
            a.edit(taskId, "title", "Inspect pump")
            syncA.cloud()
            syncB.cloud()

            // Concurrent offline changes to different registers.
            a.edit(taskId, "title", "Inspect north pump")
            b.edit(taskId, "completed", "true")
            syncB.cloud()
            syncA.cloud()
            syncB.cloud()

            val taskA = a.tasks.first().single { it.id == taskId }
            val taskB = b.tasks.first().single { it.id == taskId }
            assertEquals(taskA, taskB)
            assertEquals("Inspect north pump", taskA.title.value)
            assertEquals("true", taskA.completed.value)
            assertTrue(a.pending().isEmpty() && b.pending().isEmpty())
        } finally {
            databaseA.close()
            databaseB.close()
        }
    }
}

