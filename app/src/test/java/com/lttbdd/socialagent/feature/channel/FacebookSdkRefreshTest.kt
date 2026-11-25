package com.lttbdd.socialagent.feature.channel

import com.facebook.AccessToken
import com.facebook.FacebookException
import com.lttbdd.socialagent.data.model.FacebookRefreshException
import com.lttbdd.socialagent.data.model.FacebookRefreshIssue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class FacebookSdkRefreshTest {
    @Test
    fun sdkRotationKeepsCurrentGeneration() {
        val session = FacebookSdkSession<Any>()
        val initial = Any()
        val renewed = Any()
        val version = checkNotNull(session.version(initial))

        assertTrue(session.acceptRefresh(version, renewed, renewed) {
            error("Không cần khôi phục phiên khi SDK làm mới hợp lệ.")
        })
        assertEquals(version, checkNotNull(session.version(renewed)))
    }

    @Test
    fun sameUserReconnectRejectsOldCallbackEvenIfSdkInstalledItsToken() {
        val session = FacebookSdkSession<Any>()
        val version = checkNotNull(session.version(Any()))
        session.replace(null)
        val newLogin = Any()
        session.replace(newLogin)
        val lateRefresh = Any()

        var current: Any? = lateRefresh
        assertFalse(session.acceptRefresh(version, lateRefresh, current) { current = it })
        assertSame(newLogin, current)
        assertNotEquals(version, checkNotNull(session.version(newLogin)))
    }

    @Test
    fun missingCallbackTimesOutAndIgnoresLateCallbacks() = runTest {
        lateinit var callback: AccessToken.AccessTokenRefreshCallback
        val pending = async {
            runCatching { awaitFacebookSdkRefresh { callback = it } }.exceptionOrNull() as FacebookRefreshException
        }

        advanceTimeBy(SDK_REFRESH_TIMEOUT_MILLIS)
        assertFalse(pending.isCompleted)
        runCurrent()
        assertEquals(FacebookRefreshIssue.TIMEOUT, pending.await().issue)
        callback.OnTokenRefreshed(null)
        callback.OnTokenRefreshFailed(FacebookException("late"))
        assertEquals(FacebookRefreshIssue.TIMEOUT, pending.await().issue)
    }

    @Test
    fun cancelledWaitDropsCallbackWithoutContinuingRefresh() = runTest {
        lateinit var callback: AccessToken.AccessTokenRefreshCallback
        var continued = false
        val pending = async {
            awaitFacebookSdkRefresh { callback = it }
            continued = true
        }
        runCurrent()
        pending.cancelAndJoin()
        callback.OnTokenRefreshFailed(FacebookException("late"))
        callback.OnTokenRefreshed(null)
        runCurrent()

        assertTrue(pending.isCancelled)
        assertFalse(continued)
    }

    @Test
    fun transportErrorIsReportedAsNetworkAndDuplicateCallbackIsIgnored() = runTest {
        val error = runCatching {
            awaitFacebookSdkRefresh { callback ->
                callback.OnTokenRefreshFailed(FacebookException(IOException("test")))
                callback.OnTokenRefreshed(null)
            }
        }.exceptionOrNull() as FacebookRefreshException

        assertEquals(FacebookRefreshIssue.NETWORK, error.issue)
    }
}
