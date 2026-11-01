package moe.ouom.neriplayer.desktop.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

/** 单张图片 / 小文件的内存下载上限（8 MB），避免异常地址把堆撑爆。 */
private const val MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024

internal fun sameHttpOrigin(first: URI, second: URI): Boolean {
    fun port(uri: URI) = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    return first.scheme.equals(second.scheme, true) && first.host.equals(second.host, true) && port(first) == port(second)
}

/** 同时限制普通响应与 gzip 解压后的体积；调用方负责关闭流。 */
internal fun readLimitedBody(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        require(output.size().toLong() + read <= limit) { "HTTP response exceeds $limit bytes" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

object NeriJsonParser {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()
}

fun JsonElement?.asObject(): JsonObject? = (this as? JsonObject)

fun JsonObject.str(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.contentOrNullSafe()
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is JsonNull) null else runCatching { content }.getOrNull()

fun JsonObject.long(key: String): Long? = str(key)?.let { raw ->
    raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
}

fun JsonObject.int(key: String): Int? = str(key)?.let { raw ->
    raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
}

/**
 * 读取布尔字段。接口里既有 JSON 布尔（true/false），也有 0/1 数字，这里统一兼容，
 * 避免把 true 当成解析失败。
 */
fun JsonObject.bool(key: String): Boolean? = str(key)?.trim()?.lowercase()?.let { raw ->
    when (raw) {
        "true" -> true
        "false" -> false
        else -> raw.toIntOrNull()?.let { it != 0 }
    }
}

fun JsonObject.double(key: String): Double? = str(key)?.toDoubleOrNull()

fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()

fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

fun JsonArray.objects(): List<JsonObject> = this.mapNotNull { it as? JsonObject }

/** 轻量 HTTP 客户端，带 Cookie 记忆。 */
class HttpService {

    companion object {
        private val bodyWatchdog = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "neriplayer-http-timeouts").apply { isDaemon = true }
        }
    }

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(12))
        .build()

    private val cookieJar = ConcurrentHashMap<String, MutableMap<String, String>>()

    /** 手动跟随重定向：跨域不转发 Cookie / Authorization，HTTPS 不降级到 HTTP。 */
    private fun send(initial: HttpRequest): HttpResponse<InputStream> {
        var request = initial
        repeat(6) { hop ->
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            storeCookies(request.uri().host.orEmpty(), response)
            val location = response.headers().firstValue("Location").orElse(null)
            if (response.statusCode() !in setOf(301, 302, 303, 307, 308) || location == null) return response
            response.body().close()
            check(hop < 5) { "Too many HTTP redirects" }
            val target = request.uri().resolve(location)
            require(target.scheme?.lowercase() in setOf("http", "https") && target.host != null && target.userInfo == null)
            require(!request.uri().scheme.equals("https", true) || target.scheme.equals("https", true)) {
                "Refusing HTTPS downgrade"
            }
            val sameOrigin = sameHttpOrigin(request.uri(), target)
            // 同步写入绝不跨域转发（即使移除凭据也可能把私人数据上传到其它服务器）。
            require(sameOrigin || request.method() in setOf("GET", "HEAD")) { "Cross-origin write redirect" }
            val asGet = response.statusCode() == 303 ||
                (response.statusCode() in setOf(301, 302) && request.method() == "POST")
            val next = HttpRequest.newBuilder(target).timeout(request.timeout().orElse(Duration.ofSeconds(30)))
            request.headers().map().forEach { (name, values) ->
                if (sameOrigin || name.lowercase() !in setOf("authorization", "proxy-authorization", "cookie")) {
                    values.forEach { next.header(name, it) }
                }
            }
            if (sameOrigin) {
                val cookies = linkedMapOf<String, String>()
                request.headers().firstValue("Cookie").orElse("").split(';').forEach { pair ->
                    val name = pair.substringBefore('=', "").trim()
                    if (name.isNotEmpty()) cookies[name] = pair.substringAfter('=', "").trim()
                }
                cookies.putAll(cookiesFor(target.host))
                response.headers().allValues("Set-Cookie").forEach { cookie ->
                    val pair = cookie.substringBefore(';')
                    if (pair.substringAfter('=', "").isBlank() || Regex("(?i)max-age\\s*=\\s*0(?:;|$)").containsMatchIn(cookie)) {
                        cookies.remove(pair.substringBefore('=').trim())
                    }
                }
                if (cookies.isNotEmpty() || request.headers().firstValue("Cookie").isPresent) {
                    next.setHeader("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                }
            }
            next.method(if (asGet) "GET" else request.method(),
                if (asGet) HttpRequest.BodyPublishers.noBody() else request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
            request = next.build()
        }
        error("Too many HTTP redirects")
    }

    /** ofInputStream 在收到头部后就完成请求；另设读体超时，防止半开连接永久卡住。 */
    private fun watchBody(response: HttpResponse<InputStream>, canceled: () -> Boolean = { false }): AutoCloseable {
        val started = System.nanoTime()
        val timeout = response.request().timeout().orElse(Duration.ofSeconds(30)).toNanos()
        val task = bodyWatchdog.scheduleAtFixedRate({
            if (canceled() || System.nanoTime() - started >= timeout) runCatching { response.body().close() }
        }, 100, 100, TimeUnit.MILLISECONDS)
        return AutoCloseable { task.cancel(false) }
    }

    private fun readBody(response: HttpResponse<InputStream>, limit: Int): ByteArray = watchBody(response).use {
        response.body().use { raw ->
            val stream = if (response.headers().firstValue("Content-Encoding").orElse("").contains("gzip", true)) {
                GZIPInputStream(raw)
            } else raw
            stream.use { readLimitedBody(it, limit) }
        }
    }

    /** Cookie 按平台分组，让登录态可以在同一平台的子域之间共享。 */
    fun jarKeyFor(host: String): String {
        val name = host.substringBefore(':').lowercase()
        return when {
            // 必须按「点号边界」匹配：endsWith("bilibili.com") 会把 evilbilibili.com 也算进来
            matchesDomain(name, "bilibili.com") || matchesDomain(name, "hdslb.com") -> "bilibili"
            matchesDomain(name, "music.163.com") || matchesDomain(name, "163.com") ||
                matchesDomain(name, "126.net") -> "netease"
            else -> name
        }
    }

    private fun matchesDomain(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")

    /**
     * 写入请求头并合并 Cookie。
     *
     * `HttpRequest.Builder.header()` 是追加语义：如果调用方自己带了 `Cookie`，再追加一个
     * Cookie 头就会出现两个，服务器通常只读第一个 —— 登录态会悄悄失效。
     */
    private fun applyHeaders(builder: HttpRequest.Builder, headers: Map<String, String>, host: String) {
        var staticCookie: String? = null
        headers.forEach { (key, value) ->
            if (key.equals("Cookie", ignoreCase = true)) {
                staticCookie = value
            } else {
                builder.header(key, value)
            }
        }
        val cookies = linkedMapOf<String, String>()
        staticCookie?.split(';')?.forEach { pair ->
            val name = pair.substringBefore('=', "").trim()
            if (name.isNotEmpty()) cookies[name] = pair.substringAfter('=', "").trim()
        }
        cookies.putAll(cookiesFor(host))
        val merged = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (merged.isNotBlank()) builder.setHeader("Cookie", merged)
    }

    fun cookiesFor(host: String): Map<String, String> = cookieJar[jarKeyFor(host)].orEmpty().toMap()

    fun cookieSnapshot(key: String): Map<String, String> = cookieJar[key].orEmpty().toMap()

    fun setCookie(host: String, name: String, value: String) {
        cookieJar.getOrPut(jarKeyFor(host)) { ConcurrentHashMap() }[name] = value
    }

    fun importCookies(key: String, cookies: Map<String, String>) {
        val jar = cookieJar.getOrPut(key) { ConcurrentHashMap() }
        cookies.forEach { (name, value) -> if (value.isNotBlank()) jar[name] = value }
    }

    fun clearCookies(key: String) {
        cookieJar.remove(key)
    }

    private fun storeCookies(host: String, response: HttpResponse<*>) {
        val values = response.headers().allValues("set-cookie")
        if (values.isEmpty()) return
        val jar = cookieJar.getOrPut(jarKeyFor(host)) { ConcurrentHashMap() }
        values.forEach { header ->
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=', "").trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isNotEmpty()) {
                if (value.isEmpty() || Regex("(?i)(?:^|;)\\s*max-age\\s*=\\s*0(?:;|$)").containsMatchIn(header)) {
                    jar.remove(name)
                } else jar[name] = value
            }
        }
    }

    private fun cookieHeaderFor(host: String): String? =
        cookieJar[jarKeyFor(host)]?.takeIf { it.isNotEmpty() }
            ?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 15,
    ): String? = request("GET", url, null, headers, timeoutSeconds)

    fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 15,
    ): String? {
        val body = form.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        return request("POST", url, body, headers + mapOf("Content-Type" to "application/x-www-form-urlencoded"), timeoutSeconds)
    }

    private fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        timeoutSeconds: Long,
    ): String? {
        return runCatching {
            val host = runCatching { URI(url).host }.getOrNull().orEmpty()
            val builder = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip")
            applyHeaders(builder, headers, host)
            if (method == "POST") {
                builder.POST(HttpRequest.BodyPublishers.ofString(body.orEmpty()))
            } else {
                builder.GET()
            }
            readBody(send(builder.build()), MAX_DOWNLOAD_BYTES).decodeToString()
        }.getOrNull()
    }

    fun download(url: String, headers: Map<String, String> = emptyMap()): ByteArray? = runCatching {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(25))
            .header("User-Agent", DESKTOP_USER_AGENT)
        applyHeaders(builder, headers, host)
        val response = send(builder.GET().build())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            return@runCatching null
        }
        // 在读取时限制，而不是整个响应进内存后才检查。
        readBody(response, MAX_DOWNLOAD_BYTES)
    }.getOrNull()

    /** 需要读取状态码 / 响应头的调用（GitHub 同步接口要用）。 */
    data class RawResponse(
        val status: Int,
        val bytes: ByteArray,
        val header: (String) -> String?,
    ) {
        fun text(): String = bytes.decodeToString()
    }

    fun execute(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 30,
    ): RawResponse? = executeBytes(
        method = method,
        url = url,
        body = body?.toByteArray(Charsets.UTF_8),
        headers = headers,
        timeoutSeconds = timeoutSeconds,
    )

    fun executeBytes(
        method: String,
        url: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 30,
    ): RawResponse? = runCatching {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept-Encoding", "gzip")
        applyHeaders(builder, headers, host)
        val publisher = if (body == null) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofByteArray(body)
        }
        when (method.uppercase()) {
            "GET" -> builder.GET()
            "POST" -> builder.POST(publisher)
            "PUT" -> builder.PUT(publisher)
            "PATCH" -> builder.method("PATCH", publisher)
            "DELETE" -> builder.DELETE()
            else -> builder.method(method.uppercase(), publisher)
        }
        val response = send(builder.build())
        val bytes = readBody(response, 32 * 1024 * 1024)
        RawResponse(
            status = response.statusCode(),
            bytes = bytes,
            header = { name -> response.headers().firstValue(name).orElse(null) },
        )
    }.getOrNull()

    /**
     * 流式下载到文件：边下边写盘并回报进度，避免整首歌驻留内存。
     * @return 成功返回 true；失败时删除半成品文件
     */
    fun downloadToFile(
        url: String,
        headers: Map<String, String>,
        target: File,
        isCanceled: () -> Boolean = { false },
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Boolean {
        return runCatching {
            val host = runCatching { URI(url).host }.getOrNull().orEmpty()
            val builder = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", DESKTOP_USER_AGENT)
            applyHeaders(builder, headers, host)
            val response = send(builder.GET().build())
            // 非 2xx 也要把 body 关掉，否则连接会一直被占着（之前直接 return 漏掉了）
            if (response.statusCode() !in 200..299) {
                runCatching { response.body().close() }
                return@runCatching false
            }
            val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            target.parentFile?.mkdirs()
            var downloaded = 0L
            watchBody(response, isCanceled).use {
                BufferedInputStream(response.body()).use { input ->
                    BufferedOutputStream(target.outputStream()).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            !isCanceled() && downloaded > 0 && (total < 0 || downloaded == total)
        }.getOrElse { false }.also { success ->
            if (!success) runCatching { target.delete() }
        }
    }
}

fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
