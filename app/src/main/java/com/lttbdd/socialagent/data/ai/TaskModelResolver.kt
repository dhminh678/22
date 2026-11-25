package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask

/** Cho biết provider đã có key hay chưa. #53 nối vào secure storage ở `di/`; không bao giờ trả về chính key. */
fun interface ProviderKeyAvailability {
    suspend fun isConfigured(provider: LlmProvider): Boolean
}

/** Kết quả chọn model cho một loại việc. Không có trạng thái nào tự chuyển sang provider khác. */
sealed interface TaskModelResolution {
    /** Có cấu hình hợp lệ và provider đã có key. */
    data class Ready(val configuration: LlmModelConfiguration, val isDefault: Boolean) : TaskModelResolution

    /** Có cấu hình nhưng provider chưa có key (chưa nhập hoặc đã xóa): cần cấu hình lại. */
    data class NeedsApiKey(val configuration: LlmModelConfiguration, val isDefault: Boolean) : TaskModelResolution

    /** Loại việc không có mặc định và người dùng chưa chọn (sinh ảnh: dùng template ảnh offline). */
    data object NotSelected : TaskModelResolution

    /** Lựa chọn đã lưu không còn hợp lệ với loại việc này. */
    data class InvalidSelection(
        val provider: LlmProvider,
        val modelId: String,
        val reason: ModelValidation,
    ) : TaskModelResolution
}

/**
 * Ghép lựa chọn đã lưu (hoặc mặc định) với trạng thái key. Đồng thời là [LlmConfigurationSource] cho
 * router #52: đưa ra cấu hình khi hợp lệ để router tự báo `MISSING_API_KEY` đúng nguyên nhân.
 */
class TaskModelResolver(
    private val store: TaskModelSelectionStore,
    private val keys: ProviderKeyAvailability,
) : LlmConfigurationSource {

    suspend fun resolve(task: LlmTask): TaskModelResolution {
        val stored = store.get(task)
        val isDefault = stored == null
        val configuration = if (stored != null) {
            when (val validation = LlmModelCatalog.validate(stored.provider, task, stored.modelId)) {
                ModelValidation.VALID -> LlmModelConfiguration(stored.provider, stored.modelId)
                else -> return TaskModelResolution.InvalidSelection(stored.provider, stored.modelId, validation)
            }
        } else {
            LlmModelCatalog.defaultFor(task) ?: return TaskModelResolution.NotSelected
        }
        return if (keys.isConfigured(configuration.provider)) {
            TaskModelResolution.Ready(configuration, isDefault)
        } else {
            TaskModelResolution.NeedsApiKey(configuration, isDefault)
        }
    }

    override suspend fun configurationFor(task: LlmTask): LlmModelConfiguration? = when (val result = resolve(task)) {
        is TaskModelResolution.Ready -> result.configuration
        is TaskModelResolution.NeedsApiKey -> result.configuration
        is TaskModelResolution.NotSelected, is TaskModelResolution.InvalidSelection -> null
    }
}
