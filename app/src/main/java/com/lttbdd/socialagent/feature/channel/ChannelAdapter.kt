package com.lttbdd.socialagent.feature.channel

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.FacebookPageAccessIssue
import com.lttbdd.socialagent.databinding.ItemCapabilityRowBinding
import com.lttbdd.socialagent.databinding.ItemChannelAccountBinding

class ChannelAdapter(
    private val getScopeForAccount: (ChannelAccount) -> String?,
    private val getTasksForAccount: (ChannelAccount) -> List<String>? = { null },
    private val isFacebookProfile: (ChannelAccount) -> Boolean? = { null },
    private val onDisconnect: (ChannelAccount) -> Unit,
    private val reconnect: (ChannelAccount) -> Unit,
    private val needsFacebookReconnect: (ChannelAccount) -> Boolean,
    private val facebookPageAccessIssue: (ChannelAccount) -> FacebookPageAccessIssue?,
    private val isFacebookPageUnlisted: (ChannelAccount) -> Boolean,
    private val canRetryFacebookPage: (ChannelAccount) -> Boolean,
    private val retryFacebookPage: (ChannelAccount) -> Unit,
) : ListAdapter<ChannelAccount, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelAccountBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return ChannelViewHolder(binding, getScopeForAccount, getTasksForAccount, isFacebookProfile, onDisconnect)
        return ChannelViewHolder(
            binding, getScopeForAccount, getTasksForAccount, isFacebookProfile, reconnect,
            needsFacebookReconnect, facebookPageAccessIssue, isFacebookPageUnlisted, canRetryFacebookPage, retryFacebookPage,
        )
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ChannelViewHolder(
        private val binding: ItemChannelAccountBinding,
        private val getScopeForAccount: (ChannelAccount) -> String?,
        private val getTasksForAccount: (ChannelAccount) -> List<String>?,
        private val isFacebookProfile: (ChannelAccount) -> Boolean?,
        private val onDisconnect: (ChannelAccount) -> Unit,
        private val reconnect: (ChannelAccount) -> Unit,
        private val needsFacebookReconnect: (ChannelAccount) -> Boolean,
        private val facebookPageAccessIssue: (ChannelAccount) -> FacebookPageAccessIssue?,
        private val isFacebookPageUnlisted: (ChannelAccount) -> Boolean,
        private val canRetryFacebookPage: (ChannelAccount) -> Boolean,
        private val retryFacebookPage: (ChannelAccount) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(account: ChannelAccount) {
            val context = binding.root.context
            binding.tvChannelType.text = context.getString(
                when (account.type) {
                    ChannelType.FACEBOOK -> R.string.channel_type_facebook
                    ChannelType.LINKEDIN -> R.string.channel_type_linkedin
                },
            )
            binding.tvDisplayName.text = account.displayName
            binding.btnDisconnect.setOnClickListener { onDisconnect(account) }

            val needsReconnect = needsFacebookReconnect(account)
            val pageAccessIssue = facebookPageAccessIssue(account)
            val pagePermissionRequired = pageAccessIssue == FacebookPageAccessIssue.PERMISSION_REQUIRED
            val pageUnlisted = isFacebookPageUnlisted(account)
            val showReconnect = needsReconnect || pageUnlisted || account.status == ConnectionStatus.TOKEN_EXPIRED
            val statusRes = when {
                needsReconnect -> R.string.channel_status_reconnect_required
                pagePermissionRequired -> R.string.channel_status_page_permission_required
                pageUnlisted -> R.string.channel_status_page_unlisted
                account.status == ConnectionStatus.TOKEN_EXPIRED -> R.string.channel_status_expired
                account.status == ConnectionStatus.CONNECTED -> R.string.status_connected
                else -> R.string.status_disconnected
            }
            binding.tvStatus.setText(statusRes)
            binding.btnReconnect.isVisible = showReconnect
            binding.btnReconnect.setOnClickListener { reconnect(account) }
            binding.btnRetryFacebookPage.isVisible = canRetryFacebookPage(account)
            binding.btnRetryFacebookPage.setOnClickListener { retryFacebookPage(account) }
            binding.tvStatus.setTextColor(
                ContextCompat.getColor(
                    context,
                    when {
                        showReconnect || pagePermissionRequired -> R.color.channel_status_expired
                        account.status == ConnectionStatus.CONNECTED -> R.color.channel_status_connected
                        else -> R.color.channel_status_disconnected
                    },
                ),
            )

            // Render 5 capabilities matrix
            binding.containerCapabilities.removeAllViews()
            val scopeString = getScopeForAccount(account)
            val capabilityItems = ChannelCapabilityEvaluator.evaluateCapabilities(
                account = account,
                scopeString = scopeString,
                pageTasks = getTasksForAccount(account),
                isFacebookProfile = isFacebookProfile(account),
                pageAccessIssue = pageAccessIssue,
            )

            val inflater = LayoutInflater.from(context)
            capabilityItems.forEach { item ->
                val rowBinding = ItemCapabilityRowBinding.inflate(inflater, binding.containerCapabilities, false)
                rowBinding.tvCapName.text = context.getString(item.capabilityNameRes)
                rowBinding.tvCapStatus.text = context.getString(item.statusTextRes)
                rowBinding.tvCapCaption.text = context.getString(item.captionRes)

                val (chipBgRes, chipTextRes) = when (item.statusType) {
                    CapabilityStatusType.SUPPORTED -> R.color.cap_supported_bg to R.color.cap_supported_text
                    CapabilityStatusType.REQUIRES_PERMISSION,
                    CapabilityStatusType.REQUIRES_RECONNECT -> R.color.cap_requires_bg to R.color.cap_requires_text
                    CapabilityStatusType.NOT_SUPPORTED -> R.color.cap_not_supported_bg to R.color.cap_not_supported_text
                    CapabilityStatusType.UNVERIFIED -> R.color.cap_unverified_bg to R.color.cap_unverified_text
                    CapabilityStatusType.HANDED_OFF -> R.color.cap_handed_off_bg to R.color.cap_handed_off_text
                }
                rowBinding.tvCapStatus.setBackgroundColor(ContextCompat.getColor(context, chipBgRes))
                rowBinding.tvCapStatus.setTextColor(ContextCompat.getColor(context, chipTextRes))

                binding.containerCapabilities.addView(rowBinding.root)
            }
        }
    }

    private class ChannelDiffCallback : DiffUtil.ItemCallback<ChannelAccount>() {
        override fun areItemsTheSame(oldItem: ChannelAccount, newItem: ChannelAccount): Boolean {
            return oldItem.type == newItem.type && oldItem.accountId == newItem.accountId
        }

        override fun areContentsTheSame(oldItem: ChannelAccount, newItem: ChannelAccount): Boolean {
            return oldItem == newItem
        }
    }
}
