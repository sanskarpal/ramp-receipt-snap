package com.sanskarpal.receiptsnap

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.sanskarpal.receiptsnap.core.data.repo.ReceiptRepository
import com.sanskarpal.receiptsnap.core.data.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltAndroidApp
class ReceiptSnapApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var receipts: ReceiptRepository
    @Inject lateinit var scheduler: SyncScheduler
    @Inject @Named("appScope") lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // crash recovery: anything interrupted mid-extraction or mid-upload resumes (design doc §5)
        scope.launch { receipts.recover() }
        scheduler.schedulePeriodic()
    }
}
