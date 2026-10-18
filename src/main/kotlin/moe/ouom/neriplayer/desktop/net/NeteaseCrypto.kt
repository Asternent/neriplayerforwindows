package moe.ouom.neriplayer.desktop.net

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 eapi 参数加密。
 *
 * 新版歌词接口（`/eapi/song/lyric/v1`）才有 `yrc`（逐字时间轴），而这个接口只接受
 * eapi 加密后的参数。算法与 NeteaseCloudMusicApi / 上游 Android 版一致：
 *
 * ```
 * digest = md5("nobody" + path + "use" + json + "md5forencrypt")
 * data   = path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest
 * params = hex(AES-ECB-PKCS5(data, key = "e82ckenh8dichen8"))
 * ```
 */
internal object NeteaseCrypto {

    private const val EAPI_KEY = "e82ckenh8dichen8"
    private const val EAPI_SEPARATOR = "-36cd479b6b5-"

    fun eapiParams(path: String, json: String): String {
        val digest = md5Hex("nobody" + path + "use" + json + "md5forencrypt")
        return aesEcbHex(path + EAPI_SEPARATOR + json + EAPI_SEPARATOR + digest)
    }

    private fun md5Hex(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun aesEcbHex(text: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(EAPI_KEY.toByteArray(Charsets.UTF_8), "AES"),
        )
        return cipher.doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
    }
}
