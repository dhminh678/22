package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.ChannelError
import com.lttbdd.socialagent.data.model.ChannelException
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.model.OAuthToken
import com.lttbdd.socialagent.data.security.PendingChannelWrite
import com.lttbdd.socialagent.data.security.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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
        private val staged = mutableMapOf<PendingChannelWrite, OAuthToken?>()
        override suspend fun saveToken(channelType: ChannelType, accountId: String, token: OAuthToken) {
            storage["${channelType.name}_$accountId"] = token
        }
        override fun getToken(channelType: ChannelType, accountId: String): Flow<OAuthToken?> =
            flowOf(storage["${channelType.name}_$accountId"])
        override suspend fun removeToken(channelType: ChannelType, accountId: String) {
            storage.remove("${channelType.name}_$accountId")
            staged.keys.removeAll { it.type == channelType && it.accountId == accountId }
        }
        override suspend fun stageDisconnect(write: PendingChannelWrite) {
            removeToken(write.type, write.accountId)
            staged[write] = null
        }

        override suspend fun completeFacebookSignOut(write: PendingChannelWrite): PendingChannelWrite {
            error("Bộ kiểm thử mạng không có phiên SDK.")
        }

        override suspend fun stageToken(write: PendingChannelWrite, token: OAuthToken) {
            check(staged.put(write, token) == null)
        }

        override suspend fun loadPendingWrites() = staged.keys.toList()

        override suspend fun loadStagedToken(write: PendingChannelWrite) = staged[write]

        override suspend fun finishPendingWrite(write: PendingChannelWrite, commit: Boolean) {
            check(write in staged)
            val token = staged.remove(write)
            if (commit) {
                if (token == null) removeToken(write.type, write.accountId)
                else saveToken(write.type, write.accountId, token)
            }
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

    private lateinit var store: Store
    private lateinit var accounts: AccountStore
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
        repository = ChannelRepositoryImpl(store, accounts) { _, _ -> }
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

        val restored = ChannelRepositoryImpl(store, accounts) { _, _ -> }
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
        assertTrue(saved.expiresAt >= before + 5184000L * 1000)
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
        assertTrue(saved.expiresAt in before..(System.currentTimeMillis() + 3_600_000L))
        assertNull(saved.scope)
    }

    @Test
    fun `mat mang khi doi token thi NETWORK`() = runTest {
        assertEquals(ChannelError.NETWORK, failureError(repository.connectLinkedInViaBff("m", "s", "v")))
    }
}
