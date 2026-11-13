package com.lttbdd.socialagent.feature.channel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.lttbdd.socialagent.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.facebook.AccessToken
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import com.lttbdd.socialagent.databinding.FragmentChannelBinding
import dagger.hilt.android.AndroidEntryPoint
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

    private var facebookPageDialog: androidx.appcompat.app.AlertDialog? = null
    private lateinit var adapter: ChannelAdapter
    private var facebookCallbackManager: CallbackManager? = null

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

    /**
     * Đăng nhập Facebook bằng Meta SDK (LoginManager). Hạn và quyền lấy từ AccessToken thật của SDK.
     * Callback đăng ký theo vòng đời view và được gỡ ở onDestroyView để Fragment cũ không bị singleton giữ lại.
     */
    private fun loginWithFacebook() {
        val manager = LoginManager.getInstance()
        val callbackManager = facebookCallbackManager ?: CallbackManager.Factory.create().also {
            facebookCallbackManager = it
            manager.registerCallback(
                it,
                object : FacebookCallback<LoginResult> {
                    override fun onSuccess(result: LoginResult) {
                        viewModel.onFacebookLoggedIn(result.accessToken.toUserToken())
                    }

                    override fun onCancel() = viewModel.onFacebookLoginCancelled()

                    override fun onError(error: FacebookException) = viewModel.onFacebookLoginFailed()
                },
            )
        }
        manager.logIn(requireActivity() as ActivityResultRegistryOwner, callbackManager, FACEBOOK_PERMISSIONS)
    }

    private fun AccessToken.toUserToken() = FacebookUserToken(
        accessToken = token,
        expiresAtMillis = expires.time,
        grantedScope = permissions.filterNotNull().sorted().joinToString(",").ifEmpty { null },
    )

    private fun loginWithAnotherFacebookAccount() {
        // Đăng xuất phiên SDK để màn Facebook Login không tự dùng lại tài khoản cũ.
        LoginManager.getInstance().logOut()
        loginWithFacebook()
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
                loginWithAnotherFacebookAccount()
            }
            .setNegativeButton(R.string.channel_close, null)
            .show()
    }

    private fun setupRecyclerView() {
        adapter = ChannelAdapter(
            getScopeForAccount = { account ->
                viewModel.getScopeForAccount(account)
            },
        )
        binding.rvChannels.layoutManager = LinearLayoutManager(requireContext())
        binding.rvChannels.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnConnectFacebook.setOnClickListener {
            if (viewModel.isFacebookDemoMode()) {
                showDemoModeNotice("FACEBOOK_APP_ID")
                viewModel.connectFacebookDemo()
            } else {
                loginWithFacebook()
            }
        }

        binding.btnConnectLinkedIn.setOnClickListener {
            if (viewModel.isLinkedInDemoMode()) {
                // Chưa cấu hình LINKEDIN_MEMBER_CLIENT_ID / LINKEDIN_BFF_URL -> không có đăng nhập thật, báo rõ cho người dùng.
                showDemoModeNotice("LINKEDIN_MEMBER_CLIENT_ID, LINKEDIN_BFF_URL")
                viewModel.connectLinkedInDemo()
            } else {
                viewModel.startLinkedInLogin()
            }
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.pagePicker.collect { picker ->
                        picker?.let { showFacebookPagePicker(it.pages) }
                    }
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
                viewModel.uiState.collect { state ->
                    when (state) {
                        is ChannelUiState.Loading -> {
                            binding.progressBar.visibility = View.VISIBLE
                            binding.tvEmptyState.visibility = View.GONE
                        }
                        is ChannelUiState.Success -> {
                            binding.progressBar.visibility = View.GONE
                            // DiffUtil chỉ so sánh tài khoản; quyền/ma trận lấy từ ViewModel nên có thể đổi
                            // khi tài khoản không đổi (cấp lại quyền) -> buộc vẽ lại sau khi danh sách được áp dụng.
                            adapter.submitList(state.channels) {
                                adapter.notifyItemRangeChanged(0, adapter.itemCount)
                            }
                            if (state.channels.isEmpty()) {
                                binding.tvEmptyState.visibility = View.VISIBLE
                            } else {
                                binding.tvEmptyState.visibility = View.GONE
                            }
                            state.connectedName?.let { name ->
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.channel_connected_named, name),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                        is ChannelUiState.Error -> {
                            binding.progressBar.visibility = View.GONE
                            Toast.makeText(
                                requireContext(),
                                state.message ?: getString(state.messageRes),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    /** Hộp thoại chọn Page; không mở trùng khi màn hình quay lại STARTED. */
    private fun showFacebookPagePicker(pages: List<FacebookPage>) {
        if (facebookPageDialog?.isShowing == true) return
        facebookPageDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.channel_facebook_pick_page_title)
            .setItems(pages.map { it.name }.toTypedArray()) { _, which ->
                viewModel.selectFacebookPage(pages[which])
            }
            .setNegativeButton(R.string.channel_close) { _, _ -> viewModel.cancelFacebookPagePicker() }
            .setOnCancelListener { viewModel.cancelFacebookPagePicker() }
            .show()
    }

    private fun noticeMessage(notice: ChannelNotice): Int = when (notice) {
        ChannelNotice.NO_FACEBOOK_PAGES -> R.string.channel_facebook_no_pages
        ChannelNotice.FACEBOOK_PAGES_LOAD_FAILED -> R.string.channel_facebook_pages_load_failed
        ChannelNotice.FACEBOOK_LOGIN_CANCELLED, ChannelNotice.FACEBOOK_LOGIN_FAILED ->
            R.string.channel_facebook_login_error
        ChannelNotice.LINKEDIN_LOGIN_CANCELLED -> R.string.channel_linkedin_cancelled
        ChannelNotice.LINKEDIN_LOGIN_FAILED -> R.string.channel_linkedin_login_failed
        ChannelNotice.LINKEDIN_LOGIN_TIMEOUT -> R.string.channel_linkedin_login_timeout
    }

    private fun showDemoModeNotice(configKey: String) {
        Toast.makeText(
            requireContext(),
            getString(R.string.channel_demo_mode_notice, configKey),
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // dismiss() không gọi onCancel nên lựa chọn Page vẫn được giữ trong ViewModel.
        facebookPageDialog?.dismiss()
        facebookPageDialog = null
        facebookCallbackManager?.let { LoginManager.getInstance().unregisterCallback(it) }
        facebookCallbackManager = null
        _binding = null
    }

    private companion object {
        /** Quyền xin khi đăng nhập Facebook; quyền thật được cấp nằm trong AccessToken.permissions. */
        val FACEBOOK_PERMISSIONS = listOf(
            "public_profile",
            "pages_show_list",
            "pages_read_engagement",
            "pages_manage_posts",
            "pages_manage_engagement",
            "read_insights",
        )
    }
}