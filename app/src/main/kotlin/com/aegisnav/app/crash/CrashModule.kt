package com.aegisnav.app.crash

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for crash reporting.
 *
 * To strip Sentry from a release build, change the binding here from
 * [SentryCrashReporter] to [NoOpCrashReporter]. That's the only file that needs to change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CrashModule {

    @Binds
    @Singleton
    abstract fun bindCrashReporter(impl: NoOpCrashReporter): CrashReporter
}
