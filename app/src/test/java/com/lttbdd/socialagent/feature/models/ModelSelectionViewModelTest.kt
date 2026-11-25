package com.lttbdd.socialagent.feature.models

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import com.lttbdd.socialagent.data.ai.FakeKeyAvailability
import com.lttbdd.socialagent.data.ai.FakeTaskModelStore
import com.lttbdd.socialagent.data.ai.ModelSelectionRepository
import com.lttbdd.socialagent.data.ai.TaskModelResolver
import com.lttbdd.socialagent.data.ai.TaskModelSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ModelSelectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeTaskModelStore()
    private val keys = FakeKeyAvailability(LlmProvider.GEMINI, LlmProvider.OPENAI)
    private val viewModel = ModelSelectionViewModel(ModelSelectionRepository(store, TaskModelResolver(store, keys), keys))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun task(task: LlmTask) = viewModel.uiState.value.tasks.getValue(task)

    @Test
    fun `loads defaults for text tasks and an unselected image task`() = runTest(dispatcher) {
        assertTrue(viewModel.uiState.value.loading)
        viewModel.refresh()
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertFalse(state.busy)
        assertEquals(mapOf(LlmProvider.GEMINI to true, LlmProvider.OPENAI to true), state.providerKeys)
        assertEquals("gemini-2.5-flash", task(LlmTask.WRITE_POST).modelInput)
        assertEquals(TaskModelStatus.READY, task(LlmTask.WRITE_POST).status)
        assertTrue(task(LlmTask.WRITE_POST).isDefault)
        assertEquals("gemini-2.5-flash-lite", task(LlmTask.CLASSIFY_COMMENT).modelInput)
        assertEquals(TaskModelStatus.NOT_SELECTED, task(LlmTask.GENERATE_IMAGE).status)
        assertEquals("", task(LlmTask.GENERATE_IMAGE).modelInput)
    }

    @Test
    fun `changing provider suggests that provider's first model and leaves other tasks alone`() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.onProviderSelected(LlmTask.WRITE_POST, LlmProvider.OPENAI)
        assertEquals(LlmProvider.OPENAI, task(LlmTask.WRITE_POST).provider)
        assertEquals("gpt-6-luna", task(LlmTask.WRITE_POST).modelInput)
        assertEquals(listOf("gpt-6-luna", "gpt-6.1-terra"), task(LlmTask.WRITE_POST).suggestions)
        assertEquals(LlmProvider.GEMINI, task(LlmTask.CLASSIFY_COMMENT).provider)
        // Chưa lưu thì chưa ghi gì xuống store.
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun `provider without key cannot be selected and shows the missing key message`() = runTest(dispatcher) {
        keys.configured -= LlmProvider.OPENAI
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.onProviderSelected(LlmTask.WRITE_POST, LlmProvider.OPENAI)
        assertEquals(LlmProvider.GEMINI, task(LlmTask.WRITE_POST).provider)
        assertEquals(ModelSelectionMessage.MISSING_API_KEY, task(LlmTask.WRITE_POST).message)
    }

    @Test
    fun `saves a selection and reloads status from storage`() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.onProviderSelected(LlmTask.WRITE_POST, LlmProvider.OPENAI)
        viewModel.onModelEdited(LlmTask.WRITE_POST, "gpt-6.1-terra")
        viewModel.save(LlmTask.WRITE_POST)
        assertTrue(viewModel.uiState.value.busy)
        advanceUntilIdle()
        assertEquals(TaskModelSelection(LlmProvider.OPENAI, "gpt-6.1-terra"), store.values[LlmTask.WRITE_POST])
        assertEquals(ModelSelectionMessage.SAVED, task(LlmTask.WRITE_POST).message)
        assertFalse(task(LlmTask.WRITE_POST).isDefault)
        assertEquals(TaskModelStatus.READY, task(LlmTask.WRITE_POST).status)
        assertEquals(1, store.saveCalls)
    }

    @Test
    fun `rejects invalid input with a specific message and does not write`() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.onModelEdited(LlmTask.GENERATE_IMAGE, "gemini-2.5-flash")
        viewModel.save(LlmTask.GENERATE_IMAGE)
        advanceUntilIdle()
        assertEquals(ModelSelectionMessage.INCOMPATIBLE, task(LlmTask.GENERATE_IMAGE).message)
        viewModel.onModelEdited(LlmTask.GENERATE_IMAGE, "")
        viewModel.save(LlmTask.GENERATE_IMAGE)
        advanceUntilIdle()
        assertEquals(ModelSelectionMessage.EMPTY, task(LlmTask.GENERATE_IMAGE).message)
        viewModel.onModelEdited(LlmTask.WRITE_POST, "not valid!")
        viewModel.save(LlmTask.WRITE_POST)
        advanceUntilIdle()
        assertEquals(ModelSelectionMessage.INVALID_FORMAT, task(LlmTask.WRITE_POST).message)
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun `editing a model clears the previous message`() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.onModelEdited(LlmTask.WRITE_POST, "")
        viewModel.save(LlmTask.WRITE_POST)
        advanceUntilIdle()
        assertEquals(ModelSelectionMessage.EMPTY, task(LlmTask.WRITE_POST).message)
        viewModel.onModelEdited(LlmTask.WRITE_POST, "gemini-2.5-flash")
        assertNull(task(LlmTask.WRITE_POST).message)
    }

    @Test
    fun `key deleted after saving puts the task in needs key state on refresh without losing the draft`() = runTest(dispatcher) {
        store.values[LlmTask.WRITE_POST] = TaskModelSelection(LlmProvider.OPENAI, "gpt-6-luna")
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(TaskModelStatus.READY, task(LlmTask.WRITE_POST).status)
        viewModel.onModelEdited(LlmTask.CLASSIFY_COMMENT, "gemini-2.5-flash")
        keys.configured -= LlmProvider.OPENAI
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(TaskModelStatus.NEEDS_API_KEY, task(LlmTask.WRITE_POST).status)
        assertEquals(LlmProvider.OPENAI, task(LlmTask.WRITE_POST).provider)
        assertEquals(false, viewModel.uiState.value.providerKeys[LlmProvider.OPENAI])
        assertEquals("gemini-2.5-flash", task(LlmTask.CLASSIFY_COMMENT).modelInput)
    }

    @Test
    fun `refresh drops stale messages so they do not contradict the new status`() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.reset(LlmTask.WRITE_POST)
        advanceUntilIdle()
        assertEquals(ModelSelectionMessage.RESET, task(LlmTask.WRITE_POST).message)
        keys.configured -= LlmProvider.GEMINI
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(TaskModelStatus.NEEDS_API_KEY, task(LlmTask.WRITE_POST).status)
        assertNull(task(LlmTask.WRITE_POST).message)
    }

    @Test
    fun `reset returns a text task to default and clears an image selection`() = runTest(dispatcher) {
        store.values[LlmTask.WRITE_POST] = TaskModelSelection(LlmProvider.OPENAI, "gpt-6-luna")
        store.values[LlmTask.GENERATE_IMAGE] = TaskModelSelection(LlmProvider.GEMINI, "gemini-3.1-flash-lite-image")
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(TaskModelStatus.READY, task(LlmTask.GENERATE_IMAGE).status)
        viewModel.reset(LlmTask.WRITE_POST)
        advanceUntilIdle()
        viewModel.reset(LlmTask.GENERATE_IMAGE)
        advanceUntilIdle()
        assertEquals("gemini-2.5-flash", task(LlmTask.WRITE_POST).modelInput)
        assertTrue(task(LlmTask.WRITE_POST).isDefault)
        assertEquals(ModelSelectionMessage.RESET, task(LlmTask.GENERATE_IMAGE).message)
        assertEquals(TaskModelStatus.NOT_SELECTED, task(LlmTask.GENERATE_IMAGE).status)
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun `storage failure reports an error only on the task being saved and does not leave the screen busy`() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        store.failure = IllegalStateException("disk")
        viewModel.save(LlmTask.WRITE_POST)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.busy)
        assertEquals(ModelSelectionMessage.ERROR, task(LlmTask.WRITE_POST).message)
        assertNull(task(LlmTask.CLASSIFY_COMMENT).message)
        assertNull(task(LlmTask.GENERATE_IMAGE).message)
        store.failure = null
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(TaskModelStatus.READY, task(LlmTask.WRITE_POST).status)
    }

    @Test
    fun `initial load failure is exposed and retry succeeds`() = runTest(dispatcher) {
        store.failure = IllegalStateException("disk")
        viewModel.refresh()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.loadFailed)
        assertFalse(viewModel.uiState.value.loading)
        store.failure = null
        viewModel.refresh()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loadFailed)
        assertEquals(3, viewModel.uiState.value.tasks.size)
    }

    @Test
    fun `reset failure is reported only on its own task`() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        store.failure = IllegalStateException("disk")
        viewModel.reset(LlmTask.CLASSIFY_COMMENT)
        advanceUntilIdle()
        assertEquals(ModelSelectionMessage.ERROR, task(LlmTask.CLASSIFY_COMMENT).message)
        assertNull(task(LlmTask.WRITE_POST).message)
        assertNull(task(LlmTask.GENERATE_IMAGE).message)
    }

    @Test
    fun `refresh requested while busy runs after the current operation and picks up key changes`() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(TaskModelStatus.READY, task(LlmTask.WRITE_POST).status)
        viewModel.save(LlmTask.WRITE_POST)
        assertTrue(viewModel.uiState.value.busy)
        // Người dùng sang màn API key, xóa key rồi quay lại trong lúc lưu: onStart gọi refresh khi còn bận.
        keys.configured -= LlmProvider.GEMINI
        viewModel.refresh()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.busy)
        assertEquals(false, viewModel.uiState.value.providerKeys[LlmProvider.GEMINI])
        assertEquals(TaskModelStatus.NEEDS_API_KEY, task(LlmTask.WRITE_POST).status)
    }

    @Test
    fun `pending refresh also runs when the busy operation fails`() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        store.saveFailure = IllegalStateException("disk")
        viewModel.save(LlmTask.WRITE_POST)
        viewModel.refresh()
        keys.configured -= LlmProvider.GEMINI
        advanceUntilIdle()
        assertEquals(false, viewModel.uiState.value.providerKeys[LlmProvider.GEMINI])
        assertEquals(TaskModelStatus.NEEDS_API_KEY, task(LlmTask.WRITE_POST).status)
        assertFalse(viewModel.uiState.value.busy)
    }

    @Test
    fun `saving an unknown model with a doubtful name saves it and shows the warning message`() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.onModelEdited(LlmTask.GENERATE_IMAGE, "my-custom-model")
        viewModel.save(LlmTask.GENERATE_IMAGE)
        advanceUntilIdle()
        assertEquals(ModelSelectionMessage.SAVED_WITH_WARNING, task(LlmTask.GENERATE_IMAGE).message)
        assertEquals("my-custom-model", store.values[LlmTask.GENERATE_IMAGE]?.modelId)
        assertEquals(TaskModelStatus.READY, task(LlmTask.GENERATE_IMAGE).status)
    }

    @Test
    fun `gemini id that the router would reject is refused at save time`() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.onModelEdited(LlmTask.WRITE_POST, "tunedModels/my-model")
        viewModel.save(LlmTask.WRITE_POST)
        advanceUntilIdle()
        assertEquals(ModelSelectionMessage.INVALID_FORMAT, task(LlmTask.WRITE_POST).message)
        assertEquals(0, store.saveCalls)
    }

    @Test
    fun `operations are ignored while another one is running`() = runTest(dispatcher) {
        viewModel.refresh()
        advanceUntilIdle()
        viewModel.onModelEdited(LlmTask.WRITE_POST, "gemini-2.5-flash-lite")
        viewModel.save(LlmTask.WRITE_POST)
        viewModel.save(LlmTask.WRITE_POST)
        viewModel.reset(LlmTask.CLASSIFY_COMMENT)
        advanceUntilIdle()
        assertEquals(1, store.saveCalls)
        assertTrue(LlmTask.CLASSIFY_COMMENT !in store.values)
    }
}
