package dev.turbodl.core

import com.sun.net.httpserver.HttpServer
import okhttp3.Request
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdleConnectionPolicyTest {
    @Test fun `explicit small idle pool does not retain all active download connections`() = checkPool(2)
    @Test fun `zero idle pool still permits concurrent requests and releases every idle connection`() = checkPool(0)

    private fun checkPool(idleLimit: Int) {
        val lanes = 8
        val gate = CountDownLatch(lanes)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val payload = ByteArray(16384).also { java.util.Random(741).nextBytes(it) }
        val expected = MessageDigest.getInstance("SHA-256").digest(payload)
        val serverExecutor = Executors.newFixedThreadPool(lanes)
        val callers = Executors.newFixedThreadPool(lanes)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = serverExecutor
        server.createContext("/data") { exchange ->
            val current = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, current) }
            try {
                gate.countDown()
                check(gate.await(5, TimeUnit.SECONDS))
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            } finally { active.decrementAndGet(); exchange.close() }
        }
        server.start()
        val client = HttpClientFactory.build(TurboConfig(maxConnectionsPerTask = lanes, maxIdleConnections = idleLimit, proxy = ProxyMode.Direct))
        try {
            val futures = (0 until lanes).map {
                callers.submit<ByteArray> {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${server.address.port}/data").build())
                        .execute().use { response -> response.body!!.bytes() }
                }
            }
            futures.forEach { future ->
                assertTrue(MessageDigest.getInstance("SHA-256").digest(future.get(8, TimeUnit.SECONDS)).contentEquals(expected))
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (client.connectionPool.idleConnectionCount() > idleLimit && System.nanoTime() < deadline) Thread.sleep(10)
            println("idle-pool requested=$idleLimit retained=${client.connectionPool.idleConnectionCount()} peak=${peak.get()}")
            assertEquals(lanes, peak.get(), "idle retention must not restrict active requests")
            assertTrue(client.connectionPool.idleConnectionCount() <= idleLimit, "explicit idle connection limit must be respected")
        } finally {
            client.connectionPool.evictAll(); client.dispatcher.executorService.shutdownNow()
            server.stop(0); serverExecutor.shutdownNow(); callers.shutdownNow()
        }
    }
}
