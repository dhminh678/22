package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelCapability
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.network.LinkedInBffAuth
import com.lttbdd.socialagent.data.network.PkceUtil
import com.lttbdd.socialagent.data.repository.ChannelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeChannelRepository
    private lateinit var viewModel: ChannelViewModel
    private lateinit var callbackBus: LinkedInCallbackBus

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeChannelRepository()
        callbackBus = LinkedInCallbackBus()
        viewModel = ChannelViewModel(fakeRepository, callbackBus)
        viewModel.linkedInClientId = "client_that"
        viewModel.linkedInBffUrl = "https://bff.example.com"
        viewModel.linkedInLoginTimeoutMillis = 1_000L
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun user(expiresAt: Long = 9_000_000L, scope: String? = "public_profile,pages_show_list") =
        FacebookUserToken("user_token_gia", expiresAt, scope)

    @Test
    fun `getScopeForAccount tra ve quyen that cua token, khong viet cung`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))
        fakeRepository.scope = "pages_show_list"

        viewModel.onFacebookLoggedIn(user())
        testDispatcher.scheduler.advanceUntilIdle()

        val facebook = (viewModel.uiState.value as ChannelUiState.Success).channels.first()
        assertEquals("pages_show_list", viewModel.getScopeForAccount(facebook))
    }

    private fun connectWithFacebookToken() {
        viewModel.onFacebookLoggedIn(user())
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `nhieu Page thi hien hop thoai chon va chua ket noi cho den khi chon`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"), FacebookPage("p2", "Page Hai", "t2"))

        connectWithFacebookToken()

        assertEquals(2, viewModel.pagePicker.value?.pages?.size)
        assertTrue((viewModel.uiState.value as ChannelUiState.Success).channels.isEmpty())

        viewModel.selectFacebookPage(fakeRepository.pages[1])
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.pagePicker.value)
        val channels = (viewModel.uiState.value as ChannelUiState.Success).channels
        assertEquals(listOf("Page Hai"), channels.map { it.displayName })
        assertEquals("p2", channels.first().accountId)
    }

    @Test
    fun `mot Page thi tu dong ket noi Page do`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Duy Nhat", "t1"))

        connectWithFacebookToken()

        assertNull(viewModel.pagePicker.value)
        val channels = (viewModel.uiState.value as ChannelUiState.Success).channels
        assertEquals(listOf("Page Duy Nhat"), channels.map { it.displayName })
    }

    @Test
    fun `khong co Page thi ket noi bang ho so va phat thong bao`() = runTest {
        val notices = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { notices.add(it) } }

        connectWithFacebookToken()

        assertEquals(listOf(ChannelNotice.NO_FACEBOOK_PAGES), notices)
        val channels = (viewModel.uiState.value as ChannelUiState.Success).channels
        assertEquals(listOf("Facebook Page Test"), channels.map { it.displayName })
    }

    @Test
    fun `huy hop thoai chon Page thi khong ket noi va xoa token tam`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "A", "t1"), FacebookPage("p2", "B", "t2"))
        connectWithFacebookToken()
        assertNotNull(viewModel.pagePicker.value)

        viewModel.cancelFacebookPagePicker()
        viewModel.selectFacebookPage(fakeRepository.pages[0])
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.pagePicker.value)
        assertTrue((viewModel.uiState.value as ChannelUiState.Success).channels.isEmpty())
    }

    @Test
    fun `loi tai danh sach Page thi bao loi va khong ket noi bang ho so`() = runTest {
        val notices = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { notices.add(it) } }
        fakeRepository.pagesResult = Result.failure(java.io.IOException("mat mang"))

        connectWithFacebookToken()

        assertEquals(listOf(ChannelNotice.FACEBOOK_PAGES_LOAD_FAILED), notices)
        assertTrue((viewModel.uiState.value as ChannelUiState.Success).channels.isEmpty())
    }

    @Test
    fun `token qua han thi kenh hien TOKEN_EXPIRED`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))
        fakeRepository.expiresAt = 1_000L
        viewModel.clock = { 2_000L }

        connectWithFacebookToken()

        val channel = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        assertEquals(ConnectionStatus.TOKEN_EXPIRED, channel.status)
    }

    @Test
    fun `token con han thi kenh giu CONNECTED`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))
        fakeRepository.expiresAt = 5_000L
        viewModel.clock = { 2_000L }

        connectWithFacebookToken()

        val channel = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        assertEquals(ConnectionStatus.CONNECTED, channel.status)
    }

    @Test
    fun `khong doc duoc han token thi khong bao het han`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))
        fakeRepository.expiresAt = null
        viewModel.clock = { Long.MAX_VALUE }

        connectWithFacebookToken()

        val channel = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        assertEquals(ConnectionStatus.CONNECTED, channel.status)
    }

    @Test
    fun `ngay sau khi ket noi Page, getScopeForAccount da co quyen`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))

        connectWithFacebookToken()

        val channel = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        assertEquals("public_profile", viewModel.getScopeForAccount(channel))
    }

    @Test
    fun `ket noi thanh cong tra ve ten kenh de UI ghep chuoi da dich`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))

        connectWithFacebookToken()

        assertEquals("Page Mot", (viewModel.uiState.value as ChannelUiState.Success).connectedName)
    }

    // ---- Facebook: hạn và quyền thật từ Meta SDK ----

    @Test
    fun `Page duoc ket noi bang han va quyen that cua phien SDK`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))

        viewModel.onFacebookLoggedIn(user(expiresAt = 123_456L, scope = "pages_show_list,pages_manage_posts"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(123_456L, fakeRepository.lastUser?.expiresAtMillis)
        assertEquals("pages_show_list,pages_manage_posts", fakeRepository.lastUser?.grantedScope)
    }

    @Test
    fun `huy dang nhap Facebook phat thong bao huy`() = runTest {
        val notices = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { notices.add(it) } }

        viewModel.onFacebookLoginCancelled()

        assertEquals(listOf(ChannelNotice.FACEBOOK_LOGIN_CANCELLED), notices)
    }

    // ---- LinkedIn qua BFF ----

    private val redirect = LinkedInBffAuth.APP_REDIRECT_URI

    /** Chạy startLinkedInLogin (không chạy tới hạn timeout); trả về (các URL mở trình duyệt, thông báo). */
    private fun TestScope.startLinkedIn(): Pair<MutableList<String>, MutableList<ChannelNotice>> {
        val urls = mutableListOf<String>()
        val notices = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.browserRequests.collect { urls.add(it) } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { notices.add(it) } }
        viewModel.startLinkedInLogin()
        testDispatcher.scheduler.runCurrent()
        return urls to notices
    }

    private fun stateOf(url: String) = Regex("state=([^&]+)").find(url)!!.groupValues[1]

    private fun callback(query: String) {
        viewModel.onLinkedInCallback("$redirect?$query")
        testDispatcher.scheduler.runCurrent()
    }

    @Test
    fun `chua cau hinh client id hoac BFF thi la che do demo`() {
        assertTrue(!viewModel.isLinkedInDemoMode())
        viewModel.linkedInBffUrl = "SOCIAL_AGENT_LINKEDIN_BFF_URL"
        assertTrue(viewModel.isLinkedInDemoMode())
        viewModel.linkedInBffUrl = "https://bff.example.com"
        viewModel.linkedInClientId = ""
        assertTrue(viewModel.isLinkedInDemoMode())
    }

    @Test
    fun `LinkedIn dang ky phien voi BFF roi moi mo trinh duyet voi redirect ve BFF va khong co secret`() = runTest {
        val (urls, _) = startLinkedIn()

        val url = urls.single()
        assertTrue(url.startsWith("https://www.linkedin.com/oauth/v2/authorization?"))
        assertTrue(url.contains("client_id=client_that"))
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fbff.example.com%2Fauth%2Flinkedin%2Fcallback"))
        assertTrue(!url.contains("secret"))
        val (registeredState, challenge) = fakeRepository.sessions.single()
        assertEquals(stateOf(url), registeredState)
        assertTrue("challenge S256 dai 43 ky tu", challenge.length == 43)
    }

    @Test
    fun `deep link hop le thi doi exchange_code kem state va verifier khop challenge da dang ky`() = runTest {
        val (urls, _) = startLinkedIn()
        val state = stateOf(urls.single())

        callback("exchange_code=ma_doi&state=$state")
        testDispatcher.scheduler.runCurrent()

        val sent = fakeRepository.lastBff!!
        assertEquals("ma_doi", sent.first)
        assertEquals(state, sent.second)
        assertTrue("verifier dai 43..128 ky tu", sent.third.length in 43..128)
        assertEquals(fakeRepository.sessions.single().second, PkceUtil.generateCodeChallengeS256(sent.third))
        assertEquals("LinkedIn Org Test", (viewModel.uiState.value as ChannelUiState.Success).connectedName)
    }

    @Test
    fun `deep link di qua bus cung duoc xu ly`() = runTest {
        val (urls, _) = startLinkedIn()
        val state = stateOf(urls.single())

        callbackBus.publish("$redirect?exchange_code=ma_doi&state=$state")
        testDispatcher.scheduler.runCurrent()

        assertEquals("ma_doi", fakeRepository.lastBff?.first)
    }

    @Test
    fun `state sai thi bao loi, khong doi ma va van giu phien dang cho`() = runTest {
        val (urls, _) = startLinkedIn()
        val state = stateOf(urls.single())

        callback("exchange_code=ma_gia&state=state_gia")

        assertNull(fakeRepository.lastBff)
        val error = viewModel.uiState.value as ChannelUiState.Error
        assertEquals(com.lttbdd.socialagent.R.string.channel_oauth_state_mismatch, error.messageRes)

        callback("exchange_code=ma_that&state=$state")
        testDispatcher.scheduler.runCurrent()
        assertEquals("ma_that", fakeRepository.lastBff?.first)
    }

    @Test
    fun `deep link khi khong co phien dang cho bi bo qua`() = runTest {
        val notices = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { notices.add(it) } }

        callback("exchange_code=ma_gia&state=bat_ky")

        assertNull(fakeRepository.lastBff)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `ma chi doi duoc mot lan, deep link lap lai bi bo qua`() = runTest {
        val (urls, _) = startLinkedIn()
        val state = stateOf(urls.single())

        callback("exchange_code=ma_doi&state=$state")
        fakeRepository.lastBff = null
        callback("exchange_code=ma_doi&state=$state")

        assertNull(fakeRepository.lastBff)
    }

    @Test
    fun `nguoi dung huy thi bao huy va khong ket noi`() = runTest {
        val (_, notices) = startLinkedIn()

        callback("error=access_denied")

        assertEquals(listOf(ChannelNotice.LINKEDIN_LOGIN_CANCELLED), notices)
        assertNull(fakeRepository.lastBff)
    }

    @Test
    fun `BFF bao loi thi bao that bai va khong ket noi`() = runTest {
        val (urls, notices) = startLinkedIn()

        callback("error=exchange_failed&state=${stateOf(urls.single())}")

        assertEquals(listOf(ChannelNotice.LINKEDIN_LOGIN_FAILED), notices)
        assertNull(fakeRepository.lastBff)
    }

    @Test
    fun `het thoi gian cho thi bao timeout va deep link tre bi bo qua`() = runTest {
        val (urls, notices) = startLinkedIn()
        val state = stateOf(urls.single())

        testDispatcher.scheduler.advanceTimeBy(viewModel.linkedInLoginTimeoutMillis + 1)
        testDispatcher.scheduler.runCurrent()
        callback("exchange_code=ma_tre&state=$state")

        assertEquals(listOf(ChannelNotice.LINKEDIN_LOGIN_TIMEOUT), notices)
        assertNull(fakeRepository.lastBff)
    }

    @Test
    fun `khong dang ky duoc phien voi BFF thi bao that bai va khong mo trinh duyet`() = runTest {
        fakeRepository.sessionResult = Result.failure(java.io.IOException("mat mang"))

        val (urls, notices) = startLinkedIn()

        assertTrue(urls.isEmpty())
        assertEquals(listOf(ChannelNotice.LINKEDIN_LOGIN_FAILED), notices)
    }

    @Test
    fun `dia chi BFF khong phai HTTPS thi khong gui gi va bao that bai`() = runTest {
        viewModel.linkedInBffUrl = "http://bff.example.com"

        val (urls, notices) = startLinkedIn()

        assertTrue(urls.isEmpty())
        assertTrue(fakeRepository.sessions.isEmpty())
        assertEquals(listOf(ChannelNotice.LINKEDIN_LOGIN_FAILED), notices)
    }

    @Test
    fun `doi token that bai thi hien loi va khong them kenh`() = runTest {
        fakeRepository.bffResult = Result.failure(IllegalStateException("BFF tu choi"))
        val (urls, _) = startLinkedIn()

        callback("exchange_code=ma_doi&state=${stateOf(urls.single())}")
        testDispatcher.scheduler.runCurrent()

        assertEquals("BFF tu choi", (viewModel.uiState.value as ChannelUiState.Error).message)
    }

    private class FakeChannelRepository : ChannelRepository {
        private val channels = MutableStateFlow<List<ChannelAccount>>(emptyList())

        var pages: List<FacebookPage> = emptyList()

        /** Khác null thì [fetchFacebookPages] trả kết quả này thay cho [pages]. */
        var pagesResult: Result<List<FacebookPage>>? = null

        /** Thời điểm hết hạn token giả; null nghĩa là repository không đọc được. */
        var expiresAt: Long? = null

        override suspend fun getTokenExpiresAt(type: ChannelType, accountId: String): Long? = expiresAt

        override suspend fun fetchFacebookPages(userAccessToken: String): Result<List<FacebookPage>> =
            pagesResult ?: Result.success(pages)

        /** Phiên Facebook mà repository nhận lần cuối (để kiểm hạn/quyền thật được chuyển xuống). */
        var lastUser: FacebookUserToken? = null

        var scope: String? = "public_profile"

        /** (state, code_challenge) đã đăng ký với BFF; [sessionResult] điều khiển kết quả đăng ký. */
        val sessions = mutableListOf<Pair<String, String>>()
        var sessionResult: Result<Unit> = Result.success(Unit)
        var bffResult: Result<ChannelAccount>? = null
        var lastBff: Triple<String, String, String>? = null

        override suspend fun startLinkedInSession(state: String, codeChallenge: String): Result<Unit> {
            sessions.add(state to codeChallenge)
            return sessionResult
        }

        override suspend fun connectLinkedInViaBff(
            exchangeCode: String,
            state: String,
            codeVerifier: String,
        ): Result<ChannelAccount> {
            lastBff = Triple(exchangeCode, state, codeVerifier)
            bffResult?.let { return it }
            val account = ChannelAccount(
                type = ChannelType.LINKEDIN,
                accountId = "li_202",
                displayName = "LinkedIn Org Test",
                status = ConnectionStatus.CONNECTED,
                capabilities = setOf(ChannelCapability.PUBLISH, ChannelCapability.READ_COMMENTS),
            )
            channels.value = channels.value + account
            return Result.success(account)
        }

        override suspend fun connectFacebookProfile(user: FacebookUserToken): Result<ChannelAccount> {
            lastUser = user
            return connectFacebookPage("", "", "")
        }

        override suspend fun connectFacebookSelectedPage(
            user: FacebookUserToken,
            page: FacebookPage,
        ): Result<ChannelAccount> {
            lastUser = user
            val account = ChannelAccount(
                type = ChannelType.FACEBOOK,
                accountId = page.id,
                displayName = page.name,
                status = ConnectionStatus.CONNECTED,
                capabilities = setOf(ChannelCapability.PUBLISH),
            )
            channels.value = channels.value + account
            return Result.success(account)
        }

        override fun getConnectedChannels(): Flow<List<ChannelAccount>> = channels.asStateFlow()

        override suspend fun getGrantedScope(type: ChannelType, accountId: String): String? =
            if (type == ChannelType.FACEBOOK) scope else null

        override suspend fun connectFacebookPage(
            authCode: String,
            codeVerifier: String,
            redirectUri: String,
        ): Result<ChannelAccount> {
            val account = ChannelAccount(
                type = ChannelType.FACEBOOK,
                accountId = "fb_101",
                displayName = "Facebook Page Test",
                status = ConnectionStatus.CONNECTED,
                capabilities = setOf(ChannelCapability.PUBLISH, ChannelCapability.READ_COMMENTS),
            )
            channels.value = channels.value + account
            return Result.success(account)
        }

        override suspend fun connectLinkedIn(
            authCode: String,
            codeVerifier: String,
            redirectUri: String,
        ): Result<ChannelAccount> {
            val account = ChannelAccount(
                type = ChannelType.LINKEDIN,
                accountId = "li_202",
                displayName = "LinkedIn Org Test",
                status = ConnectionStatus.CONNECTED,
                capabilities = setOf(ChannelCapability.PUBLISH, ChannelCapability.READ_COMMENTS),
            )
            channels.value = channels.value + account
            return Result.success(account)
        }

        override suspend fun disconnectChannel(type: ChannelType, accountId: String): Result<Unit> {
            channels.value = channels.value.filterNot { it.type == type && it.accountId == accountId }
            return Result.success(Unit)
        }
    }
}
