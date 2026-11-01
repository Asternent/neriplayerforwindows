package moe.ouom.neriplayer.desktop.sync

import moe.ouom.neriplayer.desktop.net.*
import org.junit.Test
import kotlin.test.*

class SyncCompatibilityTest {
    @Test fun jsonZstdAndLegacyBase64RoundTripPreserveMetadata() {
        val data = SyncData(deviceId = "fixture", playlists = listOf(SyncPlaylist(id = 1, name = "test")))
        assertEquals(data, SyncDataSerializer.deserialize(SyncDataSerializer.serialize(data, false)))
        assertEquals(data, SyncDataSerializer.deserialize(SyncDataSerializer.serialize(data, true)))
        assertEquals(data, SyncDataSerializer.deserialize(SyncDataSerializer.encodeLegacyBase64(data)))
    }
    @Test fun arbitraryJsonOrFutureVersionsAreNotMistakenForEmptyBackups() {
        listOf("{}", "{\"protocolVersion\":4}", "{\"version\":\"99.0\"}", "not json").forEach {
            assertFailsWith<IllegalStateException>(it) { SyncDataSerializer.deserialize(it.toByteArray()) }
        }
    }
    @Test fun githubArchiveProtocolIsDetectedBeforeLegacyRead() {
        var legacyReads = 0
        LocalHttpServer { exchange ->
            when {
                exchange.requestURI.path.endsWith("/repos/u/r") -> exchange.respond(text = """{"default_branch":"main"}""")
                exchange.requestURI.path.endsWith("/git/ref/heads/main") -> exchange.respond(text = """{"object":{"sha":"snapshot"}}""")
                exchange.requestURI.path.contains(SYNC_ARCHIVE_MANIFEST_FILE_NAME) -> exchange.respond(text = "manifest")
                else -> { legacyReads++; exchange.respond(404) }
            }
        }.use { server ->
            assertIs<RemoteSyncRead.Failed>(GitHubSyncTransport(HttpService(), "fixture", server.url).readSyncFile("u", "r", false))
            assertEquals(0, legacyReads)
        }
    }
    @Test fun githubUnavailableBranchIsNotReportedAsMissingData() {
        LocalHttpServer { exchange ->
            if (exchange.requestURI.path.endsWith("/repos/u/r")) exchange.respond(text = """{"default_branch":"main"}""")
            else exchange.respond(500)
        }.use { server ->
            assertIs<RemoteSyncRead.Failed>(GitHubSyncTransport(HttpService(), "fixture", server.url).readSyncFile("u", "r", false))
        }
    }
    @Test fun githubReadsTheImmutableCommitNotTheMovingBranch() {
        var ref: String? = null
        LocalHttpServer { exchange ->
            when {
                exchange.requestURI.path.endsWith("/repos/u/r") -> exchange.respond(text = """{"default_branch":"main"}""")
                exchange.requestURI.path.endsWith("/git/ref/heads/main") -> exchange.respond(text = """{"object":{"sha":"snapshot"}}""")
                exchange.requestURI.path.contains(SYNC_ARCHIVE_MANIFEST_FILE_NAME) -> exchange.respond(404)
                else -> { ref = exchange.requestURI.query; exchange.respond(text = "{\"version\":\"2.0\"}") }
            }
        }.use { server ->
            assertIs<RemoteSyncRead.Found>(GitHubSyncTransport(HttpService(), "fixture", server.url).readSyncFile("u", "r", false))
            assertEquals("ref=snapshot", ref)
        }
    }
}
