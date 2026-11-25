package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.ChannelError
import com.lttbdd.socialagent.data.model.ChannelException
import com.lttbdd.socialagent.data.model.FacebookRefreshIssue
import com.lttbdd.socialagent.data.model.FacebookRefreshSkip
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookPageAccessIssue
import com.lttbdd.socialagent.data.model.FACEBOOK_REFRESH_RETRY
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.model.OAuthToken
import com.lttbdd.socialagent.data.security.PendingChannelWrite
import com.lttbdd.socialagent.data.security.TokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Test tầng mạng của [ChannelRepositoryImpl] bằng khách HTTP giả (Interceptor trả phản hồi có sẵn):
 * kiểm tra request được dựng đúng (URL, header, body) và cả luồng thành công lẫn lỗi (HTTP lỗi, mất mạng, thiếu trường).
 * Không gọi mạng thật, không dùng token thật.
 */
class ChannelRepositoryNetworkTest {

    private class Store : TokenStore {
        val storage = mutableMapOf<String, OAuthToken>()
        private val staged = mutableMapOf<PendingChannelWrite, OAuthToken>()
        override suspend fun saveToken(channelType: ChannelType, accountId: String, token: OAuthToken) {
            storage["${channelType.name}_$accountId"] = token
        }
        override fun getToken(channelType: ChannelType, accountId: String): Flow<OAuthToken?> =
            flowOf(storage["${channelType.name}_$accountId"])
        override suspend fun removeToken(channelType: ChannelType, accountId: String) {
            storage.remove("${channelType.name}_$accountId")
            staged.keys.removeAll { it.type == channelType && it.accountId == accountId }
        }
        override suspend fun stageToken(write: PendingChannelWrite, token: OAuthToken) {
            check(staged.put(write, token) == null)
        }

        override suspend fun loadPendingWrites() = staged.keys.toList()

        override suspend fun loadStagedToken(write: PendingChannelWrite) = staged[write]

        override suspend fun stageDisconnect(write: PendingChannelWrite) {
            check(staged.put(write, OAuthToken("staged", null)) == null)
        }

        override suspend fun completeFacebookSignOut(write: PendingChannelWrite): PendingChannelWrite = write

        override suspend fun finishPendingWrite(write: PendingChannelWrite, commit: Boolean) {
            val token = staged.remove(write)
            if (commit && token != null) saveToken(write.type, write.accountId, token)
        }
    }

    private class AccountStore : ChannelAccountStore {
        private var saved: List<StoredChannelAccount> = emptyList()

        override suspend fun load(): List<StoredChannelAccount> = saved

        override suspend fun upsert(account: StoredChannelAccount) {
            saved = saved.filterNot { it.type == account.type && it.accountId == account.accountId } + account
        }

        override suspend fun remove(type: ChannelType, accountId: String) {
            saved = saved.filterNot { it.type == type && it.accountId == accountId }
        }
    }

    private class FakeChannelDataCleanup : ChannelDataCleanup {
        override suspend fun currentFacebookSession(): String? = "fb_user_123"
        override suspend fun clear(type: ChannelType, operationId: String) {}
    }

    private lateinit var store: Store
    private lateinit var accounts: AccountStore
    private lateinit var cleanup: FakeChannelDataCleanup
    private lateinit var repository: ChannelRepositoryImpl
    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()
    private val readTimeouts = mutableListOf<Int>()

    /** Phản hồi theo (phương thức + đường dẫn); không khớp thì ném IOException như mất mạng. */
    private var handler: (Request) -> Pair<Int, String> = { throw IOException("khong co mang") }

    @Before
    fun setUp() {
        store = Store()
        accounts = AccountStore()
        cleanup = FakeChannelDataCleanup()
        repository = ChannelRepositoryImpl(store, accounts, cleanup).apply { elapsedRealtime = { 0L } }
        repository.linkedInBffUrl = "https://bff.example.com"
        repository.httpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests.add(request)
            readTimeouts.add(chain.readTimeoutMillis())
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            bodies.add(buffer.readUtf8())
            val (code, body) = handler(request)
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
    }

    private fun failureError(result: Result<*>): ChannelError =
        (result.exceptionOrNull() as ChannelException).error

    @Test
    fun pageAccessUsesStoredTokensAndDistinguishesRevocationFromPermissions() = runTest {
        val user = FacebookUserToken("sdk-user-token", Long.MAX_VALUE, null, "owner")
        val subcodes = mapOf("revoked" to 458, "password" to 460, "permissions" to 492)
        for (page in subcodes.keys + "healthy") {
            repository.connectFacebookSelectedPage(user, FacebookPage(page, page, "token-$page")).getOrThrow()
        }
        handler = { request ->
            val page = checkNotNull(request.header("Authorization")).removePrefix("Bearer token-")
            val subcode = subcodes[page]
            if (subcode == null) 200 to """{"id":"$page"}"""
            else 400 to """{"error":{"code":190,"error_subcode":$subcode}}"""
        }

        repository.checkFacebookPageAccess()
        repository.checkFacebookPageAccess()

        assertEquals(
            mapOf(
                "revoked" to FacebookPageAccessIssue.RECONNECT_REQUIRED,
                "password" to FacebookPageAccessIssue.RECONNECT_REQUIRED,
                "permissions" to FacebookPageAccessIssue.PERMISSION_REQUIRED,
            ),
            repository.observeFacebookPageAccessIssues().first(),
        )
        assertEquals(4, requests.size)
        for (request in requests) {
            assertEquals("graph.facebook.com", request.url.host)
            assertTrue(request.url.encodedPath.endsWith("/me"))
            assertEquals("id", request.url.queryParameter("fields"))
            assertNull(request.url.queryParameter("access_token"))
        }
        assertNull(repository.getTokenExpiresAt(ChannelType.FACEBOOK, "revoked"))
        assertEquals("token-revoked", store.storage["FACEBOOK_revoked"]?.accessToken)
    }

    @Test
    fun pageAccessIgnoresALateFailureAfterReconnectingTheSamePage() = runTest {
        val user = FacebookUserToken("user", Long.MAX_VALUE, null, "owner")
        val page = FacebookPage("page", "Page", "old")
        repository.connectFacebookSelectedPage(user, page).getOrThrow()
        val requested = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        handler = {
            requested.complete(Unit)
            runBlocking { withTimeout(5_000L) { finish.await() } }
            400 to """{"error":{"code":190,"error_subcode":460}}"""
        }
        val check = async { repository.checkFacebookPageAccess() }
        try {
            requested.await()
            repository.connectFacebookSelectedPage(user, page.copy(accessToken = "new")).getOrThrow()
        } finally {
            finish.complete(Unit)
        }
        check.await()

        assertTrue(repository.observeFacebookPageAccessIssues().first().isEmpty())
        assertEquals("new", store.storage["FACEBOOK_page"]?.accessToken)
    }

    @Test
    fun pageAccessRestartsWithoutCooldownAfterCancellationMidBatch() = runTest {
        val user = FacebookUserToken("user", Long.MAX_VALUE, null, "owner")
        for (page in listOf("first", "second", "last")) {
            repository.connectFacebookSelectedPage(user, FacebookPage(page, page, page)).getOrThrow()
        }
        val secondRequested = CompletableDeferred<Unit>()
        val finishSecond = CompletableDeferred<Unit>()
        handler = { request ->
            when (request.header("Authorization")) {
                "Bearer first" -> 200 to """{"id":"first"}"""
                "Bearer second" -> {
                    secondRequested.complete(Unit)
                    runBlocking { withTimeout(5_000L) { finishSecond.await() } }
                    200 to """{"id":"second"}"""
                }
                else -> 400 to """{"error":{"code":190,"error_subcode":458}}"""
            }
        }

        val interrupted = async { repository.checkFacebookPageAccess() }
        try {
            secondRequested.await()
            interrupted.cancel()
        } finally {
            finishSecond.complete(Unit)
        }
        interrupted.cancelAndJoin()
        assertEquals(listOf("Bearer first", "Bearer second"), requests.map { it.header("Authorization") })

        repository.checkFacebookPageAccess()

        assertEquals("Bearer last", requests.last().header("Authorization"))
        assertEquals(
            mapOf("last" to FacebookPageAccessIssue.RECONNECT_REQUIRED),
            repository.observeFacebookPageAccessIssues().first(),
        )
    }

    @Test
    fun pageAccessKeepsPermissionWarningOnNetworkFailureAndClearsItOnSuccess() = runTest {
        val user = FacebookUserToken("user", Long.MAX_VALUE, null, "owner")
        repository.connectFacebookSelectedPage(user, FacebookPage("page", "Page", "page-token")).getOrThrow()
        var now = 0L
        repository.elapsedRealtime = { now }
        handler = { 400 to """{"error":{"code":190,"error_subcode":492}}""" }
        repository.checkFacebookPageAccess()

        now += FACEBOOK_REFRESH_RETRY
        handler = { throw IOException("Mất mạng") }
        repository.checkFacebookPageAccess()
        assertEquals(
            FacebookPageAccessIssue.PERMISSION_REQUIRED,
            repository.observeFacebookPageAccessIssues().first()["page"],
        )

        now += FACEBOOK_REFRESH_RETRY
        handler = { 200 to """{"id":"page"}""" }
        repository.checkFacebookPageAccess()
        assertTrue(repository.observeFacebookPageAccessIssues().first().isEmpty())
    }

    @Test
    fun userPagesRequestDoesNotClassifySubcode492AsAnInvalidSession() = runTest {
        handler = { 400 to """{"error":{"code":190,"error_subcode":492}}""" }

        assertEquals(ChannelError.FACEBOOK_PAGES_LOAD_FAILED, failureError(repository.fetchFacebookPages("user")))
    }

    // ---- Facebook: /me/accounts ----

    @Test
    fun `fetchFacebookPages dung URL, header Authorization va doc duoc tasks`() = runTest {
        handler = { _ ->
            200 to """{"data":[{"id":"1","name":"Page A","access_token":"tokA","tasks":["CREATE_CONTENT","MODERATE"]}]}"""
        }

        val pages = repository.fetchFacebookPages("user_tok").getOrThrow()

        assertEquals(listOf("CREATE_CONTENT", "MODERATE"), pages.single().tasks)
        val request = requests.single()
        assertEquals("graph.facebook.com", request.url.host)
        assertTrue(request.url.encodedPath.endsWith("/me/accounts"))
        assertTrue(request.url.queryParameter("fields")!!.split(",").containsAll(listOf("id", "name", "access_token", "tasks")))
        assertEquals("Bearer user_tok", request.header("Authorization"))
        assertNull("token khong duoc nam trong URL", request.url.queryParameter("access_token"))
    }

    @Test
    fun facebookRefreshFindsTheConnectedPageInTheNextBatch() = runTest {
        val user = FacebookUserToken("old-user", 1L, "pages_show_list", "owner")
        repository.connectFacebookSelectedPage(user, FacebookPage("target", "Target", "old-page")).getOrThrow()
        handler = { request ->
            when (request.url.queryParameter("after")) {
                null -> 200 to """
                    {"data":[{"id":"other","name":"Other","access_token":"other-page"}],
                     "paging":{"cursors":{"after":"next+batch/="},
                     "next":"https://graph.facebook.com/v26.0/owner/accounts?access_token=renewed-user&after=next%2Bbatch%2F%3D"}}
                """.trimIndent()
                "next+batch/=" -> 200 to """
                    {"data":[{"id":"target","name":"Target","access_token":"new-page","tasks":["ANALYZE"]}],
                     "paging":{"cursors":{"after":"last"}}}
                """.trimIndent()
                else -> error("Unexpected pagination cursor")
            }
        }

        val updated = repository.refreshFacebookChannels("owner", expiresBefore = 2L) {
            user.copy(accessToken = "renewed-user", expiresAtMillis = Long.MAX_VALUE)
        }.outcome

        assertEquals(listOf("target"), updated.updated.map { it.accountId })
        assertEquals("new-page", store.storage["FACEBOOK_target"]?.accessToken)
        assertEquals(listOf("ANALYZE"), repository.getPageTasks(ChannelType.FACEBOOK, "target"))
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("graph.facebook.com", request.url.host)
            assertTrue(request.url.encodedPath.endsWith("/me/accounts"))
            assertEquals("Bearer renewed-user", request.header("Authorization"))
            assertNull(request.url.queryParameter("access_token"))
        }
    }

    @Test
    fun manualRetryUpdatesOnlyTheRequestedPageDuringAutomaticCooldown() = runTest {
        val oldUser = FacebookUserToken("old-user", 1L, "pages_show_list", "owner")
        val session = oldUser.copy(accessToken = "sdk-current", expiresAtMillis = Long.MAX_VALUE)
        repository.connectFacebookSelectedPage(oldUser, FacebookPage("target", "Target", "old-target")).getOrThrow()
        repository.connectFacebookSelectedPage(oldUser, FacebookPage("peer", "Peer", "old-peer")).getOrThrow()
        handler = { 200 to """{"data":[{"id":"peer","name":"Peer","access_token":"auto-peer"}]}""" }

        val first = repository.refreshFacebookChannels("owner", expiresBefore = 2L, sessionVersion = { 1L }) { session }
        assertEquals(setOf("target"), first.outcome.retryablePageIds)
        assertEquals(mapOf("target" to FacebookRefreshIssue.PAGE_NOT_RETURNED), first.outcome.issuesByAccountId)
        handler = { request ->
            check(request.url.queryParameter("after") == null)
            200 to """
                {"data":[{"id":"target","name":"Target","access_token":"retry-target"},
                         {"id":"peer","name":"Peer","access_token":"other-peer"}],
                 "paging":{"next":"https://graph.facebook.com/me/accounts?after=next","cursors":{"after":"next"}}}
            """.trimIndent()
        }
        val retry = repository.retryFacebookPage("target", session, version = 1L, sessionVersion = { 1L })

        assertEquals(listOf("target"), retry.outcome.updated.map { it.accountId })
        assertEquals("retry-target", store.storage["FACEBOOK_target"]?.accessToken)
        assertEquals("auto-peer", store.storage["FACEBOOK_peer"]?.accessToken)
        assertEquals(2, requests.size)
        assertEquals("Bearer sdk-current", requests.last().header("Authorization"))
        assertEquals(FacebookRefreshSkip.COOLDOWN, repository.refreshFacebookChannels("owner", 2L, sessionVersion = { 1L }) {
            error("Retry riêng không được reset lịch SDK.")
        }.outcome.skipped)
    }

    @Test
    fun pageRetriesUseCurrentTokenWhileAutomaticSdkRefreshIsWaiting() = runTest {
        val savedUser = FacebookUserToken("original-user", 1L, null, "owner")
        val current = savedUser.copy(accessToken = "current-user", expiresAtMillis = Long.MAX_VALUE)
        repository.connectFacebookSelectedPage(savedUser, FacebookPage("first", "First", "old-first")).getOrThrow()
        repository.connectFacebookSelectedPage(savedUser, FacebookPage("second", "Second", "old-second")).getOrThrow()
        handler = {
            200 to """{"data":[{"id":"first","name":"First","access_token":"new-first"},
                         {"id":"second","name":"Second","access_token":"new-second"}]}"""
        }
        val sdkStarted = CompletableDeferred<Unit>()
        val sdkResult = CompletableDeferred<FacebookUserToken>()
        var sdkCalls = 0
        val automatic = async {
            repository.refreshFacebookChannels("owner", 2L, sessionVersion = { 1L }) {
                sdkCalls++
                sdkStarted.complete(Unit)
                sdkResult.await()
            }
        }
        try {
            sdkStarted.await()
            for (page in listOf("first", "second")) {
                val retry = repository.retryFacebookPage(page, current, version = 1L, sessionVersion = { 1L })
                assertEquals(listOf(page), retry.outcome.updated.map { it.accountId })
                assertEquals("new-$page", store.storage["FACEBOOK_$page"]?.accessToken)
            }
            assertEquals(1, sdkCalls)
            assertFalse(automatic.isCompleted)
            assertEquals(listOf("Bearer current-user", "Bearer current-user"), requests.map { it.header("Authorization") })
        } finally {
            sdkResult.complete(current.copy(accessToken = "renewed-user"))
        }
        automatic.await()
    }

    @Test
    fun pageRetrySkipsGraphWhenItsSdkTokenSnapshotIsFromAnOldSession() = runTest {
        val current = FacebookUserToken("old-session", Long.MAX_VALUE, null, "owner")
        repository.connectFacebookSelectedPage(current, FacebookPage("page", "Page", "old-page")).getOrThrow()

        val result = repository.retryFacebookPage("page", current, version = 1L, sessionVersion = { 2L })

        assertEquals(FacebookRefreshSkip.SDK_SESSION_CHANGED, result.outcome.skipped)
        assertTrue(requests.isEmpty())
        assertEquals("old-page", store.storage["FACEBOOK_page"]?.accessToken)
    }

    @Test
    fun upstreamFailureIsNotTreatedAsAMissingPage() = runTest {
        val user = FacebookUserToken("old-user", 1L, "pages_show_list", "owner")
        repository.connectFacebookSelectedPage(user, FacebookPage("page", "Page", "old-page")).getOrThrow()
        handler = { 503 to """{"error":{"code":2,"message":"Provider unavailable"}}""" }

        val outcome = repository.refreshFacebookChannels("owner", expiresBefore = 2L) {
            user.copy(accessToken = "current-user", expiresAtMillis = Long.MAX_VALUE)
        }.outcome

        assertEquals(mapOf("page" to FacebookRefreshIssue.PROVIDER_UNAVAILABLE), outcome.issuesByAccountId)
        assertEquals(setOf("page"), outcome.retryablePageIds)
        assertTrue(outcome.confirmedPageIds.isEmpty())
        assertTrue(outcome.reconnectAccountIds.isEmpty())
        assertEquals("old-page", store.storage["FACEBOOK_page"]?.accessToken)
    }

    @Test
    fun pageFetchFromPreviousSdkSessionIsDroppedBeforeWriting() = runTest {
        val user = FacebookUserToken("old-user", 1L, "pages_show_list", "owner")
        repository.connectFacebookSelectedPage(user, FacebookPage("page", "Page", "old-page")).getOrThrow()
        val sessionVersion = java.util.concurrent.atomic.AtomicLong(1L)
        handler = {
            sessionVersion.incrementAndGet()
            200 to """{"data":[{"id":"page","name":"Page","access_token":"stale-page"}]}"""
        }

        val outcome = repository.refreshFacebookChannels(
            "owner", expiresBefore = 2L, sessionVersion = sessionVersion::get,
        ) {
            user.copy(accessToken = "current-user", expiresAtMillis = Long.MAX_VALUE)
        }.outcome

        assertEquals(FacebookRefreshSkip.SDK_SESSION_CHANGED, outcome.skipped)
        assertTrue(outcome.updated.isEmpty())
        assertEquals("old-page", store.storage["FACEBOOK_page"]?.accessToken)
    }

    @Test
    fun facebookRefreshReportsInvalidSessionWithoutReplacingThePageToken() = runTest {
        val user = FacebookUserToken("old-user", 1L, "pages_show_list", "owner")
        repository.connectFacebookSelectedPage(user, FacebookPage("page", "Page", "old-page")).getOrThrow()
        handler = { 400 to """{"error":{"type":"OAuthException","code":190}}""" }

        val result = repository.refreshFacebookChannels("owner", expiresBefore = 2L) {
            user.copy(accessToken = "invalid-user", expiresAtMillis = Long.MAX_VALUE)
        }.outcome

        assertEquals(setOf(FacebookRefreshIssue.SESSION_INVALID), result.issues)
        assertEquals(setOf("page"), result.reconnectAccountIds)
        assertTrue(result.updated.isEmpty())
        assertEquals("old-page", store.storage["FACEBOOK_page"]?.accessToken)
    }

    @Test
    fun facebookPagesReportsFailureWhenTheNextBatchFails() = runTest {
        handler = { request ->
            if (request.url.queryParameter("after") == null) {
                200 to """
                    {"data":[{"id":"first","name":"First","access_token":"first-token"}],
                     "paging":{"cursors":{"after":"next"},"next":"https://graph.facebook.com/v26.0/owner/accounts?after=next"}}
                """.trimIndent()
            } else {
                503 to "{}"
            }
        }

        val result = repository.fetchFacebookPages("user_tok")

        assertTrue(result.isFailure)
        assertEquals(ChannelError.FACEBOOK_PAGES_LOAD_FAILED, failureError(result))
        assertEquals(2, requests.size)
    }

    @Test
    fun facebookPagesStopsWhenTheGraphApiRepeatsACursor() = runTest {
        handler = {
            200 to """
                {"data":[],"paging":{"cursors":{"after":"same"},
                 "next":"https://graph.facebook.com/v26.0/owner/accounts?after=same"}}
            """.trimIndent()
        }

        val result = repository.fetchFacebookPages("user_tok")

        assertTrue(result.isFailure)
        assertEquals(ChannelError.FACEBOOK_PAGES_LOAD_FAILED, failureError(result))
        assertEquals(2, requests.size)
    }

    @Test
    fun `fetchFacebookPages HTTP loi thi FACEBOOK_PAGES_LOAD_FAILED`() = runTest {
        handler = { _ -> 400 to """{"error":{"message":"chi tiet tu Meta"}}""" }

        val result = repository.fetchFacebookPages("user_tok")

        assertEquals(ChannelError.FACEBOOK_PAGES_LOAD_FAILED, failureError(result))
    }

    @Test
    fun `fetchFacebookPages HTTP 200 phan biet data rong va payload sai`() = runTest {
        handler = { 200 to """{"data":[]}""" }
        assertEquals(emptyList<FacebookPage>(), repository.fetchFacebookPages("user_tok").getOrThrow())

        for (body in listOf("not_json", """{"error":{"code":190}}""", """{"data":[{"id":"1"}]}""")) {
            handler = { 200 to body }
            assertEquals(
                ChannelError.FACEBOOK_PAGES_LOAD_FAILED,
                failureError(repository.fetchFacebookPages("user_tok")),
            )
        }
    }

    @Test
    fun `fetchFacebookPages mat mang thi NETWORK`() = runTest {
        val result = repository.fetchFacebookPages("user_tok")

        assertEquals(ChannelError.NETWORK, failureError(result))
    }

    @Test
    fun `connectFacebookProfile HTTP loi thi FACEBOOK_PROFILE_FAILED va khong luu token`() = runTest {
        handler = { _ -> 401 to "{}" }

        val result = repository.connectFacebookProfile(FacebookUserToken("u", 1L, null))

        assertEquals(ChannelError.FACEBOOK_PROFILE_FAILED, failureError(result))
        assertTrue(store.storage.isEmpty())
    }

    @Test
    fun `ho so ca nhan va Page thieu quyen luu co khac nhau`() = runTest {
        handler = { 200 to """{"id":"profile","name":"Ho so"}""" }
        val user = FacebookUserToken("user_tok", 5L, null)

        repository.connectFacebookProfile(user).getOrThrow()
        repository.connectFacebookSelectedPage(user, FacebookPage("page", "Page", "page_tok")).getOrThrow()

        val restored = ChannelRepositoryImpl(store, accounts, cleanup)
        assertEquals(true, restored.isFacebookProfile("profile"))
        assertEquals(false, restored.isFacebookProfile("page"))
        assertEquals("public_profile", restored.getGrantedScope(ChannelType.FACEBOOK, "page"))
        assertNull(restored.getPageTasks(ChannelType.FACEBOOK, "page"))
    }

    @Test
    fun `Page ket noi luu Page task de ma tran dung duoc`() = runTest {
        val page = FacebookPage("p1", "Page", "page_tok", tasks = listOf("ANALYZE"))

        repository.connectFacebookSelectedPage(FacebookUserToken("u", 5L, "read_insights"), page)

        assertEquals(listOf("ANALYZE"), repository.getPageTasks(ChannelType.FACEBOOK, "p1"))
        assertNull(repository.getPageTasks(ChannelType.FACEBOOK, "khong_ton_tai"))
    }

    @Test
    fun `moi loi goi Graph API dung phien ban tap trung v26_0`() = runTest {
        handler = { request ->
            if (request.url.encodedPath.endsWith("/me/accounts")) 200 to """{"data":[]}""" else 200 to """{"id":"1","name":"A"}"""
        }

        repository.fetchFacebookPages("user_tok")
        repository.connectFacebookProfile(FacebookUserToken("u", 1L, null))

        assertEquals(2, requests.size)
        requests.forEach { assertTrue(it.url.encodedPath, it.url.encodedPath.startsWith("/${ChannelRepositoryImpl.FACEBOOK_GRAPH_API_VERSION}/")) }
        assertEquals("v26.0", ChannelRepositoryImpl.FACEBOOK_GRAPH_API_VERSION)
    }

    @Test
    fun `goi BFF dung timeout doc dai cho BFF dang ngu, Graph API giu timeout mac dinh`() = runTest {
        handler = { request ->
            if (request.url.host == "bff.example.com") 200 to """{"ok":true}""" else 200 to """{"data":[]}"""
        }

        repository.startLinkedInSession("state_123", "challenge_456")
        repository.fetchFacebookPages("user_tok")

        assertEquals(2, readTimeouts.size)
        assertTrue("BFF can timeout >= 60 giay, thuc te ${readTimeouts[0]} ms", readTimeouts[0] >= 60_000)
        assertTrue("Graph API khong doi timeout, thuc te ${readTimeouts[1]} ms", readTimeouts[1] < 60_000)
    }

    // ---- LinkedIn qua BFF ----

    @Test
    fun `startLinkedInSession gui POST state va code_challenge toi BFF, khong co bi mat`() = runTest {
        handler = { _ -> 200 to """{"ok":true}""" }

        val result = repository.startLinkedInSession("state_123", "challenge_456")

        assertTrue(result.isSuccess)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://bff.example.com/auth/linkedin/start", request.url.toString())
        assertTrue(bodies.single().contains("\"state\":\"state_123\""))
        assertTrue(bodies.single().contains("\"code_challenge\":\"challenge_456\""))
        assertFalse(bodies.single().contains("secret"))
    }

    @Test
    fun `startLinkedInSession BFF tra 429 hoac 503 thi LINKEDIN_SESSION_START_FAILED`() = runTest {
        listOf(429, 503).forEach { code ->
            handler = { _ -> code to """{"error":"busy"}""" }

            assertEquals(ChannelError.LINKEDIN_SESSION_START_FAILED, failureError(repository.startLinkedInSession("s", "c")))
        }
    }

    @Test
    fun `dia chi BFF khong phai HTTPS thi LINKEDIN_CONFIG_INVALID va khong gui gi`() = runTest {
        repository.linkedInBffUrl = "http://bff.example.com"

        val result = repository.startLinkedInSession("s", "c")

        assertEquals(ChannelError.LINKEDIN_CONFIG_INVALID, failureError(result))
        assertTrue(requests.isEmpty())
    }

    private fun linkedInHandler(
        tokenResponse: Pair<Int, String> = 200 to """{"access_token":"AQtoken","token_type":"bearer","expires_in":5184000,"scope":"openid,profile,w_member_social"}""",
        userInfo: Pair<Int, String> = 200 to """{"sub":"abc123","name":"Nguyen Van A"}""",
    ) {
        handler = { request ->
            if (request.url.host == "api.linkedin.com") userInfo else tokenResponse
        }
    }

    @Test
    fun linkedInReconnectDoesNotStoreAnotherAccount() = runTest {
        linkedInHandler()

        val result = repository.connectLinkedInViaBff("exchange", "state", "verifier", "original-account")

        assertEquals(ChannelError.RECONNECT_ACCOUNT_MISMATCH, failureError(result))
        assertTrue(store.storage.isEmpty())
        assertTrue(accounts.load().isEmpty())
    }

    @Test
    fun `connectLinkedInViaBff doi token roi goi userinfo, luu han va scope that`() = runTest {
        linkedInHandler()
        val before = System.currentTimeMillis()

        val account = repository.connectLinkedInViaBff("ma_doi", "state_1", "verifier_1").getOrThrow()

        assertEquals("abc123", account.accountId)
        assertEquals("Nguyen Van A", account.displayName)
        val tokenRequest = requests.first()
        assertEquals("https://bff.example.com/auth/linkedin/token", tokenRequest.url.toString())
        assertTrue(bodies.first().contains("\"exchange_code\":\"ma_doi\""))
        assertTrue(bodies.first().contains("\"code_verifier\":\"verifier_1\""))
        val userRequest = requests.last()
        assertEquals("api.linkedin.com", userRequest.url.host)
        assertEquals("Bearer AQtoken", userRequest.header("Authorization"))
        val saved = store.storage["LINKEDIN_abc123"]!!
        assertEquals("openid,profile,w_member_social", saved.scope)
        assertTrue(checkNotNull(saved.expiresAt) >= before + 5184000L * 1000)
    }

    @Test
    fun linkedInRejectsExplicitlyExpiredTokensBeforeFetchingProfile() = runTest {
        linkedInHandler(tokenResponse = 200 to """{"access_token":"old","expires_in":0}""")

        val result = repository.connectLinkedInViaBff("exchange", "state", "verifier")

        assertEquals(ChannelError.LINKEDIN_TOKEN_INVALID, failureError(result))
        assertEquals(1, requests.size)
        assertTrue(store.storage.isEmpty())
    }

    @Test
    fun linkedInKeepsPreviousTokenIfReplacementExpiresWhileFetchingProfile() = runTest {
        var now = 1_000_000L
        repository.clock = { now }
        linkedInHandler()
        repository.connectLinkedInViaBff("initial", "state", "verifier").getOrThrow()
        val previous = store.storage["LINKEDIN_abc123"]

        handler = { request ->
            if (request.url.host == "api.linkedin.com") {
                now += 2_000L
                200 to """{"sub":"abc123","name":"Member"}"""
            } else {
                200 to """{"access_token":"short-lived","expires_in":1}"""
            }
        }
        val result = repository.connectLinkedInViaBff("exchange", "state", "verifier", "abc123")

        assertEquals(ChannelError.LINKEDIN_TOKEN_INVALID, failureError(result))
        assertEquals(previous, store.storage["LINKEDIN_abc123"])
    }

    @Test
    fun linkedInExpiryDoesNotIncludeTimeSpentFetchingProfile() = runTest {
        var now = 1_000_000L
        repository.clock = { now }
        handler = { request ->
            if (request.url.host == "api.linkedin.com") {
                now += 30_000L
                200 to """{"sub":"abc123","name":"Example Member"}"""
            } else {
                now += 2_000L
                200 to """{"access_token":"test-token","expires_in":3600}"""
            }
        }

        repository.connectLinkedInViaBff("exchange", "state", "verifier").getOrThrow()

        assertEquals(1_002_000L + 3_600_000L, repository.getTokenExpiresAt(ChannelType.LINKEDIN, "abc123"))
    }

    @Test
    fun `connectLinkedInViaBff BFF tu choi thi LINKEDIN_TOKEN_EXCHANGE_FAILED, khong goi userinfo, khong luu token`() = runTest {
        linkedInHandler(tokenResponse = 400 to """{"error":"invalid_grant"}""")

        val result = repository.connectLinkedInViaBff("ma_doi", "s", "v")

        assertEquals(ChannelError.LINKEDIN_TOKEN_EXCHANGE_FAILED, failureError(result))
        assertEquals(1, requests.size)
        assertTrue(store.storage.isEmpty())
    }

    @Test
    fun `connectLinkedInViaBff BFF tra body khong co access_token thi LINKEDIN_TOKEN_INVALID`() = runTest {
        linkedInHandler(tokenResponse = 200 to """{"token_type":"bearer"}""")

        assertEquals(ChannelError.LINKEDIN_TOKEN_INVALID, failureError(repository.connectLinkedInViaBff("m", "s", "v")))
    }

    @Test
    fun `userinfo HTTP loi hoac thieu sub thi LINKEDIN_PROFILE_FAILED va khong luu token`() = runTest {
        linkedInHandler(userInfo = 401 to "{}")
        assertEquals(ChannelError.LINKEDIN_PROFILE_FAILED, failureError(repository.connectLinkedInViaBff("m", "s", "v")))

        linkedInHandler(userInfo = 200 to """{"name":"Khong co sub"}""")
        assertEquals(ChannelError.LINKEDIN_PROFILE_FAILED, failureError(repository.connectLinkedInViaBff("m", "s", "v")))

        assertTrue(store.storage.isEmpty())
    }

    @Test
    fun `userinfo khong co name thi dung ma thanh vien lam ten hien thi`() = runTest {
        linkedInHandler(userInfo = 200 to """{"sub":"abc123"}""")

        val account = repository.connectLinkedInViaBff("m", "s", "v").getOrThrow()

        assertEquals("abc123", account.displayName)
    }

    @Test
    fun `BFF khong tra expires_in thi luu han du phong ngan (1 gio), khong bia han dai`() = runTest {
        linkedInHandler(tokenResponse = 200 to """{"access_token":"AQtoken","token_type":"bearer"}""")
        val before = System.currentTimeMillis()

        repository.connectLinkedInViaBff("m", "s", "v").getOrThrow()

        val saved = store.storage["LINKEDIN_abc123"]!!
        assertTrue(checkNotNull(saved.expiresAt) in before..(System.currentTimeMillis() + 3_600_000L))
        assertNull(saved.scope)
    }

    @Test
    fun `mat mang khi doi token thi NETWORK`() = runTest {
        assertEquals(ChannelError.NETWORK, failureError(repository.connectLinkedInViaBff("m", "s", "v")))
    }
}
