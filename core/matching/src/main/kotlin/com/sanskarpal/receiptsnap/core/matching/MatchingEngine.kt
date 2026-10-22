package com.sanskarpal.receiptsnap.core.matching

import com.sanskarpal.receiptsnap.core.model.MatchCandidate
import com.sanskarpal.receiptsnap.core.model.MatchResult
import com.sanskarpal.receiptsnap.core.model.MatchStatus
import com.sanskarpal.receiptsnap.core.model.ReceiptExtraction
import com.sanskarpal.receiptsnap.core.model.Transaction
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Scores a receipt against unmatched card charges (design doc §7).
 * Weights: amount 0.5, merchant 0.35, date 0.15. AUTO needs a high score and
 * a clear margin over the runner-up, so a tip that makes two charges look
 * alike falls back to asking the user.
 */
class MatchingEngine(
    private val config: Config = Config(),
    private val zone: ZoneId = ZoneId.of("America/Los_Angeles"),
) {
    data class Config(
        val autoThreshold: Double = 0.85,
        val autoMargin: Double = 0.10,
        val suggestThreshold: Double = 0.60,
        val daysBefore: Long = 1, // a charge can carry a date one day before the receipt (time zones)
        val daysAfter: Long = 5, // or post up to five days later (hotels, pre-auths)
        val maxTipRatio: Double = 0.30,
    )

    fun match(extraction: ReceiptExtraction, transactions: List<Transaction>): MatchResult {
        val receiptDate = extraction.date?.value
        val total = extraction.total?.value
        val merchant = extraction.merchant?.value?.let(MerchantNormalizer::normalize).orEmpty()

        val scored = transactions
            .filter { it.receiptId == null }
            .filter { receiptDate == null || inWindow(receiptDate, chargeDate(it)) }
            .map { tx ->
                val amount = amountScore(total, tx.amountCents)
                val name = if (merchant.isEmpty()) 0.0 else Similarity.merchant(merchant, MerchantNormalizer.normalize(tx.merchantDescriptor))
                val date = receiptDate?.let { dateScore(it, chargeDate(tx)) } ?: 0.0
                MatchCandidate(tx.id, round(0.5 * amount + 0.35 * name + 0.15 * date), round(amount), round(name), round(date))
            }
            .sortedByDescending { it.score }

        val best = scored.firstOrNull()
        val second = scored.getOrNull(1)?.score ?: 0.0
        val status = when {
            best == null -> MatchStatus.NONE
            best.score >= config.autoThreshold && best.score - second >= config.autoMargin -> MatchStatus.AUTO
            best.score >= config.suggestThreshold -> MatchStatus.SUGGESTED
            else -> MatchStatus.NONE
        }
        return MatchResult(status, best.takeIf { status != MatchStatus.NONE }, scored.take(3))
    }

    /**
     * Exact = 1. A charge up to 30% above the receipt total is probably a tip:
     * 0.9 falling to 0.75 at the 30% edge, so a tipped meal with a clear
     * merchant match can still auto-match while the margin rule guards decoys.
     */
    internal fun amountScore(receiptCents: Long?, chargeCents: Long): Double {
        if (receiptCents == null || receiptCents <= 0) return 0.0
        if (receiptCents == chargeCents) return 1.0
        val over = (chargeCents - receiptCents).toDouble() / receiptCents
        return if (over > 0 && over <= config.maxTipRatio) 0.9 - over / 2 else 0.0
    }

    internal fun dateScore(receipt: LocalDate, charge: LocalDate): Double {
        val diff = ChronoUnit.DAYS.between(receipt, charge)
        if (diff == 0L) return 1.0
        val edge = if (diff < 0) config.daysBefore else config.daysAfter
        return (1.0 - kotlin.math.abs(diff).toDouble() / (edge + 1)).coerceAtLeast(0.0)
    }

    private fun inWindow(receipt: LocalDate, charge: LocalDate): Boolean {
        val diff = ChronoUnit.DAYS.between(receipt, charge)
        return diff >= -config.daysBefore && diff <= config.daysAfter
    }

    private fun chargeDate(tx: Transaction): LocalDate = tx.authorizedAt.atZone(zone).toLocalDate()

    private fun round(x: Double) = Math.round(x * 1000) / 1000.0
}
