package top.fpsmaster.music

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NeteaseCodeTest {
    private fun obj(s: String) = JsonParser.parseString(s).asJsonObject

    @Test
    fun `code 200 and missing code pass`() {
        checkNeteaseCode(obj("""{"code":200,"result":{}}"""), "/x")
        checkNeteaseCode(obj("""{"result":{}}"""), "/x")
    }

    @Test
    fun `301 is not logged in`() {
        val e = assertFailsWith<MusicApiException> { checkNeteaseCode(obj("""{"code":301,"msg":"需要登录"}"""), "/x") }
        assertEquals(MusicErrorKind.NOT_LOGGED_IN, e.kind)
        assertEquals(301, e.platformCode)
    }

    @Test
    fun `risk control codes are classified`() {
        for (code in listOf(-460, -462, 405)) {
            val e = assertFailsWith<MusicApiException> { checkNeteaseCode(obj("""{"code":$code}"""), "/x") }
            assertEquals(MusicErrorKind.RISK_CONTROL, e.kind)
        }
    }

    @Test
    fun `other codes are api errors`() {
        val e = assertFailsWith<MusicApiException> { checkNeteaseCode(obj("""{"code":"404"}"""), "/x") }
        assertEquals(MusicErrorKind.API_ERROR, e.kind)
        assertEquals(404, e.platformCode)
    }

    @Test
    fun `html and empty bodies are bad responses not gson exceptions`() {
        val gson = Gson()
        for (body in listOf("<html><title>502</title></html>", "", "[1,2]")) {
            val e = assertFailsWith<MusicApiException> { gson.parseObject(body, "t") }
            assertEquals(MusicErrorKind.BAD_RESPONSE, e.kind)
        }
    }

    @Test
    fun `json null members do not throw`() {
        val o = obj("""{"a":null,"b":{},"c":"x","d":"12"}""")
        assertEquals(null, o.get("a").strOrNull())
        assertEquals(null, o.get("b").strOrNull())
        assertEquals("x", o.get("c").strOrNull())
        assertEquals(12, o.get("d").intOrNull())
        assertEquals(null, o.get("c").intOrNull())
        assertEquals(null, o.get("a").asObjOrNull())
    }
}
