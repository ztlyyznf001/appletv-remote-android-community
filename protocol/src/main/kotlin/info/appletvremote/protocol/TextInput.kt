package info.appletvremote.protocol

/** A snapshot, held in memory only. tvOS may change focus after this snapshot. */
data class KeyboardState(val text:String, val sessionId:String)
enum class TouchPhase(val code:Int) { BEGIN(1), MOVE(3), END(4) }

/** RTI archive layouts adapted from pyatv (MIT); see LICENSE-pyatv.txt. */
internal object TextInput {
    data class Session(val uuid:ByteArray,val text:String)
    fun session(data:ByteArray):Session? {
        val archive=BinaryPlist.decode(data) as? Map<*,*> ?: return null
        val objects=archive["\$objects"] as? List<*> ?: return null
        fun resolve(value:Any?):Any? {var item=value;var n=0;while(item is BinaryPlist.Uid){require(++n<=64);item=objects.getOrNull(item.value)};return item}
        fun path(vararg keys:String):Any? {var item=archive["\$top"];for(key in keys)item=resolve((item as? Map<*,*>)?.get(key));return item}
        val id=path("sessionUUID")
        val uuid=(id as? ByteArray) ?: resolve((id as? Map<*,*>)?.get("NS.uuidbytes")) as? ByteArray ?: return null
        require(uuid.size==16) {"Invalid text session identifier"}
        return Session(uuid,(path("documentState","docSt","contextBeforeInput") as? String).orEmpty())
    }
    fun payload(uuid:ByteArray,text:String?,clear:Boolean=false):ByteArray {
        require(uuid.size==16)
        fun uid(i:Int)=BinaryPlist.Uid(i)
        fun cls(name:String)=mapOf("\$classname" to name,"\$classes" to listOf(name,"NSObject"))
        val objects=if(clear) listOf("\$null",mapOf("\$class" to uid(7),"targetSessionUUID" to uid(5),"keyboardOutput" to uid(2),"textToAssert" to uid(4)),mapOf("\$class" to uid(3)),cls("TIKeyboardOutput"),"",mapOf("NS.uuidbytes" to uuid,"\$class" to uid(6)),cls("NSUUID"),cls("RTITextOperations"))
        else listOf("\$null",mapOf("keyboardOutput" to uid(2),"\$class" to uid(7),"targetSessionUUID" to uid(5)),mapOf("insertionText" to uid(3),"\$class" to uid(4)),requireNotNull(text),cls("TIKeyboardOutput"),mapOf("NS.uuidbytes" to uuid,"\$class" to uid(6)),cls("NSUUID"),cls("RTITextOperations"))
        return BinaryPlist.encode(mapOf("\$version" to 100000,"\$archiver" to "RTIKeyedArchiver","\$top" to mapOf("textOperations" to uid(1)),"\$objects" to objects))
    }
}
