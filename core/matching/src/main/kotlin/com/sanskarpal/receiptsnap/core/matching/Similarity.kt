package com.sanskarpal.receiptsnap.core.matching

import kotlin.math.max
import kotlin.math.min

object Similarity {

    /** Jaro-Winkler similarity, 0..1. Good at short strings that share a prefix. */
    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return if (a.isEmpty()) 0.0 else 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val window = max(0, max(a.length, b.length) / 2 - 1)
        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            for (j in max(0, i - window)..min(b.length - 1, i + window)) {
                if (!bMatched[j] && a[i] == b[j]) {
                    aMatched[i] = true; bMatched[j] = true; matches++; break
                }
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in a.indices) if (aMatched[i]) {
            while (!bMatched[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
        val prefix = a.zip(b).takeWhile { (x, y) -> x == y }.take(4).size
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    /**
     * Token-set similarity: shared words over the smaller word set, so
     * "JOES PIZZA" vs "JOES PIZZA VALENCIA" still scores 1.0.
     */
    fun tokenSet(a: String, b: String): Double {
        val ta = a.split(" ").filter { it.isNotBlank() }.toSet()
        val tb = b.split(" ").filter { it.isNotBlank() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / min(ta.size, tb.size)
    }

    fun merchant(a: String, b: String): Double = max(jaroWinkler(a, b), tokenSet(a, b))
}
