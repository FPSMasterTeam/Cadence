package top.fpsmaster.music.store

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import top.fpsmaster.music.MusicLog
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.reader

/**
 * 音乐登录凭证的磁盘持久化（可选工具，不用也行）。
 *
 * 存：网易云 cookie（含 MUSIC_U）、QQ 的 musicid + musickey(qm_keyst)。凭证是每用户的敏感数据，
 * 请存到私有目录、不要随配置同步分发。文件以原子替换方式写入，POSIX 系统上权限为 0600。
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

    // 登录回调（扫码轮询线程）、Cookie 登录（异步池）、登出（渲染线程）可能同时改凭证；
    // 读写字段和落盘都在 this 上同步，保证文件里永远是某一次完整的状态。
    @Volatile
    var neteaseCookie: String = ""
        private set
    @Volatile
    var qqMusicId: String = ""
        private set
    @Volatile
    var qqMusicKey: String = ""
        private set

    /** 从磁盘加载。建议在启动时调用一次。 */
    @Synchronized
    fun load() {
        if (!file.exists() || !file.isRegularFile()) {
            MusicLog.debug("No music auth file at $file")
            return
        }
        // 旧版本按 umask 写成了 0644，读的时候顺手收紧
        restrictToOwner(file)
        runCatching {
            val json = file.reader().use { gson.fromJson(it, JsonObject::class.java) } ?: return
            neteaseCookie = json.get("neteaseCookie")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            qqMusicId = json.get("qqMusicId")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            qqMusicKey = json.get("qqMusicKey")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            MusicLog.debug("Loaded music auth from $file")
        }.onFailure { MusicLog.error("Failed to load music auth file", it) }
    }

    @Synchronized
    fun setNetease(cookie: String) { neteaseCookie = cookie; save() }

    @Synchronized
    fun clearNetease() { neteaseCookie = ""; save() }

    @Synchronized
    fun setQq(musicId: String, musicKey: String) { qqMusicId = musicId; qqMusicKey = musicKey; save() }

    @Synchronized
    fun clearQq() { qqMusicId = ""; qqMusicKey = ""; save() }

    /**
     * 先写同目录临时文件（创建时即为 0600），再原子替换正式文件：写到一半崩溃或断电时，
     * 旧文件保持完整，不会出现半截 JSON 导致两个平台的登录一起丢失。
     */
    private fun save() {
        runCatching {
            val dir = file.toAbsolutePath().parent
            Files.createDirectories(dir)
            val json = JsonObject().apply {
                addProperty("neteaseCookie", neteaseCookie)
                addProperty("qqMusicId", qqMusicId)
                addProperty("qqMusicKey", qqMusicKey)
                addProperty("lastUpdated", System.currentTimeMillis())
            }
            val tmp = createPrivateTempFile(dir, file.fileName.toString())
            try {
                Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { gson.toJson(json, it) }
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
        }.onFailure { MusicLog.error("Failed to save music auth file", it) }
    }

    private fun createPrivateTempFile(dir: Path, name: String): Path =
        if (isPosix(dir)) {
            Files.createTempFile(dir, "$name.", ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        } else {
            // Windows 没有 POSIX 权限；凭证放在 %APPDATA%（按用户隔离的 ACL）下。
            Files.createTempFile(dir, "$name.", ".tmp")
        }

    private fun restrictToOwner(path: Path) {
        if (!isPosix(path)) return
        runCatching { Files.setPosixFilePermissions(path, OWNER_ONLY) }
            .onFailure { MusicLog.warn("Cannot restrict permissions of $path: ${it.message}") }
    }

    private fun isPosix(path: Path): Boolean =
        path.fileSystem.supportedFileAttributeViews().contains("posix")

    companion object {
        private val OWNER_ONLY = PosixFilePermissions.fromString("rw-------")
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
