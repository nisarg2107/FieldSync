package dev.fieldsync.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.fieldsync.core.Operation
import dev.fieldsync.core.WORKSPACE
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks", primaryKeys = ["workspace", "id"])
data class TaskRow(val id: String, val snapshot: String, val workspace: String = WORKSPACE)

@Entity(tableName = "operation_log", indices = [
    Index(value = ["workspace", "id"], unique = true),
    Index(value = ["workspace", "actorId", "clock"], unique = true),
])
data class StoredOperation(
    @PrimaryKey(autoGenerate = true) val localSeq: Long = 0,
    @Embedded val operation: Operation,
    val cloudAcknowledged: Boolean = false,
    val workspace: String = WORKSPACE,
)

@Entity(tableName = "replica_state")
data class ReplicaState(
    @PrimaryKey val id: Int = 1,
    val actorId: String,
    val clock: Long = 0,
    val cursor: Long = 0,
    val serverUrl: String = "http://10.0.2.2:8080",
    @ColumnInfo(defaultValue = "'demo'") val workspace: String = WORKSPACE,
    @ColumnInfo(defaultValue = "'Guest'") val displayName: String = "Guest",
    @ColumnInfo(defaultValue = "''") val userId: String = "",
    @ColumnInfo(defaultValue = "''") val token: String = "",
    @ColumnInfo(defaultValue = "'editor'") val role: String = "editor",
    @ColumnInfo(defaultValue = "'[]'") val teamsJson: String = "[]",
    @ColumnInfo(defaultValue = "'[]'") val membersJson: String = "[]",
)

@Entity(tableName = "sync_runs")
data class SyncRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transport: String, val startedAt: Long, val durationMs: Long,
    val uploaded: Int, val downloaded: Int, val bytes: Long,
    val success: Boolean, val message: String, val workspace: String,
)

@Entity(tableName = "media", primaryKeys = ["workspace", "hash"])
data class MediaRow(val workspace: String, val hash: String, val size: Long, val cloudAcknowledged: Boolean = false, val receivedAt: Long = System.currentTimeMillis())

@Dao
interface FieldDao {
    @Query("SELECT * FROM tasks WHERE workspace=:workspace ORDER BY id") fun observeTasks(workspace: String): Flow<List<TaskRow>>
    @Query("SELECT * FROM replica_state WHERE id=1") fun observeState(): Flow<ReplicaState?>
    @Query("SELECT COUNT(*) FROM operation_log WHERE workspace=:workspace AND cloudAcknowledged=0") fun observePending(workspace: String): Flow<Int>
    @Query("SELECT * FROM operation_log WHERE workspace=:workspace AND taskId=:task ORDER BY localSeq DESC") fun history(workspace: String, task: String): Flow<List<StoredOperation>>
    @Query("SELECT * FROM sync_runs WHERE workspace=:workspace ORDER BY id DESC LIMIT 30") fun observeRuns(workspace: String): Flow<List<SyncRun>>
    @Query("SELECT * FROM media WHERE workspace=:workspace") fun observeMedia(workspace: String): Flow<List<MediaRow>>
    @Query("SELECT * FROM tasks WHERE workspace=:workspace AND id=:id") suspend fun task(workspace: String, id: String): TaskRow?
    @Upsert suspend fun putTask(task: TaskRow)
    @Query("SELECT * FROM replica_state WHERE id=1") suspend fun state(): ReplicaState?
    @Upsert suspend fun putState(state: ReplicaState)
    @Query("SELECT * FROM operation_log WHERE workspace=:workspace AND (id=:id OR (actorId=:actor AND clock=:clock))")
    suspend fun findIdentity(workspace: String, id: String, actor: String, clock: Long): List<StoredOperation>
    @Insert suspend fun append(operation: StoredOperation)
    @Insert suspend fun recordRun(run: SyncRun)
    @Query("SELECT * FROM operation_log WHERE workspace=:workspace AND cloudAcknowledged=0 ORDER BY localSeq LIMIT 100") suspend fun pending(workspace: String): List<StoredOperation>
    @Query("UPDATE operation_log SET cloudAcknowledged=1 WHERE workspace=:workspace AND id IN (:ids)") suspend fun acknowledge(workspace: String, ids: List<String>)
    @Query("UPDATE operation_log SET cloudAcknowledged=0") suspend fun resetAcknowledgments()
    @Query("UPDATE media SET cloudAcknowledged=0") suspend fun resetMediaAcknowledgments()
    @Query("SELECT * FROM operation_log WHERE workspace=:workspace AND localSeq>:cursor ORDER BY localSeq LIMIT 101") suspend fun page(workspace: String, cursor: Long): List<StoredOperation>
    @Upsert suspend fun putMedia(media: MediaRow)
    @Query("SELECT * FROM media WHERE workspace=:workspace AND cloudAcknowledged=0") suspend fun pendingMedia(workspace: String): List<MediaRow>
    @Query("UPDATE media SET cloudAcknowledged=1 WHERE workspace=:workspace AND hash=:hash") suspend fun acknowledgeMedia(workspace: String, hash: String)
}

@Database(entities = [TaskRow::class, StoredOperation::class, ReplicaState::class, MediaRow::class, SyncRun::class], version = 2, exportSchema = true)
abstract class FieldDatabase : RoomDatabase() {
    abstract fun dao(): FieldDao
    companion object {
        /** Add teams and history without discarding the original offline board. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks RENAME TO old_tasks")
                db.execSQL("CREATE TABLE tasks (id TEXT NOT NULL, snapshot TEXT NOT NULL, workspace TEXT NOT NULL, PRIMARY KEY(workspace,id))")
                db.execSQL("INSERT INTO tasks SELECT id,snapshot,'demo' FROM old_tasks")
                db.execSQL("DROP TABLE old_tasks")
                db.execSQL("ALTER TABLE operation_log RENAME TO old_operations")
                db.execSQL("CREATE TABLE operation_log (localSeq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, id TEXT NOT NULL, taskId TEXT NOT NULL, actorId TEXT NOT NULL, clock INTEGER NOT NULL, field TEXT NOT NULL, value TEXT NOT NULL, createdAt INTEGER NOT NULL, author TEXT NOT NULL, cloudAcknowledged INTEGER NOT NULL, workspace TEXT NOT NULL)")
                db.execSQL("INSERT INTO operation_log SELECT localSeq,id,taskId,actorId,clock,field,value,0,'',cloudAcknowledged,'demo' FROM old_operations")
                db.execSQL("DROP TABLE old_operations")
                db.execSQL("CREATE UNIQUE INDEX index_operation_log_workspace_id ON operation_log(workspace,id)")
                db.execSQL("CREATE UNIQUE INDEX index_operation_log_workspace_actorId_clock ON operation_log(workspace,actorId,clock)")
                for ((column, default) in listOf("workspace" to "'demo'", "displayName" to "'Guest'", "userId" to "''", "token" to "''", "role" to "'editor'", "teamsJson" to "'[]'", "membersJson" to "'[]'")) {
                    db.execSQL("ALTER TABLE replica_state ADD COLUMN $column TEXT NOT NULL DEFAULT $default")
                }
                db.execSQL("CREATE TABLE media (workspace TEXT NOT NULL, hash TEXT NOT NULL, size INTEGER NOT NULL, cloudAcknowledged INTEGER NOT NULL, receivedAt INTEGER NOT NULL, PRIMARY KEY(workspace,hash))")
                db.execSQL("CREATE TABLE sync_runs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, transport TEXT NOT NULL, startedAt INTEGER NOT NULL, durationMs INTEGER NOT NULL, uploaded INTEGER NOT NULL, downloaded INTEGER NOT NULL, bytes INTEGER NOT NULL, success INTEGER NOT NULL, message TEXT NOT NULL, workspace TEXT NOT NULL)")
            }
        }
    }
}
