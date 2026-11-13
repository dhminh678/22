package com.lttbdd.socialagent.data.network

import java.net.URLEncoder

/**
 * Tham số của luồng LinkedIn Standard OAuth 2.0 qua BFF (không có client_secret trong app).
 * Nguồn: docs/research-social-api.md (Luồng B), RFC 6749, RFC 7636, RFC 8252.
 *
 * App mở trang đăng nhập LinkedIn với `redirect_uri` là endpoint của BFF; BFF giữ `client_secret`,
 * đổi code lấy token rồi chuyển hướng về app bằng [APP_REDIRECT_URI] kèm `exchange_code` dùng một lần.
 */
object LinkedInBffAuth {

    const val AUTHORIZATION_ENDPOINT = "https://www.linkedin.com/oauth/v2/authorization"

    /** Quyền xin khi đăng nhập; scope thật nhận về nằm trong phản hồi của BFF và được lưu theo token. */
    const val SCOPE = "openid profile w_member_social"

    /** Địa chỉ BFF chuyển hướng trình duyệt về app; phải trùng `APP_REDIRECT_URI` ở máy chủ và intent-filter trong Manifest. */
    const val APP_REDIRECT_URI = "com.lttbdd.socialagent://oauth/linkedin/callback"

    private const val START_PATH = "/auth/linkedin/start"
    private const val CALLBACK_PATH = "/auth/linkedin/callback"
    private const val TOKEN_PATH = "/auth/linkedin/token"

    /** Chuẩn hóa địa chỉ BFF: bỏ khoảng trắng và dấu `/` cuối. */
    fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')

    /** BFF chỉ được dùng qua HTTPS (token đi qua đường này). */
    fun isValidBaseUrl(raw: String): Boolean {
        val url = normalizeBaseUrl(raw)
        return url.startsWith("https://") && url.length > "https://".length && !url.contains(' ')
    }

    fun startUrl(baseUrl: String): String = normalizeBaseUrl(baseUrl) + START_PATH

    fun tokenUrl(baseUrl: String): String = normalizeBaseUrl(baseUrl) + TOKEN_PATH

    /** Redirect URI đăng ký ở LinkedIn Developer Portal và gửi trong request đăng nhập. */
    fun bffRedirectUri(baseUrl: String): String = normalizeBaseUrl(baseUrl) + CALLBACK_PATH

    fun authorizationUrl(
        clientId: String,
        baseUrl: String,
        state: String,
    ): String = AUTHORIZATION_ENDPOINT + "?" + listOf(
        "response_type" to "code",
        "client_id" to clientId,
        "redirect_uri" to bffRedirectUri(baseUrl),
        "scope" to SCOPE,
        "state" to state,
    ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
}
