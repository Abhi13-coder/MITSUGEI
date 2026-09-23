package com.mitsugei.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Adaptive CPU-instruction-level integer list encoder.
 *
 * Restores the dropped Python `storage/encoding.py` (~235 lines) behaviour:
 * for sorted position lists (or any non-negative integer sequences) try, in order:
 *
 *   1. RAW          – identity (4-byte little-endian ints) when compression loses
 *   2. DELTA        – successive differences, then bit-pack
 *   3. AFFINE       – frame-of-reference (subtract min) + bit-pack  (a.k.a. FOR)
 *   4. DELTA+AFFINE – deltas then FOR on the deltas
 *   5. DICT         – dictionary of unique values + bit-packed indices
 *   6. BITPACK      – plain bit-pack of the original values
 *
 * The encoder measures the resulting byte length of every candidate and keeps
 * the shortest.  If nothing beats RAW it stores RAW.  The first byte of the
 * blob is always a codec tag so the decoder is unambiguous.
 *
 * All bit operations are pure Kotlin integer shifts / masks – no floating
 * point, no external native libraries – so they map directly onto CPU
 * bit-manipulation instructions.
 */
object AdaptiveEncoder {

    // ------------------------------------------------------------------ codec tags
    const val TAG_RAW: Byte          = 0x00
    const val TAG_DELTA_BITPACK: Byte = 0x01
    const val TAG_AFFINE_BITPACK: Byte = 0x02
    const val TAG_DELTA_AFFINE: Byte  = 0x03
    const val TAG_DICT: Byte          = 0x04
    const val TAG_BITPACK: Byte       = 0x05

    // ------------------------------------------------------------------ public API

    /**
     * Encode a list of non-negative integers (positions must already be sorted
     * ascending).  Returns a compact byte array whose first byte is the codec tag.
     * Empty input → empty output.
     */
    fun encode(values: IntArray): ByteArray {
        if (values.isEmpty()) return ByteArray(0)
        require(values.all { it >= 0 }) { "AdaptiveEncoder only accepts non-negative integers" }

        val candidates = mutableListOf<ByteArray>()

        // 1. RAW
        candidates += encodeRaw(values)

        // 2. DELTA + bit-pack
        runCatching { candidates += encodeDeltaBitpack(values) }

        // 3. AFFINE (FOR) + bit-pack
        runCatching { candidates += encodeAffineBitpack(values) }

        // 4. DELTA then AFFINE
        runCatching { candidates += encodeDeltaAffine(values) }

        // 5. DICTIONARY
        runCatching { candidates += encodeDict(values) }

        // 6. plain bit-pack
        runCatching { candidates += encodeBitpack(values) }

        return candidates.minBy { it.size }
    }

    fun encode(values: List<Int>): ByteArray = encode(values.toIntArray())

    /**
     * Decode a blob produced by [encode].  Empty blob → empty list.
     */
    fun decode(blob: ByteArray): IntArray {
        if (blob.isEmpty()) return IntArray(0)
        return when (blob[0]) {
            TAG_RAW           -> decodeRaw(blob)
            TAG_DELTA_BITPACK -> decodeDeltaBitpack(blob)
            TAG_AFFINE_BITPACK -> decodeAffineBitpack(blob)
            TAG_DELTA_AFFINE  -> decodeDeltaAffine(blob)
            TAG_DICT          -> decodeDict(blob)
            TAG_BITPACK       -> decodeBitpack(blob)
            else -> throw IllegalArgumentException("unknown AdaptiveEncoder tag 0x${blob[0].toString(16)}")
        }
    }

    /**
     * Cheap metadata that can be read *without* full decompression.
     * Used by BitRanker to score history on compressed bits only.
     */
    data class BitMeta(
        val codec: Byte,
        val count: Int,
        val bitWidth: Int,          // 0 for RAW / DICT
        val minValue: Int,          // meaningful for AFFINE family
        val maxDelta: Int,          // meaningful for DELTA family
        val uniqueCount: Int,       // meaningful for DICT
        val compressedBytes: Int,
    )

    fun inspect(blob: ByteArray): BitMeta {
        if (blob.isEmpty()) return BitMeta(TAG_RAW, 0, 0, 0, 0, 0, 0)
        val tag = blob[0]
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(1)
        return when (tag) {
            TAG_RAW -> {
                val count = (blob.size - 1) / 4
                BitMeta(tag, count, 32, 0, 0, count, blob.size)
            }
            TAG_DELTA_BITPACK, TAG_BITPACK -> {
                val count = bb.int
                val bitWidth = bb.get().toInt() and 0xFF
                BitMeta(tag, count, bitWidth, 0, (1 shl bitWidth) - 1, count, blob.size)
            }
            TAG_AFFINE_BITPACK -> {
                val count = bb.int
                val minV = bb.int
                val bitWidth = bb.get().toInt() and 0xFF
                BitMeta(tag, count, bitWidth, minV, (1 shl bitWidth) - 1, count, blob.size)
            }
            TAG_DELTA_AFFINE -> {
                val count = bb.int
                val first = bb.int
                val minDelta = bb.int
                val bitWidth = bb.get().toInt() and 0xFF
                BitMeta(tag, count, bitWidth, first, minDelta + ((1 shl bitWidth) - 1), count, blob.size)
            }
            TAG_DICT -> {
                val count = bb.int
                val uniq = bb.int
                val idxBits = bb.get().toInt() and 0xFF
                BitMeta(tag, count, idxBits, 0, 0, uniq, blob.size)
            }
            else -> BitMeta(tag, 0, 0, 0, 0, 0, blob.size)
        }
    }

    // ------------------------------------------------------------------ RAW

    private fun encodeRaw(values: IntArray): ByteArray {
        val out = ByteArray(1 + values.size * 4)
        out[0] = TAG_RAW
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(1)
        for (v in values) bb.putInt(v)
        return out
    }

    private fun decodeRaw(blob: ByteArray): IntArray {
        val count = (blob.size - 1) / 4
        val out = IntArray(count)
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(1)
        for (i in 0 until count) out[i] = bb.int
        return out
    }

    // ------------------------------------------------------------------ helpers

    private fun bitWidthFor(maxVal: Int): Int {
        if (maxVal <= 0) return 1
        var w = 0
        var v = maxVal
        while (v > 0) { w++; v = v ushr 1 }
        return w.coerceAtLeast(1).coerceAtMost(32)
    }

    private fun packBits(values: IntArray, bitWidth: Int): ByteArray {
        if (values.isEmpty()) return ByteArray(0)
        val totalBits = values.size.toLong() * bitWidth
        val byteLen = ((totalBits + 7) / 8).toInt()
        val out = ByteArray(byteLen)
        var bitPos = 0L
        for (v in values) {
            var remaining = bitWidth
            var valBits = v
            while (remaining > 0) {
                val byteIdx = (bitPos / 8).toInt()
                val bitInByte = (bitPos % 8).toInt()
                val space = 8 - bitInByte
                val take = minOf(remaining, space)
                val mask = (1 shl take) - 1
                val bits = valBits and mask
                out[byteIdx] = (out[byteIdx].toInt() or (bits shl bitInByte)).toByte()
                valBits = valBits ushr take
                remaining -= take
                bitPos += take
            }
        }
        return out
    }

    private fun unpackBits(data: ByteArray, offset: Int, count: Int, bitWidth: Int): IntArray {
        val out = IntArray(count)
        var bitPos = 0L
        for (i in 0 until count) {
            var value = 0
            var remaining = bitWidth
            var shift = 0
            while (remaining > 0) {
                val byteIdx = offset + (bitPos / 8).toInt()
                val bitInByte = (bitPos % 8).toInt()
                val space = 8 - bitInByte
                val take = minOf(remaining, space)
                val mask = (1 shl take) - 1
                val bits = (data[byteIdx].toInt() ushr bitInByte) and mask
                value = value or (bits shl shift)
                remaining -= take
                shift += take
                bitPos += take
            }
            out[i] = value
        }
        return out
    }

    // ------------------------------------------------------------------ DELTA + bit-pack

    private fun encodeDeltaBitpack(values: IntArray): ByteArray {
        val deltas = IntArray(values.size)
        deltas[0] = values[0]
        var maxD = values[0]
        for (i in 1 until values.size) {
            val d = values[i] - values[i - 1]
            require(d >= 0) { "positions must be non-decreasing for delta" }
            deltas[i] = d
            if (d > maxD) maxD = d
        }
        val bw = bitWidthFor(maxD)
        val packed = packBits(deltas, bw)
        val header = ByteBuffer.allocate(1 + 4 + 1).order(ByteOrder.LITTLE_ENDIAN)
        header.put(TAG_DELTA_BITPACK)
        header.putInt(values.size)
        header.put(bw.toByte())
        return header.array() + packed
    }

    private fun decodeDeltaBitpack(blob: ByteArray): IntArray {
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(1)
        val count = bb.int
        val bw = bb.get().toInt() and 0xFF
        val deltas = unpackBits(blob, bb.position(), count, bw)
        val out = IntArray(count)
        out[0] = deltas[0]
        for (i in 1 until count) out[i] = out[i - 1] + deltas[i]
        return out
    }

    // ------------------------------------------------------------------ AFFINE (FOR) + bit-pack

    private fun encodeAffineBitpack(values: IntArray): ByteArray {
        var minV = values[0]
        var maxV = values[0]
        for (v in values) {
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val range = maxV - minV
        val bw = bitWidthFor(range)
        val shifted = IntArray(values.size) { values[it] - minV }
        val packed = packBits(shifted, bw)
        val header = ByteBuffer.allocate(1 + 4 + 4 + 1).order(ByteOrder.LITTLE_ENDIAN)
        header.put(TAG_AFFINE_BITPACK)
        header.putInt(values.size)
        header.putInt(minV)
        header.put(bw.toByte())
        return header.array() + packed
    }

    private fun decodeAffineBitpack(blob: ByteArray): IntArray {
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(1)
        val count = bb.int
        val minV = bb.int
        val bw = bb.get().toInt() and 0xFF
        val shifted = unpackBits(blob, bb.position(), count, bw)
        return IntArray(count) { shifted[it] + minV }
    }

    // ------------------------------------------------------------------ DELTA + AFFINE

    private fun encodeDeltaAffine(values: IntArray): ByteArray {
        if (values.size == 1) return encodeAffineBitpack(values)
        val deltas = IntArray(values.size - 1)
        var minD = Int.MAX_VALUE
        var maxD = Int.MIN_VALUE
        for (i in 1 until values.size) {
            val d = values[i] - values[i - 1]
            require(d >= 0)
            deltas[i - 1] = d
            if (d < minD) minD = d
            if (d > maxD) maxD = d
        }
        val range = maxD - minD
        val bw = bitWidthFor(range)
        val shifted = IntArray(deltas.size) { deltas[it] - minD }
        val packed = packBits(shifted, bw)
        val header = ByteBuffer.allocate(1 + 4 + 4 + 4 + 1).order(ByteOrder.LITTLE_ENDIAN)
        header.put(TAG_DELTA_AFFINE)
        header.putInt(values.size)
        header.putInt(values[0])
        header.putInt(minD)
        header.put(bw.toByte())
        return header.array() + packed
    }

    private fun decodeDeltaAffine(blob: ByteArray): IntArray {
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(1)
        val count = bb.int
        val first = bb.int
        val minD = bb.int
        val bw = bb.get().toInt() and 0xFF
        if (count == 1) return intArrayOf(first)
        val shifted = unpackBits(blob, bb.position(), count - 1, bw)
        val out = IntArray(count)
        out[0] = first
        for (i in 1 until count) out[i] = out[i - 1] + shifted[i - 1] + minD
        return out
    }

    // ------------------------------------------------------------------ DICT

    private fun encodeDict(values: IntArray): ByteArray {
        val uniques = values.toSortedSet().toList()
        if (uniques.size > 65536) throw IllegalArgumentException("dict too large")
        val indexOf = HashMap<Int, Int>(uniques.size)
        uniques.forEachIndexed { i, v -> indexOf[v] = i }
        val indices = IntArray(values.size) { indexOf[values[it]]!! }
        val idxBits = bitWidthFor(uniques.size - 1)
        val packedIdx = packBits(indices, idxBits)

        val baos = ByteArrayOutputStream()
        baos.write(TAG_DICT.toInt())
        val hdr = ByteBuffer.allocate(4 + 4 + 1).order(ByteOrder.LITTLE_ENDIAN)
        hdr.putInt(values.size)
        hdr.putInt(uniques.size)
        hdr.put(idxBits.toByte())
        baos.write(hdr.array())
        // store dictionary as raw 4-byte ints
        val dictBuf = ByteBuffer.allocate(uniques.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (u in uniques) dictBuf.putInt(u)
        baos.write(dictBuf.array())
        baos.write(packedIdx)
        return baos.toByteArray()
    }

    private fun decodeDict(blob: ByteArray): IntArray {
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(1)
        val count = bb.int
        val uniq = bb.int
        val idxBits = bb.get().toInt() and 0xFF
        val dict = IntArray(uniq)
        for (i in 0 until uniq) dict[i] = bb.int
        val indices = unpackBits(blob, bb.position(), count, idxBits)
        return IntArray(count) { dict[indices[it]] }
    }

    // ------------------------------------------------------------------ plain BITPACK

    private fun encodeBitpack(values: IntArray): ByteArray {
        var maxV = 0
        for (v in values) if (v > maxV) maxV = v
        val bw = bitWidthFor(maxV)
        val packed = packBits(values, bw)
        val header = ByteBuffer.allocate(1 + 4 + 1).order(ByteOrder.LITTLE_ENDIAN)
        header.put(TAG_BITPACK)
        header.putInt(values.size)
        header.put(bw.toByte())
        return header.array() + packed
    }

    private fun decodeBitpack(blob: ByteArray): IntArray {
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(1)
        val count = bb.int
        val bw = bb.get().toInt() and 0xFF
        return unpackBits(blob, bb.position(), count, bw)
    }
}
