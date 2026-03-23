package com.aegisnav.app.data.db

import android.content.Context
import com.aegisnav.app.di.ApplicationScope
import com.aegisnav.app.p2p.P2PManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object InfraModule {

    @Provides @Singleton
    fun provideP2PManager(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope
    ): P2PManager = P2PManager(context, scope)
}
