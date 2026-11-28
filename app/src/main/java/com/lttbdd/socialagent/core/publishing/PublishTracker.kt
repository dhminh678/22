package com.lttbdd.socialagent.core.publishing

import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.PostStatus
import kotlinx.coroutines.flow.Flow

/**
 * Trạng thái đăng của MỘT kênh (một bản nháp) trong một topic (F5, #36).
 *
 * [status] là trạng thái của lần đăng gần nhất; bản nháp chưa vào hàng đợi lần nào có [PostStatus.DRAFT].
 */
data class ChannelPublishState(
    val draftId: String,
    val channel: ChannelType,
    val approved: Boolean,
    val status: PostStatus,
    /** null khi bản nháp chưa vào hàng đợi lần nào. */
    val postId: String? = null,
    /** Bài đang [PostStatus.PUBLISHING] nhưng không rõ đã lên mạng xã hội chưa: chờ người dùng kiểm tra. */
    val needsConfirmation: Boolean = false,
    val url: String? = null,
    /** Mã lý do lỗi do hàng đợi lưu (không phải câu hiển thị); #39 đổi sang chuỗi VI/EN. */
    val errorReason: String? = null,
)

/** Các kênh của cùng một topic, để đăng một lúc lên nhiều kênh và xem trạng thái từng kênh. */
data class TopicPublishState(
    val topicId: String,
    /** Nội dung bản nháp đầu tiên, dùng làm tiêu đề nhận diện topic. */
    val previewText: String,
    val channels: List<ChannelPublishState>,
)

/** Theo dõi trạng thái đăng theo topic và xử lý bài không rõ kết quả (F5, #36). */
interface PublishTracker {

    /** Topic có hoạt động gần nhất lên đầu; trong mỗi topic, kênh xếp theo thứ tự [ChannelType]. */
    fun observeTopics(): Flow<List<TopicPublishState>>

    /** Người dùng đã thấy bài trên mạng xã hội. Trả về false nếu bài không còn chờ xác nhận. */
    suspend fun confirmPublished(postId: String): Boolean

    /** Xác nhận bài đã đăng và lưu URL đã được kiểm tra trong cùng thao tác. */
    suspend fun confirmPublishedWithPermalink(postId: String, url: String): Boolean = false

    /** Người dùng đã kiểm tra và bài chưa lên: đưa lại hàng đợi. Trả về false nếu bài không còn chờ xác nhận. */
    suspend fun requeueAfterCheck(postId: String): Boolean
}
