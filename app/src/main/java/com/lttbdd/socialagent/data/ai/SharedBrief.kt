package com.lttbdd.socialagent.data.ai

import com.lttbdd.socialagent.core.model.TopicBrief

private val whitespace = Regex("\\s+")

/**
 * Brief chung cho mọi sub-agent. Xác định (không gọi LLM) nên không tốn token và test được.
 * Nội dung người dùng chỉ là dữ liệu; sub-agent phải đưa vào prompt như dữ liệu, không như chỉ thị.
 */
internal fun buildSharedBriefSummary(brief: TopicBrief): String = listOf(
    "Topic" to brief.topic,
    "Goal" to brief.goal,
    "Audience" to brief.audience,
    "Tone" to brief.tone,
    "Language" to brief.language.name,
    "Channels" to brief.channels.sortedBy { it.ordinal }.joinToString(", ") { it.name },
).joinToString("\n") { (label, value) -> "$label: ${value.replace(whitespace, " ").trim()}" }
