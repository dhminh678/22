package com.lttbdd.socialagent.data.security

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.data.model.OAuthToken
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Định danh thao tác đang chờ. Token được mã hóa và lưu riêng. */
@Serializable
data class PendingChannelWrite(
    val id: String,
    val type: ChannelType,
    val accountId: String,
    val disconnect: Boolean = false,
    val clearChannelData: Boolean = false,
    /** Chỉ chứa dấu vân tay SDK. Null khi không cần hoặc đã hoàn tất đăng xuất. */
    val facebookSessionToSignOut: String? = null,
)

/**
 * Interface định nghĩa thao tác lưu trữ OAuthToken an toàn.
 */
interface TokenStore {
    suspend fun saveToken(channelType: ChannelType, accountId: String, token: OAuthToken)
    fun getToken(channelType: ChannelType, accountId: String): Flow<OAuthToken?>
    /** Xóa nguyên tử token chính và lần ghi đang chờ của tài khoản. */
    suspend fun removeToken(channelType: ChannelType, accountId: String)

    /** Xóa token và ghi dấu cần dọn metadata trong cùng một lần ghi. */
    suspend fun stageDisconnect(write: PendingChannelWrite)

    /** Xác nhận bước đăng xuất mà vẫn giữ các bước cleanup còn lại trong journal. */
    suspend fun completeFacebookSignOut(write: PendingChannelWrite): PendingChannelWrite

    suspend fun stageToken(write: PendingChannelWrite, token: OAuthToken)
    suspend fun loadPendingWrites(): List<PendingChannelWrite>
    suspend fun loadStagedToken(write: PendingChannelWrite): OAuthToken?

    /** Cập nhật token chính thức và dọn dữ liệu tạm trong cùng một lần ghi. */
    suspend fun finishPendingWrite(write: PendingChannelWrite, commit: Boolean)
}
