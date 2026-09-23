package com.mitsugei.mitsugeidb

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

class UnsupportedCodecException(codec: Int) : Exception("unsupported Parquet codec id=$codec")

/**
 * Decompresses one page's on-wire bytes. `uncompressedSize` comes straight
 * from the page header (PageHeader.uncompressed_page_size) — Snappy's raw
 * block format doesn't self-describe length the way its streaming/framed
 * format does, and Zstd's one-shot decompress wants a destination size, so
 * both need it up front rather than guessing/growing a buffer.
 *
 * Requires (declared in app/build.gradle.kts):
 *   org.xerial:snappy-java   — ships armeabi-v7a + arm64-v8a native libs
 *   com.github.luben:zstd-jni — same, prebuilt for 32-bit ARM
 */
object Compression {
    fun decompress(codec: Int, compressed: ByteArray, uncompressedSize: Int): ByteArray {
        if (compressed.size == uncompressedSize && codec == PCodec.UNCOMPRESSED) return compressed
        return when (codec) {
            PCodec.UNCOMPRESSED -> compressed
            PCodec.SNAPPY -> org.xerial.snappy.Snappy.uncompress(compressed)
            PCodec.ZSTD -> com.github.luben.zstd.Zstd.decompress(compressed, uncompressedSize)
            PCodec.GZIP -> {
                val out = ByteArrayOutputStream(uncompressedSize)
                GZIPInputStream(compressed.inputStream()).use { it.copyTo(out) }
                out.toByteArray()
            }
            else -> throw UnsupportedCodecException(codec)
        }
    }
}
