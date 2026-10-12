package info.remote.appletv

import org.junit.Assert.*
import org.junit.Test

class ManualEndpointTest {
    @Test fun acceptsAndNormalizesNumericEndpoint() {
        val endpoint = ManualEndpoint.parse(" 192.168.001.20 ", " 49153 ")!!
        assertEquals("192.168.1.20", endpoint.host)
        assertEquals(49153, endpoint.port)
        assertEquals("manual:192.168.1.20:49153", endpoint.id)
        assertNotEquals(endpoint.id, ManualEndpoint.parse(endpoint.host, "49154")!!.id)
    }
    @Test fun rejectsInvalidOrNonUnicastAddresses() {
        listOf("", "apple-tv.local", "192.168.1", "192.168.1.256", "192.168.-1.1", "192.168.1.1:49153", "127.0.0.1", "224.0.0.251", "0.0.0.0").forEach {
            assertNull(it, ManualEndpoint.parse(it, "49153"))
        }
    }
    @Test fun requiresExplicitValidPort() {
        listOf("", "0", "65536", "-1", "+123", "1.2", "9999999999999999").forEach {
            assertNull(it, ManualEndpoint.parse("192.168.1.20", it))
        }
        assertNotNull(ManualEndpoint.parse("192.168.1.20", "1"))
        assertNotNull(ManualEndpoint.parse("192.168.1.20", "65535"))
    }
}
