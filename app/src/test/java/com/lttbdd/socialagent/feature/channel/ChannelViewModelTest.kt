package com.lttbdd.socialagent.feature.channel

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
import com.lttbdd.socialagent.data.repository.FacebookRefreshCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
        fakeRepository = FakeChannelRepository { testDispatcher.scheduler.currentTime }
        callbackBus = LinkedInCallbackBus()
        viewModel = ChannelViewModel(fakeRepository, callbackBus)
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
        viewModel.onFacebookLoginFailed() // Fragment đang dừng: chưa có collector nào

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
        val errorVm = ChannelViewModel(fakeRepository, callbackBus)

        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(ChannelUiState.LoadFailed, errorVm.uiState.value)

        fakeRepository.loadError = false
        errorVm.retryLoadChannels()
        assertEquals(ChannelUiState.Loading(), errorVm.uiState.value)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("p1"), (errorVm.uiState.value as ChannelUiState.Success).channels.map { it.accountId })
    }

    private class FakeChannelRepository(elapsedRealtime: () -> Long = { 0L }) : ChannelRepository {
        private val channels = MutableStateFlow<List<ChannelAccount>>(emptyList())
        val pendingDisconnectCleanup = MutableStateFlow(false)
        override val hasPendingDisconnectCleanup: StateFlow<Boolean> = pendingDisconnectCleanup.asStateFlow()
        var retryCleanupResult = true

        override suspend fun retryPendingDisconnectCleanup(): Boolean {
            if (retryCleanupResult) pendingDisconnectCleanup.value = false
            return retryCleanupResult
        }

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

        override fun getConnectedChannels(): Flow<List<ChannelAccount>> =
            if (loadError) flow { throw java.io.IOException("doc kho loi") } else channels.asStateFlow()

        override suspend fun getGrantedScope(type: ChannelType, accountId: String): String? =
            if (type == ChannelType.FACEBOOK) scope else null

        override suspend fun disconnectChannel(type: ChannelType, accountId: String): Result<Unit> {
            channels.value = channels.value.filterNot { it.type == type && it.accountId == accountId }
            return Result.success(Unit)
        }
    }
}
