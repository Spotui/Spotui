package com.music.spotui.resolver

import kotlin.math.max
import kotlin.math.min

/**
 * High-performance string similarity utilities for track and artist matching.
 */
object StringSimilarity {

    /**
     * Calculates the Levenshtein similarity ratio between two strings, normalized to [0.0, 1.0].
     * 1.0 = identical, 0.0 = completely different.
     */
    fun levenshteinRatio(s1: String, s2: String): Double {
        val str1 = s1.trim().lowercase()
        val str2 = s2.trim().lowercase()
        if (str1 == str2) return 1.0
        if (str1.isEmpty() || str2.isEmpty()) return 0.0

        val len1 = str1.length
        val len2 = str2.length
        val maxLen = max(len1, len2)
        if (maxLen == 0) return 1.0

        val distance = levenshteinDistance(str1, str2)
        return (1.0 - (distance.toDouble() / maxLen.toDouble())).coerceIn(0.0, 1.0)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + cost)
                prev = temp
            }
        }
        return dp[s2.length]
    }

    /**
     * Calculates token overlap / Jaccard containment ratio between two strings, normalized to [0.0, 1.0].
     */
    fun tokenOverlap(s1: String, s2: String): Double {
        val tokens1 = tokenize(s1)
        val tokens2 = tokenize(s2)

        if (tokens1.isEmpty() && tokens2.isEmpty()) return 1.0
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0

        val intersection = tokens1.intersect(tokens2).size
        val union = tokens1.union(tokens2).size
        if (union == 0) return 0.0

        val jaccard = intersection.toDouble() / union.toDouble()
        val containment = intersection.toDouble() / min(tokens1.size, tokens2.size).toDouble()
        return (jaccard * 0.6 + containment * 0.4).coerceIn(0.0, 1.0)
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-zA-Z0-9]+"))
            .filter { it.isNotBlank() && it.length > 1 }
            .toSet()
    }
}
