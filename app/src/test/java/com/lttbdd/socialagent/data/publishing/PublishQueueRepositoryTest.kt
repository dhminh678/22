package com.lttbdd.socialagent.data.publishing

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.dao.PostDao
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.database.entity.PostEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Comment
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PostMetrics
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.core.model.PublishResult
import com.lttbdd.socialagent.core.publishing.EnqueueResult
import com.lttbdd.socialagent.core.social.SocialChannelClient
import com.lttbdd.socialagent.data.model.OAuthToken
import com.lttbdd.socialagent.data.social.PublishDestinations
import com.lttbdd.socialagent.data.social.PublishingAccounts
import com.lttbdd.socialagent.data.social.checkPublishHttpStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Test hàng đợi đăng bài (#37) và chống đăng trùng (#38) trên Room in-memory, kênh giả điều khiển được.
 * Các ca chống trùng theo mục "Yêu cầu Unit Test bắt buộc" trong docs/research-social-api.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PublishQueueRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var scheduler: CountingScheduler
    private lateinit var facebook: ScriptedClient
    private var nowMillis = 1_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        scheduler = CountingScheduler()
        facebook = ScriptedClient(ChannelType.FACEBOOK)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repository(
        postDao: PostDao = db.postDao(),
        clients: Set<SocialChannelClient> = setOf(facebook),
        keyFactory: PublishKeyFactory? = null,
    ) = if (keyFactory == null) {
        PublishQueueRepository(db, postDao, db.draftDao(), clients, scheduler) { nowMillis }
    } else {
        PublishQueueRepository(db, postDao, db.draftDao(), clients, scheduler, keyFactory) { nowMillis }
    }

    private suspend fun saveDraft(
        id: String = "d1",
        approved: Boolean = true,
        channel: ChannelType = ChannelType.FACEBOOK,
    ) {
        db.draftDao().upsert(
            DraftEntity(
                id = id,
                topicId = "topic-$id",
                channel = channel,
                text = "Nội dung $id",
                imageUri = null,
                imageAltText = null,
                approved = approved,
                updatedAtMillis = nowMillis,
            ),
        )
    }

    private suspend fun post(id: String) = db.postDao().getById(id)!!

    private suspend fun queuedId(repo: PublishQueueRepository, draftId: String = "d1") =
        (repo.enqueue(draftId) as EnqueueResult.Queued).postId

    // ---------- #37: thêm vào hàng đợi ----------

    @Test
    fun `enqueue approved draft stores queued post and schedules worker`() = runTest {
        saveDraft()
        val repo = repository()

        val id = queuedId(repo)

        val saved = post(id)
        assertEquals(PostStatus.QUEUED, saved.status)
        assertEquals(ChannelType.FACEBOOK, saved.channel)
        assertEquals(0, saved.attemptCount)
        assertEquals(1, scheduler.calls)
        assertEquals("Chưa gọi API khi mới bấm đăng", 0, facebook.calls)
    }

    @Test
    fun `worker uses original destination key after repository restart`() = runTest {
        saveDraft()
        val id = queuedId(repository(keyFactory = PublishKeyFactory { "bound-to-page" }))
        assertEquals("bound-to-page", post(id).idempotencyKey)
        assertEquals(0, facebook.calls)

        val result = repository(keyFactory = PublishKeyFactory { error("Không tạo lại khóa khi xử lý hàng đợi") })
            .processQueue()

        assertEquals(1, result.published)
        assertEquals(listOf("bound-to-page"), facebook.keys)
        assertEquals(PostStatus.PUBLISHED, post(id).status)
    }

    @Test
    fun `enqueue requires exactly one publishing account for Facebook and LinkedIn`() = runTest {
        val candidates = mapOf(
            ChannelType.FACEBOOK to listOf("123", "456").map {
                it to OAuthToken("test-page", null, isFacebookProfile = false)
            },
            ChannelType.LINKEDIN to listOf("member-1", "member-2").map {
                it to OAuthToken("test-member", null, "w_member_social")
            },
        )
        for ((channel, connected) in candidates) {
            val draftId = "draft-${channel.name}"
            saveDraft(id = draftId, channel = channel)
            var accounts = connected
            val destinations = PublishDestinations(PublishingAccounts { accounts })
            val repo = repository(
                clients = setOf(ScriptedClient(channel)),
                keyFactory = PublishKeyFactory(destinations::newKey),
            )
            val scheduledBefore = scheduler.calls

            assertEquals(EnqueueResult.DestinationRequired, repo.enqueue(draftId))
            accounts = emptyList()
            assertEquals(EnqueueResult.DestinationRequired, repo.enqueue(draftId))
            assertTrue(db.postDao().getByDraft(draftId).isEmpty())
            assertEquals(scheduledBefore, scheduler.calls)

            accounts = connected.take(1)
            val postId = queuedId(repo, draftId)
            assertEquals(accounts.single(), destinations.resolve(channel, post(postId).idempotencyKey))
            assertEquals(scheduledBefore + 1, scheduler.calls)

            accounts = connected
            assertEquals(EnqueueResult.AlreadyActive(postId, PostStatus.QUEUED), repo.enqueue(draftId))
        }
    }

    @Test
    fun `enqueue skips account lookup for missing unapproved active and published drafts`() = runTest {
        val repo = repository(keyFactory = PublishKeyFactory { error("Không cần đọc tài khoản") })
        assertEquals(EnqueueResult.DraftNotFound, repo.enqueue("d1"))
        saveDraft(approved = false)
        assertEquals(EnqueueResult.NotApproved, repo.enqueue("d1"))

        saveDraft()
        val postId = queuedId(repository())
        assertEquals(EnqueueResult.AlreadyActive(postId, PostStatus.QUEUED), repo.enqueue("d1"))
        db.postDao().update(post(postId).copy(status = PostStatus.PUBLISHED, url = "https://example.test/post"))
        assertEquals(EnqueueResult.AlreadyPublished(postId, "https://example.test/post"), repo.enqueue("d1"))
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `enqueue recalculates key when approved draft changes channel during destination lookup`() = runTest {
        saveDraft()
        val reads = mutableListOf<ChannelType>()
        val repo = repository(keyFactory = PublishKeyFactory { channel ->
            reads += channel
            if (channel == ChannelType.FACEBOOK) {
                saveDraft(channel = ChannelType.LINKEDIN)
                null
            } else {
                "linkedin-key"
            }
        })

        val queued = post(queuedId(repo))
        assertEquals(listOf(ChannelType.FACEBOOK, ChannelType.LINKEDIN), reads)
        assertEquals(ChannelType.LINKEDIN, queued.channel)
        assertEquals("linkedin-key", queued.idempotencyKey)
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `enqueue rechecks approval and existing rows after account lookup`() = runTest {
        saveDraft()
        val revoked = repository(keyFactory = PublishKeyFactory {
            saveDraft(approved = false)
            "old-key"
        })
        assertEquals(EnqueueResult.NotApproved, revoked.enqueue("d1"))
        assertTrue(db.postDao().getByDraft("d1").isEmpty())

        saveDraft()
        var queuedByOtherCaller = ""
        val concurrent = repository(keyFactory = PublishKeyFactory {
            queuedByOtherCaller = queuedId(repository())
            null
        })
        val result = concurrent.enqueue("d1")
        assertEquals(EnqueueResult.AlreadyActive(queuedByOtherCaller, PostStatus.QUEUED), result)
        assertEquals(1, db.postDao().getByDraft("d1").size)
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `enqueue unapproved draft is rejected without creating post`() = runTest {
        saveDraft(approved = false)
        val repo = repository()

        assertEquals(EnqueueResult.NotApproved, repo.enqueue("d1"))
        assertTrue(db.postDao().getByDraft("d1").isEmpty())
        assertEquals(0, scheduler.calls)
    }

    @Test
    fun `enqueue missing draft returns DraftNotFound`() = runTest {
        assertEquals(EnqueueResult.DraftNotFound, repository().enqueue("missing"))
        assertEquals(0, scheduler.calls)
    }

    @Test
    fun `offline publish stays queued and publishes with same key when network returns`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        facebook.next(UnknownHostException("offline"))

        val offline = repo.processQueue()

        assertEquals(1, offline.retryLater)
        assertTrue(offline.shouldRetry)
        assertEquals(PostStatus.QUEUED, post(id).status)
        assertEquals(PublishQueueRepository.REASON_NOT_SENT, post(id).errorMessage)

        facebook.next(PublishResult.Success("fb-1", "https://facebook.com/fb-1"))
        val online = repo.processQueue()

        assertEquals(1, online.published)
        assertFalse(online.shouldRetry)
        val published = post(id)
        assertEquals(PostStatus.PUBLISHED, published.status)
        assertEquals("fb-1", published.remotePostId)
        assertEquals("https://facebook.com/fb-1", published.url)
        assertNull(published.errorMessage)
        assertEquals("Lần mất mạng không được tính là một lần thử", 1, published.attemptCount)
        assertEquals("Thử lại phải dùng lại đúng idempotencyKey", 1, facebook.keys.toSet().size)
    }

    @Test
    fun `retryable error from client keeps post queued for backoff`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        // Có Retry-After thì đi đường chờ riêng (#39): xem `rate limit with Retry-After waits…`.
        facebook.next(PublishResult.RetryableError("rate_limited"))

        val result = repo.processQueue()

        assertEquals(1, result.retryLater)
        assertEquals(PostStatus.QUEUED, post(id).status)
        assertEquals("rate_limited", post(id).errorMessage)
    }

    @Test
    fun `permanent error marks post failed and does not retry`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        facebook.next(PublishResult.PermanentError("missing_permission"))

        val result = repo.processQueue()

        assertEquals(1, result.failed)
        assertFalse(result.shouldRetry)
        assertEquals(PostStatus.FAILED, post(id).status)
        assertEquals("missing_permission", post(id).errorMessage)
        repo.processQueue()
        assertEquals(1, facebook.calls)
    }

    @Test
    fun `missing client for channel fails post`() = runTest {
        saveDraft(channel = ChannelType.LINKEDIN)
        val repo = repository()
        val id = queuedId(repo)

        repo.processQueue()

        assertEquals(PostStatus.FAILED, post(id).status)
        assertEquals(PublishQueueRepository.REASON_NO_CLIENT, post(id).errorMessage)
    }

    @Test
    fun `draft unapproved after enqueue is not published`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        saveDraft(approved = false)

        repo.processQueue()

        assertEquals(0, facebook.calls)
        assertEquals(PostStatus.FAILED, post(id).status)
        assertEquals(PublishQueueRepository.REASON_NOT_APPROVED, post(id).errorMessage)
    }

    // ---------- #38: chống đăng trùng ----------

    @Test
    fun `double tap creates only one post`() = runTest {
        saveDraft()
        val repo = repository()

        val first = repo.enqueue("d1") as EnqueueResult.Queued
        val second = repo.enqueue("d1")

        assertEquals(EnqueueResult.AlreadyActive(first.postId, PostStatus.QUEUED), second)
        assertEquals(1, db.postDao().getByDraft("d1").size)
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `concurrent taps create only one post`() = runTest {
        saveDraft()
        val repo = repository()

        val results = List(5) { async { repo.enqueue("d1") } }.awaitAll()

        assertEquals(1, results.count { it is EnqueueResult.Queued })
        assertEquals(1, db.postDao().getByDraft("d1").size)
    }

    @Test
    fun `enqueue after published returns existing post`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        facebook.next(PublishResult.Success("fb-1", "https://facebook.com/fb-1"))
        repo.processQueue()

        val again = repo.enqueue("d1")

        assertEquals(EnqueueResult.AlreadyPublished(id, "https://facebook.com/fb-1"), again)
        assertEquals(1, db.postDao().getByDraft("d1").size)
    }

    @Test
    fun `enqueue after failure creates new post with new key`() = runTest {
        saveDraft()
        val repo = repository()
        val failedId = queuedId(repo)
        facebook.next(PublishResult.PermanentError("content_rejected"))
        repo.processQueue()

        val retryId = queuedId(repo)

        assertNotEquals(failedId, retryId)
        assertNotEquals(post(failedId).idempotencyKey, post(retryId).idempotencyKey)
    }

    @Test
    fun `in-flight post blocks new enqueue even with older published and failed posts`() = runTest {
        saveDraft()
        val postDao = db.postDao()
        postDao.insertIfAbsent(existingPost("p-published", PostStatus.PUBLISHED, createdAt = 1))
        postDao.insertIfAbsent(existingPost("p-failed", PostStatus.FAILED, createdAt = 2))
        postDao.insertIfAbsent(existingPost("p-publishing", PostStatus.PUBLISHING, createdAt = 3))

        val result = repository().enqueue("d1")

        assertEquals(EnqueueResult.AlreadyActive("p-publishing", PostStatus.PUBLISHING), result)
        assertEquals(3, postDao.getByDraft("d1").size)
    }

    @Test
    fun `timeout with unknown outcome is not republished automatically`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        facebook.next(SocketTimeoutException("read timed out"))

        val result = repo.processQueue()
        repo.processQueue()

        assertEquals(1, result.needsConfirmation)
        assertEquals(1, facebook.calls)
        val pending = post(id)
        assertEquals(PostStatus.PUBLISHING, pending.status)
        assertTrue(pending.outcomeUnknown)
        assertEquals("outcome_unknown (SocketTimeoutException)", pending.errorMessage)
        assertTrue(repo.enqueue("d1") is EnqueueResult.AlreadyActive)
    }

    @Test
    fun `server error after publish does not schedule another attempt`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        val failure = runCatching { checkPublishHttpStatus(503, "3", nowMillis) }.exceptionOrNull()
        facebook.next(checkNotNull(failure))

        assertEquals(1, repo.processQueue().needsConfirmation)
        assertFalse(repo.processQueue().shouldRetry)
        assertEquals(PostStatus.PUBLISHING, post(id).status)
        assertTrue(post(id).outcomeUnknown)
        assertNull(post(id).nextAttemptAtMillis)
        assertEquals(1, facebook.calls)
        assertEquals(1, scheduler.calls)
    }

    @Test
    fun `user confirms unknown post was published`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        facebook.next(SocketTimeoutException())
        repo.processQueue()

        assertTrue(repo.confirmPublishedManually(id, url = "https://facebook.com/manual"))

        assertEquals(PostStatus.PUBLISHED, post(id).status)
        assertFalse(post(id).outcomeUnknown)
        assertEquals("https://facebook.com/manual", post(id).url)
        assertEquals(1, facebook.calls)
    }

    @Test
    fun `user check says not published so post is retried exactly once with same key`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        facebook.next(SocketTimeoutException())
        repo.processQueue()
        val schedulesBefore = scheduler.calls

        assertTrue(repo.requeueAfterUserCheck(id))
        facebook.next(PublishResult.Success("fb-2", "https://facebook.com/fb-2"))
        repo.processQueue()
        repo.processQueue()

        assertEquals(schedulesBefore + 1, scheduler.calls)
        assertEquals(2, facebook.calls)
        assertEquals(1, facebook.keys.toSet().size)
        assertEquals(PostStatus.PUBLISHED, post(id).status)
    }

    @Test
    fun `confirm and requeue are refused for posts that do not need confirmation`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)

        assertFalse(repo.confirmPublishedManually(id))
        assertFalse(repo.requeueAfterUserCheck(id))
        assertFalse(repo.requeueAfterUserCheck("missing"))
        assertEquals(PostStatus.QUEUED, post(id).status)
    }

    @Test
    fun `stale publishing post from killed process is marked unknown and not republished`() = runTest {
        saveDraft()
        val postDao = db.postDao()
        postDao.insertIfAbsent(
            existingPost("stale", PostStatus.PUBLISHING, createdAt = 1)
                .copy(attemptStartedAtMillis = nowMillis - PublishQueueRepository.STALE_PUBLISHING_MILLIS - 1),
        )
        saveDraft(id = "d2")
        postDao.insertIfAbsent(
            existingPost("fresh", PostStatus.PUBLISHING, createdAt = 2, draftId = "d2")
                .copy(attemptStartedAtMillis = nowMillis - 1_000),
        )

        repository().processQueue()

        assertTrue(post("stale").outcomeUnknown)
        assertFalse("Lượt đang chạy của worker khác không bị đụng tới", post("fresh").outcomeUnknown)
        assertEquals(0, facebook.calls)
    }

    @Test
    fun `api success but database write failure never publishes twice`() = runTest {
        saveDraft()
        val failingDao = FailingUpdatePostDao(db.postDao())
        val repo = repository(postDao = failingDao)
        val id = queuedId(repo)
        facebook.next(PublishResult.Success("fb-1", "https://facebook.com/fb-1"))
        failingDao.failNextUpdate = true

        runCatching { repo.processQueue() }
        assertEquals(PostStatus.PUBLISHING, post(id).status)

        repo.processQueue()
        nowMillis += PublishQueueRepository.STALE_PUBLISHING_MILLIS + 1
        repo.processQueue()

        assertEquals("Không gọi API lần hai khi chưa rõ kết quả", 1, facebook.calls)
        assertTrue(post(id).outcomeUnknown)
    }

    @Test
    fun `two workers running at once publish only once`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        val gate = CompletableDeferred<Unit>()
        facebook.gate = gate

        val first = async { repo.processQueue() }
        val second = async { repo.processQueue() }
        while (facebook.calls == 0) yield()
        gate.complete(Unit)
        val results = listOf(first.await(), second.await())

        assertEquals(1, facebook.calls)
        assertEquals(1, results.sumOf { it.published })
        assertEquals(PostStatus.PUBLISHED, post(id).status)
    }

    // ---------- Sửa theo review #93 ----------

    @Test
    fun `worker rerun before stale window schedules delayed check then post becomes confirmable`() = runTest {
        saveDraft()
        val postDao = db.postDao()
        // Worker trước bị dừng khi đang gọi API vài giây trước; WorkManager chạy lại ngay.
        postDao.insertIfAbsent(
            existingPost("interrupted", PostStatus.PUBLISHING, createdAt = 1)
                .copy(attemptStartedAtMillis = nowMillis - 5_000),
        )
        val repo = repository()

        val rerun = repo.processQueue()

        assertEquals(1, rerun.inFlight)
        assertEquals(1, scheduler.staleCheckDelays.size)
        assertTrue(
            "Lượt kiểm tra phải chạy sau khi bài đã quá hạn",
            scheduler.staleCheckDelays.single() > PublishQueueRepository.STALE_PUBLISHING_MILLIS,
        )
        assertFalse(repo.confirmPublishedManually("interrupted"))

        nowMillis += scheduler.staleCheckDelays.single()
        val check = repo.processQueue()

        assertEquals(0, check.inFlight)
        assertTrue(post("interrupted").outcomeUnknown)
        assertTrue(repo.confirmPublishedManually("interrupted"))
        assertEquals(0, facebook.calls)
    }

    @Test
    fun `no stale check is scheduled when nothing is in flight`() = runTest {
        saveDraft()
        val repo = repository()
        queuedId(repo)

        repo.processQueue()

        assertTrue(scheduler.staleCheckDelays.isEmpty())
    }

    @Test
    fun `confirm and requeue pressed at the same time only one wins`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        facebook.next(SocketTimeoutException())
        repo.processQueue()

        val confirm = async { repo.confirmPublishedManually(id) }
        val requeue = async { repo.requeueAfterUserCheck(id) }
        val confirmed = confirm.await()
        val requeued = requeue.await()

        assertTrue("Đúng một thao tác được áp dụng", confirmed xor requeued)
        val expected = if (confirmed) PostStatus.PUBLISHED else PostStatus.QUEUED
        assertEquals(expected, post(id).status)
        assertFalse(post(id).outcomeUnknown)
    }

    @Test
    fun `confirmed post cannot be requeued afterwards`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        facebook.next(SocketTimeoutException())
        repo.processQueue()

        assertTrue(repo.confirmPublishedManually(id))
        assertFalse(repo.requeueAfterUserCheck(id))
        repo.processQueue()

        assertEquals(PostStatus.PUBLISHED, post(id).status)
        assertEquals(1, facebook.calls)
    }

    @Test
    fun `retryable error stops after max attempts`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        repeat(PublishQueueRepository.MAX_RETRYABLE_ATTEMPTS) {
            facebook.next(PublishResult.RetryableError("server_error"))
        }

        repeat(PublishQueueRepository.MAX_RETRYABLE_ATTEMPTS - 1) {
            assertTrue(repo.processQueue().shouldRetry)
        }
        val last = repo.processQueue()

        assertEquals(1, last.failed)
        assertFalse(last.shouldRetry)
        assertEquals(PostStatus.FAILED, post(id).status)
        assertEquals("retry_limit_reached (server_error)", post(id).errorMessage)
        assertEquals(PublishQueueRepository.MAX_RETRYABLE_ATTEMPTS, facebook.calls)
    }

    @Test
    fun `many not-sent failures then one server error keeps post queued`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        repeat(PublishQueueRepository.MAX_RETRYABLE_ATTEMPTS + 2) { i ->
            facebook.next(
                when (i % 3) {
                    0 -> UnknownHostException("dns")
                    1 -> ConnectException("refused")
                    else -> SSLHandshakeException("captive portal")
                },
            )
        }
        facebook.next(PublishResult.RetryableError("server_error"))

        repeat(PublishQueueRepository.MAX_RETRYABLE_ATTEMPTS + 2) {
            assertEquals(1, repo.processQueue().retryLater)
            assertEquals(0, post(id).attemptCount)
        }
        val serverError = repo.processQueue()

        assertTrue(serverError.shouldRetry)
        assertEquals(PostStatus.QUEUED, post(id).status)
        assertEquals("server_error", post(id).errorMessage)
        assertEquals(1, post(id).attemptCount)
    }

    @Test
    fun `not-sent failures between server errors do not shorten retry limit`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        repeat(PublishQueueRepository.MAX_RETRYABLE_ATTEMPTS - 1) {
            facebook.next(PublishResult.RetryableError("rate_limited"))
            facebook.next(UnknownHostException("offline"))
        }
        facebook.next(PublishResult.Success("fb-9", "https://facebook.com/fb-9"))

        repeat(2 * (PublishQueueRepository.MAX_RETRYABLE_ATTEMPTS - 1)) {
            assertTrue(repo.processQueue().shouldRetry)
        }
        val last = repo.processQueue()

        assertEquals(1, last.published)
        assertEquals(PostStatus.PUBLISHED, post(id).status)
        assertEquals(PublishQueueRepository.MAX_RETRYABLE_ATTEMPTS, post(id).attemptCount)
        assertEquals(1, facebook.keys.toSet().size)
    }

    @Test
    fun `timeout cancellation inside client is treated as unknown outcome`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        val timeout = runCatching { withTimeout(1) { awaitCancellation() } }.exceptionOrNull()!!
        facebook.next(timeout)

        val result = repo.processQueue()

        assertEquals(1, result.needsConfirmation)
        assertTrue(post(id).outcomeUnknown)
        assertEquals(PostStatus.PUBLISHING, post(id).status)
    }

    @Test
    fun `resume on app start schedules worker only if idle`() = runTest {
        repository().resumePendingWork()

        assertEquals(1, scheduler.idleCalls)
        assertEquals("Mở app không nối thêm lượt vào chuỗi đang chờ", 0, scheduler.calls)
    }

    @Test
    fun `storage error before calling api returns post to queue without counting attempt`() = runTest {
        saveDraft()
        val failing = FailingUpdatePostDao(db.postDao())
        val repo = repository(postDao = failing)
        val id = queuedId(repo)
        failing.failNextGetById = true

        val result = repo.processQueue()

        assertEquals(1, result.retryLater)
        assertEquals(0, facebook.calls)
        val released = post(id)
        assertEquals(PostStatus.QUEUED, released.status)
        assertEquals(0, released.attemptCount)
        assertNull(released.attemptStartedAtMillis)
        assertFalse(released.outcomeUnknown)
        assertEquals(PublishQueueRepository.REASON_STORAGE, released.errorMessage)

        repo.processQueue()

        assertEquals(PostStatus.PUBLISHED, post(id).status)
        assertEquals(1, facebook.calls)
    }

    @Test
    fun `publishing post without start time is treated as stale`() = runTest {
        saveDraft()
        db.postDao().insertIfAbsent(
            existingPost("legacy", PostStatus.PUBLISHING, createdAt = 1).copy(attemptStartedAtMillis = null),
        )

        repository().processQueue()

        assertTrue(post("legacy").outcomeUnknown)
        assertEquals(0, facebook.calls)
    }

    @Test
    fun `release claim does not touch posts that need confirmation or are published`() = runTest {
        saveDraft()
        saveDraft(id = "d2")
        val postDao = db.postDao()
        postDao.insertIfAbsent(existingPost("unknown", PostStatus.PUBLISHING, 1).copy(outcomeUnknown = true))
        postDao.insertIfAbsent(existingPost("done", PostStatus.PUBLISHED, 2, draftId = "d2"))

        assertEquals(0, postDao.releaseClaim("unknown", "x", nowMillis))
        assertEquals(0, postDao.releaseClaim("done", "x", nowMillis))
        assertTrue(post("unknown").outcomeUnknown)
        assertEquals(PostStatus.PUBLISHED, post("done").status)
    }

    // ---------- Test doubles ----------

    private fun existingPost(
        id: String,
        status: PostStatus,
        createdAt: Long,
        draftId: String = "d1",
    ) = PostEntity(
        id = id,
        draftId = draftId,
        channel = ChannelType.FACEBOOK,
        status = status,
        idempotencyKey = "key-$id",
        remotePostId = if (status == PostStatus.PUBLISHED) "remote-$id" else null,
        url = null,
        errorMessage = null,
        attemptCount = 1,
        createdAtMillis = createdAt,
        updatedAtMillis = createdAt,
    )

    // ---------- #39: Retry-After ----------

    @Test
    fun `rate limit with Retry-After waits before calling the API again`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        facebook.next(PublishResult.RetryableError("rate_limited", retryAfterMillis = 60_000))

        val first = repo.processQueue()

        val waiting = post(id)
        assertEquals(PostStatus.QUEUED, waiting.status)
        assertEquals("rate_limited", waiting.errorMessage)
        assertEquals(nowMillis + 60_000, waiting.nextAttemptAtMillis)
        assertEquals(1, first.deferred)
        assertFalse("Không để backoff 30 giây của WorkManager gọi lại sớm", first.shouldRetry)
        assertEquals(listOf(60_000L), scheduler.retryAfterDelays)
        assertEquals("Hẹn theo mốc tuyệt đối để lượt sau không hẹn trùng", listOf(nowMillis + 60_000), scheduler.retryAtMillis)

        nowMillis += 30_000
        val early = repo.processQueue()
        assertEquals("Chưa hết thời gian chờ thì không gọi API", 1, facebook.calls)
        assertEquals("Hẹn lại đúng phần thời gian còn lại", 30_000L, scheduler.retryAfterDelays.last())
        assertEquals("Cùng một mốc", scheduler.retryAtMillis.first(), scheduler.retryAtMillis.last())
        assertEquals(0, early.published)

        nowMillis += 30_000
        repo.processQueue()
        assertEquals(PostStatus.PUBLISHED, post(id).status)
        assertNull(post(id).nextAttemptAtMillis)
        assertEquals(2, facebook.calls)
    }

    @Test
    fun `Retry-After is capped so a post never waits forever`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        facebook.next(PublishResult.RetryableError("rate_limited", retryAfterMillis = 30L * 24 * 60 * 60 * 1000))

        repo.processQueue()

        assertEquals(nowMillis + PublishQueueRepository.MAX_RETRY_AFTER_MILLIS, post(id).nextAttemptAtMillis)
    }

    @Test
    fun `retryable error without Retry-After keeps WorkManager backoff`() = runTest {
        saveDraft()
        val repo = repository()
        val id = queuedId(repo)
        facebook.next(PublishResult.RetryableError("server_error"))

        val run = repo.processQueue()

        assertTrue(run.shouldRetry)
        assertEquals(0, run.deferred)
        assertNull(post(id).nextAttemptAtMillis)
        assertTrue(scheduler.retryAfterDelays.isEmpty())
    }

    @Test
    fun `waiting on one channel does not delay the other channel`() = runTest {
        val linkedin = ScriptedClient(ChannelType.LINKEDIN)
        saveDraft(id = "fb", channel = ChannelType.FACEBOOK)
        saveDraft(id = "li", channel = ChannelType.LINKEDIN)
        val repo = repository(clients = setOf(facebook, linkedin))
        facebook.next(PublishResult.RetryableError("rate_limited", retryAfterMillis = 60_000))
        val fbId = queuedId(repo, "fb")
        val liId = queuedId(repo, "li")

        repo.processQueue()

        assertEquals(PostStatus.QUEUED, post(fbId).status)
        assertEquals(PostStatus.PUBLISHED, post(liId).status)
    }

    @Test
    fun `post that becomes due during a run is scheduled again right away`() = runTest {
        // Facebook chưa tới giờ lúc bị bỏ qua; LinkedIn đăng mất 10 giây nên khi lượt kết thúc Facebook đã tới giờ.
        val slowLinkedin = object : SocialChannelClient by ScriptedClient(ChannelType.LINKEDIN) {
            override suspend fun publish(draft: Draft, idempotencyKey: String): PublishResult {
                nowMillis += 10_000
                return PublishResult.Success("li-1", "https://linkedin.com/1")
            }
        }
        saveDraft(id = "fb", channel = ChannelType.FACEBOOK)
        saveDraft(id = "li", channel = ChannelType.LINKEDIN)
        val repo = repository(clients = setOf(facebook, slowLinkedin))
        val fbId = queuedId(repo, "fb")
        nowMillis += 1 // Facebook tạo trước nên được xét trước trong lượt chạy.
        queuedId(repo, "li")
        db.postDao().update(post(fbId).copy(nextAttemptAtMillis = nowMillis + 5_000))
        val scheduledBefore = scheduler.calls

        repo.processQueue()

        assertEquals("Facebook chưa được gọi trong lượt này", 0, facebook.calls)
        assertEquals("Hẹn ngay trên chuỗi chính thay vì chờ mốc đã qua", scheduledBefore + 1, scheduler.calls)
        assertTrue(scheduler.retryAtMillis.isEmpty())
    }

    @Test
    fun `claim refuses a post that must still wait`() = runTest {
        saveDraft()
        val id = queuedId(repository())
        db.postDao().update(post(id).copy(nextAttemptAtMillis = nowMillis + 1))

        assertEquals(0, db.postDao().claimForPublishing(id, nowMillis))
        assertEquals(1, db.postDao().claimForPublishing(id, nowMillis + 1))
        assertNull("Giữ chỗ xong thì xoá mốc chờ", post(id).nextAttemptAtMillis)
    }

    // ---------- #36: đăng một lúc lên nhiều kênh ----------

    @Test
    fun `one channel failing does not block publishing to the other channel`() = runTest {
        val linkedin = ScriptedClient(ChannelType.LINKEDIN)
        saveDraft(id = "fb", channel = ChannelType.FACEBOOK)
        saveDraft(id = "li", channel = ChannelType.LINKEDIN)
        val repo = repository(clients = setOf(facebook, linkedin))
        facebook.next(PublishResult.PermanentError("permission_denied"))
        val fbId = queuedId(repo, "fb")
        val liId = queuedId(repo, "li")

        val run = repo.processQueue()

        assertEquals(PostStatus.FAILED, post(fbId).status)
        assertEquals(PostStatus.PUBLISHED, post(liId).status)
        assertEquals(1, run.failed)
        assertEquals(1, run.published)
        assertEquals("Mỗi kênh gọi API đúng một lần", listOf(1, 1), listOf(facebook.calls, linkedin.calls))
    }

    private class CountingScheduler : PublishScheduler {
        var calls = 0
        var idleCalls = 0
        val staleCheckDelays = mutableListOf<Long>()
        override fun schedule() {
            calls++
        }

        override fun scheduleIfIdle() {
            idleCalls++
        }

        override fun scheduleStaleCheck(delayMillis: Long) {
            staleCheckDelays += delayMillis
        }

        val retryAfterDelays = mutableListOf<Long>()
        val retryAtMillis = mutableListOf<Long>()
        override fun scheduleRetryAt(retryAtMillis: Long, delayMillis: Long) {
            this.retryAtMillis += retryAtMillis
            retryAfterDelays += delayMillis
        }
    }

    /** Kênh giả trả lần lượt các kết quả được xếp sẵn; hết kịch bản thì đăng thành công. */
    private class ScriptedClient(override val type: ChannelType) : SocialChannelClient {
        private val script = ArrayDeque<Any>()
        val keys = mutableListOf<String>()
        val calls get() = keys.size
        var gate: CompletableDeferred<Unit>? = null

        fun next(outcome: Any) {
            script.addLast(outcome)
        }

        override suspend fun publish(draft: Draft, idempotencyKey: String): PublishResult {
            keys += idempotencyKey
            gate?.await()
            return when (val outcome = script.removeFirstOrNull()) {
                null -> PublishResult.Success("auto-${keys.size}", "https://example.com/${keys.size}")
                is Throwable -> throw outcome
                else -> outcome as PublishResult
            }
        }

        override suspend fun fetchComments(remotePostId: String): List<Comment> = emptyList()
        override suspend fun reply(commentId: String, text: String) = Unit
        override suspend fun fetchMetrics(remotePostId: String): PostMetrics = error("not used")
        override suspend fun fetchFollowerCount(): Int = 0
    }

    /** Giả lập ghi DB thất bại ngay sau khi API đăng thành công. */
    private class FailingUpdatePostDao(private val delegate: PostDao) : PostDao by delegate {
        var failNextUpdate = false
        var failNextGetById = false

        override suspend fun getById(id: String): PostEntity? {
            if (failNextGetById) {
                failNextGetById = false
                error("disk I/O error")
            }
            return delegate.getById(id)
        }

        override suspend fun update(post: PostEntity) {
            if (failNextUpdate && post.status == PostStatus.PUBLISHED) {
                failNextUpdate = false
                error("disk full")
            }
            delegate.update(post)
        }
    }
}
