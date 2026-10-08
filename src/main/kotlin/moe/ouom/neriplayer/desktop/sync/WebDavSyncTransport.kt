package moe.ouom.neriplayer.desktop.sync

import moe.ouom.neriplayer.desktop.net.HttpService
import java.util.Base64

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
        // PROPFIND on base url to check if it's reachable and auth works
        val response = http.execute("PROPFIND", buildUrl(""), headers = headers(mapOf("Depth" to "0")))
        return response != null && response.status in 200..299
    }

    /** 读取同步文件 */
    fun readSyncFile(useDataSaver: Boolean): RemoteSyncFile? {
        val candidates = listOf(SyncDataSerializer.fileNameFor(useDataSaver)) +
            SyncDataSerializer.readFallbackFileNames(useDataSaver)
            
        for (path in candidates) {
            val response = http.execute("GET", buildUrl(path), headers = headers())
            if (response != null && response.status == 200 && response.bytes.isNotEmpty()) {
                val etag = response.header("etag") ?: ""
                return RemoteSyncFile(response.bytes, etag, path)
            }
        }
        return null
    }

    /** 提交同步文件 */
    fun writeSyncFile(
        content: ByteArray,
        expectedHead: String?,
        message: String,
        useDataSaver: Boolean
    ): SyncWriteResult {
        if (content.isEmpty()) return SyncWriteResult.Failed("拒绝上传空的同步数据")
        val path = SyncDataSerializer.fileNameFor(useDataSaver)
        val url = buildUrl(path)
        
        // Simple optimistic concurrency check with ETag if provided (not strictly enforced by all WebDAV servers)
        val headers = mutableMapOf("Content-Type" to "application/octet-stream")
        if (!expectedHead.isNullOrEmpty()) {
            headers["If-Match"] = expectedHead
        }
        
        val response = http.execute("PUT", url, body = content, headers = headers(headers))
            ?: return SyncWriteResult.Failed("网络请求失败")
            
        if (response.status in 200..299 || response.status == 204) {
            val newEtag = response.header("etag") ?: ""
            return SyncWriteResult.Success(newEtag, newEtag)
        }
        
        if (response.status == 412) { // Precondition Failed (If-Match check failed)
            return SyncWriteResult.Conflict
        }
        
        return SyncWriteResult.Failed("HTTP ${response.status}: ${response.text().take(100)}")
    }
}
