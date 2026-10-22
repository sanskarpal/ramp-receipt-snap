package com.sanskarpal.receiptsnap.core.data.repo

import androidx.room.withTransaction
import com.sanskarpal.receiptsnap.core.data.db.AppDatabase
import com.sanskarpal.receiptsnap.core.data.db.ApprovalRequestEntity
import com.sanskarpal.receiptsnap.core.data.db.OutboxOperationEntity
import com.sanskarpal.receiptsnap.core.data.network.ReceiptSnapApi
import com.sanskarpal.receiptsnap.core.data.sync.OutboxPayload
import com.sanskarpal.receiptsnap.core.data.sync.SyncScheduler
import com.sanskarpal.receiptsnap.core.model.ApprovalStatus
import com.sanskarpal.receiptsnap.core.model.Decision
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApprovalRepository @Inject constructor(
    private val db: AppDatabase,
    private val api: ReceiptSnapApi,
    private val scheduler: SyncScheduler,
) {
    fun observe(): Flow<List<ApprovalRequestEntity>> = db.approvals().observeAll()

    suspend fun refresh() {
        val local = db.approvals().all().associateBy { it.id }
        val rows = api.approvals().approvals.map { dto ->
            val mine = local[dto.id]
            ApprovalRequestEntity(
                id = dto.id, requesterName = dto.requesterName, amountCents = dto.amountCents,
                merchant = dto.merchant, memo = dto.memo,
                // an unsent local decision stays on screen until the outbox resolves it
                status = if (mine?.localDecision != null) mine.status else ApprovalStatus.valueOf(dto.status),
                localDecision = mine?.localDecision,
                localDecisionAt = mine?.localDecisionAt,
                serverVersion = if (mine?.localDecision != null) mine.serverVersion else dto.version,
                conflictMessage = mine?.conflictMessage,
            )
        }
        db.approvals().upsert(rows)
    }

    /** Optimistic: the row updates now and shows "Pending sync" until the server confirms or rejects. */
    suspend fun decide(id: String, decision: Decision) {
        db.withTransaction {
            val row = db.approvals().get(id) ?: return@withTransaction
            db.approvals().upsert(listOf(row.copy(localDecision = decision, localDecisionAt = Instant.now(), conflictMessage = null)))
            val payload = OutboxPayload.ApprovalDecision(id, decision, row.serverVersion)
            db.outbox().insert(
                OutboxOperationEntity(UUID.randomUUID().toString(), OutboxPayload.typeOf(payload), id, OutboxPayload.encode(payload), Instant.now()),
            )
        }
        scheduler.syncNow()
    }
}
