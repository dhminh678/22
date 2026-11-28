package com.lttbdd.socialagent.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.core.publishing.EnqueueResult
import com.lttbdd.socialagent.core.publishing.PublishQueue
import com.lttbdd.socialagent.data.review.DraftApprovalGuard
import com.lttbdd.socialagent.data.review.DraftApprovalResult
import com.lttbdd.socialagent.data.review.DraftReviewRepository
import com.lttbdd.socialagent.data.review.DraftRevokeResult
import com.lttbdd.socialagent.data.review.PrePublishWarningAnalyzer
import com.lttbdd.socialagent.data.review.PublishGateResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel quản lý luồng duyệt bản nháp (F4, Issue #33):
 * đảm bảo không bao giờ bàn giao bản nháp sang bước đăng bài nếu người dùng chưa phê duyệt tường minh
 * đúng phiên bản nội dung đã xem.
 */
@HiltViewModel
class ReviewDraftsViewModel @Inject constructor(
    private val repository: DraftReviewRepository,
    private val publishQueue: PublishQueue,
) : ViewModel() {

    val uiState: StateFlow<ReviewDraftsUiState> = repository.observeDrafts()
        .map { drafts ->
            ReviewDraftsUiState(
                isLoading = false,
                drafts = drafts.map { draft ->
                    ReviewDraftItemUiState(
                        draft = draft,
                        canPublish = DraftApprovalGuard.canPublish(draft),
                        warnings = PrePublishWarningAnalyzer.analyze(draft.text),
                    )
                },
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ReviewDraftsUiState(isLoading = true),
        )

    private val _events = Channel<ReviewDraftsEvent>(Channel.BUFFERED)
    val events: Flow<ReviewDraftsEvent> = _events.receiveAsFlow()

    /** Mở hộp thoại xác nhận, mang theo đúng nội dung [displayedText] mà người dùng vừa xem. */
    fun onApproveRequested(draftId: String, displayedText: String) {
        _events.trySend(ReviewDraftsEvent.ConfirmApprove(draftId, displayedText))
    }

    /** Thực thi phê duyệt sau khi người dùng xác nhận trong hộp thoại, chỉ duyệt nếu nội dung còn khớp. */
    fun onApproveConfirmed(draftId: String, expectedText: String) {
        viewModelScope.launch {
            val messageRes = when (repository.approveDraft(draftId, expectedText)) {
                is DraftApprovalResult.Approved -> R.string.review_draft_approved_message
                DraftApprovalResult.ContentChanged -> R.string.review_draft_content_changed
                DraftApprovalResult.EmptyContent,
                DraftApprovalResult.NotFound,
                -> R.string.review_draft_approve_failed
                is DraftApprovalResult.StorageError -> R.string.review_draft_action_failed
            }
            _events.trySend(ReviewDraftsEvent.ShowMessage(messageRes))
        }
    }

    /** Thu hồi trạng thái duyệt để khóa lại nút đăng bài. */
    fun onRevokeApproval(draftId: String) {
        viewModelScope.launch {
            val messageRes = when (repository.revokeApproval(draftId)) {
                is DraftRevokeResult.Revoked -> R.string.review_draft_revoked_message
                DraftRevokeResult.NotFound,
                is DraftRevokeResult.StorageError,
                -> R.string.review_draft_action_failed
            }
            _events.trySend(ReviewDraftsEvent.ShowMessage(messageRes))
        }
    }

    /**
     * Yêu cầu đăng bản nháp — luôn đi qua cổng chặn cứng [DraftReviewRepository.requestPublish]
     * để phòng vệ theo chiều sâu kể cả khi bị gọi ngoài UI.
     * Nếu nội dung có cảnh báo trước khi đăng (#65), yêu cầu người dùng xác nhận rõ trước khi đưa vào hàng đợi F5 (#37).
     */
    fun onPublishRequested(draftId: String) {
        viewModelScope.launch {
            when (val gate = repository.requestPublish(draftId)) {
                is PublishGateResult.ReadyToPublish -> {
                    val warnings = PrePublishWarningAnalyzer.analyze(gate.draft.text)
                    if (warnings.isNotEmpty()) {
                        _events.trySend(ReviewDraftsEvent.ConfirmPublishWithWarnings(draftId, warnings))
                    } else {
                        _events.trySend(ReviewDraftsEvent.ShowMessage(enqueue(draftId)))
                    }
                }
                is PublishGateResult.BlockedUnapproved -> {
                    _events.trySend(ReviewDraftsEvent.ShowMessage(R.string.review_publish_blocked_unapproved))
                }
                is PublishGateResult.BlockedEmptyContent -> {
                    _events.trySend(ReviewDraftsEvent.ShowMessage(R.string.review_publish_blocked_empty))
                }
                PublishGateResult.NotFound,
                is PublishGateResult.StorageError,
                -> {
                    _events.trySend(ReviewDraftsEvent.ShowMessage(R.string.review_draft_action_failed))
                }
            }
        }
    }

    /** Người dùng xác nhận vẫn muốn đăng sau khi đã đọc cảnh báo nội dung (#65). */
    fun onPublishConfirmedAfterWarnings(draftId: String) {
        viewModelScope.launch {
            val messageRes = when (repository.requestPublish(draftId)) {
                is PublishGateResult.ReadyToPublish -> enqueue(draftId)
                is PublishGateResult.BlockedUnapproved -> R.string.review_publish_blocked_unapproved
                is PublishGateResult.BlockedEmptyContent -> R.string.review_publish_blocked_empty
                PublishGateResult.NotFound,
                is PublishGateResult.StorageError,
                -> R.string.review_draft_action_failed
            }
            _events.trySend(ReviewDraftsEvent.ShowMessage(messageRes))
        }
    }

    private suspend fun enqueue(draftId: String): Int = try {
        when (val result = publishQueue.enqueue(draftId)) {
            is EnqueueResult.Queued -> R.string.review_publish_queued
            is EnqueueResult.AlreadyActive -> when (result.status) {
                PostStatus.QUEUED -> R.string.review_publish_already_queued
                else -> R.string.review_publish_in_progress
            }
            is EnqueueResult.AlreadyPublished -> R.string.review_publish_already_published
            EnqueueResult.NotApproved -> R.string.review_publish_blocked_unapproved
            EnqueueResult.DraftNotFound -> R.string.review_draft_action_failed
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        R.string.review_draft_action_failed
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
