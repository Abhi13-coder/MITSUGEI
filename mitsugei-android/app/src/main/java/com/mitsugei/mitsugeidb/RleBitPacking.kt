package com.mitsugei.mitsugeidb

/**
 * Parquet's "RLE / Bit-Packed Hybrid" encoding
 * (https://parquet.apache.org/docs/file-format/data-pages/encodings/#RLE).
 *
 * Used in two places we care about:
 *  1. Definition levels in a DataPageV1 (int32-LE length prefix, then the
 *     hybrid stream) — tells us which of `num_values` slots are actually
 *     present vs. null, for OPTIONAL (nullable) columns.
 *  2. Dictionary indices in a PLAIN_DICTIONARY / RLE_DICTIONARY data page
 *     (one bit-width byte, then the hybrid stream with NO length prefix —
 *     it just runs to the end of the page).
 */
object RleBitPacking {

    class Result(val values: IntArray, val bytesConsumed: Int)

    /**
     * Decodes up to [maxCount] values from a hybrid RLE/bit-packed stream
     * starting at [offset] in [data], never reading past [limit] (exclusive).
     * Stops once [maxCount] values are produced.
     */
    fun decode(data: ByteArray, offset: Int, limit: Int, bitWidth: Int, maxCount: Int): Result {
        val out = IntArray(maxCount)
        var produced = 0
        var pos = offset

        if (bitWidth == 0) {
            // Domain of size <= 1 -> every value is index/level 0, no bytes encoded.
            return Result(IntArray(maxCount), 0)
        }

        while (produced < maxCount && pos < limit) {
            val (header, headerBytes) = readUnsignedVarint(data, pos)
            pos += headerBytes
            if (header and 1L == 1L) {
                // bit-packed-run: header>>1 = number of groups of 8 values
                val groups = (header ushr 1).toInt()
                val count = groups * 8
                val byteLen = (count * bitWidth + 7) / 8
                val take = minOf(count, maxCount - produced)
                unpackGroup(data, pos, bitWidth, take, out, produced)
                produced += take
                pos += byteLen
            } else {
                // rle-run: header>>1 = repeat count (actual value count, not groups)
                val runLen = (header ushr 1).toInt()
                val valueByteLen = (bitWidth + 7) / 8
                var value = 0L
                for (i in 0 until valueByteLen) {
                    value = value or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                }
                pos += valueByteLen
                val take = minOf(runLen, maxCount - produced)
                for (i in 0 until take) out[produced + i] = value.toInt()
                produced += take
            }
        }
        return Result(out, pos - offset)
    }

    /** Unpacks `count` (<=  groupCountOf8*8) values of `bitWidth` bits each, LSB-first, into out[outOffset..]. */
    private fun unpackGroup(data: ByteArray, start: Int, bitWidth: Int, count: Int, out: IntArray, outOffset: Int) {
        var bitBuf = 0L
        var bitsInBuf = 0
        var bytePos = start
        var produced = 0
        while (produced < count) {
            while (bitsInBuf < bitWidth) {
                bitBuf = bitBuf or ((data[bytePos].toLong() and 0xFF) shl bitsInBuf)
                bytePos++
                bitsInBuf += 8
            }
            val mask = (1L shl bitWidth) - 1
            out[outOffset + produced] = (bitBuf and mask).toInt()
            bitBuf = bitBuf ushr bitWidth
            bitsInBuf -= bitWidth
            produced++
        }
    }

    private fun readUnsignedVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = offset
        while (true) {
            val b = data[pos].toInt() and 0xFF
            pos++
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to (pos - offset)
    }

    /** ceil(log2(n)) — the bit width needed to represent values [0, n). */
    fun bitWidthFor(n: Int): Int {
        if (n <= 1) return 0
        var w = 0
        var v = n - 1
        while (v > 0) { w++; v = v shr 1 }
        return w
    }
}
