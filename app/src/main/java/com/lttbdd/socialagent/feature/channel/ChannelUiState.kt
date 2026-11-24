package com.lttbdd.socialagent.feature.channel

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.data.model.FacebookPage

/**
 * Trạng thái UI màn hình Kênh của tôi (F1).
 */
sealed interface ChannelUiState {
    data class Loading(val channels: List<ChannelAccount> = emptyList()) : ChannelUiState
    data object LoadFailed : ChannelUiState
    data class Success(val channels: List<ChannelAccount>) : ChannelUiState
}

/** Danh sách Page của một lượt đăng nhập. Hai lượt có thể trả về cùng danh sách Page. */
class FacebookPagePicker(val pages: List<FacebookPage>)

/** Thông báo một lần cho UI (không lưu lại trạng thái). */
enum class ChannelNotice {
    CHANNEL_DISCONNECTED,
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
