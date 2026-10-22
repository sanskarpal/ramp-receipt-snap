package com.sanskarpal.receiptsnap.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.sanskarpal.receiptsnap.core.model.MatchStatus
import com.sanskarpal.receiptsnap.core.model.OutboxState
import com.sanskarpal.receiptsnap.core.model.ReceiptState
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** One row per transaction with whatever receipt state it has, for the list screen. */
data class TransactionRow(
    val id: String,
    val merchantDescriptor: String,
    val amountCents: Long,
    val authorizedAt: Instant,
    val cardLast4: String,
    val receiptId: String?,
    val receiptState: ReceiptState?,
    val matchStatus: MatchStatus?,
)

@Dao
interface TransactionDao {
    @Upsert
    suspend fun upsert(rows: List<TransactionEntity>)

    @Query(
        """SELECT t.id, t.merchantDescriptor, t.amountCents, t.authorizedAt, t.cardLast4, t.receiptId,
                  r.state AS receiptState, m.status AS matchStatus
           FROM transactions t
           LEFT JOIN receipts r ON r.id = t.receiptId
           LEFT JOIN matches m ON m.receiptId = t.receiptId
           ORDER BY t.authorizedAt DESC"""
    )
    fun observeRows(): Flow<List<TransactionRow>>

    @Query("SELECT * FROM transactions WHERE authorizedAt BETWEEN :from AND :to")
    suspend fun inRange(from: Instant, to: Instant): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<TransactionEntity>

    @Query("UPDATE transactions SET receiptId = :receiptId WHERE id = :id")
    suspend fun attachReceipt(id: String, receiptId: String?)

    @Query("UPDATE transactions SET receiptId = NULL WHERE receiptId = :receiptId")
    suspend fun detachReceipt(receiptId: String)

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int
}

@Dao
interface ReceiptDao {
    @Insert
    suspend fun insert(receipt: ReceiptEntity)

    @Query("SELECT * FROM receipts WHERE id = :id")
    suspend fun get(id: String): ReceiptEntity?

    @Query("SELECT * FROM receipts WHERE id = :id")
    fun observe(id: String): Flow<ReceiptEntity?>

    @Query("SELECT * FROM receipts ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<ReceiptEntity>>

    @Query("UPDATE receipts SET state = :state, failureReason = :reason WHERE id = :id")
    suspend fun setState(id: String, state: ReceiptState, reason: String? = null)

    @Query("UPDATE receipts SET state = :state, remoteId = :remoteId, localImagePath = NULL WHERE id = :id")
    suspend fun markSynced(id: String, remoteId: String, state: ReceiptState = ReceiptState.SYNCED)

    @Query("UPDATE receipts SET attemptCount = attemptCount + 1 WHERE id = :id")
    suspend fun bumpAttempts(id: String)

    @Query("SELECT * FROM receipts WHERE state IN (:states)")
    suspend fun inStates(states: List<ReceiptState>): List<ReceiptEntity>

    @Upsert
    suspend fun upsertExtraction(e: ReceiptExtractionEntity)

    @Query("SELECT * FROM receipt_extractions WHERE receiptId = :id")
    suspend fun extraction(id: String): ReceiptExtractionEntity?

    @Query("SELECT * FROM receipt_extractions WHERE receiptId = :id")
    fun observeExtraction(id: String): Flow<ReceiptExtractionEntity?>

    @Upsert
    suspend fun upsertMatch(m: MatchEntity)

    @Query("SELECT * FROM matches WHERE receiptId = :id")
    suspend fun match(id: String): MatchEntity?

    @Query("SELECT * FROM matches WHERE receiptId = :id")
    fun observeMatch(id: String): Flow<MatchEntity?>
}

@Dao
interface ApprovalDao {
    @Upsert
    suspend fun upsert(rows: List<ApprovalRequestEntity>)

    @Query("SELECT * FROM approvals ORDER BY status = 'PENDING' DESC, id")
    fun observeAll(): Flow<List<ApprovalRequestEntity>>

    @Query("SELECT * FROM approvals WHERE id = :id")
    suspend fun get(id: String): ApprovalRequestEntity?

    @Query("SELECT * FROM approvals")
    suspend fun all(): List<ApprovalRequestEntity>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(op: OutboxOperationEntity)

    @Query("SELECT payloadJson FROM outbox WHERE id = :id")
    suspend fun payload(id: String): String?

    @Query("SELECT * FROM outbox WHERE state = 'PENDING' ORDER BY createdAt, id LIMIT 1")
    suspend fun nextPending(): OutboxOperationEntity?

    @Query("UPDATE outbox SET state = :state, lastError = :error, attemptCount = attemptCount + :bump WHERE id = :id")
    suspend fun update(id: String, state: OutboxState, error: String?, bump: Int)

    @Query("UPDATE outbox SET state = 'PENDING' WHERE state = 'IN_FLIGHT'")
    suspend fun resetInFlight(): Int

    @Query("SELECT * FROM outbox ORDER BY createdAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<OutboxOperationEntity>>

    @Query("SELECT COUNT(*) FROM outbox WHERE state IN ('PENDING', 'IN_FLIGHT')")
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE outbox SET state = 'PENDING', lastError = NULL WHERE state = 'FAILED_PERMANENT'")
    suspend fun retryFailed()
}
