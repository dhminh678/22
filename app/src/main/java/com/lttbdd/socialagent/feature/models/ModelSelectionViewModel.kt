package com.lttbdd.socialagent.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import com.lttbdd.socialagent.data.ai.LlmModelCatalog
import com.lttbdd.socialagent.data.ai.ModelSelectionRepository
import com.lttbdd.socialagent.data.ai.ModelSelectionSnapshot
import com.lttbdd.socialagent.data.ai.ModelValidation
import com.lttbdd.socialagent.data.ai.SaveModelResult
import com.lttbdd.socialagent.data.ai.TaskModelResolution
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Trạng thái đã lưu của một loại việc (không phải bản nháp đang sửa). */
enum class TaskModelStatus { READY, NEEDS_API_KEY, NOT_SELECTED, INVALID_SELECTION }

enum class ModelSelectionMessage {
    SAVED,
    SAVED_WITH_WARNING,
    RESET,
    MISSING_API_KEY,
    EMPTY,
    TOO_LONG,
    INVALID_FORMAT,
    INCOMPATIBLE,
    UNSUPPORTED_PROVIDER,
    ERROR,
}

/** Một loại việc: bản nháp đang chọn ([provider], [modelInput]) và trạng thái đã lưu ([status]). */
data class TaskModelUiState(
    val task: LlmTask,
    val provider: LlmProvider,
    val modelInput: String,
    val suggestions: List<String>,
    val status: TaskModelStatus,
    val isDefault: Boolean,
    val message: ModelSelectionMessage? = null,
)

/** State không chứa API key, chỉ có provider đã có key hay chưa. */
data class ModelSelectionUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val providerKeys: Map<LlmProvider, Boolean> = emptyMap(),
    val tasks: Map<LlmTask, TaskModelUiState> = emptyMap(),
    val loadFailed: Boolean = false,
)

@HiltViewModel
class ModelSelectionViewModel @Inject constructor(
    private val repository: ModelSelectionRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ModelSelectionUiState())
    val uiState: StateFlow<ModelSelectionUiState> = _uiState.asStateFlow()

    /** `refresh()` bị gọi khi đang bận: chạy lại ngay khi thao tác hiện tại kết thúc để không dùng trạng thái key cũ. */
    private var refreshPending = false

    /** Gọi mỗi lần màn hình hiện lại (ví dụ sau khi thêm/xóa key); giữ nguyên bản nháp đang sửa. */
    fun refresh() {
        if (_uiState.value.busy) {
            refreshPending = true
            return
        }
        launchOperation { applySnapshot(repository.snapshot(), rebuildDraft = emptySet()) }
    }

    fun onProviderSelected(task: LlmTask, provider: LlmProvider) {
        val state = _uiState.value
        val current = state.tasks[task] ?: return
        if (state.busy || provider == current.provider || provider !in LlmModelCatalog.supportedProviders) return
        if (state.providerKeys[provider] != true) {
            updateTask(task) { it.copy(message = ModelSelectionMessage.MISSING_API_KEY) }
            return
        }
        // Đổi provider thì gợi ý lại model đầu tiên của provider đó, tránh giữ model của provider cũ.
        updateTask(task) {
            it.copy(
                provider = provider,
                modelInput = LlmModelCatalog.firstSuggestion(provider, task).orEmpty(),
                suggestions = LlmModelCatalog.suggestions(provider, task),
                message = null,
            )
        }
    }

    fun onModelEdited(task: LlmTask, text: String) {
        if (_uiState.value.tasks[task]?.modelInput == text) return
        updateTask(task) { it.copy(modelInput = text, message = null) }
    }

    fun save(task: LlmTask) {
        val draft = _uiState.value.tasks[task] ?: return
        if (_uiState.value.busy) return
        launchOperation(task) {
            when (val result = repository.save(task, draft.provider, draft.modelInput)) {
                is SaveModelResult.Saved -> {
                    applySnapshot(repository.snapshot(), rebuildDraft = setOf(task))
                    val message = if (result.warning == null) {
                        ModelSelectionMessage.SAVED
                    } else {
                        ModelSelectionMessage.SAVED_WITH_WARNING
                    }
                    updateTask(task) { it.copy(message = message) }
                }
                SaveModelResult.MissingApiKey ->
                    updateTask(task) { it.copy(message = ModelSelectionMessage.MISSING_API_KEY) }
                is SaveModelResult.Invalid ->
                    updateTask(task) { it.copy(message = result.reason.toMessage()) }
            }
        }
    }

    /** Bỏ lựa chọn riêng: về mặc định, hoặc chưa chọn với sinh ảnh (dùng template ảnh offline). */
    fun reset(task: LlmTask) {
        if (_uiState.value.busy || task !in _uiState.value.tasks) return
        launchOperation(task) {
            repository.reset(task)
            applySnapshot(repository.snapshot(), rebuildDraft = setOf(task))
            updateTask(task) { it.copy(message = ModelSelectionMessage.RESET) }
        }
    }

    /** [task] là loại việc đang thao tác: lỗi chỉ gắn vào thẻ đó; thao tác tải lại ([task] = null) lỗi thì báo lỗi tải. */
    private fun launchOperation(task: LlmTask? = null, block: suspend () -> Unit) {
        _uiState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (task != null) {
                    updateTask(task) { it.copy(message = ModelSelectionMessage.ERROR) }
                } else {
                    _uiState.update { it.copy(loadFailed = true) }
                }
            } finally {
                _uiState.update { it.copy(busy = false, loading = false) }
                if (refreshPending) {
                    refreshPending = false
                    refresh()
                }
            }
        }
    }

    private fun applySnapshot(snapshot: ModelSelectionSnapshot, rebuildDraft: Set<LlmTask>) {
        _uiState.update { state ->
            val tasks = LlmTask.entries.associateWith { task ->
                val resolution = snapshot.tasks.getValue(task)
                val existing = state.tasks[task]
                val fresh = taskState(task, resolution, snapshot.keyConfigured)
                if (existing == null || task in rebuildDraft) {
                    fresh
                } else {
                    // Thông báo của thao tác trước không còn đúng với trạng thái mới (ví dụ key vừa bị xóa).
                    existing.copy(status = fresh.status, isDefault = fresh.isDefault, message = null)
                }
            }
            state.copy(providerKeys = snapshot.keyConfigured, tasks = tasks, loadFailed = false)
        }
    }

    private fun taskState(
        task: LlmTask,
        resolution: TaskModelResolution,
        keys: Map<LlmProvider, Boolean>,
    ): TaskModelUiState {
        val (provider, model, status, isDefault) = when (resolution) {
            is TaskModelResolution.Ready -> Quad(
                resolution.configuration.provider, resolution.configuration.model,
                TaskModelStatus.READY, resolution.isDefault,
            )
            is TaskModelResolution.NeedsApiKey -> Quad(
                resolution.configuration.provider, resolution.configuration.model,
                TaskModelStatus.NEEDS_API_KEY, resolution.isDefault,
            )
            TaskModelResolution.NotSelected -> Quad(
                LlmModelCatalog.supportedProviders.firstOrNull { keys[it] == true } ?: LlmModelCatalog.supportedProviders.first(),
                "", TaskModelStatus.NOT_SELECTED, true,
            )
            is TaskModelResolution.InvalidSelection -> Quad(
                resolution.provider, resolution.modelId, TaskModelStatus.INVALID_SELECTION, false,
            )
        }
        return TaskModelUiState(
            task = task,
            provider = provider,
            modelInput = model,
            suggestions = LlmModelCatalog.suggestions(provider, task),
            status = status,
            isDefault = isDefault,
        )
    }

    private fun updateTask(task: LlmTask, transform: (TaskModelUiState) -> TaskModelUiState) {
        _uiState.update { state ->
            val current = state.tasks[task] ?: return@update state
            state.copy(tasks = state.tasks + (task to transform(current)))
        }
    }

    private data class Quad(
        val provider: LlmProvider,
        val model: String,
        val status: TaskModelStatus,
        val isDefault: Boolean,
    )

    private fun ModelValidation.toMessage(): ModelSelectionMessage = when (this) {
        ModelValidation.VALID -> ModelSelectionMessage.ERROR
        ModelValidation.EMPTY -> ModelSelectionMessage.EMPTY
        ModelValidation.TOO_LONG -> ModelSelectionMessage.TOO_LONG
        ModelValidation.INVALID_FORMAT -> ModelSelectionMessage.INVALID_FORMAT
        ModelValidation.UNSUPPORTED_PROVIDER -> ModelSelectionMessage.UNSUPPORTED_PROVIDER
        ModelValidation.INCOMPATIBLE_WITH_TASK -> ModelSelectionMessage.INCOMPATIBLE
    }
}
