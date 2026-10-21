package dev.fieldsync.data

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.fieldsync.core.*
import dev.fieldsync.sync.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CollaborationIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    @get:Rule val migration = MigrationTestHelper(instrumentation, FieldDatabase::class.java)
    private val databases = mutableListOf<FieldDatabase>()
    private val folders = mutableListOf<File>()

    private fun replica(): Pair<TaskRepository, EvidenceStore> {
        val db = Room.inMemoryDatabaseBuilder(context, FieldDatabase::class.java).build()
        databases += db
        val repository = TaskRepository(db)
        val folder = File(context.cacheDir, "evidence-${UUID.randomUUID()}").apply { mkdirs() }
        folders += folder
        return repository to EvidenceStore(context, repository, folder)
    }
    @After fun cleanup() { databases.forEach { it.close() }; folders.forEach { it.deleteRecursively() } }

    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, bytes); bitmap.recycle()
        return bytes.toByteArray()
    }

    @Test fun nearbyProtocolConvergesChecklistsPhotosAndExplicitStatusResolution() = runBlocking {
        val (a, photosA) = replica(); val (b, photosB) = replica()
        val initial = listOf(Operation("seed-title", "task", "seed", 1, "title", "Shared task"),
            Operation("seed-status", "task", "seed", 2, "status", wireJson.encodeToString(StatusChange("open"))))
        a.receivePeer(initial); b.receivePeer(initial)
        a.setStatus("task", "completed"); b.setStatus("task", "blocked")
        a.edit("task", "check:item:label", "Review draft")
        b.edit("task", "check:item:result", "pass")
        b.edit("task", "check:item:note", "Reviewed offline")
        val image = imageBytes(); val hash = sha256(image)
        photosA.receive(hash, image)
        a.edit("task", "photo:$hash", wireJson.encodeToString(Photo(hash, image.size.toLong(), "Shared evidence")))
        val handler = PeerHandler(b, photosB)
        val link = PeerLink { handler.handle(it) }
        PeerSession(a, photosA).exchange(link)
        assertEquals(a.tasks.first(), b.tasks.first())
        assertTrue(a.tasks.first().single().hasStatusConflict)
        assertEquals("Reviewed offline", a.tasks.first().single().visibleChecks.single().note.value)
        assertArrayEquals(image, photosB.file(hash).readBytes())

        b.setStatus("task", "in_progress")
        PeerSession(a, photosA).exchange(link)
        PeerSession(a, photosA).exchange(link)
        val task = a.tasks.first().single()
        assertFalse(task.hasStatusConflict); assertEquals("in_progress", task.status)
        assertEquals(a.tasks.first(), b.tasks.first())
        assertTrue(a.runs.first().all { it.success })
        // Simulate a lost reply after the receiver has committed the uploaded batch.
        a.edit("task", "notes", "Retry this change safely")
        var loseReply = true
        val faultyLink = PeerLink { message ->
            val response = handler.handle(message)
            if (message.kind == "push" && loseReply) {
                loseReply = false
                throw java.io.IOException("Injected lost acknowledgment")
            }
            response
        }
        try { PeerSession(a, photosA).exchange(faultyLink); fail("Expected interrupted exchange") }
        catch (_: java.io.IOException) { /* committed remotely; still safe to replay */ }
        PeerSession(a, photosA).exchange(faultyLink)
        assertEquals(a.tasks.first(), b.tasks.first())
        assertEquals("Retry this change safely", b.tasks.first().single().value("notes"))
        assertTrue(a.runs.first().any { !it.success })
        try {
            handler.handle(PeerMessage("photo_put", WORKSPACE, hash = hash, photo = android.util.Base64.encodeToString("bad".toByteArray(), android.util.Base64.NO_WRAP)))
            fail("Corrupt photo was accepted")
        } catch (_: IllegalArgumentException) { /* expected */ }
        assertArrayEquals(image, photosB.file(hash).readBytes())
    }

    @Test fun teamWorkspacesStayIsolatedAndViewersCannotEdit() = runBlocking {
        val (repository, _) = replica()
        repository.edit("same-id", "title", "Public task")
        repository.useTeam(Team("team-a", "Team A", "editor"))
        assertTrue(repository.tasks.first().isEmpty())
        repository.edit("same-id", "title", "Private task")
        repository.useTeam(Team(WORKSPACE, "Demo", "editor"))
        assertEquals("Public task", repository.tasks.first().single().title.value)
        repository.useTeam(Team("team-a", "Team A", "viewer"))
        try { repository.edit("same-id", "title", "Forbidden"); fail("Viewer edited a task") }
        catch (_: IllegalArgumentException) { /* expected */ }
        assertEquals("Private task", repository.tasks.first().single().title.value)
    }

    @Test fun versionOneMigrationPreservesTasksClockAndOperationIdentity() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        migration.createDatabase(name, 1).apply {
            val oldSnapshot = """{"id":"old-task","title":{"value":"Existing task","clock":1,"actorId":"old-actor"},"completed":{"value":"false","clock":0,"actorId":""},"deleted":{"value":"false","clock":0,"actorId":""}}"""
            execSQL("INSERT INTO tasks(id,snapshot) VALUES('old-task', ?)", arrayOf(oldSnapshot))
            execSQL("INSERT INTO operation_log(localSeq,id,taskId,actorId,clock,field,value,cloudAcknowledged) VALUES(1,'old-op','old-task','old-actor',1,'title','Existing task',0)")
            execSQL("INSERT INTO replica_state VALUES(1,'old-actor',1,0,'http://10.0.2.2:8080')")
            close()
        }
        val db = Room.databaseBuilder(context, FieldDatabase::class.java, name).addMigrations(FieldDatabase.MIGRATION_1_2).build()
        try {
            val repository = TaskRepository(db)
            assertEquals("Existing task", repository.tasks.first().single().title.value)
            assertEquals("old-actor", repository.state().actorId)
            assertEquals("old-op", repository.pending().single().id)
            repository.edit("old-task", "description", "Added after upgrade")
            assertEquals(2L, repository.state().clock)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun authenticatedTeamCloudSyncTransfersPhotoAndEnforcesViewerRole() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("cloudUrl")
        assumeTrue("Provide cloudUrl for live Go/PostgreSQL tests", url != null)
        val transport = HttpTransport()
        val suffix = UUID.randomUUID().toString()
        val owner = transport.signIn(url!!, Credentials("Owner", "owner-$suffix@example.test", "test-password-123"), true)
        val member = transport.signIn(url, Credentials("Member", "member-$suffix@example.test", "test-password-123"), true)
        val team = transport.createOrJoinTeam(url, owner.token, TeamInput(name = "Shared generic work"))
        val joined = transport.createOrJoinTeam(url, member.token, TeamInput(inviteCode = team.inviteCode))
        assertEquals("viewer", joined.role)
        val (a, photosA) = replica(); val (b, photosB) = replica()
        val syncA = SyncCoordinator(a, transport, photosA); val syncB = SyncCoordinator(b, transport, photosB)
        syncA.changeServer(url); syncB.changeServer(url)
        a.saveSession(owner); b.saveSession(member)
        syncA.useTeam(team); syncB.useTeam(joined)
        val image = imageBytes(); val hash = sha256(image)
        photosA.receive(hash, image)
        a.editFields("task", listOf("title" to "Team task", "project" to "Any project", "photo:$hash" to wireJson.encodeToString(Photo(hash, image.size.toLong()))))
        a.edit("task", "assignee", member.user.id)
        syncA.cloud(); syncB.cloud()
        assertEquals(a.tasks.first(), b.tasks.first())
        assertArrayEquals(image, photosB.file(hash).readBytes())
        try { b.edit("task", "description", "Should fail"); fail("Viewer edited locally") }
        catch (_: IllegalArgumentException) { /* expected */ }
        transport.setRole(url, owner.token, team.id, RoleInput(member.user.id, "editor"))
        syncB.useTeam(joined.copy(role = "editor"))
        b.edit("task", "description", "Edited offline by a teammate")
        syncB.cloud(); syncA.cloud()
        assertEquals(a.tasks.first(), b.tasks.first())
        assertTrue(a.pending().isEmpty() && b.pending().isEmpty())
    }
}
