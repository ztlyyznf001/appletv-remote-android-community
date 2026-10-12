package info.appletvremote.protocol

import java.util.Base64

/** Secret material: callers must encrypt persistence and must never log this object. */
class CompanionCredentials internal constructor(
    internal val clientId:ByteArray, internal val clientSecret:ByteArray,
    internal val deviceId:ByteArray, internal val devicePublicKey:ByteArray
) {
    fun encode():String = listOf(clientId,clientSecret,deviceId,devicePublicKey).joinToString(".") { Base64.getEncoder().encodeToString(it) }
    override fun toString()="CompanionCredentials([redacted])"
    companion object {
        fun decode(value:String):CompanionCredentials {
            require(value.length<4096); val p=value.split('.'); require(p.size==4)
            val parts=p.map { Base64.getDecoder().decode(it) }
            require(parts[0].size in 1..128 && parts[1].size==32 && parts[2].size in 1..128 && parts[3].size==32)
            return CompanionCredentials(parts[0],parts[1],parts[2],parts[3])
        }
    }
}
enum class RemoteButton(internal val code:Int) { UP(1),DOWN(2),LEFT(3),RIGHT(4),MENU(5),SELECT(6),HOME(7),PLAY_PAUSE(14) }
