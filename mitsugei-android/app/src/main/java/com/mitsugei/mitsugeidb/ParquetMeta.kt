package com.mitsugei.mitsugeidb

/**
 * Field IDs below are copied from parquet-format's parquet.thrift
 * (stable since the format's 1.0 release — this is the public wire spec,
 * not an implementation detail that changes between writer versions).
 */

data class PColumnMeta(
    val type: Int,
    val encodings: List<Int>,
    val pathInSchema: List<String>,
    val codec: Int,
    val numValues: Long,
    val totalUncompressedSize: Long,
    val totalCompressedSize: Long,
    val dataPageOffset: Long,
    val dictionaryPageOffset: Long?,
)

data class PColumnChunk(
    val fileOffset: Long,
    val meta: PColumnMeta,
    // Present when the writer emitted Parquet's optional PageIndex.
    // When both are non-null we can fetch exactly the pages we need with
    // individual range requests instead of the whole column chunk.
    val offsetIndexOffset: Long? = null,
    val offsetIndexLength: Int? = null,
)

/** One entry of a column chunk's OffsetIndex — one Parquet data page. */
data class PPageLocation(val offset: Long, val compressedPageSize: Int, val firstRowIndex: Long)

data class PRowGroup(val columns: List<PColumnChunk>, val numRows: Long)

data class PSchemaLeaf(val name: String, val type: Int, val repetitionType: Int)

data class PFileMeta(val schemaLeavesByName: Map<String, PSchemaLeaf>, val rowGroups: List<PRowGroup>, val numRows: Long)

object ParquetMeta {

    fun parseFileMetaData(bytes: ByteArray): PFileMeta {
        val root = ThriftCompactReader(bytes).readStruct().fields

        val schemaList = (root[2] as? TVal.TList)?.v.orEmpty()
        val leaves = LinkedHashMap<String, PSchemaLeaf>()
        for (elem in schemaList) {
            val f = elem.asStruct()
            val name = f[4]?.asBinaryString() ?: continue
            val typeField = f[1] // absent on non-leaf (group) nodes
            if (typeField != null) {
                val repetition = f[3]?.asI32() ?: PRepetition.REQUIRED
                leaves[name] = PSchemaLeaf(name, typeField.asI32(), repetition)
            }
        }

        val rowGroupList = (root[4] as? TVal.TList)?.v.orEmpty()
        val rowGroups = rowGroupList.map { rg ->
            val rgFields = rg.asStruct()
            val cols = (rgFields[1] as? TVal.TList)?.v.orEmpty().map { parseColumnChunk(it.asStruct()) }
            PRowGroup(columns = cols, numRows = rgFields[3]?.asI64() ?: 0L)
        }

        return PFileMeta(
            schemaLeavesByName = leaves,
            rowGroups = rowGroups,
            numRows = root[3]?.asI64() ?: 0L,
        )
    }

    private fun parseColumnChunk(fields: Map<Int, TVal>): PColumnChunk {
        val fileOffset = fields[2]?.asI64() ?: 0L
        val metaFields = (fields[3] ?: throw ThriftDecodeException("ColumnChunk missing meta_data")).asStruct()
        val meta = PColumnMeta(
            type = metaFields[1]!!.asI32(),
            encodings = (metaFields[2] as? TVal.TList)?.v.orEmpty().map { it.asI32() },
            pathInSchema = (metaFields[3] as? TVal.TList)?.v.orEmpty().map { it.asBinaryString() },
            codec = metaFields[4]!!.asI32(),
            numValues = metaFields[5]?.asI64() ?: 0L,
            totalUncompressedSize = metaFields[6]?.asI64() ?: 0L,
            totalCompressedSize = metaFields[7]?.asI64() ?: 0L,
            dataPageOffset = metaFields[9]!!.asI64(),
            dictionaryPageOffset = metaFields[11]?.asI64(),
        )
        return PColumnChunk(
            fileOffset = fileOffset,
            meta = meta,
            offsetIndexOffset = fields[4]?.asI64(),
            offsetIndexLength = fields[5]?.asI32(),
        )
    }

    /**
     * Parquet's optional per-chunk PageIndex (OffsetIndex half): one
     * (offset, size, first_row_index) triple per data page in the chunk,
     * itself tiny (a few bytes per page) and fetched with its own range
     * request. Lets a caller issue one range GET per page it actually
     * wants instead of downloading the whole column chunk up front — the
     * same lazy, page-granular I/O DuckDB's httpfs uses when a PageIndex
     * is present.
     */
    fun parseOffsetIndex(bytes: ByteArray): List<PPageLocation> {
        val root = ThriftCompactReader(bytes).readStruct().fields
        val locs = (root[1] as? TVal.TList)?.v.orEmpty()
        return locs.map { loc ->
            val f = loc.asStruct()
            PPageLocation(
                offset = f[1]!!.asI64(),
                compressedPageSize = f[2]!!.asI32(),
                firstRowIndex = f[3]!!.asI64(),
            )
        }
    }

    // --- Page header (parsed per-page, not once per file) ---

    data class PageHeader(
        val pageType: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val numValues: Int,      // data page: DataPageHeader.num_values; dictionary page: DictionaryPageHeader.num_values
        val encoding: Int,       // data page: DataPageHeader.encoding; dictionary page: DictionaryPageHeader.encoding
        val headerLength: Int,   // bytes the header itself occupied, so caller can advance the cursor
    )

    fun parsePageHeader(bytes: ByteArray, offset: Int): PageHeader {
        val reader = ThriftCompactReader(bytes, offset)
        val fields = reader.readStruct().fields
        val pageType = fields[1]!!.asI32()
        val uncompressedSize = fields[2]!!.asI32()
        val compressedSize = fields[3]!!.asI32()

        var numValues = 0
        var encoding = PEncoding.PLAIN
        when (pageType) {
            PPageType.DATA_PAGE -> {
                val dph = fields[5]!!.asStruct()
                numValues = dph[1]!!.asI32()
                encoding = dph[2]!!.asI32()
            }
            PPageType.DICTIONARY_PAGE -> {
                val dph = fields[7]!!.asStruct()
                numValues = dph[1]!!.asI32()
                encoding = dph[2]?.asI32() ?: PEncoding.PLAIN
            }
            PPageType.DATA_PAGE_V2 -> {
                val dph = fields[8]!!.asStruct()
                numValues = dph[1]!!.asI32()
                encoding = dph[4]!!.asI32() // DataPageHeaderV2.encoding is field 4
            }
            else -> { /* index pages: not needed for a full scan, left at defaults */ }
        }

        return PageHeader(
            pageType = pageType,
            compressedSize = compressedSize,
            uncompressedSize = uncompressedSize,
            numValues = numValues,
            encoding = encoding,
            headerLength = reader.pos - offset,
        )
    }
}
