package com.sanskarpal.receiptsnap.core.matching

/**
 * Card descriptors and receipt headers name the same merchant very
 * differently ("SQ *JOES PIZZA #12 SAN FRANCISCO CA" vs "Joe's Pizza").
 * Normalize both sides to bare uppercase words before comparing.
 */
object MerchantNormalizer {
    private val PROCESSOR_PREFIX = Regex("^(SQ|TST|SP|PAYPAL|PP|IC|DD|UBER|LYFT|AMZN|GOOGLE|APL|CKO|PY|SQU)\\s*\\*\\s*")
    private val STORE_NUMBER = Regex("#\\s*\\d+|\\bSTORE\\s*\\d+\\b|\\b\\d{3,}\\b")
    private val TRAILING_STATE = Regex("\\s+[A-Z]{2}$")
    private val NOISE_WORDS = setOf("THE", "INC", "LLC", "CO", "CORP", "AND")

    fun normalize(raw: String, knownCities: Set<String> = CITIES): String {
        var s = raw.uppercase().replace('’', '\'').trim()
        s = PROCESSOR_PREFIX.replace(s, "")
        s = STORE_NUMBER.replace(s, " ") // before punctuation goes, so "#12" is still recognizable
        s = s.replace("'", "").replace(Regex("[^A-Z0-9 ]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim()
        s = TRAILING_STATE.replace(s, "")
        for (city in knownCities) if (s.endsWith(" $city")) s = s.removeSuffix(" $city")
        return s.split(" ").filter { it.isNotBlank() && it !in NOISE_WORDS }.joinToString(" ")
    }

    /** Cities that commonly trail card descriptors; enough for the demo data set. */
    val CITIES = setOf("SAN FRANCISCO", "OAKLAND", "NEW YORK", "BROOKLYN", "SEATTLE", "AUSTIN", "SAN JOSE", "FREMONT", "PALO ALTO")
}
