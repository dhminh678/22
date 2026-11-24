package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.lttbdd.socialagent.core.database.entity.CommentEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.CommentCategory
import com.lttbdd.socialagent.core.model.CommentStatus
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CommentDao {

    /** Hộp thư comment hợp nhất (F6). Tham số null nghĩa là không lọc theo tiêu chí đó. */
    @Query(
        """
        SELECT * FROM comments
        WHERE (:channel IS NULL OR channel = :channel)
          AND (:remotePostId IS NULL OR remotePostId = :remotePostId)
          AND (:status IS NULL OR status = :status)
        ORDER BY createdAtMillis DESC
        """,
    )
    abstract fun observe(
        channel: ChannelType? = null,
        remotePostId: String? = null,
        status: CommentStatus? = null,
    ): Flow<List<CommentEntity>>

    /** Ghi đè toàn bộ comment, kể cả `status` và `category`. Đồng bộ từ mạng thì dùng [syncFromRemote]. */
    @Upsert
    abstract suspend fun upsertAll(comments: List<CommentEntity>)

    /**
     * Lưu comment vừa tải từ mạng xã hội: comment mới được thêm, comment đã có chỉ cập nhật
     * nội dung và tên người viết, giữ nguyên `status` (đã trả lời…) và `category` do AI phân loại.
     */
    @Transaction
    open suspend fun syncFromRemote(comments: List<CommentEntity>) {
        insertIgnoringExisting(comments)
        comments.forEach { updateContent(it.channel, it.id, it.authorName, it.text) }
    }

    @Query("UPDATE comments SET status = :status WHERE channel = :channel AND id = :id")
    abstract suspend fun updateStatus(channel: ChannelType, id: String, status: CommentStatus)

    @Query("UPDATE comments SET category = :category WHERE channel = :channel AND id = :id")
    abstract suspend fun updateCategory(channel: ChannelType, id: String, category: CommentCategory)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnoringExisting(comments: List<CommentEntity>)

    @Query("UPDATE comments SET authorName = :authorName, text = :text WHERE channel = :channel AND id = :id")
    protected abstract suspend fun updateContent(channel: ChannelType, id: String, authorName: String, text: String)

    @Query("DELETE FROM comments WHERE channel = :channel")
    abstract suspend fun deleteByChannel(channel: ChannelType)
}
