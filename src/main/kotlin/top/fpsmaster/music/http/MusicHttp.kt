package top.fpsmaster.music.http

import top.fpsmaster.music.MusicApiException
import top.fpsmaster.music.MusicErrorKind
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * 轻量 HTTP 助手，基于 JDK 内置 [HttpURLConnection]，零第三方依赖，**兼容 Java 8**。
 *
 * 特意关闭自动重定向（QQ 登录流程需要读取 302 的 Location 与 Set-Cookie），
 * 并暴露原始响应头，便于手动处理 cookie。
 *
 * 失败一律抛 [MusicApiException]：网络错误为 [MusicErrorKind.NETWORK]，超过总时限为
 * [MusicErrorKind.TIMEOUT]，响应体超限为 [MusicErrorKind.BAD_RESPONSE]。HTTP 4xx/5xx 不算失败，
 * 照常返回 [Response] 由调用方按状态码处理。
 */
class MusicHttp @JvmOverloads constructor(
    /** 建立 TCP/TLS 连接的超时。 */
    private val connectTimeoutMs: Int = 10_000,
    /** 两次 read 之间的最长间隔。 */
    private val readTimeoutMs: Int = 15_000,
    /** 单次请求的总时限：读响应体时超过即失败（最坏再多等一个 readTimeout）。 */
    private val totalTimeoutMs: Long = 30_000,
    /** 响应体大小上限。 */
    private val maxBodyBytes: Int = 16 * 1024 * 1024,
) {

    data class Response(
        val status: Int,
        val body: String,
        val bytes: ByteArray,
        val headers: Map<String, List<String>>,
    ) {
        /**
         * 从响应头解析出的 Set-Cookie（cookie 名 -> 值）。
         *
         * **跳过空值**：同一响应里常出现同名 cookie 一次真值、一次清空（`p_skey=xxx` 与 `p_skey=`），
         * 若让空值覆盖真值会丢掉关键会话 cookie（QQ 登录 p_skey 就栽在这）。故只记录非空值。
         */
        val setCookies: Map<String, String> by lazy {
            val out = LinkedHashMap<String, String>()
            headers.entries.firstOrNull { it.key.equals("set-cookie", true) }?.value?.forEach { raw ->
                val pair = raw.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
            }
            out
        }

        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    }

    fun get(
        url: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
    ): Response {
        val full = if (query.isEmpty()) url else "$url?${encodeForm(query)}"
        return send(full, "GET", null, null, headers, cookies)
    }

    fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
    ): Response = send(
        url, "POST",
        body = encodeForm(form).toByteArray(StandardCharsets.UTF_8),
        contentType = "application/x-www-form-urlencoded",
        headers = headers, cookies = cookies,
    )

    fun postJson(
        url: String,
        json: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
    ): Response {
        val full = if (query.isEmpty()) url else "$url?${encodeForm(query)}"
        return send(
            full, "POST",
            body = json.toByteArray(StandardCharsets.UTF_8),
            contentType = "application/json",
            headers = headers, cookies = cookies,
        )
    }

    private fun send(
        url: String,
        method: String,
        body: ByteArray?,
        contentType: String?,
        headers: Map<String, String>,
        cookies: Map<String, String>,
    ): Response {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw MusicApiException("网络错误：${e.javaClass.simpleName}", e, MusicErrorKind.NETWORK)
        }
        // readTimeout 只限制两次 read 之间的间隔：服务端慢慢吐字节时请求可以无限挂住。
        // 读 body 的循环每次 read 后检查总截止时间（单次 read 最多阻塞 readTimeout），
        // 所以一次请求最长约为 connectTimeout + totalTimeout + readTimeout。
        // 不用另起线程 disconnect：新版 JDK 的 disconnect 与阻塞中的 read 抢同一把锁，打断不了。
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalTimeoutMs)
        var completed = false
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false // 手动处理 302，读取 Location + Set-Cookie
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs

            if ("User-Agent" !in headers) conn.setRequestProperty("User-Agent", DEFAULT_UA)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (cookies.isNotEmpty()) {
                conn.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            if (contentType != null) conn.setRequestProperty("Content-Type", contentType)

            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }

            val status = conn.responseCode
            val bytes = readBody(conn, status, deadline)
            // headerFields 的 key 可能含 null（状态行）；过滤掉
            val respHeaders = LinkedHashMap<String, List<String>>()
            conn.headerFields.forEach { (k, v) -> if (k != null) respHeaders[k] = v }
            completed = true
            return Response(
                status = status,
                body = String(bytes, StandardCharsets.UTF_8),
                bytes = bytes,
                headers = respHeaders,
            )
        } catch (e: IOException) {
            if (e is SocketTimeoutException) {
                throw MusicApiException("请求超时", e, MusicErrorKind.TIMEOUT)
            }
            throw MusicApiException("网络错误：${e.javaClass.simpleName}", e, MusicErrorKind.NETWORK)
        } finally {
            // 正常读完并关闭流的连接留给 JDK 的 keep-alive 缓存复用（QQ 登录要串行打 4~9 个请求，
            // 每次都 disconnect 就得重新 TLS 握手）；中途失败的连接状态未知，必须断开。
            if (!completed) conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection, status: Int, deadline: Long): ByteArray {
        val stream = try {
            if (status in 200..399) conn.inputStream else conn.errorStream
        } catch (e: IOException) {
            // 3xx/4xx 时 inputStream 可能抛异常，退回 errorStream（登录流程只需 header）
            conn.errorStream
        } ?: return ByteArray(0)
        return stream.use { readLimited(it, deadline) }
    }

    /**
     * 读完整个流。超过 [maxBodyBytes] 或总截止时间直接失败，避免异常响应把整块内容读进内存、
     * 或慢速响应把调用线程挂住。
     */
    private fun readLimited(input: InputStream, deadline: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (System.nanoTime() - deadline > 0) {
                throw MusicApiException("请求超时（超过 ${totalTimeoutMs / 1000} 秒）", null, MusicErrorKind.TIMEOUT)
            }
            if (out.size() + n > maxBodyBytes) {
                throw MusicApiException("响应体超过 ${maxBodyBytes / (1024 * 1024)} MB 上限", null, MusicErrorKind.BAD_RESPONSE)
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"

        fun encodeForm(params: Map<String, String>): String =
            params.entries.joinToString("&") {
                "${enc(it.key)}=${enc(it.value)}"
            }

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}
