package com.lttbdd.socialagent.feature.compose

import androidx.lifecycle.SavedStateHandle
import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ContentLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ComposeViewModelTest {

    private lateinit var savedStateHandle: SavedStateHandle
    private lateinit var viewModel: ComposeViewModel

    @Before
    fun setUp() {
        savedStateHandle = SavedStateHandle()
        viewModel = ComposeViewModel(savedStateHandle)
    }

    @Test
    fun `initial state has empty fields, no errors, and invalid form`() {
        val state = viewModel.uiState.value
        assertEquals("", state.formState.topic)
        assertEquals("", state.formState.goal)
        assertEquals("", state.formState.audience)
        assertEquals("", state.formState.tone)
        assertEquals(ContentLanguage.VI, state.formState.language)
        assertTrue(state.formState.selectedChannels.isEmpty())
        assertNull(state.formState.topicError)
        assertNull(state.formState.goalError)
        assertNull(state.formState.audienceError)
        assertNull(state.formState.toneError)
        assertNull(state.formState.channelsError)
        assertFalse(state.formState.isFormValid)
        assertNull(state.submittedBrief)
    }

    @Test
    fun `changing language updates language in form state`() {
        viewModel.onLanguageChanged(ContentLanguage.EN)
        assertEquals(ContentLanguage.EN, viewModel.uiState.value.formState.language)

        viewModel.onLanguageChanged(ContentLanguage.VI)
        assertEquals(ContentLanguage.VI, viewModel.uiState.value.formState.language)
    }

    @Test
    fun `toggling channels adds and removes channels and shows error when all unchecked`() {
        viewModel.onChannelToggled(ChannelType.FACEBOOK, true)
        assertEquals(setOf(ChannelType.FACEBOOK), viewModel.uiState.value.formState.selectedChannels)
        assertNull(viewModel.uiState.value.formState.channelsError)

        viewModel.onChannelToggled(ChannelType.LINKEDIN, true)
        assertEquals(
            setOf(ChannelType.FACEBOOK, ChannelType.LINKEDIN),
            viewModel.uiState.value.formState.selectedChannels,
        )
        assertNull(viewModel.uiState.value.formState.channelsError)

        viewModel.onChannelToggled(ChannelType.FACEBOOK, false)
        assertEquals(setOf(ChannelType.LINKEDIN), viewModel.uiState.value.formState.selectedChannels)
        assertNull(viewModel.uiState.value.formState.channelsError)

        viewModel.onChannelToggled(ChannelType.LINKEDIN, false)
        assertTrue(viewModel.uiState.value.formState.selectedChannels.isEmpty())
        assertEquals(R.string.compose_channels_error, viewModel.uiState.value.formState.channelsError)
    }

    @Test
    fun `typing then clearing text fields immediately shows validation errors`() {
        viewModel.onTopicChanged("Draft topic")
        assertNull(viewModel.uiState.value.formState.topicError)
        viewModel.onTopicChanged("")
        assertEquals(R.string.compose_topic_error, viewModel.uiState.value.formState.topicError)

        viewModel.onGoalChanged("Draft goal")
        assertNull(viewModel.uiState.value.formState.goalError)
        viewModel.onGoalChanged("   ")
        assertEquals(R.string.compose_goal_error, viewModel.uiState.value.formState.goalError)

        viewModel.onAudienceChanged("Devs")
        assertNull(viewModel.uiState.value.formState.audienceError)
        viewModel.onAudienceChanged("")
        assertEquals(R.string.compose_audience_error, viewModel.uiState.value.formState.audienceError)

        viewModel.onToneChanged("professional")
        assertNull(viewModel.uiState.value.formState.toneError)
        viewModel.onToneChanged("")
        assertEquals(R.string.compose_tone_error, viewModel.uiState.value.formState.toneError)
    }

    @Test
    fun `focus lost on empty fields immediately shows validation errors`() {
        viewModel.onTopicFocusLost()
        assertEquals(R.string.compose_topic_error, viewModel.uiState.value.formState.topicError)

        viewModel.onGoalFocusLost()
        assertEquals(R.string.compose_goal_error, viewModel.uiState.value.formState.goalError)

        viewModel.onAudienceFocusLost()
        assertEquals(R.string.compose_audience_error, viewModel.uiState.value.formState.audienceError)

        viewModel.onToneFocusLost()
        assertEquals(R.string.compose_tone_error, viewModel.uiState.value.formState.toneError)
    }

    @Test
    fun `unchanged initial value does not falsely trigger validation errors`() {
        viewModel.onTopicChanged("")
        viewModel.onGoalChanged("")
        viewModel.onAudienceChanged("")
        viewModel.onToneChanged("")
        viewModel.onChannelToggled(ChannelType.FACEBOOK, false)

        val form = viewModel.uiState.value.formState
        assertNull(form.topicError)
        assertNull(form.goalError)
        assertNull(form.audienceError)
        assertNull(form.toneError)
        assertNull(form.channelsError)
    }

    @Test
    fun `form becomes valid when all required fields are filled`() {
        assertFalse(viewModel.uiState.value.formState.isFormValid)

        viewModel.onTopicChanged("Kotlin Coroutines Best Practices")
        assertFalse(viewModel.uiState.value.formState.isFormValid)

        viewModel.onGoalChanged("Share technical knowledge")
        assertFalse(viewModel.uiState.value.formState.isFormValid)

        viewModel.onAudienceChanged("Android Developers")
        assertFalse(viewModel.uiState.value.formState.isFormValid)

        viewModel.onToneChanged("professional")
        assertFalse(viewModel.uiState.value.formState.isFormValid)

        viewModel.onChannelToggled(ChannelType.LINKEDIN, true)
        assertTrue(viewModel.uiState.value.formState.isFormValid)
    }

    @Test
    fun `submit with empty fields triggers validation errors and does not submit brief`() {
        viewModel.onSubmit()

        val form = viewModel.uiState.value.formState
        assertEquals(R.string.compose_topic_error, form.topicError)
        assertEquals(R.string.compose_goal_error, form.goalError)
        assertEquals(R.string.compose_audience_error, form.audienceError)
        assertEquals(R.string.compose_tone_error, form.toneError)
        assertEquals(R.string.compose_channels_error, form.channelsError)
        assertFalse(form.isFormValid)
        assertNull(viewModel.uiState.value.submittedBrief)
    }

    @Test
    fun `submit with valid fields produces TopicBrief and emits BriefSubmitted event`() = runTest {
        viewModel.onTopicChanged("Social Agent Launch")
        viewModel.onGoalChanged("Announce product release")
        viewModel.onAudienceChanged("Tech enthusiasts")
        viewModel.onToneChanged("inspirational")
        viewModel.onLanguageChanged(ContentLanguage.EN)
        viewModel.onChannelToggled(ChannelType.FACEBOOK, true)
        viewModel.onChannelToggled(ChannelType.LINKEDIN, true)

        viewModel.onSubmit()

        val state = viewModel.uiState.value
        val brief = state.submittedBrief
        assertNotNull(brief)
        assertEquals("Social Agent Launch", brief?.topic)
        assertEquals("Announce product release", brief?.goal)
        assertEquals("Tech enthusiasts", brief?.audience)
        assertEquals("inspirational", brief?.tone)
        assertEquals(ContentLanguage.EN, brief?.language)
        assertEquals(setOf(ChannelType.FACEBOOK, ChannelType.LINKEDIN), brief?.channels)
        assertNotNull(brief?.id)

        val event = viewModel.events.first()
        assertTrue(event is ComposeEvent.BriefSubmitted)
        val submittedEvent = event as ComposeEvent.BriefSubmitted
        assertEquals(brief, submittedEvent.brief)
        assertEquals(R.string.compose_submit_success, submittedEvent.messageRes)
    }

    @Test
    fun `submit trims leading and trailing whitespace in TopicBrief`() = runTest {
        viewModel.onTopicChanged("  Topic with spaces  ")
        viewModel.onGoalChanged("  Grow reach  ")
        viewModel.onAudienceChanged("  Mobile devs  ")
        viewModel.onToneChanged("  friendly  ")
        viewModel.onChannelToggled(ChannelType.FACEBOOK, true)

        viewModel.onSubmit()

        val brief = viewModel.uiState.value.submittedBrief
        assertNotNull(brief)
        assertEquals("Topic with spaces", brief?.topic)
        assertEquals("Grow reach", brief?.goal)
        assertEquals("Mobile devs", brief?.audience)
        assertEquals("friendly", brief?.tone)
    }

    @Test
    fun `submitting twice emits two BriefSubmitted events with distinct brief ids`() = runTest {
        viewModel.onTopicChanged("Topic A")
        viewModel.onGoalChanged("Goal A")
        viewModel.onAudienceChanged("Audience A")
        viewModel.onToneChanged("professional")
        viewModel.onChannelToggled(ChannelType.FACEBOOK, true)

        viewModel.onSubmit()
        viewModel.onSubmit()

        val emittedEvents = viewModel.events.take(2).toList()
        assertEquals(2, emittedEvents.size)
        val firstBrief = (emittedEvents[0] as ComposeEvent.BriefSubmitted).brief
        val secondBrief = (emittedEvents[1] as ComposeEvent.BriefSubmitted).brief
        assertNotEquals(firstBrief.id, secondBrief.id)
        assertEquals("Topic A", secondBrief.topic)
        assertEquals(secondBrief, viewModel.uiState.value.submittedBrief)
    }

    @Test
    fun `recreating ViewModel with SavedStateHandle restores tone and all form fields after process death`() {
        viewModel.onTopicChanged("Saved Topic")
        viewModel.onGoalChanged("Saved Goal")
        viewModel.onAudienceChanged("Saved Audience")
        viewModel.onToneChanged("persuasive")
        viewModel.onLanguageChanged(ContentLanguage.EN)
        viewModel.onChannelToggled(ChannelType.FACEBOOK, true)
        viewModel.onChannelToggled(ChannelType.LINKEDIN, true)

        val restoredViewModel = ComposeViewModel(savedStateHandle)
        val restoredForm = restoredViewModel.uiState.value.formState

        assertEquals("Saved Topic", restoredForm.topic)
        assertEquals("Saved Goal", restoredForm.goal)
        assertEquals("Saved Audience", restoredForm.audience)
        assertEquals("persuasive", restoredForm.tone)
        assertEquals(ContentLanguage.EN, restoredForm.language)
        assertEquals(setOf(ChannelType.FACEBOOK, ChannelType.LINKEDIN), restoredForm.selectedChannels)
        assertTrue(restoredForm.isFormValid)
    }

    @Test
    fun `typing into an error field clears its error once valid`() {
        viewModel.onTopicFocusLost()
        assertEquals(R.string.compose_topic_error, viewModel.uiState.value.formState.topicError)

        viewModel.onTopicChanged("Valid Topic")
        assertNull(viewModel.uiState.value.formState.topicError)
    }
}
