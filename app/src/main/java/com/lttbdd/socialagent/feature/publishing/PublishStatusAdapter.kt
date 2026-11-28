package com.lttbdd.socialagent.feature.publishing

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.databinding.ItemPublishChannelBinding
import com.lttbdd.socialagent.databinding.ItemPublishTopicBinding

/** Mỗi thẻ là một topic; bên trong mỗi kênh một dòng trạng thái và thao tác riêng. */
class PublishStatusAdapter(
    private val onPublishAll: (topicId: String) -> Unit,
    private val onPublishChannel: (draftId: String) -> Unit,
    private val onConfirmPublished: (postId: String) -> Unit,
    private val onRequeue: (postId: String) -> Unit,
    private val onOpenPost: (url: String) -> Unit,
) : ListAdapter<TopicPublishItem, PublishStatusAdapter.TopicViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopicViewHolder =
        TopicViewHolder(ItemPublishTopicBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: TopicViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TopicViewHolder(
        private val binding: ItemPublishTopicBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TopicPublishItem) {
            binding.tvTopicPreview.text = item.previewText
            binding.btnPublishAll.isEnabled = item.canPublishAll
            binding.btnPublishAll.setOnClickListener { onPublishAll(item.topicId) }

            // Số kênh mỗi topic rất ít (tối đa số ChannelType), nên dựng lại dòng kênh mỗi lần bind là đủ nhanh.
            val inflater = LayoutInflater.from(binding.root.context)
            binding.channelContainer.removeAllViews()
            item.channels.forEach { channel ->
                val row = ItemPublishChannelBinding.inflate(inflater, binding.channelContainer, true)
                bindChannel(row, channel)
            }
        }

        private fun bindChannel(row: ItemPublishChannelBinding, item: ChannelPublishItem) {
            val state = item.state
            val context = row.root.context
            row.tvChannelName.setText(
                when (state.channel) {
                    ChannelType.FACEBOOK -> R.string.compose_channel_facebook
                    ChannelType.LINKEDIN -> R.string.compose_channel_linkedin
                },
            )

            when (item.action) {
                ChannelAction.NEEDS_APPROVAL -> row.statusChannel.setPendingApproval()
                ChannelAction.CONFIRM_OUTCOME -> {
                    row.statusChannel.setText(R.string.publish_status_needs_check)
                    row.statusChannel.setTextColor(ContextCompat.getColor(context, R.color.brand_warning))
                    row.statusChannel.contentDescription = row.statusChannel.text
                }
                else -> row.statusChannel.setStatus(state.status)
            }

            val reason = item.reasonRes?.let(context::getString)
            val hint = when {
                item.action == ChannelAction.NEEDS_APPROVAL ->
                    context.getString(R.string.publish_status_needs_approval_hint)
                item.action == ChannelAction.CONFIRM_OUTCOME ->
                    context.getString(R.string.publish_status_needs_confirmation_hint)
                reason != null && item.isRetrying -> context.getString(R.string.publish_status_retrying, reason)
                else -> reason
            }
            row.tvChannelHint.isVisible = hint != null
            row.tvChannelHint.text = hint

            row.btnPublishChannel.isVisible = item.action == ChannelAction.PUBLISH
            row.btnPublishChannel.setText(
                if (state.postId == null) R.string.review_action_publish else R.string.publish_status_retry,
            )
            row.btnPublishChannel.setOnClickListener { onPublishChannel(state.draftId) }

            val openUrl = item.openUrl
            row.btnOpenPost.isVisible = openUrl != null
            row.btnOpenPost.setOnClickListener { openUrl?.let(onOpenPost) }

            val postId = state.postId
            row.confirmActions.isVisible = item.action == ChannelAction.CONFIRM_OUTCOME && postId != null
            if (postId != null) {
                row.btnConfirmPublished.setOnClickListener { onConfirmPublished(postId) }
                row.btnRequeue.setOnClickListener { onRequeue(postId) }
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<TopicPublishItem>() {
        override fun areItemsTheSame(oldItem: TopicPublishItem, newItem: TopicPublishItem) =
            oldItem.topicId == newItem.topicId

        override fun areContentsTheSame(oldItem: TopicPublishItem, newItem: TopicPublishItem) = oldItem == newItem
    }
}
