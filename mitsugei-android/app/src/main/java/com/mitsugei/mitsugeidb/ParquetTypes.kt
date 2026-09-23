package com.mitsugei.mitsugeidb

/** Physical storage type (parquet.thrift `Type`). Only what we decode is enumerated by value. */
object PType {
    const val BOOLEAN = 0
    const val INT32 = 1
    const val INT64 = 2
    const val INT96 = 3   // deprecated legacy timestamp
    const val FLOAT = 4
    const val DOUBLE = 5
    const val BYTE_ARRAY = 6
    const val FIXED_LEN_BYTE_ARRAY = 7
}

/** parquet.thrift `Encoding`. */
object PEncoding {
    const val PLAIN = 0
    const val GROUP_VAR_INT = 1 // deprecated, never emitted by modern writers
    const val PLAIN_DICTIONARY = 2
    const val RLE = 3
    const val BIT_PACKED = 4 // deprecated
    const val DELTA_BINARY_PACKED = 5
    const val DELTA_LENGTH_BYTE_ARRAY = 6
    const val DELTA_BYTE_ARRAY = 7
    const val RLE_DICTIONARY = 8
    const val BYTE_STREAM_SPLIT = 9

    fun isDictionary(e: Int) = e == PLAIN_DICTIONARY || e == RLE_DICTIONARY
}

/** parquet.thrift `CompressionCodec`. */
object PCodec {
    const val UNCOMPRESSED = 0
    const val SNAPPY = 1
    const val GZIP = 2
    const val LZO = 3
    const val BROTLI = 4
    const val LZ4 = 5
    const val ZSTD = 6
    const val LZ4_RAW = 7
}

/** parquet.thrift `PageType`. */
object PPageType {
    const val DATA_PAGE = 0
    const val INDEX_PAGE = 1
    const val DICTIONARY_PAGE = 2
    const val DATA_PAGE_V2 = 3
}

object PRepetition {
    const val REQUIRED = 0
    const val OPTIONAL = 1
    const val REPEATED = 2
}
