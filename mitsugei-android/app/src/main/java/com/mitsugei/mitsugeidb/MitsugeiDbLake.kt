package com.mitsugei.mitsugeidb

/** Row shape consumed directly by com.mitsugei.engine.Ingestor.ingestRow(). */
data class LakeRow(val url: String, val text: String, val dump: String, val date: String)

/**
 * Server-free, index-cap-free replacement for the original DuckDB+httpfs
 * lake client, for lakes that datasets-server's 5GB-indexed /search can't
 * usefully cover (fineweb*, refinedweb, c4 — see chat history for why).
 *
 * Called directly from com.mitsugei.engine.Federated — same JVM, plain
 * Kotlin, no bridge of any kind:
 *
 *     val lake = MitsugeiDbLake("HuggingFaceFW/fineweb", "refs/convert/parquet",
 *                                "sample-10BT/train/", "url", "text", "dump", "date", null)
 *     val rows = lake.queryCandidates(listOf("black", "hole"), 80)
 *
 * Trade-off vs. datasets-server: no pre-built index, so a rare term means
 * walking more shards before hitting `limit` — slower per-query, but not
 * capped to whatever happened to fit in the first 5GB. Common terms are
 * usually satisfied from the first shard or two.
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

    /**
     * Case-insensitive, word-boundary OR-of-terms scan — same semantics
     * duckdb_lake.py used, so ranking downstream sees comparable candidates
     * regardless of which lake implementation served them.
     */
    fun queryCandidates(terms: List<String>, limit: Int): List<LakeRow> {
        val patterns = terms.map { t -> Regex("\\b" + Regex.escape(t.lowercase()) + "\\b") }
        if (patterns.isEmpty()) return java.util.ArrayList()

        val out = ArrayList<LakeRow>()
        for (shard in shards()) {
            if (out.size >= limit) break
            try {
                scanShard(shard, patterns, limit, out)
            } catch (e: Exception) {
                // One bad/unsupported shard shouldn't kill the whole query —
                // move on, same "honest partial result" philosophy as the
                // DuckDB path's LakeQueryError handling in federated.py.
                continue
            }
        }
        return out
    }

    private fun scanShard(shard: HfFile, patterns: List<Regex>, limit: Int, out: MutableList<LakeRow>) {
        val fileUrl = HfHub.resolveUrl(repo, revision, shard.path)
        val totalSize = if (shard.size > 0) shard.size else fetchSizeViaRange(fileUrl)

        val tail = HfHub.rangeGet(fileUrl, totalSize - 8, totalSize - 1, hfToken)
        val magic = String(tail, 4, 4, Charsets.US_ASCII)
        if (magic != "PAR1") throw HfHubException("${shard.path}: not a Parquet file (bad magic)")
        val footerLen = (tail[0].toInt() and 0xFF) or ((tail[1].toInt() and 0xFF) shl 8) or
            ((tail[2].toInt() and 0xFF) shl 16) or ((tail[3].toInt() and 0xFF) shl 24)
        val footerBytes = HfHub.rangeGet(fileUrl, totalSize - 8 - footerLen, totalSize - 8 - 1, hfToken)
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

        for (rg in meta.rowGroups) {
            if (out.size >= limit) return
            val colByPath = HashMap<String, PColumnChunk>()
            for (c in rg.columns) {
                val name = c.meta.pathInSchema.lastOrNull() ?: continue
                colByPath[name] = c
            }
            val textChunk = colByPath[textCol] ?: continue
            val urlChunk = colByPath[urlCol] ?: continue

            val textVals = readColumn(fileUrl, textChunk, textDefLevel)
            val urlVals = readColumn(fileUrl, urlChunk, urlDefLevel)
            val dumpVals = if (dumpUsable) colByPath[dumpCol]?.let { readColumn(fileUrl, it, leafDefLevel(dumpCol)) } else null
            val dateVals = if (dateUsable) colByPath[dateCol]?.let { readColumn(fileUrl, it, leafDefLevel(dateCol)) } else null

            val n = minOf(textVals.size, urlVals.size)
            for (i in 0 until n) {
                if (out.size >= limit) return
                val text = textVals[i] ?: continue
                if (patterns.any { it.containsMatchIn(text.lowercase()) }) {
                    out.add(
                        LakeRow(
                            url = urlVals.getOrNull(i) ?: "",
                            text = text,
                            dump = dumpVals?.getOrNull(i) ?: "",
                            date = dateVals?.getOrNull(i) ?: "",
                        ),
                    )
                }
            }
        }
    }

    private fun readColumn(fileUrl: String, chunk: PColumnChunk, defLevel: Int): List<String?> {
        val start = chunk.meta.dictionaryPageOffset ?: chunk.meta.dataPageOffset
        val end = start + chunk.meta.totalCompressedSize - 1
        val bytes = HfHub.rangeGet(fileUrl, start, end, hfToken)
        return ParquetColumnReader.readByteArrayColumn(bytes, chunk.meta, defLevel)
    }

    private fun fetchSizeViaRange(url: String): Long = HfHub.remoteSize(url, hfToken)
}
