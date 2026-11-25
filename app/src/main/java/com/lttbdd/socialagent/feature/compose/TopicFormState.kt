package com.lttbdd.socialagent.feature.compose

import androidx.annotation.StringRes
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ContentLanguage
import com.lttbdd.socialagent.core.model.TopicBrief
import java.util.UUID

/**
 * Trạng thái dữ liệu và lỗi của biểu mẫu nhập topic.
 */
data class TopicFormState(
    val topic: String = "",
    val goal: String = "",
    val audience: String = "",
    val tone: String = "",
    val language: ContentLanguage = ContentLanguage.VI,
    val selectedChannels: Set<ChannelType> = emptySet(),
    @param:StringRes val topicError: Int? = null,
    @param:StringRes val goalError: Int? = null,
    @param:StringRes val audienceError: Int? = null,
    @param:StringRes val toneError: Int? = null,
    @param:StringRes val channelsError: Int? = null,
    val isFormValid: Boolean = false,
) {
    fun toTopicBrief(id: String = UUID.randomUUID().toString()): TopicBrief {
        return TopicBrief(
            id = id,
            topic = topic.trim(),
            goal = goal.trim(),
            audience = audience.trim(),
            tone = tone.trim(),
            language = language,
            channels = selectedChannels,
        )
    }
}

/**
 * Trạng thái UI tổng thể cho màn hình nhập topic.
 */
data class ComposeUiState(
    val formState: TopicFormState = TopicFormState(),
    val submittedBrief: TopicBrief? = null,
)

/**
 * Sự kiện dùng một lần (one-shot event) phát từ [ComposeViewModel] sang [ComposeFragment].
 */
sealed interface ComposeEvent {
    data class BriefSubmitted(
        val brief: TopicBrief,
        @param:StringRes val messageRes: Int,
    ) : ComposeEvent
}
