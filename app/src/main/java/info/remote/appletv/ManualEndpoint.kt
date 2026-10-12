package info.remote.appletv

/** Numeric IPv4 only: parsing never performs DNS or network I/O on the UI thread. */
data class ManualEndpoint(val host: String, val port: Int) {
    val id get() = "manual:$host:$port"
    companion object {
        fun parse(address: String, portText: String): ManualEndpoint? {
            val parts = address.trim().split('.')
            if (parts.size != 4 || parts.any { part ->
                part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' } ||
                    (part.toIntOrNull() ?: -1) !in 0..255
            }) return null
            val cleanPort = portText.trim()
            if (cleanPort.isEmpty() || cleanPort.any { it !in '0'..'9' }) return null
            val port = cleanPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val octets = parts.map { it.toInt() }
            if (octets[0] == 0 || octets[0] == 127 || octets[0] >= 224) return null
            return ManualEndpoint(octets.joinToString("."), port)
        }
    }
}
