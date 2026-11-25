package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.ChannelError
import com.lttbdd.socialagent.data.model.ChannelException
import com.lttbdd.socialagent.data.model.FacebookRefreshIssue
import com.lttbdd.socialagent.data.model.FacebookRefreshSkip
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.model.OAuthToken
import com.lttbdd.socialagent.data.security.PendingChannelWrite
import com.lttbdd.socialagent.data.security.TokenStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
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

    private class FakeAccountStore(var saved: List<StoredChannelAccount> = emptyList()) : ChannelAccountStore {
        var loadError = false
        var saveError = false
        var failUpsertFor: String? = null

        override suspend fun load(): List<StoredChannelAccount> {
            if (loadError) throw IOException("Đọc kho lỗi")
            return saved
        }
        override suspend fun upsert(account: StoredChannelAccount) {
            if (saveError || failUpsertFor == account.accountId) throw IOException("Lưu metadata lỗi")
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
        var afterStage: () -> Unit = {}
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

        override suspend fun stageToken(write: PendingChannelWrite, token: OAuthToken) {
            if (stageError) throw IOException("Ghi token tạm lỗi")
            check(staged.keys.none { it.type == write.type && it.accountId == write.accountId })
            staged[write] = token
            afterStage()
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
        repository = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore).apply { elapsedRealtime = { 0L } }
    }

    @Test
    fun recoveredTokenAppearsInFirstChannelList() = runTest {
        val type = ChannelType.FACEBOOK
        val write = PendingChannelWrite("confirmed", type, "a")
        fakeAccountStore.saved = listOf(StoredChannelAccount(type, "a", "A", write.id))
        fakeTokenStore.stageToken(write, OAuthToken("new-a", 20L))
        fakeTokenStore.unreadableStagedFor = "a"

        val accounts = repository.getConnectedChannels().first()

        assertNull(repository.getTokenExpiresAt(type, "a"))
        assertEquals("new-a", fakeTokenStore.storage["FACEBOOK_a"]?.accessToken)
        assertEquals(listOf("a"), accounts.map { it.accountId })
    }

    @Test
    fun facebookPageStoredBeforeUpgradeRequiresReconnectWithoutReportingAnExpiry() = runTest {
        val type = ChannelType.FACEBOOK
        fakeAccountStore.saved = listOf(StoredChannelAccount(type, "legacy", "Page"))
        val old = OAuthToken("legacy-token", 1L, isFacebookProfile = false)
        fakeTokenStore.saveToken(type, "legacy", old)
        assertNull(repository.getTokenExpiresAt(type, "legacy"))
        assertTrue(repository.needsFacebookReconnect("legacy"))
        val skipped = repository.refreshFacebookChannels("owner", expiresBefore = 10L) {
            error("Không được gọi SDK khi chưa xác nhận chủ sở hữu.")
        }.outcome
        assertEquals(FacebookRefreshSkip.NO_ELIGIBLE_CHANNELS, skipped.skipped)

        val user = FacebookUserToken("user", 20L, "pages_show_list", "owner")
        repository.connectFacebookSelectedPage(user, FacebookPage("legacy", "Page", "new-page")).getOrThrow()
        assertEquals(false, repository.needsFacebookReconnect("legacy"))
        assertNull(repository.getTokenExpiresAt(type, "legacy"))
    }

    @Test
    fun expiredFacebookUserSessionRequestsReconnectWithoutInvalidatingPageToken() = runTest {
        val type = ChannelType.FACEBOOK
        val user = FacebookUserToken("old-user", 1L, "pages_show_list", "owner")
        repository.connectFacebookSelectedPage(user, FacebookPage("page", "Page", "old-page")).getOrThrow()
        repository.connectFacebookSelectedPage(
            user.copy(userId = "different-owner"), FacebookPage("other", "Other page", "other-token"),
        ).getOrThrow()
        repository.clock = { 5L }

        val outcome = repository.refreshFacebookChannels("owner", expiresBefore = 10L) { user }.outcome

        assertEquals(setOf(FacebookRefreshIssue.USER_SESSION_EXPIRED), outcome.issues)
        assertEquals(setOf("page"), outcome.reconnectAccountIds)
        assertNull(repository.getTokenExpiresAt(type, "page"))
        assertEquals(1L, repository.getFacebookPageSessionExpiresAt("page"))
        assertEquals("old-page", fakeTokenStore.storage["FACEBOOK_page"]?.accessToken)
    }

    @Test
    fun unchangedFacebookPageTokenDoesNotKeepAnUnverifiedExpiry() = runTest {
        val type = ChannelType.FACEBOOK
        val expiryFromPreviousVersion = 1L
        fakeAccountStore.saved = listOf(StoredChannelAccount(type, "page", "Page"))
        fakeTokenStore.saveToken(
            type, "page", OAuthToken(
                "same-page-token",
                expiresAt = expiryFromPreviousVersion,
                isFacebookProfile = false,
                facebookUserId = "owner",
                facebookUserSessionExpiresAt = expiryFromPreviousVersion,
            ),
        )
        repository.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[{"id":"page","name":"Page","access_token":"same-page-token"}]}""".toResponseBody())
                .build()
        }.build()

        assertNull(repository.getTokenExpiresAt(type, "page"))
        val check = repository.refreshFacebookChannels("owner", expiresBefore = 2L) {
            FacebookUserToken("refreshed-user", Long.MAX_VALUE, "pages_show_list", "owner")
        }

        assertEquals(listOf("page"), check.outcome.updated.map { it.accountId })
        assertNull(fakeTokenStore.storage["FACEBOOK_page"]?.expiresAt)
        assertEquals(Long.MAX_VALUE, fakeTokenStore.storage["FACEBOOK_page"]?.facebookUserSessionExpiresAt)
        assertNull(repository.getTokenExpiresAt(type, "page"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun simultaneousRepositoryCallersShareTheSameSdkCooldown() = runTest {
        val type = ChannelType.FACEBOOK
        fakeAccountStore.saved = listOf(StoredChannelAccount(type, "owner", "Profile"))
        fakeTokenStore.saveToken(type, "owner", OAuthToken("old", 1L, isFacebookProfile = true))
        val sdkStarted = CompletableDeferred<Unit>()
        val sdkResult = CompletableDeferred<FacebookUserToken>()
        val first = async {
            repository.refreshFacebookChannels("owner", expiresBefore = 2L) {
                sdkStarted.complete(Unit)
                sdkResult.await()
            }
        }
        sdkStarted.await()
        val second = async {
            repository.refreshFacebookChannels("owner", expiresBefore = 2L) {
                error("Không gọi SDK lần hai trong thời gian chờ.")
            }
        }
        runCurrent()
        assertTrue(!second.isCompleted)
        sdkResult.complete(FacebookUserToken("new", Long.MAX_VALUE, "public_profile", "owner"))
        assertEquals(listOf("owner"), first.await().outcome.updated.map { it.accountId })
        assertEquals(FacebookRefreshSkip.COOLDOWN, second.await().outcome.skipped)
    }

    @Test
    fun sameUserReconnectDuringSdkWaitDiscardsOldResultAndAllowsImmediateRefresh() = runTest {
        val type = ChannelType.FACEBOOK
        fakeAccountStore.saved = listOf(StoredChannelAccount(type, "owner", "Profile"))
        fakeTokenStore.saveToken(type, "owner", OAuthToken("old", 1L, isFacebookProfile = true))
        var session = 1L
        val started = CompletableDeferred<Unit>()
        val sdkResult = CompletableDeferred<FacebookUserToken>()
        val first = async {
            repository.refreshFacebookChannels("owner", 2L, sessionVersion = { session }) {
                started.complete(Unit)
                sdkResult.await()
            }
        }
        started.await()

        session++
        sdkResult.complete(FacebookUserToken("stale", Long.MAX_VALUE, null, "owner"))

        assertEquals(FacebookRefreshSkip.SDK_SESSION_CHANGED, first.await().outcome.skipped)
        assertEquals("old", fakeTokenStore.storage["FACEBOOK_owner"]?.accessToken)
        val fresh = repository.refreshFacebookChannels("owner", 2L, sessionVersion = { session }) {
            FacebookUserToken("fresh", Long.MAX_VALUE, null, "owner")
        }
        assertEquals(listOf("owner"), fresh.outcome.updated.map { it.accountId })
        assertEquals("fresh", fakeTokenStore.storage["FACEBOOK_owner"]?.accessToken)
    }

    @Test
    fun sdkSessionChangeWhileStagingTokenKeepsPreviouslyCommittedToken() = runTest {
        val type = ChannelType.FACEBOOK
        val metadata = StoredChannelAccount(type, "owner", "Profile")
        fakeAccountStore.saved = listOf(metadata)
        fakeTokenStore.saveToken(type, "owner", OAuthToken("old", 1L, isFacebookProfile = true))
        var session = 1L
        fakeTokenStore.afterStage = { session++ }

        val check = repository.refreshFacebookChannels("owner", 2L, sessionVersion = { session }) {
            FacebookUserToken("stale", Long.MAX_VALUE, null, "owner")
        }

        assertEquals(FacebookRefreshSkip.SDK_SESSION_CHANGED, check.outcome.skipped)
        assertEquals(listOf(metadata), fakeAccountStore.saved)
        assertEquals("old", fakeTokenStore.storage["FACEBOOK_owner"]?.accessToken)
        assertTrue(fakeTokenStore.staged.isEmpty())
    }

    @Test
    fun facebookRefreshReportsStorageFailureAndKeepsCommittedToken() = runTest {
        val type = ChannelType.FACEBOOK
        val user = FacebookUserToken("user", 1L, "public_profile", "owner")
        fakeAccountStore.saved = listOf(StoredChannelAccount(type, "owner", "Profile"))
        val old = OAuthToken("old-user", 1L, isFacebookProfile = true)
        fakeTokenStore.saveToken(type, "owner", old)
        fakeAccountStore.saveError = true

        val outcome = repository.refreshFacebookChannels("owner", expiresBefore = 2L) {
            user.copy(expiresAtMillis = Long.MAX_VALUE)
        }.outcome

        assertEquals(setOf(FacebookRefreshIssue.STORAGE), outcome.issues)
        assertTrue(outcome.updated.isEmpty())
        assertTrue(outcome.reconnectAccountIds.isEmpty())
        assertEquals(old, fakeTokenStore.storage["FACEBOOK_owner"])
    }

    @Test
    fun facebookRefreshOnlyUpdatesExistingPagesForTheSameOwner() = runTest {
        val type = ChannelType.FACEBOOK
        val previous = FacebookUserToken("original-user", 1L, "pages_show_list", "owner")
        repository.connectFacebookSelectedPage(previous, FacebookPage("page", "Page", "old-page")).getOrThrow()
        repository.connectFacebookSelectedPage(previous, FacebookPage("missing", "Missing", "old-missing")).getOrThrow()
        repository.connectFacebookSelectedPage(
            previous.copy(expiresAtMillis = 500L), FacebookPage("healthy", "Healthy", "old-healthy"),
        ).getOrThrow()
        repository.connectFacebookSelectedPage(
            previous.copy(userId = "another-owner"), FacebookPage("other", "Other", "old-other"),
        ).getOrThrow()
        val refreshed = previous.copy(accessToken = "renewed-user", expiresAtMillis = Long.MAX_VALUE)
        var calls = 0
        repository.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            assertEquals("Bearer renewed-user", chain.request().header("Authorization"))
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(
                    """
                    {"data":[{"id":"page","name":"Page","access_token":"new-page"},
                    {"id":"other","name":"Other","access_token":"wrong-page"},
                    {"id":"healthy","name":"Healthy","access_token":"new-healthy"}]}
                    """.trimIndent().toResponseBody(),
                )
                .build()
        }.build()

        val updated = repository.refreshFacebookChannels("owner", expiresBefore = 10L) { refreshed }.outcome
        assertEquals(listOf("page"), updated.updated.map { it.accountId })
        assertEquals(1, calls)
        assertEquals("new-page", fakeTokenStore.storage["FACEBOOK_page"]?.accessToken)
        assertNull(repository.getTokenExpiresAt(type, "page"))
        assertEquals(Long.MAX_VALUE, fakeTokenStore.storage["FACEBOOK_page"]?.facebookUserSessionExpiresAt)
        assertEquals("old-missing", fakeTokenStore.storage["FACEBOOK_missing"]?.accessToken)
        assertEquals(mapOf("missing" to FacebookRefreshIssue.PAGE_NOT_RETURNED), updated.issuesByAccountId)
        assertEquals(setOf("page"), updated.confirmedPageIds)
        assertTrue(updated.reconnectAccountIds.isEmpty())
        assertEquals("old-other", fakeTokenStore.storage["FACEBOOK_other"]?.accessToken)
        assertEquals("old-healthy", fakeTokenStore.storage["FACEBOOK_healthy"]?.accessToken)
    }

    @Test
    fun facebookRefreshKeepsOtherPageUpdatesWhenOnePageCannotBeSaved() = runTest {
        val user = FacebookUserToken("old-user", 1L, "pages_show_list", "owner")
        for (id in listOf("a", "b", "c")) {
            repository.connectFacebookSelectedPage(user, FacebookPage(id, id, "old-$id")).getOrThrow()
        }
        fakeAccountStore.failUpsertFor = "b"
        repository.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val pages = listOf("a", "b", "c").joinToString(",") { id ->
                """{"id":"$id","name":"$id","access_token":"new-$id"}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[$pages]}""".toResponseBody())
                .build()
        }.build()

        val outcome = repository.refreshFacebookChannels("owner", expiresBefore = 10L) {
            user.copy(accessToken = "new-user", expiresAtMillis = Long.MAX_VALUE)
        }.outcome

        assertEquals(setOf("a", "c"), outcome.updated.map { it.accountId }.toSet())
        assertEquals(mapOf("b" to FacebookRefreshIssue.STORAGE), outcome.issuesByAccountId)
        assertEquals("new-a", fakeTokenStore.storage["FACEBOOK_a"]?.accessToken)
        assertEquals("old-b", fakeTokenStore.storage["FACEBOOK_b"]?.accessToken)
        assertEquals("new-c", fakeTokenStore.storage["FACEBOOK_c"]?.accessToken)
        assertTrue(fakeTokenStore.staged.isEmpty())
    }

    @Test
    fun cancelledFacebookRefreshDoesNotWriteOrRestoreDisconnectedAccount() = runTest {
        val type = ChannelType.FACEBOOK
        val user = FacebookUserToken("old-user", 1L, "pages_show_list", "owner")
        repository.connectFacebookSelectedPage(user, FacebookPage("keep", "Keep", "old-keep")).getOrThrow()
        repository.connectFacebookSelectedPage(user, FacebookPage("gone", "Gone", "old-gone")).getOrThrow()
        var pageRequests = 0
        repository.httpClient = OkHttpClient.Builder().addInterceptor {
            pageRequests++
            error("Không tải Page sau khi refresh đã hủy.")
        }.build()
        val started = CompletableDeferred<Unit>()
        val callback = CompletableDeferred<Unit>()
        val refresh = async {
            repository.refreshFacebookChannels("owner", expiresBefore = 10L) {
                started.complete(Unit)
                withContext(NonCancellable) { callback.await() }
                user.copy(accessToken = "late-user", expiresAtMillis = Long.MAX_VALUE)
            }
        }
        started.await()
        refresh.cancel()
        repository.disconnectChannel(type, "gone").getOrThrow()
        callback.complete(Unit)
        refresh.cancelAndJoin()

        assertTrue(refresh.isCancelled)
        assertEquals(0, pageRequests)
        assertEquals("old-keep", fakeTokenStore.storage["FACEBOOK_keep"]?.accessToken)
        assertNull(fakeTokenStore.storage["FACEBOOK_gone"])
        assertEquals(listOf("keep"), repository.getConnectedChannels().first().map { it.accountId })
        assertTrue(fakeTokenStore.staged.isEmpty())
    }

    @Test
    fun facebookRefreshPreservesChangesMadeWhileWaitingForSdk() = runTest {
        val type = ChannelType.FACEBOOK
        val user = FacebookUserToken("old-user", 1L, "pages_show_list", "owner")
        val reconnected = FacebookPage("reconnected", "Reconnected", "original-page")
        val disconnected = FacebookPage("disconnected", "Disconnected", "original-other-page")
        repository.connectFacebookSelectedPage(user, reconnected).getOrThrow()
        repository.connectFacebookSelectedPage(user, disconnected).getOrThrow()
        repository.httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val response = """
                {"data":[{"id":"reconnected","name":"Reconnected","access_token":"stale-page"},
                {"id":"disconnected","name":"Disconnected","access_token":"stale-other-page"}]}
            """.trimIndent()
            Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toResponseBody())
                .build()
        }.build()

        val threshold = System.currentTimeMillis() + 120_000
        val reconnectExpiry = threshold - 60_000
        val updated = repository.refreshFacebookChannels("owner", threshold) {
            repository.connectFacebookSelectedPage(
                user.copy(expiresAtMillis = reconnectExpiry), reconnected.copy(accessToken = "manual-page"),
            ).getOrThrow()
            repository.disconnectChannel(type, disconnected.id).getOrThrow()
            user.copy(accessToken = "sdk-token", expiresAtMillis = Long.MAX_VALUE)
        }.outcome

        assertTrue(updated.updated.isEmpty())
        assertTrue(updated.issuesByAccountId.isEmpty())
        assertEquals("manual-page", fakeTokenStore.storage["FACEBOOK_reconnected"]?.accessToken)
        assertNull(repository.getTokenExpiresAt(type, reconnected.id))
        assertEquals(reconnectExpiry, fakeTokenStore.storage["FACEBOOK_${reconnected.id}"]?.facebookUserSessionExpiresAt)
        assertNull(fakeTokenStore.storage["FACEBOOK_disconnected"])
        assertEquals(listOf(reconnected.id), repository.getConnectedChannels().first().map { it.accountId })
    }

    @Test
    fun facebookPageStoresSessionExpirySeparatelyFromPageExpiry() = runTest {
        val user = FacebookUserToken("user_tok", expiresAtMillis = 1_234_567L, grantedScope = "pages_show_list,read_insights")

        val result = repository.connectFacebookSelectedPage(user, FacebookPage("p9", "Page Chin", "page_tok"))

        assertTrue(result.isSuccess)
        val token = fakeTokenStore.storage["FACEBOOK_p9"]!!
        assertEquals("page_tok", token.accessToken)
        assertNull(token.expiresAt)
        assertEquals(1_234_567L, token.facebookUserSessionExpiresAt)
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
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore)

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
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore)
        assertEquals("Tên cũ", restarted.getConnectedChannels().first().single().displayName)
        assertNull(restarted.getTokenExpiresAt(type, "p1"))
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
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore)
        assertEquals("Tên mới", restarted.getConnectedChannels().first().single().displayName)
        assertNull(restarted.getTokenExpiresAt(type, "p1"))
        assertEquals(20L, fakeTokenStore.stagedToken?.facebookUserSessionExpiresAt)
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

        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore)
        assertEquals("a", restarted.getConnectedChannels().first().single().accountId)
        assertNull(restarted.getTokenExpiresAt(type, "a"))
        assertEquals(20L, fakeTokenStore.stagedToken?.facebookUserSessionExpiresAt)
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
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore)
        assertNull(restarted.getTokenExpiresAt(type, "p1"))
        assertEquals(40L, fakeTokenStore.storage["FACEBOOK_p1"]?.facebookUserSessionExpiresAt)
        assertEquals("fresh", fakeTokenStore.storage["FACEBOOK_p1"]?.accessToken)
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
        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore)
        assertEquals(setOf("a", "b"), restarted.getConnectedChannels().first().map { it.accountId }.toSet())
        assertNull(restarted.getTokenExpiresAt(type, "a"))
        assertEquals("new-a", fakeTokenStore.storage["FACEBOOK_a"]?.accessToken)
    }

    @Test
    fun `ngat ket noi loi xoa metadata thi khoi dong lai khong phuc hoi token va thu lai duoc`() = runTest {
        val type = ChannelType.FACEBOOK
        fakeTokenStore.failFinishFor = "a"
        repository.connectFacebookSelectedPage(
            FacebookUserToken("user", 20L, null), FacebookPage("a", "A", "token"),
        ).getOrThrow()
        fakeAccountStore.saveError = true
        val result = repository.disconnectChannel(type, "a")
        assertEquals(ChannelError.CHANNELS_SAVE_FAILED, (result.exceptionOrNull() as ChannelException).error)
        assertTrue(fakeTokenStore.staged.isEmpty())
        assertTrue(fakeTokenStore.storage.isEmpty())

        val restarted = ChannelRepositoryImpl(fakeTokenStore, fakeAccountStore)
        assertTrue(restarted.getConnectedChannels().first().isEmpty())
        fakeAccountStore.saveError = false
        restarted.disconnectChannel(type, "a").getOrThrow()
        assertTrue(fakeAccountStore.saved.isEmpty())
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
    fun `parseBffToken tu choi han khong hop le`() {
        assertNull(ChannelRepositoryImpl.parseBffToken("""{"access_token":"a","expires_in":0}"""))
        assertNull(ChannelRepositoryImpl.parseBffToken("""{"access_token":"a","expires_in":-5}"""))
        assertNull(ChannelRepositoryImpl.parseBffToken("""{"access_token":"a","expires_in":{}}"""))
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
