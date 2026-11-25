package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.AgentEvent
import com.lttbdd.socialagent.core.model.AgentStep
import com.lttbdd.socialagent.core.model.ChannelType
import com.lttbdd.socialagent.core.model.ContentLanguage
import com.lttbdd.socialagent.core.model.Draft
import com.lttbdd.socialagent.core.model.TopicBrief
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContentAgentImplTest {

    private val fb = ChannelType.FACEBOOK
    private val li = ChannelType.LINKEDIN

    private fun brief(channels: Set<ChannelType> = setOf(fb, li)) = TopicBrief(
        id = "t1",
        topic = "  AI   agents ",
        goal = "Tăng nhận diện",
        audience = "Dev",
        tone = "Thân thiện",
        language = ContentLanguage.VI,
        channels = channels,
    )

    private fun draft(channel: ChannelType) = Draft("d-${channel.name}", "t1", channel, "text $channel")

    /** Sub-agent chạy đủ 6 bước rồi ChannelDone. */
    private fun okAgent(channel: ChannelType, onRun: (ChannelRunContext) -> Unit = {}) = ChannelSubAgent { ctx, emit ->
        onRun(ctx)
        emit(AgentEvent.StepStarted(channel, AgentStep.OUTLINE))
        emit(AgentEvent.ChannelDone(draft(channel)))
    }

    private fun failures(events: List<AgentEvent>) = events.filterIsInstance<AgentEvent.ChannelFailed>()

    private fun done(events: List<AgentEvent>) = events.filterIsInstance<AgentEvent.ChannelDone>().map { it.draft.channel }

    @Test
    fun `generate phat BriefReady dau tien va moi kenh nhan cung brief chung`() = runTest {
        val seen = mutableMapOf<ChannelType, String>()
        val agent = ContentAgentImpl(
            mapOf(
                fb to okAgent(fb) { seen[fb] = it.sharedBriefSummary },
                li to okAgent(li) { seen[li] = it.sharedBriefSummary },
            ),
        )

        val events = agent.generate(brief()).toList()

        val ready = events.first() as AgentEvent.BriefReady
        assertEquals(
            "Topic: AI agents\nGoal: Tăng nhận diện\nAudience: Dev\nTone: Thân thiện\nLanguage: VI\nChannels: FACEBOOK, LINKEDIN",
            ready.summary,
        )
        assertEquals(setOf(fb, li), done(events).toSet())
        assertEquals(mapOf(fb to ready.summary, li to ready.summary), seen)
        assertTrue(failures(events).isEmpty())
    }

    @Test
    fun `cac kenh chay song song - kenh B xong khi kenh A van dang cho`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val agent = ContentAgentImpl(
            mapOf(
                fb to ChannelSubAgent { _, emit ->
                    emit(AgentEvent.StepStarted(fb, AgentStep.OUTLINE))
                    gate.await()
                    emit(AgentEvent.ChannelDone(draft(fb)))
                },
                li to okAgent(li),
            ),
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch { agent.generate(brief()).collect { events += it } }

        runCurrent()
        assertEquals(listOf(li), done(events))
        assertFalse(job.isCompleted)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(setOf(fb, li), done(events).toSet())
        assertTrue(job.isCompleted)
    }

    @Test
    fun `mot kenh loi van de kenh khac hoan thanh va flow ket thuc binh thuong`() = runTest {
        val agent = ContentAgentImpl(
            mapOf(
                fb to ChannelSubAgent { _, emit ->
                    emit(AgentEvent.StepStarted(fb, AgentStep.DRAFT))
                    error("boom sk-secret-key https://api.example/v1?key=abc")
                },
                li to okAgent(li),
            ),
        )

        val events = agent.generate(brief()).toList()

        assertEquals(listOf(li), done(events))
        val failed = failures(events).single()
        assertEquals(fb, failed.channel)
        assertEquals(AgentStep.DRAFT, failed.step)
        assertEquals(ChannelFailureReason.UNKNOWN, failed.reason)
        assertFalse(failed.reason.contains("secret"))
    }

    @Test
    fun `LlmException duoc doi thanh ma an toan`() = runTest {
        val agent = ContentAgentImpl(
            mapOf(fb to ChannelSubAgent { _, _ -> throw LlmException(LlmFailure.RATE_LIMIT, httpStatus = 429) }),
        )

        val failed = failures(agent.generate(brief(setOf(fb))).toList()).single()

        assertEquals("LLM_RATE_LIMIT", failed.reason)
        assertEquals(AgentStep.OUTLINE, failed.step)
    }

    @Test
    fun `ChannelSubAgentException dung buoc va ma do sub-agent cung cap`() = runTest {
        val agent = ContentAgentImpl(
            mapOf(
                li to ChannelSubAgent { _, emit ->
                    emit(AgentEvent.StepStarted(li, AgentStep.DRAFT))
                    throw ChannelSubAgentException(AgentStep.SELF_REVIEW, "REVIEW_REJECTED")
                },
            ),
        )

        val failed = failures(agent.generate(brief(setOf(li))).toList()).single()

        assertEquals(AgentStep.SELF_REVIEW, failed.step)
        assertEquals("REVIEW_REJECTED", failed.reason)
    }

    @Test
    fun `huy collect huy sub-agent va khong phat them su kien hay loi`() = runTest {
        var cancelled = false
        val started = CompletableDeferred<Unit>()
        val agent = ContentAgentImpl(
            mapOf(
                fb to ChannelSubAgent { _, emit ->
                    try {
                        emit(AgentEvent.StepStarted(fb, AgentStep.DRAFT))
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                },
                li to ChannelSubAgent { _, _ -> awaitCancellation() },
            ),
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch { agent.generate(brief()).collect { events += it } }
        runCurrent()
        assertTrue(started.isCompleted)

        job.cancel()
        advanceUntilIdle()

        assertTrue(cancelled)
        assertTrue(job.isCancelled)
        assertTrue(failures(events).isEmpty())
        assertTrue(done(events).isEmpty())
    }

    @Test
    fun `timeout ben trong sub-agent thanh ChannelFailed TIMEOUT va kenh khac van xong`() = runTest {
        val agent = ContentAgentImpl(
            mapOf(
                fb to ChannelSubAgent { _, emit ->
                    emit(AgentEvent.StepStarted(fb, AgentStep.DRAFT))
                    withTimeout(1_000) { awaitCancellation() }
                },
                li to okAgent(li),
            ),
        )

        val events = agent.generate(brief()).toList()

        assertEquals(listOf(li), done(events))
        val failed = failures(events).single()
        assertEquals(fb, failed.channel)
        assertEquals(AgentStep.DRAFT, failed.step)
        assertEquals(ChannelFailureReason.TIMEOUT, failed.reason)
    }

    @Test
    fun `huy noi bo cua sub-agent khi scope con song van co su kien ket thuc`() = runTest {
        val agent = ContentAgentImpl(
            mapOf(fb to ChannelSubAgent { _, _ -> throw CancellationException("noi bo") }),
        )

        val failed = failures(agent.generate(brief(setOf(fb))).toList()).single()

        assertEquals(ChannelFailureReason.UNKNOWN, failed.reason)
    }

    @Test
    fun `timeout khi gui su kien ket thuc bi treo do backpressure van bao ChannelFailed`() = runTest {
        val release = CompletableDeferred<Unit>()
        val agent = ContentAgentImpl(
            mapOf(
                fb to ChannelSubAgent { _, emit ->
                    // Làm đầy bộ đệm của Flow để lần send tiếp theo bị treo vì collector chậm.
                    try {
                        while (true) {
                            withTimeout(100) { emit(AgentEvent.TextDelta(fb, AgentStep.DRAFT, "x")) }
                        }
                    } catch (_: TimeoutCancellationException) {
                        // Bộ đệm đã đầy.
                    }
                    withTimeout(1_000) { emit(AgentEvent.ChannelDone(draft(fb))) }
                },
            ),
        )
        val events = mutableListOf<AgentEvent>()
        val job = launch { agent.generate(brief(setOf(fb))).collect { events += it; release.await() } }

        runCurrent()
        advanceUntilIdle()
        release.complete(Unit)
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertTrue(done(events).isEmpty())
        val failed = failures(events).single()
        assertEquals(ChannelFailureReason.TIMEOUT, failed.reason)
    }

    @Test
    fun `phat ket thuc dong thoi tu nhieu coroutine con chi gui mot su kien`() = runTest {
        val agent = ContentAgentImpl(
            mapOf(
                fb to ChannelSubAgent { _, emit ->
                    withContext(Dispatchers.Default) {
                        coroutineScope {
                            repeat(16) { i ->
                                launch {
                                    if (i % 2 == 0) emit(AgentEvent.ChannelDone(draft(fb))) else {
                                        emit(AgentEvent.ChannelFailed(fb, AgentStep.DRAFT, "X"))
                                    }
                                }
                            }
                        }
                    }
                },
            ),
        )

        val events = agent.generate(brief(setOf(fb))).toList()

        assertEquals(1, done(events).size + failures(events).size)
    }

    @Test
    fun `regenerate chi chay kenh dich va khong phat BriefReady`() = runTest {
        var liRuns = 0
        val agent = ContentAgentImpl(
            mapOf(fb to okAgent(fb), li to okAgent(li) { liRuns++ }),
        )

        val events = agent.regenerate(brief(), fb).toList()

        assertTrue(events.none { it is AgentEvent.BriefReady })
        assertEquals(listOf(fb), done(events))
        assertEquals(0, liRuns)
    }

    @Test
    fun `regenerate kenh khong co trong brief bao loi`() = runTest {
        val agent = ContentAgentImpl(mapOf(fb to okAgent(fb), li to okAgent(li)))

        val failed = failures(agent.regenerate(brief(setOf(fb)), li).toList()).single()

        assertEquals(li, failed.channel)
        assertEquals(ChannelFailureReason.CHANNEL_NOT_IN_BRIEF, failed.reason)
    }

    @Test
    fun `kenh chua co sub-agent bao loi con kenh khac van chay`() = runTest {
        val agent = ContentAgentImpl(mapOf(li to okAgent(li)))

        val events = agent.generate(brief()).toList()

        assertEquals(listOf(li), done(events))
        val failed = failures(events).single()
        assertEquals(fb, failed.channel)
        assertEquals(ChannelFailureReason.UNSUPPORTED_CHANNEL, failed.reason)
    }

    @Test
    fun `sub-agent tra ve ma khong ket thuc thi orchestrator bao NO_RESULT`() = runTest {
        val agent = ContentAgentImpl(
            mapOf(fb to ChannelSubAgent { _, emit -> emit(AgentEvent.StepStarted(fb, AgentStep.IMAGE)) }),
        )

        val failed = failures(agent.generate(brief(setOf(fb))).toList()).single()

        assertEquals(ChannelFailureReason.NO_RESULT, failed.reason)
        assertEquals(AgentStep.IMAGE, failed.step)
    }

    @Test
    fun `su kien cua kenh khac va BriefReady gia tu sub-agent bi bo`() = runTest {
        val agent = ContentAgentImpl(
            mapOf(
                fb to ChannelSubAgent { _, emit ->
                    emit(AgentEvent.BriefReady("fake"))
                    emit(AgentEvent.TextDelta(li, AgentStep.DRAFT, "lan kenh"))
                    emit(AgentEvent.ChannelDone(draft(li)))
                    emit(AgentEvent.ChannelDone(draft(fb)))
                },
            ),
        )

        val events = agent.generate(brief(setOf(fb))).toList()

        assertEquals(1, events.count { it is AgentEvent.BriefReady })
        assertTrue(events.none { it is AgentEvent.TextDelta })
        assertEquals(listOf(fb), done(events))
    }

    @Test
    fun `chi mot su kien ket thuc moi kenh du sub-agent nem loi sau ChannelDone`() = runTest {
        val agent = ContentAgentImpl(
            mapOf(
                fb to ChannelSubAgent { _, emit ->
                    emit(AgentEvent.ChannelDone(draft(fb)))
                    error("late")
                },
            ),
        )

        val events = agent.generate(brief(setOf(fb))).toList()

        assertEquals(listOf(fb), done(events))
        assertTrue(failures(events).isEmpty())
    }

    @Test
    fun `brief khong co kenh chi phat BriefReady`() = runTest {
        val events = ContentAgentImpl(emptyMap()).generate(brief(emptySet())).toList()

        assertEquals(1, events.size)
        assertTrue(events.single() is AgentEvent.BriefReady)
    }

    @Test
    fun `gioi han dong thoi 1 chay lan luot tung kenh`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var liStarted = false
        val agent = ContentAgentImpl(
            mapOf(
                fb to ChannelSubAgent { _, emit ->
                    gate.await()
                    emit(AgentEvent.ChannelDone(draft(fb)))
                },
                li to ChannelSubAgent { _, emit ->
                    liStarted = true
                    emit(AgentEvent.ChannelDone(draft(li)))
                },
            ),
            maxConcurrentChannels = 1,
        )
        val job = launch { agent.generate(brief()).toList() }

        runCurrent()
        assertFalse(liStarted)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(liStarted)
        assertTrue(job.isCompleted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxConcurrentChannels phai duong`() {
        ContentAgentImpl(emptyMap(), maxConcurrentChannels = 0)
    }

    @Test(expected = UnsupportedOperationException::class)
    fun `rewriteParagraph chua ho tro trong Issue 26`() = runTest {
        ContentAgentImpl(emptyMap()).rewriteParagraph(draft(fb), "x", com.lttbdd.socialagent.core.model.RewriteInstruction.SHORTER)
    }
}
