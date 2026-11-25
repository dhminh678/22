package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.data.model.FacebookPage
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Phân tích danh sách Page từ Graph API. Chỉ mảng `data` rỗng hợp lệ mới nghĩa là không có Page.
 * Bỏ qua phần tử lỗi nếu còn Page hợp lệ. Trả null nếu phản hồi lỗi hoặc không dùng được.
 */
object FacebookPagesParser {

    internal data class Batch(val pages: List<FacebookPage>, val nextCursor: String?)

    fun parse(body: String): List<FacebookPage>? = parseBatch(body)?.pages

    internal fun parseBatch(body: String): Batch? {
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject ?: return null
        } catch (_: SerializationException) {
            return null
        }
        if ("error" in root) return null
        val data = root["data"] as? JsonArray ?: return null
        val pages = data.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.nonBlankString("id") ?: return@mapNotNull null
            val name = item.nonBlankString("name") ?: return@mapNotNull null
            val token = item.nonBlankString("access_token") ?: return@mapNotNull null
            val tasks = (item["tasks"] as? JsonArray)?.mapNotNull { task ->
                (task as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            FacebookPage(id = id, name = name, accessToken = token, tasks = tasks)
        }.distinctBy { it.id }
        if (data.isNotEmpty() && pages.isEmpty()) return null

        val paging = root["paging"] as? JsonObject
        val nextCursor = if (paging?.nonBlankString("next") != null) {
            (paging["cursors"] as? JsonObject)?.nonBlankString("after") ?: return null
        } else {
            null
        }
        return Batch(pages, nextCursor)
    }

    private fun JsonObject.nonBlankString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content
}
