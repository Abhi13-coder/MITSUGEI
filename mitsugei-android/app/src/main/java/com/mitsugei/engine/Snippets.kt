package com.mitsugei.engine

/** Port of mitsugei/content/snippets.py. */
object Snippets {
    private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")

    fun bestSnippet(text: String, queryTerms: List<String>, windowChars: Int = 220): String {
        if (text.isEmpty()) return ""
        var sentences = SENTENCE_SPLIT.split(text)
        if (sentences.isEmpty()) sentences = listOf(text)

        val termSet = queryTerms.toHashSet()
        var bestIdx = 0
        var bestHits = -1
        for ((i, sentence) in sentences.withIndex()) {
            val tokens = Normalization.tokenize(sentence).toHashSet()
            val hits = tokens.intersect(termSet).size
            if (hits > bestHits) { bestHits = hits; bestIdx = i }
        }

        val start = maxOf(0, bestIdx - 1)
        val end = minOf(sentences.size, bestIdx + 2)
        var snippet = sentences.subList(start, end).joinToString(" ").trim()

        if (snippet.length > windowChars) {
            val cut = snippet.take(windowChars)
            val lastSpace = cut.lastIndexOf(' ')
            snippet = (if (lastSpace > 0) cut.take(lastSpace) else cut) + "..."
        }

        return highlight(snippet, queryTerms)
    }

    fun highlight(snippet: String, queryTerms: List<String>): String {
        var result = snippet
        for (term in queryTerms.toHashSet().sortedByDescending { it.length }) {
            if (term.isEmpty()) continue
            val pattern = Regex(Regex.escape(term), RegexOption.IGNORE_CASE)
            result = pattern.replace(result) { m -> "**${m.value}**" }
        }
        return result
    }
}
