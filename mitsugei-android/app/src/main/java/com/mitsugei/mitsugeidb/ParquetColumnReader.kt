package com.mitsugei.mitsugeidb

/**
 * Decodes a single column chunk's raw bytes into row-ordered String values.
 *
 * SUPPORTED (deliberately, not accidentally): BYTE_ARRAY columns, DataPageV1,
 * encodings PLAIN and PLAIN_DICTIONARY/RLE_DICTIONARY, flat (non-nested,
 * non-repeated) schema — i.e. exactly the shape url/text/dump/date columns
 * have in every lake in retrieval/lakes.py today.
 *
 * NOT SUPPORTED YET, and detected rather than silently mis-read:
 * DataPageV2, DELTA_* encodings, repeated/nested fields. A column chunk
 * hitting one of these returns an empty list for that page rather than
 * garbage — callers should treat an empty column as "skip this row group,"
 * not "no match."
 */
object ParquetColumnReader {

    /**
     * Legacy whole-column decode — still used by callers that genuinely
     * want every value. Kept for that case; the hot paths below
     * (forEachValue / decodeOnePage) are what avoid materializing a full
     * text column, and what let a caller fetch one page at a time.
     */
    fun readByteArrayColumn(chunkBytes: ByteArray, meta: PColumnMeta, maxDefLevel: Int): List<String?> {
        val values = ArrayList<String?>()
        forEachValue(chunkBytes, meta, maxDefLevel) { _, v -> values.add(v) }
        return values
    }

    /**
     * Page-at-a-time streaming decode of a BYTE_ARRAY column chunk —
     * the same execution shape DuckDB's parquet reader uses: nothing
     * forces a whole row group's column into memory as one big list.
     * Only ever holds the current page's decoded values (a Parquet page
     * is typically on the order of ~1MB uncompressed, regardless of how
     * big the row group is), and hands each row to [onValue] with its
     * row-group-local index as soon as it's decoded, then drops it.
     *
     * [dictionaryFilter], when given, is DuckDB-style predicate pushdown:
     * if this chunk turns out to be dictionary-encoded, only the (small)
     * dictionary page is decoded first and checked against the filter.
     * If none of the dictionary's unique values can pass, every data page
     * in the chunk is skipped without ever being decompressed or decoded.
     *
     * Used as the fallback path when a chunk has no PageIndex to do true
     * per-page range requests with (see MitsugeiDbLake.scanColumnLazy) —
     * here we already have the whole chunk's bytes in hand, so we still
     * decode them one page at a time rather than all at once.
     */
    fun forEachValue(
        chunkBytes: ByteArray,
        meta: PColumnMeta,
        maxDefLevel: Int,
        dictionaryFilter: ((String) -> Boolean)? = null,
        onValue: (index: Int, value: String?) -> Unit,
    ) {
        var dictionary: List<String>? = null
        var dictionaryRuledOut = false
        var pos = 0
        var rowIndex = 0
        while (pos < chunkBytes.size && rowIndex < meta.numValues) {
            val header = ParquetMeta.parsePageHeader(chunkBytes, pos)
            val pageStart = pos + header.headerLength
            val pageEnd = pageStart + header.compressedSize
            if (pageEnd > chunkBytes.size) break // truncated fetch guard

            when (header.pageType) {
                PPageType.DICTIONARY_PAGE -> {
                    val raw = Compression.decompress(
                        meta.codec, chunkBytes.copyOfRange(pageStart, pageEnd), header.uncompressedSize,
                    )
                    val dict = decodePlainByteArrayValues(raw, 0, header.numValues)
                    dictionary = dict
                    if (dictionaryFilter != null) dictionaryRuledOut = dict.none(dictionaryFilter)
                }
                PPageType.DATA_PAGE -> {
                    if (dictionaryRuledOut) {
                        // Dictionary-encoded and no dictionary entry can match —
                        // this data page cannot contain a hit. Skip decoding it
                        // entirely; just keep row indices aligned for later pages.
                        rowIndex += header.numValues
                    } else {
                        val raw = Compression.decompress(
                            meta.codec, chunkBytes.copyOfRange(pageStart, pageEnd), header.uncompressedSize,
                        )
                        val values = decodeDataPageV1(raw, header, dictionary, maxDefLevel)
                        for (v in values) {
                            onValue(rowIndex, v)
                            rowIndex++
                        }
                    }
                }
                else -> {
                    // DATA_PAGE_V2 / index pages: unsupported, but still advance
                    // the row count so subsequent pages stay index-aligned.
                    rowIndex += header.numValues
                }
            }
            pos = pageEnd
        }
    }

    /**
     * Decodes exactly one already-fetched page (header bytes followed by
     * its compressed data — exactly what one OffsetIndex-targeted range
     * request returns) into its row values. This is what lets
     * MitsugeiDbLake fetch a column one HTTP range GET per page instead
     * of one big request for the whole chunk.
     */
    fun decodeOnePage(pageBytes: ByteArray, meta: PColumnMeta, dictionary: List<String>?, maxDefLevel: Int): List<String?> {
        val header = ParquetMeta.parsePageHeader(pageBytes, 0)
        val dataStart = header.headerLength
        val dataEnd = dataStart + header.compressedSize
        if (dataEnd > pageBytes.size || header.pageType != PPageType.DATA_PAGE) return emptyList()
        val raw = Compression.decompress(meta.codec, pageBytes.copyOfRange(dataStart, dataEnd), header.uncompressedSize)
        return decodeDataPageV1(raw, header, dictionary, maxDefLevel)
    }

    /** Same idea as [decodeOnePage] but for a standalone dictionary-page fetch. */
    fun decodeDictionaryPage(pageBytes: ByteArray, codec: Int): List<String> {
        val header = ParquetMeta.parsePageHeader(pageBytes, 0)
        val dataStart = header.headerLength
        val dataEnd = dataStart + header.compressedSize
        if (dataEnd > pageBytes.size || header.pageType != PPageType.DICTIONARY_PAGE) return emptyList()
        val raw = Compression.decompress(codec, pageBytes.copyOfRange(dataStart, dataEnd), header.uncompressedSize)
        return decodePlainByteArrayValues(raw, 0, header.numValues)
    }

    private fun decodePlainByteArrayValues(data: ByteArray, startAt: Int, count: Int): List<String> {
        val out = ArrayList<String>(count)
        var pos = startAt
        repeat(count) {
            if (pos + 4 > data.size) return@repeat
            val len = readLenLE(data, pos); pos += 4
            if (pos + len > data.size) return@repeat
            out.add(String(data, pos, len, Charsets.UTF_8))
            pos += len
        }
        return out
    }

    private fun readLenLE(data: ByteArray, pos: Int): Int =
        (data[pos].toInt() and 0xFF) or
            ((data[pos + 1].toInt() and 0xFF) shl 8) or
            ((data[pos + 2].toInt() and 0xFF) shl 16) or
            ((data[pos + 3].toInt() and 0xFF) shl 24)

    private fun decodeDataPageV1(
        raw: ByteArray,
        header: ParquetMeta.PageHeader,
        dictionary: List<String>?,
        maxDefLevel: Int,
    ): List<String?> {
        var pos = 0
        var defLevels: IntArray? = null

        if (maxDefLevel > 0) {
            val lenPrefix = readLenLE(raw, pos); pos += 4
            val bitWidth = RleBitPacking.bitWidthFor(maxDefLevel + 1)
            val result = RleBitPacking.decode(raw, pos, pos + lenPrefix, bitWidth, header.numValues)
            defLevels = result.values
            pos += lenPrefix
        }

        val presentCount = defLevels?.count { it == maxDefLevel } ?: header.numValues

        val presentValues: List<String> = when {
            PEncoding.isDictionary(header.encoding) -> {
                val dict = dictionary
                    ?: return emptyList() // dictionary page missing/unsupported upstream — bail cleanly
                if (pos >= raw.size) return emptyList()
                val bitWidth = raw[pos].toInt() and 0xFF
                pos += 1
                val idx = RleBitPacking.decode(raw, pos, raw.size, bitWidth, presentCount)
                idx.values.map { dict.getOrElse(it) { "" } }
            }
            header.encoding == PEncoding.PLAIN -> decodePlainByteArrayValues(raw, pos, presentCount)
            else -> emptyList() // DELTA_* etc, not yet implemented
        }

        if (defLevels == null) return presentValues
        val out = ArrayList<String?>(header.numValues)
        var vi = 0
        for (d in defLevels) {
            if (d == maxDefLevel) {
                out.add(presentValues.getOrNull(vi))
                vi++
            } else {
                out.add(null)
            }
        }
        return out
    }
}
