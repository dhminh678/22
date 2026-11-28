package com.lttbdd.socialagent.data.social

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PublishResult
import com.lttbdd.socialagent.core.publishing.PublishErrorReason
import com.lttbdd.socialagent.core.social.SocialChannelClient
import com.lttbdd.socialagent.data.fake.FakeSocialChannelClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException

internal class LinkedInClient(
    private val destinations: PublishDestinations,
    private val http: PublishHttp,
) : SocialChannelClient by FakeSocialChannelClient(ChannelType.LINKEDIN) {
    override val type = ChannelType.LINKEDIN

    override suspend fun publish(draft: Draft, idempotencyKey: String): PublishResult {
        if (!draft.approved || draft.channel != type || draft.text.isBlank() || draft.imageUri != null) {
            return PublishResult.PermanentError(PublishErrorReason.CONTENT_REJECTED)
        }
        val (id, token) = destinations.resolve(type, idempotencyKey)
            ?: return PublishResult.PermanentError(PublishErrorReason.PERMISSION_DENIED)
        val author = memberUrn(id) ?: return PublishResult.PermanentError(PublishErrorReason.PERMISSION_DENIED)
        if (token.isExpired() || token.accessToken.isBlank()) return PublishResult.PermanentError(PublishErrorReason.AUTH_EXPIRED)
        if (token.scope?.split(scopeSeparator)?.contains("w_member_social") != true) {
            return PublishResult.PermanentError(PublishErrorReason.PERMISSION_DENIED)
        }

        val body = buildJsonObject {
            put("author", author)
            put("commentary", formatLinkedInCommentary(draft.text))
            put("visibility", "PUBLIC")
            putJsonObject("distribution") {
                put("feedDistribution", "MAIN_FEED")
                putJsonArray("targetEntities") {}
                putJsonArray("thirdPartyDistributionChannels") {}
            }
            put("lifecycleState", "PUBLISHED")
            put("isReshareDisabledByAuthor", false)
        }.requestBody()
        val request = Request.Builder()
            .url("https://api.linkedin.com/rest/posts")
            .header("Authorization", "Bearer ${token.accessToken}")
            .header("LinkedIn-Version", "202610")
            .header("X-Restli-Protocol-Version", "2.0.0")
            .post(body)
            .build()
        return http.execute(request) { response, now ->
            val header = response.header("Retry-After")
            httpRetry(response.code, header, now)?.let { return@execute it }
            if (response.code == 408) throw SocketTimeoutException()
            if (!response.isSuccessful) return@execute publishError(type, response.code, header, response.readJson(), now)
            val post = response.header("x-restli-id")?.takeIf(postUrn::matches)
                ?: throw IOException("Phản hồi LinkedIn thiếu URN bài.")
            PublishResult.Success(post, "https://www.linkedin.com/feed/update/$post/")
        }
    }

    private companion object {
        val scopeSeparator = Regex("[,\\s]+")
        val postUrn = Regex("urn:li:(?:share|ugcPost):[0-9]+")
    }
}

// Nhận hashtag có ít nhất một chữ cái theo quy tắc viết bài của #27.
private val hashtagPattern = Regex("""(?<![\p{L}\p{M}\p{N}_&/#\\])#(?=[\p{N}_]*\p{L})([\p{L}\p{M}\p{N}_]+)""")

private fun formatLinkedInCommentary(text: String): String = buildString {
    var start = 0
    for (hashtag in hashtagPattern.findAll(text)) {
        appendEscapedLittleText(text.substring(start, hashtag.range.first))
        append("{hashtag|\\#|")
        appendEscapedLittleText(hashtag.groupValues[1])
        append('}')
        start = hashtag.range.last + 1
    }
    appendEscapedLittleText(text.substring(start))
}

private fun StringBuilder.appendEscapedLittleText(text: String) {
    for (character in text) {
        if (character in "|{}@[]()<>#\\*_~") append('\\')
        append(character)
    }
}
