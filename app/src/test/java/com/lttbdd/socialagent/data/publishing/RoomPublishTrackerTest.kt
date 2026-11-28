package com.lttbdd.socialagent.data.publishing

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lttbdd.socialagent.core.database.AppDatabase
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.database.entity.PostEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.core.publishing.EnqueueResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Trạng thái đăng từng kênh theo topic (#36): hàm ghép thuần và bản đọc thật từ Room. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomPublishTrackerTest {

    private lateinit var db: AppDatabase
    private var nowMillis = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- buildTopicStates (hàm thuần) ----------

    @Test
    fun `draft without post is shown as draft`() {
        val topics = RoomPublishTracker.buildTopicStates(listOf(draft("fb", "t1")), emptyList())

        val channel = topics.single().channels.single()
        assertEquals(PostStatus.DRAFT, channel.status)
        assertNull(channel.postId)
        assertFalse(channel.needsConfirmation)
    }

    @Test
    fun `drafts of the same topic are grouped and ordered by channel`() {
        val topics = RoomPublishTracker.buildTopicStates(
            listOf(
                draft("li", "t1", ChannelType.LINKEDIN, text = "Bản LinkedIn"),
                draft("fb", "t1", ChannelType.FACEBOOK, text = "Bản Facebook"),
            ),
            emptyList(),
        )

        val topic = topics.single()
        assertEquals(listOf(ChannelType.FACEBOOK, ChannelType.LINKEDIN), topic.channels.map { it.channel })
        assertEquals("Tiêu đề lấy từ kênh đầu tiên", "Bản Facebook", topic.previewText)
    }

    @Test
    fun `each channel keeps its own status`() {
        val topics = RoomPublishTracker.buildTopicStates(
            listOf(draft("fb", "t1"), draft("li", "t1", ChannelType.LINKEDIN)),
            listOf(
                post("p1", "fb", PostStatus.PUBLISHED, url = "https://facebook.com/p1"),
                post("p2", "li", PostStatus.FAILED, error = "permission_denied"),
            ),
        )

        val (facebook, linkedin) = topics.single().channels
        assertEquals(PostStatus.PUBLISHED, facebook.status)
        assertEquals("https://facebook.com/p1", facebook.url)
        assertEquals(PostStatus.FAILED, linkedin.status)
        assertEquals("permission_denied", linkedin.errorReason)
    }

    @Test
    fun `latest attempt replaces an older failed post`() {
        val topics = RoomPublishTracker.buildTopicStates(
            listOf(draft("fb", "t1")),
            listOf(
                post("old", "fb", PostStatus.FAILED, createdAt = 10),
                post("new", "fb", PostStatus.QUEUED, createdAt = 20),
            ),
        )

        val channel = topics.single().channels.single()
        assertEquals("new", channel.postId)
        assertEquals(PostStatus.QUEUED, channel.status)
    }

    @Test
    fun `publishing post with unknown outcome needs confirmation`() {
        val topics = RoomPublishTracker.buildTopicStates(
            listOf(draft("fb", "t1")),
            listOf(post("p1", "fb", PostStatus.PUBLISHING, outcomeUnknown = true)),
        )

        assertTrue(topics.single().channels.single().needsConfirmation)
    }

    @Test
    fun `publishing post still in flight does not need confirmation`() {
        val topics = RoomPublishTracker.buildTopicStates(
            listOf(draft("fb", "t1")),
            listOf(post("p1", "fb", PostStatus.PUBLISHING, outcomeUnknown = false)),
        )

        assertFalse(topics.single().channels.single().needsConfirmation)
    }

    @Test
    fun `topic with the most recent activity comes first`() {
        val topics = RoomPublishTracker.buildTopicStates(
            listOf(draft("a", "old-topic", updatedAt = 100), draft("b", "new-topic", updatedAt = 50)),
            // Bài của topic "new-topic" vừa cập nhật nên topic đó lên đầu dù bản nháp cũ hơn.
            listOf(post("p", "b", PostStatus.PUBLISHED, updatedAt = 500)),
        )

        assertEquals(listOf("new-topic", "old-topic"), topics.map { it.topicId })
    }

    @Test
    fun `posts whose draft was deleted are ignored`() {
        val orphan = post("p", "gone", PostStatus.PUBLISHED).copy(draftId = null)

        val topics = RoomPublishTracker.buildTopicStates(listOf(draft("fb", "t1")), listOf(orphan))

        assertEquals(PostStatus.DRAFT, topics.single().channels.single().status)
    }

    // ---------- Đọc thật từ Room ----------

    @Test
    fun `observeTopics reflects queue changes from Room`() = runTest {
        db.draftDao().upsert(draft("fb", "t1"))
        db.draftDao().upsert(draft("li", "t1", ChannelType.LINKEDIN))
        val queue = queue()
        val tracker = RoomPublishTracker(db.draftDao(), db.postDao(), queue)

        assertEquals(listOf(PostStatus.DRAFT, PostStatus.DRAFT), tracker.observeTopics().first().statuses())

        queue.enqueue("fb")

        assertEquals(listOf(PostStatus.QUEUED, PostStatus.DRAFT), tracker.observeTopics().first().statuses())
    }

    @Test
    fun `confirm and requeue only act on posts awaiting confirmation`() = runTest {
        db.draftDao().upsert(draft("fb", "t1"))
        val queue = queue()
        val tracker = RoomPublishTracker(db.draftDao(), db.postDao(), queue)
        val postId = (queue.enqueue("fb") as EnqueueResult.Queued).postId

        assertFalse("Bài QUEUED không chờ xác nhận", tracker.confirmPublished(postId))

        db.postDao().update(db.postDao().getById(postId)!!.copy(status = PostStatus.PUBLISHING, outcomeUnknown = true))
        assertTrue(tracker.requeueAfterCheck(postId))
        assertEquals(PostStatus.QUEUED, db.postDao().getById(postId)!!.status)

        db.postDao().update(db.postDao().getById(postId)!!.copy(status = PostStatus.PUBLISHING, outcomeUnknown = true))
        assertTrue(tracker.confirmPublished(postId))
        assertEquals(PostStatus.PUBLISHED, db.postDao().getById(postId)!!.status)
    }

    private fun List<com.lttbdd.socialagent.core.publishing.TopicPublishState>.statuses() =
        single().channels.map { it.status }

    private fun queue() = PublishQueueRepository(
        database = db,
        postDao = db.postDao(),
        draftDao = db.draftDao(),
        clients = emptySet(),
        scheduler = object : PublishScheduler {
            override fun schedule() = Unit
            override fun scheduleIfIdle() = Unit
            override fun scheduleStaleCheck(delayMillis: Long) = Unit
        },
        clock = { nowMillis++ },
    )

    private fun draft(
        id: String,
        topicId: String,
        channel: ChannelType = ChannelType.FACEBOOK,
        text: String = "Nội dung $id",
        updatedAt: Long = 1,
    ) = DraftEntity(
        id = id,
        topicId = topicId,
        channel = channel,
        text = text,
        imageUri = null,
        imageAltText = null,
        approved = true,
        updatedAtMillis = updatedAt,
    )

    private fun post(
        id: String,
        draftId: String,
        status: PostStatus,
        url: String? = null,
        error: String? = null,
        outcomeUnknown: Boolean = false,
        createdAt: Long = 1,
        updatedAt: Long = createdAt,
    ) = PostEntity(
        id = id,
        draftId = draftId,
        channel = ChannelType.FACEBOOK,
        status = status,
        idempotencyKey = "key-$id",
        remotePostId = null,
        url = url,
        errorMessage = error,
        attemptCount = 0,
        createdAtMillis = createdAt,
        updatedAtMillis = updatedAt,
        outcomeUnknown = outcomeUnknown,
    )
}
