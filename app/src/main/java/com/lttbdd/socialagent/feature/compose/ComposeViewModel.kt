package com.lttbdd.socialagent.feature.compose

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ContentLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import javax.inject.Inject

/**
 * ViewModel quản lý biểu mẫu nhập topic, validation, khôi phục trạng thái qua [SavedStateHandle]
 * (an toàn khi process death) và đóng gói [com.lttbdd.socialagent.core.model.TopicBrief].
 */
@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ComposeUiState(formState = restoreFormState()))
    val uiState: StateFlow<ComposeUiState> = _uiState.asStateFlow()

    private val _events = Channel<ComposeEvent>(Channel.BUFFERED)
    val events: Flow<ComposeEvent> = _events.receiveAsFlow()

    fun onTopicChanged(topic: String) {
        _uiState.update { current ->
            if (topic == current.formState.topic) return@update current
            savedStateHandle[KEY_TOPIC] = topic
            val updated = current.formState.copy(
                topic = topic,
                topicError = TopicFormValidator.validateTopic(topic),
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onTopicFocusLost() {
        _uiState.update { current ->
            val updated = current.formState.copy(
                topicError = TopicFormValidator.validateTopic(current.formState.topic),
            )
            current.copy(formState = updated)
        }
    }

    fun onGoalChanged(goal: String) {
        _uiState.update { current ->
            if (goal == current.formState.goal) return@update current
            savedStateHandle[KEY_GOAL] = goal
            val updated = current.formState.copy(
                goal = goal,
                goalError = TopicFormValidator.validateGoal(goal),
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onGoalFocusLost() {
        _uiState.update { current ->
            val updated = current.formState.copy(
                goalError = TopicFormValidator.validateGoal(current.formState.goal),
            )
            current.copy(formState = updated)
        }
    }

    fun onAudienceChanged(audience: String) {
        _uiState.update { current ->
            if (audience == current.formState.audience) return@update current
            savedStateHandle[KEY_AUDIENCE] = audience
            val updated = current.formState.copy(
                audience = audience,
                audienceError = TopicFormValidator.validateAudience(audience),
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onAudienceFocusLost() {
        _uiState.update { current ->
            val updated = current.formState.copy(
                audienceError = TopicFormValidator.validateAudience(current.formState.audience),
            )
            current.copy(formState = updated)
        }
    }

    fun onToneChanged(tone: String) {
        _uiState.update { current ->
            if (tone == current.formState.tone) return@update current
            savedStateHandle[KEY_TONE] = tone
            val updated = current.formState.copy(
                tone = tone,
                toneError = TopicFormValidator.validateTone(tone),
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onToneFocusLost() {
        _uiState.update { current ->
            val updated = current.formState.copy(
                toneError = TopicFormValidator.validateTone(current.formState.tone),
            )
            current.copy(formState = updated)
        }
    }

    fun onLanguageChanged(language: ContentLanguage) {
        _uiState.update { current ->
            if (language == current.formState.language) return@update current
            savedStateHandle[KEY_LANGUAGE] = language.name
            val updated = current.formState.copy(language = language)
            current.copy(formState = updateValidity(updated))
        }
    }

    fun onChannelToggled(channel: ChannelType, isSelected: Boolean) {
        _uiState.update { current ->
            val newChannels = if (isSelected) {
                current.formState.selectedChannels + channel
            } else {
                current.formState.selectedChannels - channel
            }
            if (newChannels == current.formState.selectedChannels) return@update current
            savedStateHandle[KEY_CHANNELS] = ArrayList(newChannels.map { it.name })
            val updated = current.formState.copy(
                selectedChannels = newChannels,
                channelsError = TopicFormValidator.validateChannels(newChannels),
            )
            current.copy(formState = updateValidity(updated))
        }
    }

    /**
     * Xác thực toàn bộ biểu mẫu. Nếu có trường lỗi, gắn thông báo lỗi tương ứng.
     * Trả về true nếu toàn bộ dữ liệu hợp lệ.
     */
    fun validate(): Boolean {
        val updatedState = _uiState.updateAndGet { current ->
            val state = current.formState
            val topicError = TopicFormValidator.validateTopic(state.topic)
            val goalError = TopicFormValidator.validateGoal(state.goal)
            val audienceError = TopicFormValidator.validateAudience(state.audience)
            val toneError = TopicFormValidator.validateTone(state.tone)
            val channelsError = TopicFormValidator.validateChannels(state.selectedChannels)

            val isValid = topicError == null &&
                goalError == null &&
                audienceError == null &&
                toneError == null &&
                channelsError == null

            val validatedForm = state.copy(
                topicError = topicError,
                goalError = goalError,
                audienceError = audienceError,
                toneError = toneError,
                channelsError = channelsError,
                isFormValid = isValid,
            )
            current.copy(formState = validatedForm)
        }
        return updatedState.formState.isFormValid
    }

    /**
     * Bấm nút Tiếp tục: kiểm tra hợp lệ, đóng gói [com.lttbdd.socialagent.core.model.TopicBrief]
     * và phát sự kiện [ComposeEvent.BriefSubmitted].
     * Trong phạm vi Issue #32 (chưa gắn màn tiến độ AI ở F2), form giữ nguyên dữ liệu để người dùng
     * có thể chỉnh sửa lại và UI hiển thị thông báo xác nhận đã tạo brief.
     */
    fun onSubmit() {
        if (!validate()) return

        val brief = _uiState.value.formState.toTopicBrief()
        _uiState.update { current ->
            current.copy(submittedBrief = brief)
        }
        _events.trySend(ComposeEvent.BriefSubmitted(brief, R.string.compose_submit_success))
    }

    private fun restoreFormState(): TopicFormState {
        val topic = savedStateHandle.get<String>(KEY_TOPIC).orEmpty()
        val goal = savedStateHandle.get<String>(KEY_GOAL).orEmpty()
        val audience = savedStateHandle.get<String>(KEY_AUDIENCE).orEmpty()
        val tone = savedStateHandle.get<String>(KEY_TONE).orEmpty()
        val language = savedStateHandle.get<String>(KEY_LANGUAGE)
            ?.let { name -> ContentLanguage.entries.firstOrNull { it.name == name } }
            ?: ContentLanguage.VI
        val channels = savedStateHandle.get<List<String>>(KEY_CHANNELS)
            ?.mapNotNull { name -> ChannelType.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?: emptySet()

        return updateValidity(
            TopicFormState(
                topic = topic,
                goal = goal,
                audience = audience,
                tone = tone,
                language = language,
                selectedChannels = channels,
            ),
        )
    }

    private fun updateValidity(state: TopicFormState): TopicFormState {
        val valid = TopicFormValidator.isFormValid(
            topic = state.topic,
            goal = state.goal,
            audience = state.audience,
            tone = state.tone,
            channels = state.selectedChannels,
        )
        return state.copy(isFormValid = valid)
    }

    private companion object {
        const val KEY_TOPIC = "compose_topic"
        const val KEY_GOAL = "compose_goal"
        const val KEY_AUDIENCE = "compose_audience"
        const val KEY_TONE = "compose_tone"
        const val KEY_LANGUAGE = "compose_language"
        const val KEY_CHANNELS = "compose_channels"
    }
}
