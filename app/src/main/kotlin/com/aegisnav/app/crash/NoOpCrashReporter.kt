package com.aegisnav.app.crash

import javax.inject.Inject

/**
 * No-op CrashReporter for release builds.
 * Swap CrashModule to provide this instead of SentryCrashReporter to strip all crash reporting.
 */
class NoOpCrashReporter @Inject constructor() : CrashReporter {
    override fun captureMessage(message: String) = Unit
    override fun captureException(throwable: Throwable) = Unit
    override fun captureEvent(level: String, message: String, tags: Map<String, String>) = Unit
    override fun setTag(key: String, value: String) = Unit
}
