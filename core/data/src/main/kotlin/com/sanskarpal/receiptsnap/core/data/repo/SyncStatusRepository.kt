package com.sanskarpal.receiptsnap.core.data.repo

import com.sanskarpal.receiptsnap.core.data.db.AppDatabase
import com.sanskarpal.receiptsnap.core.data.db.OutboxOperationEntity
import com.sanskarpal.receiptsnap.core.data.sync.SyncScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Read side for the sync debug screen (design doc §11: the demo centerpiece). */
@Singleton
class SyncStatusRepository @Inject constructor(
    private val db: AppDatabase,
    private val scheduler: SyncScheduler,
) {
    fun observeOutbox(): Flow<List<OutboxOperationEntity>> = db.outbox().observeRecent()
    fun observePendingCount(): Flow<Int> = db.outbox().observePendingCount()
    fun syncNow() = scheduler.syncNow()

    suspend fun retryFailed() {
        db.outbox().retryFailed()
        scheduler.syncNow()
    }
}
