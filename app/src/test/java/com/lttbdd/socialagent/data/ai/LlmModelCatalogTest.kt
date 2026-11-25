package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelCatalogTest {
    @Test
    fun `text tasks have defaults and image generation has none`() {
        assertEquals(
            LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash"),
            LlmModelCatalog.defaultFor(LlmTask.WRITE_POST),
        )
        assertEquals(
            LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash-lite"),
            LlmModelCatalog.defaultFor(LlmTask.CLASSIFY_COMMENT),
        )
        assertNull(LlmModelCatalog.defaultFor(LlmTask.GENERATE_IMAGE))
    }

    @Test
    fun `every default is a valid suggestion for its own task`() {
        for (task in LlmTask.entries) {
            val default = LlmModelCatalog.defaultFor(task) ?: continue
            assertTrue(default.model in LlmModelCatalog.suggestions(default.provider, task))
            assertEquals(ModelValidation.VALID, LlmModelCatalog.validate(default.provider, task, default.model))
        }
    }

    @Test
    fun `suggestions are split by task so text models are not offered for images`() {
        for (provider in LlmModelCatalog.supportedProviders) {
            val image = LlmModelCatalog.suggestions(provider, LlmTask.GENERATE_IMAGE)
            val text = LlmModelCatalog.suggestions(provider, LlmTask.WRITE_POST)
            assertTrue(image.isNotEmpty() && text.isNotEmpty())
            assertTrue(image.intersect(text.toSet()).isEmpty())
        }
        assertEquals("gemini-2.5-flash", LlmModelCatalog.firstSuggestion(LlmProvider.GEMINI, LlmTask.WRITE_POST))
    }

    @Test
    fun `rejects text model for image generation and image model for text`() {
        assertEquals(
            ModelValidation.INCOMPATIBLE_WITH_TASK,
            LlmModelCatalog.validate(LlmProvider.GEMINI, LlmTask.GENERATE_IMAGE, "gemini-2.5-flash"),
        )
        assertEquals(
            ModelValidation.INCOMPATIBLE_WITH_TASK,
            LlmModelCatalog.validate(LlmProvider.GEMINI, LlmTask.WRITE_POST, "gemini-3.1-flash-lite-image"),
        )
        assertEquals(
            ModelValidation.INCOMPATIBLE_WITH_TASK,
            LlmModelCatalog.validate(LlmProvider.OPENAI, LlmTask.CLASSIFY_COMMENT, "gpt-image-1"),
        )
    }

    @Test
    fun `known model declared for fewer tasks is rejected for the others`() {
        assertEquals(
            ModelValidation.VALID,
            LlmModelCatalog.validate(LlmProvider.OPENAI, LlmTask.WRITE_POST, "gpt-6.1-terra"),
        )
        assertEquals(
            ModelValidation.INCOMPATIBLE_WITH_TASK,
            LlmModelCatalog.validate(LlmProvider.OPENAI, LlmTask.CLASSIFY_COMMENT, "gpt-6.1-terra"),
        )
    }

    @Test
    fun `uses the same id rule as the router so every saveable gemini id is callable`() = runTest {
        val ids = listOf("gemini-2.5-flash", "models/gemini-2.5-flash", "my.tuned_model-1", "tunedModels/x", "g:001", "a/b", "models/")
        for (id in ids) {
            val saveable = LlmModelCatalog.validate(LlmProvider.GEMINI, LlmTask.WRITE_POST, id) == ModelValidation.VALID
            val router = RoutingLlmClient(
                LlmConfigurationSource { LlmModelConfiguration(LlmProvider.GEMINI, id) },
                ApiKeySource { syntheticKey() },
                setOf(GeminiClient(FakeSseTransport(geminiFrames))),
            )
            if (saveable) {
                router.streamChat(chatRequest()).toList()
            } else {
                expectFailure(LlmFailure.INVALID_REQUEST) { router.streamChat(chatRequest()).toList() }
            }
        }
    }

    @Test
    fun `rejects gemini ids with path or version separators but keeps them for openai`() {
        for (id in listOf("tunedModels/my-model", "gemini-2.5-flash:001", "a/b", "models/")) {
            assertEquals(id, ModelValidation.INVALID_FORMAT, LlmModelCatalog.validate(LlmProvider.GEMINI, LlmTask.WRITE_POST, id))
        }
        assertEquals(ModelValidation.VALID, LlmModelCatalog.validate(LlmProvider.OPENAI, LlmTask.WRITE_POST, "ft:gpt-6-luna:org::1"))
    }

    @Test
    fun `unknown ids are allowed and only warned about when the name does not fit the task`() {
        // Model ngoài danh mục: không đủ dữ liệu để chặn, chỉ cảnh báo.
        assertEquals(ModelValidation.VALID, LlmModelCatalog.validate(LlmProvider.OPENAI, LlmTask.WRITE_POST, "gpt-x-image-input"))
        assertEquals(
            ModelWarning.POSSIBLY_INCOMPATIBLE_WITH_TASK,
            LlmModelCatalog.warning(LlmProvider.OPENAI, LlmTask.WRITE_POST, "gpt-x-image-input"),
        )
        assertEquals(ModelValidation.VALID, LlmModelCatalog.validate(LlmProvider.OPENAI, LlmTask.GENERATE_IMAGE, "my-model"))
        assertEquals(
            ModelWarning.POSSIBLY_INCOMPATIBLE_WITH_TASK,
            LlmModelCatalog.warning(LlmProvider.OPENAI, LlmTask.GENERATE_IMAGE, "my-model"),
        )
        // Model ảnh phổ biến ngoài danh mục không bị cảnh báo nhầm.
        assertNull(LlmModelCatalog.warning(LlmProvider.OPENAI, LlmTask.GENERATE_IMAGE, "dall-e-3"))
        assertNull(LlmModelCatalog.warning(LlmProvider.GEMINI, LlmTask.GENERATE_IMAGE, "imagen-4.0"))
        assertNull(LlmModelCatalog.warning(LlmProvider.GEMINI, LlmTask.WRITE_POST, "gemini-9-pro"))
        // Model trong danh mục dùng khai báo sẵn: không cảnh báo (sai loại việc thì validate đã chặn).
        assertNull(LlmModelCatalog.warning(LlmProvider.GEMINI, LlmTask.WRITE_POST, "gemini-2.5-flash"))
    }

    @Test
    fun `accepts custom ids that follow task naming`() {
        assertEquals(ModelValidation.VALID, LlmModelCatalog.validate(LlmProvider.OPENAI, LlmTask.WRITE_POST, "ft:my-model:org::1"))
        assertEquals(ModelValidation.VALID, LlmModelCatalog.validate(LlmProvider.GEMINI, LlmTask.GENERATE_IMAGE, "custom-image-model"))
        assertEquals(ModelValidation.VALID, LlmModelCatalog.validate(LlmProvider.GEMINI, LlmTask.WRITE_POST, "models/gemini-x.y"))
    }

    @Test
    fun `rejects empty, long, malformed ids and unsupported providers`() {
        assertEquals(ModelValidation.EMPTY, LlmModelCatalog.validate(LlmProvider.GEMINI, LlmTask.WRITE_POST, ""))
        assertEquals(
            ModelValidation.TOO_LONG,
            LlmModelCatalog.validate(LlmProvider.GEMINI, LlmTask.WRITE_POST, "m".repeat(LlmModelCatalog.MAX_MODEL_ID_LENGTH + 1)),
        )
        assertEquals(ModelValidation.INVALID_FORMAT, LlmModelCatalog.validate(LlmProvider.GEMINI, LlmTask.WRITE_POST, "has space"))
        assertEquals(ModelValidation.INVALID_FORMAT, LlmModelCatalog.validate(LlmProvider.GEMINI, LlmTask.WRITE_POST, "line\nbreak"))
        assertEquals(ModelValidation.INVALID_FORMAT, LlmModelCatalog.validate(LlmProvider.OPENAI, LlmTask.WRITE_POST, "a?b=c"))
        assertEquals(
            ModelValidation.UNSUPPORTED_PROVIDER,
            LlmModelCatalog.validate(LlmProvider.ANTHROPIC, LlmTask.WRITE_POST, "claude-haiku-4-5"),
        )
    }
}
