package info.appletvremote.protocol

import java.io.ByteArrayOutputStream

/** Bounded binary-plist subset required by Remote Text Input; never instantiates archived classes. */
internal object BinaryPlist {
    data class Uid(val value: Int)
    data class DateSeconds(val value: Double)
    fun encode(value: Any?): ByteArray {
        val objects=mutableListOf<Any?>()
        data class ArrayRefs(val refs:List<Int>)
        data class DictRefs(val keys:List<Int>,val values:List<Int>)
        fun add(v:Any?):Int {
            val index=objects.size; objects.add(null)
            objects[index]=when(v) {
                is Map<*,*> -> DictRefs(v.keys.map { require(it is String); add(it) },v.values.map { add(it) })
                is List<*> -> ArrayRefs(v.map { add(it) })
                else -> v
            }; return index
        }
        val root=add(value)
        fun width(v:Long)=when {v<=255 -> 1;v<=65535 -> 2;v<=0xffffffffL -> 4;else -> 8}
        fun ByteArrayOutputStream.number(v:Long,n:Int) { for(i in n-1 downTo 0) write((v ushr (8*i)).toInt() and 255) }
        val refWidth=width(objects.size.toLong()-1); val out=ByteArrayOutputStream();out.write("bplist00".toByteArray())
        fun length(marker:Int,size:Int) { if(size<15)out.write(marker or size) else {out.write(marker or 15);val n=width(size.toLong());out.write(0x10 or Integer.numberOfTrailingZeros(n));out.number(size.toLong(),n)} }
        val offsets=objects.map { obj ->
            val offset=out.size()
            when(obj) {
                null -> out.write(0)
                is Boolean -> out.write(if(obj)9 else 8)
                is Uid -> {val n=width(obj.value.toLong());out.write(0x80 or (n-1));out.number(obj.value.toLong(),n)}
                is Number -> {val n=if(obj.toLong()<0)8 else width(obj.toLong());out.write(0x10 or Integer.numberOfTrailingZeros(n));out.number(obj.toLong(),n)}
                is ByteArray -> {length(0x40,obj.size);out.write(obj)}
                is String -> {val ascii=obj.all {it.code<128};length(if(ascii)0x50 else 0x60,obj.length);out.write(obj.toByteArray(if(ascii)Charsets.US_ASCII else Charsets.UTF_16BE))}
                is ArrayRefs -> {length(0xa0,obj.refs.size);obj.refs.forEach {out.number(it.toLong(),refWidth)}}
                is DictRefs -> {length(0xd0,obj.keys.size);(obj.keys+obj.values).forEach {out.number(it.toLong(),refWidth)}}
                else -> error("Unsupported plist value")
            };offset
        }
        val table=out.size();val offsetWidth=width(table.toLong());offsets.forEach {out.number(it.toLong(),offsetWidth)}
        out.write(ByteArray(6));out.write(offsetWidth);out.write(refWidth);out.number(objects.size.toLong(),8);out.number(root.toLong(),8);out.number(table.toLong(),8)
        return out.toByteArray()
    }
    fun decode(bytes:ByteArray):Any? {
        require(bytes.size in 40..1048576 && bytes.copyOfRange(0,8).contentEquals("bplist00".toByteArray())) {"Invalid text archive"}
        val trailer=bytes.size-32
        fun number(at:Int,n:Int):Long {require(n in 1..8 && at>=0 && at.toLong()+n<=bytes.size);var result=0L;repeat(n){result=(result shl 8) or (bytes[at+it].toLong() and 255)};return result}
        val offsetWidth=bytes[trailer+6].toInt() and 255;val refWidth=bytes[trailer+7].toInt() and 255
        require(offsetWidth in 1..8 && refWidth in 1..8)
        val count=number(trailer+8,8);val root=number(trailer+16,8);val table=number(trailer+24,8)
        require(count in 1..10000 && root in 0 until count && table in 8 until trailer.toLong() && table+count*offsetWidth<=trailer)
        val offsets=IntArray(count.toInt()){number(table.toInt()+it*offsetWidth,offsetWidth).also {p->require(p in 8 until table)}.toInt()}
        val cache=mutableMapOf<Int,Any?>();val active=mutableSetOf<Int>();var visited=0
        fun parse(index:Int,depth:Int):Any? {
            require(index in offsets.indices && depth<=64)
            if(cache.containsKey(index))return cache[index]
            require(active.add(index) && ++visited<=10000) {"Cyclic text archive"}
            var at=offsets[index];val marker=bytes[at++].toInt() and 255;val type=marker ushr 4;var length=marker and 15
            if(length==15 && type in listOf(4,5,6,10,11,12,13)) {require(at<table);val m=bytes[at++].toInt() and 255;require(m ushr 4==1 && (m and 15)<=3);val n=1 shl (m and 15);val l=number(at,n);at+=n;require(l in 0..100000);length=l.toInt()}
            fun bound(n:Long) {require(n>=0 && at+n<=table)}
            fun refs(n:Int):List<Int> {bound(n.toLong()*refWidth);return List(n){number(at+it*refWidth,refWidth).also {r->require(r in 0 until count)}.toInt()}}
            val result:Any?=when(type) {
                0 -> when(length){0->null;8->false;9->true;else->error("Unsupported plist atom")}
                1 -> {require(length<=3);bound((1 shl length).toLong());number(at,1 shl length)}
                2 -> {require(length==2 || length==3);val n=1 shl length;bound(n.toLong());if(n==4)Float.fromBits(number(at,n).toInt()).toDouble() else Double.fromBits(number(at,n))}
                3 -> {require(length==3);bound(8);DateSeconds(Double.fromBits(number(at,8)))}
                4,5,6 -> {val n=length.toLong()*if(type==6)2 else 1;bound(n);val raw=bytes.copyOfRange(at,at+n.toInt());if(type==4)raw else raw.toString(if(type==6)Charsets.UTF_16BE else Charsets.US_ASCII)}
                8 -> {require(length<8);bound(length+1L);val v=number(at,length+1);require(v in 0..Int.MAX_VALUE);Uid(v.toInt())}
                10,11,12 -> refs(length).map {parse(it,depth+1)}
                13 -> {val r=refs(length*2);buildMap<String,Any?> {repeat(length){val key=parse(r[it],depth+1);require(key is String);put(key,parse(r[length+it],depth+1))}}}
                else -> error("Unsupported text archive type")
            }
            active.remove(index);cache[index]=result;return result
        }
        return parse(root.toInt(),0)
    }
}
