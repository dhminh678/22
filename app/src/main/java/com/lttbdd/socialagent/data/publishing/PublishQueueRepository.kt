package com.lttbdd.socialagent.data.publishing

import androidx.room.withTransaction
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.dao.DraftDao
import com.lttbdd.socialagent.core.database.dao.PostDao
import com.lttbdd.socialagent.core.database.entity.PostEntity
import com.lttbdd.socialagent.core.database.toModel
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.core.model.PublishResult
import com.lttbdd.socialagent.core.publishing.EnqueueResult
import com.lttbdd.socialagent.core.publishing.PublishErrorReason
import com.lttbdd.socialagent.core.publishing.PublishQueue
import com.lttbdd.socialagent.core.social.SocialChannelClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Tạo khóa cho lần đăng mới. Trả null nếu chưa xác định được đích đăng. */
fun interface PublishKeyFactory {
    suspend fun create(channel: ChannelType): String?
}

/** Nguồn thời gian, tách ra để test điều khiển được. */
fun interface PublishClock {
    fun nowMillis(): Long
}

/** Tổng kết một lượt xử lý hàng đợi, để worker quyết định có cần chạy lại không. */
data class QueueRunResult(
    val published: Int = 0,
    val retryLater: Int = 0,
    val failed: Int = 0,
    val needsConfirmation: Int = 0,
    /** Bài còn đang PUBLISHING chưa quá hạn sau lượt này — đã hẹn lượt kiểm tra trễ. */
    val inFlight: Int = 0,
    /**
     * Bài kênh yêu cầu chờ theo `Retry-After` (#39). Không tính vào [shouldRetry]: đã hẹn lượt chạy đúng lúc hết chờ,
     * để backoff của WorkManager không gọi lại sớm hơn.
     */
    val deferred: Int = 0,
) {
    val shouldRetry: Boolean get() = retryLater > 0
}

/**
 * Hàng đợi đăng bài F5 (#37 hàng đợi offline, #38 chống đăng trùng).
 *
 * - Bấm Đăng chỉ ghi bài [PostStatus.QUEUED] vào Room rồi hẹn [PublishScheduler]; worker chạy khi có mạng,
 *   nên mất mạng vẫn bấm đăng được.
 * - Chống trùng theo giao thức trong `docs/research-social-api.md`: giữ chỗ bằng [PostDao.claimForPublishing]
 *   (ghi `attemptStartedAtMillis` trước khi gọi API), chỉ tự thử lại khi chắc chắn request chưa được gửi,
 *   còn lỗi không rõ kết quả thì đánh dấu `outcomeUnknown` và chờ người dùng xác nhận — không tự đăng lại.
 * - Hợp đồng [SocialChannelClient] chưa có hàm đối soát feed, nên bước đối soát tự động chưa làm ở đây;
 *   người dùng xác nhận qua [confirmPublishedManually] hoặc [requeueAfterUserCheck].
 */
@Singleton
class PublishQueueRepository @Inject constructor(
    private val database: AppDatabase,
    private val postDao: PostDao,
    private val draftDao: DraftDao,
    private val clients: Set<@JvmSuppressWildcards SocialChannelClient>,
    private val scheduler: PublishScheduler,
    private val keyFactory: PublishKeyFactory = PublishKeyFactory { UUID.randomUUID().toString() },
    private val clock: PublishClock,
) : PublishQueue {

    fun observePosts(): Flow<List<PostEntity>> = postDao.observeAll()

    fun observePostsForDraft(draftId: String): Flow<List<PostEntity>> = postDao.observeByDraft(draftId)

    /**
     * Thêm bản nháp vào hàng đợi. Gọi nhiều lần (bấm hai lần) chỉ tạo một bài: kiểm tra và thêm nằm trong
     * cùng một transaction. Bài cũ [PostStatus.FAILED] không chặn — người dùng được đăng lại với khoá mới.
     */
    override suspend fun enqueue(draftId: String): EnqueueResult {
        while (true) {
            val channel = when (val check = database.withTransaction { checkEnqueue(draftId) }) {
                is EnqueueCheck.Blocked -> return check.result
                is EnqueueCheck.Ready -> check.channel
            }

            // Đọc tài khoản ngoài transaction: khôi phục OAuth có thể cần ghi DB.
            val publishKey = keyFactory.create(channel)
            val result = database.withTransaction {
                when (val current = checkEnqueue(draftId)) {
                    is EnqueueCheck.Blocked -> current.result
                    is EnqueueCheck.Ready -> {
                        // Nếu kênh đổi trong lúc tạo khóa, đọc lại đích theo kênh mới.
                        if (current.channel != channel) return@withTransaction null
                        if (publishKey == null) return@withTransaction EnqueueResult.DestinationRequired

                        val now = clock.nowMillis()
                        val post = PostEntity(
                            id = UUID.randomUUID().toString(),
                            draftId = draftId,
                            channel = channel,
                            status = PostStatus.QUEUED,
                            idempotencyKey = publishKey,
                            remotePostId = null,
                            url = null,
                            errorMessage = null,
                            attemptCount = 0,
                            createdAtMillis = now,
                            updatedAtMillis = now,
                        )
                        postDao.insertIfAbsent(post)
                        EnqueueResult.Queued(post.id)
                    }
                }
            } ?: continue
            if (result is EnqueueResult.Queued) scheduler.schedule()
            return result
        }
    }

    /** Kiểm tra bản nháp và bài đã có trong cùng một transaction của caller. */
    private suspend fun checkEnqueue(draftId: String): EnqueueCheck {
        val draft = draftDao.getById(draftId) ?: return EnqueueCheck.Blocked(EnqueueResult.DraftNotFound)
        if (!draft.approved) return EnqueueCheck.Blocked(EnqueueResult.NotApproved)

        val existing = postDao.getByDraft(draftId)
        existing.firstOrNull { it.status in ACTIVE_STATUSES }?.let {
            return EnqueueCheck.Blocked(EnqueueResult.AlreadyActive(it.id, it.status))
        }
        existing.firstOrNull { it.status == PostStatus.PUBLISHED }?.let {
            return EnqueueCheck.Blocked(EnqueueResult.AlreadyPublished(it.id, it.url))
        }
        return EnqueueCheck.Ready(draft.channel)
    }

    private sealed interface EnqueueCheck {
        data class Blocked(val result: EnqueueResult) : EnqueueCheck
        data class Ready(val channel: ChannelType) : EnqueueCheck
    }

    /**
     * Xử lý mọi bài [PostStatus.QUEUED]. Worker gọi hàm này; an toàn khi hai lượt chạy song song vì mỗi bài
     * phải được giữ chỗ thành công mới gọi API.
     */
    suspend fun processQueue(): QueueRunResult {
        val cleanup = database.channelCleanupDao()
        cleanup.finishDeferredCleanup()
        val now = clock.nowMillis()
        postDao.markStalePublishingAsUnknown(startedBeforeMillis = now - STALE_PUBLISHING_MILLIS, nowMillis = now)

        var result = QueueRunResult()
        for (post in postDao.getByStatus(listOf(PostStatus.QUEUED))) {
            result = when (publishOne(post.id)) {
                Outcome.PUBLISHED -> result.copy(published = result.published + 1)
                Outcome.RETRY_LATER -> result.copy(retryLater = result.retryLater + 1)
                Outcome.FAILED -> result.copy(failed = result.failed + 1)
                Outcome.NEEDS_CONFIRMATION -> result.copy(needsConfirmation = result.needsConfirmation + 1)
                Outcome.DEFERRED -> result.copy(deferred = result.deferred + 1)
                Outcome.SKIPPED -> result
            }
        }
        cleanup.finishDeferredCleanup()
        scheduleNextRetry()
        // Bài kẹt PUBLISHING chưa quá hạn (worker trước bị dừng giữa chừng) sẽ không còn ai xử lý nếu không hẹn
        // lượt sau: hẹn kiểm tra lại khi đã quá STALE_PUBLISHING_MILLIS để chuyển nó sang "chờ xác nhận".
        val inFlight = postDao.countInFlight()
        if (inFlight > 0) scheduler.scheduleStaleCheck(STALE_PUBLISHING_MILLIS + STALE_CHECK_MARGIN_MILLIS)
        return result.copy(inFlight = inFlight)
    }

    /**
     * Hẹn lượt chạy cho bài chờ `Retry-After` sớm nhất (#39), kể cả bài được hẹn từ lượt trước. Mốc đã qua nghĩa là
     * bài tới giờ trong lúc lượt này đang chạy (bị bỏ qua vì lúc giữ chỗ chưa tới giờ): hẹn ngay trên chuỗi chính,
     * vì work của đúng mốc đó có thể chính là lượt đang chạy và `KEEP` sẽ bỏ qua yêu cầu.
     */
    private suspend fun scheduleNextRetry() {
        val retryAt = postDao.earliestNextAttempt() ?: return
        val delay = retryAt - clock.nowMillis()
        if (delay > 0) scheduler.scheduleRetryAt(retryAt, delay) else scheduler.schedule()
    }

    /** Gọi khi app khởi động: hẹn worker để xử lý bài QUEUED/PUBLISHING còn sót (app bị tắt trước khi kịp hẹn). */
    fun resumePendingWork() {
        scheduler.scheduleIfIdle()
    }

    /** Người dùng xác nhận bài không rõ kết quả thật ra ĐÃ lên mạng xã hội. */
    suspend fun confirmPublishedManually(postId: String, url: String? = null): Boolean =
        database.withTransaction {
            val confirmed = postDao.resolveUnknownAsPublished(postId, url, clock.nowMillis()) == 1
            if (confirmed) database.channelCleanupDao().finishDeferredCleanup()
            confirmed
        }

    /**
     * Người dùng đã kiểm tra trên mạng xã hội và bài CHƯA được đăng: đưa lại vào hàng đợi với cùng
     * idempotencyKey (client vẫn phải chặn trùng theo hợp đồng).
     */
    suspend fun requeueAfterUserCheck(postId: String): Boolean {
        val requeued = database.withTransaction {
            val changed = postDao.requeueUnknown(postId, clock.nowMillis()) == 1
            if (changed) database.channelCleanupDao().retainPostForExplicitRetry(postId)
            changed
        }
        if (!requeued) return false
        scheduler.schedule()
        return true
    }

    private suspend fun publishOne(postId: String): Outcome {
        if (postDao.claimForPublishing(postId, clock.nowMillis()) == 0) return Outcome.SKIPPED
        // Lỗi đọc DB lúc này xảy ra trước khi gọi API: chắc chắn chưa gửi, nên trả bài về hàng đợi ngay thay vì
        // để nó kẹt PUBLISHING rồi bị coi là "không rõ kết quả" sau STALE_PUBLISHING_MILLIS.
        val (post, draft) = try {
            val post = postDao.getById(postId) ?: return Outcome.SKIPPED
            post to post.draftId?.let { draftDao.getById(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return releaseClaim(postId)
        }

        if (draft == null) return post.fail(REASON_DRAFT_MISSING)
        // Bản nháp có thể bị sửa và mất trạng thái duyệt sau khi vào hàng đợi: không được đăng.
        if (!draft.approved) return post.fail(REASON_NOT_APPROVED)
        val client = clients.firstOrNull { it.type == post.channel } ?: return post.fail(REASON_NO_CLIENT)

        val result = try {
            client.publish(draft.toModel(), post.idempotencyKey)
        } catch (e: TimeoutCancellationException) {
            // withTimeout hết giờ khi đang chờ response: request có thể đã tới server.
            return post.markOutcomeUnknown(e)
        } catch (e: CancellationException) {
            // Bài vẫn ở PUBLISHING; lượt sau sẽ coi là không rõ kết quả sau STALE_PUBLISHING_MILLIS.
            throw e
        } catch (e: UnknownHostException) {
            return post.requeueNotSent()
        } catch (e: ConnectException) {
            return post.requeueNotSent()
        } catch (e: SSLHandshakeException) {
            // Bắt tay TLS lỗi xảy ra trước khi ghi request: nội dung bài chưa rời máy.
            return post.requeueNotSent()
        } catch (e: Exception) {
            // Timeout, mất kết nối khi đang đọc response…: server có thể đã nhận bài.
            return post.markOutcomeUnknown(e)
        }

        return when (result) {
            is PublishResult.Success -> {
                postDao.update(
                    post.copy(
                        status = PostStatus.PUBLISHED,
                        remotePostId = result.remotePostId,
                        url = result.url,
                        errorMessage = null,
                        outcomeUnknown = false,
                        updatedAtMillis = clock.nowMillis(),
                    ),
                )
                Outcome.PUBLISHED
            }
            is PublishResult.RetryableError -> {
                val retryAfter = result.retryAfterMillis
                when {
                    post.attemptCount >= MAX_RETRYABLE_ATTEMPTS ->
                        post.fail("$REASON_RETRY_LIMIT (${result.reason})")
                    retryAfter != null && retryAfter > 0 -> {
                        // Retry-After quá dài (hoặc kênh trả giá trị sai) vẫn bị giới hạn để bài không treo mãi.
                        val wait = retryAfter.coerceAtMost(MAX_RETRY_AFTER_MILLIS)
                        post.requeue(result.reason, nextAttemptAtMillis = clock.nowMillis() + wait)
                        Outcome.DEFERRED
                    }
                    else -> post.requeue(result.reason)
                }
            }
            is PublishResult.PermanentError -> post.fail(result.reason)
        }
    }

    private suspend fun PostEntity.requeue(reason: String, nextAttemptAtMillis: Long? = null): Outcome {
        postDao.update(
            copy(
                status = PostStatus.QUEUED,
                errorMessage = reason,
                nextAttemptAtMillis = nextAttemptAtMillis,
                updatedAtMillis = clock.nowMillis(),
            ),
        )
        return Outcome.RETRY_LATER
    }

    /**
     * Request chắc chắn chưa tới server: hoàn lại lượt mà [PostDao.claimForPublishing] đã cộng, để mạng chập chờn
     * không làm bài chạm [MAX_RETRYABLE_ATTEMPTS] trước khi kênh thật sự trả lỗi.
     *
     * Hệ quả: lỗi "chưa gửi" kéo dài (ví dụ DNS hoặc base URL sai) giữ bài ở QUEUED mãi, worker thử lại theo
     * backoff mũ của WorkManager (tối đa 5 giờ/lần). #39 bổ sung ngưỡng theo thời gian và báo lý do cho người dùng.
     */
    private suspend fun PostEntity.requeueNotSent(): Outcome =
        copy(attemptCount = (attemptCount - 1).coerceAtLeast(0)).requeue(REASON_NOT_SENT)

    /** Trả lại chỗ giữ khi lỗi trước lúc gọi API. Nếu chính lệnh này cũng lỗi, bài đi theo đường xử lý bài kẹt. */
    private suspend fun releaseClaim(postId: String): Outcome {
        try {
            postDao.releaseClaim(postId, REASON_STORAGE, clock.nowMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // DB vẫn lỗi: worker thử lại theo backoff; bài PUBLISHING sẽ được đánh dấu cần xác nhận khi quá hạn.
        }
        return Outcome.RETRY_LATER
    }

    private suspend fun PostEntity.fail(reason: String): Outcome {
        postDao.update(copy(status = PostStatus.FAILED, errorMessage = reason, updatedAtMillis = clock.nowMillis()))
        return Outcome.FAILED
    }

    private suspend fun PostEntity.markOutcomeUnknown(error: Exception): Outcome {
        // Chỉ lưu tên lớp lỗi: message của exception có thể chứa URL kèm token.
        postDao.update(
            copy(
                outcomeUnknown = true,
                errorMessage = "$REASON_OUTCOME_UNKNOWN (${error::class.java.simpleName})",
                updatedAtMillis = clock.nowMillis(),
            ),
        )
        return Outcome.NEEDS_CONFIRMATION
    }

    private enum class Outcome { PUBLISHED, RETRY_LATER, FAILED, NEEDS_CONFIRMATION, DEFERRED, SKIPPED }

    companion object {
        /** Worker của WorkManager bị dừng sau tối đa 10 phút; quá mốc này mà còn PUBLISHING thì lượt đó đã chết. */
        const val STALE_PUBLISHING_MILLIS = 15 * 60 * 1000L
        private const val STALE_CHECK_MARGIN_MILLIS = 60 * 1000L

        /**
         * Số lần gọi API tối đa khi kênh trả lỗi an toàn để thử lại. Lần chưa gửi được request
         * (mất mạng, DNS, lỗi bắt tay TLS) không được tính vào `attemptCount`.
         */
        const val MAX_RETRYABLE_ATTEMPTS = 8

        /** Chờ tối đa theo `Retry-After` (#39); kênh trả lâu hơn thì vẫn thử lại sau mốc này. */
        const val MAX_RETRY_AFTER_MILLIS = 24 * 60 * 60 * 1000L

        /** Trạng thái chặn tạo bài mới cho cùng bản nháp. */
        private val ACTIVE_STATUSES = setOf(PostStatus.QUEUED, PostStatus.PUBLISHING, PostStatus.HANDED_OFF)

        // Lý do lưu trong errorMessage; UI (feature/publishing/PublishErrorMessages, #39) đổi sang chuỗi VI/EN,
        // không hiển thị nguyên văn. Mã kênh trả về: core/publishing/PublishErrorReason.
        const val REASON_NOT_SENT = PublishErrorReason.NETWORK_UNAVAILABLE
        const val REASON_STORAGE = "storage_error"
        const val REASON_OUTCOME_UNKNOWN = "outcome_unknown"
        const val REASON_DRAFT_MISSING = "draft_missing"
        const val REASON_NOT_APPROVED = "draft_not_approved"
        const val REASON_NO_CLIENT = "channel_not_supported"
        const val REASON_RETRY_LIMIT = "retry_limit_reached"
    }
}
