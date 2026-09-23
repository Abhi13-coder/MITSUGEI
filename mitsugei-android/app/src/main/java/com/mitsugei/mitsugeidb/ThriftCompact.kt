package com.mitsugei.mitsugeidb

/**
 * Minimal Apache Thrift "Compact Protocol" reader.
 *
 * Parquet's footer (FileMetaData) and every page header are Thrift structs
 * serialized with this protocol. We don't have (and don't need) the full
 * Thrift runtime — Parquet only uses structs, lists, and the primitive
 * scalar types, so a small generic decoder that returns everything as a
 * field-id -> value map is enough, and it's schema-agnostic: it doesn't
 * need a generated class per struct, just the field IDs documented in
 * parquet.thrift (https://github.com/apache/parquet-format/blob/master/src/main/thrift/parquet.thrift),
 * which are stable across the format's lifetime.
 *
 * Compact-protocol type codes (TCompactProtocol.Types):
 *   STOP=0x00 BOOLEAN_TRUE=0x01 BOOLEAN_FALSE=0x02 BYTE=0x03 I16=0x04
 *   I32=0x05 I64=0x06 DOUBLE=0x07 BINARY=0x08 LIST=0x09 SET=0x0A
 *   MAP=0x0B STRUCT=0x0C
 *
 * NOT implemented (Parquet metadata never uses these): typedefs, unions,
 * exceptions, i8-in-map-key edge cases beyond what's below. If a future
 * Parquet writer emits something outside parquet.thrift's current shape,
 * this will throw ThriftDecodeException rather than silently mis-parse —
 * intentional; a wrong field is worse than a loud failure.
 */
class ThriftDecodeException(msg: String) : Exception(msg)

sealed class TVal {
    data class TBool(val v: Boolean) : TVal()
    data class TByte(val v: Int) : TVal()
    data class TI16(val v: Int) : TVal()
    data class TI32(val v: Int) : TVal()
    data class TI64(val v: Long) : TVal()
    data class TDouble(val v: Double) : TVal()
    data class TBinary(val v: ByteArray) : TVal()
    data class TList(val v: List<TVal>) : TVal()
    data class TMap(val v: List<Pair<TVal, TVal>>) : TVal()
    data class TStruct(val fields: Map<Int, TVal>) : TVal()
}

/** Reads compact-protocol values from a byte array, tracking a cursor. */
class ThriftCompactReader(private val buf: ByteArray, start: Int = 0) {
    var pos: Int = start
        private set

    private fun byte(): Int {
        if (pos >= buf.size) throw ThriftDecodeException("unexpected EOF at $pos")
        return buf[pos++].toInt() and 0xFF
    }

    /** Thrift's "unsigned LEB128" varint (used for i32/i64 magnitudes after zigzag, and for sizes). */
    private fun readVarint64(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = byte()
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
            if (shift > 63) throw ThriftDecodeException("varint too long")
        }
        return result
    }

    private fun zigzagToLong(n: Long): Long = (n ushr 1) xor -(n and 1)

    private fun readZigzagI32(): Int = zigzagToLong(readVarint64()).toInt()
    private fun readZigzagI64(): Long = zigzagToLong(readVarint64())

    private fun readDouble(): Double {
        var bits = 0L
        // Thrift compact protocol writes doubles little-endian, 8 bytes.
        for (i in 0 until 8) bits = bits or (byte().toLong() shl (8 * i))
        return java.lang.Double.longBitsToDouble(bits)
    }

    private fun readBinary(): ByteArray {
        val len = readVarint64().toInt()
        if (len < 0 || pos + len > buf.size) throw ThriftDecodeException("bad binary length $len at $pos")
        val out = buf.copyOfRange(pos, pos + len)
        pos += len
        return out
    }

    /** compact element-type code -> generic value reader, for list/set/map elements. */
    private fun readValueOfType(typeCode: Int): TVal = when (typeCode) {
        0x01 -> TVal.TBool(byte() != 0x00) // bool-as-list-element: one byte, nonzero-ish; Parquet writers use 0x01/0x02
        0x02 -> TVal.TBool(false)          // not actually reachable standalone; kept for completeness
        0x03 -> TVal.TByte(byte().toByte().toInt())
        0x04 -> TVal.TI16(readZigzagI32())
        0x05 -> TVal.TI32(readZigzagI32())
        0x06 -> TVal.TI64(readZigzagI64())
        0x07 -> TVal.TDouble(readDouble())
        0x08 -> TVal.TBinary(readBinary())
        0x09, 0x0A -> readListOrSet()
        0x0B -> readMap()
        0x0C -> readStruct()
        else -> throw ThriftDecodeException("unsupported element type code 0x${typeCode.toString(16)}")
    }

    private fun readListOrSet(): TVal.TList {
        val header = byte()
        val elemType = header and 0x0F
        var size = (header ushr 4) and 0x0F
        if (size == 0x0F) size = readVarint64().toInt()
        val items = ArrayList<TVal>(size)
        repeat(size) { items.add(readValueOfType(elemType)) }
        return TVal.TList(items)
    }

    private fun readMap(): TVal.TMap {
        val size = readVarint64().toInt()
        if (size == 0) return TVal.TMap(emptyList())
        val typesByte = byte()
        val keyType = (typesByte ushr 4) and 0x0F
        val valType = typesByte and 0x0F
        val out = ArrayList<Pair<TVal, TVal>>(size)
        repeat(size) { out.add(readValueOfType(keyType) to readValueOfType(valType)) }
        return TVal.TMap(out)
    }

    /** Reads one struct: a sequence of field headers + values, terminated by STOP (0x00). */
    fun readStruct(): TVal.TStruct {
        val fields = HashMap<Int, TVal>()
        var lastFieldId = 0
        while (true) {
            val header = byte()
            if (header == 0x00) break // STOP
            val typeCode = header and 0x0F
            val delta = (header ushr 4) and 0x0F
            val fieldId = if (delta == 0) readZigzagI32() else lastFieldId + delta
            lastFieldId = fieldId

            val value: TVal = when (typeCode) {
                0x01 -> TVal.TBool(true)
                0x02 -> TVal.TBool(false)
                0x03 -> TVal.TByte(byte().toByte().toInt())
                0x04 -> TVal.TI16(readZigzagI32())
                0x05 -> TVal.TI32(readZigzagI32())
                0x06 -> TVal.TI64(readZigzagI64())
                0x07 -> TVal.TDouble(readDouble())
                0x08 -> TVal.TBinary(readBinary())
                0x09, 0x0A -> readListOrSet()
                0x0B -> readMap()
                0x0C -> readStruct()
                else -> throw ThriftDecodeException("unsupported field type code 0x${typeCode.toString(16)} for field $fieldId")
            }
            fields[fieldId] = value
        }
        return TVal.TStruct(fields)
    }
}

// --- small accessor helpers, since Parquet metadata is deeply nested ---
fun TVal.asStruct(): Map<Int, TVal> = (this as? TVal.TStruct)?.fields
    ?: throw ThriftDecodeException("expected struct, got $this")
fun TVal.asList(): List<TVal> = (this as? TVal.TList)?.v
    ?: throw ThriftDecodeException("expected list, got $this")
fun TVal.asI32(): Int = when (this) {
    is TVal.TI32 -> v; is TVal.TI16 -> v; is TVal.TByte -> v
    else -> throw ThriftDecodeException("expected int, got $this")
}
fun TVal.asI64(): Long = when (this) {
    is TVal.TI64 -> v; is TVal.TI32 -> v.toLong(); is TVal.TI16 -> v.toLong()
    else -> throw ThriftDecodeException("expected long, got $this")
}
fun TVal.asBinaryString(): String = (this as? TVal.TBinary)?.let { String(it.v, Charsets.UTF_8) }
    ?: throw ThriftDecodeException("expected binary/string, got $this")
