package com.lttbdd.socialagent.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lttbdd.socialagent.core.model.ChannelType

/** Lần dọn dữ liệu gần nhất đã hoàn tất cho mỗi mạng xã hội. */
@Entity(tableName = "channel_cleanup_receipts")
data class ChannelCleanupReceipt(
    @PrimaryKey val channel: ChannelType,
    val operationId: String,
)
