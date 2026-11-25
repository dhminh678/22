package com.lttbdd.socialagent.data.repository

import com.lttbdd.socialagent.data.model.FACEBOOK_REFRESH_RETRY
import com.lttbdd.socialagent.data.model.FacebookRefreshCheck
import com.lttbdd.socialagent.data.model.FacebookRefreshIssue
import com.lttbdd.socialagent.data.model.FacebookRefreshOutcome
import com.lttbdd.socialagent.data.model.FacebookRefreshSkip
import com.lttbdd.socialagent.data.model.FacebookUserToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class FacebookRefreshCoordinator(private val elapsedRealtime: () -> Long) {
    private data class Session(val userId: String, val version: Long?)
    private data class Request(val session: Session, val pageId: String?)
    private data class Retry(val startedAt: Long, val delayMillis: Long, val failures: Int = 0)

    // Chỉ lượt tự động gọi Meta SDK. Retry Page dùng token hiện tại và có thể tải song song.
    private val automaticMutex = Mutex()
    private val retryLock = Any()
    private val retries = mutableMapOf<Request, Retry>()
    private val rateLimits = mutableMapOf<Session, Retry>()
    private val blockedSessions = mutableMapOf<String, Session>()
    private val inFlight = mutableSetOf<Request>()

    suspend fun check(
        userId: String?,
        refresh: suspend () -> FacebookUserToken?,
        sessionVersion: () -> Long? = { null },
        operation: suspend (suspend () -> FacebookUserToken?) -> FacebookRefreshOutcome,
    ): FacebookRefreshCheck {
        if (userId == null) return skipped(FacebookRefreshSkip.NO_SDK_SESSION)
        val request = Request(Session(userId, sessionVersion()), pageId = null)
        return automaticMutex.withLock {
            runCheck(request, sessionVersion) { begin ->
                operation { if (begin()) refresh() else null }
            }
        }
    }

    suspend fun checkPage(
        userId: String?,
        pageId: String,
        version: Long,
        sessionVersion: () -> Long?,
        operation: suspend (begin: () -> Boolean) -> FacebookRefreshOutcome,
    ): FacebookRefreshCheck {
        if (userId == null) return skipped(FacebookRefreshSkip.NO_SDK_SESSION)
        return runCheck(Request(Session(userId, version), pageId), sessionVersion, operation)
    }

    private suspend fun runCheck(
        request: Request,
        sessionVersion: () -> Long?,
        operation: suspend (begin: () -> Boolean) -> FacebookRefreshOutcome,
    ): FacebookRefreshCheck {
        val session = request.session
        val baseDelay = if (request.pageId == null) FACEBOOK_REFRESH_RETRY else MANUAL_RETRY_MILLIS
        val now = elapsedRealtime()
        val previous = synchronized(retryLock) {
            if (session.version != sessionVersion()) return skipped(FacebookRefreshSkip.SDK_SESSION_CHANGED)
            retries.values.removeAll { now - it.startedAt >= RETRY_HISTORY_MILLIS }
            rateLimits.values.removeAll { remainingDelay(it) == 0L }
            if (blockedSessions[session.userId] == session) return skipped(FacebookRefreshSkip.WAITING_FOR_SESSION)
            blockedSessions.remove(session.userId)

            val retry = retries[request]
            val remaining = maxOf(remainingDelay(retry), remainingDelay(rateLimits[session]))
            if (remaining > 0L) return skipped(FacebookRefreshSkip.COOLDOWN, remaining)
            if (!inFlight.add(request)) return skipped(FacebookRefreshSkip.COOLDOWN, baseDelay)
            retry
        }

        var attemptedAt: Long? = null
        var outcome: FacebookRefreshOutcome? = null
        try {
            outcome = operation {
                if (session.version != sessionVersion()) {
                    false
                } else {
                    attemptedAt = elapsedRealtime()
                    true
                }
            }.also { currentCoroutineContext().ensureActive() }
        } finally {
            synchronized(retryLock) {
                inFlight.remove(request)
                // Lượt bị hủy chưa có kết quả để tính thời gian chờ.
                val completed = outcome
                if (completed != null && completed.skipped != FacebookRefreshSkip.SDK_SESSION_CHANGED &&
                    session.version == sessionVersion() &&
                    (attemptedAt != null || completed.batchIssues.isNotEmpty())
                ) {
                    recordRetry(request, completed, previous, attemptedAt ?: now, baseDelay)
                }
            }
        }

        val nextCheck = synchronized(retryLock) {
            maxOf(remainingDelay(retries[request]), remainingDelay(rateLimits[session]))
        }
        return FacebookRefreshCheck(checkNotNull(outcome), nextCheck.takeIf { it > 0L } ?: baseDelay)
    }

    private fun recordRetry(
        request: Request,
        outcome: FacebookRefreshOutcome,
        previous: Retry?,
        startedAt: Long,
        baseDelay: Long,
    ) {
        val issues = outcome.batchIssues
        val session = request.session
        if (issues.any { it in terminalIssues }) {
            blockedSessions[session.userId] = session
            return
        }
        if (FacebookRefreshIssue.RATE_LIMITED in issues) {
            rateLimits[session] = Retry(elapsedRealtime(), 2 * FACEBOOK_REFRESH_RETRY)
        }
        if (issues.any { it in backoffIssues }) {
            val failures = ((previous?.failures ?: 0) + 1).coerceAtMost(4)
            val delay = FACEBOOK_REFRESH_RETRY * (1L shl (failures - 1))
            retries[request] = Retry(startedAt, delay.coerceAtMost(MAX_RETRY_MILLIS), failures)
        } else {
            retries[request] = Retry(startedAt, baseDelay)
        }
    }

    private fun remainingDelay(retry: Retry?): Long =
        retry?.let { (it.delayMillis - (elapsedRealtime() - it.startedAt)).coerceAtLeast(0) } ?: 0

    private fun skipped(reason: FacebookRefreshSkip, delay: Long = FACEBOOK_REFRESH_RETRY) =
        FacebookRefreshCheck(FacebookRefreshOutcome(skipped = reason), delay)

    private companion object {
        const val MANUAL_RETRY_MILLIS = 30_000L
        const val MAX_RETRY_MILLIS = 2 * 60 * 60_000L
        const val RETRY_HISTORY_MILLIS = 24 * 60 * 60_000L
        val terminalIssues = setOf(
            FacebookRefreshIssue.SESSION_INVALID,
            FacebookRefreshIssue.USER_SESSION_EXPIRED,
            FacebookRefreshIssue.ACCOUNT_MISMATCH,
        )
        val backoffIssues = setOf(
            FacebookRefreshIssue.NETWORK,
            FacebookRefreshIssue.TIMEOUT,
            FacebookRefreshIssue.PROVIDER_UNAVAILABLE,
            FacebookRefreshIssue.SDK_UNAVAILABLE,
            FacebookRefreshIssue.STORAGE,
            FacebookRefreshIssue.PAGES_UNAVAILABLE,
        )
    }
}
