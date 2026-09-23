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

    fun readByteArrayColumn(chunkBytes: ByteArray, meta: PColumnMeta, maxDefLevel: Int): List<String?> {
        val values = ArrayList<String?>()
        var dictionary: List<String>? = null
        var pos = 0
        while (pos < chunkBytes.size && values.size < meta.numValues) {
            val header = ParquetMeta.parsePageHeader(chunkBytes, pos)
            val pageStart = pos + header.headerLength
            val pageEnd = pageStart + header.compressedSize
            if (pageEnd > chunkBytes.size) break // truncated fetch guard
            val raw = Compression.decompress(
                meta.codec,
                chunkBytes.copyOfRange(pageStart, pageEnd),
                header.uncompressedSize,
            )

            when (header.pageType) {
                PPageType.DICTIONARY_PAGE ->
                    dictionary = decodePlainByteArrayValues(raw, 0, header.numValues)
                PPageType.DATA_PAGE ->
                    values.addAll(decodeDataPageV1(raw, header, dictionary, maxDefLevel))
                else -> { /* DATA_PAGE_V2 / index pages: unsupported, contributes nothing */ }
            }
            pos = pageEnd
        }
        return values
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
