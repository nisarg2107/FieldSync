package dev.fieldsync.data

import androidx.room.withTransaction
import dev.fieldsync.core.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.encodeToString
import java.util.UUID

/** All edits and imports go through this class, so the log and task projection stay atomic. */
@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepository(private val db: FieldDatabase) {
    private val dao = db.dao()
    val tasks = dao.observeState().flatMapLatest { dao.observeTasks(it?.workspace ?: WORKSPACE) }
        .map { rows -> rows.map { wireJson.decodeFromString<Task>(it.snapshot) } }
    val state = dao.observeState()
    val pendingCount = dao.observeState().flatMapLatest { dao.observePending(it?.workspace ?: WORKSPACE) }
    val runs = dao.observeState().flatMapLatest { dao.observeRuns(it?.workspace ?: WORKSPACE) }
    val media = dao.observeState().flatMapLatest { dao.observeMedia(it?.workspace ?: WORKSPACE) }
    fun history(task: String) = dao.observeState().flatMapLatest { dao.history(it?.workspace ?: WORKSPACE, task) }

    suspend fun state(): ReplicaState = db.withTransaction { ensureState() }

    suspend fun edit(taskId: String = UUID.randomUUID().toString(), field: String, value: String) {
        editFields(taskId, listOf(field to value))
    }

    suspend fun editFields(taskId: String, changes: List<Pair<String, String>>) {
        db.withTransaction {
            for ((field, value) in changes) {
                val state = ensureState()
                require(state.role != "viewer") { "This team is read-only for your account" }
                check(state.clock < MAX_CLOCK) { "Logical clock exhausted" }
                val op = Operation(UUID.randomUUID().toString(), taskId, state.actorId, state.clock + 1, field, value,
                    createdAt = System.currentTimeMillis(), author = state.displayName)
                applyOperations(listOf(op), fromCloud = false)
            }
        }
    }

    suspend fun setStatus(taskId: String, value: String) {
        db.withTransaction {
            val state = ensureState()
            val task = dao.task(state.workspace, taskId)?.let { wireJson.decodeFromString<Task>(it.snapshot) } ?: Task(taskId)
            edit(taskId, "status", wireJson.encodeToString(StatusChange(value, task.activeStatuses.keys.sorted())))
        }
    }

    suspend fun pending(): List<Operation> = dao.pending(state().workspace).map { it.operation }

    suspend fun peerPage(cursor: Long): PeerPage {
        require(cursor >= 0)
        val rows = dao.page(state().workspace, cursor)
        val page = rows.take(PAGE_SIZE)
        return PeerPage(page.map { it.operation }, page.lastOrNull()?.localSeq ?: cursor, rows.size > PAGE_SIZE)
    }

    suspend fun receivePeer(operations: List<Operation>, workspace: String? = null) {
        require(operations.size <= PAGE_SIZE) { "Peer batch too large" }
        db.withTransaction {
            require(workspace == null || workspace == ensureState().workspace) { "Select the same team on both devices" }
            applyOperations(operations, fromCloud = false)
        }
    }

    suspend fun receiveCloud(response: SyncResponse, sent: List<Operation>, previousCursor: Long) {
        require(response.cursor >= previousCursor) { "Server cursor moved backwards" }
        require(response.operations.size <= PAGE_SIZE)
        require(!response.hasMore || response.cursor > previousCursor) { "Server page made no progress" }
        require(response.accepted.toSet() == sent.map { it.id }.toSet()) { "Server did not acknowledge the submitted batch" }
        db.withTransaction {
            applyOperations(response.operations, fromCloud = true)
            dao.acknowledge(ensureState().workspace, response.accepted)
            dao.putState(ensureState().copy(cursor = response.cursor))
        }
    }

    /** Coordinator serializes this with cloud sync; a different server needs a full replay. */
    suspend fun changeServer(url: String) {
        db.withTransaction {
            val state = ensureState()
            if (state.serverUrl != url) {
                dao.resetAcknowledgments()
                dao.resetMediaAcknowledgments()
                dao.putState(state.copy(serverUrl = url, cursor = 0))
            }
        }
    }

    suspend fun saveSession(session: AuthResponse) = db.withTransaction {
        dao.putState(ensureState().copy(userId = session.user.id, displayName = session.user.name, token = session.token))
    }
    suspend fun saveTeamList(teams: List<Team>) = db.withTransaction {
        dao.putState(ensureState().copy(teamsJson = wireJson.encodeToString(teams)))
    }
    suspend fun useTeam(team: Team, members: List<Member> = emptyList()) = db.withTransaction {
        dao.putState(ensureState().copy(workspace = team.id, role = team.role, cursor = 0, membersJson = wireJson.encodeToString(members)))
    }
    suspend fun signOut() = db.withTransaction {
        dao.putState(ensureState().copy(userId = "", displayName = "Guest", token = "", workspace = WORKSPACE,
            role = "editor", cursor = 0, teamsJson = "[]", membersJson = "[]"))
    }
    suspend fun recordRun(run: SyncRun) = dao.recordRun(run)
    suspend fun putMedia(hash: String, size: Long) = dao.putMedia(MediaRow(state().workspace, hash, size))
    suspend fun pendingMedia() = dao.pendingMedia(state().workspace)
    suspend fun acknowledgeMedia(hash: String) = dao.acknowledgeMedia(state().workspace, hash)

    private suspend fun ensureState(): ReplicaState = dao.state() ?: ReplicaState(actorId = UUID.randomUUID().toString()).also { dao.putState(it) }

    // Caller must hold a Room transaction. Receiving advances logical time, even for old edits.
    private suspend fun applyOperations(operations: List<Operation>, fromCloud: Boolean) {
        var state = ensureState()
        for (op in operations) {
            op.validate()
            val existing = dao.findIdentity(state.workspace, op.id, op.actorId, op.clock)
            require(existing.all { it.operation == op }) { "Operation identity collision" }
            if (existing.isEmpty()) {
                val task = dao.task(state.workspace, op.taskId)?.let { wireJson.decodeFromString<Task>(it.snapshot) } ?: Task(op.taskId)
                dao.append(StoredOperation(operation = op, cloudAcknowledged = fromCloud, workspace = state.workspace))
                dao.putTask(TaskRow(task.id, wireJson.encodeToString(task.apply(op)), state.workspace))
            } else if (fromCloud) {
                dao.acknowledge(state.workspace, listOf(op.id))
            }
            state = state.copy(clock = maxOf(state.clock, op.clock))
        }
        dao.putState(state)
    }
}

