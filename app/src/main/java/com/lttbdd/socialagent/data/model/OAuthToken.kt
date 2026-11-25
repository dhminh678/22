package com.lttbdd.socialagent.data.model

import kotlinx.serialization.Serializable

/**
 * Model lưu trữ Token OAuth sau khi xác thực thành công.
 * Nguồn tham chiếu:
 * - RFC 6749 Section 5.1 (Successful Response) & Section 4.2.2 (Access Token Response):
 *   `expires_in` được trả về theo đơn vị giây.
 * - Công thức tính mốc thời điểm hết hạn tuyệt đối:
 *   expiresAt = currentTimeMillis + (expiresInSeconds * 1000L)
 */
@Serializable
data class OAuthToken(
    val accessToken: String,
    /** Hạn đã xác nhận của chính token. Null nghĩa là chưa xác định được hạn. */
    val expiresAt: Long?,
    val scope: String? = null,
    val tokenType: String = "Bearer",
    /** Page task của người dùng trên Page (chỉ Facebook Page); null nếu chưa biết (token cũ, hồ sơ cá nhân). */
    val pageTasks: List<String>? = null,
    /** Hồ sơ Facebook là true, Page là false, token cũ hoặc kênh khác là null. */
    val isFacebookProfile: Boolean? = null,
    /** ID người cấp quyền cho token Facebook, lưu cùng token đã mã hóa. */
    val facebookUserId: String? = null,
    /** Hạn phiên người cấp quyền, dùng để lên lịch refresh Page và nhắc kết nối lại. */
    val facebookUserSessionExpiresAt: Long? = null,
) {
    /**
     * Kiểm tra token đã qua thời điểm hết hạn đã xác nhận.
     * Khi [expiresAt] là null, kết quả false chỉ có nghĩa chưa biết hạn.
     * Với Facebook Page, luồng gọi Graph phải phân biệt token không hợp lệ (190)
     * và lỗi quyền (subcode 492) của token đang dùng.
     */
    fun isExpired(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        return expiresAt?.let { currentTimeMillis >= it } ?: false
    }

    companion object {
        /**
         * Khởi tạo OAuthToken từ giá trị `expires_in` (tính bằng giây) trả về từ OAuth Server.
         */
        fun createFromExpiresIn(
            accessToken: String,
            expiresInSeconds: Long,
            scope: String? = null,
            tokenType: String = "Bearer",
            currentTimeMillis: Long = System.currentTimeMillis(),
        ): OAuthToken {
            val expiresAt = currentTimeMillis + (expiresInSeconds * 1000L)
            return OAuthToken(
                accessToken = accessToken,
                expiresAt = expiresAt,
                scope = scope,
                tokenType = tokenType,
            )
        }
    }
}
