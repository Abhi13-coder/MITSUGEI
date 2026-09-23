package com.mitsugei.engine

/**
 * User history / posting ranker that operates on *compressed* AdaptiveEncoder
 * blobs without ever fully decompressing them for the ranking step.
 *
 * Ranking signals derived purely from [AdaptiveEncoder.BitMeta] + the raw
 * byte length / tag:
 *
 *   - density          : how tightly the codec packed the integers
 *   - entropy proxy    : bit-width relative to value range
 *   - recency bias     : caller can supply a timestamp weight
 *   - frequency boost  : term_freq already stored in cleartext next to the blob
 *
 * Only when the ranked item is about to be shown to the user does the caller
 * invoke [AdaptiveEncoder.decode] to obtain human-readable positions / text.
 */
object BitRanker {

    data class RankedItem(
        val id: Long,
        val score: Double,
        val meta: AdaptiveEncoder.BitMeta,
        val termFreq: Int,
        val compressed: ByteArray,
    )

    /**
     * Rank a collection of compressed position postings (or any AdaptiveEncoder
     * blobs) for a single user history view.
     *
     * @param items list of (id, termFreq, compressedBlob)
     * @param queryBitWidthHint optional hint from the query side (0 = ignore)
     * @param recencyWeights optional map id → [0,1] freshness
     */
    fun rank(
        items: List<Triple<Long, Int, ByteArray>>,
        queryBitWidthHint: Int = 0,
        recencyWeights: Map<Long, Double> = emptyMap(),
    ): List<RankedItem> {
        if (items.isEmpty()) return emptyList()

        val scored = items.map { (id, tf, blob) ->
            val meta = AdaptiveEncoder.inspect(blob)
            val score = scoreOne(meta, tf, queryBitWidthHint, recencyWeights[id] ?: 0.5)
            RankedItem(id, score, meta, tf, blob)
        }
        return scored.sortedByDescending { it.score }
    }

    /**
     * Core scoring function – pure arithmetic on BitMeta, no decode.
     */
    fun scoreOne(
        meta: AdaptiveEncoder.BitMeta,
        termFreq: Int,
        queryBitWidthHint: Int = 0,
        recency: Double = 0.5,
    ): Double {
        if (meta.count == 0) return 0.0

        // 1. Frequency signal (clear-text, always available)
        val tfScore = 1.0 + kotlin.math.ln(1.0 + termFreq)

        // 2. Density: how many values per compressed byte (higher = better packing
        //    and usually more “interesting” structured data)
        val density = meta.count.toDouble() / meta.compressedBytes.coerceAtLeast(1)

        // 3. Codec quality bonus – adaptive choices that beat RAW indicate
        //    regular structure that a ranker can trust
        val codecBonus = when (meta.codec) {
            AdaptiveEncoder.TAG_DELTA_AFFINE -> 1.25
            AdaptiveEncoder.TAG_DELTA_BITPACK -> 1.20
            AdaptiveEncoder.TAG_AFFINE_BITPACK -> 1.15
            AdaptiveEncoder.TAG_DICT -> 1.10
            AdaptiveEncoder.TAG_BITPACK -> 1.05
            else -> 1.0 // RAW
        }

        // 4. Bit-width affinity: if the query side also used a similar width,
        //    the lists are more likely to align (cheap semantic-ish signal)
        val widthAffinity = if (queryBitWidthHint > 0 && meta.bitWidth > 0) {
            1.0 - kotlin.math.abs(meta.bitWidth - queryBitWidthHint) / 32.0
        } else 1.0

        // 5. Uniqueness penalty for dictionary (very high uniqueness → less
        //    repetitive → slightly lower trust for history ranking)
        val uniqFactor = if (meta.uniqueCount > 0) {
            1.0 - 0.15 * (meta.uniqueCount.toDouble() / meta.count)
        } else 1.0

        return tfScore * density * codecBonus * widthAffinity * uniqFactor * (0.5 + 0.5 * recency)
    }

    /**
     * Materialise the human-readable form *only* after ranking.
     * This is the single place decompression happens for the user-facing path.
     */
    fun materialise(item: RankedItem): IntArray = AdaptiveEncoder.decode(item.compressed)
}
