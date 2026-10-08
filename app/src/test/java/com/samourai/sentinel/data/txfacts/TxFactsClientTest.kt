package com.samourai.sentinel.data.txfacts

import okhttp3.Authenticator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class TxFactsClientTest {

    /**
     * The security constraint, asserted: this client must never grow the
     * ApiService interceptors (Dojo token as a query param) or any
     * authenticator plumbing - it talks to third parties.
     */
    @Test
    fun `facts client carries no interceptors and no authenticator`() {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))
        val client = TxFactsClient.buildClient(proxy)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertEquals(Authenticator.NONE, client.authenticator)
    }
}
