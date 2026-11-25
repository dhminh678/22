package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ConnectionStatus
import com.lttbdd.socialagent.data.model.FacebookPageAccessIssue

enum class CapabilityStatusType {
    SUPPORTED,
    REQUIRES_PERMISSION,
    REQUIRES_RECONNECT,
    NOT_SUPPORTED,
    UNVERIFIED,

    /** Không làm được qua API với app này: app thông báo và người dùng tự thao tác (HANDED_OFF). */
    HANDED_OFF,
}

data class CapabilityItemUiState(
    val capabilityNameRes: Int,
    val statusTextRes: Int,
    val statusType: CapabilityStatusType,
    val captionRes: Int,
)

/**
 * Lớp đánh giá ma trận 5 khả năng (Publish, Read Comments, Reply, Mention, Metrics) cho từng kênh.
 * Nguồn tham chiếu kỹ thuật chính thức:
 * - Meta Pages API Posts: https://developers.facebook.com/documentation/pages-api/posts
 * - LinkedIn Posts API: https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/posts-api
 * - LinkedIn Comments API: https://learn.microsoft.com/en-us/linkedin/marketing/integrations/community-management/shares/comments-api
 */
object ChannelCapabilityEvaluator {

    /**
     * Tách chuỗi quyền, chấp nhận cả dấu phẩy (Facebook) lẫn khoảng trắng (LinkedIn/OAuth 2.0).
     */
    internal fun parseScopes(scopeString: String?): Set<String> =
        scopeString.orEmpty().split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.toSet()

    fun evaluateCapabilities(
        account: ChannelAccount,
        scopeString: String?,
        pageTasks: List<String>? = null,
        isFacebookProfile: Boolean? = null,
        pageAccessIssue: FacebookPageAccessIssue? = null,
    ): List<CapabilityItemUiState> {
        val scopes = parseScopes(scopeString)

        val items = when (account.type) {
            ChannelType.FACEBOOK -> evaluateFacebook(scopes, pageTasks?.toSet(), isFacebookProfile)
            ChannelType.LINKEDIN -> evaluateLinkedIn(scopes)
        }
        val pageIssue = pageAccessIssue.takeIf { account.type == ChannelType.FACEBOOK && isFacebookProfile != true }
        val requiresReconnect = account.status == ConnectionStatus.TOKEN_EXPIRED ||
            pageIssue == FacebookPageAccessIssue.RECONNECT_REQUIRED
        if (!requiresReconnect && pageIssue == null) return items

        return items.map { item ->
            when {
                item.statusType == CapabilityStatusType.HANDED_OFF ||
                    item.statusType == CapabilityStatusType.NOT_SUPPORTED -> item
                requiresReconnect -> item.copy(
                    statusTextRes = R.string.cap_status_requires_reconnect,
                    statusType = CapabilityStatusType.REQUIRES_RECONNECT,
                    captionRes = when {
                        pageIssue == FacebookPageAccessIssue.RECONNECT_REQUIRED -> R.string.cap_fb_page_token_invalid
                        item.captionRes == R.string.cap_fb_account_type_unknown -> item.captionRes
                        else -> R.string.cap_token_expired
                    },
                )
                else -> item.copy(
                    statusTextRes = R.string.cap_status_requires_perm,
                    statusType = CapabilityStatusType.REQUIRES_PERMISSION,
                    captionRes = R.string.channel_status_page_permission_required,
                )
            }
        }
    }

    /**
     * Facebook: một khả năng chỉ "Hỗ trợ" khi vừa có quyền của app vừa có Page task của người dùng trên Page
     * (docs/research-social-api.md, mục Page Tasks): đăng bài cần CREATE_CONTENT; đọc comment cần MODERATE;
     * trả lời cần MODERATE và CREATE_CONTENT; số liệu cần ANALYZE kèm `read_insights` và `pages_read_engagement`.
     * [tasks] là null khi chưa biết (token cũ, hồ sơ cá nhân): khi đó chỉ xét quyền, không đoán task.
     */
    private fun evaluateFacebook(
        scopes: Set<String>,
        tasks: Set<String>?,
        isFacebookProfile: Boolean?,
    ): List<CapabilityItemUiState> {
        fun hasTasks(vararg required: String) = tasks == null || tasks.containsAll(required.toList())

        fun item(
            nameRes: Int,
            hasPerm: Boolean,
            hasTask: Boolean,
            okCaption: Int,
            noPermCaption: Int,
            noTaskCaption: Int,
        ): CapabilityItemUiState = when {
            !hasPerm -> CapabilityItemUiState(nameRes, R.string.cap_status_requires_perm, CapabilityStatusType.REQUIRES_PERMISSION, noPermCaption)
            !hasTask -> CapabilityItemUiState(nameRes, R.string.cap_status_requires_perm, CapabilityStatusType.REQUIRES_PERMISSION, noTaskCaption)
            else -> CapabilityItemUiState(nameRes, R.string.cap_status_supported, CapabilityStatusType.SUPPORTED, okCaption)
        }

        // 1. Đăng bài: pages_manage_posts + task CREATE_CONTENT.
        // Đây là giả định của ma trận #22. Feed cần thử thực tế với Page chỉ có CREATE_CONTENT
        // vì tài liệu Meta còn liệt kê MANAGE và MODERATE (docs/research-social-api.md, mục Page Tasks).
        val publishState = item(
            R.string.cap_publish,
            scopes.contains("pages_manage_posts"),
            hasTasks("CREATE_CONTENT"),
            R.string.cap_fb_publish_ok,
            R.string.cap_fb_publish_no_perm,
            R.string.cap_fb_publish_no_task,
        )

        // 2. Đọc comment: pages_read_engagement hoặc pages_read_user_content + task MODERATE
        val readCommentsState = item(
            R.string.cap_read_comments,
            scopes.contains("pages_read_engagement") || scopes.contains("pages_read_user_content"),
            hasTasks("MODERATE"),
            R.string.cap_fb_read_comments_ok,
            R.string.cap_fb_read_comments_no_perm,
            R.string.cap_fb_read_comments_no_task,
        )

        // 3. Trả lời comment: pages_manage_engagement + task MODERATE và CREATE_CONTENT
        val replyState = item(
            R.string.cap_reply,
            scopes.contains("pages_manage_engagement"),
            hasTasks("MODERATE", "CREATE_CONTENT"),
            R.string.cap_fb_reply_ok,
            R.string.cap_fb_reply_no_perm,
            R.string.cap_fb_reply_no_task,
        )

        // Mention chưa được xác nhận qua API, nên luôn hướng dẫn bàn giao thủ công.
        val mentionState = CapabilityItemUiState(
            capabilityNameRes = R.string.cap_mention,
            statusTextRes = R.string.cap_status_handed_off,
            statusType = CapabilityStatusType.HANDED_OFF,
            captionRes = R.string.cap_fb_mention_handed_off,
        )

        // 5. Số liệu: read_insights + pages_read_engagement + task ANALYZE
        val metricsState = item(
            R.string.cap_metrics,
            scopes.contains("read_insights") && scopes.contains("pages_read_engagement"),
            hasTasks("ANALYZE"),
            R.string.cap_fb_metrics_ok,
            R.string.cap_fb_metrics_no_perm,
            R.string.cap_fb_metrics_no_task,
        )

        val items = listOf(publishState, readCommentsState, replyState, mentionState, metricsState)
        val caption = when {
            isFacebookProfile == true -> R.string.cap_fb_needs_page
            isFacebookProfile == null && scopes == setOf("public_profile") && tasks == null ->
                R.string.cap_fb_account_type_unknown
            else -> return items
        }
        return items.map {
            if (it.statusType == CapabilityStatusType.HANDED_OFF) it else it.copy(captionRes = caption)
        }
    }

    private fun evaluateLinkedIn(scopes: Set<String>): List<CapabilityItemUiState> {
        // 1. Đăng bài (Publish) - Quyền yêu cầu: w_organization_social hoặc w_member_social
        val publishState = if (scopes.contains("w_organization_social") || scopes.contains("w_member_social")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_publish,
                statusTextRes = R.string.cap_status_supported,
                statusType = CapabilityStatusType.SUPPORTED,
                captionRes = R.string.cap_li_publish_ok,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_publish,
                statusTextRes = R.string.cap_status_requires_perm,
                statusType = CapabilityStatusType.REQUIRES_PERMISSION,
                captionRes = R.string.cap_li_publish_no_perm,
            )
        }

        // Các quyền đọc/trả lời comment và số liệu của LinkedIn chỉ có khi app được duyệt Community Management API
        // (hoặc cấp riêng cho "select developers"); app không tự xin thêm được nên thiếu quyền thì bàn giao thủ công.
        // 2. Đọc comment (Read Comments) - Quyền yêu cầu: r_organization_social_feed hoặc r_member_social_feed
        val readCommentsState = if (scopes.contains("r_organization_social_feed") || scopes.contains("r_member_social_feed")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_read_comments,
                statusTextRes = R.string.cap_status_supported,
                statusType = CapabilityStatusType.SUPPORTED,
                captionRes = R.string.cap_li_read_comments_ok,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_read_comments,
                statusTextRes = R.string.cap_status_handed_off,
                statusType = CapabilityStatusType.HANDED_OFF,
                captionRes = R.string.cap_li_read_comments_handed_off,
            )
        }

        // 3. Trả lời comment (Reply) - Quyền yêu cầu: w_organization_social_feed hoặc w_member_social_feed
        val replyState = if (scopes.contains("w_organization_social_feed") || scopes.contains("w_member_social_feed")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_reply,
                statusTextRes = R.string.cap_status_supported,
                statusType = CapabilityStatusType.SUPPORTED,
                captionRes = R.string.cap_li_reply_ok,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_reply,
                statusTextRes = R.string.cap_status_handed_off,
                statusType = CapabilityStatusType.HANDED_OFF,
                captionRes = R.string.cap_li_reply_handed_off,
            )
        }

        // Mention chưa được xác nhận qua API, nên luôn hướng dẫn bàn giao thủ công.
        val mentionState = CapabilityItemUiState(
            capabilityNameRes = R.string.cap_mention,
            statusTextRes = R.string.cap_status_handed_off,
            statusType = CapabilityStatusType.HANDED_OFF,
            captionRes = R.string.cap_li_mention_handed_off,
        )

        // 5. Số liệu (Metrics) - Quyền yêu cầu: rw_organization_admin
        val metricsState = if (scopes.contains("rw_organization_admin")) {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_metrics,
                statusTextRes = R.string.cap_status_supported,
                statusType = CapabilityStatusType.SUPPORTED,
                captionRes = R.string.cap_li_metrics_ok,
            )
        } else {
            CapabilityItemUiState(
                capabilityNameRes = R.string.cap_metrics,
                statusTextRes = R.string.cap_status_handed_off,
                statusType = CapabilityStatusType.HANDED_OFF,
                captionRes = R.string.cap_li_metrics_handed_off,
            )
        }

        return listOf(publishState, readCommentsState, replyState, mentionState, metricsState)
    }
}
