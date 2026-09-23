package com.mitsugei.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p

/** Port of mitsugei/ranking/features.py. */
object FeatureExtractor {

    private fun termOverlapRatio(textTokens: List<String>, queryTerms: List<String>): Double {
        if (queryTerms.isEmpty()) return 0.0
        val textSet = textTokens.toHashSet()
        val hits = queryTerms.count { it in textSet }
        return hits.toDouble() / queryTerms.size
    }

    private fun phraseMatch(text: String, phrases: List<String>): Double {
        if (phrases.isEmpty()) return 0.0
        val lower = text.lowercase()
        val hits = phrases.count { lower.contains(it) }
        return hits.toDouble() / phrases.size
    }

    private fun termProximity(textTokens: List<String>, queryTerms: List<String>): Double {
        val positions = LinkedHashMap<String, MutableList<Int>>()
        for (t in queryTerms) positions[t] = ArrayList()
        for ((i, tok) in textTokens.withIndex()) positions[tok]?.add(i)
        val present = positions.filterValues { it.isNotEmpty() }
        if (present.size < 2) return 0.0
        val firsts = present.values.map { it[0] }.sorted()
        val span = firsts.last() - firsts.first() + 1
        return minOf(1.0, present.size.toDouble() / span)
    }

    private fun freshness(crawlTimestamp: String): Double {
        if (crawlTimestamp.isBlank()) return 0.0
        val year = crawlTimestamp.take(4).toIntOrNull() ?: return 0.0
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val ageYears = maxOf(0, currentYear - year)
        return exp(-ageYears / 5.0)
    }

    fun extract(
        docTitle: String,
        docUrl: String,
        docText: String,
        queryTokens: List<String>,
        queryPhrases: List<String>,
        crawlTimestamp: String = "",
        domainAuthority: Double = 0.0,
        termFreqLookup: ((String) -> Int)? = null,
        docFreqLookup: ((String) -> Int)? = null,
        totalDocuments: Int = 1,
        semanticHasher: SubwordHasher? = null,
        rawQuery: String = "",
    ): Features {
        val titleTokens = Normalization.tokenize(docTitle)
        val urlTokens = Normalization.tokenize(docUrl)
        val bodyTokens = Normalization.tokenize(docText)

        val f = Features()
        f.values["title_match"] = termOverlapRatio(titleTokens, queryTokens)
        f.values["url_match"] = termOverlapRatio(urlTokens, queryTokens)
        f.values["body_match"] = termOverlapRatio(bodyTokens, queryTokens)
        f.values["phrase_match"] = phraseMatch("$docTitle $docText", queryPhrases)
        f.values["proximity"] = termProximity(bodyTokens, queryTokens)
        f.values["freshness"] = freshness(crawlTimestamp)
        f.values["authority"] = domainAuthority
        f.values["length_penalty"] = 1.0 / (1.0 + ln1p(maxOf(1, bodyTokens.size) / 200.0))

        if (termFreqLookup != null && docFreqLookup != null && bodyTokens.isNotEmpty()) {
            var tfidfSum = 0.0
            for (t in queryTokens.toHashSet()) {
                val tf = termFreqLookup(t)
                val df = maxOf(1, docFreqLookup(t))
                val idf = ln((totalDocuments + 1).toDouble() / df)
                tfidfSum += (tf.toDouble() / bodyTokens.size) * idf
            }
            f.values["tfidf"] = tfidfSum
        } else {
            f.values["tfidf"] = 0.0
        }

        f.values["semantic_overlap"] = semanticHasher?.similarity(
            rawQuery.ifBlank { queryTokens.joinToString(" ") }, "$docTitle $docText",
        ) ?: 0.0

        return f
    }
}
