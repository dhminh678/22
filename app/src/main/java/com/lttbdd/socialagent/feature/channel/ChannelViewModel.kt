package com.lttbdd.socialagent.feature.channel

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.BuildConfig
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.data.model.toChannelError
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.ChannelError
import com.lttbdd.socialagent.data.model.ChannelException
import com.lttbdd.socialagent.data.model.FACEBOOK_REFRESH_RETRY
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookPageAccessIssue
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.model.FacebookRefreshIssue
import com.lttbdd.socialagent.data.model.FacebookRefreshCheck
import com.lttbdd.socialagent.data.model.FacebookRefreshSkip
import com.lttbdd.socialagent.data.network.LinkedInBffAuth
import com.lttbdd.socialagent.data.network.PkceUtil
import com.lttbdd.socialagent.data.repository.ChannelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel xử lý màn hình Kênh của tôi (F1).
 * Nguồn tham chiếu:
 * - RFC 8252: LinkedIn mở trình duyệt hệ thống, BFF chuyển về app bằng deep link; Facebook dùng Meta SDK (LoginManager).
 * - RFC 7636: PKCE giữa app và BFF (code_verifier, code_challenge S256).
 * - RFC 6749: Trao đổi code đổi token ở BFF (giữ client_secret); hạn token lấy từ nhà cung cấp.
 */
@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val repository: ChannelRepository,
    private val callbackBus: LinkedInCallbackBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChannelUiState>(ChannelUiState.Loading)
    val uiState: StateFlow<ChannelUiState> = _uiState.asStateFlow()

    /** Quyền token đã được cấp thật, theo từng kênh. Rỗng/null nghĩa là chưa xác định được. */
    private var grantedScopes: Map<String, String?> = emptyMap()

    /** Page task đã lưu theo từng kênh (null/vắng = chưa biết). */
    private var pageTasks: Map<String, List<String>?> = emptyMap()
    private val facebookProfiles = mutableMapOf<String, Boolean?>()

    /** Danh sách và trạng thái gốc của kênh từ repository. */
    private var knownChannels: List<ChannelAccount> = emptyList()
    private val tokenExpiries = mutableMapOf<String, Long>()
    private val facebookPageSessionExpiries = mutableMapOf<String, Long>()
    private val tokenExpiryClockRevision = MutableStateFlow(0L)
    private var tokenRevision = 0L
    private val legacyFacebookPages = mutableSetOf<String>()
    private var facebookPageAccessIssues = emptyMap<String, FacebookPageAccessIssue>()
    private val invalidFacebookSessions = mutableSetOf<String>()
    private val unlistedFacebookPages = mutableSetOf<String>()
    private val retryableFacebookPages = mutableSetOf<String>()
    private val _facebookRefreshState = MutableStateFlow<FacebookRefreshCheck?>(null)
    internal val facebookRefreshState = _facebookRefreshState.asStateFlow()
    private val _facebookRefreshIssues = MutableStateFlow<Set<FacebookRefreshIssue>>(emptySet())
    internal val facebookRefreshIssues = _facebookRefreshIssues.asStateFlow()
    private var lastFacebookRefreshUserId: String? = null
    private var facebookRefreshInterrupted = false

    private val _pagePicker = MutableStateFlow<FacebookPagePicker?>(null)

    /** Khác null khi cần người dùng chọn một trong nhiều Facebook Page. */
    val pagePicker: StateFlow<FacebookPagePicker?> = _pagePicker.asStateFlow()

    // Sự kiện một lần dùng Channel (không phải SharedFlow): khi Fragment đang dừng (ví dụ người dùng ở trình duyệt),
    // sự kiện được giữ trong bộ đệm và giao đúng một lần khi Fragment quay lại STARTED, không bị bỏ như tryEmit.
    private val _notices = Channel<ChannelNotice>(Channel.BUFFERED)
    val notices: Flow<ChannelNotice> = _notices.receiveAsFlow()

    private val _connected = Channel<String>(Channel.BUFFERED)

    /** Phát một lần tên kênh vừa kết nối xong (không nằm trong UiState nên không hiện lại khi quay màn hình). */
    val connectedEvents: Flow<String> = _connected.receiveAsFlow()

    private val _errors = Channel<Int>(Channel.BUFFERED)

    /** Phát một lần mã chuỗi lỗi ([androidx.annotation.StringRes]) cần báo cho người dùng. */
    val errors: Flow<Int> = _errors.receiveAsFlow()

    private data class LinkedInBrowserRequest(val state: String, val url: String)

    private val _browserRequests = Channel<LinkedInBrowserRequest>(Channel.CONFLATED)

    /** Chỉ mở trình duyệt nếu URL vẫn thuộc phiên LinkedIn đang chờ. */
    val browserRequests: Flow<String> = _browserRequests.receiveAsFlow()
        .filter { request ->
            val pending = pendingLinkedIn ?: return@filter false
            if (pending.state != request.state) return@filter false
            !expireLinkedInLoginIfNeeded(pending, ChannelNotice.LINKEDIN_LOGIN_TIMEOUT)
        }
        .map { it.url }

    /** Phiên Facebook giữ tạm trong bộ nhớ trong lúc chờ chọn Page; xóa khi chọn xong hoặc hủy. */
    private var pendingFacebookUser: FacebookUserToken? = null

    /** Phiên đăng nhập LinkedIn đang chờ deep link; chỉ nằm trong RAM (verifier không được lưu ở đâu khác). */
    private class PendingLinkedInLogin(
        val state: String,
        val codeVerifier: String,
        val expectedAccountId: String?,
        val deadlineMillis: Long,
    )

    private var pendingLinkedIn: PendingLinkedInLogin? = null
    private var linkedInLoginJob: Job? = null

    /** Cấu hình tách ra để unit test điều khiển được. Không có secret nào trong app. */
    internal var linkedInClientId: String = BuildConfig.LINKEDIN_MEMBER_CLIENT_ID.trim()
    internal var linkedInBffUrl: String = BuildConfig.LINKEDIN_BFF_URL.trim()
    internal var linkedInLoginTimeoutMillis: Long = LINKEDIN_LOGIN_TIMEOUT_MILLIS

    /** Đồng hồ đơn điệu cho các khoảng chờ, bao gồm thời gian thiết bị ngủ sâu. */
    internal var elapsedRealtime: () -> Long = SystemClock::elapsedRealtime

    /**
     * Chế độ demo (chưa cấu hình khóa thật) chỉ bật ở bản debug. Kênh demo chỉ nằm trong RAM của ViewModel:
     * không lưu token hay metadata nào, nên không thể lọt vào kho thật hoặc được F5/F6 dùng làm token thật.
     */
    internal var demoEnabled: Boolean = BuildConfig.DEBUG

    private var demoChannels: List<ChannelAccount> = emptyList()

    /** Đồng hồ để xác định token hết hạn; tách ra để unit test điều khiển được thời gian. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    init {
        loadChannels()
        viewModelScope.launch {
            repository.observeFacebookPageAccessIssues().collect { issues ->
                facebookPageAccessIssues = issues
                if (_uiState.value is ChannelUiState.Success) publishChannels()
            }
        }
        viewModelScope.launch { callbackBus.callbacks.collect { onLinkedInCallback(it) } }
    }

    companion object {
        /** Thời gian chờ người dùng hoàn tất đăng nhập LinkedIn trong trình duyệt. */
        const val LINKEDIN_LOGIN_TIMEOUT_MILLIS = 5 * 60 * 1000L

        /** Scope giả cho kênh Facebook demo (chỉ RAM, bản debug). */
        private const val DEMO_FACEBOOK_SCOPE = "pages_manage_posts,pages_read_engagement"
        private const val FACEBOOK_REFRESH_WINDOW = 3 * 24 * 60 * 60 * 1000L
    }

    private fun loadChannels() {
        viewModelScope.launch {
            repository.getConnectedChannels()
                // Đọc kho lỗi (DataStore IOException/CorruptionException...) không được làm app crash.
                .catch {
                    _uiState.value = ChannelUiState.LoadFailed
                }
                .collect { channels ->
                    // Nạp quyền và hạn token trước khi phát UiState để ma trận không hiện sai.
                    channels.forEach { loadTokenDetails(it) }
                    val facebookIds = channels.filter { it.type == ChannelType.FACEBOOK }.map { it.accountId }.toSet()
                    facebookPageSessionExpiries.keys.retainAll(facebookIds)
                    invalidFacebookSessions.retainAll(facebookIds)
                    unlistedFacebookPages.retainAll(facebookIds)
                    retryableFacebookPages.retainAll(facebookIds)
                    knownChannels = channels
                    publishChannels()
                }
        }
    }

    fun retryLoadChannels() {
        if (_uiState.value != ChannelUiState.LoadFailed) return
        _uiState.value = ChannelUiState.Loading
        loadChannels()
    }

    /** Phát danh sách hiện tại: kênh thật đã kết nối, cộng kênh demo (chỉ trong RAM, bản debug). */
    private fun publishChannels() {
        val channels = knownChannels.map(::withCurrentExpiry)
        _uiState.value = ChannelUiState.Success(
            channels + demoChannels,
            tokenRevision,
            channels.filter(::needsFacebookReconnect).map { it.accountId }.toSet(),
            unlistedFacebookPages.toSet(),
            retryableFacebookPages.toSet(),
            channels.filter(::needsFacebookPagePermission).map { it.accountId }.toSet(),
        )
    }

    /** Khi quay lại màn hình, kiểm tra hạn ngay cả khi coroutine hẹn giờ chưa chạy. */
    internal fun onChannelScreenResumed() {
        recheckTokenExpiry()
        pendingLinkedIn?.let { expireLinkedInLoginIfNeeded(it, ChannelNotice.LINKEDIN_LOGIN_TIMEOUT) }
    }

    internal fun recheckTokenExpiry() {
        if (_uiState.value is ChannelUiState.Success) publishChannels()
        // Khởi động lại bộ hẹn giờ ngay cả khi trạng thái hiển thị giống lần phát trước.
        tokenExpiryClockRevision.value++
    }

    /** Cập nhật đúng lúc token hết hạn, kể cả khi kho dữ liệu không phát bản ghi mới. */
    internal suspend fun monitorTokenExpiry() {
        combine(uiState, tokenExpiryClockRevision) { state, _ -> state }.collectLatest { state ->
            if (state !is ChannelUiState.Success) return@collectLatest
            while (true) {
                val connected = state.channels.asSequence().filter { it.status == ConnectionStatus.CONNECTED }
                val tokenDeadlines = connected.mapNotNull { tokenExpiries[scopeKey(it)] }
                val pageSessionDeadlines = connected
                    .filter { it.type == ChannelType.FACEBOOK && it.accountId !in state.facebookReconnectIds }
                    .mapNotNull { facebookPageSessionExpiries[it.accountId] }
                val nextExpiry = (tokenDeadlines + pageSessionDeadlines).minOrNull() ?: return@collectLatest
                val remaining = nextExpiry - clock()
                if (remaining <= 0) {
                    publishChannels()
                    return@collectLatest
                }
                delay(remaining)
            }
        }
    }

    internal suspend fun refreshFacebookIfNeeded(
        userId: String?,
        sessionVersion: () -> Long? = { null },
        refresh: suspend () -> FacebookUserToken?,
    ): FacebookRefreshCheck = updateFacebookRefreshState(userId) {
        repository.refreshFacebookChannels(userId, clock() + FACEBOOK_REFRESH_WINDOW, sessionVersion, refresh)
    }

    internal suspend fun retryFacebookPage(
        pageId: String,
        user: FacebookUserToken,
        version: Long,
        sessionVersion: () -> Long?,
    ): FacebookRefreshCheck = updateFacebookRefreshState(user.userId) {
        repository.retryFacebookPage(pageId, user, version, sessionVersion)
    }

    /** Đồng bộ kết quả mới với màn kênh và giữ thông báo lỗi trong thời gian chờ thử lại. */
    private suspend fun updateFacebookRefreshState(
        userId: String?,
        operation: suspend () -> FacebookRefreshCheck,
    ): FacebookRefreshCheck {
        val check = try {
            if (facebookRefreshInterrupted) {
                // Giao dịch đã bắt đầu có thể đã lưu token dù Activity rời foreground.
                knownChannels.filter { it.type == ChannelType.FACEBOOK }.forEach { loadTokenDetails(it) }
                facebookRefreshInterrupted = false
                tokenRevision++
                if (_uiState.value is ChannelUiState.Success) publishChannels()
            }
            operation().also {
                val outcome = it.outcome
                outcome.updated.forEach { account -> invalidFacebookSessions.remove(account.accountId) }
                invalidFacebookSessions.addAll(outcome.reconnectAccountIds)
                unlistedFacebookPages.removeAll(outcome.confirmedPageIds)
                retryableFacebookPages.removeAll(outcome.confirmedPageIds + outcome.reconnectAccountIds)
                retryableFacebookPages.addAll(outcome.retryablePageIds)
                unlistedFacebookPages.addAll(outcome.issuesByAccountId.filterValues { issue ->
                    issue == FacebookRefreshIssue.PAGE_NOT_RETURNED
                }.keys)
                outcome.updated.forEach { account -> loadTokenDetails(account) }
                if (outcome.updated.isNotEmpty()) tokenRevision++
                if (_uiState.value is ChannelUiState.Success) publishChannels()
            }
        } catch (e: CancellationException) {
            facebookRefreshInterrupted = true
            throw e
        }
        val outcome = check.outcome

        if (lastFacebookRefreshUserId != userId ||
            (outcome.skipped != FacebookRefreshSkip.COOLDOWN &&
                outcome.skipped != FacebookRefreshSkip.SDK_SESSION_CHANGED &&
                outcome.skipped != FacebookRefreshSkip.WAITING_FOR_SESSION)
        ) {
            _facebookRefreshIssues.value = outcome.issues
        }
        lastFacebookRefreshUserId = userId
        _facebookRefreshState.value = check
        return check
    }

    /** Nạp quyền và hạn token của [account] để tính trạng thái hiển thị. */
    private suspend fun loadTokenDetails(account: ChannelAccount) {
        grantedScopes = grantedScopes + (scopeKey(account) to repository.getGrantedScope(account.type, account.accountId))
        pageTasks = pageTasks + (scopeKey(account) to repository.getPageTasks(account.type, account.accountId))
        if (account.type == ChannelType.FACEBOOK) {
            facebookProfiles[account.accountId] = repository.isFacebookProfile(account.accountId)
            val sessionExpiry = repository.getFacebookPageSessionExpiresAt(account.accountId)
            if (sessionExpiry == null) {
                facebookPageSessionExpiries.remove(account.accountId)
            } else {
                facebookPageSessionExpiries[account.accountId] = sessionExpiry
            }
            if (repository.needsFacebookReconnect(account.accountId)) {
                legacyFacebookPages.add(account.accountId)
            } else {
                legacyFacebookPages.remove(account.accountId)
            }
        }
        val expiresAt = repository.getTokenExpiresAt(account.type, account.accountId)
        val key = scopeKey(account)
        if (expiresAt == null) tokenExpiries.remove(key) else tokenExpiries[key] = expiresAt
    }

    private fun withCurrentExpiry(account: ChannelAccount): ChannelAccount {
        val expiresAt = tokenExpiries[scopeKey(account)]
        return if (account.status == ConnectionStatus.CONNECTED && expiresAt != null && clock() >= expiresAt) {
            account.copy(status = ConnectionStatus.TOKEN_EXPIRED)
        } else {
            account
        }
    }

    internal fun needsFacebookReconnect(account: ChannelAccount): Boolean =
        account.type == ChannelType.FACEBOOK &&
            (account.accountId in legacyFacebookPages || account.accountId in invalidFacebookSessions ||
                facebookPageSessionExpiries[account.accountId]?.let { clock() >= it } == true ||
                facebookPageAccessIssue(account) == FacebookPageAccessIssue.RECONNECT_REQUIRED)

    internal fun facebookPageAccessIssue(account: ChannelAccount): FacebookPageAccessIssue? =
        if (account.type == ChannelType.FACEBOOK) facebookPageAccessIssues[account.accountId] else null

    private fun needsFacebookPagePermission(account: ChannelAccount): Boolean =
        facebookPageAccessIssue(account) == FacebookPageAccessIssue.PERMISSION_REQUIRED

    internal suspend fun monitorFacebookPageAccess() {
        while (true) {
            repository.checkFacebookPageAccess()
            delay(FACEBOOK_REFRESH_RETRY)
        }
    }

    internal fun isFacebookPageUnlisted(account: ChannelAccount): Boolean =
        account.type == ChannelType.FACEBOOK && account.accountId in unlistedFacebookPages

    internal fun canRetryFacebookPage(account: ChannelAccount): Boolean =
        account.type == ChannelType.FACEBOOK && account.accountId in retryableFacebookPages &&
            !needsFacebookReconnect(account)

    private fun scopeKey(account: ChannelAccount) = "${account.type.name}_${account.accountId}"

    /**
     * Trả về quyền thật của token đã lưu; null nếu chưa đọc được
     * (khi đó ma trận hiển thị "Cần cấp thêm quyền", không giả định đã đủ quyền).
     */
    fun getScopeForAccount(account: ChannelAccount): String? = grantedScopes[scopeKey(account)]

    /** Page task của người dùng trên Page này; null nếu chưa biết (ma trận khi đó chỉ xét quyền của app). */
    fun getTasksForAccount(account: ChannelAccount): List<String>? = pageTasks[scopeKey(account)]

    fun isFacebookProfile(account: ChannelAccount): Boolean? =
        if (account.type == ChannelType.FACEBOOK) facebookProfiles[account.accountId] else null

    /** Demo khi chưa cấu hình client id công khai hoặc địa chỉ BFF (còn placeholder). */
    fun isLinkedInDemoMode(): Boolean =
        linkedInClientId.isBlank() || linkedInClientId.contains("SOCIAL_AGENT") ||
            linkedInBffUrl.isBlank() || linkedInBffUrl.contains("SOCIAL_AGENT")

    /** Chỉ bản debug mới cho thử kết nối giả khi chưa cấu hình khóa; bản khác chỉ báo chưa cấu hình. */
    fun isDemoEnabled(): Boolean = demoEnabled

    fun connectFacebookDemo(displayName: String) {
        addDemoChannel(ChannelType.FACEBOOK, "demo_fb_page", displayName, DEMO_FACEBOOK_SCOPE)
    }

    fun connectLinkedInDemo(displayName: String) {
        addDemoChannel(ChannelType.LINKEDIN, "demo_li_member", displayName, LinkedInBffAuth.SCOPE)
    }

    /**
     * Thêm kênh demo vào RAM. Không gọi repository, không ghi SecureTokenStore/DataStore: tắt app là mất,
     * và tên có tiền tố "[DEMO]" để không ai nhầm với kênh thật.
     */
    private fun addDemoChannel(type: ChannelType, accountId: String, displayName: String, scope: String) {
        if (!demoEnabled) return
        val account = ChannelAccount(
            type = type,
            accountId = accountId,
            displayName = displayName,
            status = ConnectionStatus.CONNECTED,
            capabilities = emptySet(),
        )
        grantedScopes = grantedScopes + (scopeKey(account) to scope)
        pageTasks = pageTasks + (scopeKey(account) to null)
        demoChannels = demoChannels.filterNot { it.type == type && it.accountId == accountId } + account
        publishChannels()
        _connected.trySend(displayName)
    }

    /**
     * Bắt đầu đăng nhập LinkedIn qua BFF (RFC 8252 + RFC 7636, docs/research-social-api.md Luồng B):
     * 1. sinh `state` và cặp `code_verifier`/`code_challenge` (verifier chỉ ở RAM);
     * 2. đăng ký (state, challenge) với BFF;
     * 3. phát URL đăng nhập LinkedIn (redirect_uri là BFF) cho Fragment mở bằng trình duyệt hệ thống;
     * 4. chờ deep link của BFF ([onLinkedInCallback]); quá hạn thì báo timeout.
     */
    fun startLinkedInLogin(expectedAccountId: String? = null) {
        if (!LinkedInBffAuth.isValidBaseUrl(linkedInBffUrl)) {
            _notices.trySend(ChannelNotice.LINKEDIN_LOGIN_FAILED)
            return
        }
        linkedInLoginJob?.cancel()
        pendingLinkedIn = null
        linkedInLoginJob = viewModelScope.launch {
            val verifier = PkceUtil.generateCodeVerifier()
            val state = PkceUtil.generateState()
            // BFF trên gói free có thể đang ngủ và mất tới ~1 phút để dậy: hiện tiến trình thay vì im lặng.
            _uiState.value = ChannelUiState.Loading
            val registered = repository.startLinkedInSession(state, PkceUtil.generateCodeChallengeS256(verifier))
            publishChannels()
            if (registered.isFailure) {
                _notices.trySend(ChannelNotice.LINKEDIN_LOGIN_FAILED)
                return@launch
            }
            val timeoutMillis = linkedInLoginTimeoutMillis
            pendingLinkedIn = PendingLinkedInLogin(
                state, verifier, expectedAccountId, deadlineMillis = elapsedRealtime() + timeoutMillis,
            )
            _browserRequests.send(
                LinkedInBrowserRequest(state, LinkedInBffAuth.authorizationUrl(linkedInClientId, linkedInBffUrl, state)),
            )
            delay(timeoutMillis)
            if (pendingLinkedIn?.state == state) {
                pendingLinkedIn = null
                _notices.trySend(ChannelNotice.LINKEDIN_LOGIN_TIMEOUT)
            }
        }
    }

    /**
     * Xử lý deep link BFF chuyển về. Bỏ qua mọi thứ không đúng dạng hoặc khi không có phiên đang chờ,
     * nên một intent bên ngoài không thể tự kết nối kênh. Chỉ `state` khớp mới đổi `exchange_code`.
     */
    internal fun onLinkedInCallback(url: String) {
        if (!LinkedInAuthRedirect.isRedirect(url, LinkedInBffAuth.APP_REDIRECT_URI)) return
        val pending = pendingLinkedIn
        if (pending == null) {
            // Link của BFF quay về khi không còn phiên chờ (quá hạn 5 phút, hoặc app bị đóng giữa chừng): báo để
            // người dùng thử lại thay vì im lặng.
            _notices.trySend(ChannelNotice.LINKEDIN_SESSION_EXPIRED)
            return
        }
        if (expireLinkedInLoginIfNeeded(pending, ChannelNotice.LINKEDIN_SESSION_EXPIRED)) return
        when (val result = LinkedInAuthRedirect.parse(url, LinkedInBffAuth.APP_REDIRECT_URI, pending.state)) {
            null -> Unit
            LinkedInAuthRedirect.Result.Cancelled -> finishLinkedInLogin(ChannelNotice.LINKEDIN_LOGIN_CANCELLED)
            is LinkedInAuthRedirect.Result.Failure -> {
                if (result.reason == LinkedInAuthRedirect.FailureReason.STATE_MISMATCH) {
                    // Giữ phiên đang chờ: intent giả mạo không được làm hỏng lần đăng nhập thật.
                    _errors.trySend(R.string.channel_oauth_state_mismatch)
                } else {
                    finishLinkedInLogin(ChannelNotice.LINKEDIN_LOGIN_FAILED)
                }
            }
            is LinkedInAuthRedirect.Result.Code -> {
                linkedInLoginJob?.cancel()
                pendingLinkedIn = null
                viewModelScope.launch {
                    _uiState.value = ChannelUiState.Loading
                    applyConnectResult(
                        repository.connectLinkedInViaBff(
                            result.exchangeCode, pending.state, pending.codeVerifier, pending.expectedAccountId,
                        ),
                    )
                }
            }
        }
    }

    /** Đồng hồ này tính cả lúc thiết bị ngủ sâu, khi coroutine hẹn giờ chưa thể chạy. */
    private fun expireLinkedInLoginIfNeeded(pending: PendingLinkedInLogin, notice: ChannelNotice): Boolean {
        if (elapsedRealtime() < pending.deadlineMillis) return false
        finishLinkedInLogin(notice)
        return true
    }

    private fun finishLinkedInLogin(notice: ChannelNotice) {
        linkedInLoginJob?.cancel()
        pendingLinkedIn = null
        _notices.trySend(notice)
    }

    internal fun onLinkedInBrowserUnavailable() {
        linkedInLoginJob?.cancel()
        pendingLinkedIn = null
    }

    /** Người dùng bấm Hủy trong màn Facebook Login: không thay đổi gì, chỉ báo để Fragment hỏi đổi tài khoản. */
    fun onFacebookLoginCancelled() {
        _notices.trySend(ChannelNotice.FACEBOOK_LOGIN_CANCELLED)
    }

    fun onFacebookLoginFailed() {
        _notices.trySend(ChannelNotice.FACEBOOK_LOGIN_FAILED)
    }

    /**
     * Meta SDK đã trả phiên đăng nhập (token, hạn và quyền thật): lấy các Page người dùng quản lý.
     * - 0 Page: kết nối bằng hồ sơ cá nhân và báo [ChannelNotice.NO_FACEBOOK_PAGES].
     * - 1 Page: dùng luôn Page đó.
     * - Nhiều Page: phát [pagePicker] để người dùng chọn.
     */
    fun onFacebookLoggedIn(user: FacebookUserToken, expectedAccountId: String? = null) {
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            if (expectedAccountId != null && expectedAccountId == user.userId) {
                applyConnectResult(repository.connectFacebookProfile(user))
                return@launch
            }
            val pagesResult = repository.fetchFacebookPages(user.accessToken)
            if (pagesResult.isFailure) {
                // Lỗi mạng/quyền khi tải Page KHÔNG đồng nghĩa "không có Page": không nối bằng hồ sơ cá nhân,
                // chỉ báo lỗi để người dùng thử lại.
                _notices.trySend(ChannelNotice.FACEBOOK_PAGES_LOAD_FAILED)
                publishChannels()
                return@launch
            }
            val pages = pagesResult.getOrThrow()
            if (expectedAccountId != null) {
                val target = pages.firstOrNull { it.id == expectedAccountId }
                applyConnectResult(
                    if (target == null) Result.failure(ChannelException(ChannelError.RECONNECT_ACCOUNT_MISMATCH))
                    else repository.connectFacebookSelectedPage(user, target),
                )
                return@launch
            }
            when {
                pages.size > 1 -> {
                    pendingFacebookUser = user
                    publishChannels()
                    _pagePicker.value = FacebookPagePicker(pages)
                }
                pages.size == 1 -> applyConnectResult(repository.connectFacebookSelectedPage(user, pages.first()))
                else -> applyConnectResult(
                    repository.connectFacebookProfile(user),
                    successNotice = ChannelNotice.NO_FACEBOOK_PAGES,
                )
            }
        }
    }

    /** Người dùng chọn một Page trong hộp thoại: kết nối Page đó. */
    fun selectFacebookPage(page: FacebookPage) {
        val user = pendingFacebookUser ?: return
        pendingFacebookUser = null
        _pagePicker.value = null
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            applyConnectResult(repository.connectFacebookSelectedPage(user, page))
        }
    }

    /** Người dùng đóng hộp thoại chọn Page mà không chọn: không kết nối gì. */
    fun cancelFacebookPagePicker() {
        pendingFacebookUser = null
        _pagePicker.value = null
    }

    override fun onCleared() {
        pendingFacebookUser = null
        pendingLinkedIn = null
        super.onCleared()
    }

    /**
     * Phát trạng thái sau khi kết nối. Nạp quyền của kênh mới TRƯỚC khi phát để ma trận không đọc scope null
     * (luồng [loadChannels] nạp bất đồng bộ nên có thể chậm hơn kết quả kết nối).
     */
    private suspend fun applyConnectResult(
        result: Result<ChannelAccount>,
        successNotice: ChannelNotice? = null,
    ) {
        result.onSuccess { account ->
            if (account.type == ChannelType.FACEBOOK) {
                invalidFacebookSessions.remove(account.accountId)
                unlistedFacebookPages.remove(account.accountId)
                retryableFacebookPages.remove(account.accountId)
            }
            loadTokenDetails(account)
            val updatedChannels = knownChannels
                .filterNot { it.type == account.type && it.accountId == account.accountId } + account
            knownChannels = updatedChannels
            tokenRevision++
            publishChannels()
            successNotice?.let { _notices.trySend(it) }
            _connected.trySend(account.displayName)
        }.onFailure { error ->
            // Quay về danh sách hiện có (thoát Loading) và báo lỗi bằng chuỗi đã dịch, một lần.
            publishChannels()
            _errors.trySend(errorMessageRes(error.toChannelError()))
        }
    }

    private fun errorMessageRes(error: ChannelError): Int = when (error) {
        ChannelError.NETWORK -> R.string.channel_error_network
        ChannelError.CHANNELS_LOAD_FAILED -> R.string.channel_error_load_failed
        ChannelError.CHANNELS_SAVE_FAILED -> R.string.channel_error_save_failed
        ChannelError.LINKEDIN_CONFIG_INVALID -> R.string.channel_error_linkedin_config
        ChannelError.LINKEDIN_SESSION_START_FAILED -> R.string.channel_error_linkedin_session
        ChannelError.LINKEDIN_TOKEN_EXCHANGE_FAILED -> R.string.channel_error_linkedin_exchange
        ChannelError.LINKEDIN_TOKEN_INVALID -> R.string.channel_error_linkedin_token_invalid
        ChannelError.LINKEDIN_PROFILE_FAILED -> R.string.channel_error_linkedin_profile
        ChannelError.FACEBOOK_PROFILE_FAILED -> R.string.channel_error_facebook_profile
        ChannelError.RECONNECT_ACCOUNT_MISMATCH -> R.string.channel_error_reconnect_account_mismatch
        ChannelError.FACEBOOK_SESSION_INVALID -> R.string.channel_facebook_login_error
        ChannelError.FACEBOOK_PAGES_LOAD_FAILED -> R.string.channel_facebook_pages_load_failed
        ChannelError.UNKNOWN -> R.string.channel_oauth_error_generic
    }
}
