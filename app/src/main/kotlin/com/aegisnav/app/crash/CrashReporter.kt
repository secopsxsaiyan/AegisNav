package com.aegisnav.app.crash

interface CrashReporter {
    fun captureMessage(message: String)
    fun captureException(throwable: Throwable)
    fun captureEvent(level: String, message: String, tags: Map<String, String> = emptyMap())
    fun setTag(key: String, value: String)
}
