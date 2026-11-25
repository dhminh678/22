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

/**
 * Điều phối lượt gia hạn tự động: chỉ một lượt gọi Meta SDK tại một thời điểm, chờ cố định
 * [FACEBOOK_REFRESH_RETRY] giữa hai lượt của cùng một phiên, và dừng khi phiên cần đăng nhập lại.
 */
internal class FacebookRefreshCoordinator(private val elapsedRealtime: () -> Long) {
    private data class Session(val userId: String, val version: Long?)
    private data class Retry(val startedAt: Long, val delayMillis: Long)

    private val automaticMutex = Mutex()
    private val retryLock = Any()
    private val retries = mutableMapOf<Session, Retry>()
    private val blockedSessions = mutableMapOf<String, Session>()

    suspend fun check(
        userId: String?,
        refresh: suspend () -> FacebookUserToken?,
        sessionVersion: () -> Long? = { null },
        operation: suspend (suspend () -> FacebookUserToken?) -> FacebookRefreshOutcome,
    ): FacebookRefreshCheck {
        if (userId == null) return skipped(FacebookRefreshSkip.NO_SDK_SESSION)
        val session = Session(userId, sessionVersion())
        return automaticMutex.withLock {
            runCheck(session, sessionVersion) { begin ->
                operation { if (begin()) refresh() else null }
            }
        }
    }

    private suspend fun runCheck(
        session: Session,
        sessionVersion: () -> Long?,
        operation: suspend (begin: () -> Boolean) -> FacebookRefreshOutcome,
    ): FacebookRefreshCheck {
        val now = elapsedRealtime()
        synchronized(retryLock) {
            if (session.version != sessionVersion()) return skipped(FacebookRefreshSkip.SDK_SESSION_CHANGED)
            retries.values.removeAll { now - it.startedAt >= RETRY_HISTORY_MILLIS }
            if (blockedSessions[session.userId] == session) return skipped(FacebookRefreshSkip.WAITING_FOR_SESSION)
            blockedSessions.remove(session.userId)

            val remaining = remainingDelay(retries[session])
            if (remaining > 0L) return skipped(FacebookRefreshSkip.COOLDOWN, remaining)
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
                // Lượt bị hủy chưa có kết quả để tính thời gian chờ.
                val completed = outcome
                if (completed != null && completed.skipped != FacebookRefreshSkip.SDK_SESSION_CHANGED &&
                    session.version == sessionVersion() &&
                    (attemptedAt != null || completed.batchIssues.isNotEmpty())
                ) {
                    recordRetry(session, completed, attemptedAt ?: now)
                }
            }
        }

        val nextCheck = synchronized(retryLock) { remainingDelay(retries[session]) }
        return FacebookRefreshCheck(checkNotNull(outcome), nextCheck.takeIf { it > 0L } ?: FACEBOOK_REFRESH_RETRY)
    }

    private fun recordRetry(session: Session, outcome: FacebookRefreshOutcome, startedAt: Long) {
        if (outcome.batchIssues.any { it in terminalIssues }) {
            blockedSessions[session.userId] = session
            return
        }
        retries[session] = Retry(startedAt, FACEBOOK_REFRESH_RETRY)
    }

    private fun remainingDelay(retry: Retry?): Long =
        retry?.let { (it.delayMillis - (elapsedRealtime() - it.startedAt)).coerceAtLeast(0) } ?: 0

    private fun skipped(reason: FacebookRefreshSkip, delay: Long = FACEBOOK_REFRESH_RETRY) =
        FacebookRefreshCheck(FacebookRefreshOutcome(skipped = reason), delay)

    private companion object {
        const val RETRY_HISTORY_MILLIS = 24 * 60 * 60_000L
        val terminalIssues = setOf(
            FacebookRefreshIssue.SESSION_INVALID,
            FacebookRefreshIssue.USER_SESSION_EXPIRED,
            FacebookRefreshIssue.ACCOUNT_MISMATCH,
        )
    }
}
