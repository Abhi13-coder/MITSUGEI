package com.mitsugei.engine

import kotlin.math.exp

/** Port of mitsugei/ranking/rank_brain.py. Deterministic linear re-weighting,
 * explicitly not a neural network — see the Python module's docstring. */
class RankBrain(private val db: MitsugeiDb, private val learningRate: Double = 0.05) {

    private fun blendWeights(): Map<String, Double> = db.getRankBrainWeights()

    fun adjust(breakdown: ScoreBreakdown): ScoreBreakdown {
        val multipliers = blendWeights()
        val adjusted = ScoreBreakdown()
        for ((signal, value) in breakdown.signals) {
            val mult = multipliers[signal] ?: 1.0
            adjusted.add(signal, value * mult)
        }
        return adjusted
    }

    fun learnFromClick(breakdown: ScoreBreakdown, clicked: Boolean) {
        val current = blendWeights()
        val target = if (clicked) 1.0 else 0.0
        val predicted = if (breakdown.total != 0.0) 1.0 / (1.0 + exp(-breakdown.total)) else 0.5
        val error = target - predicted

        for ((signal, value) in breakdown.signals) {
            val weight = current[signal] ?: 1.0
            val gradient = error * value
            val newWeight = (weight + learningRate * gradient).coerceIn(0.0, 3.0)
            db.setRankBrainWeight(signal, newWeight)
        }
    }
}
