package com.lttbdd.socialagent.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.facebook.AccessToken
import com.facebook.AuthenticationToken
import com.facebook.AuthenticationTokenCache
import com.facebook.FacebookSdk
import com.facebook.login.LoginManager
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.entity.ChannelCleanupReceipt
import com.lttbdd.socialagent.core.model.ChannelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import javax.inject.Inject

fun interface ChannelDataCleanup {
    suspend fun clear(type: ChannelType, operationId: String)
    /** Dấu vân tay ổn định qua khởi động lại của phiên truy cập Facebook hiện tại. */
    suspend fun currentFacebookSession(): String? = null

    /** Hoàn tất đăng xuất khi phiên còn khớp. Phiên mới vẫn được giữ lại. */
    suspend fun signOutFacebook(expectedSession: String) {
        error("Không có bộ xử lý đăng xuất Facebook.")
    }
}

class LocalChannelDataCleanup @Inject constructor(
    private val database: AppDatabase,
) : ChannelDataCleanup {
    override suspend fun clear(type: ChannelType, operationId: String) {
        database.withTransaction {
            val cleanupDao = database.channelCleanupDao()
            if (cleanupDao.getCompletedOperation(type) == operationId) return@withTransaction
            database.commentDao().deleteByChannel(type)
            database.metricsDao().deleteByChannel(type)
            database.postDao().deleteByChannel(type)
            database.draftDao().deleteByChannel(type)
            // Ghi dấu cùng giao dịch để việc thử lại không xoá dữ liệu tạo sau lần dọn này.
            cleanupDao.recordCompletion(ChannelCleanupReceipt(type, operationId))
        }
    }

    override suspend fun currentFacebookSession(): String? = withContext(Dispatchers.Main.immediate) {
        if (!FacebookSdk.isInitialized()) return@withContext null
        val session = currentSession(FacebookSdk.getApplicationContext())
        session.key.takeIf { session.access != null || session.authentication != null }
    }

    override suspend fun signOutFacebook(expectedSession: String) {
        val context = withContext(Dispatchers.Main.immediate) {
            check(FacebookSdk.isInitialized())
            val sdkContext = FacebookSdk.getApplicationContext()
            val current = currentSession(sdkContext)
            // SDK và OAuth callback chạy trên Main. So sánh và logout liền nhau.
            // Nếu lần trước mới xóa một phần, chỉ tiếp tục khi phần còn lại thuộc phiên cũ.
            if (!current.canFinishSignOut(expectedSession)) return@withContext null
            LoginManager.getInstance().logOut()
            sdkContext
        } ?: return

        // Meta SDK 17 dùng SharedPreferences.apply(). Chờ ghi xuống đĩa trước khi xác nhận journal.
        withContext(Dispatchers.IO) {
            for (name in FACEBOOK_LOGOUT_PREFERENCES) {
                check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit())
            }
        }
    }

    private suspend fun currentSession(context: Context): FacebookSession {
        // SDK đánh dấu đã khởi tạo trước khi nạp token từ cache. Token null tạm thời
        // trong khoảng đó không có nghĩa phiên đã được đăng xuất.
        val cache = context.getSharedPreferences(ACCESS_TOKEN_CACHE, Context.MODE_PRIVATE)
        check(withTimeoutOrNull(CACHE_LOAD_TIMEOUT_MILLIS) {
            while (cache.contains(CACHED_ACCESS_TOKEN) && AccessToken.getCurrentAccessToken() == null) {
                delay(25L)
            }
            true
        } == true) { "Chưa khôi phục được phiên Facebook SDK." }
        val access = AccessToken.getCurrentAccessToken()?.let {
            // Thời điểm SDK tạo token cũng nhận diện lần đăng nhập lại cùng tài khoản.
            fingerprint("${it.applicationId}:${it.userId}:${it.lastRefresh.time}:${it.token}")
        }
        val authentication = AuthenticationToken.getCurrentAuthenticationToken() ?: AuthenticationTokenCache().load()
        return FacebookSession(access, authentication?.let { fingerprint(it.token) })
    }

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private data class FacebookSession(val access: String?, val authentication: String?) {
        val key: String get() = "${access.orEmpty()}:${authentication.orEmpty()}"

        fun canFinishSignOut(expected: String): Boolean =
            (access == null || access == expected.substringBefore(':')) &&
                (authentication == null || authentication == expected.substringAfter(':'))
    }

    private companion object {
        // Tên cache và tùy chọn đăng nhập trong Meta SDK 17.0.2.
        const val ACCESS_TOKEN_CACHE = "com.facebook.AccessTokenManager.SharedPreferences"
        const val CACHED_ACCESS_TOKEN = "com.facebook.AccessTokenManager.CachedAccessToken"
        const val CACHE_LOAD_TIMEOUT_MILLIS = 1_000L
        val FACEBOOK_LOGOUT_PREFERENCES = listOf(
            ACCESS_TOKEN_CACHE,
            "com.facebook.AuthenticationTokenManager.SharedPreferences",
            "com.facebook.loginManager",
        )
    }
}
