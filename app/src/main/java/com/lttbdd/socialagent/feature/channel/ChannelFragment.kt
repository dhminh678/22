package com.lttbdd.socialagent.feature.channel

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.lttbdd.socialagent.BuildConfig
import com.lttbdd.socialagent.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginBehavior
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookRefreshIssue
import com.lttbdd.socialagent.data.model.FacebookRefreshSkip
import com.lttbdd.socialagent.databinding.FragmentChannelBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Fragment hiển thị và quản lý màn hình Kênh của tôi (F1).
 * Nguồn tham chiếu:
 * - Facebook đăng nhập bằng Meta SDK (LoginManager), ưu tiên Custom Tabs ở chế độ web.
 * - LinkedIn mở trình duyệt hệ thống rồi nhận deep link từ BFF (xem ChannelViewModel.startLinkedInLogin).
 * - AGENTS.md: ViewModel expose StateFlow, Fragment collect bằng repeatOnLifecycle(STARTED), _binding nullable.
 */
@AndroidEntryPoint
class ChannelFragment : Fragment() {

    private var _binding: FragmentChannelBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChannelViewModel by activityViewModels()

    private var disconnectDialog: androidx.appcompat.app.AlertDialog? = null
    private var facebookPageDialog: androidx.appcompat.app.AlertDialog? = null
    private var displayedFacebookPagePicker: FacebookPagePicker? = null
    private var facebookReconnectAccountId: String? = null
    private var cancelledFacebookReconnectAccountId: String? = null
    private lateinit var adapter: ChannelAdapter
    private val facebookCallbackManager: CallbackManager = CallbackManager.Factory.create()
    private val facebookLoginManager = BuildConfig.FACEBOOK_APP_ID
        .takeIf { it.isNotBlank() && !it.contains("SOCIAL_AGENT") }
        ?.let { LoginManager.getInstance().setLoginBehavior(LoginBehavior.WEB_ONLY) }

    // Cấu hình App ID cố định trong APK. Khi có cấu hình, đăng ký ở mỗi lần tạo Fragment
    // để nhận lại kết quả đăng nhập qua CallbackManager sau khi Activity được tạo lại.
    private val facebookLoginContract = facebookLoginManager?.createLogInActivityResultContract(facebookCallbackManager)
    private val facebookLoginLauncher = facebookLoginContract?.let { contract ->
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.dispatchFacebookSdkResult { contract.parseResult(result.resultCode, result.data) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        facebookReconnectAccountId = savedInstanceState?.getString(FACEBOOK_RECONNECT_ACCOUNT)
        cancelledFacebookReconnectAccountId = savedInstanceState?.getString(CANCELLED_FACEBOOK_RECONNECT_ACCOUNT)
        facebookLoginManager?.registerCallback(
            facebookCallbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    invalidateFacebookSdkSession()
                    val expectedAccountId = facebookReconnectAccountId
                    facebookReconnectAccountId = null
                    viewModel.onFacebookLoggedIn(result.accessToken.toUserToken(), expectedAccountId)
                }

                override fun onCancel() {
                    cancelledFacebookReconnectAccountId = facebookReconnectAccountId
                    facebookReconnectAccountId = null
                    viewModel.onFacebookLoginCancelled()
                }

                override fun onError(error: FacebookException) {
                    facebookReconnectAccountId = null
                    viewModel.onFacebookLoginFailed()
                }
            },
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(FACEBOOK_RECONNECT_ACCOUNT, facebookReconnectAccountId)
        outState.putString(CANCELLED_FACEBOOK_RECONNECT_ACCOUNT, cancelledFacebookReconnectAccountId)
    }

    override fun onDestroy() {
        // Gỡ callback khỏi singleton LoginManager để Fragment cũ không bị giữ lại.
        facebookLoginManager?.unregisterCallback(facebookCallbackManager)
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentChannelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeUiState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onChannelScreenResumed()
    }

    private fun launchFacebookLogin(useAnotherAccount: Boolean = false) {
        val contract = facebookLoginContract ?: return
        val launcher = facebookLoginLauncher ?: return
        if (!viewModel.beginFacebookSdkLogin()) return
        try {
            // Đăng xuất phiên SDK trước khi yêu cầu đổi tài khoản.
            if (useAnotherAccount) facebookLoginManager?.logOut()
            launcher.launch(contract.createIntent(requireContext(), FACEBOOK_PERMISSIONS))
        } catch (_: ActivityNotFoundException) {
            viewModel.onFacebookLoginFailed()
        } catch (_: FacebookException) {
            viewModel.onFacebookLoginFailed()
        }
    private fun loginWithAnotherFacebookAccount() {
        // Đăng xuất phiên SDK để màn Facebook Login không tự dùng lại tài khoản cũ.
        facebookReconnectAccountId = cancelledFacebookReconnectAccountId
        cancelledFacebookReconnectAccountId = null
        facebookLoginManager?.logOut()
        invalidateFacebookSdkSession()
        facebookLoginLauncher?.launch(FACEBOOK_PERMISSIONS)
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            viewModel.onLinkedInBrowserUnavailable()
            Toast.makeText(requireContext(), R.string.channel_linkedin_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private fun showFacebookCancelledDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.channel_facebook_cancel_title)
            .setMessage(R.string.channel_facebook_cancel_message)
            .setPositiveButton(R.string.channel_facebook_login_another) { _, _ ->
                launchFacebookLogin(useAnotherAccount = true)
            }
            .setNegativeButton(R.string.channel_close, null)
            .setOnDismissListener { cancelledFacebookReconnectAccountId = null }
            .show()
    }

    private fun setupRecyclerView() {
        adapter = ChannelAdapter(
            getScopeForAccount = { account ->
                viewModel.getScopeForAccount(account)
            },
            getTasksForAccount = { account -> viewModel.getTasksForAccount(account) },
            isFacebookProfile = viewModel::isFacebookProfile,
            onDisconnect = ::confirmDisconnect,
            reconnect = ::reconnectAccount,
            needsFacebookReconnect = viewModel::needsFacebookReconnect,
            facebookPageAccessIssue = viewModel::facebookPageAccessIssue,
            isFacebookPageUnlisted = viewModel::isFacebookPageUnlisted,
            canRetryFacebookPage = viewModel::canRetryFacebookPage,
            retryFacebookPage = ::retryFacebookPage,
        )
        binding.rvChannels.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChannels.adapter = adapter
    }

    private fun confirmDisconnect(account: ChannelAccount) {
        if (!viewModel.canDisconnectChannel(account) || disconnectDialog?.isShowing == true) return
        val message = if (viewModel.disconnectDeletesSharedData(account)) {
            R.string.channel_disconnect_last_message
        } else {
            R.string.channel_disconnect_message
        }
        disconnectDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.channel_disconnect_title, account.displayName))
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.channel_disconnect) { _, _ -> viewModel.disconnectChannel(account) }
            .show()
    private fun retryFacebookPage(account: ChannelAccount) {
        if (!viewModel.canRetryFacebookPage(account)) return
        val current = currentFacebookSdkToken()
        if (current == null) {
            Toast.makeText(requireContext(), R.string.channel_retry_page_sign_in, Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val check = viewModel.retryFacebookPage(
                account.accountId, current.user, current.version, ::facebookSdkSessionVersion,
            )
            val message = when (check.outcome.skipped) {
                FacebookRefreshSkip.COOLDOWN -> R.string.channel_retry_page_wait
                FacebookRefreshSkip.NO_ELIGIBLE_CHANNELS,
                FacebookRefreshSkip.NO_SDK_SESSION,
                FacebookRefreshSkip.SDK_SESSION_CHANGED,
                FacebookRefreshSkip.WAITING_FOR_SESSION -> R.string.channel_retry_page_sign_in
                else -> null
            }
            message?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun reconnectAccount(account: ChannelAccount) {
        when (account.type) {
            ChannelType.FACEBOOK -> {
                if (!binding.btnConnectFacebook.isEnabled) return
                val launcher = facebookLoginLauncher ?: return toastReconnectUnavailable()
                facebookReconnectAccountId = account.accountId
                launcher.launch(FACEBOOK_PERMISSIONS)
            }
            ChannelType.LINKEDIN -> {
                if (!binding.btnConnectLinkedIn.isEnabled) return
                if (viewModel.isLinkedInDemoMode()) return toastReconnectUnavailable()
                viewModel.startLinkedInLogin(account.accountId)
            }
        }
    }

    private fun toastReconnectUnavailable() {
        Toast.makeText(requireContext(), R.string.channel_error_reconnect_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun setupListeners() {
        binding.btnRetryLoadChannels.setOnClickListener { viewModel.retryLoadChannels() }
        binding.btnRetryDisconnectCleanup.setOnClickListener { viewModel.retryDisconnectCleanup() }
        binding.btnConnectFacebook.setOnClickListener {
            facebookReconnectAccountId = null
            if (facebookLoginLauncher == null) {
                showDemoModeNotice("FACEBOOK_APP_ID")
                viewModel.connectFacebookDemo(getString(R.string.channel_demo_facebook_name))
            } else {
                launchFacebookLogin()
            }
        }

        binding.btnConnectLinkedIn.setOnClickListener {
            if (viewModel.isLinkedInDemoMode()) {
                // Chưa cấu hình LINKEDIN_MEMBER_CLIENT_ID / LINKEDIN_BFF_URL -> không có đăng nhập thật, báo rõ cho người dùng.
                showDemoModeNotice("LINKEDIN_MEMBER_CLIENT_ID, LINKEDIN_BFF_URL")
                viewModel.connectLinkedInDemo(getString(R.string.channel_demo_linkedin_name))
            } else {
                viewModel.startLinkedInLogin()
            }
        }
    }

    private fun observeUiState() {
        if (facebookLoginManager != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    viewModel.monitorFacebookPageAccess()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { monitorTokenExpiry() }
                launch { viewModel.facebookRefreshIssues.collect(::renderFacebookRefreshIssues) }
                launch {
                    viewModel.pagePicker.collect(::renderFacebookPagePicker)
                }
                launch {
                    viewModel.connectedEvents.collect { name ->
                        Toast.makeText(requireContext(), getString(R.string.channel_connected_named, name), Toast.LENGTH_SHORT).show()
                    }
                }
                launch {
                    viewModel.errors.collect { res -> Toast.makeText(requireContext(), res, Toast.LENGTH_LONG).show() }
                }
                launch {
                    viewModel.browserRequests.collect { url -> openInBrowser(url) }
                }
                launch {
                    viewModel.notices.collect { notice ->
                        when (notice) {
                            ChannelNotice.FACEBOOK_LOGIN_CANCELLED -> showFacebookCancelledDialog()
                            else -> Toast.makeText(requireContext(), noticeMessage(notice), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                combine(viewModel.uiState, viewModel.hasPendingDisconnectCleanup) { state, pendingCleanup ->
                    state to pendingCleanup
                }.collect { (state, pendingCleanup) -> render(state, pendingCleanup) }
            }
        }
    }

    private fun render(state: ChannelUiState, pendingCleanup: Boolean) {
    private suspend fun monitorTokenExpiry() {
        val context = requireContext()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_TIME_CHANGED) viewModel.recheckTokenExpiry()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_TIME_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            viewModel.monitorTokenExpiry()
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun renderFacebookRefreshIssues(issues: Set<FacebookRefreshIssue>) {
        val message = when {
            FacebookRefreshIssue.USER_SESSION_EXPIRED in issues -> R.string.channel_refresh_user_session_expired
            FacebookRefreshIssue.STORAGE in issues -> R.string.channel_refresh_storage_error
            FacebookRefreshIssue.NETWORK in issues -> R.string.channel_refresh_network_error
            FacebookRefreshIssue.SDK_UNAVAILABLE in issues ||
                FacebookRefreshIssue.PAGES_UNAVAILABLE in issues ||
                FacebookRefreshIssue.TIMEOUT in issues ||
                FacebookRefreshIssue.RATE_LIMITED in issues ||
                FacebookRefreshIssue.PROVIDER_UNAVAILABLE in issues -> R.string.channel_refresh_unavailable
            FacebookRefreshIssue.PAGE_NOT_RETURNED in issues -> R.string.channel_refresh_page_access
            else -> null
        }
        binding.tvFacebookRefreshStatus.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let { binding.tvFacebookRefreshStatus.setText(it) }
    }

    private fun render(state: ChannelUiState) {
        val loadFailed = state == ChannelUiState.LoadFailed
        val loading = state is ChannelUiState.Loading
        val canRetryCleanup = !loading && pendingCleanup
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnRetryLoadChannels.visibility = if (loadFailed) View.VISIBLE else View.GONE
        binding.btnRetryDisconnectCleanup.visibility = if (canRetryCleanup) View.VISIBLE else View.GONE
        val canConnect = state is ChannelUiState.Success && !pendingCleanup
        binding.btnConnectFacebook.isEnabled = canConnect
        binding.btnConnectLinkedIn.isEnabled = canConnect
        binding.tvEmptyState.setText(if (loadFailed) R.string.channel_error_load_failed else R.string.channel_empty_desc)
        binding.tvEmptyState.visibility = if (loadFailed || (state is ChannelUiState.Success && state.channels.isEmpty())) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val channels = when (state) {
            is ChannelUiState.Loading -> state.channels
            is ChannelUiState.Success -> state.channels
            ChannelUiState.LoadFailed -> emptyList()
        }
        // Quyền và Page task có thể đổi khi bản ghi tài khoản vẫn giống nhau.
        adapter.submitList(channels) {
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }
    }

    /** Đóng hộp thoại khi lượt đăng nhập kết thúc hoặc được thay thế. */
    private fun renderFacebookPagePicker(picker: FacebookPagePicker?) {
        if (picker === displayedFacebookPagePicker && facebookPageDialog?.isShowing == true) return
        facebookPageDialog?.dismiss()
        facebookPageDialog = null
        displayedFacebookPagePicker = picker
        if (picker == null) return

        facebookPageDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.channel_facebook_pick_page_title)
            .setItems(picker.pages.map { it.name }.toTypedArray()) { _, which ->
                viewModel.selectFacebookPage(picker, which)
            }
            .setNegativeButton(R.string.channel_close) { _, _ -> viewModel.cancelFacebookPagePicker(picker) }
            .setOnCancelListener { viewModel.cancelFacebookPagePicker(picker) }
            .show()
    }

    private fun noticeMessage(notice: ChannelNotice): Int = when (notice) {
        ChannelNotice.CHANNEL_DISCONNECTED -> R.string.channel_disconnected_notice
        ChannelNotice.NO_FACEBOOK_PAGES -> R.string.channel_facebook_no_pages
        ChannelNotice.FACEBOOK_PAGES_LOAD_FAILED -> R.string.channel_facebook_pages_load_failed
        ChannelNotice.FACEBOOK_LOGIN_CANCELLED, ChannelNotice.FACEBOOK_LOGIN_FAILED ->
            R.string.channel_facebook_login_error
        ChannelNotice.LINKEDIN_LOGIN_CANCELLED -> R.string.channel_linkedin_cancelled
        ChannelNotice.LINKEDIN_LOGIN_FAILED -> R.string.channel_linkedin_login_failed
        ChannelNotice.LINKEDIN_LOGIN_TIMEOUT -> R.string.channel_linkedin_login_timeout
        ChannelNotice.LINKEDIN_SESSION_EXPIRED -> R.string.channel_linkedin_session_expired
    }

    /** Bản debug: báo đang ở chế độ demo (kênh giả chỉ trong RAM). Bản khác: chỉ báo chưa cấu hình, không kết nối gì. */
    private fun showDemoModeNotice(configKey: String) {
        val message = if (viewModel.isDemoEnabled()) R.string.channel_demo_mode_notice else R.string.channel_not_configured_notice
        Toast.makeText(requireContext(), getString(message, configKey), Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // dismiss() không gọi onCancel nên lựa chọn Page vẫn được giữ trong ViewModel.
        disconnectDialog?.dismiss()
        disconnectDialog = null
        renderFacebookPagePicker(null)
        _binding = null
    }

    private companion object {
        const val CANCELLED_FACEBOOK_RECONNECT_ACCOUNT = "cancelled_facebook_reconnect_account"
        const val FACEBOOK_RECONNECT_ACCOUNT = "facebook_reconnect_account"
        /** Quyền xin khi đăng nhập Facebook; quyền thật được cấp nằm trong AccessToken.permissions. */
        val FACEBOOK_PERMISSIONS = listOf(
            "public_profile",
            "pages_show_list",
            "pages_read_engagement",
            "pages_read_user_content",
            "pages_manage_posts",
            "pages_manage_engagement",
            "read_insights",
        )
    }
}
