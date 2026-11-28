package com.lttbdd.socialagent.feature.publishing

import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.core.publishing.ChannelPublishState
import com.lttbdd.socialagent.core.publishing.EnqueueResult
import com.lttbdd.socialagent.core.publishing.PublishQueue
import com.lttbdd.socialagent.core.publishing.PublishTracker
import com.lttbdd.socialagent.core.publishing.TopicPublishState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PublishStatusViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var tracker: FakeTracker
    private lateinit var queue: FakeQueue
    private lateinit var viewModel: PublishStatusViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tracker = FakeTracker()
        queue = FakeQueue()
        viewModel = PublishStatusViewModel(tracker, queue)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------- Thao tác theo trạng thái ----------

    @Test
    fun `action depends on approval and status`() {
        assertEquals(ChannelAction.PUBLISH, PublishStatusViewModel.actionFor(state(PostStatus.DRAFT)))
        assertEquals(ChannelAction.PUBLISH, PublishStatusViewModel.actionFor(state(PostStatus.FAILED, postId = "p")))
        assertEquals(
            ChannelAction.NEEDS_APPROVAL,
            PublishStatusViewModel.actionFor(state(PostStatus.DRAFT, approved = false)),
        )
        assertEquals(
            "Bài lỗi nhưng bản nháp đã bị sửa và mất duyệt thì không đăng lại được",
            ChannelAction.NEEDS_APPROVAL,
            PublishStatusViewModel.actionFor(state(PostStatus.FAILED, approved = false, postId = "p")),
        )
        assertEquals(
            ChannelAction.CONFIRM_OUTCOME,
            PublishStatusViewModel.actionFor(state(PostStatus.PUBLISHING, postId = "p", needsConfirmation = true)),
        )
        listOf(PostStatus.QUEUED, PostStatus.PUBLISHING, PostStatus.PUBLISHED, PostStatus.HANDED_OFF).forEach {
            assertEquals(it.name, ChannelAction.NONE, PublishStatusViewModel.actionFor(state(it, postId = "p")))
        }
    }

    @Test
    fun `ui state maps every channel of every topic`() = runTest {
        collectState()
        tracker.topics.value = listOf(
            topic("t1", state(PostStatus.PUBLISHED, draftId = "fb", postId = "p"), state(PostStatus.DRAFT, draftId = "li")),
        )
        advanceUntilIdle()

        val ui = viewModel.uiState.value
        assertFalse(ui.isLoading)
        val item = ui.topics.single()
        assertEquals(listOf(ChannelAction.NONE, ChannelAction.PUBLISH), item.channels.map { it.action })
        assertTrue(item.canPublishAll)
    }

    // ---------- #39: lý do lỗi ----------

    @Test
    fun `failed and retrying posts show a readable reason`() {
        assertEquals(
            R.string.publish_error_permission_denied,
            PublishStatusViewModel.reasonFor(state(PostStatus.FAILED, postId = "p", error = "permission_denied")),
        )
        assertEquals(
            R.string.publish_error_rate_limited,
            PublishStatusViewModel.reasonFor(state(PostStatus.QUEUED, postId = "p", error = "rate_limited")),
        )
    }

    @Test
    fun `old reason is not shown once the post is published or in flight`() {
        listOf(PostStatus.PUBLISHED, PostStatus.PUBLISHING, PostStatus.HANDED_OFF).forEach {
            val state = state(it, postId = "p", error = "rate_limited")
            assertEquals(it.name, null, PublishStatusViewModel.reasonFor(state))
        }
    }

    @Test
    fun `ui state marks queued posts with a reason as retrying`() = runTest {
        collectState()
        tracker.topics.value = listOf(
            topic(
                "t1",
                state(PostStatus.QUEUED, draftId = "fb", postId = "p1", error = "network_unavailable"),
                state(PostStatus.FAILED, draftId = "li", postId = "p2", error = "content_rejected"),
                state(PostStatus.QUEUED, draftId = "x", postId = "p3"),
            ),
        )
        advanceUntilIdle()

        val channels = viewModel.uiState.value.topics.single().channels
        assertEquals(listOf(true, false, false), channels.map { it.isRetrying })
        assertEquals(
            listOf(R.string.publish_error_network_unavailable, R.string.publish_error_content_rejected, null),
            channels.map { it.reasonRes },
        )
    }

    // ---------- #40: mở bài gốc ----------

    @Test
    fun `published post with web link can be opened`() {
        val url = "https://www.facebook.com/123_456"
        assertEquals(url, PublishStatusViewModel.openableUrl(published(url)))
        val withQuery = "http://example.com/p?id=1#c"
        assertEquals(withQuery, PublishStatusViewModel.openableUrl(published(withQuery)))
        assertEquals(
            "Không phân biệt hoa thường ở scheme",
            "HTTPS://linkedin.com/feed/update/urn:li:share:1",
            PublishStatusViewModel.openableUrl(published("HTTPS://linkedin.com/feed/update/urn:li:share:1")),
        )
    }

    @Test
    fun `link is hidden until the post is published`() {
        listOf(PostStatus.QUEUED, PostStatus.PUBLISHING, PostStatus.FAILED, PostStatus.HANDED_OFF).forEach {
            val state = state(it, postId = "p", url = "https://x.com/1")
            assertEquals(it.name, null, PublishStatusViewModel.openableUrl(state))
        }
    }

    @Test
    fun `non web or malformed links are never opened`() {
        listOf(
            null,
            "",
            "https://",
            "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end",
            "file:///sdcard/secret.txt",
            "content://contacts/people/1",
            "https://example.com/a b",
            // AC #40: khoảng trắng ở đầu/cuối cũng bị chặn, không tự cắt bỏ.
            "  https://example.com/post  ",
            " https://example.com/post",
            "https://example.com/post\n",
            // Thiếu host.
            "https:///post/1",
            "https://?q=1",
            "http:example.com",
            "https:",
            "ftp://example.com/p",
        ).forEach { url ->
            assertEquals(url.toString(), null, PublishStatusViewModel.openableUrl(published(url)))
        }
    }

    @Test
    fun `ui state exposes the open link for published channels only`() = runTest {
        collectState()
        tracker.topics.value = listOf(
            topic(
                "t1",
                state(PostStatus.PUBLISHED, draftId = "fb", postId = "p1", url = "https://facebook.com/p1"),
                state(PostStatus.FAILED, draftId = "li", postId = "p2", url = "https://linkedin.com/p2"),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("https://facebook.com/p1", null),
            viewModel.uiState.value.topics.single().channels.map { it.openUrl },
        )
    }

    private fun published(url: String?) = state(PostStatus.PUBLISHED, postId = "p", url = url)

    // ---------- Đăng một lúc lên nhiều kênh ----------

    @Test
    fun `publish all enqueues only approved channels that are not yet published`() = runTest {
        collectState()
        tracker.topics.value = listOf(
            topic(
                "t1",
                state(PostStatus.DRAFT, draftId = "fb"),
                state(PostStatus.FAILED, draftId = "li", postId = "old"),
                state(PostStatus.PUBLISHED, draftId = "done", postId = "p"),
            ),
        )
        advanceUntilIdle()

        viewModel.onPublishAll("t1")
        advanceUntilIdle()

        assertEquals(listOf("fb", "li"), queue.enqueued)
        assertEquals(R.string.publish_status_all_queued, nextMessage())
    }

    @Test
    fun `publish all skips unapproved channels and says so`() = runTest {
        collectState()
        tracker.topics.value = listOf(
            topic("t1", state(PostStatus.DRAFT, draftId = "fb"), state(PostStatus.DRAFT, draftId = "li", approved = false)),
        )
        advanceUntilIdle()

        viewModel.onPublishAll("t1")
        advanceUntilIdle()

        assertEquals("Bản nháp chưa duyệt không được vào hàng đợi (F4)", listOf("fb"), queue.enqueued)
        assertEquals(R.string.publish_status_queued_some_unapproved, nextMessage())
    }

    @Test
    fun `publish all with nothing to publish does not touch the queue`() = runTest {
        collectState()
        tracker.topics.value = listOf(topic("t1", state(PostStatus.QUEUED, draftId = "fb", postId = "p")))
        advanceUntilIdle()

        viewModel.onPublishAll("t1")
        advanceUntilIdle()

        assertTrue(queue.enqueued.isEmpty())
        assertEquals(R.string.publish_status_nothing_to_publish, nextMessage())
    }

    @Test
    fun `one channel failing does not stop the other channels`() = runTest {
        collectState()
        tracker.topics.value = listOf(
            topic("t1", state(PostStatus.DRAFT, draftId = "fb"), state(PostStatus.DRAFT, draftId = "li")),
        )
        queue.errorFor = "fb"
        advanceUntilIdle()

        viewModel.onPublishAll("t1")
        advanceUntilIdle()

        assertEquals("Kênh sau vẫn được đưa vào hàng đợi", listOf("fb", "li"), queue.enqueued)
        assertEquals(R.string.publish_status_action_failed, nextMessage())
    }

    @Test
    fun `publish all tolerates channels that were queued meanwhile`() = runTest {
        collectState()
        tracker.topics.value = listOf(topic("t1", state(PostStatus.DRAFT, draftId = "fb")))
        queue.results["fb"] = EnqueueResult.AlreadyActive("p", PostStatus.QUEUED)
        advanceUntilIdle()

        viewModel.onPublishAll("t1")
        advanceUntilIdle()

        assertEquals(R.string.review_publish_already_queued, nextMessage())
    }

    @Test
    fun `publish all reports missing destination and still queues the other channel`() = runTest {
        collectState()
        tracker.topics.value = listOf(
            topic("t1", state(PostStatus.DRAFT, draftId = "fb"), state(PostStatus.DRAFT, draftId = "li")),
        )
        queue.results["fb"] = EnqueueResult.DestinationRequired
        advanceUntilIdle()

        viewModel.onPublishAll("t1")
        advanceUntilIdle()

        assertEquals(listOf("fb", "li"), queue.enqueued)
        assertEquals(R.string.publish_status_some_destinations_required, nextMessage())
    }

    // ---------- Từng kênh ----------

    @Test
    fun `publish channel maps queue results to messages`() = runTest {
        val cases = mapOf(
            EnqueueResult.Queued("p") to R.string.review_publish_queued,
            EnqueueResult.AlreadyActive("p", PostStatus.QUEUED) to R.string.review_publish_already_queued,
            EnqueueResult.AlreadyActive("p", PostStatus.PUBLISHING) to R.string.review_publish_in_progress,
            EnqueueResult.AlreadyPublished("p", null) to R.string.review_publish_already_published,
            EnqueueResult.NotApproved to R.string.review_publish_blocked_unapproved,
            EnqueueResult.DestinationRequired to R.string.review_publish_destination_required,
            EnqueueResult.DraftNotFound to R.string.publish_status_action_failed,
        )
        cases.forEach { (result, message) ->
            queue.results["fb"] = result
            viewModel.onPublishChannel("fb")
            advanceUntilIdle()
            assertEquals(result.toString(), message, nextMessage())
        }
    }

    @Test
    fun `confirm published reports success or changed state`() = runTest {
        tracker.confirmResult = true
        viewModel.onConfirmPublished("p")
        advanceUntilIdle()
        assertEquals(R.string.publish_status_marked_published, nextMessage())

        tracker.confirmResult = false
        viewModel.onConfirmPublished("p")
        advanceUntilIdle()
        assertEquals(R.string.publish_status_state_changed, nextMessage())
        assertEquals(listOf("p", "p"), tracker.confirmed)
    }

    @Test
    fun `manual confirmation forwards a valid permalink and rejects invalid links`() = runTest {
        val url = "https://www.facebook.com/permalink.php?story_fbid=456&id=123"
        for (invalid in listOf("https://facebook.com.evil.test/posts/456", "https://linkedin.com/feed/update/123", "javascript:alert(1)")) {
            assertFalse(viewModel.onConfirmPublished("p", ChannelType.FACEBOOK, invalid))
        }
        advanceUntilIdle()
        assertTrue(tracker.confirmed.isEmpty())
        assertTrue(tracker.confirmedPermalinks.isEmpty())

        assertTrue(viewModel.onConfirmPublished("p", ChannelType.FACEBOOK, "  $url  "))
        assertTrue(viewModel.onConfirmPublished("without-link", ChannelType.FACEBOOK, " "))
        advanceUntilIdle()

        assertEquals(listOf("p" to url), tracker.confirmedPermalinks)
        assertEquals(listOf("without-link"), tracker.confirmed)
        assertEquals(R.string.publish_status_marked_published, nextMessage())
        assertEquals(R.string.publish_status_marked_published, nextMessage())
    }

    @Test
    fun `manual confirmation rejects non post pages and incomplete post routes`() = runTest {
        val invalidLinks = mapOf(
            ChannelType.FACEBOOK to listOf(
                "https://www.facebook.com/settings",
                "https://developers.facebook.com/documentation/",
                "https://developers.facebook.com/posts/456",
                "https://www.facebook.com/permalink.php?id=123",
                "https://www.facebook.com/permalink.php?story_fbid=456&story_fbid=789",
                "https://www.facebook.com/page/posts/",
                "https://www.facebook.com/page/photos/albums",
                "https://www.facebook.com/watch/?v=",
                "https://www.facebook.com/photo.php?fbid=not-an-id",
            ),
            ChannelType.LINKEDIN to listOf(
                "https://www.linkedin.com/mypreferences/",
                "https://www.linkedin.com/feed/update/",
                "https://www.linkedin.com/feed/update/urn:li:person:123/",
                "https://www.linkedin.com/feed/update/urn:li:activity:123/extra",
                "https://www.linkedin.com/posts/activity-123",
                "https://www.linkedin.com/posts/alice-profile/",
            ),
        )
        invalidLinks.forEach { (channel, links) ->
            links.forEach { url -> assertFalse(url, viewModel.onConfirmPublished("p", channel, url)) }
        }
        advanceUntilIdle()

        assertTrue(tracker.confirmed.isEmpty())
        assertTrue(tracker.confirmedPermalinks.isEmpty())
    }

    @Test
    fun `manual confirmation accepts supported Facebook and LinkedIn post links`() = runTest {
        val validLinks = mapOf(
            ChannelType.FACEBOOK to listOf(
                "https://m.facebook.com/story.php?story_fbid=456&id=123",
                "https://www.facebook.com/page/posts/pfbid0AbCdEf/",
                "https://www.facebook.com/groups/123/permalink/456/",
                "https://www.facebook.com/photo.php?fbid=456&set=a.123",
                "https://www.facebook.com/page/photos/a.123/456/",
                "https://www.facebook.com/page/videos/456/",
                "https://www.facebook.com/page/activity/456/",
                "https://www.facebook.com/watch/?v=456",
                "https://www.facebook.com/share/p/1AbCdEf/",
            ),
            ChannelType.LINKEDIN to listOf(
                "https://www.linkedin.com/feed/update/urn:li:share:123/",
                "https://www.linkedin.com/feed/update/urn%3Ali%3AugcPost%3A123/?trk=public",
                "https://uk.linkedin.com/posts/alice_example-activity-123-Ab_4/",
            ),
        )
        validLinks.forEach { (channel, links) ->
            links.forEach { url -> assertTrue(url, viewModel.onConfirmPublished("p", channel, url)) }
        }
        advanceUntilIdle()

        assertEquals(validLinks.values.flatten().map { "p" to it }, tracker.confirmedPermalinks)
    }

    @Test
    fun `requeue asks for confirmation before touching the queue`() = runTest {
        viewModel.onRequeueRequested("p")
        advanceUntilIdle()

        assertEquals(PublishStatusEvent.ConfirmRequeue("p"), viewModel.events.first())
        assertTrue("Chưa xác nhận thì chưa đưa lại hàng đợi", tracker.requeued.isEmpty())

        viewModel.onRequeueConfirmed("p")
        advanceUntilIdle()

        assertEquals(listOf("p"), tracker.requeued)
        assertEquals(R.string.publish_status_requeued, nextMessage())
    }

    @Test
    fun `storage error on confirm shows failure instead of crashing`() = runTest {
        tracker.error = IllegalStateException("db closed")

        viewModel.onConfirmPublished("p")
        viewModel.onRequeueConfirmed("p")
        advanceUntilIdle()

        assertEquals(R.string.publish_status_action_failed, nextMessage())
        assertEquals(R.string.publish_status_action_failed, nextMessage())
    }

    private fun TestScope.collectState() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }
    }

    private suspend fun nextMessage(): Int =
        (viewModel.events.first() as PublishStatusEvent.ShowMessage).messageRes

    private fun topic(id: String, vararg channels: ChannelPublishState) =
        TopicPublishState(topicId = id, previewText = "Topic $id", channels = channels.toList())

    private fun state(
        status: PostStatus,
        draftId: String = "fb",
        approved: Boolean = true,
        postId: String? = null,
        needsConfirmation: Boolean = false,
        url: String? = null,
        error: String? = null,
    ) = ChannelPublishState(
        draftId = draftId,
        channel = ChannelType.FACEBOOK,
        approved = approved,
        status = status,
        postId = postId,
        needsConfirmation = needsConfirmation,
        url = url,
        errorReason = error,
    )

    private class FakeTracker : PublishTracker {
        val topics = MutableStateFlow<List<TopicPublishState>>(emptyList())
        val confirmed = mutableListOf<String>()
        val confirmedPermalinks = mutableListOf<Pair<String, String>>()
        val requeued = mutableListOf<String>()
        var confirmResult = true
        var error: Exception? = null

        override fun observeTopics(): Flow<List<TopicPublishState>> = topics

        override suspend fun confirmPublished(postId: String): Boolean {
            error?.let { throw it }
            confirmed += postId
            return confirmResult
        }

        override suspend fun confirmPublishedWithPermalink(postId: String, url: String): Boolean {
            confirmedPermalinks += postId to url
            return confirmResult
        }

        override suspend fun requeueAfterCheck(postId: String): Boolean {
            error?.let { throw it }
            requeued += postId
            return true
        }
    }

    private class FakeQueue : PublishQueue {
        val enqueued = mutableListOf<String>()
        val results = mutableMapOf<String, EnqueueResult>()
        var errorFor: String? = null

        override suspend fun enqueue(draftId: String): EnqueueResult {
            enqueued += draftId
            if (draftId == errorFor) throw IllegalStateException("disk full")
            return results[draftId] ?: EnqueueResult.Queued("post-$draftId")
        }
    }
}
