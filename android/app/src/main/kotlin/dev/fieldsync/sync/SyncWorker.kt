package dev.fieldsync.sync

import android.content.Context
import androidx.work.*
import dev.fieldsync.FieldSyncApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as FieldSyncApp).container.sync.cloud()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            // APPEND_OR_REPLACE preserves an edit queued while an older worker is finishing.
            WorkManager.getInstance(context).enqueueUniqueWork("cloud-edits", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun schedule(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(connected).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("cloud-poll", ExistingPeriodicWorkPolicy.KEEP, periodic)
            enqueue(context)
        }
    }
}

