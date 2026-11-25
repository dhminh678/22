package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import javax.inject.Inject

/** Ảnh chụp trạng thái chọn model: key của từng provider và kết quả chọn của từng loại việc. */
data class ModelSelectionSnapshot(
    val keyConfigured: Map<LlmProvider, Boolean>,
    val tasks: Map<LlmTask, TaskModelResolution>,
)

sealed interface SaveModelResult {
    /** [warning] khác null khi model ngoài danh mục và tên không giống loại model của việc này; vẫn đã lưu. */
    data class Saved(val warning: ModelWarning? = null) : SaveModelResult
    data object MissingApiKey : SaveModelResult
    data class Invalid(val reason: ModelValidation) : SaveModelResult
}

/** Cho tầng UI đọc và ghi lựa chọn model; tầng gọi LLM dùng [TaskModelResolver] trực tiếp. */
class ModelSelectionRepository @Inject constructor(
    private val store: TaskModelSelectionStore,
    private val resolver: TaskModelResolver,
    private val keys: ProviderKeyAvailability,
) {
    suspend fun snapshot(): ModelSelectionSnapshot = ModelSelectionSnapshot(
        keyConfigured = LlmModelCatalog.supportedProviders.associateWith { keys.isConfigured(it) },
        tasks = LlmTask.entries.associateWith { resolver.resolve(it) },
    )

    /** Chỉ lưu khi model hợp lệ cho loại việc và provider đã có key; không ghi dữ liệu khi bị từ chối. */
    suspend fun save(task: LlmTask, provider: LlmProvider, modelInput: String): SaveModelResult {
        val modelId = modelInput.trim()
        val validation = LlmModelCatalog.validate(provider, task, modelId)
        if (validation != ModelValidation.VALID) return SaveModelResult.Invalid(validation)
        if (!keys.isConfigured(provider)) return SaveModelResult.MissingApiKey
        store.save(task, TaskModelSelection(provider, modelId))
        return SaveModelResult.Saved(LlmModelCatalog.warning(provider, task, modelId))
    }

    /** Xóa lựa chọn riêng: loại việc quay về mặc định (hoặc chưa chọn với sinh ảnh). */
    suspend fun reset(task: LlmTask) = store.clear(task)
}
