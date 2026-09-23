package com.mitsugei.engine

/** Port of mitsugei/ranking/scoring.py. */
object Scoring {
    val DEFAULT_WEIGHTS: Map<String, Double> = mapOf(
        "title_match" to 1.6,
        "url_match" to 0.6,
        "body_match" to 0.9,
        "phrase_match" to 1.4,
        "proximity" to 0.7,
        "freshness" to 0.3,
        "authority" to 0.5,
        "tfidf" to 1.1,
        "length_penalty" to 0.2,
        "semantic_overlap" to 0.8,
    )

    fun linearScore(features: Features, weights: Map<String, Double> = DEFAULT_WEIGHTS): ScoreBreakdown {
        val breakdown = ScoreBreakdown()
        for ((signal, value) in features.values) {
            val weight = weights[signal] ?: 0.0
            if (weight != 0.0) breakdown.add(signal, value * weight)
        }
        return breakdown
    }
}
