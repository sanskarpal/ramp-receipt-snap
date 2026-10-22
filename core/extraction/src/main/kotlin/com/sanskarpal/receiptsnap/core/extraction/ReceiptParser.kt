package com.sanskarpal.receiptsnap.core.extraction

import com.sanskarpal.receiptsnap.core.model.Field
import com.sanskarpal.receiptsnap.core.model.ReceiptExtraction
import com.sanskarpal.receiptsnap.core.model.TextLine
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Turns recognized receipt text into merchant, total and date, each with a
 * heuristic confidence. Pure Kotlin so it runs in fast JVM tests; the ML Kit
 * wrapper in :app only converts recognizer output into [TextLine]s.
 */
class ReceiptParser(private val maxAgeDays: Long = 90) {

    fun parse(input: List<TextLine>, today: LocalDate): ReceiptExtraction {
        val lines = input.filter { it.text.isNotBlank() }.sortedBy { it.top }
        return ReceiptExtraction(
            merchant = merchant(lines),
            total = total(lines),
            date = date(lines, today),
            rawText = lines.joinToString("\n") { it.text },
        )
    }

    // ---- total ----

    internal fun total(lines: List<TextLine>): Field<Long>? {
        val keywordLines = lines.filter { TOTAL_KEYWORD.containsMatchIn(it.text) && !TOTAL_EXCLUDE.containsMatchIn(it.text) }
        // the keyword line lowest on the page wins: "TOTAL" after "SUBTOTAL", "BALANCE DUE" after tips
        for (line in keywordLines.sortedByDescending { it.top }) {
            val strong = STRONG_TOTAL.containsMatchIn(line.text)
            amounts(line.text).lastOrNull()?.let { return Field(it, if (strong) 0.95 else 0.9) }
            // recognizers often split "TOTAL ........ 42.18" into two boxes on the same row
            val sameRow = lines.filter { it !== line && sameRow(it, line) }.flatMap { amounts(it.text) }
            sameRow.lastOrNull()?.let { return Field(it, if (strong) 0.85 else 0.8) }
        }
        val bottom = lines.filter { it.top >= 0.6f && !TOTAL_EXCLUDE.containsMatchIn(it.text) }.flatMap { amounts(it.text) }
        return bottom.maxOrNull()?.let { Field(it, 0.45) }
    }

    private fun sameRow(a: TextLine, b: TextLine): Boolean =
        kotlin.math.abs(a.top - b.top) <= maxOf(a.height, b.height) * 0.7f

    // ---- date ----

    internal fun date(lines: List<TextLine>, today: LocalDate): Field<LocalDate>? {
        data class Found(val date: LocalDate, val line: TextLine)
        val found = lines.flatMap { line -> parseDates(line.text).map { Found(it, line) } }
            .filter { !it.date.isAfter(today) && ChronoUnit.DAYS.between(it.date, today) <= maxAgeDays }
        if (found.isEmpty()) return null
        val best = found.maxBy { score(it.line) }
        var confidence = 0.7 + score(best.line)
        if (found.map { it.date }.distinct().size > 1) confidence -= 0.2
        return Field(best.date, confidence.coerceIn(0.0, 0.95))
    }

    private fun score(line: TextLine): Double =
        (if (TIME.containsMatchIn(line.text)) 0.15 else 0.0) + (if (line.top < 0.4f) 0.1 else 0.0)

    internal fun parseDates(text: String): List<LocalDate> = DATE_PATTERNS.flatMap { (regex, formats) ->
        regex.findAll(text).mapNotNull { m -> formats.firstNotNullOfOrNull { f -> tryParse(m.value, f) } }.toList()
    }

    private fun tryParse(s: String, f: DateTimeFormatter): LocalDate? = try {
        LocalDate.parse(s.replace(Regex("\\s+"), " ").trim(), f)
    } catch (_: DateTimeParseException) {
        null
    }

    // ---- merchant ----

    internal fun merchant(lines: List<TextLine>): Field<String>? {
        val head = lines.take(6).filter { isMerchantCandidate(it.text) }.take(3)
        if (head.isEmpty()) return null
        val medianHeight = lines.map { it.height }.sorted().let { it[it.size / 2] }
        fun bonus(l: TextLine, index: Int): Double {
            val letters = l.text.filter { it.isLetter() }
            val caps = letters.isNotEmpty() && letters.all { it.isUpperCase() }
            return (if (caps) 0.2 else 0.0) +
                (if (l.height > medianHeight * 1.2f) 0.2 else 0.0) +
                (if (index == 0) 0.1 else 0.0)
        }
        val (best, b) = head.mapIndexed { i, l -> l to bonus(l, i) }.maxBy { it.second }
        return Field(best.text.trim().replace(Regex("\\s+"), " "), (0.5 + b).coerceAtMost(0.95))
    }

    private fun isMerchantCandidate(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2 || t.count { it.isLetter() } < 2) return false
        return listOf(ADDRESS, STATE_ZIP, PHONE, URL, GREETING, AMOUNT).none { it.containsMatchIn(t) } &&
            parseDates(t).isEmpty()
    }

    companion object {
        private val TOTAL_KEYWORD = Regex("\\b(GRAND\\s+TOTAL|TOTAL|AMOUNT\\s+DUE|BALANCE(\\s+DUE)?)\\b", RegexOption.IGNORE_CASE)
        private val STRONG_TOTAL = Regex("\\b(GRAND\\s+TOTAL|AMOUNT\\s+DUE|BALANCE\\s+DUE)\\b", RegexOption.IGNORE_CASE)
        private val TOTAL_EXCLUDE = Regex("\\b(SUB\\s*-?\\s*TOTAL|TAX|TIP|GRATUITY|CHANGE|CASH|SAVINGS|DISCOUNT)\\b", RegexOption.IGNORE_CASE)
        private val AMOUNT = Regex("(?<![\\d.])\\$?\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)\\.(\\d{2})(?![\\d%])")
        private val TIME = Regex("\\b\\d{1,2}:\\d{2}(\\s?[AP]M)?\\b", RegexOption.IGNORE_CASE)
        private val ADDRESS = Regex("^\\d+\\s+\\w+.*\\b(ST|AVE|RD|BLVD|DR|LN|WAY|HWY|STREET|AVENUE|ROAD|SUITE|STE)\\b\\.?", RegexOption.IGNORE_CASE)
        private val STATE_ZIP = Regex("\\b[A-Z]{2}\\s+\\d{5}(-\\d{4})?\\b")
        private val PHONE = Regex("\\(?\\d{3}\\)?[-. ]\\d{3}[-. ]\\d{4}")
        private val URL = Regex("(www\\.|https?://|\\.com\\b)", RegexOption.IGNORE_CASE)
        private val GREETING = Regex("\\b(welcome|thank\\s*you|thanks|receipt|order\\s*#|server|table|guests?)\\b", RegexOption.IGNORE_CASE)

        private fun fmt(p: String) = DateTimeFormatter.ofPattern(p, Locale.US)
        private val DATE_PATTERNS = listOf(
            Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b") to listOf(fmt("yyyy-MM-dd")),
            Regex("\\b\\d{1,2}/\\d{1,2}/\\d{4}\\b") to listOf(fmt("M/d/yyyy")),
            Regex("\\b\\d{1,2}/\\d{1,2}/\\d{2}\\b(?!/)") to listOf(fmt("M/d/yy")),
            Regex("\\b\\d{1,2}-\\d{1,2}-\\d{4}\\b") to listOf(fmt("M-d-yyyy")),
            Regex("\\b[A-Z][a-z]{2}\\s+\\d{1,2},\\s*\\d{4}\\b") to listOf(fmt("MMM d, yyyy"), fmt("MMM d,yyyy")),
            Regex("\\b\\d{1,2}\\s+[A-Z][a-z]{2}\\s+\\d{4}\\b") to listOf(fmt("d MMM yyyy")),
        )

        /** Every money amount on a line, in cents, left to right. */
        fun amounts(text: String): List<Long> = AMOUNT.findAll(text).map { m ->
            m.groupValues[1].replace(",", "").toLong() * 100 + m.groupValues[2].toLong()
        }.toList()
    }
}
