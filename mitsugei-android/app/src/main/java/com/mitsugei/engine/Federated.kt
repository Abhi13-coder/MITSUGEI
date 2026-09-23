package com.mitsugei.engine

import com.mitsugei.mitsugeidb.LakeRow
import com.mitsugei.mitsugeidb.MitsugeiDbLake

/**
 * Port of mitsugei/retrieval/federated.py.
 *
 * query -> local SQLite cache first
 *       -> verticals (Wikipedia / GitHub / HF / Stack / Reddit)
 *       -> mitsugeidb lakes (FineWeb, RefinedWeb, C4, open-markdown, …)
 *          in router order
 *
 * Since both this and mitsugeidb are now plain Kotlin in the same process,
 * there's no Chaquopy/JVM-bridge indirection left to reason about — this
 * calls MitsugeiDbLake directly, same as calling any other class.
 */
object Federated {

    private fun hitsToCandidates(hits: List<VerticalHit>, db: MitsugeiDb): Int {
        var newly = 0
        for (h in hits) {
            val docId = Ingestor.ingestRow(db, url = h.url, text = "${h.title}. ${h.text}", dump = h.source, date = "")
            if (docId != null) newly++
        }
        return newly
    }

    private fun queryLake(lakeName: String, queryTerms: List<String>, limit: Int, hfToken: String?): List<LakeRow> {
        val preset = Lakes.LAKES[lakeName] ?: return emptyList()
        return try {
            val lake = MitsugeiDbLake(
                preset.dataset, preset.revision, preset.subpathPrefix,
                preset.urlCol, preset.textCol, preset.dumpCol, preset.dateCol, hfToken,
            )
            lake.queryCandidates(queryTerms, limit)
        } catch (e: Exception) {
            emptyList() // one bad lake shouldn't kill the whole search — same philosophy as the old LakeQueryError catch
        }
    }

    fun discoverAuto(
        db: MitsugeiDb,
        queryTerms: List<String>,
        query: String = "",
        localLimit: Int = 500,
        lakeLimit: Int = 80,
        verticalLimit: Int = 12,
        minLocalHits: Int = 3,
        hfToken: String? = null,
        githubToken: String? = null,
    ): List<Candidate> {
        var local = CandidateDiscovery.discover(db, queryTerms, limit = localLimit)
        if (local.size >= minLocalHits) return local

        val fullQuery = query.ifBlank { queryTerms.joinToString(" ") }

        for (vname in Router.pickVerticals(fullQuery)) {
            try {
                val vert = Verticals.get(vname, githubToken)
                val hits = vert.search(fullQuery, limit = verticalLimit)
                if (hits.isNotEmpty()) {
                    hitsToCandidates(hits, db)
                    local = CandidateDiscovery.discover(db, queryTerms, limit = localLimit)
                    if (local.size >= minLocalHits) return local
                }
            } catch (e: Exception) {
                continue
            }
        }

        for (lakeName in Router.pickLakeChain(queryTerms)) {
            val rows = queryLake(lakeName, queryTerms, lakeLimit, hfToken)
            var newly = 0
            for (row in rows) {
                val docId = Ingestor.ingestRow(db, url = row.url, text = row.text, dump = row.dump, date = row.date)
                if (docId != null) newly++
            }
            local = CandidateDiscovery.discover(db, queryTerms, limit = localLimit)
            if (local.size >= minLocalHits) return local
        }

        return local
    }
}
