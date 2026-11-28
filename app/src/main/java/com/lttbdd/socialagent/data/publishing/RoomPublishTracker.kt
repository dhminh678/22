package com.lttbdd.socialagent.data.publishing

import com.lttbdd.socialagent.core.database.dao.DraftDao
import com.lttbdd.socialagent.core.database.dao.PostDao
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.database.entity.PostEntity
import com.lttbdd.socialagent.core.model.PostStatus
import com.lttbdd.socialagent.core.publishing.ChannelPublishState
import com.lttbdd.socialagent.core.publishing.PublishTracker
import com.lttbdd.socialagent.core.publishing.TopicPublishState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ghép bảng `drafts` và `posts` thành trạng thái từng kênh theo topic. Đọc hoàn toàn từ Room nên xem được khi offline.
 * Xác nhận bài không rõ kết quả đi qua [PublishQueueRepository] để giữ một đường ghi duy nhất vào hàng đợi.
 */
@Singleton
class RoomPublishTracker @Inject constructor(
    private val draftDao: DraftDao,
    private val postDao: PostDao,
    private val queue: PublishQueueRepository,
) : PublishTracker {

    override fun observeTopics(): Flow<List<TopicPublishState>> =
        combine(draftDao.observeAll(), postDao.observeAll(), ::buildTopicStates)

    override suspend fun confirmPublished(postId: String): Boolean = queue.confirmPublishedManually(postId)

    override suspend fun requeueAfterCheck(postId: String): Boolean = queue.requeueAfterUserCheck(postId)

    companion object {

        /**
         * Hàm thuần để test không cần Room. Mỗi bản nháp lấy bài **tạo gần nhất**: bài FAILED cũ bị thay bằng lần
         * đăng lại mới. Bài đã mất bản nháp (`draftId = null`) không thuộc topic nào nên bị bỏ qua.
         */
        fun buildTopicStates(drafts: List<DraftEntity>, posts: List<PostEntity>): List<TopicPublishState> {
            val latestPostByDraft = posts
                .filter { it.draftId != null }
                .groupBy { it.draftId!! }
                .mapValues { (_, list) -> list.maxBy { it.createdAtMillis } }

            return drafts
                .groupBy { it.topicId }
                .map { (topicId, topicDrafts) ->
                    val sorted = topicDrafts.sortedBy { it.channel.ordinal }
                    val lastActivity = sorted.maxOf { draft ->
                        maxOf(draft.updatedAtMillis, latestPostByDraft[draft.id]?.updatedAtMillis ?: 0L)
                    }
                    lastActivity to TopicPublishState(
                        topicId = topicId,
                        previewText = sorted.first().text,
                        channels = sorted.map { it.toChannelState(latestPostByDraft[it.id]) },
                    )
                }
                .sortedByDescending { it.first }
                .map { it.second }
        }

        private fun DraftEntity.toChannelState(post: PostEntity?) = ChannelPublishState(
            draftId = id,
            channel = channel,
            approved = approved,
            status = post?.status ?: PostStatus.DRAFT,
            postId = post?.id,
            needsConfirmation = post != null && post.status == PostStatus.PUBLISHING && post.outcomeUnknown,
            url = post?.url,
            errorReason = post?.errorMessage,
        )
    }
}
