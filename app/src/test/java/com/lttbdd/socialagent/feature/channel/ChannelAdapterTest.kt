package com.lttbdd.socialagent.feature.channel

import android.app.Application
import android.view.ContextThemeWrapper
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.FacebookPageAccessIssue
import com.lttbdd.socialagent.databinding.ItemCapabilityRowBinding
import com.lttbdd.socialagent.databinding.ItemChannelAccountBinding
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChannelAdapterTest {
    @Test
    fun capabilityRowsReflectPageAccessIssueEvenWithFullStoredPermissions() {
        val context = ContextThemeWrapper(
            ApplicationProvider.getApplicationContext<Application>(),
            R.style.Theme_SocialAgent,
        )
        val account = ChannelAccount(ChannelType.FACEBOOK, "page", "Page", ConnectionStatus.CONNECTED, emptySet())
        var issue: FacebookPageAccessIssue? = null
        val adapter = ChannelAdapter(
            getScopeForAccount = { "pages_manage_posts,pages_read_engagement,pages_manage_engagement,read_insights" },
            getTasksForAccount = { listOf("CREATE_CONTENT", "MODERATE", "ANALYZE") },
            isFacebookProfile = { false },
            onDisconnect = {},
            reconnect = {},
            needsFacebookReconnect = { issue == FacebookPageAccessIssue.RECONNECT_REQUIRED },
            facebookPageAccessIssue = { issue },
            isFacebookPageUnlisted = { false },
            canRetryFacebookPage = { false },
            retryFacebookPage = {},
        )
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        val binding = ItemChannelAccountBinding.bind(holder.itemView)
        val expected = mapOf(
            null to R.string.cap_status_supported,
            FacebookPageAccessIssue.RECONNECT_REQUIRED to R.string.cap_status_requires_reconnect,
            FacebookPageAccessIssue.PERMISSION_REQUIRED to R.string.cap_status_requires_perm,
        )
        for ((value, status) in expected) {
            issue = value
            holder.bind(account)

            val capabilities = binding.containerCapabilities
            assertEquals(5, capabilities.childCount)
            for (index in 0 until capabilities.childCount) {
                val row = ItemCapabilityRowBinding.bind(capabilities.getChildAt(index))
                val expectedStatus = if (row.tvCapName.text.toString() == context.getString(R.string.cap_mention)) {
                    R.string.cap_status_handed_off
                } else {
                    status
                }
                assertEquals(context.getString(expectedStatus), row.tvCapStatus.text.toString())
            }
        }
    }
}
