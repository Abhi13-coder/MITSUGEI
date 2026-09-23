package com.mitsugei.engine

/** Port of mitsugei/retrieval/candidate.py. */
object CandidateDiscovery {
    fun discover(db: MitsugeiDb, queryTokens: List<String>, limit: Int = 500): List<Candidate> {
        val docIds = LinkedHashSet<Long>()
        docIds.addAll(db.lookupCandidates(queryTokens, field = "body", limit = limit))
        docIds.addAll(db.lookupCandidates(queryTokens, field = "title", limit = limit))
        docIds.addAll(db.lookupCandidates(queryTokens, field = "url", limit = limit))

        val candidates = ArrayList<Candidate>()
        for (docId in docIds) {
            val row = db.getDocument(docId) ?: continue
            candidates.add(
                Candidate(
                    url = row.url, urlkey = row.urlkey, crawlTimestamp = row.crawlTimestamp ?: "",
                    docId = docId, dump = row.dump, title = row.title,
                ),
            )
        }
        return candidates
    }
}
