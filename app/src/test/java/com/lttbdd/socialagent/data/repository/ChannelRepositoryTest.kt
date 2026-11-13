package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelCapability
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.model.OAuthToken
import com.lttbdd.socialagent.data.security.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChannelRepositoryTest {

    private lateinit var repository: ChannelRepositoryImpl
    private lateinit var fakeTokenStore: FakeTokenStore
    private lateinit var fakeAccountStore: FakeAccountStore

    private class FakeAccountStore(var saved: List<StoredChannelAccount> = emptyList()) : ChannelAccountStore {
        override suspend fun load(): List<StoredChannelAccount> = saved
        override suspend fun save(accounts: List<StoredChannelAccount>) {
            saved = accounts
        }
    }

    private class FakeTokenStore : TokenStore {
            val storage = mutableMapOf<String, OAuthToken>()

            override suspend fun saveToken(channelType: ChannelType, accountId: String, token: OAuthToken) {
                storage["${channelType.name}_$accountId"] = token
            }

            override fun getToken(channelType: ChannelType, accountId: String): Flow<OAuthToken?> {
                return flowOf(storage["${channelType.name}_$accountId"])
            }

            override suspend fun removeToken(channelType: ChannelType, accountId: String) {
                storage.remove("${channelType.name}_$accountId")
            }
    }

    @Before
    fun setUp() {
        fakeTokenStore = FakeTokenStore()
        fakeAccountStore = FakeAccountStore()
        repository = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore)
    }

    @Test
    fun `Page ket noi luu han va quyen that cua phien SDK, khong gan cung 60 ngay`() = runTest {
        val user = FacebookUserToken("user_tok", expiresAtMillis = 1_234_567L, grantedScope = "pages_show_list,read_insights")

        val result = repository.connectFacebookSelectedPage(user, FacebookPage("p9", "Page Chin", "page_tok"))

        assertTrue(result.isSuccess)
        val token = fakeTokenStore.storage["FACEBOOK_p9"]!!
        assertEquals("page_tok", token.accessToken)
        assertEquals(1_234_567L, token.expiresAt)
        assertEquals("pages_show_list,read_insights", token.scope)
    }

    @Test
    fun `phien SDK khong tra quyen thi chi luu public_profile`() = runTest {
        repository.connectFacebookSelectedPage(FacebookUserToken("u", 5L, null), FacebookPage("p1", "A", "t"))

        assertEquals("public_profile", fakeTokenStore.storage["FACEBOOK_p1"]!!.scope)
    }

    @Test
    fun `metadata tai khoan duoc luu va khong chua token`() = runTest {
        repository.connectFacebookSelectedPage(FacebookUserToken("u", 5L, "x"), FacebookPage("p1", "Page Mot", "bi_mat_123"))

        assertEquals(listOf(StoredChannelAccount(ChannelType.FACEBOOK, "p1", "Page Mot")), fakeAccountStore.saved)
        assertTrue(!fakeAccountStore.saved.toString().contains("bi_mat_123"))
    }

    @Test
    fun `mo lai app khoi phuc kenh con token`() = runTest {
        fakeAccountStore.saved = listOf(StoredChannelAccount(ChannelType.FACEBOOK, "p1", "Page Mot"))
        fakeTokenStore.saveToken(ChannelType.FACEBOOK, "p1", OAuthToken("t", 10L, "pages_show_list"))
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore)

        val channels = restarted.getConnectedChannels().first()

        assertEquals(listOf("Page Mot"), channels.map { it.displayName })
        assertEquals(ConnectionStatus.CONNECTED, channels.single().status)
    }

    @Test
    fun `tai khoan mat token thi khong khoi phuc`() = runTest {
        fakeAccountStore.saved = listOf(StoredChannelAccount(ChannelType.LINKEDIN, "li1", "Nguoi Dung"))
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore)

        assertTrue(restarted.getConnectedChannels().first().isEmpty())
    }

    @Test
    fun `ngat ket noi xoa token va metadata da luu`() = runTest {
        repository.connectFacebookSelectedPage(FacebookUserToken("u", 5L, "x"), FacebookPage("p1", "A", "t"))

        repository.disconnectChannel(ChannelType.FACEBOOK, "p1")

        assertTrue(fakeAccountStore.saved.isEmpty())
        assertNull(fakeTokenStore.storage["FACEBOOK_p1"])
    }

    @Test
    fun `parseBffToken lay han va scope that tu BFF`() {
        val token = ChannelRepositoryImpl.parseBffToken(
            """{"access_token":"AQtoken","token_type":"bearer","expires_in":5184000,"scope":"openid,profile,w_member_social"}""",
        )!!

        assertEquals("AQtoken", token.accessToken)
        assertEquals(5_184_000L, token.expiresInSeconds)
        assertEquals("openid,profile,w_member_social", token.scope)
    }

    @Test
    fun `parseBffToken khong bia han va scope khi BFF khong tra`() {
        val token = ChannelRepositoryImpl.parseBffToken("""{"access_token":"AQtoken","expires_in":null,"scope":null}""")!!

        assertNull(token.expiresInSeconds)
        assertNull(token.scope)
    }

    @Test
    fun `parseBffToken bo qua han khong hop le`() {
        assertNull(ChannelRepositoryImpl.parseBffToken("""{"access_token":"a","expires_in":0}""")!!.expiresInSeconds)
        assertNull(ChannelRepositoryImpl.parseBffToken("""{"access_token":"a","expires_in":-5}""")!!.expiresInSeconds)
    }

    @Test
    fun `parseBffToken tu choi phan hoi thieu access_token hoac khong phai JSON`() {
        assertNull(ChannelRepositoryImpl.parseBffToken("""{"error":"invalid_grant"}"""))
        assertNull(ChannelRepositoryImpl.parseBffToken("""{"access_token":""}"""))
        assertNull(ChannelRepositoryImpl.parseBffToken("""{"access_token":null}"""))
        assertNull(ChannelRepositoryImpl.parseBffToken("khong phai json"))
        assertNull(ChannelRepositoryImpl.parseBffToken("[]"))
    }

    @Test
    fun `LinkedIn qua BFF tu choi dia chi khong phai HTTPS truoc khi gui bat ky request nao`() = runTest {
        repository.linkedInBffUrl = "http://bff.example.com"

        assertTrue(repository.startLinkedInSession("s", "c").isFailure)
        assertTrue(repository.connectLinkedInViaBff("ex", "s", "v").isFailure)
        assertTrue(fakeTokenStore.storage.isEmpty())
    }

    @Test
    fun `connectFacebookPage connects channel and stores token with computed expiresAt`() = runTest {
        val result = repository.connectFacebookPage(
            authCode = "code_fb_123",
            codeVerifier = "verifier_fb_456",
            redirectUri = "com.lttbdd.socialagent://oauth/callback",
        )

        assertTrue(result.isSuccess)
        val channel = result.getOrThrow()
        assertEquals(ChannelType.FACEBOOK, channel.type)
        assertEquals("fb_page_101", channel.accountId)
        assertEquals(ConnectionStatus.CONNECTED, channel.status)
        assertTrue(channel.capabilities.contains(ChannelCapability.PUBLISH))

        val connectedList = repository.getConnectedChannels().first()
        assertEquals(1, connectedList.size)
        assertEquals("fb_page_101", connectedList[0].accountId)
    }

    @Test
    fun `connectLinkedIn connects channel and stores token`() = runTest {
        val result = repository.connectLinkedIn(
            authCode = "code_li_123",
            codeVerifier = "verifier_li_456",
            redirectUri = "com.lttbdd.socialagent://oauth/callback",
        )

        assertTrue(result.isSuccess)
        val channel = result.getOrThrow()
        assertEquals(ChannelType.LINKEDIN, channel.type)
        assertEquals("li_org_202", channel.accountId)
        assertEquals(ConnectionStatus.CONNECTED, channel.status)

        val connectedList = repository.getConnectedChannels().first()
        assertEquals(1, connectedList.size)
    }

    @Test
    fun `disconnectChannel removes channel from list`() = runTest {
        repository.connectFacebookPage("code_fb", "verifier_fb", "uri")
        assertEquals(1, repository.getConnectedChannels().first().size)

        val disconnectResult = repository.disconnectChannel(ChannelType.FACEBOOK, "fb_page_101")
        assertTrue(disconnectResult.isSuccess)
        assertEquals(0, repository.getConnectedChannels().first().size)
    }

    @Test
    fun `parseFacebookProfile returns id and name for valid json`() {
        val json = """{"id":"1029384756","name":"Agent Fanpage"}"""
        val result = ChannelRepositoryImpl.parseFacebookProfile(json)

        assertEquals("1029384756" to "Agent Fanpage", result)
    }

    @Test
    fun `parseFacebookProfile returns null for empty string or invalid json`() {
        assertNull(ChannelRepositoryImpl.parseFacebookProfile(""))
        assertNull(ChannelRepositoryImpl.parseFacebookProfile("not_json"))
    }

    @Test
    fun `parseFacebookProfile returns null when id or name is missing or blank`() {
        assertNull(ChannelRepositoryImpl.parseFacebookProfile("""{"id":"123"}"""))
        assertNull(ChannelRepositoryImpl.parseFacebookProfile("""{"name":"Agent"}"""))
        assertNull(ChannelRepositoryImpl.parseFacebookProfile("""{"id":"","name":"Agent"}"""))
        assertNull(ChannelRepositoryImpl.parseFacebookProfile("""{"id":"123","name":" "}"""))
    }

    @Test
    fun `getGrantedScope tra ve scope da luu cua token`() = runTest {
        repository.connectFacebookPage(
            authCode = "code_fb_123",
            codeVerifier = "v",
            redirectUri = "com.lttbdd.socialagent://oauth/callback",
        )

        assertEquals(
            "pages_manage_posts,pages_read_engagement",
            repository.getGrantedScope(ChannelType.FACEBOOK, "fb_page_101"),
        )
        assertNull(repository.getGrantedScope(ChannelType.FACEBOOK, "khong_ton_tai"))
    }
}
