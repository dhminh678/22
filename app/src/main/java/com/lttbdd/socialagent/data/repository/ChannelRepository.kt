package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.core.model.ChannelAccount
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.FacebookPage
import com.lttbdd.socialagent.data.model.FacebookUserToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository quản lý việc kết nối kênh mạng xã hội qua OAuth.
 * Nguồn tham chiếu:
 * - RFC 6749 Section 5.1 & 4.2.2: Tính expiresAt từ expires_in thực tế.
 * - RFC 7636: PKCE giữa app và BFF (code_verifier) khi đổi exchange_code lấy token.
 * - RFC 8252: Xác thực qua External User-Agent.
 */
interface ChannelRepository {

    /**
     * Lấy danh sách các kênh đã kết nối. Flow báo lỗi nếu kho không đọc được, có thể thu thập lại để thử lại.
     */
    fun getConnectedChannels(): Flow<List<ChannelAccount>>

    /** Có lượt ngắt kết nối đã được lưu và còn dữ liệu cần dọn. */
    val hasPendingDisconnectCleanup: StateFlow<Boolean>

    /** Thử lại các lượt ngắt kết nối đã lưu. Trả về true khi không còn lượt nào cần dọn. */
    suspend fun retryPendingDisconnectCleanup(): Boolean

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
     * Ngắt kết nối và xoá token, metadata. Xoá dữ liệu cục bộ theo loại kênh khi ngắt tài khoản cuối.
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
     * Page task của người dùng trên Page đã kết nối (lưu cùng token). Trả về null nếu chưa biết
     * (khi đó ma trận chỉ xét quyền của app, không giả định có hay không có task).
     */
    suspend fun getPageTasks(type: ChannelType, accountId: String): List<String>? = null

    /** Hồ sơ Facebook là true, Page là false, chưa xác định được là null. */
    suspend fun isFacebookProfile(accountId: String): Boolean? = null

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
