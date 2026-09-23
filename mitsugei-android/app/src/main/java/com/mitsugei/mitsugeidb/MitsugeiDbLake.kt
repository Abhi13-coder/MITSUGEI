package com.mitsugei.mitsugeidb

/** Row shape consumed directly by com.mitsugei.engine.Ingestor.ingestRow(). */
data class LakeRow(val url: String, val text: String, val dump: String, val date: String)

/**
 * Server-free, index-cap-free replacement for the original DuckDB+httpfs
 * lake client, for lakes that datasets-server's 5GB-indexed /search can't
 * usefully cover (fineweb*, refinedweb, c4 — see chat history for why).
 *
 * Mirrors DuckDB's own httpfs+parquet execution model as closely as a
 * "contains any of these terms" predicate allows:
 *   - only footer + specific column chunks are ever range-fetched, never
 *     a whole shard
 *   - when a writer emitted Parquet's PageIndex, pages are fetched one
 *     range request at a time, in row order, and the scan stops the
 *     moment enough matches are found — later pages are never requested
 *   - dictionary-encoded chunks get predicate pushdown against just the
 *     dictionary page before any data page is touched
 *   - decoding is page-at-a-time throughout, so memory never scales with
 *     row-group size the way materializing a whole column would
 * The one thing real DuckDB has that this doesn't: zone-map (min/max)
 * row-group pruning — but that only helps range/equality predicates, not
 * a substring "contains" search, so DuckDB itself gets no benefit from it
 * for this query shape either. There's no index shortcut around actually
 * reading a row group's text to check whether a substring occurs in it.
 *
 * Called directly from com.mitsugei.engine.Federated — same JVM, plain
 * Kotlin, no bridge of any kind:
 *
 *     val lake = MitsugeiDbLake("HuggingFaceFW/fineweb", "refs/convert/parquet",
 *                                "sample-10BT/train/", "url", "text", "dump", "date", null)
 *     val rows = lake.queryCandidates(listOf("black", "hole"), 80)
 */
class MitsugeiDbLake(
    private val repo: String,
    private val revision: String,
    private val subpathPrefix: String,
    private val urlCol: String,
    private val textCol: String,
    private val dumpCol: String?,
    private val dateCol: String?,
    private val hfToken: String?,
) {
    private var cachedShards: List<HfFile>? = null

    private fun shards(): List<HfFile> {
        var s = cachedShards
        if (s == null) {
            s = HfHub.listShards(repo, revision, subpathPrefix, hfToken)
            cachedShards = s
        }
        return s
    }

    companion object {
        // Total bytes fetched over the network across an entire
        // queryCandidates() call, across every shard. Memory no longer
        // scales with row-group size (everything is page-at-a-time now),
        // so this exists purely to bound how long/far a never-matching
        // query walks on a slow mobile connection.
        private const val MAX_QUERY_BUDGET_BYTES = 512L * 1024 * 1024

        // Hard ceiling on shards visited per query, independent of the
        // byte budget, so a lake with thousands of tiny shards can't turn
        // into thousands of network round trips on a miss.
        private const val MAX_SHARDS_PER_QUERY = 60
    }

    /**
     * Case-insensitive, word-boundary OR-of-terms scan — same semantics
     * duckdb_lake.py used, so ranking downstream sees comparable candidates
     * regardless of which lake implementation served them.
     */
    fun queryCandidates(terms: List<String>, limit: Int): List<LakeRow> {
        val patterns = terms.map { t -> Regex("\\b" + Regex.escape(t.lowercase()) + "\\b") }
        if (patterns.isEmpty()) return java.util.ArrayList()

        val out = ArrayList<LakeRow>()
        val budget = LongArray(1) // [0] = bytes spent so far, boxed so scan helpers can mutate it
        var shardsVisited = 0
        for (shard in shards()) {
            if (out.size >= limit) break
            if (shardsVisited >= MAX_SHARDS_PER_QUERY) break
            if (budget[0] >= MAX_QUERY_BUDGET_BYTES) break
            shardsVisited++
            try {
                scanShard(shard, patterns, limit, out, budget)
            } catch (e: Exception) {
                // One bad/unsupported shard shouldn't kill the whole query —
                // move on, same "honest partial result" philosophy as the
                // DuckDB path's LakeQueryError handling in federated.py.
                continue
            }
        }
        return out
    }

    private fun scanShard(shard: HfFile, patterns: List<Regex>, limit: Int, out: MutableList<LakeRow>, budget: LongArray) {
        val fileUrl = HfHub.resolveUrl(repo, revision, shard.path)
        val totalSize = if (shard.size > 0) shard.size else fetchSizeViaRange(fileUrl)

        // Footer only (last 8 bytes for the trailer, then the footer itself)
        // — same as DuckDB's httpfs: never the whole shard, just the two
        // small range reads needed to get row-group/column-chunk metadata.
        val tail = HfHub.rangeGet(fileUrl, totalSize - 8, totalSize - 1, hfToken)
        val magic = String(tail, 4, 4, Charsets.US_ASCII)
        if (magic != "PAR1") throw HfHubException("${shard.path}: not a Parquet file (bad magic)")
        val footerLen = (tail[0].toInt() and 0xFF) or ((tail[1].toInt() and 0xFF) shl 8) or
            ((tail[2].toInt() and 0xFF) shl 16) or ((tail[3].toInt() and 0xFF) shl 24)
        val footerBytes = HfHub.rangeGet(fileUrl, totalSize - 8 - footerLen, totalSize - 8 - 1, hfToken)
        budget[0] += (tail.size + footerBytes.size).toLong()
        val meta = ParquetMeta.parseFileMetaData(footerBytes)

        fun leafDefLevel(colName: String?): Int {
            if (colName == null) return 0
            val leaf = meta.schemaLeavesByName[colName] ?: return 0
            return if (leaf.repetitionType == PRepetition.OPTIONAL) 1 else 0
        }
        fun isStringCol(colName: String?): Boolean {
            if (colName == null) return false
            val leaf = meta.schemaLeavesByName[colName] ?: return false
            return leaf.type == PType.BYTE_ARRAY
        }

        val urlDefLevel = leafDefLevel(urlCol)
        val textDefLevel = leafDefLevel(textCol)
        val dumpUsable = isStringCol(dumpCol)
        val dateUsable = isStringCol(dateCol)
        val matcher: (String) -> Boolean = { v -> patterns.any { it.containsMatchIn(v.lowercase()) } }

        for (rg in meta.rowGroups) {
            if (out.size >= limit) return
            if (budget[0] >= MAX_QUERY_BUDGET_BYTES) return
            val colByPath = HashMap<String, PColumnChunk>()
            for (c in rg.columns) {
                val name = c.meta.pathInSchema.lastOrNull() ?: continue
                colByPath[name] = c
            }
            val textChunk = colByPath[textCol] ?: continue
            val urlChunk = colByPath[urlCol] ?: continue

            // 1) Text column: true page-granular lazy fetch when the
            //    writer emitted a PageIndex — one range GET per page,
            //    stopping the moment we have enough matches, so trailing
            //    pages in a huge row group are never even requested. Falls
            //    back to a single (but still page-streamed) chunk fetch
            //    when there's no PageIndex to random-access with.
            val remainingSlots = limit - out.size
            val matched = scanColumnLazy(fileUrl, textChunk, textDefLevel, matcher, remainingSlots, budget)
            if (matched.isEmpty()) continue // nothing here — url/dump/date never even get requested

            // 2) Only now fetch the metadata columns, and only the specific
            //    pages (or, without a PageIndex, the specific rows) that
            //    cover the matched indices — never the full columns.
            val wanted = matched.keys
            val urlVals = fetchRowsForIndices(fileUrl, urlChunk, urlDefLevel, wanted, budget)
            val dumpVals = if (dumpUsable) colByPath[dumpCol]?.let { fetchRowsForIndices(fileUrl, it, leafDefLevel(dumpCol), wanted, budget) } else null
            val dateVals = if (dateUsable) colByPath[dateCol]?.let { fetchRowsForIndices(fileUrl, it, leafDefLevel(dateCol), wanted, budget) } else null

            for ((idx, text) in matched) {
                if (out.size >= limit) return
                out.add(
                    LakeRow(
                        url = urlVals[idx] ?: "",
                        text = text,
                        dump = dumpVals?.get(idx) ?: "",
                        date = dateVals?.get(idx) ?: "",
                    ),
                )
            }
        }
    }

    private fun fetchColumnBytes(fileUrl: String, chunk: PColumnChunk, budget: LongArray): ByteArray {
        val start = chunk.meta.dictionaryPageOffset ?: chunk.meta.dataPageOffset
        val end = start + chunk.meta.totalCompressedSize - 1
        val bytes = HfHub.rangeGet(fileUrl, start, end, hfToken)
        budget[0] += bytes.size.toLong()
        return bytes
    }

    /** Small standalone fetch of just a chunk's dictionary page, if it has one. */
    private fun fetchDictionaryIfAny(fileUrl: String, chunk: PColumnChunk, budget: LongArray): List<String>? {
        val dictOffset = chunk.meta.dictionaryPageOffset ?: return null
        val end = chunk.meta.dataPageOffset - 1
        if (end < dictOffset) return null
        val bytes = HfHub.rangeGet(fileUrl, dictOffset, end, hfToken)
        budget[0] += bytes.size.toLong()
        return ParquetColumnReader.decodeDictionaryPage(bytes, chunk.meta.codec)
    }

    /**
     * DuckDB-style lazy column scan. Order of operations, cheapest first:
     *  1. If dictionary-encoded, fetch only the (tiny) dictionary page and
     *     rule the whole chunk out with zero data-page reads if none of
     *     its unique values can match — real predicate pushdown.
     *  2. If a PageIndex is present, fetch pages one at a time, in file
     *     order, and stop as soon as [remainingSlots] matches are found —
     *     later pages are never requested. This is the actual "twin of
     *     DuckDB's httpfs+PageIndex" behavior: only the bytes strictly
     *     needed to answer the query cross the network.
     *  3. Otherwise (older writer, no PageIndex) fall back to one
     *     whole-chunk fetch, still decoded page-by-page in memory
     *     (ParquetColumnReader.forEachValue) rather than materialized —
     *     the one case a "contains" predicate can't avoid reading past
     *     the first match, because without a PageIndex there's no way to
     *     know a page's byte range without reading sequentially from the
     *     start of the chunk.
     */
    private fun scanColumnLazy(
        fileUrl: String,
        chunk: PColumnChunk,
        defLevel: Int,
        matcher: (String) -> Boolean,
        remainingSlots: Int,
        budget: LongArray,
    ): LinkedHashMap<Int, String> {
        val matched = LinkedHashMap<Int, String>()
        if (remainingSlots <= 0) return matched

        val dictionary = fetchDictionaryIfAny(fileUrl, chunk, budget)
        if (dictionary != null && dictionary.none(matcher)) return matched // chunk ruled out, zero data-page reads

        val offOffset = chunk.offsetIndexOffset
        val offLength = chunk.offsetIndexLength
        if (offOffset != null && offLength != null && offLength > 0) {
            val oiBytes = HfHub.rangeGet(fileUrl, offOffset, offOffset + offLength - 1, hfToken)
            budget[0] += oiBytes.size.toLong()
            for (loc in ParquetMeta.parseOffsetIndex(oiBytes)) {
                if (matched.size >= remainingSlots) break // stop — remaining pages never fetched
                if (budget[0] >= MAX_QUERY_BUDGET_BYTES) break
                val pageBytes = HfHub.rangeGet(fileUrl, loc.offset, loc.offset + loc.compressedPageSize - 1, hfToken)
                budget[0] += pageBytes.size.toLong()
                val values = ParquetColumnReader.decodeOnePage(pageBytes, chunk.meta, dictionary, defLevel)
                for ((i, v) in values.withIndex()) {
                    if (matched.size >= remainingSlots) break
                    if (v != null && matcher(v)) matched[(loc.firstRowIndex + i).toInt()] = v
                }
            }
            return matched
        }

        val chunkBytes = fetchColumnBytes(fileUrl, chunk, budget)
        ParquetColumnReader.forEachValue(chunkBytes, chunk.meta, defLevel, dictionaryFilter = matcher) { idx, value ->
            if (matched.size < remainingSlots && value != null && matcher(value)) matched[idx] = value
        }
        return matched
    }

    /**
     * Fetches only the rows in [wanted] from a column. With a PageIndex,
     * this means only the specific pages that overlap those row indices —
     * a column can have hundreds of pages and only one or two need to be
     * touched for a handful of matched rows. Without a PageIndex it falls
     * back to one whole-chunk fetch (these columns — url/dump/date — are
     * short strings, so that fallback is cheap either way).
     */
    private fun fetchRowsForIndices(fileUrl: String, chunk: PColumnChunk, defLevel: Int, wanted: Set<Int>, budget: LongArray): Map<Int, String> {
        val out = HashMap<Int, String>(wanted.size)
        val offOffset = chunk.offsetIndexOffset
        val offLength = chunk.offsetIndexLength
        if (offOffset != null && offLength != null && offLength > 0) {
            val oiBytes = HfHub.rangeGet(fileUrl, offOffset, offOffset + offLength - 1, hfToken)
            budget[0] += oiBytes.size.toLong()
            val pages = ParquetMeta.parseOffsetIndex(oiBytes)
            val dictionary = fetchDictionaryIfAny(fileUrl, chunk, budget)
            for ((pi, loc) in pages.withIndex()) {
                val nextStart = if (pi + 1 < pages.size) pages[pi + 1].firstRowIndex else Long.MAX_VALUE
                val overlaps = wanted.any { it >= loc.firstRowIndex && it < nextStart }
                if (!overlaps) continue // this page holds none of the rows we need — skip it, never fetched
                val pageBytes = HfHub.rangeGet(fileUrl, loc.offset, loc.offset + loc.compressedPageSize - 1, hfToken)
                budget[0] += pageBytes.size.toLong()
                val values = ParquetColumnReader.decodeOnePage(pageBytes, chunk.meta, dictionary, defLevel)
                for ((i, v) in values.withIndex()) {
                    val globalIdx = (loc.firstRowIndex + i).toInt()
                    if (globalIdx in wanted && v != null) out[globalIdx] = v
                }
            }
            return out
        }

        val bytes = fetchColumnBytes(fileUrl, chunk, budget)
        ParquetColumnReader.forEachValue(bytes, chunk.meta, defLevel) { idx, value ->
            if (idx in wanted && value != null) out[idx] = value
        }
        return out
    }

    private fun fetchSizeViaRange(url: String): Long = HfHub.remoteSize(url, hfToken)
}
