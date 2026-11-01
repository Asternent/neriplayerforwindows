package moe.ouom.neriplayer.desktop.sync

import moe.ouom.neriplayer.desktop.net.*
import org.junit.Test
import kotlin.test.*

class WebDavSyncTransportTest {
    private fun legacyServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit) = LocalHttpServer {
        if (it.requestURI.path.endsWith(SYNC_ARCHIVE_MANIFEST_FILE_NAME)) it.respond(404, "missing") else handler(it)
    }
    private fun write(transport: WebDavSyncTransport, head: String? = null, modified: String? = null, create: Boolean = false) =
        transport.writeSyncFile("fixture".toByteArray(), head, modified, "test", false, create)

    @Test fun strongEtagsMustBeQuotedAndNotWeak() {
        assertEquals("\"abc\"", WebDavSyncTransport.strongETag(" \"abc\" "))
        listOf("W/\"abc\"", "w/\"abc\"", "abc", "\"a b\"", "").forEach {
            assertEquals("", WebDavSyncTransport.strongETag(it), it)
        }
    }
    @Test fun failedReadBackIsNotReportedAsSuccessfulUpload() {
        legacyServer { if (it.requestMethod == "PUT") it.respond(204) else it.respond(500) }.use { server ->
            assertIs<SyncWriteResult.Failed>(write(WebDavSyncTransport(HttpService(), server.url, "user", "pass")))
        }
    }
    @Test fun preconditionFailureNeverTriggersAnUnconditionalSecondPut() {
        var puts = 0
        legacyServer { exchange ->
            if (exchange.requestMethod == "PUT") { puts++; exchange.respond(412) }
            else exchange.respond(text = "fixture", headers = mapOf("ETag" to "\"new\""))
        }.use { server ->
            assertIs<SyncWriteResult.Success>(write(WebDavSyncTransport(HttpService(), server.url, "u", "p"), head = "\"old\""))
            assertEquals(1, puts)
        }
    }
    @Test fun preconditionFailureWithDifferentContentIsAConflict() {
        legacyServer { if (it.requestMethod == "PUT") it.respond(412) else it.respond(text = "another device") }.use { server ->
            assertEquals(SyncWriteResult.Conflict, write(WebDavSyncTransport(HttpService(), server.url, "u", "p")))
        }
    }
    @Test fun conditionalCreateAndWeakEtagFallbackHeadersAreCorrect() {
        var noneMatch: String? = null
        var modified: String? = null
        legacyServer { exchange ->
            noneMatch = exchange.requestHeaders.getFirst("If-None-Match")
            modified = exchange.requestHeaders.getFirst("If-Unmodified-Since")
            exchange.respond(204, headers = mapOf("ETag" to "\"new\""))
        }.use { server ->
            val transport = WebDavSyncTransport(HttpService(), server.url, "u", "p")
            assertIs<SyncWriteResult.Success>(write(transport, create = true))
            assertEquals("*", noneMatch)
            assertIs<SyncWriteResult.Success>(write(transport, modified = "date"))
            assertEquals("date", modified)
        }
    }
    @Test fun unreadableRemoteIsNotTreatedAsMissing() {
        legacyServer { it.respond(403) }.use { server ->
            assertIs<RemoteSyncRead.Failed>(WebDavSyncTransport(HttpService(), server.url, "u", "p").readSyncFile(false))
        }
    }
    @Test fun webDavUrlValidationRejectsCredentialAndSchemeConfusion() {
        assertEquals("https://example.test/dav/", validatedWebDavUrl(" https://example.test/dav/ "))
        listOf("file:///path", "https://u:p@example.test", "https://example.test/?x=1", "https://example.test/#x").forEach {
            assertFailsWith<IllegalArgumentException>(it) { validatedWebDavUrl(it) }
        }
    }
    @Test fun newerArchiveProtocolIsNeverReadAsAnEmptyLegacyBackupOrWrittenOver() {
        var puts = 0
        LocalHttpServer {
            if (it.requestMethod == "PUT") puts++
            it.respond(text = "new archive manifest")
        }.use { server ->
            val transport = WebDavSyncTransport(HttpService(), server.url, "u", "p")
            assertIs<RemoteSyncRead.Failed>(transport.readSyncFile(false))
            assertIs<SyncWriteResult.Failed>(write(transport))
            assertEquals(0, puts)
        }
    }
}
