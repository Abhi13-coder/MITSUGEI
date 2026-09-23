package com.mitsugei.engine

/** Port of mitsugei/retrieval/filters.py. */
object Filters {
    fun reduceCandidates(candidates: List<Candidate>, cheapLimit: Int = 200): List<Candidate> =
        candidates.take(cheapLimit)
}
