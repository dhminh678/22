package com.lttbdd.socialagent.feature.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.BuildConfig
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.data.network.LinkedInBffAuth
import com.lttbdd.socialagent.data.network.PkceUtil
import com.lttbdd.socialagent.data.repository.ChannelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Danh sách kênh mới nhất từ repository (dùng khi UiState đang ở trạng thái khác Success). */
    private var knownChannels: List<ChannelAccount> = emptyList()

    private val _pagePicker = MutableStateFlow<FacebookPagePicker?>(null)

    /** Khác null khi cần người dùng chọn một trong nhiều Facebook Page. */
    val pagePicker: StateFlow<FacebookPagePicker?> = _pagePicker.asStateFlow()

    private val _notices = MutableSharedFlow<ChannelNotice>(extraBufferCapacity = 1)
    val notices: SharedFlow<ChannelNotice> = _notices.asSharedFlow()

    private val _browserRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** URL cần mở bằng trình duyệt hệ thống (đăng nhập LinkedIn). */
    val browserRequests: SharedFlow<String> = _browserRequests.asSharedFlow()

    /** Phiên Facebook giữ tạm trong bộ nhớ trong lúc chờ chọn Page; xóa khi chọn xong hoặc hủy. */
    private var pendingFacebookUser: FacebookUserToken? = null

    /** Phiên đăng nhập LinkedIn đang chờ deep link; chỉ nằm trong RAM (verifier không được lưu ở đâu khác). */
    private class PendingLinkedInLogin(val state: String, val codeVerifier: String)

    private var pendingLinkedIn: PendingLinkedInLogin? = null
    private var linkedInLoginJob: Job? = null

    /** Cấu hình tách ra để unit test điều khiển được. Không có secret nào trong app. */
    internal var linkedInClientId: String = BuildConfig.LINKEDIN_MEMBER_CLIENT_ID.trim()
    internal var linkedInBffUrl: String = BuildConfig.LINKEDIN_BFF_URL.trim()
    internal var linkedInLoginTimeoutMillis: Long = LINKEDIN_LOGIN_TIMEOUT_MILLIS

    /** Đồng hồ để xác định token hết hạn; tách ra để unit test điều khiển được thời gian. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    init {
        loadChannels()
        viewModelScope.launch { callbackBus.callbacks.collect { onLinkedInCallback(it) } }
    }

    companion object {
        /** Thời gian chờ người dùng hoàn tất đăng nhập LinkedIn trong trình duyệt. */
        const val LINKEDIN_LOGIN_TIMEOUT_MILLIS = 5 * 60 * 1000L
    }

    private fun loadChannels() {
        viewModelScope.launch {
            repository.getConnectedChannels().collect { channels ->
                // Nạp quyền thật và trạng thái hạn token trước khi phát UiState để ma trận không hiện sai.
                val resolved = channels.map { resolveAccount(it) }
                knownChannels = resolved
                val currentConnectedName = (_uiState.value as? ChannelUiState.Success)?.connectedName
                _uiState.value = ChannelUiState.Success(channels = resolved, connectedName = currentConnectedName)
            }
        }
    }

    /**
     * Nạp quyền thật của [account] vào bộ nhớ và trả về bản có trạng thái đúng:
     * token đã quá hạn thì đổi CONNECTED thành TOKEN_EXPIRED (lưu trạng thái này ở UI, không đụng token đã lưu).
     */
    private suspend fun resolveAccount(account: ChannelAccount): ChannelAccount {
        grantedScopes = grantedScopes + (scopeKey(account) to repository.getGrantedScope(account.type, account.accountId))
        val expiresAt = repository.getTokenExpiresAt(account.type, account.accountId)
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

    fun isFacebookDemoMode(): Boolean {
        val id = BuildConfig.FACEBOOK_APP_ID
        return id.isBlank() || id.contains("SOCIAL_AGENT")
    }

    /** Demo khi chưa cấu hình client id công khai hoặc địa chỉ BFF (còn placeholder). */
    fun isLinkedInDemoMode(): Boolean =
        linkedInClientId.isBlank() || linkedInClientId.contains("SOCIAL_AGENT") ||
            linkedInBffUrl.isBlank() || linkedInBffUrl.contains("SOCIAL_AGENT")

    fun connectFacebookDemo() {
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            applyConnectResult(repository.connectFacebookPage("demo_code_fb_${clock()}", "", ""))
        }
    }

    fun connectLinkedInDemo() {
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            applyConnectResult(repository.connectLinkedIn("demo_code_li_${clock()}", "", ""))
        }
    }

    /**
     * Bắt đầu đăng nhập LinkedIn qua BFF (RFC 8252 + RFC 7636, docs/research-social-api.md Luồng B):
     * 1. sinh `state` và cặp `code_verifier`/`code_challenge` (verifier chỉ ở RAM);
     * 2. đăng ký (state, challenge) với BFF;
     * 3. phát URL đăng nhập LinkedIn (redirect_uri là BFF) cho Fragment mở bằng trình duyệt hệ thống;
     * 4. chờ deep link của BFF ([onLinkedInCallback]); quá hạn thì báo timeout.
     */
    fun startLinkedInLogin() {
        if (!LinkedInBffAuth.isValidBaseUrl(linkedInBffUrl)) {
            _notices.tryEmit(ChannelNotice.LINKEDIN_LOGIN_FAILED)
            return
        }
        linkedInLoginJob?.cancel()
        pendingLinkedIn = null
        linkedInLoginJob = viewModelScope.launch {
            val verifier = PkceUtil.generateCodeVerifier()
            val state = PkceUtil.generateState()
            val registered = repository.startLinkedInSession(state, PkceUtil.generateCodeChallengeS256(verifier))
            if (registered.isFailure) {
                _notices.tryEmit(ChannelNotice.LINKEDIN_LOGIN_FAILED)
                return@launch
            }
            pendingLinkedIn = PendingLinkedInLogin(state, verifier)
            _browserRequests.emit(LinkedInBffAuth.authorizationUrl(linkedInClientId, linkedInBffUrl, state))
            delay(linkedInLoginTimeoutMillis)
            if (pendingLinkedIn?.state == state) {
                pendingLinkedIn = null
                _notices.tryEmit(ChannelNotice.LINKEDIN_LOGIN_TIMEOUT)
            }
        }
    }

    /**
     * Xử lý deep link BFF chuyển về. Bỏ qua mọi thứ không đúng dạng hoặc khi không có phiên đang chờ,
     * nên một intent bên ngoài không thể tự kết nối kênh. Chỉ `state` khớp mới đổi `exchange_code`.
     */
    internal fun onLinkedInCallback(url: String) {
        val pending = pendingLinkedIn ?: return
        when (val result = LinkedInAuthRedirect.parse(url, LinkedInBffAuth.APP_REDIRECT_URI, pending.state)) {
            null -> Unit
            LinkedInAuthRedirect.Result.Cancelled -> finishLinkedInLogin(ChannelNotice.LINKEDIN_LOGIN_CANCELLED)
            is LinkedInAuthRedirect.Result.Failure -> {
                if (result.reason == LinkedInAuthRedirect.FailureReason.STATE_MISMATCH) {
                    // Giữ phiên đang chờ: intent giả mạo không được làm hỏng lần đăng nhập thật.
                    _uiState.value = ChannelUiState.Error(messageRes = R.string.channel_oauth_state_mismatch)
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
                        repository.connectLinkedInViaBff(result.exchangeCode, pending.state, pending.codeVerifier),
                    )
                }
            }
        }
    }

    private fun finishLinkedInLogin(notice: ChannelNotice) {
        linkedInLoginJob?.cancel()
        pendingLinkedIn = null
        _notices.tryEmit(notice)
    }

    /** Người dùng bấm Hủy trong màn Facebook Login: không thay đổi gì, chỉ báo để Fragment hỏi đổi tài khoản. */
    fun onFacebookLoginCancelled() {
        _notices.tryEmit(ChannelNotice.FACEBOOK_LOGIN_CANCELLED)
    }

    fun onFacebookLoginFailed() {
        _notices.tryEmit(ChannelNotice.FACEBOOK_LOGIN_FAILED)
    }

    /**
     * Meta SDK đã trả phiên đăng nhập (token, hạn và quyền thật): lấy các Page người dùng quản lý.
     * - 0 Page: kết nối bằng hồ sơ cá nhân và báo [ChannelNotice.NO_FACEBOOK_PAGES].
     * - 1 Page: dùng luôn Page đó.
     * - Nhiều Page: phát [pagePicker] để người dùng chọn.
     */
    fun onFacebookLoggedIn(user: FacebookUserToken) {
        viewModelScope.launch {
            _uiState.value = ChannelUiState.Loading
            val pagesResult = repository.fetchFacebookPages(user.accessToken)
            if (pagesResult.isFailure) {
                // Lỗi mạng/quyền khi tải Page KHÔNG đồng nghĩa "không có Page": không nối bằng hồ sơ cá nhân,
                // chỉ báo lỗi để người dùng thử lại.
                _notices.tryEmit(ChannelNotice.FACEBOOK_PAGES_LOAD_FAILED)
                _uiState.value = ChannelUiState.Success(channels = knownChannels)
                return@launch
            }
            val pages = pagesResult.getOrThrow()
            when {
                pages.size > 1 -> {
                    pendingFacebookUser = user
                    _uiState.value = ChannelUiState.Success(channels = knownChannels)
                    _pagePicker.value = FacebookPagePicker(pages)
                }
                pages.size == 1 -> applyConnectResult(repository.connectFacebookSelectedPage(user, pages.first()))
                else -> {
                    _notices.tryEmit(ChannelNotice.NO_FACEBOOK_PAGES)
                    applyConnectResult(repository.connectFacebookProfile(user))
                }
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
    private suspend fun applyConnectResult(result: Result<ChannelAccount>) {
        result.onSuccess { connected ->
            val account = resolveAccount(connected)
            val updatedChannels = knownChannels
                .filterNot { it.type == account.type && it.accountId == account.accountId } + account
            knownChannels = updatedChannels
            _uiState.value = ChannelUiState.Success(
                channels = updatedChannels,
                connectedName = account.displayName,
            )
        }.onFailure { error ->
            _uiState.value = ChannelUiState.Error(message = error.message)
        }
    }

    fun disconnectChannel(type: ChannelType, accountId: String) {
        viewModelScope.launch {
            repository.disconnectChannel(type, accountId)
        }
    }
}
