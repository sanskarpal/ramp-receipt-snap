package com.sanskarpal.receiptsnap.core.matching

import com.google.common.truth.Truth.assertThat
import com.sanskarpal.receiptsnap.core.model.Field
import com.sanskarpal.receiptsnap.core.model.MatchStatus
import com.sanskarpal.receiptsnap.core.model.ReceiptExtraction
import com.sanskarpal.receiptsnap.core.model.Transaction
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MatchingEngineTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val engine = MatchingEngine(zone = zone)

    private fun tx(id: String, descriptor: String, cents: Long, date: LocalDate, receiptId: String? = null) =
        Transaction(id, descriptor, cents, "USD", date.atTime(19, 30).atZone(zone).toInstant(), "4242", receiptId)

    private fun receipt(merchant: String?, cents: Long?, date: LocalDate?) = ReceiptExtraction(
        merchant = merchant?.let { Field(it, 0.9) },
        total = cents?.let { Field(it, 0.9) },
        date = date?.let { Field(it, 0.9) },
        rawText = "",
    )

    private val day = LocalDate.of(2026, 10, 18)

    @Test
    fun `exact amount, messy descriptor and same day auto-match`() {
        val r = engine.match(
            receipt("JOE'S PIZZA", 3915, day),
            listOf(tx("t1", "SQ *JOES PIZZA #12 SAN FRANCISCO CA", 3915, day), tx("t2", "TST* BLUE BOTTLE", 650, day)),
        )
        assertThat(r.status).isEqualTo(MatchStatus.AUTO)
        assertThat(r.best?.transactionId).isEqualTo("t1")
    }

    @Test
    fun `a tip added after signing still matches`() {
        val r = engine.match(receipt("JOE'S PIZZA", 3915, day), listOf(tx("t1", "SQ *JOES PIZZA #12", 4615, day.plusDays(1))))
        assertThat(r.best?.transactionId).isEqualTo("t1")
        assertThat(r.status).isAnyOf(MatchStatus.AUTO, MatchStatus.SUGGESTED)
    }

    @Test
    fun `a close decoy blocks auto-matching and asks the user`() {
        // same merchant, two charges a few dollars apart: both look plausible with a tip
        val r = engine.match(
            receipt("JOE'S PIZZA", 3915, day),
            listOf(tx("t1", "SQ *JOES PIZZA #12", 4615, day), tx("t2", "SQ *JOES PIZZA #12", 4415, day)),
        )
        assertThat(r.status).isEqualTo(MatchStatus.SUGGESTED)
        assertThat(r.candidates.map { it.transactionId }).containsExactly("t2", "t1").inOrder()
    }

    @Test
    fun `charges outside the minus one to plus five day window are ignored`() {
        val r = engine.match(
            receipt("JOE'S PIZZA", 3915, day),
            listOf(tx("early", "SQ *JOES PIZZA", 3915, day.minusDays(2)), tx("late", "SQ *JOES PIZZA", 3915, day.plusDays(6))),
        )
        assertThat(r.status).isEqualTo(MatchStatus.NONE)
        assertThat(r.candidates).isEmpty()
    }

    @Test
    fun `a hotel charge posting four days later still matches`() {
        val r = engine.match(receipt("Harbor Inn", 41200, day), listOf(tx("h", "HARBOR INN SEATTLE WA", 41200, day.plusDays(4))))
        assertThat(r.best?.transactionId).isEqualTo("h")
    }

    @Test
    fun `transactions that already have a receipt are not candidates`() {
        val r = engine.match(receipt("JOE'S PIZZA", 3915, day), listOf(tx("t1", "SQ *JOES PIZZA", 3915, day, receiptId = "r0")))
        assertThat(r.status).isEqualTo(MatchStatus.NONE)
    }

    @Test
    fun `a charge far above the receipt total gets no amount credit`() {
        assertThat(engine.amountScore(1000, 1400)).isEqualTo(0.0)
        assertThat(engine.amountScore(1000, 900)).isEqualTo(0.0)
        assertThat(engine.amountScore(1000, 1200)).isWithin(1e-9).of(0.8)
    }

    @Test
    fun `date score falls off linearly to the window edge`() {
        assertThat(engine.dateScore(day, day)).isEqualTo(1.0)
        assertThat(engine.dateScore(day, day.plusDays(3))).isWithin(1e-9).of(0.5)
        assertThat(engine.dateScore(day, day.minusDays(1))).isWithin(1e-9).of(0.5)
    }

    @Test
    fun `normalizer strips processor prefixes, store numbers and trailing city`() {
        assertThat(MerchantNormalizer.normalize("SQ *JOES PIZZA #12 SAN FRANCISCO CA")).isEqualTo("JOES PIZZA")
        assertThat(MerchantNormalizer.normalize("TST* The Blue Bottle Coffee 0042")).isEqualTo("BLUE BOTTLE COFFEE")
        assertThat(MerchantNormalizer.normalize("Joe’s Pizza")).isEqualTo("JOES PIZZA")
    }
}
