package com.mitsugei.engine

/** Port of mitsugei/ranking/markov.py. */
class MarkovModel(private val db: MitsugeiDb) {

    fun observeQueryTokens(tokens: List<String>) {
        if (tokens.isEmpty()) return
        db.markovBump("__START__", tokens[0])
        for (i in 0 until tokens.size - 1) db.markovBump(tokens[i], tokens[i + 1])
    }

    fun observeRankingState(fromState: String, toState: String) = db.markovBump(fromState, toState)

    fun likelyNext(token: String, topK: Int = 3): List<String> =
        db.markovTransitions(token).take(topK).map { it.first }

    fun transitionProbability(fromState: String, toState: String, smoothing: Double = 1.0): Double {
        val total = db.markovTotal(fromState)
        val transitions = db.markovTransitions(fromState).toMap()
        val count = transitions[toState] ?: 0
        val vocabSize = maxOf(1, transitions.size)
        return (count + smoothing) / (total + smoothing * vocabSize)
    }

    fun scoreQuerySequence(tokens: List<String>): Double {
        if (tokens.size < 2) return 0.0
        val probs = (0 until tokens.size - 1).map { i -> transitionProbability(tokens[i], tokens[i + 1]) }
        return probs.sum() / probs.size
    }
}
