package info.appletvremote.protocol

import org.bouncycastle.crypto.agreement.srp.SRP6Client
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.*
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

internal object Crypto {
    val random=SecureRandom()
    fun random(n:Int)=ByteArray(n).also(random::nextBytes)
    fun hash(vararg data:ByteArray):ByteArray = MessageDigest.getInstance("SHA-512").run { data.forEach(::update); digest() }
    fun hkdf(key:ByteArray,salt:String,info:String):ByteArray = ByteArray(32).also { out -> HKDFBytesGenerator(SHA512Digest()).apply { init(HKDFParameters(key,salt.toByteArray(),info.toByteArray())); generateBytes(out,0,out.size) } }
    fun aead(encrypt:Boolean,key:ByteArray,nonce:ByteArray,data:ByteArray,aad:ByteArray=byteArrayOf()):ByteArray {
        require(nonce.size==12)
        val cipher=ChaCha20Poly1305(); cipher.init(encrypt,AEADParameters(KeyParameter(key),128,nonce,aad))
        val out=ByteArray(cipher.getOutputSize(data.size)); val n=cipher.processBytes(data,0,data.size,out,0); val tail=cipher.doFinal(out,n); return out.copyOf(n+tail)
    }
    fun nonce(name:String)=ByteArray(4)+name.toByteArray().also { require(it.size==8) }
    fun counter(n:Long)=ByteArray(12) { if(it<8) (n ushr (8*it)).toByte() else 0 }
    fun publicKey(seed:ByteArray)=Ed25519PrivateKeyParameters(seed,0).generatePublicKey().encoded
    fun sign(seed:ByteArray,data:ByteArray)=Ed25519Signer().run { init(true,Ed25519PrivateKeyParameters(seed,0)); update(data,0,data.size); generateSignature() }
    fun verify(key:ByteArray,data:ByteArray,sig:ByteArray) {
        require(key.size==32 && sig.size==64) { "Invalid identity signature" }
        check(Ed25519Signer().run { init(false,Ed25519PublicKeyParameters(key,0)); update(data,0,data.size); verifySignature(sig) }) { "Apple TV identity verification failed" }
    }
    fun unsigned(n:BigInteger):ByteArray = n.toByteArray().let { if(it.size>1 && it[0]==0.toByte()) it.copyOfRange(1,it.size) else it }
}
internal class Srp(pin:String,salt:ByteArray,serverPublic:ByteArray) {
    val publicKey:ByteArray
    val proof:ByteArray
    val expectedServerProof:ByteArray
    val sessionKey:ByteArray
    init {
        val group=SRP6StandardGroups.rfc5054_3072
        val srp=SRP6Client(); srp.init(group,SHA512Digest(),Crypto.random)
        val a=srp.generateClientCredentials(salt,"Pair-Setup".toByteArray(),pin.toByteArray())
        val b=BigInteger(1,serverPublic)
        val secret=srp.calculateSecret(b)
        publicKey=Crypto.unsigned(a)
        sessionKey=Crypto.hash(Crypto.unsigned(secret))
        val hn=Crypto.hash(Crypto.unsigned(group.n)); val hg=Crypto.hash(Crypto.unsigned(group.g))
        val xor=ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        proof=Crypto.hash(xor,Crypto.hash("Pair-Setup".toByteArray()),salt,publicKey,Crypto.unsigned(b),sessionKey)
        expectedServerProof=Crypto.hash(publicKey,proof,sessionKey)
    }
    fun verify(proof:ByteArray) { check(MessageDigest.isEqual(expectedServerProof,proof)) { "Pairing proof rejected; check PIN" } }
}
