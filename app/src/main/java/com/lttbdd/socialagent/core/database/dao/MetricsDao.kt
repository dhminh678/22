package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.lttbdd.socialagent.core.database.entity.MetricsSnapshotEntity
import com.lttbdd.socialagent.core.model.ChannelType
import kotlinx.coroutines.flow.Flow

@Dao
interface MetricsDao {

    @Insert
    suspend fun insert(snapshot: MetricsSnapshotEntity)

    /** Các snapshot của một bài theo thời gian tăng dần, dùng để vẽ đường tăng trưởng (F7). */
    @Query(
        """
        SELECT * FROM metrics_snapshots
        WHERE channel = :channel AND remotePostId = :remotePostId
        ORDER BY capturedAtMillis
        """,
    )
    fun observeHistory(channel: ChannelType, remotePostId: String): Flow<List<MetricsSnapshotEntity>>

    @Query(
        """
        SELECT * FROM metrics_snapshots
        WHERE channel = :channel AND remotePostId = :remotePostId
        ORDER BY capturedAtMillis DESC
        LIMIT 1
        """,
    )
    suspend fun getLatest(channel: ChannelType, remotePostId: String): MetricsSnapshotEntity?

    /**
     * Dọn snapshot cũ để bảng không phình mãi. Trả về số dòng đã xoá.
     * Lưu ý: xoá theo thời gian nên bài cũ có thể mất hết snapshot; khi làm F7 cần giữ lại bản mới nhất của mỗi bài.
     */
    @Query("DELETE FROM metrics_snapshots WHERE capturedAtMillis < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long): Int

    @Query("DELETE FROM metrics_snapshots WHERE channel = :channel")
    suspend fun deleteByChannel(channel: ChannelType)
}
