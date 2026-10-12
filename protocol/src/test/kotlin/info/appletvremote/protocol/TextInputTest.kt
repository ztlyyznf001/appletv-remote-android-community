package info.appletvremote.protocol

import org.junit.Assert.*
import org.junit.Test

class TextInputTest {
    @Test fun decodesPythonPlistlibSessionWithUnicodeAndUidReferences() {
        val bytes=javaClass.getResourceAsStream("/rti-session.bplist")!!.use {it.readBytes()}
        val session=TextInput.session(bytes)!!
        assertArrayEquals(ByteArray(16){it.toByte()},session.uuid)
        assertEquals("中文 😀 café",session.text)
    }
    @Test fun insertionAndClearUseCorrectRtiArchivedClassesAndFreshSessionIdentity() {
        val uuid=ByteArray(16){(it+50).toByte()}
        for(clear in listOf(false,true)) {
            val archive=BinaryPlist.decode(TextInput.payload(uuid,"中文 😀",clear)) as Map<*,*>
            fun canonical(value:Any?):Any?=when(value) {
                is ByteArray -> value.map {it.toInt() and 255}
                is Map<*,*> -> value.mapValues {canonical(it.value)}
                is List<*> -> value.map {canonical(it)}
                else -> value
            }
            val fixture=if(clear)"rti-clear-pyatv" else "rti-insert-pyatv"
            val expected=javaClass.getResourceAsStream("/$fixture.bplist")!!.use {BinaryPlist.decode(it.readBytes())}
            assertEquals(canonical(expected),canonical(archive))
            assertEquals("RTIKeyedArchiver",archive["\$archiver"])
            val objects=archive["\$objects"] as List<*>
            fun ref(v:Any?)=objects[(v as BinaryPlist.Uid).value]
            val root=ref((archive["\$top"] as Map<*,*>)["textOperations"]) as Map<*,*>
            assertEquals("RTITextOperations",(ref(root["\$class"]) as Map<*,*>)["\$classname"])
            val target=ref(root["targetSessionUUID"]) as Map<*,*>
            assertArrayEquals(uuid,target["NS.uuidbytes"] as ByteArray)
            val output=ref(root["keyboardOutput"]) as Map<*,*>
            assertEquals("TIKeyboardOutput",(ref(output["\$class"]) as Map<*,*>)["\$classname"])
            if(clear) {assertEquals("",ref(root["textToAssert"]));assertFalse(output.containsKey("insertionText"))}
            else {assertEquals("中文 😀",ref(output["insertionText"]));assertFalse(root.containsKey("textToAssert"))}
        }
    }
    @Test fun supportsLongUtf16TextAndWideObjectReferences() {
        val value=List(400) {"条目 $it 😀"}
        assertEquals(value,BinaryPlist.decode(BinaryPlist.encode(value)))
        val longText="中😀".repeat(1000)
        assertEquals(longText,BinaryPlist.decode(BinaryPlist.encode(longText)))
    }
    @Test fun refusesTruncatedAndCyclicUntrustedArchives() {
        val valid=BinaryPlist.encode(mapOf("key" to "value"))
        for(length in listOf(0,8,39,valid.size-1)) {
            try {BinaryPlist.decode(valid.copyOf(length));fail("Accepted truncated archive")}catch(expected:IllegalArgumentException){}
        }
        val cyclic=BinaryPlist.encode(mapOf("\$top" to mapOf("sessionUUID" to BinaryPlist.Uid(0)),"\$objects" to listOf(BinaryPlist.Uid(0))))
        try {TextInput.session(cyclic);fail("Accepted cyclic UIDs")}catch(expected:IllegalArgumentException){}
    }
}
