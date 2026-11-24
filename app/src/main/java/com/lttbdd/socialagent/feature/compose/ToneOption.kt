package com.lttbdd.socialagent.feature.compose

import androidx.annotation.StringRes
import com.lttbdd.socialagent.R

/**
 * Tùy chọn giọng văn (Tone of Voice) cho bài viết ở màn F3.
 * [key] là mã tiếng Anh ổn định (`professional`, `friendly`, `casual`, `inspirational`, `persuasive`)
 * được lưu vào [com.lttbdd.socialagent.core.model.TopicBrief.tone] để không phụ thuộc ngôn ngữ UI
 * khi bàn giao sang F2.
 */
data class ToneOption(
    val key: String,
    @param:StringRes val labelRes: Int,
) {
    companion object {
        val ALL = listOf(
            ToneOption("professional", R.string.tone_professional),
            ToneOption("friendly", R.string.tone_friendly),
            ToneOption("casual", R.string.tone_casual),
            ToneOption("inspirational", R.string.tone_inspirational),
            ToneOption("persuasive", R.string.tone_persuasive),
        )
    }
}
