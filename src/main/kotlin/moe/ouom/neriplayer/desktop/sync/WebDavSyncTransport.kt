package moe.ouom.neriplayer.desktop.sync

import moe.ouom.neriplayer.desktop.net.HttpService
import java.security.MessageDigest
import java.util.Base64

/**
 * WebDAV 同步文件传输。
 *
 * 条件请求与手机端保持一致：
 * - 强 ETag（带引号、不以 `W/` 开头）→ `If-Match`；
 * - 弱 ETag（坚果云等）**不能**用于条件写，改用 `If-Unmodified-Since` + `Last-Modified`；
 * - 两个都没有时不做条件写，写完后重新读回比对 SHA-256，避免悄悄覆盖别的设备的数据。
 */
class WebDavSyncTransport(
    private val http: HttpService,
    private val baseUrl: String,
    private val username: String,
    private val password: String,
) {
    private val authHeader = "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray())

    private fun headers(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("Authorization", authHeader)
        putAll(extra)
    }

    private fun buildUrl(path: String): String {
        val base = baseUrl.trimEnd('/')
        val relative = path.trimStart('/')
        return "$base/$relative"
    }

    /** 测试连接 */
    fun testConnection(): Boolean {
        // 尝试 PROPFIND（Depth 0），部分服务器只允许 GET，做双重兜底
        val propfind = http.execute("PROPFIND", buildUrl(""), headers = headers(mapOf("Depth" to "0")))
        if (propfind != null && (propfind.status in 200..299 || propfind.status == 207)) return true
        val get = http.execute("GET", buildUrl(""), headers = headers())
        return get != null && get.status in 200..299
    }

    /** 读取同步文件 */
    fun readSyncFile(useDataSaver: Boolean): RemoteSyncFile? {
        val candidates = listOf(SyncDataSerializer.fileNameFor(useDataSaver)) +
            SyncDataSerializer.readFallbackFileNames(useDataSaver)

        for (path in candidates) {
            val response = http.execute("GET", buildUrl(path), headers = headers()) ?: continue
            if (response.status == 200 && response.bytes.isNotEmpty()) {
                return RemoteSyncFile(
                    content = response.bytes,
                    headSha = response.header("etag").orEmpty().let(::strongETag),
                    path = path,
                    lastModified = response.header("last-modified").orEmpty().trim(),
                )
            }
        }
        return null
    }

    /**
     * 提交同步文件。
     *
     * @param expectedHead 读到的强 ETag；为空表示服务端只给了弱 ETag 或没给。
     * @param expectedLastModified 读到的 `Last-Modified`，弱 ETag 时用它做条件写。
     * @param createOnly 远端还没有同步文件，用 `If-None-Match: *` 抢占创建。
     */
    fun writeSyncFile(
        content: ByteArray,
        expectedHead: String?,
        expectedLastModified: String?,
        message: String,
        useDataSaver: Boolean,
        createOnly: Boolean = false,
    ): SyncWriteResult {
        if (content.isEmpty()) return SyncWriteResult.Failed("拒绝上传空的同步数据")
        val path = SyncDataSerializer.fileNameFor(useDataSaver)
        val url = buildUrl(path)
        val baseHeaders = mutableMapOf("Content-Type" to "application/octet-stream")

        fun conditionHeaders(): MutableMap<String, String> {
            val result = baseHeaders.toMutableMap()
            when {
                createOnly -> result["If-None-Match"] = "*"
                !expectedHead.isNullOrBlank() -> result["If-Match"] = expectedHead
                !expectedLastModified.isNullOrBlank() -> result["If-Unmodified-Since"] = expectedLastModified
            }
            return result
        }

        var response = http.executeBytes("PUT", url, body = content, headers = headers(conditionHeaders()))
            ?: return SyncWriteResult.Failed("网络请求失败")

        if (response.status == 412) {
            // 412 可能只是「服务器不认弱 ETag / 时间戳粒度太粗」。重新读一次：
            // 内容没变就说明没人改过，可以无条件重写；变了才是真冲突。
            val current = readSyncFile(useDataSaver)
            if (current == null || !sameContent(current.content, content)) {
                return SyncWriteResult.Conflict
            }
            response = http.executeBytes("PUT", url, body = content, headers = headers(baseHeaders))
                ?: return SyncWriteResult.Failed("网络请求失败")
        }

        if (response.status in 200..299 || response.status == 204) {
            val newEtag = strongETag(response.header("etag").orEmpty())
            // 没有强 ETag 的服务器（坚果云）写完必须读回校验，否则并发写会静默丢数据
            if (newEtag.isBlank()) {
                val readBack = readSyncFile(useDataSaver)
                if (readBack != null && !sameContent(readBack.content, content)) {
                    return SyncWriteResult.Conflict
                }
            }
            return SyncWriteResult.Success(newEtag, newEtag)
        }

        return SyncWriteResult.Failed("HTTP ${response.status}: ${response.text().take(100)}")
    }

    companion object {
        /** 只有带引号的强 ETag 才能用于 If-Match；`W/"..."` 一律当作没有。 */
        internal fun strongETag(value: String): String {
            val trimmed = value.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("W/", ignoreCase = true)) return ""
            return trimmed
        }

        /** 内容是否一致（用于替代缺失的 ETag）。 */
        internal fun sameContent(first: ByteArray, second: ByteArray): Boolean =
            first.size == second.size && MessageDigest.isEqual(first, second)
    }
}
