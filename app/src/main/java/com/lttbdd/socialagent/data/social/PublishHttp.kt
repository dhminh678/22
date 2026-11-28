package com.lttbdd.socialagent.data.social

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.PublishResult
import com.lttbdd.socialagent.core.publishing.PublishErrorReason.AUTH_EXPIRED
import com.lttbdd.socialagent.core.publishing.PublishErrorReason.CONTENT_REJECTED
import com.lttbdd.socialagent.core.publishing.PublishErrorReason.DUPLICATE_CONTENT
import com.lttbdd.socialagent.core.publishing.PublishErrorReason.PERMISSION_DENIED
import com.lttbdd.socialagent.core.publishing.PublishErrorReason.RATE_LIMITED
import com.lttbdd.socialagent.core.publishing.PublishErrorReason.SERVER_ERROR
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PublishHttp(
    private val calls: Call.Factory = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(request: Request, parse: (Response, Long) -> PublishResult): PublishResult =
        suspendCancellableCoroutine { continuation ->
            val call = calls.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { parse(it, clock()) }
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(result)
                }
            })
        }
}

private val jsonMedia = "application/json; charset=utf-8".toMediaType()

internal fun JsonObject.requestBody(): RequestBody = toString().toRequestBody(jsonMedia).singleAttempt()

/** Đánh dấu body chỉ được gửi một lần, kể cả follow-up 503 với Retry-After bằng 0. */
internal fun RequestBody.singleAttempt(): RequestBody = object : RequestBody() {
    override fun contentType(): MediaType? = this@singleAttempt.contentType()
    override fun contentLength(): Long = this@singleAttempt.contentLength()
    override fun writeTo(sink: BufferedSink) = this@singleAttempt.writeTo(sink)
    override fun isOneShot(): Boolean = true
}

internal fun Response.readJson(): JsonObject? {
    val text = peekBody(64 * 1024L).string()
    return try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }
}

internal fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull

internal fun retryAfterMillis(header: String?, now: Long): Long? {
    val value = header?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (value.all { it in '0'..'9' }) {
        val seconds = value.trimStart('0').ifEmpty { "0" }.toLongOrNull() ?: return Long.MAX_VALUE
        return seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
    }
    return try {
        val deadline = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        if (deadline <= Instant.ofEpochMilli(now)) 0 else Math.subtractExact(deadline.toEpochMilli(), now)
    } catch (_: DateTimeException) {
        null
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
}

internal fun checkPublishHttpStatus(status: Int, header: String?, now: Long): PublishResult.RetryableError? {
    // Máy chủ có thể đã tạo bài trước khi trả lỗi 5xx. Hàng đợi xử lý IOException
    // tại đây như kết quả chưa rõ và giữ bài ở trạng thái chờ xác nhận.
    if (status in 500..599) throw IOException("Kết quả đăng bài chưa rõ (HTTP $status).")
    if (status != 429) return null
    return PublishResult.RetryableError(RATE_LIMITED, retryAfterMillis(header, now))
}

/**
 * Graph: 102/190 là phiên lỗi, riêng 190/492 là mất vai trò Page. 3/10/200–299 là lỗi quyền.
 * 506 là bài trùng, 100/1609005 là lỗi nội dung, 4/17/32/341/613/80001 là giới hạn, 1/2 là lỗi dịch vụ.
 * 368 là chặn tạm thời do chính sách, cần chờ rồi thử lại theo Retry-After nếu có.
 * LinkedIn: 401 là phiên lỗi, 403 là lỗi quyền, 400/422 là lỗi nội dung.
 * 409 là xung đột ghi có thể thử lại, trừ khi LinkedIn trả mã hoặc thông báo trùng rõ ràng.
 */
internal fun publishError(
    channel: ChannelType,
    status: Int,
    header: String?,
    body: JsonObject?,
    now: Long,
): PublishResult {
    checkPublishHttpStatus(status, header, now)?.let { return it }
    val reason = if (channel == ChannelType.FACEBOOK) {
        val error = body?.get("error") as? JsonObject
        val code = error?.get("code").text()?.toIntOrNull()
        when {
            code == 190 && error?.get("error_subcode").text() == "492" -> PERMISSION_DENIED
            code == 102 || code == 190 || status == 401 -> AUTH_EXPIRED
            code == 506 -> DUPLICATE_CONTENT
            code in graphRateLimits -> return PublishResult.RetryableError(RATE_LIMITED, retryAfterMillis(header, now))
            code == 1 || code == 2 -> throw IOException("Kết quả đăng Facebook chưa rõ.")
            code == 368 ->
                return PublishResult.RetryableError(SERVER_ERROR, retryAfterMillis(header, now))
            code == 3 || code == 10 || (code != null && code in 200..299) || status == 403 -> PERMISSION_DENIED
            else -> CONTENT_REJECTED
        }
    } else {
        when {
            status == 401 -> AUTH_EXPIRED
            status == 403 -> PERMISSION_DENIED
            body?.get("code").text() == "DUPLICATE_CONTENT" ||
                duplicatePost.containsMatchIn(body?.get("message").text().orEmpty()) -> DUPLICATE_CONTENT
            status == 409 -> return PublishResult.RetryableError(SERVER_ERROR, retryAfterMillis(header, now))
            else -> CONTENT_REJECTED
        }
    }
    return PublishResult.PermanentError(reason)
}

private val graphRateLimits = setOf(4, 17, 32, 341, 613, 80001)
private val duplicatePost = Regex("""\b(?:duplicate (?:of |post\b|content\b)|(?:post|content) already exists)""", RegexOption.IGNORE_CASE)
