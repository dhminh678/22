package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask

/** Store trong bộ nhớ, có thể làm hỏng thao tác ghi để kiểm tra không ghi dở dang. */
internal class FakeTaskModelStore : TaskModelSelectionStore {
    val values = mutableMapOf<LlmTask, TaskModelSelection>()
    var saveCalls = 0
    var failure: Exception? = null

    /** Chỉ làm hỏng thao tác ghi, để đọc lại trạng thái vẫn chạy được. */
    var saveFailure: Exception? = null

    override suspend fun get(task: LlmTask): TaskModelSelection? {
        failure?.let { throw it }
        return values[task]
    }

    override suspend fun save(task: LlmTask, selection: TaskModelSelection) {
        failure?.let { throw it }
        saveFailure?.let { throw it }
        saveCalls++
        values[task] = selection
    }

    override suspend fun clear(task: LlmTask) {
        failure?.let { throw it }
        values.remove(task)
    }
}

/** Trạng thái key do test điều khiển; không có nội dung key nào. */
internal class FakeKeyAvailability(vararg configured: LlmProvider) : ProviderKeyAvailability {
    val configured = configured.toMutableSet()

    override suspend fun isConfigured(provider: LlmProvider): Boolean = provider in configured
}
