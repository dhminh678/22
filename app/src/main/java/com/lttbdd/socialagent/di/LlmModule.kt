package com.lttbdd.socialagent.di

import android.content.Context
import com.lttbdd.socialagent.core.ai.LlmClient
import com.lttbdd.socialagent.data.ai.ApiKeySource
import com.lttbdd.socialagent.data.ai.GeminiClient
import com.lttbdd.socialagent.data.ai.DataStoreTaskModelSelectionStore
import com.lttbdd.socialagent.data.ai.LlmConfigurationSource
import com.lttbdd.socialagent.data.ai.OkHttpSseTransport
import com.lttbdd.socialagent.data.ai.OpenAiClient
import com.lttbdd.socialagent.data.ai.ProviderKeyAvailability
import com.lttbdd.socialagent.data.ai.RoutingLlmClient
import com.lttbdd.socialagent.data.ai.TaskModelResolver
import com.lttbdd.socialagent.data.ai.TaskModelSelectionStore
import com.lttbdd.socialagent.data.apikeys.ApiKeyStore
import com.lttbdd.socialagent.data.apikeys.KeyStorageStatus
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.sse.EventSources

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {
    /** Lựa chọn model theo loại việc (#54), lưu bền vững; không chứa key. */
    @Provides
    @Singleton
    fun taskModelStore(@ApplicationContext context: Context): TaskModelSelectionStore =
        DataStoreTaskModelSelectionStore(context)

    /** Chỉ hỏi trạng thái key, không bao giờ đọc nội dung key. */
    @Provides
    fun providerKeyAvailability(store: ApiKeyStore): ProviderKeyAvailability =
        ProviderKeyAvailability { provider -> store.status(provider) == KeyStorageStatus.CONFIGURED }

    @Provides
    @Singleton
    fun taskModelResolver(store: TaskModelSelectionStore, keys: ProviderKeyAvailability): TaskModelResolver =
        TaskModelResolver(store, keys)

    @Provides
    fun configurations(resolver: TaskModelResolver): LlmConfigurationSource = resolver

    /** Đọc secure storage ngay trước mỗi request; không giữ key cũ sau khi thay/xóa. */
    @Provides
    fun apiKeys(store: ApiKeyStore): ApiKeySource = ApiKeySource { provider -> store.read(provider) }

    @Provides
    @Singleton
    fun llmClient(configurations: LlmConfigurationSource, keys: ApiKeySource): LlmClient {
        // Client riêng cho LLM, không ảnh hưởng HTTP/DI của mảng khác. Không có interceptor ghi log.
        val http = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val transport = OkHttpSseTransport(EventSources.createFactory(http))
        return RoutingLlmClient(configurations, keys, setOf(OpenAiClient(transport), GeminiClient(transport)))
    }
}
