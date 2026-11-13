package com.lttbdd.socialagent.feature.channel

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chuyển deep link BFF gửi về app (`com.lttbdd.socialagent://oauth/linkedin/callback?...`) từ MainActivity
 * sang [ChannelViewModel].
 *
 * Deep link chỉ là một chuỗi chưa tin cậy: bus không tự kết nối kênh nào. ViewModel chỉ xử lý khi đang chờ
 * đăng nhập, `state` khớp và có `code_verifier` trong RAM (xem [LinkedInAuthRedirect]). Không replay: link
 * đến khi không có ai chờ (ví dụ app bị đóng giữa chừng) bị bỏ.
 */
@Singleton
class LinkedInCallbackBus @Inject constructor() {

    private val _callbacks = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val callbacks: SharedFlow<String> = _callbacks.asSharedFlow()

    fun publish(url: String) {
        _callbacks.tryEmit(url)
    }
}
