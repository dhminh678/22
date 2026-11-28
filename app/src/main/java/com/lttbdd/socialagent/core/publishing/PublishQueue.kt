package com.lttbdd.socialagent.core.publishing

import com.lttbdd.socialagent.core.model.PostStatus

/**
 * Hợp đồng hàng đợi đăng bài F5 (#37, #38) cho các màn hình gọi sang (quy tắc 3 của AGENTS.md).
 *
 * Bấm Đăng chỉ thêm bài vào hàng đợi; bài được đăng sau đó trong nền khi có mạng.
 * Vì vậy [EnqueueResult.Queued] **không** có nghĩa là bài đã lên mạng xã hội.
 */
interface PublishQueue {

    /** Thêm bản nháp đã duyệt vào hàng đợi; gọi lặp không tạo bài trùng. */
    suspend fun enqueue(draftId: String): EnqueueResult
}

/** Kết quả khi người dùng bấm Đăng một bản nháp. */
sealed interface EnqueueResult {
    /** Đã thêm vào hàng đợi; sẽ tự đăng khi có mạng. */
    data class Queued(val postId: String) : EnqueueResult

    /**
     * Bấm lặp: bản nháp đã có bài đang chờ, đang đăng, chờ xác nhận hoặc đã bàn giao — không tạo bài mới.
     * [PostStatus.PUBLISHING] gộp cả "đang đăng" lẫn "chờ người dùng xác nhận" (`outcomeUnknown`).
     */
    data class AlreadyActive(val postId: String, val status: PostStatus) : EnqueueResult

    /** Bản nháp đã được đăng thành công trước đó. */
    data class AlreadyPublished(val postId: String, val url: String?) : EnqueueResult

    data object DraftNotFound : EnqueueResult

    /** Chưa xác định được một tài khoản đích duy nhất để đăng bài. */
    data object DestinationRequired : EnqueueResult

    /** Bản nháp chưa được duyệt: agent không được tự đăng (yêu cầu F4, quy tắc 6 của AGENTS.md). */
    data object NotApproved : EnqueueResult
}
