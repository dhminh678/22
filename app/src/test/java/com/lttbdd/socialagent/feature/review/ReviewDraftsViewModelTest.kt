package com.lttbdd.socialagent.feature.review

import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.core.publishing.EnqueueResult
import com.lttbdd.socialagent.core.publishing.PublishQueue
import com.lttbdd.socialagent.data.review.DraftApprovalGuard
import com.lttbdd.socialagent.data.review.DraftApprovalResult
import com.lttbdd.socialagent.data.review.DraftContentUpdateResult
import com.lttbdd.socialagent.data.review.DraftReviewRepository
import com.lttbdd.socialagent.data.review.DraftRevokeResult
import com.lttbdd.socialagent.data.review.PrePublishWarningType
import com.lttbdd.socialagent.data.review.PublishEligibility
import com.lttbdd.socialagent.data.review.PublishGateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
class ReviewDraftsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeRepository: FakeDraftReviewRepository
    private lateinit var fakeQueue: FakePublishQueue
    private lateinit var viewModel: ReviewDraftsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepository = FakeDraftReviewRepository()
        fakeQueue = FakePublishQueue()
        viewModel = ReviewDraftsViewModel(fakeRepository, fakeQueue)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `unapproved draft has canPublish false and publish request is blocked`() = runTest {
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        fakeRepository.saveAgentDraft(
            Draft(
                id = "d1",
                topicId = "t1",
                channel = ChannelType.FACEBOOK,
                text = "Bài nháp AI mới tạo",
                approved = true, // Cố tình truyền true để kiểm chứng guard ép về false
            ),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.drafts.size)
        assertFalse(state.drafts.first().draft.approved)
        assertFalse(state.drafts.first().canPublish)

        viewModel.onPublishRequested("d1")
        advanceUntilIdle()

        assertEquals(
            ReviewDraftsEvent.ShowMessage(R.string.review_publish_blocked_unapproved),
            viewModel.events.first(),
        )
        assertTrue(fakeQueue.enqueuedDraftIds.isEmpty())
        collector.cancel()
    }

    @Test
    fun `approval requires confirmation matching viewed text and unlocks publishing`() = runTest {
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        fakeRepository.saveAgentDraft(
            Draft(
                id = "d2",
                topicId = "t2",
                channel = ChannelType.LINKEDIN,
                text = "Bài LinkedIn cần duyệt",
            ),
        )
        advanceUntilIdle()

        viewModel.onApproveRequested("d2", "Bài LinkedIn cần duyệt")
        assertEquals(
            ReviewDraftsEvent.ConfirmApprove("d2", "Bài LinkedIn cần duyệt"),
            viewModel.events.first(),
        )
        assertFalse(viewModel.uiState.value.drafts.first().draft.approved)

        viewModel.onApproveConfirmed("d2", "Bài LinkedIn cần duyệt")
        advanceUntilIdle()

        assertEquals(
            ReviewDraftsEvent.ShowMessage(R.string.review_draft_approved_message),
            viewModel.events.first(),
        )
        assertTrue(viewModel.uiState.value.drafts.first().draft.approved)
        assertTrue(viewModel.uiState.value.drafts.first().canPublish)

        viewModel.onPublishRequested("d2")
        advanceUntilIdle()

        assertEquals(
            ReviewDraftsEvent.ShowMessage(R.string.review_publish_queued),
            viewModel.events.first(),
        )
        assertEquals(listOf("d2"), fakeQueue.enqueuedDraftIds)
        collector.cancel()
    }

    @Test
    fun `confirming approval after content changed rejects approval and asks user to review again`() = runTest {
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        fakeRepository.saveAgentDraft(
            Draft(
                id = "d-race",
                topicId = "t-race",
                channel = ChannelType.FACEBOOK,
                text = "Nội dung v1",
            ),
        )
        advanceUntilIdle()

        // Mở hộp thoại với v1
        viewModel.onApproveRequested("d-race", "Nội dung v1")
        val confirmEvent = viewModel.events.first() as ReviewDraftsEvent.ConfirmApprove

        // Nội dung thay đổi sang v2 trước khi người dùng bấm Xác nhận
        fakeRepository.updateDraftContent("d-race", "Nội dung v2 đã đổi")
        advanceUntilIdle()

        viewModel.onApproveConfirmed(confirmEvent.draftId, confirmEvent.expectedText)
        advanceUntilIdle()

        assertEquals(
            ReviewDraftsEvent.ShowMessage(R.string.review_draft_content_changed),
            viewModel.events.first(),
        )
        assertFalse(viewModel.uiState.value.drafts.first().draft.approved)
        assertFalse(viewModel.uiState.value.drafts.first().canPublish)
        collector.cancel()
    }

    @Test
    fun `storage error on approve or revoke shows action failed message without crashing`() = runTest {
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        fakeRepository.saveAgentDraft(
            Draft(
                id = "d-io",
                topicId = "t-io",
                channel = ChannelType.FACEBOOK,
                text = "Nội dung",
            ),
        )
        fakeRepository.failWithStorageError = true
        advanceUntilIdle()

        viewModel.onApproveConfirmed("d-io", "Nội dung")
        advanceUntilIdle()
        assertEquals(
            ReviewDraftsEvent.ShowMessage(R.string.review_draft_action_failed),
            viewModel.events.first(),
        )

        viewModel.onRevokeApproval("d-io")
        advanceUntilIdle()
        assertEquals(
            ReviewDraftsEvent.ShowMessage(R.string.review_draft_action_failed),
            viewModel.events.first(),
        )
        collector.cancel()
    }

    @Test
    fun `revokeApproval re-locks publishing`() = runTest {
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        fakeRepository.saveAgentDraft(
            Draft(
                id = "d3",
                topicId = "t3",
                channel = ChannelType.FACEBOOK,
                text = "Nội dung bản nháp",
            ),
        )
        fakeRepository.approveDraft("d3", expectedText = "Nội dung bản nháp")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.drafts.first().canPublish)

        viewModel.onRevokeApproval("d3")
        advanceUntilIdle()

        assertEquals(
            ReviewDraftsEvent.ShowMessage(R.string.review_draft_revoked_message),
            viewModel.events.first(),
        )
        assertFalse(viewModel.uiState.value.drafts.first().canPublish)
        collector.cancel()
    }

    @Test
    fun `empty repository exposes empty drafts list`() = runTest {
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.drafts.isEmpty())
        collector.cancel()
    }

    @Test
    fun `each enqueue result shows its own message and never says published on queue`() = runTest {
        approvedDraft("d-q")
        val cases = listOf(
            EnqueueResult.Queued("p1") to R.string.review_publish_queued,
            EnqueueResult.AlreadyActive("p1", PostStatus.QUEUED) to R.string.review_publish_already_queued,
            EnqueueResult.AlreadyActive("p1", PostStatus.PUBLISHING) to R.string.review_publish_in_progress,
            EnqueueResult.AlreadyActive("p1", PostStatus.HANDED_OFF) to R.string.review_publish_in_progress,
            EnqueueResult.AlreadyPublished("p1", "https://example.com/p1") to
                R.string.review_publish_already_published,
            EnqueueResult.NotApproved to R.string.review_publish_blocked_unapproved,
            EnqueueResult.DestinationRequired to R.string.review_publish_destination_required,
            EnqueueResult.DraftNotFound to R.string.review_draft_action_failed,
        )
        for ((result, expectedMessage) in cases) {
            fakeQueue.nextResult = result
            viewModel.onPublishRequested("d-q")
            advanceUntilIdle()
            assertEquals(result.toString(), ReviewDraftsEvent.ShowMessage(expectedMessage), viewModel.events.first())
        }
        assertEquals(cases.size, fakeQueue.enqueuedDraftIds.size)
    }

    @Test
    fun `empty approved draft is blocked before reaching the queue`() = runTest {
        fakeRepository.putDraft(
            Draft(id = "d-empty", topicId = "t", channel = ChannelType.FACEBOOK, text = " ", approved = true),
        )

        viewModel.onPublishRequested("d-empty")
        advanceUntilIdle()

        assertEquals(
            ReviewDraftsEvent.ShowMessage(R.string.review_publish_blocked_empty),
            viewModel.events.first(),
        )
        assertTrue(fakeQueue.enqueuedDraftIds.isEmpty())
    }

    @Test
    fun `queue storage error shows action failed message without crashing`() = runTest {
        approvedDraft("d-err")
        fakeQueue.error = IllegalStateException("IO")

        viewModel.onPublishRequested("d-err")
        advanceUntilIdle()

        assertEquals(
            ReviewDraftsEvent.ShowMessage(R.string.review_draft_action_failed),
            viewModel.events.first(),
        )
    }

    @Test
    fun `approved draft with pre-publish warnings requires confirmation before enqueue`() = runTest {
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        fakeRepository.putDraft(
            Draft(
                id = "d-warn",
                topicId = "t-warn",
                channel = ChannelType.FACEBOOK,
                text = "Tăng 75% doanh thu, liên hệ user@example.com!",
                approved = true,
            ),
        )
        advanceUntilIdle()

        val item = viewModel.uiState.value.drafts.single()
        assertEquals(
            setOf(PrePublishWarningType.PERSONAL_INFO, PrePublishWarningType.UNSOURCED_STATISTIC),
            item.warnings.map { it.type }.toSet(),
        )

        // Bấm Đăng bài lần đầu → phát sự kiện xác nhận cảnh báo, chưa đưa vào hàng đợi
        viewModel.onPublishRequested("d-warn")
        advanceUntilIdle()

        val event = viewModel.events.first()
        assertTrue(event is ReviewDraftsEvent.ConfirmPublishWithWarnings)
        assertEquals("d-warn", (event as ReviewDraftsEvent.ConfirmPublishWithWarnings).draftId)
        assertTrue(fakeQueue.enqueuedDraftIds.isEmpty())

        // Người dùng xác nhận Vẫn đăng bài → mới đưa vào hàng đợi
        viewModel.onPublishConfirmedAfterWarnings("d-warn")
        advanceUntilIdle()

        assertEquals(
            ReviewDraftsEvent.ShowMessage(R.string.review_publish_queued),
            viewModel.events.first(),
        )
        assertEquals(listOf("d-warn"), fakeQueue.enqueuedDraftIds)
        collector.cancel()
    }

    private fun approvedDraft(id: String) {
        fakeRepository.putDraft(
            Draft(id = id, topicId = "t", channel = ChannelType.LINKEDIN, text = "Nội dung đã duyệt", approved = true),
        )
    }

    private class FakePublishQueue : PublishQueue {
        val enqueuedDraftIds = mutableListOf<String>()
        var nextResult: EnqueueResult = EnqueueResult.Queued("post-1")
        var error: Exception? = null

        override suspend fun enqueue(draftId: String): EnqueueResult {
            error?.let { throw it }
            enqueuedDraftIds += draftId
            return nextResult
        }
    }

    private class FakeDraftReviewRepository : DraftReviewRepository {
        private val items = MutableStateFlow<List<Draft>>(emptyList())
        var failWithStorageError = false

        /** Đặt thẳng bản nháp (bỏ qua guard) để dựng trạng thái đã duyệt trong test. */
        fun putDraft(draft: Draft) {
            items.value = items.value.filterNot { it.id == draft.id } + draft
        }

        override fun observeDrafts(): Flow<List<Draft>> = items.asStateFlow()

        override suspend fun saveAgentDraft(draft: Draft): Result<Draft> {
            val sanitized = DraftApprovalGuard.sanitizeAgentDraft(draft)
            items.value = items.value.filterNot { it.id == sanitized.id } + sanitized
            return Result.success(sanitized)
        }

        override suspend fun updateDraftContent(
            draftId: String,
            newText: String,
        ): DraftContentUpdateResult {
            if (newText.isBlank()) return DraftContentUpdateResult.EmptyContent
            val current = items.value.firstOrNull { it.id == draftId }
                ?: return DraftContentUpdateResult.NotFound
            val updated = DraftApprovalGuard.onDraftContentEdited(current.copy(text = newText))
            items.value = items.value.map { if (it.id == draftId) updated else it }
            return DraftContentUpdateResult.Updated(updated)
        }

        override suspend fun approveDraft(
            draftId: String,
            expectedText: String,
        ): DraftApprovalResult {
            if (failWithStorageError) return DraftApprovalResult.StorageError(IllegalStateException("IO"))
            val current = items.value.firstOrNull { it.id == draftId }
                ?: return DraftApprovalResult.NotFound
            if (current.text != expectedText) return DraftApprovalResult.ContentChanged
            if (current.text.isBlank()) return DraftApprovalResult.EmptyContent
            val updated = current.copy(approved = true)
            items.value = items.value.map { if (it.id == draftId) updated else it }
            return DraftApprovalResult.Approved(updated)
        }

        override suspend fun revokeApproval(draftId: String): DraftRevokeResult {
            if (failWithStorageError) return DraftRevokeResult.StorageError(IllegalStateException("IO"))
            val current = items.value.firstOrNull { it.id == draftId }
                ?: return DraftRevokeResult.NotFound
            val updated = current.copy(approved = false)
            items.value = items.value.map { if (it.id == draftId) updated else it }
            return DraftRevokeResult.Revoked(updated)
        }

        override suspend fun requestPublish(draftId: String): PublishGateResult {
            val draft = items.value.firstOrNull { it.id == draftId } ?: return PublishGateResult.NotFound
            return when (DraftApprovalGuard.checkPublishEligibility(draft)) {
                PublishEligibility.Allowed -> PublishGateResult.ReadyToPublish(draft)
                PublishEligibility.BlockedNotApproved -> PublishGateResult.BlockedUnapproved(draft)
                PublishEligibility.BlockedEmptyContent -> PublishGateResult.BlockedEmptyContent(draft)
            }
        }
    }
}
