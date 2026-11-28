package com.lttbdd.socialagent.feature.publishing

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.ui.components.AppDialog
import com.lttbdd.socialagent.core.ui.components.AppSnackbar
import com.lttbdd.socialagent.databinding.DialogPublishPermalinkBinding
import com.lttbdd.socialagent.databinding.FragmentPublishStatusBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** Màn Trạng thái đăng (F5, #36), mở từ tab Bài viết. */
@AndroidEntryPoint
class PublishStatusFragment : Fragment() {

    private var _binding: FragmentPublishStatusBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PublishStatusViewModel by viewModels()
    private lateinit var adapter: PublishStatusAdapter
    private var confirmPublishedDialog: AlertDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPublishStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // android:accessibilityHeading trong XML cần API 28, nên đặt qua ViewCompat.
        ViewCompat.setAccessibilityHeading(binding.tvHeaderTitle, true)
        adapter = PublishStatusAdapter(
            onPublishAll = viewModel::onPublishAll,
            onPublishChannel = viewModel::onPublishChannel,
            onConfirmPublished = ::showConfirmPublished,
            onRequeue = viewModel::onRequeueRequested,
            onOpenPost = ::openPost,
        )
        binding.rvTopics.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTopics.adapter = adapter
        binding.emptyState.setContent(
            title = getString(R.string.publish_status_empty_title),
            message = getString(R.string.publish_status_empty_message),
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    private fun showConfirmPublished(postId: String, channel: ChannelType) {
        val input = DialogPublishPermalinkBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.publish_status_permalink_title)
            .setMessage(R.string.publish_status_permalink_message)
            .setView(input.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.publish_status_confirm_published, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val permalink = input.permalink.text?.toString().orEmpty()
                if (viewModel.onConfirmPublished(postId, channel, permalink)) {
                    dialog.dismiss()
                } else {
                    input.permalinkLayout.error = getString(R.string.publish_status_permalink_invalid)
                }
            }
        }
        dialog.setOnDismissListener { confirmPublishedDialog = null }
        confirmPublishedDialog = dialog
        dialog.show()
    }

    private fun render(state: PublishStatusUiState) {
        binding.loadingIndicator.isVisible = state.isLoading
        binding.emptyState.isVisible = !state.isLoading && state.topics.isEmpty()
        binding.rvTopics.isVisible = !state.isLoading && state.topics.isNotEmpty()
        adapter.submitList(state.topics)
    }

    private fun handleEvent(event: PublishStatusEvent) {
        when (event) {
            is PublishStatusEvent.ShowMessage -> AppSnackbar.show(binding.root, event.messageRes)
            is PublishStatusEvent.ConfirmRequeue -> AppDialog.show(
                context = requireContext(),
                titleRes = R.string.publish_status_requeue_dialog_title,
                messageRes = R.string.publish_status_requeue_dialog_message,
                positiveActionRes = R.string.publish_status_requeue_action,
                negativeActionRes = R.string.action_cancel,
                onPositiveAction = { viewModel.onRequeueConfirmed(event.postId) },
            )
        }
    }

    /** Mở bài gốc bằng trình duyệt hoặc app mạng xã hội (#40). URL đã được ViewModel kiểm tra là http/https. */
    private fun openPost(url: String) {
        if (!PostLinkOpener.open(requireContext(), url)) {
            AppSnackbar.show(binding.root, R.string.publish_status_open_failed)
        }
    }

    override fun onDestroyView() {
        confirmPublishedDialog?.dismiss()
        super.onDestroyView()
        _binding = null
    }
}
