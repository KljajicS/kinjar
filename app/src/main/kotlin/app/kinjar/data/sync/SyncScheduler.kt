package app.kinjar.data.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun schedule() = SyncWorker.schedule(context)
}
