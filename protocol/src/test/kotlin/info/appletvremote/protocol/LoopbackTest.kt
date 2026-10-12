package info.appletvremote.protocol

import org.junit.Assert.*
import org.junit.Test
import org.bouncycastle.crypto.agreement.srp.*
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.params.*
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.math.BigInteger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A real TCP peer exercises the wire lifecycle; never substitutes for a physical tvOS test. */
class LoopbackTest {
    private class Peer(val socket:Socket) {
        val input=DataInputStream(socket.getInputStream())
        var rx:ByteArray?=null; var tx:ByteArray?=null; var rc=0L; var tc=0L
        @Suppress("UNCHECKED_CAST") fun read():Pair<Int,Map<String,Any?>> {
            val h=ByteArray(4); input.readFully(h)
            val n=((h[1].toInt() and 255) shl 16) or ((h[2].toInt() and 255) shl 8) or (h[3].toInt() and 255)
            var p=ByteArray(n); input.readFully(p); rx?.let { p=Crypto.aead(false,it,Crypto.counter(rc++),p,h) }
            return (h[0].toInt() and 255) to (Opack.decode(p) as Map<String,Any?>)
        }
        fun send(t:Int,m:Map<String,Any?>) {
            var p=Opack.encode(m); val n=p.size+if(tx!=null) 16 else 0
            val h=byteArrayOf(t.toByte(),(n ushr 16).toByte(),(n ushr 8).toByte(),n.toByte())
            tx?.let { p=Crypto.aead(true,it,Crypto.counter(tc++),p,h) }
            // Deliberate header fragmentation tests readFully-style handling.
            socket.getOutputStream().apply { write(h,0,2); flush(); write(h,2,2); write(p); flush() }
        }
        fun auth(t:Int,vararg fields:Pair<Int,ByteArray>)=send(t,mapOf("_pd" to Tlv.encode(*fields)))
        fun fields(expected:Int):Map<Int,ByteArray> { val(t,m)=read(); assertEquals(expected,t); return Tlv.decode(m.getValue("_pd") as ByteArray) }
    }
    @Test fun pairVerifyAndButtonOverEncryptedTcp() {
        val listener=ServerSocket(0); listener.soTimeout=15000
        val executor=Executors.newSingleThreadExecutor()
        val startIdleEvents=java.util.concurrent.CountDownLatch(1)
        val idleEventsSent=java.util.concurrent.CountDownLatch(1)
        val worker=executor.submit {
            val tvSeed=Crypto.random(32); val tvKey=Crypto.publicKey(tvSeed); val tvId="test-tv".toByteArray()
            var clientId=byteArrayOf(); var clientKey=byteArrayOf()
            listener.accept().use { sock ->
                sock.soTimeout=15000; val p=Peer(sock)
                val start=p.fields(3); assertArrayEquals(byteArrayOf(1),start.getValue(6))
                val salt=Crypto.random(16); val g=SRP6StandardGroups.rfc5054_3072
                val v=SRP6VerifierGenerator().apply { init(g,SHA512Digest()) }.generateVerifier(salt,"Pair-Setup".toByteArray(),"1234".toByteArray())
                val server=SRP6Server().apply { init(g,v,SHA512Digest(),Crypto.random) }; val b=Crypto.unsigned(server.generateServerCredentials())
                p.auth(4,6 to byteArrayOf(2),2 to salt,3 to b)
                val m3=p.fields(4); val a=m3.getValue(3); val secret=server.calculateSecret(BigInteger(1,a)); val k=Crypto.hash(Crypto.unsigned(secret))
                val hn=Crypto.hash(Crypto.unsigned(g.n)); val hg=Crypto.hash(Crypto.unsigned(g.g))
                val proof=Crypto.hash(ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() },Crypto.hash("Pair-Setup".toByteArray()),salt,a,b,k)
                assertArrayEquals(proof,m3.getValue(4)); p.auth(4,6 to byteArrayOf(4),4 to Crypto.hash(a,proof,k))
                val key=Crypto.hkdf(k,"Pair-Setup-Encrypt-Salt","Pair-Setup-Encrypt-Info")
                val m5=p.fields(4); val identity=Tlv.decode(Crypto.aead(false,key,Crypto.nonce("PS-Msg05"),m5.getValue(5)))
                clientId=identity.getValue(1); clientKey=identity.getValue(3)
                Crypto.verify(clientKey,Crypto.hkdf(k,"Pair-Setup-Controller-Sign-Salt","Pair-Setup-Controller-Sign-Info")+clientId+clientKey,identity.getValue(10))
                val tvSig=Crypto.sign(tvSeed,Crypto.hkdf(k,"Pair-Setup-Accessory-Sign-Salt","Pair-Setup-Accessory-Sign-Info")+tvId+tvKey)
                p.auth(4,6 to byteArrayOf(6),5 to Crypto.aead(true,key,Crypto.nonce("PS-Msg06"),Tlv.encode(1 to tvId,3 to tvKey,10 to tvSig)))
            }
            listener.accept().use { sock ->
                sock.soTimeout=15000; val p=Peer(sock); val m1=p.fields(5); val clientPublic=m1.getValue(3)
                val ep=X25519PrivateKeyParameters(Crypto.random); val pub=ep.generatePublicKey().encoded
                val shared=ByteArray(32); ep.generateSecret(X25519PublicKeyParameters(clientPublic,0),shared,0)
                val key=Crypto.hkdf(shared,"Pair-Verify-Encrypt-Salt","Pair-Verify-Encrypt-Info")
                val sig=Crypto.sign(tvSeed,pub+tvId+clientPublic)
                p.auth(6,6 to byteArrayOf(2),3 to pub,5 to Crypto.aead(true,key,Crypto.nonce("PV-Msg02"),Tlv.encode(1 to tvId,10 to sig)))
                val m3=p.fields(6); val identity=Tlv.decode(Crypto.aead(false,key,Crypto.nonce("PV-Msg03"),m3.getValue(5)))
                assertArrayEquals(clientId,identity.getValue(1)); Crypto.verify(clientKey,clientPublic+clientId+pub,identity.getValue(10))
                p.auth(6,6 to byteArrayOf(4)); p.rx=Crypto.hkdf(shared,"","ClientEncrypt-main"); p.tx=Crypto.hkdf(shared,"","ServerEncrypt-main")
                listOf("_systemInfo","_touchStart","_sessionStart","_hidC","_hidC").forEachIndexed { index,name ->
                    val(type,msg)=p.read(); assertEquals(8,type); assertEquals(name,msg["_i"])
                    assertEquals(2L,msg["_t"])
                    if(name=="_hidC") { val c=msg["_c"] as Map<*,*>; assertEquals(6L,c["_hidC"]); assertEquals(if(index==3) 1L else 2L,c["_hBtS"]) }
                    // Zero-length NoOp must neither consume a crypto nonce nor abort the request.
                    sock.getOutputStream().write(byteArrayOf(1,0,0,0))
                    // Ignore both an unsolicited event and an out-of-order response.
                    p.send(8,mapOf("_t" to 1,"_i" to "_noop","_c" to emptyMap<String,Any>()))
                    p.send(8,mapOf("_t" to 3,"_x" to 99999,"_c" to emptyMap<String,Any>()))
                    p.send(8,mapOf("_t" to 3,"_x" to msg["_x"],"_c" to if(name=="_sessionStart") mapOf("_sid" to 44) else emptyMap<String,Any>()))
                }
                fun heldSelect() {
                    for (state in listOf(1L, 2L)) {
                        val (_, request) = p.read(); assertEquals("_hidC", request["_i"])
                        val c = request["_c"] as Map<*,*>; assertEquals(state, c["_hBtS"]); assertEquals(6L, c["_hidC"])
                        p.send(8, mapOf("_t" to 3, "_x" to request["_x"], "_c" to emptyMap<String, Any>()))
                    }
                }
                heldSelect() // duplicate down/up calls are idempotent
                for (phase in listOf(1L,3L,4L)) {
                    val (type,event)=p.read();assertEquals(8,type);assertEquals("_hidT",event["_i"]);assertEquals(1L,event["_t"])
                    assertTrue(event["_x"] is Long)
                    val c=event["_c"] as Map<*,*>;assertEquals(phase,c["_tPh"]);assertEquals(0L,c["_cx"]);assertEquals(1000L,c["_cy"]);assertEquals(1L,c["_tFg"]);assertTrue((c["_ns"] as Long)>=0)
                }
                val (_, begin) = p.read(); assertEquals(1L, (begin["_c"] as Map<*,*>)["_tPh"])
                heldSelect() // finishInput releases a held SELECT before closing the touch surface
                val (_, end) = p.read(); assertEquals(4L, (end["_c"] as Map<*,*>)["_tPh"])
                fun textSession(active:Boolean) {
                    for(name in listOf("_tiStop","_tiStart")) {
                        val (_,request)=p.read();assertEquals(name,request["_i"]);assertEquals(2L,request["_t"])
                        val content=if(active && name=="_tiStart")mapOf("_tiD" to javaClass.getResourceAsStream("/rti-session.bplist")!!.use {it.readBytes()}) else emptyMap()
                        p.send(8,mapOf("_t" to 3,"_x" to request["_x"],"_c" to content))
                    }
                }
                textSession(true) // keyboard snapshot
                textSession(true) // replace command refreshes focus again
                for(clear in listOf(true,false)) {
                    val (_,event)=p.read();assertEquals("_tiC",event["_i"]);assertEquals(1L,event["_t"])
                    assertTrue(event["_x"] is Long)
                    val content=event["_c"] as Map<*,*>;assertEquals(1L,content["_tiV"])
                    val archive=BinaryPlist.decode(content["_tiD"] as ByteArray) as Map<*,*>
                    val objects=archive["\$objects"] as List<*>
                    val op=objects[1] as Map<*,*>
                    assertEquals(clear,op.containsKey("textToAssert"))
                    assertArrayEquals(ByteArray(16){it.toByte()},(objects[5] as Map<*,*>)["NS.uuidbytes"] as ByteArray)
                    if(!clear)assertEquals("新文本 😀",objects[3])
                }
                textSession(false) // must not emit any text for missing focus
                val (_,monitorRequest)=p.read(); assertEquals("_tiStart",monitorRequest["_i"])
                val initial=javaClass.getResourceAsStream("/rti-session.bplist")!!.use { it.readBytes() }
                p.send(8,mapOf("_t" to 3,"_x" to monitorRequest["_x"],"_c" to mapOf("_tiD" to initial)))
                check(startIdleEvents.await(5,TimeUnit.SECONDS))
                p.send(8,mapOf("_t" to 1,"_i" to "_tiStopped","_c" to emptyMap<String,Any>()))
                sock.getOutputStream().write(byteArrayOf(1,0,0,0))
                val changed=BinaryPlist.encode(mapOf("\$top" to mapOf("sessionUUID" to BinaryPlist.Uid(1)),"\$objects" to listOf("\$null",mapOf("NS.uuidbytes" to ByteArray(16){42}))))
                p.send(8,mapOf("_t" to 1,"_i" to "_tiStarted","_c" to mapOf("_tiD" to changed)))
                idleEventsSent.countDown()
                // A stale editor must not receive any text. The next frame is the accepted clear for B.
                val (_,clearRequest)=p.read(); assertEquals("_tiC",clearRequest["_i"])
                val clearArchive=BinaryPlist.decode((clearRequest["_c"] as Map<*,*>)["_tiD"] as ByteArray) as Map<*,*>
                val clearObjects=clearArchive["\$objects"] as List<*>
                assertArrayEquals(ByteArray(16){42},(clearObjects[5] as Map<*,*>)["NS.uuidbytes"] as ByteArray)
                repeat(2) { index ->
                    val (_,request)=p.read(); assertEquals("_hidC",request["_i"])
                    if(index==0) p.send(8,mapOf("_t" to 1,"_i" to "_tiStopped","_c" to emptyMap<String,Any>()))
                    p.send(8,mapOf("_t" to 3,"_x" to request["_x"],"_c" to emptyMap<String,Any>()))
                }
                val (_,snapshot)=p.read(); assertEquals("_tiStart",snapshot["_i"])
                p.send(8,mapOf("_t" to 1,"_i" to "_tiStarted","_c" to mapOf("_tiD" to changed)))
                p.send(8,mapOf("_t" to 3,"_x" to snapshot["_x"],"_c" to mapOf("_tiD" to initial)))
                val (_,unsupported)=p.read(); assertEquals("_tiStart",unsupported["_i"])
                p.send(8,mapOf("_t" to 3,"_x" to unsupported["_x"],"_ec" to 1))
                val (_,manualKeyboard)=p.read(); assertEquals("_tiStop",manualKeyboard["_i"])
                p.send(8,mapOf("_t" to 3,"_x" to manualKeyboard["_x"],"_ec" to 1))
                repeat(2) {
                    val (_,request)=p.read(); assertEquals("_hidC",request["_i"])
                    p.send(8,mapOf("_t" to 3,"_x" to request["_x"],"_c" to emptyMap<String,Any>()))
                }
                assertEquals(-1,sock.getInputStream().read())
            }
        }
        try {
            val client=CompanionClient("127.0.0.1",listener.localPort)
            client.use { it.beginPairing(); val credentials=it.finishPairing("1234"); it.connect(credentials); it.press(RemoteButton.SELECT)
                it.holdSelect(true); it.holdSelect(true); it.holdSelect(false); it.holdSelect(false)
                for(phase in TouchPhase.entries)it.touch(-20,1200,phase)
                it.touch(333,444,TouchPhase.BEGIN); it.holdSelect(true); it.finishInput(); it.finishInput()
                assertEquals("中文 😀 café",it.keyboardState()!!.text)
                assertTrue(it.sendText("新文本 😀",replace=true))
                assertFalse(it.sendText("do not send",replace=false))
                val focus=mutableListOf<String?>()
                it.startKeyboardMonitoring { id -> focus.add(id) }
                val editorA=ByteArray(16){it.toByte()}.joinToString("") { "%02x".format(it.toInt() and 255) }
                val editorB="2a".repeat(16)
                assertEquals(listOf(editorA),focus)
                startIdleEvents.countDown(); check(idleEventsSent.await(5,TimeUnit.SECONDS))
                it.pollEvents()
                assertEquals(listOf(editorA,null,editorB),focus)
                try { it.sendText("must not reach new field",expectedSessionId=editorA); fail("Accepted stale editor") }
                catch(expected:KeyboardFocusChangedException) { }
                assertTrue(it.sendText("",replace=true,expectedSessionId=editorB))
                it.press(RemoteButton.SELECT)
                assertEquals(listOf(editorA,null,editorB,null),focus)
                it.startKeyboardMonitoring { id -> focus.add(id) }
                assertEquals(listOf(editorA,null,editorB,null,editorB),focus)
                try { it.startKeyboardMonitoring { }; fail("Ignored rejected subscription") }
                catch(expected:CompanionCommandRejectedException) { }
                try { it.keyboardState(); fail("Ignored rejected manual keyboard") }
                catch(expected:CompanionCommandRejectedException) { }
                it.press(RemoteButton.SELECT) // Rejected optional monitoring/manual keyboard does not break controls.
            }
            worker.get(20,TimeUnit.SECONDS)
        } finally { listener.close(); executor.shutdownNow() }
    }
    @Test fun reconnectRejectsChangedIdentityAndForgedSignature() {
        for (changedId in listOf(true, false)) {
            val listener=ServerSocket(0); listener.soTimeout=15000
            val executor=Executors.newSingleThreadExecutor()
            val tvSeed=Crypto.random(32); val tvId="expected-tv".toByteArray()
            val credentials=CompanionCredentials("client".toByteArray(),Crypto.random(32),tvId,Crypto.publicKey(tvSeed))
            val worker=executor.submit {
                listener.accept().use { sock ->
                    sock.soTimeout=15000
                    val p=Peer(sock); val clientPublic=p.fields(5).getValue(3)
                    val ep=X25519PrivateKeyParameters(Crypto.random); val pub=ep.generatePublicKey().encoded
                    val shared=ByteArray(32); ep.generateSecret(X25519PublicKeyParameters(clientPublic,0),shared,0)
                    val key=Crypto.hkdf(shared,"Pair-Verify-Encrypt-Salt","Pair-Verify-Encrypt-Info")
                    val sentId=if(changedId) "other-tv".toByteArray() else tvId
                    val signed=Crypto.sign(if(changedId) tvSeed else Crypto.random(32),pub+sentId+clientPublic)
                    p.auth(6,6 to byteArrayOf(2),3 to pub,5 to Crypto.aead(true,key,Crypto.nonce("PV-Msg02"),Tlv.encode(1 to sentId,10 to signed)))
                    assertEquals("Client must close untrusted peer",-1,sock.getInputStream().read())
                }
            }
            try {
                CompanionClient("127.0.0.1",listener.localPort).use { client ->
                    try { client.connect(credentials); fail("Accepted forged TV identity") } catch(expected:IllegalStateException) {}
                }
                worker.get(20,TimeUnit.SECONDS)
            } finally { listener.close(); executor.shutdownNow() }
        }
    }

    @Test fun authenticationDeadlineSurvivesUnrelatedFrameFlood() {
        val listener=ServerSocket(0); listener.soTimeout=15000
        val executor=Executors.newSingleThreadExecutor()
        val worker=executor.submit {
            listener.accept().use { sock ->
                sock.soTimeout=15000; val p=Peer(sock); p.fields(3)
                try {
                    repeat(750) { p.send(7,mapOf("unrelated" to true)); Thread.sleep(20) }
                    fail("Client never enforced authentication deadline")
                } catch(expected:java.net.SocketException) { /* Client closes on total timeout. */ }
            }
        }
        try {
            CompanionClient("127.0.0.1",listener.localPort).use { client ->
                try { client.beginPairing(); fail("Accepted unrelated authentication frames") }
                catch(expected:java.net.SocketTimeoutException) {}
            }
            worker.get(20,TimeUnit.SECONDS)
        } finally { listener.close(); executor.shutdownNow() }
    }

}
