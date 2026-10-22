package com.sanskarpal.receiptsnap.core.extraction

import com.google.common.truth.Truth.assertThat
import com.sanskarpal.receiptsnap.core.model.TextLine
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTest {

    private val parser = ReceiptParser()
    private val today = LocalDate.of(2026, 10, 20)

    /** Lines spaced evenly down the page; the first line can be drawn larger, like a logo. */
    private fun receipt(vararg text: String, bigFirst: Boolean = true): List<TextLine> {
        val step = 1f / text.size
        return text.mapIndexed { i, t -> TextLine(t, i * step, if (i == 0 && bigFirst) step * 0.9f else step * 0.5f) }
    }

    @Test
    fun `restaurant receipt with tip line`() {
        val r = parser.parse(
            receipt(
                "JOE'S PIZZA", "1842 Valencia St", "San Francisco, CA 94110", "(415) 555-0142",
                "10/18/2026 7:42 PM", "2 Large Margherita 36.00", "SUBTOTAL 36.00", "TAX 3.15", "TOTAL 39.15",
                "TIP 7.00", "THANK YOU",
            ),
            today,
        )
        assertThat(r.merchant?.value).isEqualTo("JOE'S PIZZA")
        assertThat(r.total?.value).isEqualTo(3915)
        assertThat(r.date?.value).isEqualTo(LocalDate.of(2026, 10, 18))
        assertThat(r.total!!.isLowConfidence).isFalse()
    }

    @Test
    fun `prefers the lowest total keyword, such as balance due after a tip`() {
        val r = parser.parse(receipt("CAFE LUNA", "Subtotal 18.40", "Total 19.92", "Tip 3.00", "Balance Due 22.92"), today)
        assertThat(r.total?.value).isEqualTo(2292)
        assertThat(r.total?.confidence).isEqualTo(0.95)
    }

    @Test
    fun `reads an amount split onto the same row as its label`() {
        val lines = listOf(
            TextLine("HARBOR HARDWARE", 0.02f, 0.06f),
            TextLine("Oct 3, 2026", 0.12f, 0.03f),
            TextLine("TOTAL", 0.80f, 0.04f),
            TextLine("$1,204.50", 0.81f, 0.04f),
        )
        val r = parser.parse(lines, today)
        assertThat(r.total?.value).isEqualTo(120450)
        assertThat(r.date?.value).isEqualTo(LocalDate.of(2026, 10, 3))
    }

    @Test
    fun `falls back to the largest amount near the bottom with low confidence`() {
        val r = parser.parse(receipt("CORNER MART", "Milk 4.29", "Bread 3.49", "Eggs 5.99", "13.77", "VISA ****4242"), today)
        assertThat(r.total?.value).isEqualTo(1377)
        assertThat(r.total!!.isLowConfidence).isTrue()
    }

    @Test
    fun `ignores subtotal, tax, change and cash lines when looking for the total`() {
        val r = parser.parse(receipt("GROCER", "SUBTOTAL 50.00", "TAX 4.00", "CASH 60.00", "CHANGE 6.00"), today)
        assertThat(r.total?.value).isNotEqualTo(6000)
    }

    @Test
    fun `parses every supported date format`() {
        listOf(
            "2026-10-01" to LocalDate.of(2026, 10, 1),
            "10/01/26" to LocalDate.of(2026, 10, 1),
            "10/1/2026" to LocalDate.of(2026, 10, 1),
            "Sep 24, 2026" to LocalDate.of(2026, 9, 24),
            "24 Sep 2026" to LocalDate.of(2026, 9, 24),
        ).forEach { (text, expected) -> assertThat(parser.parseDates(text)).containsExactly(expected) }
    }

    @Test
    fun `rejects future and stale dates`() {
        assertThat(parser.parse(receipt("SHOP", "11/02/2026", "TOTAL 5.00"), today).date).isNull()
        assertThat(parser.parse(receipt("SHOP", "01/02/2026", "TOTAL 5.00"), today).date).isNull()
    }

    @Test
    fun `skips address, phone, url and greeting lines when picking the merchant`() {
        val r = parser.parse(
            receipt("Welcome!", "www.bluebottle.example", "Blue Bottle Coffee", "66 Mint St", "TOTAL 6.50", bigFirst = false),
            today,
        )
        assertThat(r.merchant?.value).isEqualTo("Blue Bottle Coffee")
    }

    @Test
    fun `parses money with thousands separators and ignores percentages`() {
        assertThat(ReceiptParser.amounts("Total $1,234.56")).containsExactly(123456L)
        assertThat(ReceiptParser.amounts("Tax 8.25% 3.30")).containsExactly(330L)
    }
}
