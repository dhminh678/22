package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.data.model.FacebookPage

/**
 * Trạng thái UI màn hình Kênh của tôi (F1).
 */
sealed interface ChannelUiState {
    data object Loading : ChannelUiState
    data object LoadFailed : ChannelUiState
    data class Success(
        val channels: List<ChannelAccount>,
        val tokenRevision: Long = 0,
        val facebookReconnectIds: Set<String> = emptySet(),
        val facebookPagePermissionIds: Set<String> = emptySet(),
    ) : ChannelUiState
}

/** Danh sách Page để người dùng chọn khi tài khoản Facebook quản lý nhiều Page. */
data class FacebookPagePicker(val pages: List<FacebookPage>)

/** Thông báo một lần cho UI (không lưu lại trạng thái). */
enum class ChannelNotice {
    NO_FACEBOOK_PAGES,
    FACEBOOK_PAGES_LOAD_FAILED,
    FACEBOOK_LOGIN_CANCELLED,
    FACEBOOK_LOGIN_FAILED,
    LINKEDIN_LOGIN_CANCELLED,
    LINKEDIN_LOGIN_FAILED,
    LINKEDIN_LOGIN_TIMEOUT,

    /** Deep link của BFF quay về nhưng không còn phiên đang chờ (quá hạn hoặc app bị đóng giữa chừng). */
    LINKEDIN_SESSION_EXPIRED,
}
