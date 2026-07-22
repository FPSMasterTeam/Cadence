package top.fpsmaster.music.http

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 轻量 HTTP 助手，基于 JDK 内置 [HttpURLConnection]，零第三方依赖，**兼容 Java 8**。
 *
 * 特意关闭自动重定向（QQ 登录流程需要读取 302 的 Location 与 Set-Cookie），
 * 并暴露原始响应头，便于手动处理 cookie。
 */
class MusicHttp {

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
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = false // 手动处理 302，读取 Location + Set-Cookie
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000

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
        val bytes = readBody(conn, status)
        // headerFields 的 key 可能含 null（状态行）；过滤掉
        val respHeaders = LinkedHashMap<String, List<String>>()
        conn.headerFields.forEach { (k, v) -> if (k != null) respHeaders[k] = v }
        conn.disconnect()

        return Response(
            status = status,
            body = String(bytes, StandardCharsets.UTF_8),
            bytes = bytes,
            headers = respHeaders,
        )
    }

    private fun readBody(conn: HttpURLConnection, status: Int): ByteArray {
        return try {
            val stream = if (status in 200..399) conn.inputStream else conn.errorStream
            stream?.use { it.readBytes() } ?: ByteArray(0)
        } catch (e: IOException) {
            // 3xx/4xx 时 inputStream 可能抛异常，退回 errorStream（登录流程只需 header）
            runCatching { conn.errorStream?.use { it.readBytes() } }.getOrNull() ?: ByteArray(0)
        }
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
