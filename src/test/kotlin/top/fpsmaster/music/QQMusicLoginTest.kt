package top.fpsmaster.music

import top.fpsmaster.music.http.MusicHttp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QQMusicLoginTest {
    /** 按 URL 回放一次扫码确认后的登录链；[onQQLogin] 在换取凭证的请求里触发（模拟授权进行中用户的操作）。 */
    private class FakeHttp(var onQQLogin: () -> Unit = {}) : MusicHttp() {
        private fun resp(status: Int = 200, body: String = "", headers: Map<String, List<String>> = emptyMap()) =
            Response(status, body, body.toByteArray(), headers)

        override fun get(url: String, query: Map<String, String>, headers: Map<String, String>, cookies: Map<String, String>) =
            when {
                url.endsWith("/ptqrshow") -> resp(headers = mapOf("Set-Cookie" to listOf("qrsig=sig1; Path=/")))
                url.endsWith("/ptqrlogin") ->
                    resp(body = "ptuiCB('0','0','https://ssl.ptlogin2.graph.qq.com/check_sig?uin=123&ptsigx=abc','0','ok','nick')")
                url.endsWith("/check_sig") -> resp(headers = mapOf("Set-Cookie" to listOf("p_skey=pk; Path=/")))
                else -> error("unexpected GET $url")
            }

        override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>, cookies: Map<String, String>) =
            resp(302, headers = mapOf("Location" to listOf("https://y.qq.com/portal/wx_redirect.html?code=CODE1&state=state")))

        override fun postJson(url: String, json: String, query: Map<String, String>, headers: Map<String, String>, cookies: Map<String, String>): Response {
            onQQLogin()
            return resp(body = """{"code":0,"req_1":{"code":0,"data":{"musicid":"123","musickey":"KEY"}}}""")
        }
    }

    @Test
    fun `confirmed scan commits credentials`() {
        val api = QQMusicApi(FakeHttp())
        val qr = api.createQrCode()
        assertEquals(QrLoginState.CONFIRMED, api.checkQrCode(qr))
        assertTrue(api.loggedIn)
        assertEquals("123", api.musicid)
    }

    @Test
    fun `clearLogin during authorize discards credentials`() {
        val http = FakeHttp()
        val api = QQMusicApi(http)
        http.onQQLogin = { api.clearLogin() }
        val qr = api.createQrCode()
        assertEquals(QrLoginState.EXPIRED, api.checkQrCode(qr))
        assertFalse(api.loggedIn)
    }

    @Test
    fun `new qr during authorize discards old poll result`() {
        val http = FakeHttp()
        val api = QQMusicApi(http)
        val old = api.createQrCode()
        http.onQQLogin = { http.onQQLogin = {}; api.createQrCode() }
        assertEquals(QrLoginState.EXPIRED, api.checkQrCode(old))
        assertFalse(api.loggedIn)
    }
}
