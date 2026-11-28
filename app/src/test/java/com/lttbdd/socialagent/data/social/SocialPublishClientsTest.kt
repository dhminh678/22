package com.lttbdd.socialagent.data.social

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PublishResult
import com.lttbdd.socialagent.core.social.SocialChannelClient
import com.lttbdd.socialagent.core.publishing.PublishErrorReason
import com.lttbdd.socialagent.data.model.OAuthToken
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URLDecoder
import java.time.Instant
import javax.net.ssl.SSLHandshakeException

class SocialPublishClientsTest {
    private var pages = listOf("123" to OAuthToken("test-page", null, isFacebookProfile = false))
    private var members = listOf("member" to OAuthToken("test-member", null, "w_member_social"))
    private val destinations = PublishDestinations(PublishingAccounts {
        if (it == ChannelType.FACEBOOK) pages else members
    })

    @Test
    fun facebookFeedSendsFormEncodedMessageAndLinkWithTokenOnlyInHeader() = runTest {
        val fixture = FakeHttp(body = """{"id":"123_456"}""")
        val client = facebook(fixture)
        val link = "https://example.com/story?q=a+b&lang=vi%20VN"
        val message = "Tiếng Việt + ký tự & dấu =\nXem $link."

        val result = client.publish(draft(client.type).copy(text = message), client.requirePublishKey())

        assertEquals(PublishResult.Success("123_456", "https://www.facebook.com/permalink.php?story_fbid=456&id=123"), result)
        val request = fixture.requests.single()
        assertEquals("POST", request.method)
        assertEquals("graph.facebook.com", request.url.host)
        assertEquals("/v26.0/123/feed", request.url.encodedPath)
        assertEquals("Bearer test-page", request.header("Authorization"))
        assertNull(request.url.query)
        assertFalse(request.url.toString().contains("test-page"))
        val body = requireNotNull(request.body)
        assertEquals("application/x-www-form-urlencoded", body.contentType().toString())
        val encoded = request.bodyText()
        val fields = encoded.split('&').map { field ->
            val (name, value) = field.split('=', limit = 2)
            name to URLDecoder.decode(value, Charsets.UTF_8.name())
        }
        assertEquals(listOf("message" to message, "link" to link), fields)
        assertFalse(encoded.contains("test-page"))
        assertTrue(body.isOneShot())
    }

    @Test
    fun facebookPhotoSendsMultipartAndUsesPostId() = runTest {
        val fixture = FakeHttp(body = """{"id":"99","post_id":"123_456"}""")
        val client = facebook(fixture)
        val result = client.publish(draft(client.type).copy(imageUri = "content://test/image"), client.requirePublishKey())

        assertEquals(PublishResult.Success("123_456", "https://www.facebook.com/permalink.php?story_fbid=456&id=123"), result)
        val request = fixture.requests.single()
        assertEquals("/v26.0/123/photos", request.url.encodedPath)
        assertEquals("multipart", request.body?.contentType()?.type)
        assertEquals("form-data", request.body?.contentType()?.subtype)
        assertTrue(request.bodyText().contains("name=\"caption\""))
        assertTrue(request.bodyText().contains("name=\"source\""))
        assertTrue(request.bodyText().contains("image-bytes"))
    }

    @Test
    fun facebookPhotoWithoutValidPostIdLeavesOutcomeUnknown() = runTest {
        for (body in listOf("""{"id":"123"}""", """{"id":"123","post_id":"invalid"}""")) {
            val fixture = FakeHttp(body = body)
            val client = facebook(fixture)
            val imageDraft = draft(client.type).copy(imageUri = "content://test/image")

            val error = runCatching { client.publish(imageDraft, client.requirePublishKey()) }.exceptionOrNull()

            assertTrue(error is IOException)
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun linkedInUsesMemberUrnAndReturnsRestLiId() = runTest {
        val fixture = FakeHttp(201, headers = mapOf("x-restli-id" to "urn:li:ugcPost:123"))
        val client = linkedIn(fixture)
        val result = client.publish(draft(client.type), client.requirePublishKey())

        assertEquals(PublishResult.Success("urn:li:ugcPost:123", "https://www.linkedin.com/feed/update/urn:li:ugcPost:123/"), result)
        val request = fixture.requests.single()
        assertEquals("https://api.linkedin.com/rest/posts", request.url.toString())
        assertEquals("Bearer test-member", request.header("Authorization"))
        assertEquals("202610", request.header("LinkedIn-Version"))
        assertEquals("2.0.0", request.header("X-Restli-Protocol-Version"))
        assertTrue(request.bodyText().contains("\"author\":\"urn:li:person:member\""))
    }

    @Test
    fun linkedInEncodesHashtagsAndEscapesPlainText() = runTest {
        val fixture = FakeHttp(201, headers = mapOf("x-restli-id" to "urn:li:ugcPost:123"))
        val client = linkedIn(fixture)
        val text = "#Kotlin #Tiếng_Việt\nC# (#Android), #1, url#anchor và {x|y}."

        client.publish(draft(client.type).copy(text = text), client.requirePublishKey())

        val commentary = Json.parseToJsonElement(fixture.requests.single().bodyText())
            .jsonObject.getValue("commentary").jsonPrimitive.content
        assertEquals(
            """{hashtag|\#|Kotlin} {hashtag|\#|Tiếng\_Việt}""" + "\n" +
                """C\# \({hashtag|\#|Android}\), \#1, url\#anchor và \{x\|y\}.""",
            commentary,
        )
    }

    @Test
    fun linkedInWithoutPublishingScopeDoesNotSendRequest() = runTest {
        val fixture = FakeHttp()
        val client = linkedIn(fixture)
        for (scope in listOf(null, "openid profile")) {
            members = listOf("member" to OAuthToken("test-member", null, scope))

            assertEquals(
                PublishResult.PermanentError(PublishErrorReason.PERMISSION_DENIED),
                client.publish(draft(client.type), client.requirePublishKey()),
            )
        }
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun bothClientsReturnRetryAfterAndDoNotRepeatRequests() = runTest {
        val cases = listOf(
            Triple(429, "3", PublishResult.RetryableError(PublishErrorReason.RATE_LIMITED, 3000)),
            Triple(429, "Sun, 06 Nov 1994 08:49:42 GMT", PublishResult.RetryableError(PublishErrorReason.RATE_LIMITED, 5000)),
            Triple(429, null, PublishResult.RetryableError(PublishErrorReason.RATE_LIMITED)),
        )
        for ((status, header, expected) in cases) {
            val fixture = FakeHttp(status, headers = if (header == null) emptyMap() else mapOf("Retry-After" to header))
            for (client in listOf(facebook(fixture), linkedIn(fixture))) {
                assertEquals(expected, client.publish(draft(client.type), client.requirePublishKey()))
            }
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test
    fun bothClientsTreatServerErrorsAsUnknownEvenWithRetryAfter() = runTest {
        for (status in listOf(500, 502, 503, 504, 599)) {
            val fixture = FakeHttp(status, headers = mapOf("Retry-After" to "0"))
            for (client in listOf(facebook(fixture), linkedIn(fixture))) {
                val error = runCatching { client.publish(draft(client.type), client.requirePublishKey()) }.exceptionOrNull()
                assertEquals(IOException::class.java, error?.javaClass)
                assertTrue(error?.message.orEmpty().contains("HTTP $status"))
            }
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test
    fun facebookServiceErrorsAreUnknownEvenWithHttp400() = runTest {
        for (code in listOf(1, 2)) {
            val fixture = FakeHttp(400, """{"error":{"code":$code}}""")
            val client = facebook(fixture)
            val error = runCatching { client.publish(draft(client.type), client.requirePublishKey()) }.exceptionOrNull()
            assertEquals(IOException::class.java, error?.javaClass)
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun linkedInWriteConflictIsRetryableAndPreservesRetryAfter() = runTest {
        val cases = listOf(
            Triple(emptyMap(), """{"code":"CONFLICT","message":"Write conflict"}""", null),
            Triple(mapOf("Retry-After" to "7"), """{"code":"CONFLICT"}""", 7_000L),
            Triple(mapOf("Retry-After" to "Sun, 06 Nov 1994 08:49:42 GMT"), "", 5_000L),
        )
        for ((headers, body, retryAfter) in cases) {
            val fixture = FakeHttp(409, body, headers)
            val client = linkedIn(fixture)

            assertEquals(
                PublishResult.RetryableError(PublishErrorReason.SERVER_ERROR, retryAfter),
                client.publish(draft(client.type), client.requirePublishKey()),
            )
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun linkedInDuplicateConflictRemainsPermanentDespiteRetryAfter() = runTest {
        for (body in listOf(
            """{"code":"DUPLICATE_CONTENT"}""",
            """{"message":"Content is a duplicate of urn:li:share:42"}""",
        )) {
            val fixture = FakeHttp(409, body, mapOf("Retry-After" to "7"))
            val client = linkedIn(fixture)

            assertEquals(
                PublishResult.PermanentError(PublishErrorReason.DUPLICATE_CONTENT),
                client.publish(draft(client.type), client.requirePublishKey()),
            )
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun facebookPolicyBlockIsRetryableAndPreservesRetryAfter() = runTest {
        val cases = listOf(
            Triple(400, emptyMap(), null),
            Triple(403, mapOf("Retry-After" to "7"), 7000L),
        )
        for ((status, headers, retryAfterMillis) in cases) {
            val fixture = FakeHttp(status, """{"error":{"code":368}}""", headers)
            val client = facebook(fixture)

            assertEquals(
                PublishResult.RetryableError(PublishErrorReason.SERVER_ERROR, retryAfterMillis),
                client.publish(draft(client.type), client.requirePublishKey()),
            )
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun providerErrorsMapToStableReasons() = runTest {
        val graph = listOf(190 to PublishErrorReason.AUTH_EXPIRED, 200 to PublishErrorReason.PERMISSION_DENIED,
            100 to PublishErrorReason.CONTENT_REJECTED, 506 to PublishErrorReason.DUPLICATE_CONTENT)
        for ((code, reason) in graph) {
            val fixture = FakeHttp(400, """{"error":{"code":$code,"message":"private payload"}}""")
            val client = facebook(fixture)
            assertEquals(PublishResult.PermanentError(reason), client.publish(draft(client.type), client.requirePublishKey()))
        }
        val linkedIn = listOf(401 to PublishErrorReason.AUTH_EXPIRED, 403 to PublishErrorReason.PERMISSION_DENIED,
            422 to PublishErrorReason.CONTENT_REJECTED, 409 to PublishErrorReason.DUPLICATE_CONTENT)
        for ((code, reason) in linkedIn) {
            val message = if (code == 409) "Content is a duplicate of urn:li:share:42" else "private payload"
            val client = linkedIn(FakeHttp(code, """{"message":"$message"}"""))
            assertEquals(PublishResult.PermanentError(reason), client.publish(draft(client.type), client.requirePublishKey()))
        }
    }

    @Test
    fun connectionFailuresKeepTheirOriginalExceptionType() = runTest {
        for (failure in listOf(UnknownHostException(), ConnectException(), SSLHandshakeException("test"), SocketTimeoutException())) {
            val fixture = FakeHttp(failure = failure)
            for (client in listOf(facebook(fixture), linkedIn(fixture))) {
                val error = runCatching { client.publish(draft(client.type), client.requirePublishKey()) }.exceptionOrNull()
                assertEquals(failure.javaClass, error?.javaClass)
            }
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test
    fun expiredCredentialAndUnapprovedDraftNeverSend() = runTest {
        val fixture = FakeHttp()
        val client = facebook(fixture)
        val key = client.requirePublishKey()
        pages = listOf("123" to OAuthToken("expired", 0, isFacebookProfile = false))
        assertEquals(PublishResult.PermanentError(PublishErrorReason.AUTH_EXPIRED), client.publish(draft(client.type), key))
        assertEquals(
            PublishResult.PermanentError(PublishErrorReason.CONTENT_REJECTED),
            client.publish(draft(client.type).copy(approved = false), key),
        )
        assertTrue(fixture.requests.isEmpty())
    }

    @Test
    fun boundKeyDoesNotSwitchPagesAndAmbiguousDestinationHasNoKey() = runTest {
        val fixture = FakeHttp(body = """{"id":"123_456"}""")
        val client = facebook(fixture)
        val key = client.requirePublishKey()
        pages = pages + ("999" to OAuthToken("other-page", null, isFacebookProfile = false))
        assertNull(destinations.newKey(client.type))
        assertTrue(client.publish(draft(client.type), key) is PublishResult.Success)
        assertEquals("/v26.0/123/feed", fixture.requests.single().url.encodedPath)

        pages = pages.drop(1)
        for (blocked in listOf("legacy", key)) {
            assertEquals(PublishResult.PermanentError(PublishErrorReason.PERMISSION_DENIED), client.publish(draft(client.type), blocked))
        }
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun malformedSuccessDoesNotScheduleAnotherPost() = runTest {
        val fixture = FakeHttp(201, "{}")
        for (client in listOf(facebook(fixture), linkedIn(fixture))) {
            assertTrue(runCatching { client.publish(draft(client.type), client.requirePublishKey()) }.exceptionOrNull() is IOException)
        }
        assertEquals(2, fixture.requests.size)
    }

    private fun facebook(fixture: FakeHttp) =
        FacebookClient(destinations, fixture.http) { "image-bytes".toRequestBody("image/jpeg".toMediaType()) }

    private fun linkedIn(fixture: FakeHttp) = LinkedInClient(destinations, fixture.http)

    private suspend fun SocialChannelClient.requirePublishKey(): String = requireNotNull(destinations.newKey(type))

    private fun draft(type: ChannelType) = Draft("draft", "topic", type, "Bài đã duyệt", approved = true)

    private fun Request.bodyText(): String = Buffer().also { requireNotNull(body).writeTo(it) }.readUtf8()

    private class FakeHttp(
        code: Int = 200,
        body: String = "",
        headers: Map<String, String> = emptyMap(),
        failure: IOException? = null,
    ) {
        val requests = mutableListOf<Request>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            if (failure != null) throw failure
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("fixture").body(body.toResponseBody())
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
        }.build()
        val http = PublishHttp(client) { Instant.parse("1994-11-06T08:49:37Z").toEpochMilli() }
    }
}
