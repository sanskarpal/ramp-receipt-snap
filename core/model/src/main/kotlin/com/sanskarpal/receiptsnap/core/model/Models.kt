package com.sanskarpal.receiptsnap.core.model

import java.time.Instant
import java.time.LocalDate

/** A card charge, as synced from the server. Money is always integer cents. */
data class Transaction(
    val id: String,
    val merchantDescriptor: String,
    val amountCents: Long,
    val currency: String,
    val authorizedAt: Instant,
    val cardLast4: String,
    val receiptId: String? = null,
)

/** One recognized line of receipt text. Geometry is normalized to the page: 0 = top, 1 = bottom. */
data class TextLine(
    val text: String,
    val top: Float,
    val height: Float,
)

/** An extracted value plus how sure the parser is about it, 0..1. */
data class Field<T>(val value: T, val confidence: Double) {
    val isLowConfidence: Boolean get() = confidence < LOW_CONFIDENCE

    companion object {
        const val LOW_CONFIDENCE = 0.6
    }
}

data class ReceiptExtraction(
    val merchant: Field<String>?,
    val total: Field<Long>?,
    val date: Field<LocalDate>?,
    val rawText: String,
    val userEdited: Boolean = false,
)

enum class ReceiptState {
    CAPTURED, EXTRACTING, EXTRACTED,
    MATCHED, NEEDS_REVIEW, UNMATCHED,
    UPLOAD_PENDING, UPLOADING, SYNCED, FAILED_PERMANENT,
}

enum class MatchStatus { AUTO, SUGGESTED, CONFIRMED, REJECTED, NONE }

data class MatchCandidate(
    val transactionId: String,
    val score: Double,
    val amountScore: Double,
    val merchantScore: Double,
    val dateScore: Double,
)

data class MatchResult(
    val status: MatchStatus,
    val best: MatchCandidate?,
    /** Top three, best first, for the picker. */
    val candidates: List<MatchCandidate>,
)

enum class ApprovalStatus { PENDING, APPROVED, DENIED }

enum class Decision { APPROVE, DENY }

data class ApprovalRequest(
    val id: String,
    val requesterName: String,
    val amountCents: Long,
    val merchant: String,
    val memo: String,
    val status: ApprovalStatus,
    val localDecision: Decision?,
    val serverVersion: Int,
    val conflictMessage: String? = null,
)

enum class OutboxType { UPLOAD_RECEIPT, CONFIRM_MATCH, APPROVAL_DECISION }

enum class OutboxState { PENDING, IN_FLIGHT, DONE, FAILED_PERMANENT }
