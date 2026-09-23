package com.mitsugei.engine

import android.content.Context

/**
 * Port of mitsugei/search.py's MitsugeiSearch.
 *
 * raw query -> normalize -> tokenize -> phrases -> candidate discovery
 * -> Page Ranker -> Rank Brain (+ Markov) -> content ranking -> snippets
 * -> final ranking
 *
 * No FastAPI/uvicorn, no Chaquopy JSON hop — SearchRepository.kt calls
 * search() on this directly and gets SearchResult objects back.
 */
class SearchEngine(
    context: Context,
    dbName: String = "mitsugei.db",
    private val hfToken: String? = null,
    private val githubToken: String? = null,
    cheapLimit: Int = 200,
    private val strongLimit: Int = 50,
    useSemantic: Boolean = true,
) {
    private val db = MitsugeiDb(context, dbName)
    private val pageRanker = PageRanker(semanticHasher = if (useSemantic) SubwordHasher() else null)
    private val rankBrain = RankBrain(db)
    private val markov = MarkovModel(db)
    private val cheapLimit = cheapLimit

    fun search(rawQuery: String, topK: Int = 10): List<SearchResult> {
        val parsed = QueryParser.parse(rawQuery, markov)
        db.recordQuery(parsed.normalized)
        markov.observeQueryTokens(parsed.tokens)

        var candidates = Federated.discoverAuto(
            db, parsed.importantTerms, query = parsed.normalized,
            localLimit = cheapLimit * 4, hfToken = hfToken, githubToken = githubToken,
        )
        candidates = Filters.reduceCandidates(candidates, cheapLimit = cheapLimit)

        val markovSignal = markov.scoreQuerySequence(parsed.tokens)
        val totalDocs = maxOf(1, db.totalDocuments())

        val scored = ArrayList<Pair<MitsugeiDb.DocumentRow, ScoreBreakdown>>()
        for (candidate in candidates.take(strongLimit)) {
            val row = db.getDocument(candidate.docId) ?: continue
            val domain = try { java.net.URI(row.url).host ?: "" } catch (e: Exception) { "" }
            val breakdown = pageRanker.scoreDocument(
                document = Document(url = row.url, title = row.title ?: "", text = row.text ?: "", language = row.language),
                queryTokens = parsed.importantTerms,
                queryPhrases = parsed.phrases,
                crawlTimestamp = row.crawlTimestamp ?: "",
                domainAuthority = db.domainAuthority(domain),
                termFreqLookup = { t -> db.termFreq(row.docId, t) },
                docFreqLookup = { t -> db.docFreq(t) },
                totalDocuments = totalDocs,
                rawQuery = parsed.normalized,
            )
            val adjusted = rankBrain.adjust(breakdown)
            adjusted.add("markov_transition", markovSignal * 0.4)
            scored.add(row to adjusted)
        }

        scored.sortByDescending { it.second.total }

        return scored.take(topK).map { (row, breakdown) ->
            val snippet = Snippets.bestSnippet(row.text ?: "", parsed.importantTerms)
            SearchResult(
                url = row.url,
                title = row.title ?: row.url,
                snippet = snippet,
                score = Math.round(breakdown.total * 10000.0) / 10000.0,
                signals = breakdown.signals.mapValues { Math.round(it.value * 10000.0) / 10000.0 },
            )
        }
    }

    fun close() = db.close()
}
