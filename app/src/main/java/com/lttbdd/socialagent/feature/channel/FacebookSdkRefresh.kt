package com.lttbdd.socialagent.feature.channel

import com.facebook.AccessToken
import com.facebook.FacebookException
import com.facebook.FacebookRequestError
import com.facebook.FacebookServiceException
import com.lttbdd.socialagent.data.model.FacebookRefreshException
import com.lttbdd.socialagent.data.model.FacebookRefreshIssue
import com.lttbdd.socialagent.data.model.FacebookUserToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal suspend fun ChannelViewModel.monitorFacebookRefresh(
    currentUserId: () -> String? = { AccessToken.getCurrentAccessToken()?.userId },
    currentSessionVersion: () -> Long? = { null },
    refreshToken: suspend () -> FacebookUserToken? = ::refreshFacebookSdkToken,
) {
    while (true) {
        val userId = currentUserId()
        val sessionVersion = currentSessionVersion()
        fun isCurrent() = currentUserId() == userId && currentSessionVersion() == sessionVersion

        val check = refreshFacebookIfNeeded(userId, sessionVersion = {
            currentSessionVersion().takeIf { currentUserId() == userId }
        }) {
            if (!isCurrent()) return@refreshFacebookIfNeeded null
            try {
                refreshToken().takeIf { isCurrent() }
            } catch (e: FacebookRefreshException) {
                if (isCurrent()) throw e
                null
            }
        }
        // Khi phiên SDK thay đổi, kiểm tra ngay cả khi người dùng đăng nhập lại cùng tài khoản.
        var remaining = check.nextCheckMillis
        while (remaining > 0 && isCurrent()) {
            val interval = minOf(remaining, SESSION_CHECK_INTERVAL)
            delay(interval)
            remaining -= interval
        }
    }
}

private suspend fun refreshFacebookSdkToken(): FacebookUserToken? = withContext(Dispatchers.Main.immediate) {
    val version = facebookSdkSessionVersion() ?: return@withContext null
    awaitFacebookSdkRefresh(
        acceptToken = { refreshed ->
            facebookSdkSession.acceptRefresh(
                version, refreshed, AccessToken.getCurrentAccessToken(), AccessToken::setCurrentAccessToken,
            )
        },
        register = AccessToken::refreshCurrentAccessTokenAsync,
    )
}

private data class SdkRefreshResult(val token: AccessToken?)

internal suspend fun awaitFacebookSdkRefresh(
    acceptToken: (AccessToken) -> Boolean = { true },
    register: (AccessToken.AccessTokenRefreshCallback) -> Unit,
): FacebookUserToken? = withTimeoutOrNull(SDK_REFRESH_TIMEOUT_MILLIS) {
    suspendCancellableCoroutine<SdkRefreshResult> { continuation ->
        val finished = AtomicBoolean()
        if (!continuation.isActive) return@suspendCancellableCoroutine
        register(object : AccessToken.AccessTokenRefreshCallback {
            override fun OnTokenRefreshed(accessToken: AccessToken?) {
                if (!finished.compareAndSet(false, true)) return
                // SDK có thể đổi token sau timeout. Đồng bộ phiên SDK dù coroutine đã ngừng chờ.
                val accepted = accessToken?.takeIf(acceptToken)
                if (!continuation.isActive) return
                if (accessToken == null) {
                    continuation.resumeWithException(FacebookRefreshException(FacebookRefreshIssue.SDK_UNAVAILABLE))
                } else {
                    continuation.resume(SdkRefreshResult(accepted))
                }
            }

            override fun OnTokenRefreshFailed(exception: FacebookException?) {
                if (!finished.compareAndSet(false, true) || !continuation.isActive) return
                continuation.resumeWithException(classifySdkFailure(exception))
            }
        })
    }
}.let { result ->
    if (result == null) throw FacebookRefreshException(FacebookRefreshIssue.TIMEOUT)
    result.token?.toUserToken()
}

private fun classifySdkFailure(exception: FacebookException?): FacebookRefreshException {
    val error = (exception as? FacebookServiceException)?.requestError
    val code = error?.errorCode
    val status = error?.requestStatusCode
    val category = error?.category
    val type = when {
        error?.errorType == "OAuthException" -> "OAuthException"
        error != null -> "GraphError"
        exception?.cause is IOException -> "TransportError"
        exception != null -> "SdkError"
        else -> "Unknown"
    }
    val issue = when {
        error?.subErrorCode == 492 -> FacebookRefreshIssue.PAGES_UNAVAILABLE
        code == 190 -> FacebookRefreshIssue.SESSION_INVALID
        status == 429 || code in rateLimitCodes -> FacebookRefreshIssue.RATE_LIMITED
        exception?.cause is IOException -> FacebookRefreshIssue.NETWORK
        (status != null && status in 500..599) || category == FacebookRequestError.Category.TRANSIENT ->
            FacebookRefreshIssue.PROVIDER_UNAVAILABLE
        else -> FacebookRefreshIssue.SDK_UNAVAILABLE
    }
    // Chỉ ghi các trường số và enum, không ghi message, exception hoặc nội dung phản hồi.
    sdkRefreshLogger.warning(
        "event=facebook_token_refresh stage=sdk result=failed issue=$issue type=$type category=$category " +
            "graph_code=$code graph_subcode=${error?.subErrorCode} http_status=$status",
    )
    return FacebookRefreshException(issue)
}

private val facebookSdkSession = FacebookSdkSession<AccessToken>()

internal fun facebookSdkSessionVersion(): Long? =
    facebookSdkSession.version(AccessToken.getCurrentAccessToken())

internal fun invalidateFacebookSdkSession() =
    facebookSdkSession.replace(AccessToken.getCurrentAccessToken())

internal class FacebookSdkSession<T : Any> {
    private var token: T? = null
    private var generation = 0L

    @Synchronized
    fun version(current: T?): Long? {
        if (token !== current) replace(current)
        return generation.takeIf { current != null }
    }

    @Synchronized
    fun replace(current: T?) {
        token = current
        generation++
    }

    @Synchronized
    fun acceptRefresh(
        expectedVersion: Long,
        refreshed: T,
        current: T?,
        restoreSession: (T?) -> Unit,
    ): Boolean {
        if (refreshed !== current) return false
        if (generation != expectedVersion) {
            // Callback cũ có thể ghi đè phiên mới trong SDK trước khi app nhận được kết quả.
            if (token !== refreshed) restoreSession(token)
            return false
        }
        // SDK cài token mới trước callback. Lần thay token này vẫn thuộc phiên ban đầu.
        token = refreshed
        return true
    }
}

internal fun AccessToken.toUserToken() = FacebookUserToken(
    accessToken = token,
    expiresAtMillis = minOf(expires.time, dataAccessExpirationTime.time),
    grantedScope = permissions.filterNotNull().sorted().joinToString(",").ifEmpty { null },
    userId = userId,
)

private val sdkRefreshLogger = Logger.getLogger("FacebookTokenRefresh")
private val rateLimitCodes = setOf(4, 17, 32, 613)
private const val SESSION_CHECK_INTERVAL = 60_000L
internal const val SDK_REFRESH_TIMEOUT_MILLIS = 30_000L
