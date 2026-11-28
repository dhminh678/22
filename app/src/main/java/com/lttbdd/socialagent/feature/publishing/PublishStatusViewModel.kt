package com.lttbdd.socialagent.feature.publishing

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.core.publishing.ChannelPublishState
import com.lttbdd.socialagent.core.publishing.EnqueueResult
import com.lttbdd.socialagent.core.publishing.PublishQueue
import com.lttbdd.socialagent.core.publishing.PublishTracker
import com.lttbdd.socialagent.core.publishing.TopicPublishState
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
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject

/**
 * Màn Trạng thái đăng (F5, #36): đăng một lúc lên mọi kênh đã duyệt của một topic và theo dõi từng kênh
 * qua nháp → chờ → đang đăng → đã đăng / lỗi / đã bàn giao.
 *
 * Chỉ đưa vào hàng đợi bản nháp đã duyệt (F4); hàng đợi kiểm tra lại lần nữa nên không có đường đăng bài chưa duyệt.
 */
@HiltViewModel
class PublishStatusViewModel @Inject constructor(
    private val tracker: PublishTracker,
    private val publishQueue: PublishQueue,
) : ViewModel() {

    val uiState: StateFlow<PublishStatusUiState> = tracker.observeTopics()
        .map { topics -> PublishStatusUiState(isLoading = false, topics = topics.map { it.toItem() }) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = PublishStatusUiState(isLoading = true),
        )

    private val _events = Channel<PublishStatusEvent>(Channel.BUFFERED)
    val events: Flow<PublishStatusEvent> = _events.receiveAsFlow()

    /**
     * Đưa mọi kênh đã duyệt chưa đăng của topic vào hàng đợi. Mỗi kênh vào hàng đợi riêng: một kênh lỗi
     * không chặn kênh khác. Dùng trạng thái đang hiển thị; hàng đợi tự chặn trùng nếu trạng thái vừa đổi.
     */
    fun onPublishAll(topicId: String) {
        val topic = uiState.value.topics.firstOrNull { it.topicId == topicId } ?: return
        val targets = topic.channels.filter { it.action == ChannelAction.PUBLISH }
        if (targets.isEmpty()) {
            send(R.string.publish_status_nothing_to_publish)
            return
        }
        val skippedUnapproved = topic.channels.any { it.action == ChannelAction.NEEDS_APPROVAL }
        viewModelScope.launch {
            var queued = 0
            var failed = 0
            for (target in targets) {
                when (safeEnqueue(target.state.draftId)) {
                    is EnqueueResult.Queued -> queued++
                    // Đã ở hàng đợi hoặc đã đăng (bấm hai lần, hoặc màn hình chưa kịp cập nhật): không phải lỗi.
                    is EnqueueResult.AlreadyActive, is EnqueueResult.AlreadyPublished -> Unit
                    else -> failed++
                }
            }
            send(
                when {
                    failed > 0 -> R.string.publish_status_action_failed
                    queued == 0 -> R.string.review_publish_already_queued
                    skippedUnapproved -> R.string.publish_status_queued_some_unapproved
                    else -> R.string.publish_status_all_queued
                },
            )
        }
    }

    /** Đăng (hoặc đăng lại sau khi lỗi) một kênh. */
    fun onPublishChannel(draftId: String) {
        viewModelScope.launch {
            send(
                when (val result = safeEnqueue(draftId)) {
                    is EnqueueResult.Queued -> R.string.review_publish_queued
                    is EnqueueResult.AlreadyActive ->
                        if (result.status == PostStatus.QUEUED) {
                            R.string.review_publish_already_queued
                        } else {
                            R.string.review_publish_in_progress
                        }
                    is EnqueueResult.AlreadyPublished -> R.string.review_publish_already_published
                    EnqueueResult.NotApproved -> R.string.review_publish_blocked_unapproved
                    EnqueueResult.DraftNotFound, null -> R.string.publish_status_action_failed
                },
            )
        }
    }

    fun onConfirmPublished(postId: String) {
        viewModelScope.launch {
            send(
                when (safeCall { tracker.confirmPublished(postId) }) {
                    true -> R.string.publish_status_marked_published
                    false -> R.string.publish_status_state_changed
                    null -> R.string.publish_status_action_failed
                },
            )
        }
    }

    fun onRequeueRequested(postId: String) {
        _events.trySend(PublishStatusEvent.ConfirmRequeue(postId))
    }

    fun onRequeueConfirmed(postId: String) {
        viewModelScope.launch {
            send(
                when (safeCall { tracker.requeueAfterCheck(postId) }) {
                    true -> R.string.publish_status_requeued
                    false -> R.string.publish_status_state_changed
                    null -> R.string.publish_status_action_failed
                },
            )
        }
    }

    /** null khi lỗi lưu trữ; huỷ coroutine vẫn được truyền tiếp. */
    private suspend fun safeEnqueue(draftId: String): EnqueueResult? = safeCall { publishQueue.enqueue(draftId) }

    private suspend fun <T> safeCall(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun send(@StringRes messageRes: Int) {
        _events.trySend(PublishStatusEvent.ShowMessage(messageRes))
    }

    private fun TopicPublishState.toItem() = TopicPublishItem(
        topicId = topicId,
        previewText = previewText,
        channels = channels.map {
            ChannelPublishItem(
                state = it,
                action = actionFor(it),
                openUrl = openableUrl(it),
                reasonRes = reasonFor(it),
            )
        },
    )

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * Link bài gốc mở được (#40): chỉ bài [PostStatus.PUBLISHED] và chỉ `http`/`https`. Link do API mạng xã hội
         * trả về hoặc người dùng nhập, nên chặn scheme khác (`intent:`, `javascript:`, `file:`…) để không mở nhầm
         * ứng dụng hay tệp trên máy.
         */
        fun openableUrl(state: ChannelPublishState): String? {
            if (state.status != PostStatus.PUBLISHED) return null
            // Không trim: AC #40 chặn link có khoảng trắng ở bất kỳ đâu, kể cả đầu/cuối (review của Minh ở #106).
            val url = state.url ?: return null
            if (url.isEmpty() || url.any(Char::isWhitespace)) return null
            // Parse thay vì chỉ so tiền tố, để chặn link thiếu host như `https:///post/1` hay `https://?q=1`.
            val uri = try {
                URI(url)
            } catch (_: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.lowercase()
            return url.takeIf { scheme in WEB_SCHEMES && !uri.host.isNullOrEmpty() }
        }

        private val WEB_SCHEMES = setOf("https", "http")

        /** Lý do hiển thị (#39): chỉ cho bài lỗi và bài đang chờ thử lại; bài đã đăng có thể còn lý do cũ. */
        @StringRes
        fun reasonFor(state: ChannelPublishState): Int? = when (state.status) {
            PostStatus.FAILED, PostStatus.QUEUED -> PublishErrorMessages.messageFor(state.errorReason)
            else -> null
        }

        /** Thao tác cho một kênh; tách hàm thuần để test. */
        fun actionFor(state: ChannelPublishState): ChannelAction = when {
            state.needsConfirmation && state.postId != null -> ChannelAction.CONFIRM_OUTCOME
            state.status == PostStatus.DRAFT || state.status == PostStatus.FAILED ->
                if (state.approved) ChannelAction.PUBLISH else ChannelAction.NEEDS_APPROVAL
            else -> ChannelAction.NONE
        }
    }
}
