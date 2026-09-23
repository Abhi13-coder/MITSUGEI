package com.mitsugei.engine

/** Port of mitsugei/core/query.py. */

data class ParsedQuery(
    val raw: String,
    val normalized: String,
    val tokens: List<String>,
    val phrases: List<String>,
    val importantTerms: List<String>,
    val expandedTerms: List<String> = emptyList(),
)

object QueryParser {
    private val STOPWORDS = setOf(
        "the", "a", "an", "of", "and", "or", "to", "in", "on", "for", "is",
        "are", "was", "were", "with", "at", "by", "from", "about", "as",
    )

    private fun extractPhrases(rawQuery: String): List<String> {
        val phrases = ArrayList<String>()
        val buf = StringBuilder()
        var inQuote = false
        for (ch in rawQuery) {
            if (ch == '"') {
                if (inQuote && buf.isNotEmpty()) {
                    phrases.add(buf.toString().trim().split(Regex("\\s+")).joinToString(" ").lowercase())
                }
                buf.clear()
                inQuote = !inQuote
            } else if (inQuote) {
                buf.append(ch)
            }
        }
        return phrases
    }

    fun parse(rawQuery: String, markov: MarkovModel? = null): ParsedQuery {
        val normalized = Normalization.normalizeQuery(rawQuery)
        val phrases = extractPhrases(rawQuery)
        val tokens = Normalization.tokenize(normalized)
        val important = tokens.filter { it !in STOPWORDS }.ifEmpty { tokens }

        val expanded = ArrayList<String>()
        if (markov != null) {
            for (t in important) expanded.addAll(markov.likelyNext(t, topK = 2))
        }

        return ParsedQuery(rawQuery, normalized, tokens, phrases, important, expanded)
    }
}
