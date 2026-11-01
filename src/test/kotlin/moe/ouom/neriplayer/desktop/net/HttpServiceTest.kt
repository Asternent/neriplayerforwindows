package moe.ouom.neriplayer.desktop.net

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.test.*

class HttpServiceTest {
    @Test fun responseLimitIsEnforcedDuringRead() {
        assertEquals(16, readLimitedBody(ByteArrayInputStream(ByteArray(16)), 16).size)
        assertFailsWith<IllegalArgumentException> { readLimitedBody(ByteArrayInputStream(ByteArray(17)), 16) }
    }
    @Test fun domainBoundariesAndPortBoundariesAreRespected() {
        val http = HttpService()
        assertEquals("bilibili", http.jarKeyFor("api.bilibili.com"))
        assertNotEquals("bilibili", http.jarKeyFor("evilbilibili.com"))
        assertFalse(sameHttpOrigin(URI("http://localhost:80"), URI("http://localhost:81")))
        assertTrue(sameHttpOrigin(URI("https://example.test"), URI("https://example.test:443/a")))
    }
    @Test fun crossOriginRedirectDoesNotLeakAuthentication() {
        val seen = AtomicReference<Pair<String?, String?>>()
        LocalHttpServer { exchange ->
            seen.set(exchange.requestHeaders.getFirst("Authorization") to exchange.requestHeaders.getFirst("Cookie"))
            exchange.respond()
        }.use { destination ->
            LocalHttpServer { it.respond(302, headers = mapOf("Location" to "${destination.url}/target")) }.use { origin ->
                val http = HttpService().apply { setCookie("127.0.0.1", "session", "fixture") }
                assertEquals("ok", http.get(origin.url, mapOf("Authorization" to "Bearer fixture")))
                assertEquals(null to null, seen.get())
            }
        }
    }
    @Test fun privateWriteIsNotFollowedAcrossOrigins() {
        var calls = 0
        LocalHttpServer { calls++; it.respond() }.use { destination ->
            LocalHttpServer { it.respond(307, headers = mapOf("Location" to destination.url)) }.use { origin ->
                assertNull(HttpService().execute("PUT", origin.url, "private data", mapOf("Authorization" to "Basic fixture")))
                assertEquals(0, calls)
            }
        }
    }
    @Test fun sameOriginRedirectRetainsAuthorizationAndReceivesCookies() {
        LocalHttpServer { exchange ->
            if (exchange.requestURI.path == "/start") {
                exchange.respond(302, headers = mapOf("Location" to "/end", "Set-Cookie" to "new=value; Path=/"))
            } else {
                exchange.respond(text = "${exchange.requestHeaders.getFirst("Authorization")}|${exchange.requestHeaders.getFirst("Cookie")}")
            }
        }.use { server ->
            assertEquals("Bearer fixture|new=value", HttpService().get("${server.url}/start", mapOf("Authorization" to "Bearer fixture")))
        }
    }
    @Test fun sameOriginRedirectKeepsStaticCookiesButRemovesLoggedOutCookies() {
        LocalHttpServer { exchange ->
            if (exchange.requestURI.path == "/start") exchange.respond(302,
                headers = mapOf("Location" to "/end", "Set-Cookie" to "session=; Max-Age=0"))
            else exchange.respond(text = exchange.requestHeaders.getFirst("Cookie"))
        }.use { server ->
            val http = HttpService().apply { setCookie("127.0.0.1", "session", "old") }
            assertEquals("os=pc", http.get("${server.url}/start", mapOf("Cookie" to "os=pc")))
        }
    }
    @Test fun staticCookieAndLoginCookieAreMergedWithoutDuplicateNames() {
        LocalHttpServer { it.respond(text = it.requestHeaders.getFirst("Cookie")) }.use { server ->
            val http = HttpService().apply { setCookie("127.0.0.1", "session", "new") }
            assertEquals("session=new; os=pc", http.get(server.url, mapOf("Cookie" to "session=old; os=pc")))
        }
    }
    @Test fun logoutCookieActuallyClearsSession() {
        LocalHttpServer { it.respond(headers = mapOf("Set-Cookie" to "session=; Max-Age=0; Path=/")) }.use { server ->
            val http = HttpService().apply { setCookie("127.0.0.1", "session", "fixture") }
            http.get(server.url)
            assertTrue(http.cookiesFor("127.0.0.1").isEmpty())
        }
    }
    @Test fun gzipIsDecodedAndHttpErrorIsNotSavedAsArtwork() {
        val bytes = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write("compressed".toByteArray()) } }.toByteArray()
        LocalHttpServer { exchange ->
            if (exchange.requestURI.path == "/error") exchange.respond(404, "not an image")
            else {
                exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }.use { server ->
            val http = HttpService()
            assertEquals("compressed", http.execute("GET", server.url)?.text())
            assertEquals("compressed", http.get(server.url))
            assertNull(http.download("${server.url}/error"))
        }
    }
    @Test fun gzipExpansionCannotExhaustTheHeap() {
        val bytes = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { gzip -> repeat(9 * 1024) { gzip.write(ByteArray(1024)) } }
        }.toByteArray()
        LocalHttpServer { exchange ->
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }.use { server -> assertNull(HttpService().download(server.url)) }
    }
    @Test fun stalledResponseBodyIsTimedOutAfterHeadersHaveArrived() {
        LocalHttpServer { exchange ->
            exchange.sendResponseHeaders(200, 100)
            exchange.responseBody.write(byteArrayOf(1))
            exchange.responseBody.flush()
            Thread.sleep(2500)
        }.use { server ->
            val started = System.nanoTime()
            assertNull(HttpService().get(server.url, timeoutSeconds = 1))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2400)
        }
    }
}
