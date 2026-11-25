package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.data.model.FACEBOOK_REFRESH_RETRY
import com.lttbdd.socialagent.data.model.FacebookRefreshIssue
import com.lttbdd.socialagent.data.model.FacebookRefreshOutcome
import com.lttbdd.socialagent.data.model.FacebookRefreshSkip
import com.lttbdd.socialagent.data.model.FacebookUserToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacebookRefreshCoordinatorTest {
    @Test
    fun networkAndTimeoutUseIncreasingBackoff() = runTest {
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
        assertEquals(2 * FACEBOOK_REFRESH_RETRY, second.nextCheckMillis)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun manualPageRetryCompletesWhileAutomaticGraphRequestIsSuspended() = runTest {
        val coordinator = FacebookRefreshCoordinator { testScheduler.currentTime }
        val user = FacebookUserToken("session", Long.MAX_VALUE, null, "owner")
        val automaticGraphStarted = CompletableDeferred<Unit>()
        val releaseAutomaticGraph = CompletableDeferred<Unit>()
        val automatic = async {
            coordinator.check("owner", { user }) { refresh ->
                assertEquals(user, refresh())
                automaticGraphStarted.complete(Unit)
                releaseAutomaticGraph.await()
                FacebookRefreshOutcome(skipped = FacebookRefreshSkip.NO_CHANGES)
            }
        }
        try {
            automaticGraphStarted.await()

            val manual = withTimeout(1_000L) {
                coordinator.checkPage("owner", "target", 1L, { 1L }) { begin ->
                    assertTrue(begin())
                    FacebookRefreshOutcome(confirmedPageIds = setOf("target"))
                }
            }

            assertEquals(setOf("target"), manual.outcome.confirmedPageIds)
            assertFalse(automatic.isCompleted)
        } finally {
            releaseAutomaticGraph.complete(Unit)
        }
        automatic.await()
    }

    @Test
    fun isolatedStorageFailureDoesNotIncreaseAutomaticBackoff() = runTest {
        var now = 0L
        val coordinator = FacebookRefreshCoordinator { now }
        val user = FacebookUserToken("session", Long.MAX_VALUE, null, "owner")
        repeat(2) {
            val result = coordinator.check("owner", { user }) { refresh ->
                refresh()
                FacebookRefreshOutcome(
                    issues = setOf(FacebookRefreshIssue.STORAGE),
                    issuesByAccountId = mapOf("target" to FacebookRefreshIssue.STORAGE),
                    confirmedPageIds = setOf("healthy"),
                )
            }
            assertEquals(FACEBOOK_REFRESH_RETRY, result.nextCheckMillis)
            now += result.nextCheckMillis
        }
    }

    @Test
    fun missingPageUsesCooldownOnlyForItsManualRetry() = runTest {
        val coordinator = FacebookRefreshCoordinator { 0L }
        suspend fun retry(page: String) = coordinator.checkPage("owner", page, 1L, { 1L }) { begin ->
            assertTrue(begin())
            FacebookRefreshOutcome(
                issues = setOf(FacebookRefreshIssue.PAGE_NOT_RETURNED),
                issuesByAccountId = mapOf(page to FacebookRefreshIssue.PAGE_NOT_RETURNED),
            )
        }

        assertEquals(30_000L, retry("first").nextCheckMillis)
        assertEquals(FacebookRefreshSkip.COOLDOWN, retry("first").outcome.skipped)
        assertEquals(FacebookRefreshIssue.PAGE_NOT_RETURNED, retry("second").outcome.issuesByAccountId["second"])
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
    fun rateLimitBlocksBothTargetedAndAutomaticRetries() = runTest {
        val coordinator = FacebookRefreshCoordinator { 0L }
        coordinator.checkPage("owner", "page", 1L, { 1L }) { begin ->
            assertTrue(begin())
            FacebookRefreshOutcome(issues = setOf(FacebookRefreshIssue.RATE_LIMITED))
        }
        val automatic = coordinator.check("owner", { error("Chờ giới hạn tốc độ.") }, sessionVersion = { 1L }) {
            error("Chờ giới hạn tốc độ.")
        }

        assertEquals(FacebookRefreshSkip.COOLDOWN, automatic.outcome.skipped)
        assertEquals(2 * FACEBOOK_REFRESH_RETRY, automatic.nextCheckMillis)
    }
}
