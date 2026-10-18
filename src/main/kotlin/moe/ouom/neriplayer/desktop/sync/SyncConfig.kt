package moe.ouom.neriplayer.desktop.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import moe.ouom.neriplayer.desktop.core.AppDirs
import moe.ouom.neriplayer.desktop.core.JsonFileStore

@Serializable
data class SyncConfig(
    val deviceId: String = "",
    val token: String = "",
    val owner: String = "",
    val repo: String = "",
    val useDataSaver: Boolean = false,
    val autoSync: Boolean = true,
    /** 支持 GitHub Enterprise / 自建地址；默认 github.com。 */
    val apiBase: String = "https://api.github.com",
    val webdavUrl: String = "",
    val webdavUsername: String = "",
    val webdavPassword: String = "",
    val syncBackend: String = "GITHUB", // GITHUB or WEBDAV
    val lastSyncAt: Long = 0L,
    val lastStatus: String = "",
) {
    val configured: Boolean get() = when (syncBackend) {
        "WEBDAV" -> webdavUrl.isNotBlank() && webdavUsername.isNotBlank() && webdavPassword.isNotBlank()
        else -> token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()
    }

    val repoFullName: String get() = if (owner.isBlank() || repo.isBlank()) "" else "$owner/$repo"
}

/** 同步配置（含 GitHub Token）单独存放，文件权限收紧到 600。 */
class SyncConfigStore {

    private val store = JsonFileStore(AppDirs.syncConfigFile, SyncConfig.serializer()) { SyncConfig() }

    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<SyncConfig> = _state.asStateFlow()

    val current: SyncConfig get() = _state.value

    private val updateLock = Any()

    /**
     * 读-改-写必须整体串行：同步结束时的 `finish()` 与设置页保存 Token / WebDAV 密码
     * 可能同时发生，各自读到旧值再写回，就会把刚填好的凭据覆盖回默认值。
     */
    fun update(transform: (SyncConfig) -> SyncConfig) {
        synchronized(updateLock) {
            val next = transform(_state.value)
            _state.value = next
            store.save(next)
            restrictPermissions()
        }
    }

    fun clear() {
        update { SyncConfig(autoSync = it.autoSync) }
    }

    private fun restrictPermissions() {
        runCatching {
            val file = AppDirs.syncConfigFile
            if (file.isFile) {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
            }
        }
    }
}
