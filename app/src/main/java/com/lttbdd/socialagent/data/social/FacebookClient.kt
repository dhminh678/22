package com.lttbdd.socialagent.data.social

import android.content.ContentResolver
import android.net.Uri
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PublishResult
import com.lttbdd.socialagent.core.publishing.PublishErrorReason
import com.lttbdd.socialagent.core.social.SocialChannelClient
import com.lttbdd.socialagent.data.apikeys.readBytesBounded
import com.lttbdd.socialagent.data.fake.FakeSocialChannelClient
import com.lttbdd.socialagent.data.repository.ChannelRepositoryImpl.Companion.FACEBOOK_GRAPH_API_VERSION
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException

internal class FacebookClient(
    private val destinations: PublishDestinations,
    private val http: PublishHttp,
    private val readImage: suspend (String) -> RequestBody?,
) : SocialChannelClient by FakeSocialChannelClient(ChannelType.FACEBOOK) {
    override val type = ChannelType.FACEBOOK

    override suspend fun publish(draft: Draft, idempotencyKey: String): PublishResult {
        if (!draft.approved || draft.channel != type) return PublishResult.PermanentError(PublishErrorReason.CONTENT_REJECTED)
        val (id, token) = destinations.resolve(type, idempotencyKey)
            ?: return PublishResult.PermanentError(PublishErrorReason.PERMISSION_DENIED)
        if (token.isFacebookProfile != false || !pageId.matches(id)) {
            return PublishResult.PermanentError(PublishErrorReason.PERMISSION_DENIED)
        }
        if (token.isExpired() || token.accessToken.isBlank()) return PublishResult.PermanentError(PublishErrorReason.AUTH_EXPIRED)

        val image = draft.imageUri
        val body = if (image != null) {
            val source = readImage(image) ?: return PublishResult.PermanentError(PublishErrorReason.CONTENT_REJECTED)
            MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("caption", draft.text)
                .addFormDataPart("source", "image", source)
                .build().singleAttempt()
        } else {
            if (draft.text.isBlank()) return PublishResult.PermanentError(PublishErrorReason.CONTENT_REJECTED)
            val link = links.findAll(draft.text).mapNotNull {
                it.value.trimEnd('.', ',', ';', '!', '?').toHttpUrlOrNull()
            }.firstOrNull()
            FormBody.Builder()
                .add("message", draft.text)
                .apply { link?.let { add("link", it.toString()) } }
                .build()
                .singleAttempt()
        }
        val request = Request.Builder()
            .url(graph.newBuilder().addPathSegment(id).addPathSegment(if (image != null) "photos" else "feed").build())
            .header("Authorization", "Bearer ${token.accessToken}")
            .post(body)
            .build()

        return http.execute(request) { response, now ->
            val header = response.header("Retry-After")
            httpRetry(response.code, header, now)?.let { return@execute it }
            if (response.code == 408) throw SocketTimeoutException()
            val payload = response.readJson()
            if (!response.isSuccessful || payload?.containsKey("error") == true) {
                return@execute publishError(type, response.code, header, payload, now)
            }
            val post = payload?.get(if (image == null) "id" else "post_id").text()?.takeIf(postId::matches)
            if (post != null) {
                return@execute PublishResult.Success(
                    post, "https://www.facebook.com/permalink.php?story_fbid=${post.substringAfter('_')}&id=$id",
                )
            }
            // API có thể đã tạo bài, để hàng đợi chuyển sang chờ xác nhận.
            throw IOException("Phản hồi Facebook thiếu ID bài.")
        }
    }

    private companion object {
        val graph = "https://graph.facebook.com/$FACEBOOK_GRAPH_API_VERSION/".toHttpUrl()
        val postId = Regex("[0-9]+(?:_[0-9]+)?")
        val links = Regex("""https?://[^\s<>"()\[\]{}]+""", RegexOption.IGNORE_CASE)
    }
}

internal suspend fun readFacebookImage(resolver: ContentResolver, value: String): RequestBody? =
    withContext(Dispatchers.IO) {
        val uri = Uri.parse(value)
        if (uri.scheme !in setOf(ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE)) return@withContext null
        try {
            val mime = (resolver.getType(uri) ?: "image/*").toMediaTypeOrNull()?.takeIf { it.type == "image" }
                ?: return@withContext null
            resolver.openInputStream(uri)?.use { it.readBytesBounded(4 * 1024 * 1024) }
                ?.takeIf { it.isNotEmpty() }?.toRequestBody(mime)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
