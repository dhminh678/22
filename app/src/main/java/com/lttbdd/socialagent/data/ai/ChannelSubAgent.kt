package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.AgentEvent
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.TopicBrief

/** Ngữ cảnh một lần chạy sub-agent của MỘT kênh; mọi kênh nhận cùng một brief chung. */
data class ChannelRunContext(
    val brief: TopicBrief,
    val channel: ChannelType,
    /** Brief chung do orchestrator lập, giống nhau cho mọi kênh. */
    val sharedBriefSummary: String,
)

/**
 * Sub-agent của một kênh (luồng Dàn ý → … → Đóng gói, Issue #27 triển khai).
 *
 * Quy ước với orchestrator:
 * - Phát tiến độ qua [emit]; kết thúc bằng đúng một [AgentEvent.ChannelDone] hoặc [AgentEvent.ChannelFailed].
 * - Gặp lỗi thì ném exception (nên là [ChannelSubAgentException] hoặc [LlmException]); orchestrator đổi thành
 *   [AgentEvent.ChannelFailed] mà không ảnh hưởng kênh khác.
 * - Không bắt [kotlinx.coroutines.CancellationException]; huỷ phải lan hết cây coroutine.
 */
fun interface ChannelSubAgent {
    suspend fun run(context: ChannelRunContext, emit: suspend (AgentEvent) -> Unit)
}

/**
 * Lỗi nghiệp vụ của sub-agent kèm bước đang chạy.
 * [reason] phải là mã an toàn (xem [ChannelFailureReason]); tuyệt đối không chứa key, token hay prompt.
 */
class ChannelSubAgentException(
    val step: AgentStep,
    val reason: String,
) : Exception(reason)

/** Mã lý do lỗi ổn định để UI ánh xạ sang chuỗi hiển thị EN/VI. */
object ChannelFailureReason {
    const val UNKNOWN = "UNKNOWN"
    const val UNSUPPORTED_CHANNEL = "UNSUPPORTED_CHANNEL"
    const val CHANNEL_NOT_IN_BRIEF = "CHANNEL_NOT_IN_BRIEF"
    const val NO_RESULT = "NO_RESULT"
    const val TIMEOUT = "TIMEOUT"

    fun fromLlm(failure: LlmFailure): String = "LLM_${failure.name}"
}
