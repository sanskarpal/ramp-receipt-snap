package com.sanskarpal.receiptsnap.core.data.sync

import com.sanskarpal.receiptsnap.core.data.network.ApprovalDto
import com.sanskarpal.receiptsnap.core.data.network.MatchOutcome
import com.sanskarpal.receiptsnap.core.data.network.ReceiptUploadMetadata
import com.sanskarpal.receiptsnap.core.data.network.ReceiptUploadResult
import com.sanskarpal.receiptsnap.core.model.Decision
import com.sanskarpal.receiptsnap.core.model.OutboxType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** How a request failed, which decides what the outbox does next (design doc §8.1). */
sealed class SyncError(message: String) : Exception(message) {
    /** Network error, timeout or 5xx: try the same operation again later, same Idempotency-Key. */
    class Retryable(message: String) : SyncError(message)

    /** 409: someone else changed the entity first. */
    class Conflict(val decidedBy: String?, val status: String?) : SyncError("Conflict")

    /** Other 4xx: retrying won't help; surface it on the sync screen. */
    class Permanent(val code: Int, message: String) : SyncError(message)
}

@Serializable
sealed interface OutboxPayload {
    @Serializable data class UploadReceipt(val receiptId: String) : OutboxPayload
    @Serializable data class ConfirmMatch(val receiptId: String, val transactionId: String?) : OutboxPayload
    @Serializable data class ApprovalDecision(val approvalId: String, val decision: Decision, val clientVersion: Int) : OutboxPayload

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun encode(p: OutboxPayload): String = json.encodeToString(p)
        fun decode(s: String): OutboxPayload = json.decodeFromString(s)
        fun typeOf(p: OutboxPayload): OutboxType = when (p) {
            is UploadReceipt -> OutboxType.UPLOAD_RECEIPT
            is ConfirmMatch -> OutboxType.CONFIRM_MATCH
            is ApprovalDecision -> OutboxType.APPROVAL_DECISION
        }
    }
}

data class PendingOp(val id: String, val payload: OutboxPayload)

data class UploadRequest(val metadata: ReceiptUploadMetadata, val imagePath: String)

/** Local persistence the engine needs. Room backs it in the app, an in-memory fake in tests. */
interface SyncStore {
    suspend fun nextPending(): PendingOp?
    suspend fun markInFlight(opId: String)
    suspend fun markDone(opId: String)
    suspend fun markRetry(opId: String, error: String)
    suspend fun markFailed(opId: String, error: String)

    /** True once the server's reply was stored; a crash before markDone can leave the op pending after that. */
    suspend fun isUploaded(receiptId: String): Boolean

    /** Null if the receipt or its image is gone, which is a permanent failure for this op. */
    suspend fun uploadRequest(receiptId: String): UploadRequest?

    /** Must persist the synced state before deleting the local image. */
    suspend fun onReceiptUploaded(receiptId: String, result: ReceiptUploadResult)
    suspend fun onMatchConfirmed(receiptId: String, outcome: MatchOutcome)
    suspend fun onApprovalSynced(approval: ApprovalDto)
    suspend fun onApprovalConflict(approvalId: String, decidedBy: String?, status: String?)
}

interface SyncRemote {
    suspend fun uploadReceipt(idempotencyKey: String, request: UploadRequest): ReceiptUploadResult
    suspend fun confirmMatch(idempotencyKey: String, receiptId: String, transactionId: String?): MatchOutcome
    suspend fun decide(idempotencyKey: String, approvalId: String, decision: Decision, clientVersion: Int): ApprovalDto
}

/**
 * Drains the outbox in FIFO order. Each operation keeps its id as the
 * Idempotency-Key across retries, so a request that reached the server but
 * lost its response can be resent safely. The first retryable failure stops
 * the drain, keeping order intact; WorkManager's backoff decides when to resume.
 */
class SyncEngine(private val store: SyncStore, private val remote: SyncRemote) {

    enum class Outcome { DRAINED, RETRY_LATER }

    /** How a drain ended and how many operations it finished (done or permanently failed) on the way. */
    data class Result(val outcome: Outcome, val processed: Int)

    suspend fun drain(maxOps: Int = 500): Result {
        var processed = 0
        repeat(maxOps) {
            val op = store.nextPending() ?: return Result(Outcome.DRAINED, processed)
            store.markInFlight(op.id)
            try {
                execute(op)
                store.markDone(op.id)
            } catch (e: SyncError.Retryable) {
                store.markRetry(op.id, e.message ?: "retryable error")
                return Result(Outcome.RETRY_LATER, processed)
            } catch (e: SyncError.Permanent) {
                store.markFailed(op.id, "${e.code}: ${e.message}")
            }
            processed++
        }
        return Result(Outcome.RETRY_LATER, processed)
    }

    /**
     * Drains with a few quick in-process retries (jittered 1–8 s). The streak
     * resets whenever a drain makes progress, so a flaky network that still lets
     * most requests through keeps the queue moving instead of handing every
     * hiccup to WorkManager, whose backoff doubles per run regardless of progress.
     */
    suspend fun drainWithRetries(maxFailedStreak: Int = 4, pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }): Outcome {
        var streak = 0
        while (true) {
            val r = drain()
            if (r.outcome == Outcome.DRAINED) return Outcome.DRAINED
            streak = if (r.processed > 0) 1 else streak + 1
            if (streak >= maxFailedStreak) return Outcome.RETRY_LATER
            pause(minOf(8_000L, 1_000L shl (streak - 1)) + kotlin.random.Random.nextLong(0, 500))
        }
    }

    private suspend fun execute(op: PendingOp) {
        when (val p = op.payload) {
            is OutboxPayload.UploadReceipt -> {
                if (store.isUploaded(p.receiptId)) return
                val req = store.uploadRequest(p.receiptId) ?: throw SyncError.Permanent(0, "Receipt image missing locally")
                store.onReceiptUploaded(p.receiptId, remote.uploadReceipt(op.id, req))
            }
            is OutboxPayload.ConfirmMatch ->
                store.onMatchConfirmed(p.receiptId, remote.confirmMatch(op.id, p.receiptId, p.transactionId))
            is OutboxPayload.ApprovalDecision -> try {
                store.onApprovalSynced(remote.decide(op.id, p.approvalId, p.decision, p.clientVersion))
            } catch (c: SyncError.Conflict) {
                // someone else decided first: the server wins, the local decision is rolled back
                store.onApprovalConflict(p.approvalId, c.decidedBy, c.status)
            }
        }
    }
}
