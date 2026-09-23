package com.mitsugei.engine

/** Port of mitsugei/ranking/page_ranker.py. */
class PageRanker(private val semanticHasher: SubwordHasher? = null) {

    fun scoreDocument(
        document: Document,
        queryTokens: List<String>,
        queryPhrases: List<String>,
        crawlTimestamp: String = "",
        domainAuthority: Double = 0.0,
        termFreqLookup: ((String) -> Int)? = null,
        docFreqLookup: ((String) -> Int)? = null,
        totalDocuments: Int = 1,
        rawQuery: String = "",
    ): ScoreBreakdown {
        val features = FeatureExtractor.extract(
            docTitle = document.title, docUrl = document.url, docText = document.text,
            queryTokens = queryTokens, queryPhrases = queryPhrases, crawlTimestamp = crawlTimestamp,
            domainAuthority = domainAuthority, termFreqLookup = termFreqLookup, docFreqLookup = docFreqLookup,
            totalDocuments = totalDocuments, semanticHasher = semanticHasher, rawQuery = rawQuery,
        )
        return Scoring.linearScore(features)
    }
}
