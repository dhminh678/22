package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.ChannelError
import com.lttbdd.socialagent.data.model.ChannelException
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.model.OAuthToken
import com.lttbdd.socialagent.data.security.PendingChannelWrite
import com.lttbdd.socialagent.data.security.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ChannelRepositoryTest {

    private lateinit var repository: ChannelRepositoryImpl
    private lateinit var fakeTokenStore: FakeTokenStore
    private lateinit var fakeAccountStore: FakeAccountStore
    private val clearedChannels = mutableListOf<ChannelType>()
    private var cleanupFailure = false
    private var facebookSdkSession: String? = null
    private var facebookSdkLoggedIn: Boolean
        get() = facebookSdkSession != null
        set(value) {
            facebookSdkSession = if (value) "original-session" else null
        }
    private var facebookSignOutCalls = 0
    private var facebookSignOutFailure = false

    private class FakeAccountStore(var saved: List<StoredChannelAccount> = emptyList()) : ChannelAccountStore {
        var loadError = false
        var saveError = false

        override suspend fun load(): List<StoredChannelAccount> {
            if (loadError) throw IOException("Đọc kho lỗi")
            return saved
        }
        override suspend fun upsert(account: StoredChannelAccount) {
            if (saveError) throw IOException("Lưu metadata lỗi")
            saved = saved.filterNot { it.type == account.type && it.accountId == account.accountId } + account
        }

        override suspend fun remove(type: ChannelType, accountId: String) {
            if (saveError) throw IOException("Lưu metadata lỗi")
            saved = saved.filterNot { it.type == type && it.accountId == accountId }
        }
    }

    private class FakeTokenStore : TokenStore {
        val storage = mutableMapOf<String, OAuthToken>()
        var stageError = false
        var disconnectStageError = false
        var finishError = false
        val staged = mutableMapOf<PendingChannelWrite, OAuthToken?>()
        val stagedToken: OAuthToken? get() = staged.values.singleOrNull()
        var failFinishFor: String? = null
        var unreadableStagedFor: String? = null

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
            if (disconnectStageError) throw IOException("Ghi thao tác ngắt kết nối lỗi")
            removeToken(write.type, write.accountId)
            staged[write] = null
        }

        override suspend fun completeFacebookSignOut(write: PendingChannelWrite): PendingChannelWrite {
            check(write in staged)
            val completed = write.copy(facebookSessionToSignOut = null)
            staged.remove(write)
            staged[completed] = null
            return completed
        }

        override suspend fun stageToken(write: PendingChannelWrite, token: OAuthToken) {
            if (stageError) throw IOException("Ghi token tạm lỗi")
            check(staged.keys.none { it.type == write.type && it.accountId == write.accountId })
            staged[write] = token
        }

        override suspend fun loadPendingWrites() = staged.keys.toList()

        override suspend fun loadStagedToken(write: PendingChannelWrite) =
            staged[write].takeUnless { unreadableStagedFor == write.accountId }

        override suspend fun finishPendingWrite(write: PendingChannelWrite, commit: Boolean) {
            if (finishError || failFinishFor == write.accountId) throw IOException("Dọn token tạm lỗi")
            check(write in staged)
            val token = staged.remove(write)
            if (commit) {
                if (token == null) removeToken(write.type, write.accountId)
                else saveToken(write.type, write.accountId, token)
            }
        }
    }

    @Before
    fun setUp() {
        fakeTokenStore = FakeTokenStore()
        fakeAccountStore = FakeAccountStore()
        repository = newRepository()
    }

    private fun newRepository() =
        ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore, object : ChannelDataCleanup {
            override suspend fun clear(type: ChannelType, operationId: String) {
                if (cleanupFailure) throw IOException("Xoá dữ liệu lỗi")
                clearedChannels.add(type)
            }

            override suspend fun currentFacebookSession(): String? = facebookSdkSession

            override suspend fun signOutFacebook(expectedSession: String) {
                if (facebookSdkSession != expectedSession) return
                facebookSignOutCalls++
                if (facebookSignOutFailure) throw IllegalStateException("Đăng xuất Facebook SDK lỗi")
                facebookSdkLoggedIn = false
            }
        })

    @Test
    fun recoveredTokenAppearsInFirstChannelList() = runTest {
        val type = ChannelType.FACEBOOK
        val write = PendingChannelWrite("confirmed", type, "a")
        fakeAccountStore.saved = listOf(StoredChannelAccount(type, "a", "A", write.id))
        fakeTokenStore.stageToken(write, OAuthToken("new-a", 20L))
        fakeTokenStore.unreadableStagedFor = "a"

        val accounts = repository.getConnectedChannels().first()

        assertEquals(20L, repository.getTokenExpiresAt(type, "a"))
        assertEquals(listOf("a"), accounts.map { it.accountId })
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

        val saved = fakeAccountStore.saved.single()
        assertEquals(StoredChannelAccount(ChannelType.FACEBOOK, "p1", "Page Mot"), saved.copy(tokenWriteId = null))
        assertNotNull(saved.tokenWriteId)
        assertTrue(!saved.toString().contains("bi_mat_123"))
    }

    @Test
    fun `mo lai app khoi phuc kenh con token`() = runTest {
        fakeAccountStore.saved = listOf(StoredChannelAccount(ChannelType.FACEBOOK, "p1", "Page Mot"))
        fakeTokenStore.saveToken(ChannelType.FACEBOOK, "p1", OAuthToken("t", 10L, "pages_show_list"))
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore) { _, _ -> }

        val channels = restarted.getConnectedChannels().first()

        assertEquals(listOf("Page Mot"), channels.map { it.displayName })
        assertEquals(ConnectionStatus.CONNECTED, channels.single().status)
    }

    @Test
    fun `kho doc loi thi khong ghi de, tai lai xong van giu kenh cu`() = runTest {
        val saved = StoredChannelAccount(ChannelType.FACEBOOK, "old", "Page cũ")
        fakeAccountStore.saved = listOf(saved)
        fakeTokenStore.saveToken(ChannelType.FACEBOOK, "old", OAuthToken("old_token", 10L))
        fakeAccountStore.loadError = true

        val loadError = runCatching { repository.getConnectedChannels().first() }.exceptionOrNull()
        assertEquals(ChannelError.CHANNELS_LOAD_FAILED, (loadError as ChannelException).error)

        val user = FacebookUserToken("user_tok", 10L, "pages_show_list")
        val page = FacebookPage("new", "Page mới", "new_token")
        val connect = repository.connectFacebookSelectedPage(user, page)
        assertEquals(ChannelError.CHANNELS_LOAD_FAILED, (connect.exceptionOrNull() as ChannelException).error)

        assertTrue(repository.disconnectChannel(ChannelType.FACEBOOK, "old").isFailure)
        assertEquals(listOf(saved), fakeAccountStore.saved)
        assertEquals(setOf("FACEBOOK_old"), fakeTokenStore.storage.keys)

        fakeAccountStore.loadError = false
        assertEquals(listOf("old"), repository.getConnectedChannels().first().map { it.accountId })
        assertTrue(repository.connectFacebookSelectedPage(user, page).isSuccess)
        assertEquals(listOf("old", "new"), fakeAccountStore.saved.map { it.accountId })
    }

    @Test
    fun `luu metadata loi thi xoa token kenh moi va cho phep thu lai`() = runTest {
        val user = FacebookUserToken("user", 10L, null)
        val page = FacebookPage("p1", "Page", "page")
        fakeAccountStore.saveError = true

        val result = repository.connectFacebookSelectedPage(user, page)

        assertEquals(ChannelError.CHANNELS_SAVE_FAILED, (result.exceptionOrNull() as ChannelException).error)
        assertTrue(fakeTokenStore.storage.isEmpty())
        assertTrue(fakeAccountStore.saved.isEmpty())
        assertTrue(repository.getConnectedChannels().first().isEmpty())

        fakeAccountStore.saveError = false
        assertTrue(repository.connectFacebookSelectedPage(user, page).isSuccess)
        assertEquals(listOf("p1"), repository.getConnectedChannels().first().map { it.accountId })
    }

    @Test
    fun `ket noi lai luu metadata loi thi khoi phuc token cu va giu ten cu`() = runTest {
        val old = StoredChannelAccount(ChannelType.FACEBOOK, "p1", "Tên cũ")
        val oldToken = OAuthToken("old", 10L, "pages_show_list", pageTasks = listOf("ANALYZE"))
        fakeAccountStore.saved = listOf(old)
        fakeTokenStore.saveToken(old.type, old.accountId, oldToken)
        fakeAccountStore.saveError = true

        val result = repository.connectFacebookSelectedPage(
            FacebookUserToken("user", 20L, null),
            FacebookPage("p1", "Tên mới", "new"),
        )

        assertEquals(ChannelError.CHANNELS_SAVE_FAILED, (result.exceptionOrNull() as ChannelException).error)
        assertEquals(oldToken, fakeTokenStore.storage["FACEBOOK_p1"])
        assertEquals(listOf(old), fakeAccountStore.saved)
        assertEquals(listOf("Tên cũ"), repository.getConnectedChannels().first().map { it.displayName })
    }

    @Test
    fun `ghi token that bai bao loi luu va khong ghi metadata`() = runTest {
        fakeTokenStore.stageError = true
        val result = repository.connectFacebookSelectedPage(
            FacebookUserToken("user", 20L, null), FacebookPage("p1", "Tên mới", "new"),
        )

        assertEquals(ChannelError.CHANNELS_SAVE_FAILED, (result.exceptionOrNull() as ChannelException).error)
        assertTrue(fakeAccountStore.saved.isEmpty())
        assertTrue(repository.getConnectedChannels().first().isEmpty())
    }

    @Test
    fun `ghi metadata va don token tam deu loi thi mo lai app van giu token cu`() = runTest {
        val type = ChannelType.FACEBOOK
        val old = StoredChannelAccount(type, "p1", "Tên cũ")
        val oldToken = OAuthToken("old", 10L, "pages_show_list")
        fakeAccountStore.saved = listOf(old)
        fakeTokenStore.saveToken(type, "p1", oldToken)
        fakeAccountStore.saveError = true
        fakeTokenStore.finishError = true

        val result = repository.connectFacebookSelectedPage(
            FacebookUserToken("user", 20L, "pages_manage_posts"), FacebookPage("p1", "Tên mới", "new"),
        )

        assertEquals(ChannelError.CHANNELS_SAVE_FAILED, (result.exceptionOrNull() as ChannelException).error)
        assertEquals("new", fakeTokenStore.stagedToken?.accessToken)
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore) { _, _ -> }
        assertEquals("Tên cũ", restarted.getConnectedChannels().first().single().displayName)
        assertEquals(10L, restarted.getTokenExpiresAt(type, "p1"))
        assertEquals("pages_show_list", restarted.getGrantedScope(type, "p1"))

        fakeTokenStore.finishError = false
        restarted.getConnectedChannels().first()
        assertNull(fakeTokenStore.stagedToken)
        assertEquals(oldToken, fakeTokenStore.storage["FACEBOOK_p1"])
    }

    @Test
    fun `app mo lai don token tam khi metadata chua xac nhan ket noi moi`() = runTest {
        val type = ChannelType.FACEBOOK
        val write = PendingChannelWrite("pending", type, "p1")
        fakeTokenStore.stageToken(write, OAuthToken("new", 20L, "pages_manage_posts"))

        assertTrue(repository.getConnectedChannels().first().isEmpty())
        assertNull(repository.getGrantedScope(type, "p1"))
        assertNull(fakeTokenStore.stagedToken)
        assertTrue(fakeTokenStore.storage.isEmpty())
    }

    @Test
    fun `metadata da xac nhan thi doc token moi sau khi mo lai du chua chuyen token`() = runTest {
        val type = ChannelType.FACEBOOK
        val old = StoredChannelAccount(type, "p1", "Tên cũ")
        fakeAccountStore.saved = listOf(old)
        fakeTokenStore.saveToken(type, "p1", OAuthToken("old", 10L, "pages_show_list"))
        fakeTokenStore.finishError = true

        val result = repository.connectFacebookSelectedPage(
            FacebookUserToken("user", 20L, "pages_manage_posts"), FacebookPage("p1", "Tên mới", "new"),
        )

        assertTrue(result.isSuccess)
        assertEquals("old", fakeTokenStore.storage["FACEBOOK_p1"]?.accessToken)
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore) { _, _ -> }
        assertEquals("Tên mới", restarted.getConnectedChannels().first().single().displayName)
        assertEquals(20L, restarted.getTokenExpiresAt(type, "p1"))
        assertEquals("pages_manage_posts", restarted.getGrantedScope(type, "p1"))

        fakeTokenStore.finishError = false
        restarted.getConnectedChannels().first()
        assertEquals("new", fakeTokenStore.storage["FACEBOOK_p1"]?.accessToken)
        assertNull(fakeTokenStore.stagedToken)
    }

    @Test
    fun `loi hoan tat A van cho ket noi va ngat B va giu A sau khoi dong lai`() = runTest {
        val type = ChannelType.FACEBOOK
        val user = FacebookUserToken("user", 20L, "pages_show_list")
        fakeTokenStore.failFinishFor = "a"
        repository.connectFacebookSelectedPage(user, FacebookPage("a", "A", "token-a")).getOrThrow()
        repository.connectFacebookSelectedPage(user, FacebookPage("b", "B", "token-b")).getOrThrow()
        repository.disconnectChannel(type, "b").getOrThrow()

        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore) { _, _ -> }
        assertEquals("a", restarted.getConnectedChannels().first().single().accountId)
        assertEquals(20L, restarted.getTokenExpiresAt(type, "a"))
        assertNull(fakeTokenStore.storage["FACEBOOK_b"])
    }

    @Test
    fun `mat token tam da xac nhan thi bo token cu va cho ket noi lai`() = runTest {
        val type = ChannelType.FACEBOOK
        val write = PendingChannelWrite("pending", type, "p1")
        fakeAccountStore.saved = listOf(StoredChannelAccount(type, "p1", "Page", write.id))
        fakeTokenStore.saveToken(type, "p1", OAuthToken("old", 10L))
        fakeTokenStore.staged[write] = null

        assertTrue(repository.getConnectedChannels().first().isEmpty())
        assertNull(repository.getTokenExpiresAt(type, "p1"))
        repository.connectFacebookSelectedPage(
            FacebookUserToken("user", 40L, null), FacebookPage("p1", "Page", "fresh"),
        ).getOrThrow()
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore) { _, _ -> }
        assertEquals(40L, restarted.getTokenExpiresAt(type, "p1"))
    }

    @Test
    fun `luu B giu nguyen metadata va giao dich cua A khi token A tam thoi khong doc duoc`() = runTest {
        val type = ChannelType.FACEBOOK
        val write = PendingChannelWrite("confirmed", type, "a")
        fakeAccountStore.saved = listOf(StoredChannelAccount(type, "a", "A", write.id))
        fakeTokenStore.stageToken(write, OAuthToken("new-a", 20L))
        fakeTokenStore.unreadableStagedFor = "a"
        fakeTokenStore.failFinishFor = "a"
        assertTrue(repository.getConnectedChannels().first().isEmpty())

        repository.connectFacebookSelectedPage(
            FacebookUserToken("user", 40L, null), FacebookPage("b", "B", "token-b"),
        ).getOrThrow()

        fakeTokenStore.unreadableStagedFor = null
        fakeTokenStore.failFinishFor = null
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore) { _, _ -> }
        assertEquals(setOf("a", "b"), restarted.getConnectedChannels().first().map { it.accountId }.toSet())
        assertEquals(20L, restarted.getTokenExpiresAt(type, "a"))
    }

    @Test
    fun disconnectRecoversMetadataDeletionAfterRestart() = runTest {
        val type = ChannelType.FACEBOOK
        fakeTokenStore.failFinishFor = "a"
        repository.connectFacebookSelectedPage(
            FacebookUserToken("user", 20L, null), FacebookPage("a", "A", "token"),
        ).getOrThrow()
        fakeAccountStore.saveError = true
        val result = repository.disconnectChannel(type, "a")
        assertEquals(ChannelError.DISCONNECT_CLEANUP_FAILED, (result.exceptionOrNull() as ChannelException).error)
        assertTrue(fakeTokenStore.staged.keys.single().disconnect)
        assertTrue(fakeTokenStore.storage.isEmpty())

        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore) { _, _ -> }
        assertTrue(restarted.getConnectedChannels().first().isEmpty())
        assertTrue(fakeTokenStore.staged.keys.single().disconnect)
        fakeAccountStore.saveError = false
        fakeTokenStore.failFinishFor = null
        assertTrue(restarted.getConnectedChannels().first().isEmpty())
        assertTrue(fakeAccountStore.saved.isEmpty())
        assertTrue(fakeTokenStore.staged.isEmpty())
    }

    @Test
    fun `tai khoan mat token thi khong khoi phuc`() = runTest {
        fakeAccountStore.saved = listOf(StoredChannelAccount(ChannelType.LINKEDIN, "li1", "Nguoi Dung"))
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore) { _, _ -> }

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
    fun `disconnectChannel removes channel from list`() = runTest {
        repository.connectFacebookSelectedPage(FacebookUserToken("u", 5L, "x"), FacebookPage("p1", "Page Mot", "t"))
        assertEquals(1, repository.getConnectedChannels().first().size)

        val disconnectResult = repository.disconnectChannel(ChannelType.FACEBOOK, "p1")
        assertTrue(disconnectResult.isSuccess)
        assertEquals(0, repository.getConnectedChannels().first().size)
    }

    @Test
    fun disconnectClearsSharedDataOnlyWhenLastAccountOfThatNetworkIsRemoved() = runTest {
        val facebook = ChannelType.FACEBOOK
        val linkedin = ChannelType.LINKEDIN
        fakeAccountStore.saved = listOf(
            StoredChannelAccount(facebook, "a", "Page A"),
            StoredChannelAccount(facebook, "b", "Page B"),
            StoredChannelAccount(linkedin, "a", "LinkedIn A"),
        )
        fakeTokenStore.saveToken(facebook, "a", OAuthToken("page-a", 1))
        fakeTokenStore.saveToken(facebook, "b", OAuthToken("page-b", 1))
        fakeTokenStore.saveToken(linkedin, "a", OAuthToken("member-a", 1))
        facebookSdkLoggedIn = true

        repository.disconnectChannel(facebook, "a").getOrThrow()

        assertTrue(facebookSdkLoggedIn)
        assertTrue(clearedChannels.isEmpty())
        assertEquals(setOf("FACEBOOK_b", "LINKEDIN_a"), fakeTokenStore.storage.keys)
        assertEquals(setOf(facebook to "b", linkedin to "a"), fakeAccountStore.saved.map { it.type to it.accountId }.toSet())

        repository.disconnectChannel(facebook, "b").getOrThrow()
        repository.disconnectChannel(facebook, "b").getOrThrow()

        assertTrue(!facebookSdkLoggedIn)
        assertEquals(listOf(facebook), clearedChannels)
        assertEquals(setOf("LINKEDIN_a"), fakeTokenStore.storage.keys)
        assertEquals(listOf(linkedin), repository.getConnectedChannels().first().map { it.type })
    }

    @Test
    fun failedFacebookSdkSignOutDoesNotBlockLocalDisconnectCleanup() = runTest {
        val type = ChannelType.FACEBOOK
        repository.connectFacebookSelectedPage(
            FacebookUserToken("user", 5L, null), FacebookPage("a", "Page A", "page-token"),
        ).getOrThrow()
        facebookSdkLoggedIn = true
        facebookSignOutFailure = true

        val disconnect = repository.disconnectChannel(type, "a")

        assertEquals(ChannelError.FACEBOOK_SIGN_OUT_FAILED, (disconnect.exceptionOrNull() as ChannelException).error)
        assertTrue(facebookSdkLoggedIn)
        assertTrue(fakeTokenStore.storage.isEmpty())
        assertTrue(fakeAccountStore.saved.isEmpty())
        assertEquals(listOf(type), clearedChannels)
        assertEquals("original-session", fakeTokenStore.staged.keys.single().facebookSessionToSignOut)
        assertTrue(repository.hasPendingDisconnectCleanup.value)

        facebookSignOutFailure = false
        assertTrue(repository.retryPendingDisconnectCleanup())
        assertFalse(facebookSdkLoggedIn)
        assertTrue(fakeTokenStore.staged.isEmpty())
        assertFalse(repository.hasPendingDisconnectCleanup.value)
    }

    @Test
    fun startupSignsOutTheJournaledFacebookSessionAfterCrashBeforeSdkLogout() = runTest {
        val type = ChannelType.FACEBOOK
        fakeAccountStore.saved = listOf(StoredChannelAccount(type, "a", "Page A"))
        fakeTokenStore.saveToken(type, "a", OAuthToken("page-token", 5L))
        facebookSdkLoggedIn = true
        fakeTokenStore.stageDisconnect(
            PendingChannelWrite(
                "disconnect-a", type, "a",
                disconnect = true,
                clearChannelData = true,
                facebookSessionToSignOut = facebookSdkSession,
            ),
        )

        val restarted = newRepository()
        assertTrue(restarted.getConnectedChannels().first().isEmpty())

        assertEquals(1, facebookSignOutCalls)
        assertFalse(facebookSdkLoggedIn)
        assertEquals(listOf(type), clearedChannels)
        assertTrue(fakeAccountStore.saved.isEmpty())
        assertTrue(fakeTokenStore.staged.isEmpty())
        assertFalse(restarted.hasPendingDisconnectCleanup.value)
    }

    @Test
    fun sdkSignOutCompletionIsJournaledEvenWhenLocalCleanupFails() = runTest {
        val type = ChannelType.FACEBOOK
        repository.connectFacebookSelectedPage(
            FacebookUserToken("user", 5L, null), FacebookPage("a", "Page A", "page-token"),
        ).getOrThrow()
        facebookSdkLoggedIn = true
        cleanupFailure = true

        val disconnect = repository.disconnectChannel(type, "a")

        assertEquals(ChannelError.DISCONNECT_CLEANUP_FAILED, (disconnect.exceptionOrNull() as ChannelException).error)
        assertEquals(1, facebookSignOutCalls)
        assertNull(fakeTokenStore.staged.keys.single().facebookSessionToSignOut)
        cleanupFailure = false
        facebookSdkSession = "new-session"
        assertTrue(newRepository().retryPendingDisconnectCleanup())
        assertEquals("new-session", facebookSdkSession)
        assertEquals(1, facebookSignOutCalls)
    }

    @Test
    fun failedLocalCleanupRemainsRetryableWhenFacebookSignOutAlsoFails() = runTest {
        val type = ChannelType.FACEBOOK
        repository.connectFacebookSelectedPage(
            FacebookUserToken("user", 5L, null), FacebookPage("a", "Page A", "page-token"),
        ).getOrThrow()
        facebookSdkLoggedIn = true
        facebookSignOutFailure = true
        cleanupFailure = true

        val disconnect = repository.disconnectChannel(type, "a")

        assertEquals(ChannelError.DISCONNECT_CLEANUP_FAILED, (disconnect.exceptionOrNull() as ChannelException).error)
        assertTrue(repository.hasPendingDisconnectCleanup.value)
        assertTrue(fakeTokenStore.staged.keys.single().disconnect)

        cleanupFailure = false
        facebookSignOutFailure = false
        facebookSdkSession = "new-session"
        assertTrue(newRepository().retryPendingDisconnectCleanup())

        assertEquals("new-session", facebookSdkSession)
        assertEquals(1, facebookSignOutCalls)
        assertEquals(listOf(type), clearedChannels)
        assertTrue(fakeTokenStore.staged.isEmpty())
    }

    @Test
    fun failedTokenDeletionDoesNotEraseSharedChannelData() = runTest {
        val type = ChannelType.FACEBOOK
        repository.connectFacebookSelectedPage(
            FacebookUserToken("user", 5L, null), FacebookPage("a", "Page A", "page-token"),
        ).getOrThrow()
        fakeTokenStore.disconnectStageError = true

        val disconnect = repository.disconnectChannel(type, "a")

        assertEquals(ChannelError.CHANNELS_SAVE_FAILED, (disconnect.exceptionOrNull() as ChannelException).error)

        assertFalse(repository.hasPendingDisconnectCleanup.value)
        assertTrue(clearedChannels.isEmpty())
        assertTrue("FACEBOOK_a" in fakeTokenStore.storage)
        assertEquals("a", repository.getConnectedChannels().first().single().accountId)
    }

    @Test
    fun failedLocalCleanupResumesAfterRestart() = runTest {
        val type = ChannelType.FACEBOOK
        repository.connectFacebookSelectedPage(
            FacebookUserToken("user", 5L, null), FacebookPage("a", "Page A", "page-token"),
        ).getOrThrow()
        cleanupFailure = true

        assertTrue(repository.disconnectChannel(type, "a").isFailure)
        assertTrue(repository.hasPendingDisconnectCleanup.value)
        assertTrue(fakeTokenStore.storage.isEmpty())
        val pendingWrite = fakeTokenStore.staged.keys.single()
        assertTrue(pendingWrite.clearChannelData)

        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore) { channel, operationId ->
            assertEquals(pendingWrite.id, operationId)
            if (cleanupFailure) throw IOException("Xoá dữ liệu lỗi")
            clearedChannels.add(channel)
        }
        assertTrue(restarted.getConnectedChannels().first().isEmpty())
        assertTrue(restarted.hasPendingDisconnectCleanup.value)

        cleanupFailure = false
        assertTrue(restarted.getConnectedChannels().first().isEmpty())
        assertFalse(restarted.hasPendingDisconnectCleanup.value)

        assertEquals(listOf(type), clearedChannels)
        assertTrue(fakeTokenStore.staged.isEmpty())
        assertTrue(fakeAccountStore.saved.isEmpty())
    }

    @Test
    fun startupFinishesPendingDisconnectEvenWhenChannelListCannotBeRead() = runTest {
        val type = ChannelType.FACEBOOK
        val user = FacebookUserToken("user", 5L, null)
        repository.connectFacebookSelectedPage(user, FacebookPage("a", "Page A", "page-token")).getOrThrow()
        cleanupFailure = true
        assertTrue(repository.disconnectChannel(type, "a").isFailure)
        val disconnect = fakeTokenStore.staged.keys.single()
        fakeAccountStore.loadError = true
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore) { channel, operationId ->
            assertEquals(disconnect.id, operationId)
            clearedChannels.add(channel)
        }

        val load = runCatching { restarted.getConnectedChannels().first() }

        assertEquals(ChannelError.CHANNELS_LOAD_FAILED, (load.exceptionOrNull() as ChannelException).error)
        assertEquals(listOf(type), clearedChannels)
        assertTrue(fakeTokenStore.staged.isEmpty())
        assertFalse(restarted.hasPendingDisconnectCleanup.value)
    }

    @Test
    fun startupClearsSharedDataWhileMetadataStoreIsUnavailable() = runTest {
        val type = ChannelType.FACEBOOK
        val account = StoredChannelAccount(type, "a", "Page A")
        val disconnect = PendingChannelWrite("disconnect-a", type, "a", disconnect = true, clearChannelData = true)
        fakeAccountStore.saved = listOf(account)
        fakeTokenStore.stageDisconnect(disconnect)
        fakeAccountStore.loadError = true
        fakeAccountStore.saveError = true
        val cleanupAttempts = mutableListOf<Pair<ChannelType, String>>()
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore) { channel, operationId ->
            cleanupAttempts.add(channel to operationId)
        }

        val load = runCatching { restarted.getConnectedChannels().first() }

        assertEquals(ChannelError.CHANNELS_LOAD_FAILED, (load.exceptionOrNull() as ChannelException).error)
        assertEquals(listOf(type to disconnect.id), cleanupAttempts)
        assertEquals(listOf(account), fakeAccountStore.saved)
        assertEquals(setOf(disconnect), fakeTokenStore.staged.keys)
        assertTrue(restarted.hasPendingDisconnectCleanup.value)

        fakeAccountStore.saveError = false
        assertTrue(restarted.retryPendingDisconnectCleanup())

        assertEquals(listOf(type to disconnect.id, type to disconnect.id), cleanupAttempts)
        assertTrue(fakeAccountStore.saved.isEmpty())
        assertTrue(fakeTokenStore.staged.isEmpty())
        assertFalse(restarted.hasPendingDisconnectCleanup.value)
    }

    @Test
    fun retryPendingDisconnectDoesNotReadChannelListOrFinalizeUnrelatedTokenWrite() = runTest {
        val type = ChannelType.FACEBOOK
        val user = FacebookUserToken("user", 5L, null)
        repository.connectFacebookSelectedPage(user, FacebookPage("a", "Page A", "page-token")).getOrThrow()
        cleanupFailure = true
        assertTrue(repository.disconnectChannel(type, "a").isFailure)
        val disconnect = fakeTokenStore.staged.keys.single()

        val unrelatedWrite = PendingChannelWrite("pending-token", ChannelType.LINKEDIN, "member")
        fakeTokenStore.stageToken(unrelatedWrite, OAuthToken("member-token", 20L))
        fakeAccountStore.loadError = true
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore) { channel, operationId ->
            assertEquals(disconnect.id, operationId)
            clearedChannels.add(channel)
        }

        assertTrue(restarted.retryPendingDisconnectCleanup())

        assertFalse(restarted.hasPendingDisconnectCleanup.value)
        assertEquals(listOf(type), clearedChannels)
        assertEquals(setOf(unrelatedWrite), fakeTokenStore.staged.keys)
    }

    @Test
    fun pendingSharedDataCleanupCompletesBeforeSavingAnotherAccountOfTheSameNetwork() = runTest {
        val type = ChannelType.FACEBOOK
        val user = FacebookUserToken("user", 5L, null)
        repository.connectFacebookSelectedPage(user, FacebookPage("a", "Page A", "page-token")).getOrThrow()
        cleanupFailure = true
        assertTrue(repository.disconnectChannel(type, "a").isFailure)

        val newPage = FacebookPage("b", "Page B", "new-token")
        assertTrue(repository.connectFacebookSelectedPage(user, newPage).isFailure)
        assertTrue(fakeTokenStore.storage.isEmpty())

        cleanupFailure = false
        facebookSdkLoggedIn = true
        repository.connectFacebookSelectedPage(user, newPage).getOrThrow()

        assertTrue(facebookSdkLoggedIn)
        assertFalse(repository.hasPendingDisconnectCleanup.value)
        assertEquals(listOf(type), clearedChannels)
        assertEquals(setOf("FACEBOOK_b"), fakeTokenStore.storage.keys)
        assertEquals("b", repository.getConnectedChannels().first().single().accountId)
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
        repository.connectFacebookSelectedPage(
            FacebookUserToken("u", 5L, "pages_manage_posts,pages_read_engagement"),
            FacebookPage("p1", "Page Mot", "t"),
        )

        assertEquals(
            "pages_manage_posts,pages_read_engagement",
            repository.getGrantedScope(ChannelType.FACEBOOK, "p1"),
        )
        assertNull(repository.getGrantedScope(ChannelType.FACEBOOK, "khong_ton_tai"))
    }

    @Test
    fun `repository khong con duong ket noi gia nao ghi vao kho that`() = runTest {
        // Chế độ demo nằm ở ViewModel (RAM, bản debug); repository chỉ ghi token thật từ nhà cung cấp.
        assertTrue(fakeTokenStore.storage.isEmpty())
        assertTrue(fakeAccountStore.saved.isEmpty())
        assertTrue(repository.getConnectedChannels().first().isEmpty())
    }
}
