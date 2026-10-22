package com.sanskarpal.receiptsnap.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sanskarpal.receiptsnap.core.model.ApprovalStatus
import com.sanskarpal.receiptsnap.core.model.Decision
import com.sanskarpal.receiptsnap.core.model.MatchStatus
import com.sanskarpal.receiptsnap.core.model.OutboxState
import com.sanskarpal.receiptsnap.core.model.OutboxType
import com.sanskarpal.receiptsnap.core.model.ReceiptState
import java.time.Instant
import java.time.LocalDate

// Tables follow the design doc §5. Money is integer cents everywhere.

@Entity(tableName = "transactions", indices = [Index("authorizedAt"), Index("receiptId")])
data class TransactionEntity(
    @PrimaryKey val id: String,
    val merchantDescriptor: String,
    val merchantNormalized: String,
    val amountCents: Long,
    val currency: String,
    val authorizedAt: Instant,
    val cardLast4: String,
    val receiptId: String?,
    val updatedAt: Instant,
)

@Entity(tableName = "receipts")
data class ReceiptEntity(
    @PrimaryKey val id: String, // client UUID, also the upload's Idempotency-Key
    val localImagePath: String?, // full-size image, deleted only after the server confirms with a 2xx
    val thumbnailPath: String?, // small copy kept for the UI after the original is gone
    val state: ReceiptState,
    val capturedAt: Instant,
    val remoteId: String? = null,
    val failureReason: String? = null,
    val attemptCount: Int = 0,
)

@Entity(tableName = "receipt_extractions")
data class ReceiptExtractionEntity(
    @PrimaryKey val receiptId: String,
    val merchant: String?,
    val merchantConfidence: Double,
    val totalCents: Long?,
    val totalConfidence: Double,
    val date: LocalDate?,
    val dateConfidence: Double,
    val rawText: String,
    val userEdited: Boolean,
)

@Entity(tableName = "matches")
data class MatchEntity(
    @PrimaryKey val receiptId: String,
    val transactionId: String?,
    val score: Double,
    val status: MatchStatus,
    val candidatesJson: String, // top 3 for the picker
)

@Entity(tableName = "approvals")
data class ApprovalRequestEntity(
    @PrimaryKey val id: String,
    val requesterName: String,
    val amountCents: Long,
    val merchant: String,
    val memo: String,
    val status: ApprovalStatus,
    val localDecision: Decision?,
    val localDecisionAt: Instant?,
    val serverVersion: Int,
    val conflictMessage: String? = null,
)

@Entity(tableName = "outbox", indices = [Index("state"), Index("entityId")])
data class OutboxOperationEntity(
    @PrimaryKey val id: String, // sent as the Idempotency-Key header
    val type: OutboxType,
    val entityId: String,
    val payloadJson: String,
    val createdAt: Instant,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val state: OutboxState = OutboxState.PENDING,
)
