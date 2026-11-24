package com.lttbdd.socialagent.feature.compose

import com.lttbdd.socialagent.R
import com.lttbdd.socialagent.core.model.ChannelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicFormValidatorTest {

    @Test
    fun `validateTopic returns error for blank topic`() {
        assertEquals(R.string.compose_topic_error, TopicFormValidator.validateTopic(""))
        assertEquals(R.string.compose_topic_error, TopicFormValidator.validateTopic("   "))
        assertNull(TopicFormValidator.validateTopic("AI Agent in Android"))
    }

    @Test
    fun `validateGoal returns error for blank goal`() {
        assertEquals(R.string.compose_goal_error, TopicFormValidator.validateGoal(""))
        assertEquals(R.string.compose_goal_error, TopicFormValidator.validateGoal("   "))
        assertNull(TopicFormValidator.validateGoal("Increase engagement"))
    }

    @Test
    fun `validateAudience returns error for blank audience`() {
        assertEquals(R.string.compose_audience_error, TopicFormValidator.validateAudience(""))
        assertEquals(R.string.compose_audience_error, TopicFormValidator.validateAudience("   "))
        assertNull(TopicFormValidator.validateAudience("Mobile developers"))
    }

    @Test
    fun `validateTone returns error for blank tone`() {
        assertEquals(R.string.compose_tone_error, TopicFormValidator.validateTone(""))
        assertEquals(R.string.compose_tone_error, TopicFormValidator.validateTone("   "))
        assertNull(TopicFormValidator.validateTone("professional"))
    }

    @Test
    fun `validateChannels returns error when no channels selected`() {
        assertEquals(R.string.compose_channels_error, TopicFormValidator.validateChannels(emptySet()))
        assertNull(TopicFormValidator.validateChannels(setOf(ChannelType.FACEBOOK)))
        assertNull(TopicFormValidator.validateChannels(setOf(ChannelType.LINKEDIN)))
        assertNull(TopicFormValidator.validateChannels(setOf(ChannelType.FACEBOOK, ChannelType.LINKEDIN)))
    }

    @Test
    fun `isFormValid returns true only when all fields are valid`() {
        assertFalse(
            TopicFormValidator.isFormValid(
                topic = "",
                goal = "Goal",
                audience = "Audience",
                tone = "professional",
                channels = setOf(ChannelType.FACEBOOK),
            ),
        )
        assertFalse(
            TopicFormValidator.isFormValid(
                topic = "Topic",
                goal = "",
                audience = "Audience",
                tone = "professional",
                channels = setOf(ChannelType.FACEBOOK),
            ),
        )
        assertFalse(
            TopicFormValidator.isFormValid(
                topic = "Topic",
                goal = "Goal",
                audience = "",
                tone = "professional",
                channels = setOf(ChannelType.FACEBOOK),
            ),
        )
        assertFalse(
            TopicFormValidator.isFormValid(
                topic = "Topic",
                goal = "Goal",
                audience = "Audience",
                tone = "",
                channels = setOf(ChannelType.FACEBOOK),
            ),
        )
        assertFalse(
            TopicFormValidator.isFormValid(
                topic = "Topic",
                goal = "Goal",
                audience = "Audience",
                tone = "professional",
                channels = emptySet(),
            ),
        )
        assertTrue(
            TopicFormValidator.isFormValid(
                topic = "Topic",
                goal = "Goal",
                audience = "Audience",
                tone = "professional",
                channels = setOf(ChannelType.FACEBOOK),
            ),
        )
    }
}
