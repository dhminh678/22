package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskModelResolverTest {
    private val store = FakeTaskModelStore()
    private val keys = FakeKeyAvailability(LlmProvider.GEMINI)
    private val resolver = TaskModelResolver(store, keys)

    @Test
    fun `uses task defaults when nothing is stored`() = runTest {
        assertEquals(
            TaskModelResolution.Ready(LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash"), isDefault = true),
            resolver.resolve(LlmTask.WRITE_POST),
        )
        assertEquals(
            TaskModelResolution.Ready(LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash-lite"), isDefault = true),
            resolver.resolve(LlmTask.CLASSIFY_COMMENT),
        )
    }

    @Test
    fun `image generation is not selected by default so the offline template applies`() = runTest {
        assertEquals(TaskModelResolution.NotSelected, resolver.resolve(LlmTask.GENERATE_IMAGE))
        assertNull(resolver.configurationFor(LlmTask.GENERATE_IMAGE))
    }

    @Test
    fun `stored selection overrides the default and only for its own task`() = runTest {
        keys.configured += LlmProvider.OPENAI
        store.values[LlmTask.WRITE_POST] = TaskModelSelection(LlmProvider.OPENAI, "gpt-6-luna")
        assertEquals(
            TaskModelResolution.Ready(LlmModelConfiguration(LlmProvider.OPENAI, "gpt-6-luna"), isDefault = false),
            resolver.resolve(LlmTask.WRITE_POST),
        )
        assertEquals(LlmProvider.GEMINI, resolver.configurationFor(LlmTask.CLASSIFY_COMMENT)?.provider)
    }

    @Test
    fun `provider without a key needs configuration and never falls back to another provider`() = runTest {
        keys.configured.clear()
        keys.configured += LlmProvider.OPENAI
        // Mặc định là Gemini nhưng chỉ OpenAI có key: không được tự chuyển sang OpenAI.
        val result = resolver.resolve(LlmTask.WRITE_POST)
        assertEquals(
            TaskModelResolution.NeedsApiKey(LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash"), isDefault = true),
            result,
        )
        assertEquals(LlmProvider.GEMINI, resolver.configurationFor(LlmTask.WRITE_POST)?.provider)
    }

    @Test
    fun `deleting the key of a saved provider moves the task back to needs key`() = runTest {
        keys.configured += LlmProvider.OPENAI
        store.values[LlmTask.WRITE_POST] = TaskModelSelection(LlmProvider.OPENAI, "gpt-6-luna")
        assertTrue(resolver.resolve(LlmTask.WRITE_POST) is TaskModelResolution.Ready)
        keys.configured -= LlmProvider.OPENAI
        assertEquals(
            TaskModelResolution.NeedsApiKey(LlmModelConfiguration(LlmProvider.OPENAI, "gpt-6-luna"), isDefault = false),
            resolver.resolve(LlmTask.WRITE_POST),
        )
    }

    @Test
    fun `stored selection that is invalid for the task is reported and not used`() = runTest {
        store.values[LlmTask.WRITE_POST] = TaskModelSelection(LlmProvider.GEMINI, "gemini-3.1-flash-lite-image")
        store.values[LlmTask.CLASSIFY_COMMENT] = TaskModelSelection(LlmProvider.ANTHROPIC, "claude-haiku-4-5")
        assertEquals(
            TaskModelResolution.InvalidSelection(LlmProvider.GEMINI, "gemini-3.1-flash-lite-image", ModelValidation.INCOMPATIBLE_WITH_TASK),
            resolver.resolve(LlmTask.WRITE_POST),
        )
        assertEquals(
            TaskModelResolution.InvalidSelection(LlmProvider.ANTHROPIC, "claude-haiku-4-5", ModelValidation.UNSUPPORTED_PROVIDER),
            resolver.resolve(LlmTask.CLASSIFY_COMMENT),
        )
        assertNull(resolver.configurationFor(LlmTask.WRITE_POST))
    }

    @Test
    fun `router reports missing key rather than missing configuration when selection exists`() = runTest {
        keys.configured.clear()
        val router = RoutingLlmClient(resolver, ApiKeySource { null }, setOf(GeminiClient(FakeSseTransport())))
        expectFailure(LlmFailure.MISSING_API_KEY) { router.streamChat(chatRequest()).toList() }
    }

    @Test
    fun `router calls the model chosen for each task`() = runTest {
        keys.configured += LlmProvider.OPENAI
        store.values[LlmTask.WRITE_POST] = TaskModelSelection(LlmProvider.OPENAI, "gpt-6-luna")
        val transport = FakeSseTransport()
        val router = RoutingLlmClient(
            resolver,
            ApiKeySource { syntheticKey() },
            setOf(OpenAiClient(transport), GeminiClient(transport)),
        )
        transport.frames = openAiFrames
        router.streamChat(chatRequest(LlmTask.WRITE_POST)).toList()
        assertEquals("gpt-6-luna", transport.requests.last().jsonBody()["model"].toString().trim('"'))
        transport.frames = geminiFrames
        router.streamChat(chatRequest(LlmTask.CLASSIFY_COMMENT)).toList()
        assertTrue(transport.requests.last().url.toString().contains("gemini-2.5-flash-lite"))
    }
}
