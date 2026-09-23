package com.mitsugei.engine

/** Port of mitsugei/ranking/semantic.py. Deliberately not a real embedding
 * model — see the Python module's docstring for why (no GPU, no large RAM,
 * needs to run per-candidate at query time on a phone). */
class SubwordHasher(private val n: Int = 3) {
    private val wordRe = Regex("[a-z0-9]+")

    private fun hashedNgrams(text: String): Set<Int> {
        val grams = HashSet<Int>()
        for (m in wordRe.findAll(text.lowercase())) {
            val word = m.value
            val padded = "^$word$"
            if (padded.length < n) {
                grams.add(padded.hashCode() and 0xFFFFFFF)
                continue
            }
            for (i in 0..padded.length - n) {
                grams.add(padded.substring(i, i + n).hashCode() and 0xFFFFFFF)
            }
        }
        return grams
    }

    fun similarity(query: String, documentText: String): Double {
        val qGrams = hashedNgrams(query)
        if (qGrams.isEmpty()) return 0.0
        val dGrams = hashedNgrams(documentText.take(2000))
        if (dGrams.isEmpty()) return 0.0
        val intersection = qGrams.intersect(dGrams).size
        val union = qGrams.union(dGrams).size
        return intersection.toDouble() / union
    }
}
