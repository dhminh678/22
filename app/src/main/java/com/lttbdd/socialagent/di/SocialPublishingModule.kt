package com.lttbdd.socialagent.di

import android.content.Context
import com.lttbdd.socialagent.core.social.SocialChannelClient
import com.lttbdd.socialagent.data.publishing.PublishKeyFactory
import com.lttbdd.socialagent.data.social.FacebookClient
import com.lttbdd.socialagent.data.social.LinkedInClient
import com.lttbdd.socialagent.data.social.PublishDestinations
import com.lttbdd.socialagent.data.social.PublishHttp
import com.lttbdd.socialagent.data.social.readFacebookImage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object SocialPublishingModule {
    @Provides
    fun publishKeyFactory(destinations: PublishDestinations): PublishKeyFactory =
        PublishKeyFactory(destinations::newKey)

    @Provides
    @Singleton
    fun publishHttp(): PublishHttp = PublishHttp()

    @Provides
    @IntoSet
    @Singleton
    fun facebookClient(
        @ApplicationContext context: Context,
        destinations: PublishDestinations,
        http: PublishHttp,
    ): SocialChannelClient = FacebookClient(destinations, http) { readFacebookImage(context.contentResolver, it) }

    @Provides
    @IntoSet
    @Singleton
    fun linkedInClient(destinations: PublishDestinations, http: PublishHttp): SocialChannelClient =
        LinkedInClient(destinations, http)
}
