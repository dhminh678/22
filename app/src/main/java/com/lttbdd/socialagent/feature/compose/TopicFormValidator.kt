package com.lttbdd.socialagent.feature.compose

import androidx.annotation.StringRes
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType

/**
 * Logic kiểm tra tính hợp lệ của biểu mẫu nhập thông tin topic/brief (F3).
 */
object TopicFormValidator {

    @StringRes
    fun validateTopic(topic: String): Int? {
        return if (topic.trim().isEmpty()) R.string.compose_topic_error else null
    }

    @StringRes
    fun validateGoal(goal: String): Int? {
        return if (goal.trim().isEmpty()) R.string.compose_goal_error else null
    }

    @StringRes
    fun validateAudience(audience: String): Int? {
        return if (audience.trim().isEmpty()) R.string.compose_audience_error else null
    }

    @StringRes
    fun validateTone(tone: String): Int? {
        return if (tone.trim().isEmpty()) R.string.compose_tone_error else null
    }

    @StringRes
    fun validateChannels(channels: Set<ChannelType>): Int? {
        return if (channels.isEmpty()) R.string.compose_channels_error else null
    }

    fun isFormValid(
        topic: String,
        goal: String,
        audience: String,
        tone: String,
        channels: Set<ChannelType>,
    ): Boolean {
        return topic.trim().isNotEmpty() &&
            goal.trim().isNotEmpty() &&
            audience.trim().isNotEmpty() &&
            tone.trim().isNotEmpty() &&
            channels.isNotEmpty()
    }
}
