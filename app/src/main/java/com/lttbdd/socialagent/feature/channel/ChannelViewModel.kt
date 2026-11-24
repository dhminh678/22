package com.lttbdd.socialagent.feature.channel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.BuildConfig
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.data.model.toChannelError
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.ChannelError
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.network.LinkedInBffAuth
import com.lttbdd.socialagent.data.network.PkceUtil
import com.lttbdd.socialagent.data.repository.ChannelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.job
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
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ChannelUiState>(ChannelUiState.Loading())
    val uiState: StateFlow<ChannelUiState> = _uiState.asStateFlow()
    val hasPendingDisconnectCleanup: StateFlow<Boolean> = repository.hasPendingDisconnectCleanup

    /** Quyền token đã được cấp thật, theo từng kênh. Rỗng/null nghĩa là chưa xác định được. */
    private var grantedScopes: Map<String, String?> = emptyMap()

    /** Page task đã lưu theo từng kênh (null/vắng = chưa biết). */
    private var pageTasks: Map<String, List<String>?> = emptyMap()
    private val facebookProfiles = mutableMapOf<String, Boolean?>()

    /** Danh sách kênh mới nhất từ repository (dùng khi UiState đang ở trạng thái khác Success). */
    private var knownChannels: List<ChannelAccount> = emptyList()
    private enum class ChannelListLoadState { LOADING, FAILED, LOADED }
    private var channelListLoadState = ChannelListLoadState.LOADING

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
        .filter { request -> pendingLinkedIn?.state == request.state }
        .map { it.url }

    /** Phiên Facebook giữ tạm trong bộ nhớ trong lúc chờ chọn Page; xóa khi chọn xong hoặc hủy. */
    private var pendingFacebookUser: FacebookUserToken? = null
    private var facebookLoginJob: Job? = null

    private enum class FacebookSdkRequest { PENDING, INVALIDATED }

    private var facebookSdkRequest: FacebookSdkRequest?
        get() = savedStateHandle[FACEBOOK_SDK_REQUEST_KEY]
        set(value) { savedStateHandle[FACEBOOK_SDK_REQUEST_KEY] = value }

    /** Phiên đăng nhập LinkedIn đang chờ deep link; chỉ nằm trong RAM (verifier không được lưu ở đâu khác). */
    private class PendingLinkedInLogin(val state: String, val codeVerifier: String)

    private var pendingLinkedIn: PendingLinkedInLogin? = null
    private var linkedInLoginJob: Job? = null

    /** Cấu hình tách ra để unit test điều khiển được. Không có secret nào trong app. */
    internal var linkedInClientId: String = BuildConfig.LINKEDIN_MEMBER_CLIENT_ID.trim()
    internal var linkedInBffUrl: String = BuildConfig.LINKEDIN_BFF_URL.trim()
    internal var linkedInLoginTimeoutMillis: Long = LINKEDIN_LOGIN_TIMEOUT_MILLIS

    /**
     * Chế độ demo (chưa cấu hình khóa thật) chỉ bật ở bản debug. Kênh demo chỉ nằm trong RAM của ViewModel:
     * không lưu token hay metadata nào, nên không thể lọt vào kho thật hoặc được F5/F6 dùng làm token thật.
     */
    internal var demoEnabled: Boolean = BuildConfig.DEBUG

    private var disconnectingChannelType: ChannelType? = null
    private var retryingDisconnectCleanup = false
    // Job đã hủy có thể vẫn chờ I/O kết thúc. Chỉ Job còn hoạt động giữ chỉ báo tải.
    private val oauthOperations = mutableSetOf<Job>()
    private var demoChannels: List<ChannelAccount> = emptyList()

    /** Đồng hồ để xác định token hết hạn; tách ra để unit test điều khiển được thời gian. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    init {
        loadChannels()
        viewModelScope.launch { callbackBus.callbacks.collect { onLinkedInCallback(it) } }
    }

    companion object {
        private const val FACEBOOK_SDK_REQUEST_KEY = "facebook_sdk_request"

        /** Thời gian chờ người dùng hoàn tất đăng nhập LinkedIn trong trình duyệt. */
        const val LINKEDIN_LOGIN_TIMEOUT_MILLIS = 5 * 60 * 1000L

        /** Scope giả cho kênh Facebook demo (chỉ RAM, bản debug). */
        private const val DEMO_FACEBOOK_SCOPE = "pages_manage_posts,pages_read_engagement"
    }

    private fun loadChannels() {
        viewModelScope.launch {
            repository.getConnectedChannels()
                // Đọc kho lỗi (DataStore IOException/CorruptionException...) không được làm app crash.
                .catch {
                    channelListLoadState = ChannelListLoadState.FAILED
                    publishChannels()
                }
                .collectLatest { channels ->
                    // Bỏ lượt đọc cũ nếu danh sách đổi trong khi đang đọc quyền và hạn token.
                    knownChannels = channels.map { resolveAccount(it) }
                    channelListLoadState = ChannelListLoadState.LOADED
                    publishChannels()
                }
        }
    }

    fun retryLoadChannels() {
        if (_uiState.value != ChannelUiState.LoadFailed) return
        channelListLoadState = ChannelListLoadState.LOADING
        publishChannels()
        loadChannels()
    }

    internal fun retryDisconnectCleanup() {
        if (_uiState.value is ChannelUiState.Loading || !hasPendingDisconnectCleanup.value) return
        retryingDisconnectCleanup = true
        publishChannels()
        viewModelScope.launch {
            try {
                if (!repository.retryPendingDisconnectCleanup()) _errors.trySend(R.string.channel_disconnect_failed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _errors.trySend(R.string.channel_disconnect_failed)
            } finally {
                retryingDisconnectCleanup = false
                publishChannels()
            }
        }
    }

    /** Giữ danh sách đã tải trên màn hình trong khi một thao tác khác đang chạy. */
    private fun publishChannels() {
        val channels = knownChannels + demoChannels
        _uiState.value = when {
            channelListLoadState == ChannelListLoadState.LOADING -> ChannelUiState.Loading()
            disconnectingChannelType != null || retryingDisconnectCleanup || oauthOperations.any { it.isActive } ->
                ChannelUiState.Loading(
                    channels = if (channelListLoadState == ChannelListLoadState.LOADED) channels else emptyList(),
                )
            channelListLoadState == ChannelListLoadState.FAILED -> ChannelUiState.LoadFailed
            else -> ChannelUiState.Success(channels)
        }
    }

    private suspend fun <T> withOAuthLoading(operation: suspend () -> T): T {
        val job = currentCoroutineContext().job
        oauthOperations.add(job)
        publishChannels()
        try {
            return operation()
        } finally {
            oauthOperations.remove(job)
            publishChannels()
        }
    }

    internal fun disconnectDeletesSharedData(account: ChannelAccount): Boolean =
        account !in demoChannels && knownChannels.count { it.type == account.type } == 1

    internal fun canDisconnectChannel(account: ChannelAccount): Boolean =
        channelListLoadState == ChannelListLoadState.LOADED &&
            disconnectingChannelType == null && !retryingDisconnectCleanup &&
            (account in knownChannels || account in demoChannels)

    fun disconnectChannel(account: ChannelAccount) {
        if (!canDisconnectChannel(account)) return
        val isDemo = account in demoChannels
        if (!isDemo && account.type == ChannelType.FACEBOOK && facebookSdkRequest == FacebookSdkRequest.PENDING) {
            facebookSdkRequest = FacebookSdkRequest.INVALIDATED
        }
        disconnectingChannelType = account.type
        publishChannels()
        viewModelScope.launch {
            try {
                val result = if (isDemo) {
                    demoChannels = demoChannels - account
                    Result.success(Unit)
                } else {
                    when (account.type) {
                        ChannelType.FACEBOOK -> {
                            facebookLoginJob?.cancel()
                            clearFacebookPagePicker()
                        }
                        ChannelType.LINKEDIN -> {
                            linkedInLoginJob?.cancel()
                            pendingLinkedIn = null
                        }
                    }
                    repository.disconnectChannel(account.type, account.accountId)
                }
                val error = result.exceptionOrNull()?.toChannelError()
                if (error == null || error == ChannelError.DISCONNECT_CLEANUP_FAILED ||
                    error == ChannelError.FACEBOOK_SIGN_OUT_FAILED
                ) {
                    val key = scopeKey(account)
                    grantedScopes = grantedScopes - key
                    pageTasks = pageTasks - key
                    if (account.type == ChannelType.FACEBOOK) facebookProfiles.remove(account.accountId)
                    knownChannels = knownChannels.filterNot {
                        it.type == account.type && it.accountId == account.accountId
                    }
                }
                when (error) {
                    null -> _notices.trySend(ChannelNotice.CHANNEL_DISCONNECTED)
                    ChannelError.FACEBOOK_SIGN_OUT_FAILED -> _errors.trySend(R.string.channel_facebook_disconnect_sign_out_failed)
                    else -> _errors.trySend(R.string.channel_disconnect_failed)
                }
            } finally {
                disconnectingChannelType = null
                publishChannels()
            }
        }
    }

    /**
     * Nạp quyền thật của [account] vào bộ nhớ và trả về bản có trạng thái đúng:
     * token đã quá hạn thì đổi CONNECTED thành TOKEN_EXPIRED (lưu trạng thái này ở UI, không đụng token đã lưu).
     */
    private suspend fun resolveAccount(account: ChannelAccount): ChannelAccount {
        val key = scopeKey(account)
        val scope = repository.getGrantedScope(account.type, account.accountId)
        val tasks = repository.getPageTasks(account.type, account.accountId)
        val isFacebook = account.type == ChannelType.FACEBOOK
        val profile = if (isFacebook) repository.isFacebookProfile(account.accountId) else null
        val expiresAt = repository.getTokenExpiresAt(account.type, account.accountId)

        // Dùng bộ nhớ đệm hiện tại sau các lần đọc có thể tạm dừng.
        grantedScopes = grantedScopes + (key to scope)
        pageTasks = pageTasks + (key to tasks)
        if (isFacebook) facebookProfiles[account.accountId] = profile

        return if (account.status == ConnectionStatus.CONNECTED && expiresAt != null && clock() >= expiresAt) {
            account.copy(status = ConnectionStatus.TOKEN_EXPIRED)
        } else {
            account
        }
    }

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
    fun startLinkedInLogin() {
        if (hasPendingDisconnectCleanup.value || disconnectingChannelType == ChannelType.LINKEDIN) return
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
            val registered = withOAuthLoading {
                repository.startLinkedInSession(state, PkceUtil.generateCodeChallengeS256(verifier))
            }
            if (registered.isFailure) {
                _notices.trySend(ChannelNotice.LINKEDIN_LOGIN_FAILED)
                return@launch
            }
            pendingLinkedIn = PendingLinkedInLogin(state, verifier)
            _browserRequests.send(
                LinkedInBrowserRequest(state, LinkedInBffAuth.authorizationUrl(linkedInClientId, linkedInBffUrl, state)),
            )
            delay(linkedInLoginTimeoutMillis)
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
        val pending = pendingLinkedIn
        if (pending == null) {
            // Link của BFF quay về khi không còn phiên chờ (quá hạn 5 phút, hoặc app bị đóng giữa chừng): báo để
            // người dùng thử lại thay vì im lặng. Link không đúng dạng vẫn bị bỏ qua hoàn toàn.
            if (LinkedInAuthRedirect.isRedirect(url, LinkedInBffAuth.APP_REDIRECT_URI)) {
                _notices.trySend(ChannelNotice.LINKEDIN_SESSION_EXPIRED)
            }
            return
        }
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
                // Vẫn cho phép ngắt kết nối hủy lượt đăng nhập trong lúc BFF đang đổi mã.
                linkedInLoginJob = viewModelScope.launch {
                    withOAuthLoading {
                        applyConnectResult(
                            repository.connectLinkedInViaBff(result.exchangeCode, pending.state, pending.codeVerifier),
                        )
                    }
                }
            }
        }
    }

    private fun finishLinkedInLogin(notice: ChannelNotice) {
        linkedInLoginJob?.cancel()
        pendingLinkedIn = null
        _notices.trySend(notice)
    }

    /** Chỉ một lượt có thể chờ kết quả từ cùng trình khởi chạy, kể cả lượt đã bị vô hiệu hóa. */
    fun beginFacebookSdkLogin(): Boolean {
        if (hasPendingDisconnectCleanup.value || facebookSdkRequest != null ||
            disconnectingChannelType == ChannelType.FACEBOOK
        ) return false
        facebookSdkRequest = FacebookSdkRequest.PENDING
        return true
    }

    fun dispatchFacebookSdkResult(deliver: () -> Unit) {
        // SDK cập nhật token hiện tại khi xử lý kết quả. Bỏ qua lượt cũ trước bước đó.
        if (facebookSdkRequest != FacebookSdkRequest.PENDING) {
            facebookSdkRequest = null
            return
        }
        deliver()
        // Giải phóng lượt đăng nhập nếu SDK không gọi callback cho kết quả này.
        onFacebookLoginFailed()
    }

    private fun consumeFacebookSdkLogin(): Boolean {
        if (facebookSdkRequest != FacebookSdkRequest.PENDING) return false
        facebookSdkRequest = null
        return true
    }

    /** Người dùng bấm Hủy trong màn Facebook Login: báo để Fragment hỏi đổi tài khoản. */
    fun onFacebookLoginCancelled() {
        if (consumeFacebookSdkLogin()) _notices.trySend(ChannelNotice.FACEBOOK_LOGIN_CANCELLED)
    }

    fun onFacebookLoginFailed() {
        if (consumeFacebookSdkLogin()) _notices.trySend(ChannelNotice.FACEBOOK_LOGIN_FAILED)
    }

    /**
     * Meta SDK đã trả phiên đăng nhập (token, hạn và quyền thật): lấy các Page người dùng quản lý.
     * - 0 Page: kết nối bằng hồ sơ cá nhân và báo [ChannelNotice.NO_FACEBOOK_PAGES].
     * - 1 Page: dùng luôn Page đó.
     * - Nhiều Page: phát [pagePicker] để người dùng chọn.
     */
    fun onFacebookLoggedIn(user: FacebookUserToken) {
        if (!consumeFacebookSdkLogin()) return
        if (disconnectingChannelType == ChannelType.FACEBOOK) return
        facebookLoginJob?.cancel()
        clearFacebookPagePicker()
        facebookLoginJob = viewModelScope.launch {
            withOAuthLoading {
                val pagesResult = repository.fetchFacebookPages(user.accessToken)
                if (pagesResult.isFailure) {
                    // Lỗi mạng/quyền khi tải Page KHÔNG đồng nghĩa "không có Page": không nối bằng hồ sơ cá nhân,
                    // chỉ báo lỗi để người dùng thử lại.
                    _notices.trySend(ChannelNotice.FACEBOOK_PAGES_LOAD_FAILED)
                    return@withOAuthLoading
                }
                val pages = pagesResult.getOrThrow()
                when {
                    pages.size > 1 -> {
                        pendingFacebookUser = user
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
    }

    /** Người dùng chọn một Page trong hộp thoại: kết nối Page đó. */
    fun selectFacebookPage(picker: FacebookPagePicker, index: Int) {
        if (picker !== _pagePicker.value) return
        val page = picker.pages.getOrNull(index) ?: return
        val user = pendingFacebookUser ?: return
        clearFacebookPagePicker()
        facebookLoginJob = viewModelScope.launch {
            withOAuthLoading {
                applyConnectResult(repository.connectFacebookSelectedPage(user, page))
            }
        }
    }

    /** Người dùng đóng hộp thoại chọn Page mà không chọn: không kết nối gì. */
    fun cancelFacebookPagePicker(picker: FacebookPagePicker) {
        if (picker === _pagePicker.value) clearFacebookPagePicker()
    }

    private fun clearFacebookPagePicker() {
        pendingFacebookUser = null
        _pagePicker.value = null
    }

    override fun onCleared() {
        clearFacebookPagePicker()
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
        result.onSuccess { connected ->
            val account = resolveAccount(connected)
            val updatedChannels = knownChannels
                .filterNot { it.type == account.type && it.accountId == account.accountId } + account
            knownChannels = updatedChannels
            publishChannels()
            successNotice?.let { _notices.trySend(it) }
            _connected.trySend(account.displayName)
        }.onFailure { error ->
            _errors.trySend(errorMessageRes(error.toChannelError()))
        }
    }

    private fun errorMessageRes(error: ChannelError): Int = when (error) {
        ChannelError.NETWORK -> R.string.channel_error_network
        ChannelError.CHANNELS_LOAD_FAILED -> R.string.channel_error_load_failed
        ChannelError.CHANNELS_SAVE_FAILED -> R.string.channel_error_save_failed
        ChannelError.DISCONNECT_CLEANUP_FAILED -> R.string.channel_disconnect_failed
        ChannelError.LINKEDIN_CONFIG_INVALID -> R.string.channel_error_linkedin_config
        ChannelError.LINKEDIN_SESSION_START_FAILED -> R.string.channel_error_linkedin_session
        ChannelError.LINKEDIN_TOKEN_EXCHANGE_FAILED -> R.string.channel_error_linkedin_exchange
        ChannelError.LINKEDIN_TOKEN_INVALID -> R.string.channel_error_linkedin_token_invalid
        ChannelError.LINKEDIN_PROFILE_FAILED -> R.string.channel_error_linkedin_profile
        ChannelError.FACEBOOK_PROFILE_FAILED -> R.string.channel_error_facebook_profile
        ChannelError.FACEBOOK_PAGES_LOAD_FAILED -> R.string.channel_facebook_pages_load_failed
        ChannelError.FACEBOOK_SIGN_OUT_FAILED -> R.string.channel_facebook_disconnect_sign_out_failed
        ChannelError.UNKNOWN -> R.string.channel_oauth_error_generic
    }
}
