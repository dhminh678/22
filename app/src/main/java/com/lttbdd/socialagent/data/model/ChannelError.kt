package com.lttbdd.socialagent.data.model

import java.io.IOException

/**
 * Lỗi kết nối kênh có kiểu: repository chỉ trả mã lỗi, ViewModel ánh xạ sang chuỗi đã dịch (quy tắc 4 của AGENTS.md).
 * Không mang nội dung phản hồi hay token, nên an toàn để hiện và để log.
 */
enum class ChannelError {
    NETWORK,
    CHANNELS_LOAD_FAILED,
    CHANNELS_SAVE_FAILED,
    DISCONNECT_CLEANUP_FAILED,
    LINKEDIN_CONFIG_INVALID,
    LINKEDIN_SESSION_START_FAILED,
    LINKEDIN_TOKEN_EXCHANGE_FAILED,
    LINKEDIN_TOKEN_INVALID,
    LINKEDIN_PROFILE_FAILED,
    FACEBOOK_PROFILE_FAILED,
    FACEBOOK_PAGES_LOAD_FAILED,
    FACEBOOK_SIGN_OUT_FAILED,
    FACEBOOK_SESSION_INVALID,
    RECONNECT_ACCOUNT_MISMATCH,
    UNKNOWN,
}

/** Ngoại lệ mang [ChannelError]; `message` chỉ là tên mã, không hiển thị cho người dùng. */
class ChannelException(val error: ChannelError, cause: Throwable? = null) : Exception(error.name, cause)

/** Phân loại lỗi bất kỳ: lỗi mạng (IOException) tách riêng, còn lại là UNKNOWN. */
fun Throwable.toChannelError(): ChannelError = when {
    this is ChannelException -> error
    this is IOException -> ChannelError.NETWORK
    else -> ChannelError.UNKNOWN
}
