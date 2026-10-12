package info.appletvremote.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Limited, bounded OPACK implementation for Companion's dictionaries. */
internal object Opack {
    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(n: Long, size: Int) { repeat(size) { out.write((n ushr (it * 8)).toInt() and 255) } }
        fun blob(bytes: ByteArray, base: Int) {
            if (bytes.size <= 32) out.write(base + bytes.size)
            else if (bytes.size <= 255) { out.write(base + 33); le(bytes.size.toLong(), 1) }
            else { out.write(base + 34); le(bytes.size.toLong(), 2) }
            require(bytes.size <= 65535); out.write(bytes)
        }
        fun emit(v: Any?) {
            when (v) {
                null -> out.write(4)
                is Boolean -> out.write(if (v) 1 else 2)
                is Float -> { out.write(0x35); le(java.lang.Float.floatToRawIntBits(v).toLong(), 4) }
                is Double -> { out.write(0x36); le(java.lang.Double.doubleToRawLongBits(v), 8) }
                is Number -> { val n = v.toLong(); when {
                    n in 0..39 -> out.write(n.toInt() + 8)
                    n in 0..255 -> { out.write(0x30); le(n, 1) }
                    n in 0..65535 -> { out.write(0x31); le(n, 2) }
                    n in 0..0xffffffffL -> { out.write(0x32); le(n, 4) }
                    else -> { out.write(0x33); le(n, 8) }
                } }
                is String -> blob(v.toByteArray(Charsets.UTF_8), 0x40)
                is ByteArray -> blob(v, 0x70)
                is Map<*, *> -> { out.write(0xe0 + minOf(v.size, 15)); v.forEach { (k, x) -> emit(k); emit(x) }; if(v.size >= 15) out.write(3) }
                is List<*> -> { out.write(0xd0 + minOf(v.size, 15)); v.forEach(::emit); if(v.size >= 15) out.write(3) }
                else -> error("Unsupported OPACK value")
            }
        }
        emit(value); return out.toByteArray()
    }
    fun decode(bytes: ByteArray): Any? {
        var pos = 0
        val objects = mutableListOf<Any?>()
        fun take(n: Int): ByteArray { require(n >= 0 && n <= bytes.size - pos) { "Truncated OPACK" }; return bytes.copyOfRange(pos, pos+n).also { pos+=n } }
        fun byte() = take(1)[0].toInt() and 255
        fun le(n: Int): Long { var v=0L; take(n).forEachIndexed { i,b -> v=v or ((b.toLong() and 255) shl (8*i)) }; return v }
        fun length(n: Int): Int { val v=le(n); require(v in 0..1048576); return v.toInt() }
        fun read(depth: Int): Any? {
            require(depth < 48) { "OPACK nesting too deep" }
            val tag=byte(); var cache=true
            val value: Any? = when(tag) {
                1 -> true.also { cache=false }; 2 -> false.also { cache=false }; 4 -> null.also { cache=false }
                5 -> take(16); 6 -> le(8)
                in 8..47 -> (tag-8).toLong().also { cache=false }
                in 0x30..0x33 -> le(1 shl (tag-0x30))
                0x35 -> ByteBuffer.wrap(take(4)).order(ByteOrder.LITTLE_ENDIAN).float
                0x36 -> ByteBuffer.wrap(take(8)).order(ByteOrder.LITTLE_ENDIAN).double
                in 0x40..0x60 -> take(tag-0x40).toString(Charsets.UTF_8)
                in 0x61..0x64 -> take(length(tag-0x60)).toString(Charsets.UTF_8)
                in 0x70..0x90 -> take(tag-0x70)
                in 0x91..0x94 -> take(length(1 shl (tag-0x91)))
                in 0xa0..0xc0 -> { cache=false; objects.getOrElse(tag-0xa0) { error("Invalid OPACK reference") } }
                in 0xc1..0xc4 -> { cache=false; objects.getOrElse(length(tag-0xc0)) { error("Invalid OPACK reference") } }
                in 0xd0..0xdf -> { cache=false; val list=mutableListOf<Any?>(); val count=tag and 15
                    if(count<15) repeat(count) { list.add(read(depth+1)) } else { while(pos<bytes.size && bytes[pos].toInt()!=3) list.add(read(depth+1)); require(byte()==3) }; list }
                in 0xe0..0xef -> { cache=false; val map=linkedMapOf<String,Any?>(); val count=tag and 15
                    fun pair() { val k=read(depth+1) as? String ?: error("Invalid OPACK dictionary key"); map[k]=read(depth+1) }
                    if(count<15) repeat(count) { pair() } else { while(pos<bytes.size && bytes[pos].toInt()!=3) pair(); require(byte()==3) }; map }
                else -> error("Unsupported OPACK tag")
            }
            if(cache && objects.none { if(it is ByteArray && value is ByteArray) it.contentEquals(value) else it==value }) objects.add(value)
            return value
        }
        return read(0).also { require(pos==bytes.size) { "Trailing OPACK bytes" } }
    }
}
internal object Tlv {
    fun encode(vararg fields: Pair<Int,ByteArray>): ByteArray {
        val out=ByteArrayOutputStream()
        for((tag,value) in fields) {
            if(value.isEmpty()) { out.write(tag); out.write(0) }
            var offset=0
            while(offset<value.size) { val count=minOf(255,value.size-offset); out.write(tag); out.write(count); out.write(value,offset,count); offset+=count }
        }
        return out.toByteArray()
    }
    fun decode(bytes: ByteArray): Map<Int,ByteArray> {
        val out=linkedMapOf<Int,ByteArray>(); var pos=0
        while(pos<bytes.size) { require(pos+2<=bytes.size); val tag=bytes[pos++].toInt() and 255; val n=bytes[pos++].toInt() and 255; require(n<=bytes.size-pos); out[tag]=(out[tag]?:byteArrayOf())+bytes.copyOfRange(pos,pos+n); pos+=n }
        return out
    }
}
