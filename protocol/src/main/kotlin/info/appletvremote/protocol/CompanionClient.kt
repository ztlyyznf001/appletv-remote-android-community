package info.appletvremote.protocol

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** Blocking client. Serialize calls on one background worker. No payloads are logged. */
class CompanionClient(private val host:String,private val port:Int,private val clientName:String="Android Remote") : Closeable {
    private var socket:Socket?=null
    private var input:DataInputStream?=null
    private var writeKey:ByteArray?=null
    private var readKey:ByteArray?=null
    private var writeCounter=0L
    private var readCounter=0L
    private var xid=1L
    private var salt:ByteArray?=null
    private var serverPublic:ByteArray?=null
    private var connected=false
    private var keyboardListener: ((String?) -> Unit)? = null
    private var textSession: TextInput.Session? = null
    private var focusVersion = 0L
    private var selectHeld = false
    private var activeTouch: Pair<Int, Int>? = null
    private var touchEpoch=System.nanoTime()
    private val timeoutMs=10000
    private fun open() { close(); require(port in 1..65535); socket=Socket().also { it.connect(InetSocketAddress(host,port),timeoutMs); it.soTimeout=timeoutMs; it.tcpNoDelay=true }; input=DataInputStream(socket!!.getInputStream()) }
    @Synchronized fun beginPairing() {
        open()
        try { val response=auth(3,2,0 to byteArrayOf(0),6 to byteArrayOf(1)); salt=response.getValue(2); serverPublic=response.getValue(3) }
        catch(e:Exception) { close(); throw e }
    }
    @Synchronized fun finishPairing(pin:String):CompanionCredentials {
        require(pin.matches(Regex("[0-9]{4}"))) { "Enter the four-digit PIN shown on Apple TV" }
        val s=salt?:error("Start pairing first"); val b=serverPublic?:error("Start pairing first")
        try {
            val srp=Srp(pin,s,b)
            val m4=auth(4,4,6 to byteArrayOf(3),3 to srp.publicKey,4 to srp.proof)
            srp.verify(m4.getValue(4))
            val seed=Crypto.random(32); val public=Crypto.publicKey(seed); val id=UUID.randomUUID().toString().toByteArray()
            val controllerX=Crypto.hkdf(srp.sessionKey,"Pair-Setup-Controller-Sign-Salt","Pair-Setup-Controller-Sign-Info")
            val key=Crypto.hkdf(srp.sessionKey,"Pair-Setup-Encrypt-Salt","Pair-Setup-Encrypt-Info")
            val payload=Tlv.encode(1 to id,3 to public,10 to Crypto.sign(seed,controllerX+id+public))
            val m6=auth(4,6,6 to byteArrayOf(5),5 to Crypto.aead(true,key,Crypto.nonce("PS-Msg05"),payload))
            val peer=Tlv.decode(Crypto.aead(false,key,Crypto.nonce("PS-Msg06"),m6.getValue(5)))
            val peerId=peer.getValue(1); val peerKey=peer.getValue(3)
            val accessoryX=Crypto.hkdf(srp.sessionKey,"Pair-Setup-Accessory-Sign-Salt","Pair-Setup-Accessory-Sign-Info")
            Crypto.verify(peerKey,accessoryX+peerId+peerKey,peer.getValue(10))
            return CompanionCredentials(id,seed,peerId,peerKey)
        } finally { close() }
    }
    @Synchronized fun connect(credentials:CompanionCredentials) {
        open()
        try {
            val ephemeral=X25519PrivateKeyParameters(Crypto.random); val public=ephemeral.generatePublicKey().encoded
            val m2=auth(5,2,6 to byteArrayOf(1),3 to public)
            val peerPublic=m2.getValue(3); require(peerPublic.size==32)
            val shared=ByteArray(32); ephemeral.generateSecret(X25519PublicKeyParameters(peerPublic,0),shared,0)
            val key=Crypto.hkdf(shared,"Pair-Verify-Encrypt-Salt","Pair-Verify-Encrypt-Info")
            val peer=Tlv.decode(Crypto.aead(false,key,Crypto.nonce("PV-Msg02"),m2.getValue(5)))
            check(peer.getValue(1).contentEquals(credentials.deviceId)) { "Apple TV identity changed; pair again" }
            Crypto.verify(credentials.devicePublicKey,peerPublic+credentials.deviceId+public,peer.getValue(10))
            val signed=Crypto.sign(credentials.clientSecret,public+credentials.clientId+peerPublic)
            val payload=Tlv.encode(1 to credentials.clientId,10 to signed)
            auth(6,4,6 to byteArrayOf(3),5 to Crypto.aead(true,key,Crypto.nonce("PV-Msg03"),payload))
            writeKey=Crypto.hkdf(shared,"","ClientEncrypt-main"); readKey=Crypto.hkdf(shared,"","ServerEncrypt-main")
            command("_systemInfo",mapOf("_bf" to 0,"_cf" to 512,"_clFl" to 128,"_i" to credentials.clientId.toString(Charsets.UTF_8),"_idsID" to credentials.clientId,"_pubID" to Crypto.hash(credentials.clientId).take(6).mapIndexed { index, byte -> "%02X".format(if(index==0) ((byte.toInt() and 0xfc) or 2) else (byte.toInt() and 255)) }.joinToString(":"),"_sf" to 256,"_sv" to "170.18","model" to "Android","name" to clientName))
            touchEpoch=System.nanoTime()
            command("_touchStart",mapOf("_height" to 1000.0,"_width" to 1000.0,"_tFl" to 0))
            val session=command("_sessionStart",mapOf("_srvT" to "com.apple.tvremoteservices","_sid" to (Crypto.random.nextInt().toLong() and 0xffffffffL)))
            check((session["_c"] as? Map<*,*>)?.get("_sid") is Number) { "Apple TV did not open a remote session" }
            connected=true
        } catch(e:Exception) { close(); throw e }
    }
    @Synchronized fun press(button:RemoteButton) {
        check(connected) { "Connect to Apple TV first" }
        try {
            command("_hidC",mapOf("_hBtS" to 1,"_hidC" to button.code))
            command("_hidC",mapOf("_hBtS" to 2,"_hidC" to button.code))
        } catch(e:Exception) { close(); throw e }
    }
    /** A held SELECT uses the same HID down/up pair as a click, without blocking the worker. */
    @Synchronized fun holdSelect(held: Boolean) {
        check(connected) { "Connect to Apple TV first" }
        if (selectHeld == held) return
        try {
            command("_hidC", mapOf("_hBtS" to if (held) 1 else 2, "_hidC" to RemoteButton.SELECT.code))
            selectHeld = held
        } catch (e: Exception) { close(); throw e }
    }
    /** Call on the I/O worker before normal teardown. Transport failure instead closes the session. */
    @Synchronized fun finishInput() {
        if (!connected) return
        if (selectHeld) holdSelect(false)
        activeTouch?.let { (x, y) -> touch(x, y, TouchPhase.END) }
    }
    /** Fire-and-forget motion events. Coordinates refer to the negotiated 1000 x 1000 surface. */
    @Synchronized fun touch(x:Int,y:Int,phase:TouchPhase) {
        check(connected) { "Connect to Apple TV first" }
        try {
            event("_hidT",mapOf("_ns" to (System.nanoTime()-touchEpoch).coerceAtLeast(0),"_tFg" to 1,"_cx" to x.coerceIn(0,1000),"_cy" to y.coerceIn(0,1000),"_tPh" to phase.code))
            activeTouch = if (phase == TouchPhase.END) null else x to y
        }
        catch(e:Exception) { close();throw e }
    }
    /** Subscribe on the serialized I/O worker, then call pollEvents regularly while foregrounded. */
    @Synchronized fun startKeyboardMonitoring(listener: (String?) -> Unit) {
        check(connected) { "Connect to Apple TV first" }
        keyboardListener = listener
        val version = focusVersion
        try {
            val response = command("_tiStart", emptyMap())
            // A notification interleaved with the reply is newer than the requested snapshot.
            if (version == focusVersion) updateTextSession(response["_c"] as? Map<*,*> ?: emptyMap<String,Any>())
        } catch (e: CompanionCommandRejectedException) {
            keyboardListener = null; textSession = null
            throw e // Unsupported keyboard monitoring must not disable ordinary controls.
        } catch (e: Exception) { close(); throw e }
    }
    /** Only consume when bytes are available; all framing and crypto remain on the same worker.
     * Limit each batch so queued touch/button work is not starved by unsolicited events.
     */
    @Synchronized fun pollEvents() {
        check(connected) { "Connect to Apple TV first" }
        try {
            repeat(16) {
                if ((input?.available() ?: 0) == 0) return
                val (frame, message) = receive(System.nanoTime() + timeoutMs * 1_000_000L)
                if (frame == 8) dispatchEvent(message)
            }
        } catch (e: Exception) { close(); throw e }
    }
    private fun dispatchEvent(message: Map<String,Any?>) {
        if (keyboardListener == null || (message["_t"] as? Number)?.toInt() != 1) return
        when (message["_i"]) {
            "_tiStarted" -> updateTextSession(message["_c"] as? Map<*,*> ?: emptyMap<String,Any>())
            "_tiStopped" -> updateTextSession(emptyMap<String,Any>())
        }
    }
    private fun updateTextSession(content: Map<*,*>) {
        val bytes = content["_tiD"] as? ByteArray
        textSession = bytes?.let { TextInput.session(it) }
        focusVersion++
        val id = textSession?.uuid?.joinToString("") { "%02x".format(it.toInt() and 255) }
        keyboardListener?.invoke(id)
    }
    /** Snapshot only; text is never persisted. */
    @Synchronized fun keyboardState():KeyboardState? {
        check(connected) { "Connect to Apple TV first" }
        return refreshTextSession()?.let {KeyboardState(it.text, it.uuid.joinToString("") { byte -> "%02x".format(byte.toInt() and 255) })}
    }
    /** Returns false without sending text when Apple TV has no focused input field.
     * Events have no acknowledgement; true means sent, not confirmed displayed by the TV.
     */
    @Synchronized fun sendText(text:String,replace:Boolean=false,expectedSessionId:String?=null):Boolean {
        check(connected) { "Connect to Apple TV first" }
        require(text.length<=16384) { "Text is too long" }
        val session=refreshTextSession() ?: return false
        if (expectedSessionId != null && session.uuid.joinToString("") { "%02x".format(it.toInt() and 255) } != expectedSessionId)
            throw KeyboardFocusChangedException()
        try {
            if(replace)event("_tiC",mapOf("_tiV" to 1,"_tiD" to TextInput.payload(session.uuid,null,clear=true)))
            if(text.isNotEmpty())event("_tiC",mapOf("_tiV" to 1,"_tiD" to TextInput.payload(session.uuid,text)))
            return true
        } catch(e:Exception) {close();throw e}
    }
    private fun refreshTextSession():TextInput.Session? {
        try {
            if (keyboardListener != null) {
                pollEvents()
                return textSession
            }
            command("_tiStop",emptyMap())
            val response=command("_tiStart",emptyMap())
            val bytes=(response["_c"] as? Map<*,*>)?.get("_tiD") as? ByteArray ?: return null
            return TextInput.session(bytes)
        } catch(e:CompanionCommandRejectedException) { throw e }
        catch(e:Exception) {close();throw e}
    }
    private fun event(name:String,content:Map<String,Any?>) {send(8,mapOf("_i" to name,"_t" to 1,"_x" to xid++,"_c" to content))}
    private fun auth(frame:Int,sequence:Int,vararg fields:Pair<Int,ByteArray>):Map<Int,ByteArray> {
        val data=linkedMapOf<String,Any?>("_pd" to Tlv.encode(*fields),"_x" to xid++)
        if(frame==3 || frame==4) data["_pwTy"]=1
        if(frame==5) data["_auTy"]=4
        send(frame,data)
        val expected=if(frame==3) 4 else if(frame==5) 6 else frame
        val deadline=System.nanoTime()+timeoutMs*1_000_000L
        while(true) {
            val (type,response)=receive(deadline)
            if(type!=expected) continue
            check(!response.containsKey("_em") && !response.containsKey("_ec")) { "Apple TV rejected authentication" }
            val tlv=Tlv.decode(response["_pd"] as? ByteArray ?: error("Missing pairing response"))
            check(!tlv.containsKey(7)) { "Apple TV rejected pairing; verify PIN and retry" }
            check(tlv[6]?.contentEquals(byteArrayOf(sequence.toByte()))==true) { "Unexpected pairing step" }
            return tlv
        }
    }
    private fun command(name:String,content:Map<String,Any?>):Map<String,Any?> {
        val id=xid++; send(8,mapOf("_i" to name,"_t" to 2,"_x" to id,"_c" to content))
        val deadline=System.nanoTime()+timeoutMs*1_000_000L
        while(true) {
            val (frame,response)=receive(deadline)
            if (frame == 8) dispatchEvent(response)
            if(frame!=8 || (response["_t"] as? Number)?.toInt()!=3 || (response["_x"] as? Number)?.toLong()!=id) continue
            if (response.containsKey("_em") || response.containsKey("_ec")) throw CompanionCommandRejectedException()
            return response
        }
    }
    private fun send(type:Int,data:Map<String,Any?>) {
        var bytes=Opack.encode(data); val size=bytes.size+if(writeKey!=null) 16 else 0
        val header=byteArrayOf(type.toByte(),(size ushr 16).toByte(),(size ushr 8).toByte(),size.toByte())
        writeKey?.let { bytes=Crypto.aead(true,it,Crypto.counter(writeCounter++),bytes,header) }
        socket!!.getOutputStream().apply { write(header); write(bytes); flush() }
    }
    @Suppress("UNCHECKED_CAST") private fun receive(deadline:Long):Pair<Int,Map<String,Any?>> {
        fun read(n:Int):ByteArray {
            val out=ByteArray(n); var pos=0
            while(pos<n) {
                val remaining=(deadline-System.nanoTime())/1_000_000L
                if(remaining<=0) throw java.net.SocketTimeoutException("Apple TV response timed out")
                socket!!.soTimeout=remaining.coerceAtMost(timeoutMs.toLong()).coerceAtLeast(1).toInt()
                val count=input!!.read(out,pos,n-pos); if(count<0) throw java.io.EOFException("Apple TV disconnected"); pos+=count
            }; return out
        }
        val header=read(4); val size=((header[1].toInt() and 255) shl 16) or ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
        if(size==0 && header[0].toInt()==1) return 1 to emptyMap() // Unencrypted NoOp keepalive.
        require(size in 1..1048576) { "Invalid Companion frame length" }
        var payload=read(size)
        readKey?.let { payload=Crypto.aead(false,it,Crypto.counter(readCounter++),payload,header) }
        return (header[0].toInt() and 255) to (Opack.decode(payload) as? Map<String,Any?> ?: error("Invalid Companion response"))
    }
    @Synchronized override fun close() {
        connected=false; selectHeld=false; activeTouch=null; keyboardListener=null; textSession=null; focusVersion=0
        try { socket?.close() } finally { socket=null; input=null; writeKey?.fill(0); readKey?.fill(0); writeKey=null; readKey=null; writeCounter=0; readCounter=0; salt=null; serverPublic=null }
    }
}

/** A valid response rejecting a command, distinct from transport/framing failure. */
class CompanionCommandRejectedException : java.io.IOException("Apple TV rejected command")

class KeyboardFocusChangedException : java.io.IOException("Keyboard input field changed")
