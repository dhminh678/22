package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.ai.ContentAgent
import com.lttbdd.socialagent.core.model.AgentEvent
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.RewriteInstruction
import com.lttbdd.socialagent.core.model.TopicBrief
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Orchestrator của F2 (Issue #26): lập brief chung rồi chạy song song mỗi kênh một [ChannelSubAgent].
 *
 * Bảo đảm:
 * - Mỗi kênh phát đúng một sự kiện kết thúc ([AgentEvent.ChannelDone] hoặc [AgentEvent.ChannelFailed]).
 * - Lỗi của một kênh không làm dừng kênh khác; lý do lỗi chỉ là mã an toàn, không chứa key/prompt.
 * - Huỷ việc collect Flow huỷ toàn bộ sub-agent; huỷ thật không bị đổi thành lỗi nghiệp vụ.
 *   Riêng [TimeoutCancellationException] phát sinh bên trong sub-agent (scope vẫn còn sống) là lỗi của kênh đó
 *   và được báo bằng [AgentEvent.ChannelFailed] với mã [ChannelFailureReason.TIMEOUT].
 * - Không tự đăng bài: chỉ sinh bản nháp, việc duyệt/đăng thuộc F4/F5.
 *
 * Mỗi lần gọi [generate]/[regenerate] là một Flow lạnh độc lập nên luồng cũ không ghi đè kết quả lần chạy mới.
 *
 * [rewriteParagraph] và [regenerateImage] chưa thuộc Issue #26 (thuộc #35 và #28) nên ném
 * [UnsupportedOperationException]; vì vậy lớp này chưa được gắn vào DI thay cho `FakeContentAgent`.
 */
class ContentAgentImpl(
    private val subAgents: Map<ChannelType, ChannelSubAgent>,
    private val maxConcurrentChannels: Int = DEFAULT_MAX_CONCURRENT_CHANNELS,
) : ContentAgent {

    init {
        require(maxConcurrentChannels > 0) { "maxConcurrentChannels phải > 0" }
    }

    override fun generate(brief: TopicBrief): Flow<AgentEvent> = channelFlow {
        val summary = buildSharedBriefSummary(brief)
        send(AgentEvent.BriefReady(summary))
        val gate = Semaphore(maxConcurrentChannels)
        supervisorScope {
            brief.channels.sortedBy { it.ordinal }.forEach { channel ->
                launch { gate.withPermit { runChannel(brief, channel, summary) } }
            }
        }
    }

    override fun regenerate(brief: TopicBrief, channel: ChannelType): Flow<AgentEvent> = channelFlow {
        if (channel !in brief.channels) {
            send(AgentEvent.ChannelFailed(channel, AgentStep.OUTLINE, ChannelFailureReason.CHANNEL_NOT_IN_BRIEF))
        } else {
            runChannel(brief, channel, buildSharedBriefSummary(brief))
        }
    }

    override suspend fun rewriteParagraph(
        draft: Draft,
        paragraph: String,
        instruction: RewriteInstruction,
    ): String = throw UnsupportedOperationException("Viết lại đoạn thuộc Issue #35")

    override suspend fun regenerateImage(draft: Draft): String =
        throw UnsupportedOperationException("Sinh lại ảnh thuộc Issue #28")

    /**
     * Trạng thái riêng của một lần chạy kênh. Sub-agent có thể phát từ nhiều coroutine con nên mọi thao tác
     * "kiểm tra đã kết thúc → cập nhật → gửi" chạy trong [mutex]; nhờ đó chỉ một sự kiện kết thúc được gửi
     * và không có sự kiện nào đi sau nó.
     */
    private class ChannelRunState {
        val mutex = Mutex()
        var step = AgentStep.OUTLINE
        var finished = false
    }

    private suspend fun ProducerScope<AgentEvent>.runChannel(
        brief: TopicBrief,
        channel: ChannelType,
        summary: String,
    ) {
        val subAgent = subAgents[channel]
        if (subAgent == null) {
            send(AgentEvent.ChannelFailed(channel, AgentStep.OUTLINE, ChannelFailureReason.UNSUPPORTED_CHANNEL))
            return
        }
        val state = ChannelRunState()
        try {
            subAgent.run(ChannelRunContext(brief, channel, summary)) { event ->
                // Orchestrator sở hữu BriefReady; sự kiện của kênh khác hoặc sau khi đã kết thúc bị bỏ để cách ly kênh.
                if (!event.belongsTo(channel)) return@run
                state.mutex.withLock {
                    if (state.finished) return@withLock
                    if (event is AgentEvent.StepStarted) state.step = event.step
                    send(event)
                    // Chỉ đánh dấu kết thúc SAU khi send xong: nếu send bị treo (backpressure) rồi bị
                    // timeout cắt thì kênh vẫn chưa có sự kiện kết thúc và catch bên dưới sẽ báo lỗi.
                    if (event is AgentEvent.ChannelDone || event is AgentEvent.ChannelFailed) state.finished = true
                }
            }
            failIfUnfinished(state, channel, ChannelFailureReason.NO_RESULT)
        } catch (cancelled: CancellationException) {
            // Huỷ thật (scope đã bị huỷ) thì ensureActive ném lại và dừng cả cây coroutine.
            // Nếu scope vẫn sống thì đây là timeout/huỷ nội bộ của chính sub-agent: kênh này phải có kết thúc.
            currentCoroutineContext().ensureActive()
            val reason = if (cancelled is TimeoutCancellationException) {
                ChannelFailureReason.TIMEOUT
            } else {
                ChannelFailureReason.UNKNOWN
            }
            failIfUnfinished(state, channel, reason)
        } catch (error: Exception) {
            failIfUnfinished(state, channel, error.toSafeReason(), (error as? ChannelSubAgentException)?.step)
        }
    }

    /** Phát [AgentEvent.ChannelFailed] đúng một lần nếu kênh chưa có sự kiện kết thúc. */
    private suspend fun ProducerScope<AgentEvent>.failIfUnfinished(
        state: ChannelRunState,
        channel: ChannelType,
        reason: String,
        step: AgentStep? = null,
    ) {
        state.mutex.withLock {
            if (state.finished) return
            send(AgentEvent.ChannelFailed(channel, step ?: state.step, reason))
            state.finished = true
        }
    }

    private fun AgentEvent.belongsTo(channel: ChannelType): Boolean = when (this) {
        is AgentEvent.BriefReady -> false
        is AgentEvent.StepStarted -> this.channel == channel
        is AgentEvent.TextDelta -> this.channel == channel
        is AgentEvent.StepFinished -> this.channel == channel
        is AgentEvent.ChannelDone -> draft.channel == channel
        is AgentEvent.ChannelFailed -> this.channel == channel
    }

    /** Không dùng `message` của exception lạ vì có thể chứa URL/key/prompt. */
    private fun Exception.toSafeReason(): String = when (this) {
        is ChannelSubAgentException -> reason
        is LlmException -> ChannelFailureReason.fromLlm(failure)
        else -> ChannelFailureReason.UNKNOWN
    }

    companion object {
        /** Đủ để Facebook + LinkedIn chạy cùng lúc; hạn chế bùng nổ request/rate limit khi thêm kênh. */
        const val DEFAULT_MAX_CONCURRENT_CHANNELS = 2
    }
}
