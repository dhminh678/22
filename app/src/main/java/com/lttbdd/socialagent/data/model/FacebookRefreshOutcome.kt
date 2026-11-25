package com.lttbdd.socialagent.data.model

import com.lttbdd.socialagent.core.model.ChannelAccount

enum class FacebookRefreshIssue {
    NETWORK,
    SDK_UNAVAILABLE,
    TIMEOUT,
    RATE_LIMITED,
    PROVIDER_UNAVAILABLE,
    SESSION_INVALID,
    USER_SESSION_EXPIRED,
    ACCOUNT_MISMATCH,
    STORAGE,
    PAGES_UNAVAILABLE,
    PAGE_NOT_RETURNED,
}

enum class FacebookRefreshSkip {
    NO_ELIGIBLE_CHANNELS,
    NO_SDK_SESSION,
    SDK_SESSION_CHANGED,
    WAITING_FOR_SESSION,
    COOLDOWN,
    NO_CHANGES,
}

data class FacebookRefreshOutcome(
    val updated: List<ChannelAccount> = emptyList(),
    val issues: Set<FacebookRefreshIssue> = emptySet(),
    val reconnectAccountIds: Set<String> = emptySet(),
    val skipped: FacebookRefreshSkip? = null,
    val issuesByAccountId: Map<String, FacebookRefreshIssue> = emptyMap(),
    val confirmedPageIds: Set<String> = emptySet(),
    val retryablePageIds: Set<String> = emptySet(),
    val batchIssues: Set<FacebookRefreshIssue> = issues - issuesByAccountId.values.toSet(),
)

data class FacebookRefreshCheck(val outcome: FacebookRefreshOutcome, val nextCheckMillis: Long)

internal const val FACEBOOK_REFRESH_RETRY = 15 * 60 * 1000L

internal class FacebookRefreshException(val issue: FacebookRefreshIssue) : Exception(issue.name)
