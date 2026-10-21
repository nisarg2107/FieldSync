package dev.fieldsync

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.fieldsync.data.FieldDatabase
import dev.fieldsync.data.TaskRepository
import dev.fieldsync.data.EvidenceStore
import dev.fieldsync.sync.HttpTransport
import dev.fieldsync.sync.SyncCoordinator
import dev.fieldsync.sync.SyncWorker

/** A small manual dependency container keeps construction visible to a new reader. */
class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(context, FieldDatabase::class.java, "fieldsync.db")
        .addMigrations(FieldDatabase.MIGRATION_1_2).build()
    val repository = TaskRepository(database)
    val evidence = EvidenceStore(context, repository)
    val transport = HttpTransport()
    val sync = SyncCoordinator(repository, transport, evidence)
}

class FieldSyncApp : Application() {
    val container by lazy { AppContainer(this) }
    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)
    }
}

