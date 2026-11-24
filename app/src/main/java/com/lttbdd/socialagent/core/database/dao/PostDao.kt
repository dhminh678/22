package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.lttbdd.socialagent.core.database.entity.PostEntity
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.PostStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PostDao {

    @Query("SELECT * FROM posts ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE draftId = :draftId ORDER BY createdAtMillis DESC")
    fun observeByDraft(draftId: String): Flow<List<PostEntity>>

    @Query("SELECT * FROM posts WHERE status IN (:statuses) ORDER BY createdAtMillis")
    suspend fun getByStatus(statuses: List<PostStatus>): List<PostEntity>

    @Query("SELECT * FROM posts WHERE idempotencyKey = :idempotencyKey")
    suspend fun getByIdempotencyKey(idempotencyKey: String): PostEntity?

    /**
     * Thêm bài đăng mới; chặn tạo **bản ghi** trùng (chặn đăng trùng thật sự cần thêm `outcomeUnknown`, xem #38).
     * Trả về -1 khi [PostEntity.idempotencyKey] hoặc [PostEntity.id] đã tồn tại. Khi nhận -1, gọi
     * [getByIdempotencyKey] để xác nhận đây đúng là lần bấm đăng cũ trước khi coi là trùng.
     * `IGNORE` không áp dụng cho khoá ngoại: [PostEntity.draftId] trỏ tới bản nháp không tồn tại
     * vẫn ném `SQLiteConstraintException`, nên phải lưu bản nháp trước.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(post: PostEntity): Long

    @Update
    suspend fun update(post: PostEntity)

    @Query("DELETE FROM posts WHERE channel = :channel")
    suspend fun deleteByChannel(channel: ChannelType)
}
