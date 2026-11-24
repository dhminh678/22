package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lttbdd.socialagent.core.database.entity.DraftEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.PostStatus
import kotlinx.coroutines.flow.Flow

/**
 * Kết quả của [DraftDao.deleteWithPendingPosts].
 * - [postsRemoved]: số bài chưa đăng đã bị bỏ — **luôn được bỏ**, kể cả khi bản nháp được giữ lại.
 * - [postsInFlight] > 0 (khi đó [deleted] = false): còn bài đang đăng hoặc chưa rõ kết quả nên bản nháp được giữ;
 *   UI nên báo "đã bỏ [postsRemoved] bài, còn [postsInFlight] bài đang đăng, thử lại sau".
 */
data class DraftDeletion(val deleted: Boolean, val postsRemoved: Int, val postsInFlight: Int)

@Dao
abstract class DraftDao {

    /** Mọi bản nháp, mới sửa nhất lên đầu. Flow tự phát lại khi bảng thay đổi. */
    @Query("SELECT * FROM drafts ORDER BY updatedAtMillis DESC")
    abstract fun observeAll(): Flow<List<DraftEntity>>

    @Query("SELECT * FROM drafts WHERE topicId = :topicId ORDER BY channel")
    abstract fun observeByTopic(topicId: String): Flow<List<DraftEntity>>

    @Query("SELECT * FROM drafts WHERE id = :id")
    abstract suspend fun getById(id: String): DraftEntity?

    /**
     * Cập nhật bản nháp theo `id`, chưa có thì thêm mới.
     * Mỗi topic chỉ có một bản nháp cho mỗi kênh (unique `topicId, channel`): khi sinh lại bản nháp hãy
     * dùng lại `id` cũ của kênh đó. Thêm bản nháp khác `id` cho cùng topic và kênh sẽ ném
     * `SQLiteConstraintException` (không dùng `@Upsert` vì nó âm thầm bỏ qua trường hợp này).
     */
    @Transaction
    open suspend fun upsert(draft: DraftEntity) {
        if (update(draft) == 0) insert(draft)
    }

    @Transaction
    open suspend fun upsertAll(drafts: List<DraftEntity>) {
        drafts.forEach { upsert(it) }
    }

    /**
     * Xoá bản nháp, chỉ khi mọi bài đăng của nó đã [PostStatus.PUBLISHED]. Bảng `posts` không lưu nội dung,
     * nên bài còn ở trạng thái khác (QUEUED, PUBLISHING, FAILED chờ thử lại, HANDED_OFF chờ người dùng tự đăng…)
     * vẫn cần bản nháp. Trả về số dòng đã xoá: 0 nghĩa là không tồn tại hoặc bị chặn — dùng [countPendingPosts]
     * để biết lý do, và [deleteWithPendingPosts] khi người dùng xác nhận bỏ luôn các bài chưa đăng.
     */
    open suspend fun deleteById(id: String): Int = deleteIfAllPostsIn(id, PostStatus.PUBLISHED)

    /** Số bài của bản nháp chưa [PostStatus.PUBLISHED] — lớn hơn 0 thì [deleteById] sẽ bị chặn. */
    open suspend fun countPendingPosts(draftId: String): Int = countPostsNotIn(draftId, PostStatus.PUBLISHED)

    /**
     * Lối thoát có chủ đích khi người dùng xác nhận bỏ các bài chưa đăng (bài lỗi, bài bàn giao, bài còn trong hàng đợi…).
     * Không bao giờ bỏ bài đang [PostStatus.PUBLISHING] hoặc có `outcomeUnknown = true`: bài đó có thể đã lên mạng xã hội,
     * xoá đi sẽ mất khoá chống đăng trùng. Khi còn bài như vậy, bản nháp được giữ lại để thử lại sau, nhưng các bài
     * khác vẫn đã bị bỏ — xem [DraftDeletion.postsRemoved] để báo đúng cho người dùng.
     * Caller nên huỷ job đăng (WorkManager) của các bài `QUEUED` trước khi gọi.
     */
    @Transaction
    open suspend fun deleteWithPendingPosts(id: String): DraftDeletion {
        val removed = deleteAbandonablePosts(id, PostStatus.PUBLISHED, PostStatus.PUBLISHING)
        val inFlight = countPostsNotIn(id, PostStatus.PUBLISHED)
        val deleted = deleteIfAllPostsIn(id, PostStatus.PUBLISHED) > 0
        return DraftDeletion(deleted = deleted, postsRemoved = removed, postsInFlight = inFlight)
    }

    @Query("SELECT COUNT(*) FROM posts WHERE draftId = :draftId AND status != :finishedStatus")
    protected abstract suspend fun countPostsNotIn(draftId: String, finishedStatus: PostStatus): Int

    @Query(
        """
        DELETE FROM posts
        WHERE draftId = :draftId
          AND status != :finishedStatus
          AND status != :inFlightStatus
          AND outcomeUnknown = 0
        """,
    )
    protected abstract suspend fun deleteAbandonablePosts(
        draftId: String,
        finishedStatus: PostStatus,
        inFlightStatus: PostStatus,
    ): Int

    /** Truyền trạng thái bằng tham số thay vì viết cứng chuỗi trong SQL, để đổi tên enum không làm mất tác dụng. */
    @Query(
        """
        DELETE FROM drafts
        WHERE id = :id
          AND NOT EXISTS (
            SELECT 1 FROM posts
            WHERE posts.draftId = :id AND posts.status != :finishedStatus
          )
        """,
    )
    protected abstract suspend fun deleteIfAllPostsIn(id: String, finishedStatus: PostStatus): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(draft: DraftEntity)

    @Update
    protected abstract suspend fun update(draft: DraftEntity): Int

    @Query("DELETE FROM drafts WHERE channel = :channel")
    abstract suspend fun deleteByChannel(channel: ChannelType)
}
