package com.lttbdd.socialagent.data.model

/**
 * Phiên đăng nhập Facebook lấy từ Meta SDK (`AccessToken`): giá trị hạn và quyền là thật, không viết cứng.
 *
 * @property expiresAtMillis thời điểm hết hạn thật (epoch millis); `Long.MAX_VALUE` nếu SDK báo không hết hạn.
 * @property grantedScope các quyền đã được cấp, phân tách bằng dấu phẩy; null nếu SDK không trả quyền nào.
 */
data class FacebookUserToken(
    val accessToken: String,
    val expiresAtMillis: Long,
    val grantedScope: String?,
    val userId: String? = null,
) {
    // Không để token lọt vào log qua toString.
    override fun toString(): String =
        "FacebookUserToken(expiresAtMillis=$expiresAtMillis, grantedScope=$grantedScope)"
}
