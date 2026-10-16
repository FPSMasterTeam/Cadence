package top.fpsmaster.music.http

import top.fpsmaster.music.MusicApiException
import top.fpsmaster.music.MusicErrorKind
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MusicHttpTest {
    private val server = ServerSocket(0)
    private val url get() = "http://127.0.0.1:${server.localPort}/"

    @AfterTest
    fun close() = server.close()

    /** 每个连接：读完请求头后交给 [respond] 写原始 HTTP 响应。 */
    private fun serve(respond: (java.io.OutputStream) -> Unit) = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            thread(isDaemon = true) {
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    runCatching { respond(s.getOutputStream()) }
                }
            }
        }
    }

    @Test
    fun `slow trickling response is cut off by the total deadline`() {
        serve { out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\n\r\n".toByteArray())
            out.flush()
            // 每 100ms 吐一个字节：单次 read 永远不会超时，只有总时限能截断
            repeat(1000) {
                out.write('x'.code)
                out.flush()
                Thread.sleep(100)
            }
        }
        val http = MusicHttp(connectTimeoutMs = 2_000, readTimeoutMs = 2_000, totalTimeoutMs = 600)
        val started = System.nanoTime()
        val e = assertFailsWith<MusicApiException> { http.get(url) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(MusicErrorKind.TIMEOUT, e.kind)
        assertTrue(elapsedMs < 3_000, "took $elapsedMs ms")
    }

    @Test
    fun `oversized body is rejected`() {
        serve { out ->
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 4096\r\n\r\n".toByteArray())
            out.write(ByteArray(4096) { 'a'.code.toByte() })
            out.flush()
        }
        val http = MusicHttp(maxBodyBytes = 1024)
        val e = assertFailsWith<MusicApiException> { http.get(url) }
        assertEquals(MusicErrorKind.BAD_RESPONSE, e.kind)
    }

    @Test
    fun `connection refused maps to network error`() {
        val port = server.localPort
        server.close()
        val e = assertFailsWith<MusicApiException> { MusicHttp().get("http://127.0.0.1:$port/") }
        assertEquals(MusicErrorKind.NETWORK, e.kind)
    }

    @Test
    fun `normal response returns body and headers`() {
        serve { out ->
            val body = "{\"ok\":true}".toByteArray(StandardCharsets.UTF_8)
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nSet-Cookie: a=1; Path=/\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }
        val r = MusicHttp().get(url)
        assertEquals(200, r.status)
        assertEquals("{\"ok\":true}", r.body)
        assertEquals("1", r.setCookies["a"])
    }
}
