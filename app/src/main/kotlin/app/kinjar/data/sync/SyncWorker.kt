package app.kinjar.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWorkerDependencies { fun syncCoordinator(): SyncCoordinator }

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val sync = EntryPoints.get(applicationContext, SyncWorkerDependencies::class.java).syncCoordinator()
        return try {
            if (inputData.getBoolean(CLEANUP, false)) {
                // Each batch takes the coordinator lock separately, so sync and family actions interleave with cleanup.
                while (sync.continueCleanup()) Unit
            } else sync.synchronize()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: SyncCapacityReached) {
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val CLEANUP = "cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(online()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("family-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** A running continuation already loops until cleanup finishes, so a pending or running one is kept. */
        fun continueCleanup(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(online()).setInputData(workDataOf(CLEANUP to true)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("family-cleanup", ExistingWorkPolicy.KEEP, request)
        }

        private fun online() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    }
}
