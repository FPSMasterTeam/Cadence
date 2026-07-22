package top.fpsmaster.music.store

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import top.fpsmaster.music.MusicLog
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.reader
import kotlin.io.path.writer

/**
 * 音乐登录凭证的磁盘持久化（可选工具，不用也行）。
 *
 * 存：网易云 cookie（含 MUSIC_U）、QQ 的 musicid + musickey(qm_keyst)。凭证是每用户的敏感数据，
 * 请存到私有目录、不要随配置同步分发。
 *
 * 与原实现不同：这里是**普通类而非全局单例**，且**文件路径由构造方注入**，从而不再耦合任何
 * 具体应用的目录（原来写死 `%APPDATA%/FPSMaster`）。多个宿主/mod 同 JVM 使用也不会互相覆盖。
 *
 * 用法：
 * ```kotlin
 * val store = MusicCredentialStore.inDir(myModDataDir)   // 或 default("MyMod")
 * store.load()
 * netease.cookie = store.neteaseCookie
 * qq.musicid = store.qqMusicId; qq.musicKey = store.qqMusicKey
 * // 登录成功后：
 * store.setNetease(netease.cookie)
 * store.setQq(qq.musicid, qq.musicKey)
 * ```
 */
class MusicCredentialStore(private val file: Path) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    var neteaseCookie: String = ""
        private set
    var qqMusicId: String = ""
        private set
    var qqMusicKey: String = ""
        private set

    /** 从磁盘加载。建议在启动时调用一次。 */
    fun load() {
        if (!file.exists() || !file.isRegularFile()) {
            MusicLog.debug("No music auth file at $file")
            return
        }
        runCatching {
            val json = file.reader().use { gson.fromJson(it, JsonObject::class.java) } ?: return
            neteaseCookie = json.get("neteaseCookie")?.takeIf { !it.isJsonNull }?.asString ?: ""
            qqMusicId = json.get("qqMusicId")?.takeIf { !it.isJsonNull }?.asString ?: ""
            qqMusicKey = json.get("qqMusicKey")?.takeIf { !it.isJsonNull }?.asString ?: ""
            MusicLog.debug("Loaded music auth from $file")
        }.onFailure { MusicLog.error("Failed to load music auth file", it) }
    }

    fun setNetease(cookie: String) { neteaseCookie = cookie; save() }
    fun clearNetease() { neteaseCookie = ""; save() }

    fun setQq(musicId: String, musicKey: String) { qqMusicId = musicId; qqMusicKey = musicKey; save() }
    fun clearQq() { qqMusicId = ""; qqMusicKey = ""; save() }

    private fun save() {
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            val json = JsonObject().apply {
                addProperty("neteaseCookie", neteaseCookie)
                addProperty("qqMusicId", qqMusicId)
                addProperty("qqMusicKey", qqMusicKey)
                addProperty("lastUpdated", System.currentTimeMillis())
            }
            file.writer().use { gson.toJson(json, it) }
        }.onFailure { MusicLog.error("Failed to save music auth file", it) }
    }

    companion object {
        private const val DEFAULT_FILE_NAME = "music_auth.json"

        /** 放到指定目录下的 `music_auth.json`（或自定义文件名）。 */
        fun inDir(dir: Path, fileName: String = DEFAULT_FILE_NAME): MusicCredentialStore =
            MusicCredentialStore(dir.resolve(fileName))

        /**
         * 便捷默认位置：`%APPDATA%/<appName>/music_auth.json`（Windows）或
         * `~/.<appName 小写>/music_auth.json`（其它系统）。
         */
        fun default(appName: String, fileName: String = DEFAULT_FILE_NAME): MusicCredentialStore {
            val appData = System.getenv("APPDATA")
            val dir = if (!appData.isNullOrBlank()) Paths.get(appData, appName)
                      else Paths.get(System.getProperty("user.home"), ".${appName.lowercase()}")
            return MusicCredentialStore(dir.resolve(fileName))
        }
    }
}
