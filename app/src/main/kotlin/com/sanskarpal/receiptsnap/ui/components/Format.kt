package com.sanskarpal.receiptsnap.ui.components

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val usd = NumberFormat.getCurrencyInstance(Locale.US)
private val day = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val dayYear = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

fun money(cents: Long): String = usd.format(cents / 100.0)

fun shortDate(instant: Instant): String = day.format(instant.atZone(ZoneId.systemDefault()))

fun longDate(date: LocalDate): String = dayYear.format(date)

/** "39.15" or "$1,204.50" → cents; null when it isn't a money amount. */
fun parseMoneyInput(text: String): Long? {
    val clean = text.replace("$", "").replace(",", "").trim()
    if (!Regex("^\\d+(\\.\\d{1,2})?$").matches(clean)) return null
    val (whole, frac) = clean.split(".").let { it[0] to (it.getOrNull(1) ?: "") }
    return whole.toLong() * 100 + frac.padEnd(2, '0').toLong()
}
