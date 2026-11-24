package com.lttbdd.socialagent.di

import android.content.Context
import com.lttbdd.socialagent.data.repository.ChannelDataCleanup
import com.lttbdd.socialagent.data.repository.LocalChannelDataCleanup
import com.lttbdd.socialagent.data.repository.ChannelAccountStore
import com.lttbdd.socialagent.data.repository.ChannelRepository
import com.lttbdd.socialagent.data.repository.ChannelRepositoryImpl
import com.lttbdd.socialagent.data.repository.DataStoreChannelAccountStore
import com.lttbdd.socialagent.data.security.AndroidKeystoreCipher
import com.lttbdd.socialagent.data.security.SecureTokenStore
import com.lttbdd.socialagent.data.security.TokenStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SocialRepositoryModule {

    @Binds
    @Singleton
    abstract fun bindChannelRepository(
        impl: ChannelRepositoryImpl,
    ): ChannelRepository

    @Binds
    abstract fun bindChannelDataCleanup(impl: LocalChannelDataCleanup): ChannelDataCleanup

    @Binds
    @Singleton
    abstract fun bindTokenStore(
        impl: SecureTokenStore,
    ): TokenStore

    companion object {

        @Provides
        @Singleton
        fun provideAndroidKeystoreCipher(): AndroidKeystoreCipher {
            return AndroidKeystoreCipher()
        }

        @Provides
        @Singleton
        fun provideChannelAccountStore(
            @ApplicationContext context: Context,
        ): ChannelAccountStore = DataStoreChannelAccountStore(context)
    }
}
