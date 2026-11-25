package com.lttbdd.socialagent.data.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask
import kotlinx.coroutines.flow.first

/** Lựa chọn của người dùng cho một loại việc. Không chứa API key. */
data class TaskModelSelection(val provider: LlmProvider, val modelId: String)

/** Nơi lưu lựa chọn provider + model theo từng [LlmTask]. */
interface TaskModelSelectionStore {
    /** `null` khi người dùng chưa chọn hoặc dữ liệu đã lưu không đọc được. */
    suspend fun get(task: LlmTask): TaskModelSelection?
    suspend fun save(task: LlmTask, selection: TaskModelSelection)
    suspend fun clear(task: LlmTask)
}

/** Tệp hỏng được thay bằng cấu hình rỗng: người dùng quay về mặc định thay vì app bị lỗi. */
internal fun taskModelCorruptionHandler() = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

private val Context.taskModelDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "social_agent_task_models",
    corruptionHandler = taskModelCorruptionHandler(),
)

/** Lưu trong DataStore Preferences; chỉ có tên provider và model ID nên không cần mã hóa. */
class DataStoreTaskModelSelectionStore internal constructor(
    private val dataStore: DataStore<Preferences>,
) : TaskModelSelectionStore {
    constructor(context: Context) : this(context.taskModelDataStore)

    override suspend fun get(task: LlmTask): TaskModelSelection? {
        val preferences = dataStore.data.first()
        val provider = preferences[providerKey(task)]
            ?.let { name -> LlmProvider.entries.firstOrNull { it.name == name } }
        val modelId = preferences[modelKey(task)]
        return if (provider != null && !modelId.isNullOrBlank()) TaskModelSelection(provider, modelId) else null
    }

    override suspend fun save(task: LlmTask, selection: TaskModelSelection) {
        dataStore.edit {
            it[providerKey(task)] = selection.provider.name
            it[modelKey(task)] = selection.modelId
        }
    }

    override suspend fun clear(task: LlmTask) {
        dataStore.edit {
            it.remove(providerKey(task))
            it.remove(modelKey(task))
        }
    }

    private fun providerKey(task: LlmTask) = stringPreferencesKey("provider_${task.name}")
    private fun modelKey(task: LlmTask) = stringPreferencesKey("model_${task.name}")
}
