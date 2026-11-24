package com.lttbdd.socialagent

import android.app.Application
import com.lttbdd.socialagent.data.repository.ChannelRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class SocialAgentApp : Application() {

    @Inject
    lateinit var channelRepository: Provider<ChannelRepository>

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.Main).launch {
            // Khôi phục các lượt ngắt kết nối dang dở ngay cả khi không mở màn hình Kênh của tôi.
            try {
                channelRepository.get().getConnectedChannels().first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Lần đọc tiếp theo sẽ thử lại nếu kho lưu trữ chưa sẵn sàng.
            }
        }
    }
}
