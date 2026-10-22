package com.sanskarpal.receiptsnap.core.data.repo

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.sanskarpal.receiptsnap.core.data.db.AppDatabase
import com.sanskarpal.receiptsnap.core.data.db.MatchEntity
import com.sanskarpal.receiptsnap.core.data.db.OutboxOperationEntity
import com.sanskarpal.receiptsnap.core.data.db.ReceiptEntity
import com.sanskarpal.receiptsnap.core.data.db.ReceiptExtractionEntity
import com.sanskarpal.receiptsnap.core.data.db.TransactionEntity
import com.sanskarpal.receiptsnap.core.data.sync.OutboxPayload
import com.sanskarpal.receiptsnap.core.data.sync.SyncScheduler
import com.sanskarpal.receiptsnap.core.extraction.ReceiptParser
import com.sanskarpal.receiptsnap.core.matching.MatchingEngine
import com.sanskarpal.receiptsnap.core.model.Field
import com.sanskarpal.receiptsnap.core.model.MatchCandidate
import com.sanskarpal.receiptsnap.core.model.MatchStatus
import com.sanskarpal.receiptsnap.core.model.ReceiptExtraction
import com.sanskarpal.receiptsnap.core.model.ReceiptState
import com.sanskarpal.receiptsnap.core.model.TextLine
import com.sanskarpal.receiptsnap.core.model.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Supplied by :app (ML Kit). Kept as an interface so this module never touches the recognizer. */
fun interface TextRecognizer {
    suspend fun recognize(imagePath: String): List<TextLine>
}

data class ReviewState(
    val receipt: ReceiptEntity,
    val extraction: ReceiptExtractionEntity?,
    val match: MatchEntity?,
    val candidates: List<Pair<MatchCandidate, TransactionEntity>>,
)

/**
 * The capture pipeline from design doc §4.3 / §6: durable image → row →
 * on-device text recognition → parse → match → outbox. Every step writes to
 * Room first, so a crash at any point resumes from the last stable state.
 */
@Singleton
class ReceiptRepository @Inject constructor(
    private val db: AppDatabase,
    private val images: ImageStore,
    private val recognizer: TextRecognizer,
    private val parser: ReceiptParser,
    private val engine: MatchingEngine,
    private val scheduler: SyncScheduler,
    @Named("appScope") private val scope: CoroutineScope,
) {
    private val receipts = db.receipts()
    private val json = Json { ignoreUnknownKeys = true }
    private val zone = ZoneId.systemDefault()

    suspend fun capture(photo: File): String = capture { id -> images.save(id, photo) }

    suspend fun capture(bytes: ByteArray): String = capture { id -> images.save(id, bytes) }

    private suspend fun capture(save: (String) -> ImageStore.Saved): String {
        val id = UUID.randomUUID().toString()
        val saved = save(id) // image on disk before the row exists...
        receipts.insert(ReceiptEntity(id, saved.imagePath, saved.thumbnailPath, ReceiptState.CAPTURED, Instant.now())) // ...row before ML Kit runs
        scope.launch { process(id) }
        return id
    }

    suspend fun process(id: String) {
        val receipt = receipts.get(id) ?: return
        val path = receipt.localImagePath ?: return
        receipts.setState(id, ReceiptState.EXTRACTING)
        val extraction = runCatching { parser.parse(recognizer.recognize(path), LocalDate.now(zone)) }
            .getOrElse { ReceiptExtraction(null, null, null, rawText = "", userEdited = false) }
        receipts.upsertExtraction(extraction.toEntity(id))
        receipts.setState(id, ReceiptState.EXTRACTED)
        runMatch(id)
        enqueue(OutboxPayload.UploadReceipt(id), opId = id) // the receipt UUID doubles as the upload's idempotency key
    }

    /** Manual corrections from the review screen; the match is recomputed from the edited values. */
    suspend fun updateFields(id: String, merchant: String?, totalCents: Long?, date: LocalDate?) {
        val current = receipts.extraction(id)
        receipts.upsertExtraction(
            ReceiptExtractionEntity(
                receiptId = id, merchant = merchant, merchantConfidence = 1.0, totalCents = totalCents, totalConfidence = 1.0,
                date = date, dateConfidence = 1.0, rawText = current?.rawText.orEmpty(), userEdited = true,
            ),
        )
        runMatch(id)
    }

    /** The user picked a charge (or "no match"). */
    suspend fun confirmMatch(id: String, transactionId: String?) {
        db.withTransaction {
            val m = receipts.match(id)
            db.transactions().detachReceipt(id)
            if (transactionId != null) db.transactions().attachReceipt(transactionId, id)
            receipts.upsertMatch(
                MatchEntity(id, transactionId, m?.score ?: 0.0, if (transactionId != null) MatchStatus.CONFIRMED else MatchStatus.REJECTED, m?.candidatesJson ?: "[]"),
            )
            receipts.setState(id, if (transactionId != null) ReceiptState.MATCHED else ReceiptState.UNMATCHED)
        }
        enqueue(OutboxPayload.ConfirmMatch(id, transactionId))
    }

    fun observeReview(id: String): Flow<ReviewState?> =
        combine(receipts.observe(id), receipts.observeExtraction(id), receipts.observeMatch(id)) { r, e, m ->
            r?.let {
                val cands = m?.let { decodeCandidates(it.candidatesJson) }.orEmpty()
                val txs = db.transactions().byIds(cands.map { c -> c.transactionId }).associateBy { t -> t.id }
                ReviewState(r, e, m, cands.mapNotNull { c -> txs[c.transactionId]?.let { t -> c to t } })
            }
        }

    fun observeAll(): Flow<List<ReceiptEntity>> = receipts.observeAll()

    /** App start: put anything a crash interrupted back into a stable state and resume it (design doc §5). */
    suspend fun recover() {
        db.outbox().resetInFlight()
        receipts.inStates(listOf(ReceiptState.UPLOADING)).forEach { receipts.setState(it.id, ReceiptState.UPLOAD_PENDING) }
        receipts.inStates(listOf(ReceiptState.CAPTURED, ReceiptState.EXTRACTING)).forEach { scope.launch { process(it.id) } }
        scheduler.syncNow()
    }

    private suspend fun runMatch(id: String) {
        val e = receipts.extraction(id) ?: return
        val date = e.date
        val from = (date ?: LocalDate.now(zone).minusDays(30)).minusDays(2).atStartOfDay(zone).toInstant()
        val to = (date ?: LocalDate.now(zone)).plusDays(6).atStartOfDay(zone).toInstant()
        val pool = db.transactions().inRange(from, to).filter { it.receiptId == null || it.receiptId == id }
            .map { Transaction(it.id, it.merchantDescriptor, it.amountCents, it.currency, it.authorizedAt, it.cardLast4, null) }
        val result = engine.match(e.toModel(), pool)
        db.withTransaction {
            db.transactions().detachReceipt(id)
            if (result.status == MatchStatus.AUTO) db.transactions().attachReceipt(result.best!!.transactionId, id)
            receipts.upsertMatch(
                MatchEntity(id, result.best?.transactionId, result.best?.score ?: 0.0, result.status, json.encodeToString(result.candidates.map(::toDto))),
            )
            receipts.setState(
                id,
                when (result.status) {
                    MatchStatus.AUTO -> ReceiptState.MATCHED
                    MatchStatus.SUGGESTED -> ReceiptState.NEEDS_REVIEW
                    else -> ReceiptState.UNMATCHED
                },
            )
        }
    }

    private suspend fun enqueue(payload: OutboxPayload, opId: String = UUID.randomUUID().toString()) {
        val entityId = when (payload) {
            is OutboxPayload.UploadReceipt -> payload.receiptId
            is OutboxPayload.ConfirmMatch -> payload.receiptId
            is OutboxPayload.ApprovalDecision -> payload.approvalId
        }
        try {
            db.outbox().insert(OutboxOperationEntity(opId, OutboxPayload.typeOf(payload), entityId, OutboxPayload.encode(payload), Instant.now()))
        } catch (_: SQLiteConstraintException) {
            // already queued (e.g. extraction re-ran after a crash): one upload per receipt
        }
        scheduler.syncNow()
    }

    @Serializable
    private data class CandidateDto(val t: String, val s: Double, val a: Double, val m: Double, val d: Double)

    private fun toDto(c: MatchCandidate) = CandidateDto(c.transactionId, c.score, c.amountScore, c.merchantScore, c.dateScore)

    private fun decodeCandidates(s: String): List<MatchCandidate> =
        runCatching { json.decodeFromString<List<CandidateDto>>(s) }.getOrDefault(emptyList()).map { MatchCandidate(it.t, it.s, it.a, it.m, it.d) }

    private fun ReceiptExtraction.toEntity(id: String) = ReceiptExtractionEntity(
        receiptId = id, merchant = merchant?.value, merchantConfidence = merchant?.confidence ?: 0.0,
        totalCents = total?.value, totalConfidence = total?.confidence ?: 0.0,
        date = date?.value, dateConfidence = date?.confidence ?: 0.0, rawText = rawText, userEdited = userEdited,
    )

    private fun ReceiptExtractionEntity.toModel() = ReceiptExtraction(
        merchant = merchant?.let { Field(it, merchantConfidence) },
        total = totalCents?.let { Field(it, totalConfidence) },
        date = date?.let { Field(it, dateConfidence) },
        rawText = rawText, userEdited = userEdited,
    )
}
