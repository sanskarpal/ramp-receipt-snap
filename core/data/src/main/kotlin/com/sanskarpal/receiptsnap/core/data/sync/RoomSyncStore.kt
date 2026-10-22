package com.sanskarpal.receiptsnap.core.data.sync

import androidx.room.withTransaction
import com.sanskarpal.receiptsnap.core.data.db.AppDatabase
import com.sanskarpal.receiptsnap.core.data.network.ApprovalDto
import com.sanskarpal.receiptsnap.core.data.network.MatchOutcome
import com.sanskarpal.receiptsnap.core.data.network.ReceiptUploadMetadata
import com.sanskarpal.receiptsnap.core.data.network.ReceiptUploadResult
import com.sanskarpal.receiptsnap.core.data.repo.ImageStore
import com.sanskarpal.receiptsnap.core.model.ApprovalStatus
import com.sanskarpal.receiptsnap.core.model.MatchStatus
import com.sanskarpal.receiptsnap.core.model.OutboxState
import com.sanskarpal.receiptsnap.core.model.ReceiptState
import javax.inject.Inject

class RoomSyncStore @Inject constructor(
    private val db: AppDatabase,
    private val images: ImageStore,
) : SyncStore {
    private val outbox = db.outbox()
    private val receipts = db.receipts()

    override suspend fun nextPending(): PendingOp? =
        outbox.nextPending()?.let { PendingOp(it.id, OutboxPayload.decode(it.payloadJson)) }

    override suspend fun markInFlight(opId: String) {
        outbox.update(opId, OutboxState.IN_FLIGHT, null, 0)
        payloadReceipt(opId)?.let { receipts.setState(it, ReceiptState.UPLOADING) }
    }

    override suspend fun markDone(opId: String) = outbox.update(opId, OutboxState.DONE, null, 0)

    override suspend fun markRetry(opId: String, error: String) {
        outbox.update(opId, OutboxState.PENDING, error, 1)
        payloadReceipt(opId)?.let {
            receipts.setState(it, ReceiptState.UPLOAD_PENDING)
            receipts.bumpAttempts(it)
        }
    }

    override suspend fun markFailed(opId: String, error: String) {
        outbox.update(opId, OutboxState.FAILED_PERMANENT, error, 1)
        payloadReceipt(opId)?.let { receipts.setState(it, ReceiptState.FAILED_PERMANENT, error) }
    }

    override suspend fun isUploaded(receiptId: String): Boolean = receipts.get(receiptId)?.remoteId != null

    override suspend fun uploadRequest(receiptId: String): UploadRequest? {
        val r = receipts.get(receiptId) ?: return null
        val path = r.localImagePath ?: return null
        val e = receipts.extraction(receiptId)
        val m = receipts.match(receiptId)
        val proposed = m?.transactionId?.takeIf { m.status == MatchStatus.AUTO || m.status == MatchStatus.CONFIRMED }
        return UploadRequest(ReceiptUploadMetadata(receiptId, e?.merchant, e?.totalCents, e?.date?.toString(), proposed), path)
    }

    override suspend fun onReceiptUploaded(receiptId: String, result: ReceiptUploadResult) {
        val imagePath = receipts.get(receiptId)?.localImagePath
        db.withTransaction {
            val rejected = result.match.status == "REJECTED"
            receipts.markSynced(receiptId, result.remoteId, if (rejected) ReceiptState.NEEDS_REVIEW else ReceiptState.SYNCED)
            if (rejected) {
                db.transactions().detachReceipt(receiptId)
                receipts.match(receiptId)?.let { receipts.upsertMatch(it.copy(status = MatchStatus.SUGGESTED)) }
                receipts.setState(receiptId, ReceiptState.NEEDS_REVIEW, result.match.reason)
            }
        }
        images.delete(imagePath) // only now, after the server's 2xx is stored
    }

    override suspend fun onMatchConfirmed(receiptId: String, outcome: MatchOutcome) {
        if (outcome.status == "REJECTED") {
            db.transactions().detachReceipt(receiptId)
            receipts.setState(receiptId, ReceiptState.NEEDS_REVIEW, outcome.reason)
        } else {
            receipts.setState(receiptId, ReceiptState.SYNCED)
        }
    }

    override suspend fun onApprovalSynced(approval: ApprovalDto) {
        val local = db.approvals().get(approval.id) ?: return
        db.approvals().upsert(listOf(local.copy(status = ApprovalStatus.valueOf(approval.status), serverVersion = approval.version, localDecision = null, conflictMessage = null)))
    }

    override suspend fun onApprovalConflict(approvalId: String, decidedBy: String?, status: String?) {
        val local = db.approvals().get(approvalId) ?: return
        val serverStatus = status?.let(ApprovalStatus::valueOf) ?: local.status
        val verb = if (serverStatus == ApprovalStatus.DENIED) "denied" else "approved"
        db.approvals().upsert(listOf(local.copy(status = serverStatus, localDecision = null, conflictMessage = "Already $verb by ${decidedBy ?: "someone else"}")))
    }

    /** Receipt uploads move the receipt's own state too, so the list can show "Syncing". */
    private suspend fun payloadReceipt(opId: String): String? =
        outbox.payload(opId)?.let { (OutboxPayload.decode(it) as? OutboxPayload.UploadReceipt)?.receiptId }
}
