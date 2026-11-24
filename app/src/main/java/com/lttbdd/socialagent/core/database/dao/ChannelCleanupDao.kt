package com.lttbdd.socialagent.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lttbdd.socialagent.core.database.entity.ChannelCleanupReceipt
import com.lttbdd.socialagent.core.model.ChannelType

@Dao
interface ChannelCleanupDao {

    @Query("SELECT operationId FROM channel_cleanup_receipts WHERE channel = :channel")
    suspend fun getCompletedOperation(channel: ChannelType): String?

    @Upsert
    suspend fun recordCompletion(receipt: ChannelCleanupReceipt)
}
