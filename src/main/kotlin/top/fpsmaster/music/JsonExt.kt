package top.fpsmaster.music

import com.google.gson.JsonArray
import com.google.gson.JsonObject

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
