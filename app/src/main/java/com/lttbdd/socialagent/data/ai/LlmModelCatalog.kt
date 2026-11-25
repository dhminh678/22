package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.LlmProvider
import com.lttbdd.socialagent.core.model.LlmTask

/** Model gợi ý cho một nhà cung cấp; chỉ là cấu hình đề xuất, không bảo đảm tài khoản có quyền dùng. */
data class ModelOption(val id: String, val tasks: Set<LlmTask>)

/** Kết quả kiểm tra một cặp provider + model ID cho một loại việc. */
enum class ModelValidation {
    VALID,
    EMPTY,
    TOO_LONG,
    INVALID_FORMAT,
    UNSUPPORTED_PROVIDER,
    INCOMPATIBLE_WITH_TASK,
}

/** Cảnh báo (không chặn) cho model ID ngoài danh mục: tên không giống loại model mà việc này cần. */
enum class ModelWarning { POSSIBLY_INCOMPATIBLE_WITH_TASK }

/**
 * Danh mục model gợi ý và mặc định theo loại việc (docs/research-llm.md mục 3.2).
 *
 * Người dùng có thể nhập model ID tùy chọn nên danh mục không phải danh sách đóng. Model có trong danh mục
 * dùng khai báo sẵn nên bị chặn khi sai loại việc. Model ngoài danh mục chỉ bị cảnh báo theo quy ước đặt tên
 * ([warning]) vì không đủ dữ liệu để khẳng định, nên vẫn cho lưu.
 */
object LlmModelCatalog {
    const val MAX_MODEL_ID_LENGTH = 128

    private val textTasks = setOf(LlmTask.WRITE_POST, LlmTask.CLASSIFY_COMMENT)
    private val imageTasks = setOf(LlmTask.GENERATE_IMAGE)

    /** OpenAI cho phép `:` và `/` (ví dụ model tinh chỉnh `ft:gpt-...:org::id`). */
    private val generalModelIdPattern = Regex("[A-Za-z0-9._:/-]+")

    /** Gemini ghép ID vào đường dẫn URL nên chỉ nhận ký tự an toàn sau khi bỏ tiền tố `models/`. */
    private val geminiModelIdPattern = Regex("[A-Za-z0-9._-]+")
    private val imageModelMarkers = listOf("image", "imagen", "dall-e")

    /** Các provider đã có adapter gọi thật (#52). */
    val supportedProviders: List<LlmProvider> = listOf(LlmProvider.GEMINI, LlmProvider.OPENAI)

    private val options: Map<LlmProvider, List<ModelOption>> = mapOf(
        LlmProvider.GEMINI to listOf(
            ModelOption("gemini-2.5-flash", textTasks),
            ModelOption("gemini-2.5-flash-lite", textTasks),
            ModelOption("gemini-3.1-flash-lite-image", imageTasks),
        ),
        LlmProvider.OPENAI to listOf(
            ModelOption("gpt-6-luna", textTasks),
            ModelOption("gpt-6.1-terra", setOf(LlmTask.WRITE_POST)),
            ModelOption("gpt-image-1", imageTasks),
        ),
    )

    /**
     * Mặc định theo loại việc. Sinh ảnh không có mặc định vì cần quyền model ảnh riêng và có thể tốn phí;
     * khi chưa chọn, ứng dụng dùng template ảnh offline.
     */
    private val defaults: Map<LlmTask, LlmModelConfiguration> = mapOf(
        LlmTask.WRITE_POST to LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash"),
        LlmTask.CLASSIFY_COMMENT to LlmModelConfiguration(LlmProvider.GEMINI, "gemini-2.5-flash-lite"),
    )

    fun defaultFor(task: LlmTask): LlmModelConfiguration? = defaults[task]

    /** Model gợi ý cho cặp provider + task, theo thứ tự ưu tiên. */
    fun suggestions(provider: LlmProvider, task: LlmTask): List<String> =
        options[provider].orEmpty().filter { task in it.tasks }.map { it.id }

    /** Model gợi ý đầu tiên của provider cho task, dùng khi người dùng đổi provider. */
    fun firstSuggestion(provider: LlmProvider, task: LlmTask): String? = suggestions(provider, task).firstOrNull()

    /**
     * Quy tắc định dạng model ID cho từng provider. Dùng chung cho lúc lưu ([validate]) và lúc gọi
     * (`RoutingLlmClient`) để ID đã lưu được thì chắc chắn không bị router từ chối vì định dạng.
     */
    fun isValidModelIdFormat(provider: LlmProvider, modelId: String): Boolean = when (provider) {
        LlmProvider.GEMINI -> modelId.removePrefix("models/").matches(geminiModelIdPattern)
        else -> modelId.matches(generalModelIdPattern)
    }

    fun validate(provider: LlmProvider, task: LlmTask, modelId: String): ModelValidation {
        if (provider !in supportedProviders) return ModelValidation.UNSUPPORTED_PROVIDER
        if (modelId.isEmpty()) return ModelValidation.EMPTY
        if (modelId.length > MAX_MODEL_ID_LENGTH) return ModelValidation.TOO_LONG
        if (!isValidModelIdFormat(provider, modelId)) return ModelValidation.INVALID_FORMAT
        val known = options[provider].orEmpty().firstOrNull { it.id == modelId }
        return if (known == null || task in known.tasks) ModelValidation.VALID else ModelValidation.INCOMPATIBLE_WITH_TASK
    }

    /** Chỉ áp dụng cho model hợp lệ nhưng ngoài danh mục; model trong danh mục đã được [validate] chặn nếu sai. */
    fun warning(provider: LlmProvider, task: LlmTask, modelId: String): ModelWarning? {
        if (options[provider].orEmpty().any { it.id == modelId }) return null
        val looksLikeImage = imageModelMarkers.any { modelId.contains(it, ignoreCase = true) }
        return if ((task in imageTasks) == looksLikeImage) null else ModelWarning.POSSIBLY_INCOMPATIBLE_WITH_TASK
    }
}
