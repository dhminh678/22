package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import kotlinx.coroutines.flow.Flow

/**
 * Repository quản lý việc kết nối kênh mạng xã hội qua OAuth.
 * Nguồn tham chiếu:
 * - RFC 6749 Section 5.1 & 4.2.2: Tính expiresAt từ expires_in thực tế.
 * - RFC 7636: PKCE giữa app và BFF (code_verifier) khi đổi exchange_code lấy token.
 * - RFC 8252: Xác thực qua External User-Agent.
 */
interface ChannelRepository {

    /**
     * Lấy danh sách các kênh đã kết nối.
     */
    fun getConnectedChannels(): Flow<List<ChannelAccount>>

    /**
     * Chỉ cho chế độ demo (chưa cấu hình FACEBOOK_APP_ID): tạo kênh Facebook giả.
     * Đăng nhập thật dùng Meta SDK rồi [connectFacebookProfile] / [connectFacebookSelectedPage].
     */
    suspend fun connectFacebookPage(
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<ChannelAccount>

    /**
     * Chỉ cho chế độ demo (chưa cấu hình LINKEDIN_MEMBER_CLIENT_ID hoặc LINKEDIN_BFF_URL): tạo kênh LinkedIn giả.
     * Đăng nhập thật dùng [startLinkedInSession] rồi [connectLinkedInViaBff].
     */
    suspend fun connectLinkedIn(
        authCode: String,
        codeVerifier: String,
        redirectUri: String,
    ): Result<ChannelAccount>

    /**
     * Đăng ký phiên đăng nhập LinkedIn với BFF (`state` + `code_challenge` S256) trước khi mở trình duyệt.
     */
    suspend fun startLinkedInSession(state: String, codeChallenge: String): Result<Unit> = Result.success(Unit)

    /**
     * Kết nối LinkedIn thật: đổi `exchange_code` (BFF chuyển về app qua deep link) kèm `state` và `code_verifier`
     * lấy token. `client_secret` chỉ nằm ở BFF, không có trong app.
     */
    suspend fun connectLinkedInViaBff(
        exchangeCode: String,
        state: String,
        codeVerifier: String,
    ): Result<ChannelAccount> = Result.failure(UnsupportedOperationException("Chưa hỗ trợ đăng nhập LinkedIn qua BFF"))

    /**
     * Ngắt kết nối một kênh và xóa token khỏi Keystore/SecureTokenStore.
     */
    suspend fun disconnectChannel(type: ChannelType, accountId: String): Result<Unit>

    /**
     * Quyền (scope) thật mà token của kênh đã được cấp, lưu lúc kết nối.
     * Trả về null nếu chưa có token hoặc không đọc được.
     */
    suspend fun getGrantedScope(type: ChannelType, accountId: String): String? = null

    /**
     * Thời điểm (epoch millis) token của kênh hết hạn, đọc từ token đã lưu.
     * Trả về null nếu chưa có token hoặc không đọc được (khi đó coi như chưa xác định, không báo hết hạn).
     */
    suspend fun getTokenExpiresAt(type: ChannelType, accountId: String): Long? = null

    /**
     * Lấy danh sách Facebook Page mà người dùng quản lý (Graph API `/me/accounts`).
     * Thất bại (lỗi mạng, thiếu quyền `pages_show_list`) trả về Result.failure.
     */
    suspend fun fetchFacebookPages(userAccessToken: String): Result<List<FacebookPage>> =
        Result.success(emptyList())

    /**
     * Kết nối một Page đã chọn: lưu Page access token, quyền thật và hạn thật lấy từ phiên [user] (Meta SDK).
     */
    suspend fun connectFacebookSelectedPage(
        user: FacebookUserToken,
        page: FacebookPage,
    ): Result<ChannelAccount> = Result.failure(UnsupportedOperationException("Chưa hỗ trợ chọn Page"))

    /**
     * Người dùng không quản lý Page nào: kết nối bằng hồ sơ cá nhân (chỉ `public_profile`, chưa có khả năng ở mức Page).
     */
    suspend fun connectFacebookProfile(user: FacebookUserToken): Result<ChannelAccount> =
        Result.failure(UnsupportedOperationException("Chưa hỗ trợ kết nối hồ sơ"))
}
