package com.lttbdd.socialagent.feature.channel

import androidx.annotation.StringRes
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.data.model.FacebookPage

/**
 * Trạng thái UI màn hình Kênh của tôi (F1).
 */
sealed interface ChannelUiState {
    data object Loading : ChannelUiState
    data class Success(
        val channels: List<ChannelAccount>,
        /** Tên kênh vừa kết nối xong; Fragment ghép vào chuỗi đã dịch để báo cho người dùng. */
        val connectedName: String? = null,
    ) : ChannelUiState

    /**
     * Lỗi hiển thị cho người dùng: ưu tiên [message] (từ nguồn ngoài như repository),
     * không có thì dùng chuỗi đã dịch [messageRes].
     */
    data class Error(
        val message: String? = null,
        @StringRes val messageRes: Int = R.string.channel_oauth_error_generic,
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
}
