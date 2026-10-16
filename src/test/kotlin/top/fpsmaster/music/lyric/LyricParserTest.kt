package top.fpsmaster.music.lyric

import top.fpsmaster.music.LyricWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class LyricParserTest {

    @Test
    fun `parses standard LRC into timed lines`() {
        val lrc = """
            [00:00.00]作词 : A
            [00:12.34]第一句
            [00:15.50]第二句
        """.trimIndent()
        val lines = LyricParser.parse(lrc)
        assertEquals(3, lines.size)
        assertEquals(0, lines[0].startMs)
        assertEquals(12_340, lines[1].startMs)
        assertEquals("第一句", lines[1].text)
        // 行时长由下一行推算
        assertEquals(15_500 - 12_340, lines[1].durationMs)
        // 末行时长为 0
        assertEquals(0, lines.last().durationMs)
    }

    @Test
    fun `handles multiple timestamps on one line`() {
        val lrc = "[00:01.00][00:05.00]副歌"
        val lines = LyricParser.parse(lrc)
        assertEquals(2, lines.size)
        assertEquals(1_000, lines[0].startMs)
        assertEquals(5_000, lines[1].startMs)
        assertTrue(lines.all { it.text == "副歌" })
    }

    @Test
    fun `applies offset tag`() {
        val lrc = """
            [offset:-500]
            [00:10.00]偏移后应为 9500ms
        """.trimIndent()
        val lines = LyricParser.parse(lrc)
        assertEquals(1, lines.size)
        assertEquals(9_500, lines[0].startMs)
    }

    @Test
    fun `skips metadata-only tags without timestamps`() {
        val lrc = """
            [ti:标题]
            [ar:歌手]
            [al:专辑]
            [00:03.00]正文
        """.trimIndent()
        val lines = LyricParser.parse(lrc)
        assertEquals(1, lines.size)
        assertEquals("正文", lines[0].text)
    }

    @Test
    fun `parses netease YRC word-by-word`() {
        // [行起点,行时长](字起点,字时长,0)字...
        val yrc = "[1000,2000](1000,500,0)你(1500,500,0)好(2000,1000,0)世界"
        val lines = LyricParser.parse(lrc = null, yrc = yrc)
        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals(1000, line.startMs)
        assertEquals(2000, line.durationMs)
        assertEquals("你好世界", line.text)
        assertEquals(3, line.words.size)
        assertEquals(LyricWord(1000, 500, "你"), line.words[0])
        assertEquals(LyricWord(2000, 1000, "世界"), line.words[2])
        assertTrue(lines.any { it.words.isNotEmpty() })
    }

    @Test
    fun `parses YRC metadata json line`() {
        val yrc = """
            {"t":0,"c":[{"tx":"作词: "},{"tx":"某人"}]}
            [500,1000](500,1000,0)歌词
        """.trimIndent()
        val lines = LyricParser.parse(lrc = null, yrc = yrc)
        assertEquals(2, lines.size)
        assertTrue(lines[0].isMetadata)
        assertEquals("作词: 某人", lines[0].text)
        assertFalse(lines[1].isMetadata)
    }

    @Test
    fun `yrc takes priority over lrc`() {
        val lrc = "[00:01.00]来自LRC"
        val yrc = "[1000,1000](1000,1000,0)来自YRC"
        val lines = LyricParser.parse(lrc = lrc, yrc = yrc)
        assertEquals("来自YRC", lines[0].text)
        assertTrue(lines[0].words.isNotEmpty())
    }

    @Test
    fun `aligns translation to lines by timestamp`() {
        val lrc = "[00:01.00]Hello\n[00:03.00]World"
        val tlyric = "[00:01.00]你好\n[00:03.20]世界" // 第二条差 200ms，应按 500ms 容差对齐
        val lines = LyricParser.parse(lrc, translated = tlyric)
        assertEquals("你好", lines[0].translation)
        assertEquals("世界", lines[1].translation)
    }

    @Test
    fun `returns empty for all-blank input`() {
        assertTrue(LyricParser.parse(null).isEmpty())
        assertTrue(LyricParser.parse("").isEmpty())
        assertTrue(LyricParser.parse("   ", yrc = "  ").isEmpty())
    }

    @Test
    fun `yrc keeps words that are or start with an opening parenthesis`() {
        val yrc = "[1000,1000](1000,300,0)Hold (1300,100,0)((1400,200,0)ooh(1600,100,0)) (1700,300,0)me"
        val line = LyricParser.parse(lrc = null, yrc = yrc).single()
        assertEquals("Hold (ooh) me", line.text)
        assertEquals(listOf("Hold ", "(", "ooh", ") ", "me"), line.words.map { it.text })
        assertEquals(LyricWord(1300, 100, "("), line.words[1])
    }

    @Test
    fun `translation goes to the nearest timestamp within tolerance`() {
        // 两条翻译都在 500ms 内：应取差值最小的 3.30，而不是先出现的 2.60
        val lrc = "[00:03.20]Line"
        val tlyric = "[00:02.80]远的\n[00:03.30]近的"
        val lines = LyricParser.parse(lrc, translated = tlyric)
        assertEquals("近的", lines[0].translation)
    }
}
