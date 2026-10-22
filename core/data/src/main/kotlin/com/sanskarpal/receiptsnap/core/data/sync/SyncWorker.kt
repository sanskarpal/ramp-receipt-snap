package com.sanskarpal.receiptsnap.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sanskarpal.receiptsnap.core.data.repo.ApprovalRepository
import com.sanskarpal.receiptsnap.core.data.repo.TransactionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Pushes the outbox, then pulls fresh transactions and approvals (design doc §8). */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
    private val transactions: TransactionRepository,
    private val approvals: ApprovalRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = engine.drainWithRetries()
        // pulls are best effort: a failed pull shouldn't make WorkManager resend pushes that already succeeded
        runCatching { transactions.refresh() }
        runCatching { approvals.refresh() }
        return if (outcome == SyncEngine.Outcome.DRAINED) Result.success() else Result.retry()
    }
}

@Singleton
class SyncScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Called after every outbox insert and on app start. KEEP: one sync at a time; it drains new rows too. */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.KEEP, request)
    }

    fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("$UNIQUE-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val UNIQUE = "SyncWork"
    }
}
