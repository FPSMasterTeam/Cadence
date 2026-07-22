package top.fpsmaster.music.lyric

import com.google.gson.Gson
import com.google.gson.JsonObject
import top.fpsmaster.music.LyricLine
import top.fpsmaster.music.LyricWord
import kotlin.math.abs

/**
 * 歌词解析器：把原始文本歌词解析成带时间轴的 [LyricLine] 列表。
 *
 * 支持两种格式：
 *  - **标准 LRC**（`[mm:ss.xx]文本`）：兼容单行多时间戳、`[offset:±ms]` 偏移、灵活位数；
 *    元信息标签（`[ti:]`/`[ar:]`/`[al:]`/`[by:]` 等无时间戳的行）自动跳过。
 *  - **网易云 YRC 逐字**（`[start,duration](wStart,wDur,0)字...` + 开头的 `{...}` JSON 元信息行）：
 *    解析出每个字的起点/时长，供卡拉OK高亮；元信息行标记 [LyricLine.isMetadata]。
 *
 * 翻译（[translated]，同为 LRC）会按时间戳对齐到主歌词行：先精确匹配，再取 500ms 内最近的一条。
 *
 * 无副作用、纯函数、不依赖网络/宿主运行时——可单独用于任何已有歌词文本。
 */
object LyricParser {

    private val gson = Gson()

    // YRC 行：[起点ms,时长ms]后跟若干 (字起点,字时长,0)字
    private val YRC_LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val YRC_WORD = Regex("""\((\d+),(\d+),(\d+)\)([^(]+)""")

    // LRC 时间戳：[mm:ss.xx] / [mm:ss] / [mm:ss:xx]（分/秒位数放宽）
    private val LRC_TIME = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")
    private val OFFSET_RE = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    /**
     * 解析歌词。
     *
     * 优先用逐字（[yrc]）；无逐字则用 [lrc]。
     *
     * @param lrc        标准 LRC 文本，可空。
     * @param translated 翻译 LRC 文本，可空；会对齐到结果行的 [LyricLine.translation]。
     * @param yrc        网易云 YRC 逐字文本，可空；给出时优先解析它。
     * @return 按时间升序排列的歌词行；均为空时返回空列表。
     */
    fun parse(lrc: String?, translated: String? = null, yrc: String? = null): List<LyricLine> {
        val usingYrc = !yrc.isNullOrBlank()
        val base = when {
            usingYrc -> parseYrc(yrc)
            !lrc.isNullOrBlank() -> parseLrc(lrc)
            else -> return emptyList()
        }.sortedBy { it.startMs }

        // 普通 LRC 没有行时长，用下一行起点推算（逐字行本身自带时长）
        val withDuration = if (usingYrc) base else base.mapIndexed { i, line ->
            if (line.durationMs > 0 || line.isMetadata) line
            else base.getOrNull(i + 1)
                ?.let { next -> line.copy(durationMs = (next.startMs - line.startMs).coerceAtLeast(0)) }
                ?: line
        }

        if (translated.isNullOrBlank()) return withDuration
        val transMap = parseLrc(translated)
            .filter { it.text.isNotBlank() }
            .associate { it.startMs to it.text }
        if (transMap.isEmpty()) return withDuration

        return withDuration.map { line ->
            val t = transMap[line.startMs]
                ?: transMap.entries.firstOrNull { abs(it.key - line.startMs) < 500 }?.value
            if (t != null) line.copy(translation = t) else line
        }
    }

    private fun parseYrc(yrc: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in yrc.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            // 元信息行：{"t":ms,"c":[{"tx":"..."},...]}
            if (line.startsWith("{")) {
                runCatching {
                    val meta = gson.fromJson(line, JsonObject::class.java)
                    val c = meta.getAsJsonArray("c") ?: return@runCatching
                    val text = c.joinToString("") { it.asJsonObject.get("tx")?.asString ?: "" }
                    val t = meta.get("t")?.takeIf { !it.isJsonNull }?.asLong ?: 0
                    out.add(LyricLine(startMs = t, text = text, isMetadata = true))
                }
                continue
            }

            val m = YRC_LINE.find(line) ?: continue
            val start = m.groupValues[1].toLong()
            val duration = m.groupValues[2].toLong()
            val rest = m.groupValues[3]

            val words = ArrayList<LyricWord>()
            val sb = StringBuilder()
            for (w in YRC_WORD.findAll(rest)) {
                val wStart = w.groupValues[1].toLong()
                val wDur = w.groupValues[2].toLong()
                val wText = w.groupValues[4]
                words.add(LyricWord(wStart, wDur, wText))
                sb.append(wText)
            }
            val text = if (sb.isEmpty() && rest.isNotEmpty()) rest else sb.toString()
            out.add(LyricLine(startMs = start, durationMs = duration, text = text, words = words))
        }
        return out
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val offset = OFFSET_RE.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = ArrayList<LyricLine>()
        for (raw in lrc.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val times = LRC_TIME.findAll(line).toList()
            if (times.isEmpty()) continue
            val text = line.substring(times.last().range.last + 1).trim()
            for (t in times) {
                val min = t.groupValues[1].toLongOrNull() ?: 0L
                val sec = t.groupValues[2].replace(':', '.').toDoubleOrNull() ?: 0.0
                val ms = min * 60_000L + (sec * 1000).toLong() + offset
                out.add(LyricLine(startMs = ms.coerceAtLeast(0), text = text))
            }
        }
        return out
    }
}
