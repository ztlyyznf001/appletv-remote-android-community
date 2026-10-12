package info.appletvremote.protocol

import org.junit.Assert.*
import org.junit.Test
import org.bouncycastle.crypto.agreement.srp.SRP6Server
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator
import org.bouncycastle.crypto.digests.SHA512Digest
import java.math.BigInteger

class ProtocolTest {
    private fun hex(s:String)=s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun opackKnownWireVectorAndReferences() {
        assertArrayEquals(hex("e3425f740a425f7809425f69455f68696443"),Opack.encode(linkedMapOf("_t" to 2,"_x" to 1,"_i" to "_hidC")))
        assertEquals(listOf("name","name"),Opack.decode(hex("d2446e616d65a0")))
        assertEquals(1000.0,Opack.decode(Opack.encode(1000.0)))
        assertArrayEquals(hex("360000000000408f40"),Opack.encode(1000.0))
        val binary=ByteArray(700) { it.toByte() }
        assertArrayEquals(binary, (Opack.decode(Opack.encode(mapOf("blob" to binary))) as Map<*,*>)["blob"] as ByteArray)
    }
    @Test fun malformedOpackRejected() {
        listOf("62ffff", "a0", "ef", "e14178", "0808").forEach { encoded ->
            try { Opack.decode(hex(encoded)); fail("Accepted malformed OPACK") } catch(expected:IllegalArgumentException) {} catch(expected:IllegalStateException) {}
        }
    }
    @Test fun tlvFragmentationAndTruncation() {
        val data=ByteArray(600) { it.toByte() }
        assertArrayEquals(data,Tlv.decode(Tlv.encode(3 to data)).getValue(3))
        try { Tlv.decode(hex("03ff00")); fail() } catch(expected:IllegalArgumentException) {}
    }
    @Test fun ed25519Rfc8032VectorAndForgery() {
        val seed=hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val public=hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature=hex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")
        assertArrayEquals(public,Crypto.publicKey(seed)); assertArrayEquals(signature,Crypto.sign(seed,byteArrayOf()))
        Crypto.verify(public,byteArrayOf(),signature)
        try { Crypto.verify(public,byteArrayOf(1),signature); fail() } catch(expected:IllegalStateException) {}
    }
    @Test fun chachaRfc8439VectorAndHeaderTampering() {
        val key=hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce=hex("070000004041424344454647"); val aad=hex("50515253c0c1c2c3c4c5c6c7")
        val plain="Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()
        val cipher=hex("d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd0600691")
        assertArrayEquals(cipher,Crypto.aead(true,key,nonce,plain,aad)); assertArrayEquals(plain,Crypto.aead(false,key,nonce,cipher,aad))
        try { Crypto.aead(false,key,nonce,cipher,byteArrayOf(0)); fail() } catch(expected:org.bouncycastle.crypto.InvalidCipherTextException) {}
        assertArrayEquals(hex("010000000000000000000000"),Crypto.counter(1))
    }
    @Test fun srpAgreementAndRejectWrongServerProof() {
        val salt=hex("beb25379d1a8581eb5a727673a2441ee"); val group=SRP6StandardGroups.rfc5054_3072
        val generator=SRP6VerifierGenerator().apply { init(group,SHA512Digest()) }
        val verifier=generator.generateVerifier(salt,"Pair-Setup".toByteArray(),"1234".toByteArray())
        val server=SRP6Server().apply { init(group,verifier,SHA512Digest(),Crypto.random) }
        val public=server.generateServerCredentials()
        val client=Srp("1234",salt,Crypto.unsigned(public))
        val secret=server.calculateSecret(BigInteger(1,client.publicKey))
        assertArrayEquals(Crypto.hash(Crypto.unsigned(secret)),client.sessionKey)
        client.verify(Crypto.hash(client.publicKey,client.proof,client.sessionKey))
        try { client.verify(ByteArray(64)); fail() } catch(expected:IllegalStateException) {}
    }
    @Test fun credentialsAreRedacted() {
        val credentials=CompanionCredentials("client".toByteArray(),ByteArray(32),"tv".toByteArray(),ByteArray(32))
        assertEquals("CompanionCredentials([redacted])",credentials.toString())
        assertEquals(credentials.encode(),CompanionCredentials.decode(credentials.encode()).encode())
    }
}
