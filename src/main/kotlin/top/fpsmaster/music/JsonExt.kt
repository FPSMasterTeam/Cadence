package top.fpsmaster.music

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException

/**
 * 安全的 JSON 成员访问。
 *
 * Gson 的 [JsonObject.getAsJsonObject]/[JsonObject.getAsJsonArray] 在成员为 `null`（JsonNull）时会抛
 * `ClassCastException`（JsonNull 无法转 JsonObject/JsonArray）——真实接口响应里带 `null` 的字段很常见
 * （如网易云 `freeTrialInfo:null`）。这些扩展在缺失/为 null/类型不符时返回 null，避免崩溃。
 */
internal fun JsonObject.objOrNull(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject.arrOrNull(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

/** 元素本身是对象时返回它，否则 null（数组里混进 null 或基本类型时不抛异常）。 */
internal fun JsonElement?.asObjOrNull(): JsonObject? =
    this?.takeIf { it.isJsonObject }?.asJsonObject

/** 基本类型转字符串；JsonNull、对象、数组、缺失都返回 null（`asString` 对它们会抛异常）。 */
internal fun JsonElement?.strOrNull(): String? =
    this?.takeIf { it.isJsonPrimitive }?.asString

/** 数字或数字字符串转 Int；其它情况返回 null（`asInt` 对 "abc" 会抛 NumberFormatException）。 */
internal fun JsonElement?.intOrNull(): Int? =
    this?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

internal fun JsonElement?.longOrNull(): Long? =
    this?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

/**
 * 把响应 body 解析成 JSON 对象。空 body、HTML 错误页、顶层不是对象时抛
 * [MusicErrorKind.BAD_RESPONSE]，而不是让 Gson 的 JsonSyntaxException / null 漏给宿主。
 */
internal fun Gson.parseObject(body: String, what: String): JsonObject {
    val parsed = try {
        fromJson(body, JsonElement::class.java)
    } catch (e: JsonParseException) {
        throw MusicApiException("$what：响应不是 JSON", e, MusicErrorKind.BAD_RESPONSE)
    }
    return parsed.asObjOrNull()
        ?: throw MusicApiException("$what：响应解析失败", null, MusicErrorKind.BAD_RESPONSE)
}
