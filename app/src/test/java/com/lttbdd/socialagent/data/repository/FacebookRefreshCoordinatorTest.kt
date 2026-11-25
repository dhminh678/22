package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.data.model.FACEBOOK_REFRESH_RETRY
import com.lttbdd.socialagent.data.model.FacebookRefreshIssue
import com.lttbdd.socialagent.data.model.FacebookRefreshOutcome
import com.lttbdd.socialagent.data.model.FacebookRefreshSkip
import com.lttbdd.socialagent.data.model.FacebookUserToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FacebookRefreshCoordinatorTest {
    @Test
    fun networkAndTimeoutUseFixedCooldown() = runTest {
        var now = 0L
        val coordinator = FacebookRefreshCoordinator { now }
        val user = FacebookUserToken("session", Long.MAX_VALUE, null, "owner")
        suspend fun fail(issue: FacebookRefreshIssue) =
            coordinator.check("owner", { user }) { refresh ->
                refresh()
                FacebookRefreshOutcome(issues = setOf(issue))
            }
        val first = fail(FacebookRefreshIssue.NETWORK)
        assertEquals(FACEBOOK_REFRESH_RETRY, first.nextCheckMillis)
        now += first.nextCheckMillis
        val second = fail(FacebookRefreshIssue.TIMEOUT)
        assertEquals(FACEBOOK_REFRESH_RETRY, second.nextCheckMillis)
        val skipped = coordinator.check("owner", { error("Đang trong backoff.") }) {
            error("Đang trong backoff.")
        }
        assertEquals(FacebookRefreshSkip.COOLDOWN, skipped.outcome.skipped)
    }

    @Test
    fun cancelledAutomaticRefreshAllowsImmediateRetryInTheSameSession() = runTest {
        val coordinator = FacebookRefreshCoordinator { 0L }
        val user = FacebookUserToken("session", Long.MAX_VALUE, null, "owner")
        val started = CompletableDeferred<Unit>()
        val interrupted = async {
            coordinator.check("owner", { user }, sessionVersion = { 1L }) { refresh ->
                assertEquals(user, refresh())
                started.complete(Unit)
                awaitCancellation()
            }
        }

        started.await()
        interrupted.cancelAndJoin()

        val result = coordinator.check("owner", { user }, sessionVersion = { 1L }) { refresh ->
            assertEquals(user, refresh())
            FacebookRefreshOutcome(skipped = FacebookRefreshSkip.NO_CHANGES)
        }

        assertEquals(FacebookRefreshSkip.NO_CHANGES, result.outcome.skipped)
    }

    @Test
    fun storageFailureKeepsFixedAutomaticCooldown() = runTest {
        var now = 0L
        val coordinator = FacebookRefreshCoordinator { now }
        val user = FacebookUserToken("session", Long.MAX_VALUE, null, "owner")
        repeat(2) {
            val result = coordinator.check("owner", { user }) { refresh ->
                refresh()
                FacebookRefreshOutcome(
                    issues = setOf(FacebookRefreshIssue.STORAGE),
                    issuesByAccountId = mapOf("target" to FacebookRefreshIssue.STORAGE),
                )
            }
            assertEquals(FACEBOOK_REFRESH_RETRY, result.nextCheckMillis)
            now += result.nextCheckMillis
        }
    }

    @Test
    fun invalidSessionBlocksSdkUntilSdkSessionChanges() = runTest {
        var now = 0L
        var revision = 1L
        val coordinator = FacebookRefreshCoordinator { now }
        val user = FacebookUserToken("session", Long.MAX_VALUE, null, "owner")
        coordinator.check("owner", { user }, sessionVersion = { revision }) { refresh ->
            refresh()
            FacebookRefreshOutcome(issues = setOf(FacebookRefreshIssue.SESSION_INVALID))
        }
        now += 10 * FACEBOOK_REFRESH_RETRY
        val skipped = coordinator.check("owner", { error("Hãy đợi đăng nhập lại.") }, sessionVersion = { revision }) {
            error("Hãy đợi đăng nhập lại.")
        }
        assertEquals(FacebookRefreshSkip.WAITING_FOR_SESSION, skipped.outcome.skipped)

        revision++
        val fresh = coordinator.check("owner", { user }, sessionVersion = { revision }) { refresh ->
            assertEquals(user, refresh())
            FacebookRefreshOutcome(skipped = FacebookRefreshSkip.NO_CHANGES)
        }
        assertEquals(FacebookRefreshSkip.NO_CHANGES, fresh.outcome.skipped)
    }

    @Test
    fun rateLimitUsesFixedCooldownForAutomaticRetries() = runTest {
        val coordinator = FacebookRefreshCoordinator { 0L }
        val user = FacebookUserToken("session", Long.MAX_VALUE, null, "owner")
        val limited = coordinator.check("owner", { user }, sessionVersion = { 1L }) { refresh ->
            refresh()
            FacebookRefreshOutcome(issues = setOf(FacebookRefreshIssue.RATE_LIMITED))
        }
        val automatic = coordinator.check("owner", { error("Chờ giới hạn tốc độ.") }, sessionVersion = { 1L }) {
            error("Chờ giới hạn tốc độ.")
        }

        assertEquals(FACEBOOK_REFRESH_RETRY, limited.nextCheckMillis)
        assertEquals(FacebookRefreshSkip.COOLDOWN, automatic.outcome.skipped)
    }
}
