package com.lttbdd.socialagent.feature.channel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.lttbdd.socialagent.BuildConfig
import com.lttbdd.socialagent.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.facebook.AccessToken
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.databinding.FragmentChannelBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Fragment hiển thị và quản lý màn hình Kênh của tôi (F1).
 * Nguồn tham chiếu:
 * - RFC 8252 Section 8.1: không dùng WebView nhúng. Facebook đăng nhập bằng Meta SDK (LoginManager),
 * - LinkedIn mở trình duyệt hệ thống rồi nhận deep link từ BFF (xem ChannelViewModel.startLinkedInLogin).
 * - AGENTS.md: ViewModel expose StateFlow, Fragment collect bằng repeatOnLifecycle(STARTED), _binding nullable.
 */
@AndroidEntryPoint
class ChannelFragment : Fragment() {

    private var _binding: FragmentChannelBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChannelViewModel by viewModels()

    private var disconnectDialog: androidx.appcompat.app.AlertDialog? = null
    private var facebookPageDialog: androidx.appcompat.app.AlertDialog? = null
    private var displayedFacebookPagePicker: FacebookPagePicker? = null
    private lateinit var adapter: ChannelAdapter
    private val facebookCallbackManager: CallbackManager = CallbackManager.Factory.create()
    private val facebookLoginManager = BuildConfig.FACEBOOK_APP_ID
        .takeIf { it.isNotBlank() && !it.contains("SOCIAL_AGENT") }
        ?.let { LoginManager.getInstance() }

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
        facebookLoginManager?.registerCallback(
            facebookCallbackManager,
            object : FacebookCallback<LoginResult> {
                override fun onSuccess(result: LoginResult) {
                    viewModel.onFacebookLoggedIn(result.accessToken.toUserToken())
                }

                override fun onCancel() = viewModel.onFacebookLoginCancelled()

                override fun onError(error: FacebookException) = viewModel.onFacebookLoginFailed()
            },
        )
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

    private fun AccessToken.toUserToken() = FacebookUserToken(
        accessToken = token,
        expiresAtMillis = expires.time,
        grantedScope = permissions.filterNotNull().sorted().joinToString(",").ifEmpty { null },
    )

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
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
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
    }

    private fun setupListeners() {
        binding.btnRetryLoadChannels.setOnClickListener { viewModel.retryLoadChannels() }
        binding.btnRetryDisconnectCleanup.setOnClickListener { viewModel.retryDisconnectCleanup() }
        binding.btnConnectFacebook.setOnClickListener {
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
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
