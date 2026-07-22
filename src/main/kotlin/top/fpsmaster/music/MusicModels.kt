package top.fpsmaster.music

/**
 * 音乐来源平台。
 */
enum class MusicSource {
    NETEASE,
    QQ,
}

/**
 * 统一音质档位。各平台在自己的客户端里映射到平台特定的编码。
 *
 * 注意：高于 [STANDARD] 的档位在两个平台通常都需要对应会员（网易云黑胶 / QQ 绿钻），
 * 未登录或非会员账号请求高音质时接口会静默回退到可用的最低档，或返回空链接。
 */
enum class AudioQuality {
    STANDARD,   // 128 kbps
    HIGH,       // 320 kbps
    LOSSLESS,   // FLAC
}

/**
 * 一首歌的规范化元数据，跨平台字段对齐。
 *
 * @param source   来源平台。
 * @param id        平台内数字/字符串 ID（网易云为数字歌曲 ID；QQ 为 songid）。
 * @param mid       QQ 音乐特有的 songmid，取链接/歌词时需要；网易云为 null。
 * @param name      歌名。
 * @param artists   歌手名，多位以 " / " 连接。
 * @param album     专辑名。
 * @param durationMs 时长（毫秒）。
 * @param coverUrl  封面图 URL，可能为 null。
 * @param vip       是否需要会员才能完整播放（用于前端提示）。
 */
data class Track(
    val source: MusicSource,
    val id: String,
    val mid: String? = null,
    val name: String,
    val artists: String,
    val album: String = "",
    val durationMs: Long = 0,
    val coverUrl: String? = null,
    val vip: Boolean = false,
)

/**
 * 歌曲播放链接。
 *
 * @param url          可直接交给播放器的 HTTP(S) 链接；不可用时为 null。
 * @param quality      实际返回的音质档（可能低于请求档）。
 * @param format       容器/编码，如 "mp3" / "flac" / "m4a"。
 * @param sizeBytes    文件大小（字节），未知为 0。
 * @param isTrial      是否为试听片段（非完整歌曲）。VIP 歌未开通会员时常返回 ~30s 试听。
 * @param trialStartMs 试听片段起点（毫秒），[isTrial] 为 true 时有意义。
 * @param trialEndMs   试听片段终点（毫秒），[isTrial] 为 true 时有意义。
 */
data class SongUrl(
    val url: String?,
    val quality: AudioQuality,
    val format: String = "",
    val sizeBytes: Long = 0,
    val isTrial: Boolean = false,
    val trialStartMs: Long = 0,
    val trialEndMs: Long = 0,
    /** 不可用原因（供前端展示 + 自动跳过），可播放时为 null。 */
    val reason: String? = null,
) {
    val available: Boolean get() = !url.isNullOrEmpty()
}

/**
 * 逐字歌词的单个音节/词（卡拉OK高亮用）。
 *
 * @param startMs   起点（毫秒，绝对时间，与所在 [LyricLine.startMs] 同基准）。
 * @param durationMs 持续（毫秒）。
 * @param text      该音节文本。
 */
data class LyricWord(
    val startMs: Long,
    val durationMs: Long,
    val text: String,
)

/**
 * 一行歌词（已解析出时间轴）。
 *
 * 普通 LRC：[words] 为空，[durationMs] 由下一行推算（末行为 0）。
 * 网易云 YRC（逐字）：[words] 非空，可做卡拉OK高亮。
 *
 * @param startMs     行起点（毫秒）。
 * @param durationMs  行时长（毫秒），未知为 0。
 * @param text        整行文本。
 * @param words       逐字时间轴；普通 LRC 为空列表。
 * @param translation 对应的翻译文本（若有）。
 * @param isMetadata  是否为元信息行（作词/作曲等，YRC 里以 JSON 描述）。
 */
data class LyricLine(
    val startMs: Long,
    val durationMs: Long = 0,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
    val isMetadata: Boolean = false,
)

/**
 * 歌词。
 *
 * [lrc] / [translated] / [yrc] 为原始文本（兜底 & 需要重新解析时用）；
 * [lines] 是本库已解析好的时间轴，调用方直接按 [LyricLine.startMs] 定位即可，无需自己写解析器。
 *
 * @param lrc        原始 LRC 文本（`[mm:ss.xx]` 标准格式）。
 * @param translated 原始翻译 LRC 文本（若有）。
 * @param yrc        原始逐字歌词文本（网易云 YRC，若有）；QQ 为 null。
 * @param lines      解析好的行（含时间戳、可选逐字、可选翻译）。
 */
data class Lyric(
    val lrc: String,
    val translated: String? = null,
    val yrc: String? = null,
    val lines: List<LyricLine> = emptyList(),
) {
    /** 是否含逐字（卡拉OK）时间轴。 */
    val hasWordByWord: Boolean get() = lines.any { it.words.isNotEmpty() }

    /** 是否完全没有可用歌词。 */
    val isEmpty: Boolean get() = lines.isEmpty() && lrc.isBlank()
}

/**
 * 二维码登录轮询状态。
 */
enum class QrLoginState {
    WAITING,    // 等待扫码
    SCANNED,    // 已扫码，等待确认
    CONFIRMED,  // 已确认，登录成功（此时凭证已写入客户端）
    EXPIRED,    // 二维码过期
    ERROR,
}

/**
 * 二维码创建结果。用 [qrContent] 生成/显示二维码图片，用 [key] 轮询状态。
 *
 * 网易云：[qrContent] 是一个 URL 字符串，需自行渲染成二维码图片。
 * QQ：[qrContent] 已是 `data:image/png;base64,...` 图片，可直接作为图片显示。
 */
data class QrCode(
    val key: String,
    val qrContent: String,
)

/** 歌单/电台（跨平台）。 */
data class MusicPlaylist(
    val source: MusicSource,
    val id: String,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int = 0,
    val description: String = "",
)

/** QQ 登录用户信息。 */
data class QQUserInfo(
    val musicid: String,
    val nickname: String,
    val avatarUrl: String,
)

/** 音乐 API 调用异常。 */
class MusicApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
