package moe.ouom.neriplayer.desktop.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class LocalHttpServer(handler: (HttpExchange) -> Unit) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> exchange.use { handler(it) } }
        start()
    }
    val url: String get() = "http://127.0.0.1:${server.address.port}"
    override fun close() = server.stop(0)
}

fun HttpExchange.respond(status: Int = 200, text: String = "ok", headers: Map<String, String> = emptyMap()) {
    headers.forEach { (name, value) -> responseHeaders.add(name, value) }
    val bytes = text.toByteArray()
    sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
    if (status != 204) responseBody.write(bytes)
}
