package top.fpsmaster.music.store

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MusicCredentialStoreTest {
    private val dir = Files.createTempDirectory("cadence-store")
    private val file = dir.resolve("music_auth.json")

    @Test
    fun `round trips credentials`() {
        MusicCredentialStore(file).apply {
            setNetease("MUSIC_U=abc")
            setQq("123", "Q_KEY")
        }
        val loaded = MusicCredentialStore(file).apply { load() }
        assertEquals("MUSIC_U=abc", loaded.neteaseCookie)
        assertEquals("123", loaded.qqMusicId)
        assertEquals("Q_KEY", loaded.qqMusicKey)
    }

    @Test
    fun `file is owner only on posix and no temp files remain`() {
        if (!dir.fileSystem.supportedFileAttributeViews().contains("posix")) return
        MusicCredentialStore(file).setNetease("MUSIC_U=abc")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals(listOf("music_auth.json"), dir.toFile().list()!!.toList())
    }

    @Test
    fun `load tightens permissions of files written by older versions`() {
        if (!dir.fileSystem.supportedFileAttributeViews().contains("posix")) return
        Files.write(file, """{"neteaseCookie":"x"}""".toByteArray())
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        MusicCredentialStore(file).load()
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }

    @Test
    fun `concurrent writers always leave a complete json file`() {
        val store = MusicCredentialStore(file)
        val threads = (0 until 8).map { t ->
            thread {
                repeat(50) { i ->
                    if (t % 2 == 0) store.setNetease("MUSIC_U=$t-$i") else store.setQq("$t", "key-$i")
                }
            }
        }
        threads.forEach { it.join() }
        val json = JsonParser.parseString(String(Files.readAllBytes(file))).asJsonObject
        assertTrue(json.get("neteaseCookie").asString.startsWith("MUSIC_U="))
        assertEquals(store.qqMusicKey, json.get("qqMusicKey").asString)
    }
}
