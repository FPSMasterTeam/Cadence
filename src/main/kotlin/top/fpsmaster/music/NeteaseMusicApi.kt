package top.fpsmaster.music

import com.google.gson.Gson
import com.google.gson.JsonObject
import top.fpsmaster.music.crypto.NeteaseCrypto
import top.fpsmaster.music.http.MusicHttp
import top.fpsmaster.music.lyric.LyricParser

/**
 * 网易云音乐客户端。走 web 端 "weapi" 加密接口，功能完整且稳定。
 *
 * 覆盖：搜索、播放直链、歌词、二维码登录、登录状态、每日推荐、用户歌单、歌单详情。
 *
 * 登录态：二维码登录成功后 [cookie] 会被写入 `MUSIC_U`，之后的请求自动带上。
 * 高音质 / VIP 歌曲需要对应黑胶会员的登录 cookie，否则接口会回退或返回空链接。
 *
 * 线程安全性：[cookie] 的读写对多线程可见，登录成功后的 cookie 合并在实例锁内完成；
 * 业务请求本身无共享可变状态，可以并发调用。
 *
 * 错误：所有方法失败时抛 [MusicApiException]，按 [MusicApiException.kind] 区分
 * 未登录（code 301）、风控（-460/-462/405）、网络、超时、响应异常。
 */
class NeteaseMusicApi(
    private val http: MusicHttp = MusicHttp(),
    initialCookie: String = "",
) {
    private val gson = Gson()

    /** 完整 cookie 串（如登录后持久化到磁盘再回填）。 */
    @Volatile
    var cookie: String = initialCookie

    // ---- 公开 API ----

    /** 搜索歌曲。 */
    fun search(keyword: String, limit: Int = 30, offset: Int = 0): List<Track> {
        val data = weapi(
            "/weapi/cloudsearch/get/web",
            mapOf("s" to keyword, "type" to "1", "limit" to limit.toString(), "offset" to offset.toString(), "total" to "true"),
        )
        val songs = data.objOrNull("result")?.arrOrNull("songs") ?: return emptyList()
        return songs.mapNotNull { el -> el.asObjOrNull()?.let { runCatching { parseTrack(it) }.getOrNull() } }
    }

    /** 获取播放直链。 */
    fun getSongUrl(track: Track, quality: AudioQuality = AudioQuality.STANDARD): SongUrl {
        val level = when (quality) {
            AudioQuality.STANDARD -> "standard"
            AudioQuality.HIGH -> "exhigh"
            AudioQuality.LOSSLESS -> "lossless"
        }
        val data = weapi(
            "/weapi/song/enhance/player/url/v1",
            mapOf("ids" to "[${track.id}]", "level" to level, "encodeType" to "flac"),
        )
        val item = data.arrOrNull("data")?.firstOrNull().asObjOrNull()
            ?: return SongUrl(null, quality)
        val url = item.get("url").strOrNull()
        val type = item.get("type").strOrNull() ?: ""
        val size = item.get("size").longOrNull() ?: 0
        // freeTrialInfo 非空 => 该链接是试听片段（start/end 单位为秒）
        val trial = item.objOrNull("freeTrialInfo")
        return if (trial != null) {
            val start = trial.get("start").longOrNull() ?: 0
            val end = trial.get("end").longOrNull() ?: 0
            SongUrl(url, quality, type, size, isTrial = true, trialStartMs = start * 1000, trialEndMs = end * 1000)
        } else {
            SongUrl(url, quality, type, size)
        }
    }

    /**
     * 获取歌词（含翻译与逐字）。
     *
     * 请求带上 `yv=-1` 以拉取逐字歌词（YRC）；返回的 [Lyric.lines] 已由 [LyricParser] 解析好，
     * 有逐字时 [Lyric.hasWordByWord] 为 true。原始文本一并保留在 [Lyric.lrc]/[Lyric.translated]/[Lyric.yrc]。
     */
    fun getLyric(track: Track): Lyric {
        val data = weapi(
            "/weapi/song/lyric",
            mapOf("id" to track.id, "lv" to "-1", "kv" to "-1", "tv" to "-1", "yv" to "-1"),
        )
        val lrc = data.objOrNull("lrc")?.get("lyric").strOrNull() ?: ""
        val tlyric = data.objOrNull("tlyric")?.get("lyric").strOrNull()?.ifBlank { null }
        val yrc = data.objOrNull("yrc")?.get("lyric").strOrNull()?.ifBlank { null }
        return Lyric(lrc, tlyric, yrc, LyricParser.parse(lrc, tlyric, yrc))
    }

    // ---- 二维码登录 ----

    /** 第一步：创建二维码。用 [QrCode.qrContent]（一个 URL）渲染二维码图片。 */
    fun createQrCode(): QrCode {
        val keyData = weapi("/weapi/login/qrcode/unikey", mapOf("type" to "1"))
        val unikey = keyData.get("unikey").strOrNull()
            ?: throw MusicApiException("网易云：获取 unikey 失败", null, MusicErrorKind.BAD_RESPONSE)
        return QrCode(unikey, "https://music.163.com/login?codekey=$unikey")
    }

    /**
     * 第二步：轮询二维码状态。返回 [QrLoginState.CONFIRMED] 时，登录 cookie 已写入 [cookie]。
     * 建议每 2~3 秒轮询一次。
     *
     * 响应无法解析时返回 [QrLoginState.ERROR]；网络失败抛 [MusicApiException]（[MusicErrorKind.NETWORK]/[MusicErrorKind.TIMEOUT]）。
     */
    fun checkQrCode(qr: QrCode): QrLoginState {
        val resp = http.postForm(
            "https://music.163.com/weapi/login/qrcode/client/login",
            weapiForm(mapOf("key" to qr.key, "type" to "1")),
            headers = baseHeaders(),
            cookies = buildCookies(cookie),
        )
        val json = try {
            gson.parseObject(resp.body, "网易云二维码状态")
        } catch (e: MusicApiException) {
            MusicLog.warn("网易云二维码状态无法解析: HTTP ${resp.status}")
            return QrLoginState.ERROR
        }
        return when (json.get("code").intOrNull()) {
            803 -> {
                captureCookies(resp.setCookies)
                QrLoginState.CONFIRMED
            }
            802 -> QrLoginState.SCANNED
            801 -> QrLoginState.WAITING
            800 -> QrLoginState.EXPIRED
            else -> QrLoginState.ERROR
        }
    }

    /**
     * 是否已登录，返回用户 id（未登录或 cookie 已失效为 null）。
     *
     * 宿主可以用它校验持久化回来的 cookie：返回 null 就应把界面切回未登录。
     */
    fun getLoginUid(): Long? {
        val data = try {
            weapi("/weapi/w/nuser/account/get", emptyMap())
        } catch (e: MusicApiException) {
            if (e.kind == MusicErrorKind.NOT_LOGGED_IN) return null
            throw e
        }
        return data.objOrNull("account")?.get("id").longOrNull()
    }

    /** 每日推荐歌曲（需登录）。 */
    fun getDailyRecommendSongs(): List<Track> {
        val data = weapi("/weapi/v3/discovery/recommend/songs", emptyMap())
        val songs = data.objOrNull("data")?.arrOrNull("dailySongs") ?: return emptyList()
        return songs.mapNotNull { el -> el.asObjOrNull()?.let { runCatching { parseTrack(it) }.getOrNull() } }
    }

    /** 用户歌单列表（需登录）。 */
    fun getUserPlaylists(uid: Long, limit: Int = 30, offset: Int = 0): List<PlaylistBrief> {
        val data = weapi(
            "/weapi/user/playlist",
            mapOf("uid" to uid.toString(), "limit" to limit.toString(), "offset" to offset.toString(), "includeVideo" to "true"),
        )
        val list = data.arrOrNull("playlist") ?: return emptyList()
        return list.mapNotNull {
            val o = it.asObjOrNull() ?: return@mapNotNull null
            PlaylistBrief(
                id = o.get("id").strOrNull() ?: return@mapNotNull null,
                name = o.get("name").strOrNull() ?: "",
                coverUrl = o.get("coverImgUrl").strOrNull(),
                trackCount = o.get("trackCount").intOrNull() ?: 0,
            )
        }
    }

    /** 歌单内歌曲（一次最多 ~1000 首）。 */
    fun getPlaylistTracks(playlistId: String, limit: Int = 1000): List<Track> {
        val data = weapi(
            "/weapi/v6/playlist/detail",
            mapOf("id" to playlistId, "n" to limit.toString(), "s" to "8"),
        )
        val tracks = data.objOrNull("playlist")?.arrOrNull("tracks") ?: return emptyList()
        return tracks.mapNotNull { el -> el.asObjOrNull()?.let { runCatching { parseTrack(it) }.getOrNull() } }
    }

    fun clearLogin() {
        cookie = ""
    }

    // ---- 内部 ----

    private fun parseTrack(o: JsonObject): Track {
        val artists = (o.arrOrNull("ar") ?: o.arrOrNull("artists"))
            ?.mapNotNull { it.asObjOrNull()?.get("name").strOrNull() }
            ?.joinToString(" / ") ?: ""
        val albumObj = o.objOrNull("al") ?: o.objOrNull("album")
        // fee: 0=免费, 1=VIP专享, 4=数字专辑(需购买), 8=免费低音质/高音质需VIP。
        // 除 0 外都需要相应权限才能完整播放。
        val fee = o.get("fee")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        return Track(
            source = MusicSource.NETEASE,
            id = o.get("id").asString,
            name = o.get("name")?.asString ?: "",
            artists = artists,
            album = albumObj?.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "",
            durationMs = (o.get("dt") ?: o.get("duration"))?.takeIf { !it.isJsonNull }?.asLong ?: 0,
            coverUrl = albumObj?.get("picUrl")?.takeIf { !it.isJsonNull }?.asString,
            vip = fee != 0,
        )
    }

    /** 原始 weapi 调用结果（供本地 HTTP 代理层透传给前端使用，如需要）。 */
    data class RawResult(val body: String, val cookie: String)

    /**
     * 用指定 cookie 发一次 weapi 请求，返回**原始响应体**与本次 Set-Cookie。
     *
     * 供需要透传原始 NeteaseCloudMusicApi 响应结构的场景（如 web 兼容代理）使用：
     * 这里只负责加密与转发，不做规范化。纯原生调用一般用不到，直接用上面的规范化方法即可。
     *
     * @param path            weapi 路径，如 `/weapi/song/enhance/player/url/v1`。
     * @param params          业务参数（会被 JSON 化后加密）。
     * @param cookieOverride  本次请求使用的完整 cookie 串（前端持有登录态时传入）。
     */
    fun call(path: String, params: Map<String, String>, cookieOverride: String? = null): RawResult {
        val resp = postWeapi(path, params, buildCookies(cookieOverride ?: cookie))
        return RawResult(resp.body, resp.setCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }

    /**
     * 发一次 weapi 请求并返回响应 JSON。
     *
     * 网易云的业务错误是 HTTP 200 + body 里的 `code`（301 未登录、-460 风控……），
     * 不检查就会把「登录过期」「被风控」当成空列表静默返回。这里统一翻成 [MusicApiException]。
     */
    private fun weapi(path: String, params: Map<String, String>): JsonObject {
        val resp = postWeapi(path, params, buildCookies(cookie))
        val json = gson.parseObject(resp.body, "网易云 $path")
        checkNeteaseCode(json, path)
        return json
    }

    private fun postWeapi(path: String, params: Map<String, String>, cookies: Map<String, String>): MusicHttp.Response =
        http.postForm("https://music.163.com$path", weapiForm(params), headers = baseHeaders(), cookies = cookies)

    private fun weapiForm(params: Map<String, String>): Map<String, String> {
        val json = gson.toJson(params)
        val enc = NeteaseCrypto.weapi(json)
        return mapOf("params" to enc.params, "encSecKey" to enc.encSecKey)
    }

    private fun baseHeaders() = mapOf(
        "Referer" to "https://music.163.com",
        "Origin" to "https://music.163.com",
    )

    private fun buildCookies(cookieString: String): Map<String, String> {
        val base = linkedMapOf("os" to "pc", "appver" to "8.9.70")
        cookieString.split(';').forEach { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isNotEmpty()) base[name] = value
        }
        return base
    }

    @Synchronized
    private fun captureCookies(set: Map<String, String>) {
        // 至少需要 MUSIC_U；把新 cookie 合并进现有串
        val merged = LinkedHashMap<String, String>()
        cookie.split(';').forEach {
            val n = it.substringBefore('=').trim(); val v = it.substringAfter('=', "").trim()
            if (n.isNotEmpty()) merged[n] = v
        }
        set.forEach { (k, v) -> merged[k] = v }
        cookie = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}

/**
 * 把网易云 body 里的业务码翻成 [MusicApiException]。没有 `code` 字段的响应按成功处理。
 */
internal fun checkNeteaseCode(json: JsonObject, path: String) {
    val code = json.get("code").intOrNull() ?: return
    if (code == 200) return
    val kind = when (code) {
        301 -> MusicErrorKind.NOT_LOGGED_IN
        -460, -461, -462, 405 -> MusicErrorKind.RISK_CONTROL
        else -> MusicErrorKind.API_ERROR
    }
    val detail = (json.get("message").strOrNull() ?: json.get("msg").strOrNull())?.takeIf { it.isNotBlank() }
    throw MusicApiException(
        "网易云：接口返回 code=$code" + (detail?.let { "（$it）" } ?: "") + " ($path)",
        null, kind, code,
    )
}

/** 歌单摘要。 */
data class PlaylistBrief(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
)
