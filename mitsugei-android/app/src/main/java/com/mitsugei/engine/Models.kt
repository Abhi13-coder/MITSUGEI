package com.mitsugei.engine

/** Port of mitsugei/core/models.py. */

data class Candidate(
    val url: String,
    val urlkey: String,
    val crawlTimestamp: String,
    val docId: Long,
    val dump: String?,
    val title: String?,
)

data class Document(
    val url: String,
    val title: String,
    val text: String,
    val language: String?,
)

class ScoreBreakdown {
    val signals: LinkedHashMap<String, Double> = LinkedHashMap()

    val total: Double get() = signals.values.sum()

    fun add(name: String, value: Double) {
        signals[name] = (signals[name] ?: 0.0) + value
    }

    fun asText(): String {
        val lines = signals.entries.map { (k, v) -> "  ${k.padEnd(18)}${"%+.3f".format(v)}" }.toMutableList()
        lines.add("  " + "-".repeat(28))
        lines.add("  ${"final_score".padEnd(18)}${"%+.3f".format(total)}")
        return lines.joinToString("\n")
    }
}

data class SearchResult(
    val url: String,
    val title: String,
    val snippet: String,
    val score: Double,
    val signals: Map<String, Double>,
)

data class Features(val values: LinkedHashMap<String, Double> = LinkedHashMap())
