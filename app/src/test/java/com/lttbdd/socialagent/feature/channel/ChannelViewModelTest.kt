package com.lttbdd.socialagent.feature.channel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.repeatOnLifecycle
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelCapability
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.ChannelError
import com.lttbdd.socialagent.data.model.ChannelException
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookPageAccessIssue
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.model.FacebookRefreshCheck
import com.lttbdd.socialagent.data.model.FacebookRefreshException
import com.lttbdd.socialagent.data.model.FacebookRefreshOutcome
import com.lttbdd.socialagent.data.model.FacebookRefreshIssue
import com.lttbdd.socialagent.data.model.FacebookRefreshSkip
import com.lttbdd.socialagent.data.network.LinkedInBffAuth
import com.lttbdd.socialagent.data.network.PkceUtil
import com.lttbdd.socialagent.data.repository.ChannelRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.lttbdd.socialagent.data.repository.FacebookRefreshCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private lateinit var savedStateHandle: SavedStateHandle

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeChannelRepository { testDispatcher.scheduler.currentTime }
        callbackBus = LinkedInCallbackBus()
        savedStateHandle = SavedStateHandle()
        viewModel = ChannelViewModel(fakeRepository, callbackBus, savedStateHandle)
        viewModel.linkedInClientId = "client_that"
        viewModel.linkedInBffUrl = "https://bff.example.com"
        viewModel.linkedInLoginTimeoutMillis = 1_000L
        viewModel.elapsedRealtime = { testDispatcher.scheduler.currentTime }
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

        completeFacebookLogin()
        testDispatcher.scheduler.advanceUntilIdle()

        val facebook = (viewModel.uiState.value as ChannelUiState.Success).channels.first()
        assertEquals("pages_show_list", viewModel.getScopeForAccount(facebook))
    }

    private fun connectWithFacebookToken() {
        completeFacebookLogin()
        testDispatcher.scheduler.advanceUntilIdle()
    }

    private fun completeFacebookLogin(token: FacebookUserToken = user()) {
        assertTrue(viewModel.beginFacebookSdkLogin())
        viewModel.dispatchFacebookSdkResult { viewModel.onFacebookLoggedIn(token) }
    }

    @Test
    fun `nhieu Page thi hien hop thoai chon va chua ket noi cho den khi chon`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"), FacebookPage("p2", "Page Hai", "t2"))

        connectWithFacebookToken()

        assertEquals(2, viewModel.pagePicker.value?.pages?.size)
        assertTrue((viewModel.uiState.value as ChannelUiState.Success).channels.isEmpty())

        viewModel.selectFacebookPage(checkNotNull(viewModel.pagePicker.value), 1)
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.pagePicker.value)
        val channels = (viewModel.uiState.value as ChannelUiState.Success).channels
        assertEquals(listOf("Page Hai"), channels.map { it.displayName })
        assertEquals("p2", channels.first().accountId)
    }

    @Test
    fun staleFacebookPagePickerCannotConnectAfterDisconnectAndLogin() = runTest {
        val connectedPage = FacebookPage("connected", "Connected Page", "first-token")
        fakeRepository.pages = listOf(connectedPage)
        connectWithFacebookToken()
        val facebook = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        fakeRepository.pages = listOf(connectedPage, FacebookPage("old", "Old Page", "old-token"))
        connectWithFacebookToken()
        val oldPicker = checkNotNull(viewModel.pagePicker.value)

        viewModel.disconnectChannel(facebook)
        testScheduler.runCurrent()
        assertNull(viewModel.pagePicker.value)

        val newPage = FacebookPage("new", "New Page", "new-token")
        fakeRepository.pages = listOf(newPage, FacebookPage("other", "Other Page", "other-token"))
        val newUser = user(expiresAt = 10_000_000L, scope = "pages_read_engagement")
        completeFacebookLogin(newUser)
        testScheduler.runCurrent()
        val newPicker = checkNotNull(viewModel.pagePicker.value)

        viewModel.selectFacebookPage(oldPicker, 1)
        viewModel.cancelFacebookPagePicker(oldPicker)
        testScheduler.runCurrent()
        assertTrue(fakeRepository.getConnectedChannels().first().isEmpty())
        assertEquals(newPicker, viewModel.pagePicker.value)

        viewModel.selectFacebookPage(newPicker, 0)
        testScheduler.runCurrent()
        assertEquals(newUser, fakeRepository.lastUser)
        assertEquals(newPage.id, fakeRepository.getConnectedChannels().first().single().accountId)
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
    fun facebookReconnectSelectsOnlyRequestedPage() = runTest {
        fakeRepository.pages = listOf(FacebookPage("a", "Page A", "token-a"), FacebookPage("b", "Page B", "token-b"))
        viewModel.onFacebookLoggedIn(user(), expectedAccountId = "b")
        testScheduler.runCurrent()

        assertNull(viewModel.pagePicker.value)
        assertEquals(listOf("b"), (viewModel.uiState.value as ChannelUiState.Success).channels.map { it.accountId })
    }

    @Test
    fun facebookReconnectDoesNotConnectAnotherPage() = runTest {
        fakeRepository.pages = listOf(FacebookPage("a", "Page A", "token-a"))
        viewModel.onFacebookLoggedIn(user(), expectedAccountId = "missing")
        testScheduler.runCurrent()

        assertEquals(com.lttbdd.socialagent.R.string.channel_error_reconnect_account_mismatch, viewModel.errors.first())
        assertTrue((viewModel.uiState.value as ChannelUiState.Success).channels.isEmpty())
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
    fun `luu ho so that bai thi chi bao loi`() = runTest {
        fakeRepository.profileFailure = ChannelException(ChannelError.CHANNELS_SAVE_FAILED)
        val notices = mutableListOf<ChannelNotice>()
        val connected = mutableListOf<String>()
        val errors = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { notices.add(it) } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.connectedEvents.collect { connected.add(it) } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.errors.collect { errors.add(it) } }

        connectWithFacebookToken()

        assertTrue(notices.isEmpty())
        assertTrue(connected.isEmpty())
        assertEquals(listOf(com.lttbdd.socialagent.R.string.channel_error_save_failed), errors)
    }

    @Test
    fun `huy hop thoai chon Page thi khong ket noi va xoa token tam`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "A", "t1"), FacebookPage("p2", "B", "t2"))
        connectWithFacebookToken()
        val picker = checkNotNull(viewModel.pagePicker.value)

        viewModel.cancelFacebookPagePicker(picker)
        viewModel.selectFacebookPage(picker, 0)
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
    fun `token het han trong luc man hinh dang mo thi can ket noi lai`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        viewModel.clock = { testScheduler.currentTime }
        connectWithFacebookToken()
        backgroundScope.launch { viewModel.monitorTokenExpiry() }
        testScheduler.runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, (viewModel.uiState.value as ChannelUiState.Success).channels.single().status)

        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()
        assertEquals(ConnectionStatus.TOKEN_EXPIRED, (viewModel.uiState.value as ChannelUiState.Success).channels.single().status)
    }

    @Test
    fun resumingChannelScreenRechecksTokenExpiryBeforeTimerRuns() = runTest {
        var now = 0L
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        viewModel.clock = { now }
        connectWithFacebookToken()
        backgroundScope.launch { viewModel.monitorTokenExpiry() }
        testScheduler.runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, (viewModel.uiState.value as ChannelUiState.Success).channels.single().status)

        now = 1_000L
        viewModel.onChannelScreenResumed()

        assertEquals(ConnectionStatus.TOKEN_EXPIRED, (viewModel.uiState.value as ChannelUiState.Success).channels.single().status)
    }

    @Test
    fun correctingDeviceTimeRestoresChannelAndReschedulesExpiry() = runTest {
        var clockOffset = 2_000L
        viewModel.clock = { testScheduler.currentTime + clockOffset }
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        connectWithFacebookToken()
        backgroundScope.launch { viewModel.monitorTokenExpiry() }
        testScheduler.runCurrent()

        fun channelStatus() = (viewModel.uiState.value as ChannelUiState.Success).channels.single().status
        assertEquals(ConnectionStatus.TOKEN_EXPIRED, channelStatus())

        clockOffset = 500L
        viewModel.onChannelScreenResumed()
        testScheduler.runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, channelStatus())

        testScheduler.advanceTimeBy(500L)
        testScheduler.runCurrent()
        assertEquals(ConnectionStatus.TOKEN_EXPIRED, channelStatus())

        clockOffset = 0L
        viewModel.onChannelScreenResumed()
        assertEquals(ConnectionStatus.CONNECTED, channelStatus())
    }

    @Test
    fun resumingAfterClockMovesForwardReschedulesStillValidToken() = runTest {
        var clockOffset = 0L
        viewModel.clock = { testScheduler.currentTime + clockOffset }
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        connectWithFacebookToken()
        backgroundScope.launch { viewModel.monitorTokenExpiry() }
        testScheduler.runCurrent()

        clockOffset = 750L
        viewModel.onChannelScreenResumed()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(249L)
        testScheduler.runCurrent()
        assertEquals(ConnectionStatus.CONNECTED, (viewModel.uiState.value as ChannelUiState.Success).channels.single().status)

        testScheduler.advanceTimeBy(1L)
        testScheduler.runCurrent()
        assertEquals(ConnectionStatus.TOKEN_EXPIRED, (viewModel.uiState.value as ChannelUiState.Success).channels.single().status)
    }

    @Test
    fun deviceClockChangeUpdatesTokenStatusWhileScreenRemainsOpen() = runTest {
        var now = 0L
        viewModel.clock = { now }
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        connectWithFacebookToken()
        backgroundScope.launch { viewModel.monitorTokenExpiry() }
        testScheduler.runCurrent()

        fun channelStatus() = (viewModel.uiState.value as ChannelUiState.Success).channels.single().status
        assertEquals(ConnectionStatus.CONNECTED, channelStatus())

        now = 1_000L
        viewModel.recheckTokenExpiry()
        assertEquals(ConnectionStatus.TOKEN_EXPIRED, channelStatus())

        now = 500L
        viewModel.recheckTokenExpiry()
        assertEquals(ConnectionStatus.CONNECTED, channelStatus())
    }

    @Test
    fun facebookReconnectReschedulesExpiryForUnchangedChannel() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 5_000L
        viewModel.clock = { testScheduler.currentTime }
        connectWithFacebookToken()
        backgroundScope.launch { viewModel.monitorTokenExpiry() }
        testScheduler.runCurrent()
        val original = viewModel.uiState.value as ChannelUiState.Success

        testScheduler.advanceTimeBy(100L)
        fakeRepository.expiresAt = 1_000L
        fakeRepository.scope = "pages_show_list"
        viewModel.onFacebookLoggedIn(user(), expectedAccountId = "p1")
        testScheduler.runCurrent()
        val reconnected = viewModel.uiState.value as ChannelUiState.Success
        assertEquals(original.channels, reconnected.channels)
        assertEquals("pages_show_list", viewModel.getScopeForAccount(reconnected.channels.single()))

        testScheduler.advanceTimeBy(900L)
        testScheduler.runCurrent()
        val expired = viewModel.uiState.value as ChannelUiState.Success
        assertEquals(ConnectionStatus.TOKEN_EXPIRED, expired.channels.single().status)
        assertTrue(reconnected.tokenRevision > original.tokenRevision)
    }

    @Test
    fun pageOffersReconnectAtStoredSessionDeadlineEvenWithoutSdkSession() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.facebookPageSessionExpiresAt = 1_000L
        viewModel.clock = { testScheduler.currentTime }
        connectWithFacebookToken()
        backgroundScope.launch { viewModel.monitorTokenExpiry() }
        testScheduler.runCurrent()

        val initial = viewModel.uiState.value as ChannelUiState.Success
        assertTrue(initial.facebookReconnectIds.isEmpty())
        val check = viewModel.refreshFacebookIfNeeded(null) { error("Không có phiên SDK.") }
        assertEquals(FacebookRefreshSkip.NO_SDK_SESSION, check.outcome.skipped)

        testScheduler.advanceTimeBy(1_000L)
        testScheduler.runCurrent()
        val expired = viewModel.uiState.value as ChannelUiState.Success
        assertEquals(setOf("p1"), expired.facebookReconnectIds)
        assertEquals(ConnectionStatus.CONNECTED, expired.channels.single().status)
        assertTrue(viewModel.needsFacebookReconnect(expired.channels.single()))

        fakeRepository.facebookPageSessionExpiresAt = 5_000L
        viewModel.onFacebookLoggedIn(user(), expectedAccountId = "p1")
        testScheduler.runCurrent()
        val reconnected = viewModel.uiState.value as ChannelUiState.Success
        assertTrue(reconnected.facebookReconnectIds.isEmpty())
    }

    @Test
    fun pageAccessUpdatesOnlyTheAffectedRowsWhileUserSessionRemainsValid() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "token-one"))
        fakeRepository.facebookPageSessionExpiresAt = Long.MAX_VALUE
        connectWithFacebookToken()
        fakeRepository.pages = listOf(FacebookPage("p2", "Second page", "token-two"))
        connectWithFacebookToken()

        fakeRepository.pageAccessIssues.value = mapOf(
            "p1" to FacebookPageAccessIssue.RECONNECT_REQUIRED,
            "p2" to FacebookPageAccessIssue.PERMISSION_REQUIRED,
        )
        testScheduler.runCurrent()

        val state = viewModel.uiState.value as ChannelUiState.Success
        assertEquals(setOf("p1"), state.facebookReconnectIds)
        assertEquals(setOf("p2"), state.facebookPagePermissionIds)
        assertEquals(setOf(ConnectionStatus.CONNECTED), state.channels.map { it.status }.toSet())
    }

    private suspend fun checkFacebookRefresh(userId: String, refresh: suspend () -> FacebookUserToken?): Long =
        viewModel.refreshFacebookIfNeeded(userId) {
            refresh() ?: throw FacebookRefreshException(FacebookRefreshIssue.SDK_UNAVAILABLE)
        }.nextCheckMillis

    @Test
    fun `tu lam moi phien Facebook gan het han va gioi han tan suat thu lai`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        viewModel.clock = { 2_000L }
        connectWithFacebookToken()

        var refreshCalls = 0
        suspend fun refresh(): FacebookUserToken {
            refreshCalls++
            return user(60_000L).copy(userId = "owner")
        }
        launch { checkFacebookRefresh("owner", refresh = ::refresh) }
        launch { checkFacebookRefresh("owner", refresh = ::refresh) }
        testScheduler.runCurrent()
        checkFacebookRefresh("owner", refresh = ::refresh)
        testScheduler.runCurrent()

        assertEquals(1, refreshCalls)
        assertEquals(60_000L, fakeRepository.expiresAt)
        assertEquals(ConnectionStatus.CONNECTED, (viewModel.uiState.value as ChannelUiState.Success).channels.single().status)
    }

    @Test
    fun facebookRefreshReportsSkippedAndInvalidSessionSeparately() = runTest {
        val noSession = viewModel.refreshFacebookIfNeeded(null) { error("Không có phiên SDK.") }
        assertEquals(FacebookRefreshSkip.NO_SDK_SESSION, noSession.outcome.skipped)
        val noChannels = viewModel.refreshFacebookIfNeeded("owner") { error("Không có kênh cần refresh.") }
        assertEquals(FacebookRefreshSkip.NO_ELIGIBLE_CHANNELS, noChannels.outcome.skipped)

        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        connectWithFacebookToken()
        fakeRepository.facebookRefreshOutcome = FacebookRefreshOutcome(
            issues = setOf(FacebookRefreshIssue.SESSION_INVALID),
            reconnectAccountIds = setOf("p1"),
        )
        val failure = viewModel.refreshFacebookIfNeeded("owner") { throw FacebookRefreshException(FacebookRefreshIssue.SESSION_INVALID) }

        assertEquals(setOf(FacebookRefreshIssue.SESSION_INVALID), failure.outcome.issues)
        assertEquals(failure, viewModel.facebookRefreshState.value)
        val state = viewModel.uiState.value as ChannelUiState.Success
        assertEquals(setOf("p1"), state.facebookReconnectIds)
        assertTrue(viewModel.needsFacebookReconnect(state.channels.single()))
        val cooldown = viewModel.refreshFacebookIfNeeded("owner") { error("Đang chờ thử lại.") }
        assertEquals(FacebookRefreshSkip.WAITING_FOR_SESSION, cooldown.outcome.skipped)

        connectWithFacebookToken()
        assertTrue((viewModel.uiState.value as ChannelUiState.Success).facebookReconnectIds.isEmpty())
    }

    @Test
    fun facebookRefreshKeepsTemporaryIssueDuringCooldownAndClearsItOnSuccess() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        connectWithFacebookToken()

        val failed = viewModel.refreshFacebookIfNeeded("owner") {
            throw FacebookRefreshException(FacebookRefreshIssue.NETWORK)
        }
        assertEquals(setOf(FacebookRefreshIssue.NETWORK), viewModel.facebookRefreshIssues.value)
        val cooldown = viewModel.refreshFacebookIfNeeded("owner") { error("Đang chờ thử lại.") }
        assertEquals(FacebookRefreshSkip.COOLDOWN, cooldown.outcome.skipped)
        assertEquals(setOf(FacebookRefreshIssue.NETWORK), viewModel.facebookRefreshIssues.value)
        assertTrue((viewModel.uiState.value as ChannelUiState.Success).facebookReconnectIds.isEmpty())

        testScheduler.advanceTimeBy(failed.nextCheckMillis)
        val updated = viewModel.refreshFacebookIfNeeded("owner") {
            user(Long.MAX_VALUE).copy(userId = "owner")
        }
        assertEquals(listOf("p1"), updated.outcome.updated.map { it.accountId })
        assertTrue(viewModel.facebookRefreshIssues.value.isEmpty())
    }

    @Test
    fun facebookShowsMissingPageWarningAndClearsItWhenPageReturnsUnchanged() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        viewModel.clock = { 0L }
        connectWithFacebookToken()
        fakeRepository.facebookRefreshOutcome = FacebookRefreshOutcome(
            issues = setOf(FacebookRefreshIssue.PAGE_NOT_RETURNED),
            issuesByAccountId = mapOf("p1" to FacebookRefreshIssue.PAGE_NOT_RETURNED),
        )

        val first = viewModel.refreshFacebookIfNeeded("owner") { user(Long.MAX_VALUE).copy(userId = "owner") }
        val state = viewModel.uiState.value as ChannelUiState.Success
        assertEquals(setOf("p1"), state.unlistedFacebookPageIds)
        assertTrue(state.facebookReconnectIds.isEmpty())
        assertEquals(ConnectionStatus.CONNECTED, state.channels.single().status)
        assertTrue(viewModel.isFacebookPageUnlisted(state.channels.single()))

        testScheduler.advanceTimeBy(first.nextCheckMillis)
        fakeRepository.facebookRefreshOutcome = FacebookRefreshOutcome(
            skipped = FacebookRefreshSkip.NO_CHANGES,
            confirmedPageIds = setOf("p1"),
        )
        viewModel.refreshFacebookIfNeeded("owner") { user(Long.MAX_VALUE).copy(userId = "owner") }
        assertTrue((viewModel.uiState.value as ChannelUiState.Success).unlistedFacebookPageIds.isEmpty())
    }

    @Test
    fun leavingForegroundCancelsSdkWaitAndNextResumeRefreshesTheSameSession() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        connectWithFacebookToken()
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this)
        }
        val attempts = mutableListOf<String>()
        val cancellations = mutableListOf<String>()
        val monitor = backgroundScope.launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.monitorFacebookRefresh(currentUserId = { "owner" }, currentSessionVersion = { 1L }) {
                    attempts.add("owner")
                    try {
                        awaitCancellation()
                    } finally {
                        cancellations.add("owner")
                    }
                }
            }
        }

        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        testScheduler.runCurrent()
        assertEquals(listOf("owner"), attempts)
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        testScheduler.runCurrent()
        assertEquals(listOf("owner"), cancellations)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        testScheduler.runCurrent()
        assertEquals(listOf("owner", "owner"), attempts)
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        monitor.cancelAndJoin()
    }

    @Test
    fun resumedRefreshReloadsTokenDetailsBeforeRetryingAfterCancellation() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        fakeRepository.scope = "pages_show_list"
        connectWithFacebookToken()
        val account = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        val refresh = launch {
            viewModel.refreshFacebookIfNeeded("owner") {
                fakeRepository.scope = "pages_manage_posts"
                awaitCancellation()
            }
        }
        testScheduler.runCurrent()
        refresh.cancelAndJoin()

        val check = viewModel.refreshFacebookIfNeeded("owner") {
            assertEquals("pages_manage_posts", viewModel.getScopeForAccount(account))
            user(Long.MAX_VALUE).copy(userId = "owner")
        }
        assertEquals(listOf(account.accountId), check.outcome.updated.map { it.accountId })
    }

    @Test
    fun sdkResultFromPreviousSessionDoesNotUpdateAfterAccountSwitch() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        connectWithFacebookToken()
        var currentUser = "first"
        val release = CompletableDeferred<Unit>()
        val attempts = mutableListOf<String>()
        val monitor = backgroundScope.launch {
            viewModel.monitorFacebookRefresh(currentUserId = { currentUser }) {
                attempts.add(currentUser)
                if (currentUser == "first") {
                    release.await()
                    user(40_000L).copy(userId = "first")
                } else {
                    awaitCancellation()
                }
            }
        }

        testScheduler.runCurrent()
        assertEquals(listOf("first"), attempts)
        currentUser = "second"
        release.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf("first", "second"), attempts)
        assertEquals(1_000L, fakeRepository.expiresAt)
        assertEquals(FacebookRefreshSkip.SDK_SESSION_CHANGED, viewModel.facebookRefreshState.value?.outcome?.skipped)
        monitor.cancelAndJoin()
    }

    @Test
    fun facebookRefreshUsesRemainingRetryDelayAfterScreenResumes() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        viewModel.clock = { testScheduler.currentTime }
        connectWithFacebookToken()

        var attempts = 0
        suspend fun refresh(): FacebookUserToken? {
            attempts++
            return null
        }

        assertEquals(15 * 60_000L, checkFacebookRefresh("owner", refresh = ::refresh))
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(10 * 60_000L)

        val retryIn = checkFacebookRefresh("owner", refresh = ::refresh)
        assertEquals(5 * 60_000L, retryIn)
        assertEquals(1, attempts)

        testScheduler.advanceTimeBy(retryIn)
        checkFacebookRefresh("owner", refresh = ::refresh)
        testScheduler.runCurrent()
        assertEquals(2, attempts)
    }

    @Test
    fun facebookRefreshCountsSdkWaitTowardsRetryInterval() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        viewModel.clock = { testScheduler.currentTime }
        connectWithFacebookToken()

        val retryIn = checkFacebookRefresh("owner") {
            delay(30_000L)
            null
        }

        assertEquals(15 * 60_000L - 30_000L, retryIn)
    }

    @Test
    fun facebookRefreshChecksNewUserAfterPreviousUserFinishes() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        connectWithFacebookToken()

        val finishPreviousRefresh = CompletableDeferred<Unit>()
        val attempts = mutableListOf<String>()
        launch {
            checkFacebookRefresh("previous") {
                attempts += "previous"
                finishPreviousRefresh.await()
                null
            }
        }
        testScheduler.runCurrent()
        launch {
            checkFacebookRefresh("current") {
                attempts += "current"
                null
            }
        }
        testScheduler.runCurrent()
        assertEquals(listOf("previous"), attempts)

        finishPreviousRefresh.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf("previous", "current"), attempts)
    }

    @Test
    fun facebookRefreshKeepsTheRetryDelayWhenSwitchingBackToPreviousUser() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        connectWithFacebookToken()

        val attempts = mutableListOf<String>()
        suspend fun check(userId: String) = checkFacebookRefresh(userId) {
            attempts += userId
            null
        }

        check("first")
        testScheduler.advanceTimeBy(5 * 60_000L)
        check("second")
        val retryIn = check("first")

        assertEquals(listOf("first", "second"), attempts)
        assertEquals(10 * 60_000L, retryIn)
    }

    @Test
    fun facebookRefreshRetryDelayUsesElapsedTimeWhenDeviceClockChanges() = runTest {
        var wallTime = 0L
        viewModel.clock = { wallTime }
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        connectWithFacebookToken()

        var attempts = 0
        suspend fun refresh(): FacebookUserToken? {
            attempts++
            return null
        }

        checkFacebookRefresh("owner", refresh = ::refresh)
        testScheduler.advanceTimeBy(5 * 60_000L)
        wallTime += 60 * 60_000L
        val retryIn = checkFacebookRefresh("owner", refresh = ::refresh)

        assertEquals(1, attempts)
        assertEquals(10 * 60_000L, retryIn)
    }

    @Test
    fun facebookAccountChangeBeforeSdkCallDoesNotConsumeRetryInterval() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page", "page-token"))
        fakeRepository.expiresAt = 1_000L
        connectWithFacebookToken()

        var currentUserId = "owner"
        var sdkAttempts = 0
        val candidatesReady = CompletableDeferred<Unit>()
        fakeRepository.beforeFacebookRefresh = { candidatesReady.await() }
        fun isCurrentUser() = currentUserId == "owner"
        suspend fun refresh() = viewModel.refreshFacebookIfNeeded("owner") {
            if (!isCurrentUser()) return@refreshFacebookIfNeeded null
            sdkAttempts++
            throw FacebookRefreshException(FacebookRefreshIssue.SDK_UNAVAILABLE)
        }

        val originalCheck = launch { refresh() }
        testScheduler.runCurrent()
        currentUserId = "another-owner"
        candidatesReady.complete(Unit)
        originalCheck.join()
        assertEquals(0, sdkAttempts)

        currentUserId = "owner"
        refresh()
        assertEquals(1, sdkAttempts)
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
    fun `ViewModel chuyen co ho so Facebook da luu den UI`() = runTest {
        fakeRepository.pages = emptyList()
        connectWithFacebookToken()

        val profile = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        assertEquals(true, viewModel.isFacebookProfile(profile))
    }

    @Test
    fun `ngay sau khi ket noi Page, getScopeForAccount da co quyen`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))

        connectWithFacebookToken()

        val channel = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        assertEquals("public_profile", viewModel.getScopeForAccount(channel))
    }

    @Test
    fun `ket noi thanh cong phat mot lan ten kenh, khong nam trong UiState`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))
        val names = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.connectedEvents.collect { names.add(it) } }

        connectWithFacebookToken()

        assertEquals(listOf("Page Mot"), names)
        // Người đăng ký sau (Fragment được tạo lại) không nhận lại sự kiện cũ.
        val late = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.connectedEvents.collect { late.add(it) } }
        assertTrue(late.isEmpty())
    }

    // ---- Facebook: hạn và quyền thật từ Meta SDK ----

    @Test
    fun `Page duoc ket noi bang han va quyen that cua phien SDK`() = runTest {
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))

        completeFacebookLogin(user(expiresAt = 123_456L, scope = "pages_show_list,pages_manage_posts"))
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(123_456L, fakeRepository.lastUser?.expiresAtMillis)
        assertEquals("pages_show_list,pages_manage_posts", fakeRepository.lastUser?.grantedScope)
    }

    @Test
    fun `huy dang nhap Facebook phat thong bao huy`() = runTest {
        val notices = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { notices.add(it) } }

        assertTrue(viewModel.beginFacebookSdkLogin())
        viewModel.dispatchFacebookSdkResult(viewModel::onFacebookLoginCancelled)

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

    @Test
    fun linkedinDoesNotOpenQueuedBrowserRequestAfterTimeout() = runTest {
        viewModel.startLinkedInLogin()
        testScheduler.runCurrent()
        assertEquals(1, fakeRepository.sessions.size)
        testScheduler.advanceTimeBy(viewModel.linkedInLoginTimeoutMillis + 1)

        val urls = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.browserRequests.collect { urls.add(it) } }
        assertTrue(urls.isEmpty())
        assertEquals(ChannelNotice.LINKEDIN_LOGIN_TIMEOUT, viewModel.notices.first())
    }

    @Test
    fun returningFromBrowserWithoutCallbackReportsElapsedLinkedInTimeout() = runTest {
        var elapsed = 10_000L
        viewModel.elapsedRealtime = { elapsed }
        val (urls, notices) = startLinkedIn()
        assertEquals(1, urls.size)

        elapsed += viewModel.linkedInLoginTimeoutMillis - 1
        viewModel.onChannelScreenResumed()
        assertTrue(notices.isEmpty())

        elapsed++
        viewModel.onChannelScreenResumed()

        assertEquals(listOf(ChannelNotice.LINKEDIN_LOGIN_TIMEOUT), notices)
        testScheduler.advanceTimeBy(viewModel.linkedInLoginTimeoutMillis + 1)
        assertEquals(listOf(ChannelNotice.LINKEDIN_LOGIN_TIMEOUT), notices)
    }

    @Test
    fun linkedinDoesNotOpenQueuedBrowserWhenElapsedTimeExpiresBeforeTimerRuns() = runTest {
        var elapsed = 10_000L
        viewModel.elapsedRealtime = { elapsed }
        viewModel.startLinkedInLogin()
        testScheduler.runCurrent()
        assertEquals(1, fakeRepository.sessions.size)
        elapsed += viewModel.linkedInLoginTimeoutMillis

        val urls = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.browserRequests.collect { urls.add(it) } }

        assertTrue(urls.isEmpty())
        assertEquals(ChannelNotice.LINKEDIN_LOGIN_TIMEOUT, viewModel.notices.first())
    }

    @Test
    fun linkedinDoesNotExchangeCallbackWhenElapsedTimeExpiresBeforeTimerRuns() = runTest {
        var elapsed = 10_000L
        viewModel.elapsedRealtime = { elapsed }
        val (urls, notices) = startLinkedIn()
        val state = stateOf(urls.single())
        elapsed += viewModel.linkedInLoginTimeoutMillis

        callback("exchange_code=ma_doi&state=$state")

        assertNull(fakeRepository.lastBff)
        assertEquals(listOf(ChannelNotice.LINKEDIN_SESSION_EXPIRED), notices)
    }

    @Test
    fun linkedinOpensOnlyLatestQueuedBrowserRequest() = runTest {
        viewModel.startLinkedInLogin()
        testScheduler.runCurrent()
        viewModel.startLinkedInLogin()
        testScheduler.runCurrent()
        assertEquals(2, fakeRepository.sessions.size)

        val urls = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.browserRequests.collect { urls.add(it) } }
        assertEquals(listOf(fakeRepository.sessions.last().first), urls.map(::stateOf))
    }

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
        assertEquals("LinkedIn Org Test", (viewModel.uiState.value as ChannelUiState.Success).channels.single().displayName)
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
        val errors = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.errors.collect { errors.add(it) } }
        val (urls, _) = startLinkedIn()
        val state = stateOf(urls.single())

        callback("exchange_code=ma_gia&state=state_gia")

        assertNull(fakeRepository.lastBff)
        assertEquals(listOf(com.lttbdd.socialagent.R.string.channel_oauth_state_mismatch), errors)

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
        // Không kết nối gì; chỉ báo phiên đã hết hạn để người dùng thử lại.
        assertEquals(listOf(ChannelNotice.LINKEDIN_SESSION_EXPIRED), notices)
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
        val (urls, notices) = startLinkedIn()

        callback("error=access_denied&state=${stateOf(urls.single())}")

        assertEquals(listOf(ChannelNotice.LINKEDIN_LOGIN_CANCELLED), notices)
        assertNull(fakeRepository.lastBff)
    }

    @Test
    fun `deep link huy khong kem state hoac sai state khong huy duoc phien dang cho`() = runTest {
        val (urls, notices) = startLinkedIn()

        callback("error=access_denied")
        callback("error=access_denied&state=ke-tan-cong")

        assertTrue(notices.isEmpty())
        // Phiên thật vẫn dùng được sau các deep link giả.
        callback("exchange_code=${"a".repeat(43)}&state=${stateOf(urls.single())}")
        assertNotNull(fakeRepository.lastBff)
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

        // Timeout, rồi deep link đến muộn thì báo phiên hết hạn (không im lặng) và không đổi token.
        assertEquals(listOf(ChannelNotice.LINKEDIN_LOGIN_TIMEOUT, ChannelNotice.LINKEDIN_SESSION_EXPIRED), notices)
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
    fun `doi token that bai thi bao loi co kieu bang chuoi da dich va khong them kenh`() = runTest {
        fakeRepository.bffResult = Result.failure(ChannelException(ChannelError.LINKEDIN_TOKEN_EXCHANGE_FAILED))
        val errors = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.errors.collect { errors.add(it) } }
        val (urls, _) = startLinkedIn()

        callback("exchange_code=ma_doi&state=${stateOf(urls.single())}")
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf(com.lttbdd.socialagent.R.string.channel_error_linkedin_exchange), errors)
        assertTrue((viewModel.uiState.value as ChannelUiState.Success).channels.isEmpty())
    }

    @Test
    fun `loi mang va loi la duoc anh xa sang chuoi chung, khong lo noi dung loi goc`() = runTest {
        val errors = mutableListOf<Int>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.errors.collect { errors.add(it) } }

        fakeRepository.bffResult = Result.failure(java.io.IOException("chi tiet noi bo cua OkHttp"))
        val (urls, _) = startLinkedIn()
        callback("exchange_code=ma_doi&state=${stateOf(urls.single())}")
        testDispatcher.scheduler.runCurrent()

        assertEquals(
            listOf(com.lttbdd.socialagent.R.string.channel_error_network),
            errors,
        )
    }

    // ---- Sự kiện một lần, phiên hết hạn, demo, lỗi đọc kho ----

    @Test
    fun `su kien khong bi mat khi chua co ai lang nghe, giao dung mot lan khi Fragment quay lai`() = runTest {
        assertTrue(viewModel.beginFacebookSdkLogin())
        viewModel.dispatchFacebookSdkResult { } // SDK không gọi callback và chưa có collector nào.

        val first = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { first.add(it) } }
        assertEquals(listOf(ChannelNotice.FACEBOOK_LOGIN_FAILED), first)

        val second = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { second.add(it) } }
        assertTrue("khong duoc giao lai su kien da nhan", second.isEmpty())
    }

    @Test
    fun `thong bao timeout phat khi nguoi dung dang o trinh duyet van den tay khi quay lai`() = runTest {
        val urls = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.browserRequests.collect { urls.add(it) } }
        viewModel.startLinkedInLogin()
        testDispatcher.scheduler.runCurrent()

        // Quá hạn trong lúc Fragment dừng (không ai nghe notices).
        testDispatcher.scheduler.advanceTimeBy(viewModel.linkedInLoginTimeoutMillis + 1)
        testDispatcher.scheduler.runCurrent()

        val notices = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { notices.add(it) } }
        assertEquals(listOf(ChannelNotice.LINKEDIN_LOGIN_TIMEOUT), notices)
    }

    @Test
    fun `deep link cua BFF khi khong con phien cho thi bao phien het han, link la thi bo qua`() = runTest {
        val notices = mutableListOf<ChannelNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.notices.collect { notices.add(it) } }

        viewModel.onLinkedInCallback("https://evil.example/?exchange_code=ma_gia&state=x")
        viewModel.onLinkedInCallback("com.lttbdd.socialagent://other/path?exchange_code=ma_gia")
        assertTrue(notices.isEmpty())

        callback("exchange_code=ma_that_nhung_tre&state=bat_ky")
        assertEquals(listOf(ChannelNotice.LINKEDIN_SESSION_EXPIRED), notices)
        assertNull(fakeRepository.lastBff)
    }

    @Test
    fun `bat dau dang nhap LinkedIn xong thi man hinh thoat Loading`() = runTest {
        startLinkedIn()

        assertTrue(viewModel.uiState.value is ChannelUiState.Success)
    }

    @Test
    fun `che do demo tat thi khong tao kenh nao`() = runTest {
        viewModel.demoEnabled = false

        viewModel.connectFacebookDemo("[DEMO] Trang Facebook")
        viewModel.connectLinkedInDemo("[DEMO] Tài khoản cá nhân LinkedIn")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue((viewModel.uiState.value as ChannelUiState.Success).channels.isEmpty())
        assertTrue(!viewModel.isDemoEnabled())
    }

    @Test
    fun `che do demo bat thi kenh demo chi nam trong RAM, co dau demo va khong cham vao repository`() = runTest {
        viewModel.demoEnabled = true
        testDispatcher.scheduler.advanceUntilIdle()

        val names = listOf("[DEMO] Trang Facebook", "[DEMO] Tài khoản cá nhân LinkedIn")
        val connected = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.connectedEvents.collect { connected.add(it) }
        }
        viewModel.connectFacebookDemo(names[0])
        viewModel.connectLinkedInDemo(names[1])
        testDispatcher.scheduler.advanceUntilIdle()

        val channels = (viewModel.uiState.value as ChannelUiState.Success).channels
        assertEquals(names, channels.map { it.displayName })
        assertEquals(names, connected)
        assertNotNull(viewModel.getScopeForAccount(channels.first()))
        // Repository (nơi ghi token và metadata thật) không được gọi.
        assertTrue(fakeRepository.getConnectedChannels().first().isEmpty())
        assertNull(fakeRepository.lastUser)
        assertNull(fakeRepository.lastBff)
        assertTrue(fakeRepository.sessions.isEmpty())
    }

    @Test
    fun `kenh demo khong bi mat khi repository phat lai danh sach`() = runTest {
        viewModel.demoEnabled = true
        fakeRepository.pages = listOf(FacebookPage("p1", "Page Mot", "t1"))
        viewModel.connectFacebookDemo("[DEMO] Trang Facebook")
        testDispatcher.scheduler.advanceUntilIdle()

        connectWithFacebookToken()

        val names = (viewModel.uiState.value as ChannelUiState.Success).channels.map { it.displayName }
        assertEquals(listOf("Page Mot", "[DEMO] Trang Facebook"), names)
    }

    @Test
    fun `doc kho loi thi hien loi va co the thu lai`() = runTest {
        fakeRepository.connectFacebookSelectedPage(user(), FacebookPage("p1", "Page Mot", "t1"))
        fakeRepository.loadError = true
        val errorVm = ChannelViewModel(fakeRepository, callbackBus, SavedStateHandle())

        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ChannelUiState.LoadFailed, errorVm.uiState.value)

        fakeRepository.loadError = false
        errorVm.retryLoadChannels()
        assertEquals(ChannelUiState.Loading(), errorVm.uiState.value)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("p1"), (errorVm.uiState.value as ChannelUiState.Success).channels.map { it.accountId })
    }

    @Test
    fun disconnectDropsQueuedLinkedInBrowserRequest() = runTest {
        val account = fakeRepository.connectLinkedInViaBff("initial-code", "initial-state", "verifier").getOrThrow()
        testScheduler.runCurrent()
        viewModel.startLinkedInLogin()
        testScheduler.runCurrent()
        assertEquals(1, fakeRepository.sessions.size)

        viewModel.disconnectChannel(account)
        testScheduler.runCurrent()

        val urls = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.browserRequests.collect { urls.add(it) }
        }
        assertTrue(urls.isEmpty())

        viewModel.startLinkedInLogin()
        testScheduler.runCurrent()
        assertEquals(2, fakeRepository.sessions.size)
        assertEquals(fakeRepository.sessions.last().first, stateOf(urls.single()))
    }

    @Test
    fun confirmingDisconnectWhileLinkedInExchangeIsLoadingCancelsTheExchange() = runTest {
        val linkedin = fakeRepository.connectLinkedInViaBff("initial-code", "initial-state", "verifier").getOrThrow()
        testScheduler.runCurrent()
        val (urls, _) = startLinkedIn()
        assertEquals(ChannelUiState.Success(listOf(linkedin)), viewModel.uiState.value)
        val tokenReady = CompletableDeferred<Unit>()
        fakeRepository.beforeLinkedInToken = {
            // Mô phỏng yêu cầu mạng đồng bộ vẫn đang chờ phản hồi sau khi coroutine bị hủy.
            withContext(NonCancellable) { tokenReady.await() }
            currentCoroutineContext().ensureActive()
        }

        try {
            callback("exchange_code=reconnect&state=${stateOf(urls.single())}")
            assertEquals("reconnect", fakeRepository.lastBff?.first)
            assertEquals(ChannelUiState.Loading(listOf(linkedin)), viewModel.uiState.value)

            viewModel.disconnectChannel(linkedin)
            viewModel.disconnectChannel(linkedin)
            testScheduler.runCurrent()
            assertEquals(listOf(ChannelType.LINKEDIN to linkedin.accountId), fakeRepository.disconnects)
            assertEquals(ChannelUiState.Success(emptyList()), viewModel.uiState.value)
        } finally {
            tokenReady.complete(Unit)
            testScheduler.runCurrent()
        }
        assertTrue(fakeRepository.getConnectedChannels().first().isEmpty())
    }

    @Test
    fun channelListUpdateKeepsLinkedInExchangeLoadingAndAllowsDisconnect() = runTest {
        val linkedin = fakeRepository.connectLinkedInViaBff("initial-code", "initial-state", "verifier").getOrThrow()
        testScheduler.runCurrent()
        val (urls, _) = startLinkedIn()
        val tokenReady = CompletableDeferred<Unit>()
        fakeRepository.beforeLinkedInToken = { tokenReady.await() }
        fakeRepository.pages = listOf(FacebookPage("a", "Page A", "page-token"))

        try {
            callback("exchange_code=reconnect&state=${stateOf(urls.single())}")
            val facebook = fakeRepository.connectFacebookSelectedPage(user(), fakeRepository.pages.single()).getOrThrow()
            testScheduler.runCurrent()

            assertEquals(ChannelUiState.Loading(listOf(linkedin, facebook)), viewModel.uiState.value)
            assertTrue(viewModel.canDisconnectChannel(linkedin))
            viewModel.disconnectChannel(linkedin)
            testScheduler.runCurrent()
            assertEquals(ChannelUiState.Success(listOf(facebook)), viewModel.uiState.value)
        } finally {
            tokenReady.complete(Unit)
            testScheduler.runCurrent()
        }
        assertEquals(listOf(ChannelType.LINKEDIN to linkedin.accountId), fakeRepository.disconnects)
        assertEquals(listOf(ChannelType.FACEBOOK), fakeRepository.getConnectedChannels().first().map { it.type })
    }

    @Test
    fun disconnectingFacebookKeepsUnrelatedLinkedInExchangeLoading() = runTest {
        fakeRepository.pages = listOf(FacebookPage("a", "Page A", "page-token"))
        connectWithFacebookToken()
        val facebook = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        val (urls, _) = startLinkedIn()
        val tokenReady = CompletableDeferred<Unit>()
        fakeRepository.beforeLinkedInToken = { tokenReady.await() }

        try {
            callback("exchange_code=connect&state=${stateOf(urls.single())}")
            viewModel.disconnectChannel(facebook)
            testScheduler.runCurrent()

            assertEquals(listOf(ChannelType.FACEBOOK to facebook.accountId), fakeRepository.disconnects)
            assertEquals(ChannelUiState.Loading(), viewModel.uiState.value)
        } finally {
            tokenReady.complete(Unit)
            testScheduler.runCurrent()
        }
        val channels = (viewModel.uiState.value as ChannelUiState.Success).channels
        assertEquals(listOf(ChannelType.LINKEDIN), channels.map { it.type })
    }

    @Test
    fun linkedInLoginCannotStartWhileDisconnectIsInProgress() = runTest {
        val linkedin = fakeRepository.connectLinkedInViaBff("initial-code", "initial-state", "verifier").getOrThrow()
        testScheduler.runCurrent()
        val disconnectReady = CompletableDeferred<Unit>()
        fakeRepository.disconnectReady = disconnectReady

        try {
            viewModel.disconnectChannel(linkedin)
            testScheduler.runCurrent()
            assertEquals(ChannelUiState.Loading(listOf(linkedin)), viewModel.uiState.value)

            viewModel.startLinkedInLogin()
            testScheduler.runCurrent()
            assertTrue(fakeRepository.sessions.isEmpty())
        } finally {
            disconnectReady.complete(Unit)
            testScheduler.runCurrent()
        }

        val (urls, _) = startLinkedIn()
        assertEquals(1, fakeRepository.sessions.size)
        assertEquals(fakeRepository.sessions.single().first, stateOf(urls.single()))
    }

    @Test
    fun disconnectCancelsLinkedInTokenExchangeAlreadyInProgress() = runTest {
        val linkedIn = fakeRepository.connectLinkedInViaBff("initial-code", "initial-state", "verifier").getOrThrow()
        testScheduler.runCurrent()
        val (urls, _) = startLinkedIn()
        val facebookPagesReady = CompletableDeferred<Unit>()
        val linkedInTokenReady = CompletableDeferred<Unit>()
        fakeRepository.pages = listOf(FacebookPage("page", "Facebook Page", "page-token"))
        fakeRepository.facebookPagesReady = facebookPagesReady
        fakeRepository.beforeLinkedInToken = { linkedInTokenReady.await() }

        completeFacebookLogin()
        testScheduler.runCurrent()
        callback("exchange_code=reconnect&state=${stateOf(urls.single())}")
        assertEquals("reconnect", fakeRepository.lastBff?.first)

        // Facebook hoàn tất trước, danh sách vẫn dùng được trong lúc LinkedIn đang đổi mã.
        facebookPagesReady.complete(Unit)
        testScheduler.runCurrent()
        val loading = viewModel.uiState.value as ChannelUiState.Loading
        assertTrue(linkedIn in loading.channels)
        assertTrue(viewModel.canDisconnectChannel(linkedIn))
        viewModel.disconnectChannel(linkedIn)
        testScheduler.runCurrent()
        assertEquals(listOf(ChannelType.LINKEDIN to linkedIn.accountId), fakeRepository.disconnects)

        linkedInTokenReady.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf(ChannelType.FACEBOOK), fakeRepository.getConnectedChannels().first().map { it.type })
        assertEquals(
            listOf(ChannelType.FACEBOOK),
            (viewModel.uiState.value as ChannelUiState.Success).channels.map { it.type },
        )
    }

    @Test
    fun disconnectCancelsFacebookLoginWhileLoadingPagesAndAllowsNewLogin() = runTest {
        val page = FacebookPage("a", "Page A", "page-token")
        fakeRepository.pages = listOf(page)
        connectWithFacebookToken()
        val facebook = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        val (urls, _) = startLinkedIn()
        val pagesReady = CompletableDeferred<Unit>()
        fakeRepository.facebookPagesReady = pagesReady

        completeFacebookLogin()
        testScheduler.runCurrent()
        callback("exchange_code=connect&state=${stateOf(urls.single())}")
        viewModel.disconnectChannel(facebook)
        testScheduler.runCurrent()
        assertEquals(listOf(ChannelType.FACEBOOK to page.id), fakeRepository.disconnects)

        pagesReady.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf(ChannelType.LINKEDIN), fakeRepository.getConnectedChannels().first().map { it.type })

        completeFacebookLogin()
        testScheduler.runCurrent()
        assertEquals(
            setOf(ChannelType.FACEBOOK, ChannelType.LINKEDIN),
            (viewModel.uiState.value as ChannelUiState.Success).channels.map { it.type }.toSet(),
        )
    }

    @Test
    fun disconnectCancelsFacebookLoginWhileSavingSelectedPage() = runTest {
        val page = FacebookPage("a", "Page A", "page-token")
        fakeRepository.pages = listOf(page)
        connectWithFacebookToken()
        val facebook = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        val (urls, _) = startLinkedIn()
        fakeRepository.pages = listOf(page, FacebookPage("b", "Page B", "other-token"))
        completeFacebookLogin()
        testScheduler.runCurrent()
        val picker = checkNotNull(viewModel.pagePicker.value)
        val pageSaveReady = CompletableDeferred<Unit>()
        fakeRepository.facebookPageSaveReady = pageSaveReady

        viewModel.selectFacebookPage(picker, 0)
        testScheduler.runCurrent()
        callback("exchange_code=connect&state=${stateOf(urls.single())}")
        viewModel.disconnectChannel(facebook)
        testScheduler.runCurrent()
        assertEquals(listOf(ChannelType.FACEBOOK to page.id), fakeRepository.disconnects)

        pageSaveReady.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf(ChannelType.LINKEDIN), fakeRepository.getConnectedChannels().first().map { it.type })
        assertNull(viewModel.pagePicker.value)
    }

    @Test
    fun facebookSdkResultDuringDisconnectDoesNotReconnectAccount() = runTest {
        val page = FacebookPage("a", "Page A", "page-token")
        fakeRepository.pages = listOf(page)
        connectWithFacebookToken()
        val facebook = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        val disconnectReady = CompletableDeferred<Unit>()
        val pagesReady = CompletableDeferred<Unit>()
        fakeRepository.disconnectReady = disconnectReady
        fakeRepository.facebookPagesReady = pagesReady

        assertTrue(viewModel.beginFacebookSdkLogin())
        viewModel.disconnectChannel(facebook)
        testScheduler.runCurrent()
        assertEquals(listOf(ChannelType.FACEBOOK to page.id), fakeRepository.disconnects)
        assertEquals(ChannelUiState.Loading(listOf(facebook)), viewModel.uiState.value)

        viewModel.dispatchFacebookSdkResult { viewModel.onFacebookLoggedIn(user()) }
        testScheduler.runCurrent()
        disconnectReady.complete(Unit)
        testScheduler.runCurrent()
        pagesReady.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(emptyList<ChannelAccount>(), fakeRepository.getConnectedChannels().first())
        assertEquals(ChannelUiState.Success(emptyList()), viewModel.uiState.value)

        completeFacebookLogin()
        testScheduler.runCurrent()
        assertEquals(page.id, fakeRepository.getConnectedChannels().first().single().accountId)
    }

    @Test
    fun facebookSdkResultAfterDisconnectDoesNotReconnectAccount() = runTest {
        val page = FacebookPage("a", "Page A", "page-token")
        fakeRepository.pages = listOf(page)
        connectWithFacebookToken()
        val facebook = (viewModel.uiState.value as ChannelUiState.Success).channels.single()

        assertTrue(viewModel.beginFacebookSdkLogin())
        viewModel.disconnectChannel(facebook)
        testScheduler.runCurrent()
        assertEquals(ChannelUiState.Success(emptyList()), viewModel.uiState.value)

        val restoredState = SavedStateHandle(savedStateHandle.keys().associateWith { savedStateHandle.get<Any>(it) })
        viewModel = ChannelViewModel(fakeRepository, callbackBus, restoredState)
        assertFalse(viewModel.beginFacebookSdkLogin())
        var delivered = false
        viewModel.dispatchFacebookSdkResult {
            delivered = true
            viewModel.onFacebookLoggedIn(user())
        }
        testScheduler.runCurrent()
        assertFalse(delivered)
        assertEquals(emptyList<ChannelAccount>(), fakeRepository.getConnectedChannels().first())
        assertEquals(ChannelUiState.Success(emptyList()), viewModel.uiState.value)

        connectWithFacebookToken()
        assertEquals(page.id, fakeRepository.getConnectedChannels().first().single().accountId)
    }

    @Test
    fun disconnectRemovesOnlyTheSelectedAccountAndClearsItsCachedScope() = runTest {
        fakeRepository.pages = listOf(FacebookPage("a", "Page A", "page-token"))
        connectWithFacebookToken()
        viewModel.demoEnabled = true
        viewModel.connectLinkedInDemo("[DEMO] LinkedIn")
        val channels = (viewModel.uiState.value as ChannelUiState.Success).channels
        val account = channels.first()
        assertNotNull(viewModel.getScopeForAccount(account))
        assertTrue(viewModel.disconnectDeletesSharedData(account))

        viewModel.disconnectChannel(account)
        viewModel.disconnectChannel(account)
        assertEquals(ChannelUiState.Loading(channels), viewModel.uiState.value)
        testScheduler.runCurrent()

        val state = viewModel.uiState.value as ChannelUiState.Success
        assertEquals(listOf(ChannelType.FACEBOOK to "a"), fakeRepository.disconnects)
        assertEquals(ChannelType.LINKEDIN, state.channels.single().type)
        assertNull(viewModel.getScopeForAccount(account))
        assertEquals(ChannelNotice.CHANNEL_DISCONNECTED, viewModel.notices.first())
    }

    @Test
    fun disconnectFailureKeepsTheChannelVisibleAndReportsFailure() = runTest {
        fakeRepository.pages = listOf(FacebookPage("a", "Page A", "page-token"))
        connectWithFacebookToken()
        fakeRepository.disconnectResult = Result.failure(java.io.IOException("Kho lỗi"))
        val account = (viewModel.uiState.value as ChannelUiState.Success).channels.single()

        viewModel.disconnectChannel(account)
        testScheduler.runCurrent()

        assertEquals(account, (viewModel.uiState.value as ChannelUiState.Success).channels.single())
        assertEquals(com.lttbdd.socialagent.R.string.channel_disconnect_failed, viewModel.errors.first())
    }

    @Test
    fun obsoleteChannelListCannotRestoreAccountWhileNewListIsLoading() = runTest {
        fakeRepository.pages = listOf(FacebookPage("a", "Page A", "page-token"))
        connectWithFacebookToken()
        val facebook = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        val obsoleteScopeReady = CompletableDeferred<Unit>()
        val currentScopeReady = CompletableDeferred<Unit>()
        var linkedinScopeReads = 0
        fakeRepository.beforeScopeRead = { type ->
            if (type == ChannelType.LINKEDIN) {
                linkedinScopeReads++
                if (linkedinScopeReads == 1) obsoleteScopeReady.await() else currentScopeReady.await()
            }
        }

        val linkedin = fakeRepository.connectLinkedInViaBff("code", "state", "verifier").getOrThrow()
        testScheduler.runCurrent()
        assertEquals(1, linkedinScopeReads)

        try {
            viewModel.disconnectChannel(facebook)
            testScheduler.runCurrent()
            assertEquals(ChannelUiState.Success(emptyList()), viewModel.uiState.value)

            obsoleteScopeReady.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(2, linkedinScopeReads)
            assertEquals(ChannelUiState.Success(emptyList()), viewModel.uiState.value)
            assertNull(viewModel.getScopeForAccount(facebook))
        } finally {
            obsoleteScopeReady.complete(Unit)
            currentScopeReady.complete(Unit)
            testScheduler.runCurrent()
        }
        assertEquals(ChannelUiState.Success(listOf(linkedin)), viewModel.uiState.value)
    }

    @Test
    fun loadingRemainingAccountsDoesNotRestoreRemovedScope() = runTest {
        fakeRepository.pages = listOf(FacebookPage("a", "Page A", "page-token"))
        connectWithFacebookToken()
        val facebook = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        val linkedin = fakeRepository.connectLinkedInViaBff("code", "state", "verifier").getOrThrow()
        testScheduler.runCurrent()
        val scopeReadStarted = CompletableDeferred<Unit>()
        val scopeReadReady = CompletableDeferred<Unit>()
        val disconnectCleanupReady = CompletableDeferred<Unit>()
        fakeRepository.beforeScopeRead = { type ->
            if (type == ChannelType.LINKEDIN) {
                scopeReadStarted.complete(Unit)
                scopeReadReady.await()
            }
        }
        fakeRepository.disconnectCleanupReady = disconnectCleanupReady

        try {
            viewModel.disconnectChannel(facebook)
            testScheduler.runCurrent()
            assertTrue(scopeReadStarted.isCompleted)

            disconnectCleanupReady.complete(Unit)
            testScheduler.runCurrent()
            assertNull(viewModel.getScopeForAccount(facebook))

            scopeReadReady.complete(Unit)
            testScheduler.runCurrent()
            assertEquals(ChannelUiState.Success(listOf(linkedin)), viewModel.uiState.value)
            assertNull(viewModel.getScopeForAccount(facebook))
        } finally {
            disconnectCleanupReady.complete(Unit)
            scopeReadReady.complete(Unit)
            testScheduler.runCurrent()
        }
    }

    @Test
    fun cleanupFailureHidesDisconnectedAccountBeforeRemainingAccountsFinishLoading() = runTest {
        fakeRepository.pages = listOf(FacebookPage("a", "Page A", "page-token"))
        connectWithFacebookToken()
        val facebook = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        val linkedin = fakeRepository.connectLinkedInViaBff("code", "state", "verifier").getOrThrow()
        testScheduler.runCurrent()
        val scopeReadReady = CompletableDeferred<Unit>()
        fakeRepository.beforeScopeRead = { type ->
            if (type == ChannelType.LINKEDIN) scopeReadReady.await()
        }
        fakeRepository.failDisconnectCleanup = true

        try {
            viewModel.disconnectChannel(facebook)
            testScheduler.runCurrent()

            assertTrue(viewModel.hasPendingDisconnectCleanup.value)
            assertEquals(listOf(linkedin), (viewModel.uiState.value as ChannelUiState.Success).channels)
            assertNull(viewModel.getScopeForAccount(facebook))
            assertEquals(com.lttbdd.socialagent.R.string.channel_disconnect_failed, viewModel.errors.first())
        } finally {
            scopeReadReady.complete(Unit)
            testScheduler.runCurrent()
        }
    }

    @Test
    fun disconnectReportsFacebookSignOutFailureAfterLocalDataWasRemoved() = runTest {
        fakeRepository.pages = listOf(FacebookPage("a", "Page A", "page-token"))
        connectWithFacebookToken()
        val account = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        fakeRepository.failFacebookSignOut = true

        viewModel.disconnectChannel(account)
        testScheduler.runCurrent()

        assertEquals(ChannelUiState.Success(emptyList()), viewModel.uiState.value)
        assertTrue(viewModel.hasPendingDisconnectCleanup.value)
        assertEquals(com.lttbdd.socialagent.R.string.channel_facebook_disconnect_sign_out_failed, viewModel.errors.first())
    }

    @Test
    fun pendingDisconnectCleanupBlocksStartingFacebookAndLinkedInOAuth() = runTest {
        testScheduler.runCurrent()
        fakeRepository.hasPendingDisconnectCleanup.value = true

        assertFalse(viewModel.beginFacebookSdkLogin())
        viewModel.startLinkedInLogin()
        testScheduler.runCurrent()

        assertTrue(fakeRepository.sessions.isEmpty())
        viewModel.retryDisconnectCleanup()
        testScheduler.runCurrent()

        assertTrue(viewModel.beginFacebookSdkLogin())
        viewModel.startLinkedInLogin()
        testScheduler.runCurrent()
        assertEquals(1, fakeRepository.sessions.size)
    }

    @Test
    fun retryRemovesPendingChannelDataAfterAccountHasDisappeared() = runTest {
        fakeRepository.pages = listOf(FacebookPage("a", "Page A", "page-token"))
        connectWithFacebookToken()
        val account = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        fakeRepository.failDisconnectCleanup = true

        viewModel.disconnectChannel(account)
        testScheduler.runCurrent()
        assertEquals(ChannelUiState.Success(emptyList()), viewModel.uiState.value)
        assertTrue(viewModel.hasPendingDisconnectCleanup.value)
        assertEquals(com.lttbdd.socialagent.R.string.channel_disconnect_failed, viewModel.errors.first())

        viewModel.retryDisconnectCleanup()
        testScheduler.runCurrent()
        assertTrue(viewModel.hasPendingDisconnectCleanup.value)
        assertEquals(com.lttbdd.socialagent.R.string.channel_disconnect_failed, viewModel.errors.first())

        fakeRepository.failDisconnectCleanup = false
        // Việc đọc lại danh sách kênh có thể lỗi dù lượt dọn đã có thể hoàn tất.
        fakeRepository.loadError = true
        viewModel.retryDisconnectCleanup()
        testScheduler.runCurrent()

        assertFalse(viewModel.hasPendingDisconnectCleanup.value)
        assertEquals(listOf(ChannelType.FACEBOOK to account.accountId), fakeRepository.disconnects)
        assertEquals(ChannelUiState.Success(emptyList()), viewModel.uiState.value)
    }

    @Test
    fun retryCleanupIsIndependentOfAFailedChannelListLoad() = runTest {
        val fakeRepository = FakeChannelRepository().apply {
            loadError = true
            hasPendingDisconnectCleanup.value = true
            failDisconnectCleanup = true
        }
        val viewModel = ChannelViewModel(fakeRepository, callbackBus, SavedStateHandle())
        testScheduler.runCurrent()
        assertEquals(ChannelUiState.LoadFailed, viewModel.uiState.value)

        viewModel.retryDisconnectCleanup()
        testScheduler.runCurrent()

        assertEquals(ChannelUiState.LoadFailed, viewModel.uiState.value)
        assertTrue(viewModel.hasPendingDisconnectCleanup.value)
        assertEquals(com.lttbdd.socialagent.R.string.channel_disconnect_failed, viewModel.errors.first())

        fakeRepository.failDisconnectCleanup = false
        viewModel.retryDisconnectCleanup()
        testScheduler.runCurrent()

        assertFalse(viewModel.hasPendingDisconnectCleanup.value)
        assertEquals(ChannelUiState.LoadFailed, viewModel.uiState.value)
        assertEquals(1, fakeRepository.channelLoadAttempts)

        fakeRepository.loadError = false
        viewModel.retryLoadChannels()
        testScheduler.runCurrent()
        assertEquals(ChannelUiState.Success(emptyList()), viewModel.uiState.value)
        assertEquals(2, fakeRepository.channelLoadAttempts)
    }

    @Test
    fun facebookResultAfterRecreationDoesNotHideChannelLoadFailureOrCleanup() = runTest {
        val repository = FakeChannelRepository().apply {
            loadError = true
            hasPendingDisconnectCleanup.value = true
            failDisconnectCleanup = true
            pagesResult = Result.failure(java.io.IOException("Không tải được Page"))
        }
        assertTrue(viewModel.beginFacebookSdkLogin())
        val restoredState = SavedStateHandle(savedStateHandle.keys().associateWith { savedStateHandle.get<Any>(it) })
        val restored = ChannelViewModel(repository, callbackBus, restoredState)
        testScheduler.runCurrent()
        assertEquals(ChannelUiState.LoadFailed, restored.uiState.value)

        restored.dispatchFacebookSdkResult { restored.onFacebookLoggedIn(user()) }
        testScheduler.runCurrent()

        assertEquals(ChannelNotice.FACEBOOK_PAGES_LOAD_FAILED, restored.notices.first())
        assertEquals(ChannelUiState.LoadFailed, restored.uiState.value)
        assertTrue(restored.hasPendingDisconnectCleanup.value)

        repository.failDisconnectCleanup = false
        restored.retryDisconnectCleanup()
        testScheduler.runCurrent()
        assertFalse(restored.hasPendingDisconnectCleanup.value)
        assertEquals(ChannelUiState.LoadFailed, restored.uiState.value)

        repository.loadError = false
        restored.retryLoadChannels()
        testScheduler.runCurrent()
        assertEquals(ChannelUiState.Success(emptyList()), restored.uiState.value)
    }

    @Test
    fun disconnectDemoAccountDoesNotTouchPersistentStorage() = runTest {
        viewModel.demoEnabled = true
        testScheduler.runCurrent()
        viewModel.connectFacebookDemo("[DEMO] Facebook")
        val account = (viewModel.uiState.value as ChannelUiState.Success).channels.single()
        assertTrue(!viewModel.disconnectDeletesSharedData(account))

        viewModel.disconnectChannel(account)
        testScheduler.runCurrent()

        assertTrue(fakeRepository.disconnects.isEmpty())
        assertTrue((viewModel.uiState.value as ChannelUiState.Success).channels.isEmpty())
    }

    private class FakeChannelRepository : ChannelRepository {
    private class FakeChannelRepository(elapsedRealtime: () -> Long = { 0L }) : ChannelRepository {
        private val channels = MutableStateFlow<List<ChannelAccount>>(emptyList())

        val pageAccessIssues = MutableStateFlow<Map<String, FacebookPageAccessIssue>>(emptyMap())
        override fun observeFacebookPageAccessIssues() = pageAccessIssues.asStateFlow()

        var pages: List<FacebookPage> = emptyList()

        /** Khác null thì [fetchFacebookPages] trả kết quả này thay cho [pages]. */
        var pagesResult: Result<List<FacebookPage>>? = null

        /** Thời điểm hết hạn token giả; null nghĩa là repository không đọc được. */
        var expiresAt: Long? = null
        var facebookPageSessionExpiresAt: Long? = null

        override suspend fun getFacebookPageSessionExpiresAt(accountId: String) = facebookPageSessionExpiresAt

        override suspend fun getTokenExpiresAt(type: ChannelType, accountId: String): Long? = expiresAt

        var facebookPagesReady: CompletableDeferred<Unit>? = null
        var facebookPageSaveReady: CompletableDeferred<Unit>? = null

        override suspend fun fetchFacebookPages(userAccessToken: String): Result<List<FacebookPage>> {
            facebookPagesReady?.await()
            return pagesResult ?: Result.success(pages)
        }
        var beforeFacebookRefresh: suspend () -> Unit = {}

        var facebookRefreshOutcome: FacebookRefreshOutcome? = null

        private val refreshCoordinator = FacebookRefreshCoordinator(elapsedRealtime)

        override suspend fun refreshFacebookChannels(
            userId: String?,
            expiresBefore: Long,
            sessionVersion: () -> Long?,
            refresh: suspend () -> FacebookUserToken?,
        ): FacebookRefreshCheck = refreshCoordinator.check(userId, refresh, sessionVersion) { attempt ->
            if (expiresAt?.let { it <= expiresBefore } != true) {
                return@check FacebookRefreshOutcome(skipped = FacebookRefreshSkip.NO_ELIGIBLE_CHANNELS)
            }
            beforeFacebookRefresh()
            val user = try {
                attempt()
            } catch (e: FacebookRefreshException) {
                return@check facebookRefreshOutcome ?: FacebookRefreshOutcome(issues = setOf(e.issue))
            }
            facebookRefreshOutcome?.let { return@check it }
            if (user == null || user.userId != userId) {
                return@check FacebookRefreshOutcome(skipped = FacebookRefreshSkip.SDK_SESSION_CHANGED)
            }
            expiresAt = user.expiresAtMillis
            FacebookRefreshOutcome(updated = channels.value.filter { it.type == ChannelType.FACEBOOK })
        }

        override suspend fun fetchFacebookPages(userAccessToken: String): Result<List<FacebookPage>> =
            pagesResult ?: Result.success(pages)

        /** Phiên Facebook mà repository nhận lần cuối (để kiểm hạn/quyền thật được chuyển xuống). */
        var lastUser: FacebookUserToken? = null

        var scope: String? = "public_profile"

        /** (state, code_challenge) đã đăng ký với BFF; [sessionResult] điều khiển kết quả đăng ký. */
        val sessions = mutableListOf<Pair<String, String>>()
        var sessionResult: Result<Unit> = Result.success(Unit)
        var bffResult: Result<ChannelAccount>? = null
        var beforeLinkedInToken: suspend () -> Unit = {}
        var lastBff: Triple<String, String, String>? = null

        override suspend fun startLinkedInSession(state: String, codeChallenge: String): Result<Unit> {
            sessions.add(state to codeChallenge)
            return sessionResult
        }

        override suspend fun connectLinkedInViaBff(
            exchangeCode: String,
            state: String,
            codeVerifier: String,
            expectedAccountId: String?,
        ): Result<ChannelAccount> {
            lastBff = Triple(exchangeCode, state, codeVerifier)
            beforeLinkedInToken()
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

        override suspend fun isFacebookProfile(accountId: String): Boolean = accountId == "fb_101"

        var profileFailure: ChannelException? = null

        override suspend fun connectFacebookProfile(user: FacebookUserToken): Result<ChannelAccount> {
            lastUser = user
            profileFailure?.let { return Result.failure(it) }
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

        override suspend fun connectFacebookSelectedPage(
            user: FacebookUserToken,
            page: FacebookPage,
        ): Result<ChannelAccount> {
            lastUser = user
            facebookPageSaveReady?.await()
            val account = ChannelAccount(
                type = ChannelType.FACEBOOK,
                accountId = page.id,
                displayName = page.name,
                status = ConnectionStatus.CONNECTED,
                capabilities = setOf(ChannelCapability.PUBLISH),
            )
            channels.value = channels.value.filterNot {
                it.type == account.type && it.accountId == account.accountId
            } + account
            return Result.success(account)
        }

        /** Bật thì [getConnectedChannels] ném lỗi đọc kho (như DataStore IOException). */
        var loadError = false
        var channelLoadAttempts = 0

        override fun getConnectedChannels(): Flow<List<ChannelAccount>> = flow {
            channelLoadAttempts++
            if (loadError) throw java.io.IOException("doc kho loi")
            if (!failDisconnectCleanup) hasPendingDisconnectCleanup.value = false
            emitAll(channels.asStateFlow())
        }

        var beforeScopeRead: suspend (ChannelType) -> Unit = {}

        override suspend fun getGrantedScope(type: ChannelType, accountId: String): String? {
            beforeScopeRead(type)
            return if (type == ChannelType.FACEBOOK) scope else null
        }

        val disconnects = mutableListOf<Pair<ChannelType, String>>()
        var disconnectResult = Result.success(Unit)
        var disconnectReady: CompletableDeferred<Unit>? = null
        var disconnectCleanupReady: CompletableDeferred<Unit>? = null
        override val hasPendingDisconnectCleanup = MutableStateFlow(false)
        var failDisconnectCleanup = false
        var failFacebookSignOut = false

        override suspend fun retryPendingDisconnectCleanup(): Boolean {
            if (!failDisconnectCleanup) hasPendingDisconnectCleanup.value = false
            return !hasPendingDisconnectCleanup.value
        }

        override suspend fun disconnectChannel(type: ChannelType, accountId: String): Result<Unit> {
            disconnects.add(type to accountId)
            disconnectReady?.await()
            if (disconnectResult.isFailure) return disconnectResult
            channels.value = channels.value.filterNot { it.type == type && it.accountId == accountId }
            disconnectCleanupReady?.await()
            if (failDisconnectCleanup) {
                hasPendingDisconnectCleanup.value = true
                return Result.failure(ChannelException(ChannelError.DISCONNECT_CLEANUP_FAILED))
            }
            if (failFacebookSignOut) {
                hasPendingDisconnectCleanup.value = true
                return Result.failure(ChannelException(ChannelError.FACEBOOK_SIGN_OUT_FAILED))
            }
            return Result.success(Unit)
        }
    }
}
