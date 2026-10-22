package com.sanskarpal.receiptsnap.core.data.sync

import com.sanskarpal.receiptsnap.core.data.network.ApprovalDto
import com.sanskarpal.receiptsnap.core.data.network.MatchOutcome
import com.sanskarpal.receiptsnap.core.data.network.ReceiptUploadMetadata
import com.sanskarpal.receiptsnap.core.data.network.ReceiptUploadResult
import com.sanskarpal.receiptsnap.core.model.Decision
import com.sanskarpal.receiptsnap.core.model.OutboxState
import kotlin.random.Random

/** Thrown at random points to simulate the OS killing the app mid-sync. */
class ProcessDeath : RuntimeException("process killed")

/**
 * A server with real idempotency semantics plus injected faults:
 * 500s before commit, responses dropped after commit, and timeouts on either side.
 */
class FakeServer(
    private val random: Random,
    var failureRate: Double = 0.0,
    var dropRate: Double = 0.0,
    var timeoutRate: Double = 0.0,
    var offline: Boolean = false,
) : SyncRemote {
    val receipts = linkedMapOf<String, Int>() // receipt id → times a row was created (must stay 1)
    private val replies = mutableMapOf<String, Any>()
    val approvals = mutableMapOf<String, ApprovalDto>()
    var requests = 0

    private fun <T : Any> handle(key: String, commit: () -> T): T {
        requests++
        if (offline) throw SyncError.Retryable("offline")
        if (random.nextDouble() < failureRate) throw SyncError.Retryable("HTTP 500")
        val timeoutBefore = random.nextDouble() < timeoutRate / 2
        if (timeoutBefore) throw SyncError.Retryable("timeout before server saw it")
        @Suppress("UNCHECKED_CAST")
        val reply = replies.getOrPut(key) { commit() } as T
        if (random.nextDouble() < dropRate || random.nextDouble() < timeoutRate / 2) {
            throw SyncError.Retryable("connection dropped after commit")
        }
        return reply
    }

    override suspend fun uploadReceipt(idempotencyKey: String, request: UploadRequest): ReceiptUploadResult =
        handle(idempotencyKey) {
            val id = request.metadata.id
            receipts[id] = (receipts[id] ?: 0) + 1
            ReceiptUploadResult(id, "remote-$id", MatchOutcome(request.metadata.proposedTransactionId, "ACCEPTED"))
        }

    override suspend fun confirmMatch(idempotencyKey: String, receiptId: String, transactionId: String?) =
        handle(idempotencyKey) { MatchOutcome(transactionId, "ACCEPTED") }

    override suspend fun decide(idempotencyKey: String, approvalId: String, decision: Decision, clientVersion: Int): ApprovalDto =
        handle(idempotencyKey) {
            val current = approvals.getValue(approvalId)
            if (current.status != "PENDING" || current.version != clientVersion) throw SyncError.Conflict(current.decidedBy, current.status)
            current.copy(status = if (decision == Decision.APPROVE) "APPROVED" else "DENIED", version = current.version + 1, decidedBy = "You")
                .also { approvals[approvalId] = it }
        }
}

data class FakeOp(val id: String, val payload: OutboxPayload, var state: OutboxState = OutboxState.PENDING, var attempts: Int = 0, var error: String? = null)

class FakeStore(private val server: FakeServer, private val random: Random, var killRate: Double = 0.0) : SyncStore {
    val ops = mutableListOf<FakeOp>()
    val images = mutableSetOf<String>() // receipt ids whose local image still exists
    val synced = mutableSetOf<String>()
    val deletedBeforeServerHadIt = mutableListOf<String>()
    val approvalConflicts = mutableMapOf<String, String?>()
    val approvalsSynced = mutableMapOf<String, String>()

    private fun maybeDie() {
        if (random.nextDouble() < killRate) throw ProcessDeath()
    }

    fun enqueue(id: String, payload: OutboxPayload) {
        ops += FakeOp(id, payload)
    }

    fun addReceipt(receiptId: String) {
        images += receiptId
        enqueue("op-$receiptId", OutboxPayload.UploadReceipt(receiptId))
    }

    /** What app start does after a crash: anything left in flight goes back to pending. */
    fun recoverInFlight() = ops.filter { it.state == OutboxState.IN_FLIGHT }.forEach { it.state = OutboxState.PENDING }

    override suspend fun nextPending(): PendingOp? = ops.firstOrNull { it.state == OutboxState.PENDING }?.let { PendingOp(it.id, it.payload) }
    override suspend fun markInFlight(opId: String) { op(opId).state = OutboxState.IN_FLIGHT; maybeDie() }
    override suspend fun markDone(opId: String) { maybeDie(); op(opId).state = OutboxState.DONE }
    override suspend fun markRetry(opId: String, error: String) { op(opId).apply { state = OutboxState.PENDING; attempts++; this.error = error } }
    override suspend fun markFailed(opId: String, error: String) { op(opId).apply { state = OutboxState.FAILED_PERMANENT; this.error = error } }

    override suspend fun isUploaded(receiptId: String) = receiptId in synced

    override suspend fun uploadRequest(receiptId: String): UploadRequest? =
        if (receiptId in images) UploadRequest(ReceiptUploadMetadata(receiptId, "m", 100, null, null), "/img/$receiptId") else null

    override suspend fun onReceiptUploaded(receiptId: String, result: ReceiptUploadResult) {
        maybeDie()
        synced += receiptId // state persisted first...
        if (server.receipts[receiptId] == null) deletedBeforeServerHadIt += receiptId
        images -= receiptId // ...then the image goes
    }

    override suspend fun onMatchConfirmed(receiptId: String, outcome: MatchOutcome) = Unit
    override suspend fun onApprovalSynced(approval: ApprovalDto) { approvalsSynced[approval.id] = approval.status }
    override suspend fun onApprovalConflict(approvalId: String, decidedBy: String?, status: String?) { approvalConflicts[approvalId] = decidedBy }

    private fun op(id: String) = ops.first { it.id == id }
}
