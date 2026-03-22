package com.aegisnav.app.util

import android.util.Log
import com.aegisnav.app.BuildConfig

/** Debug-only logging wrapper. All calls are no-ops in release builds. */
object AppLog {
    fun d(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.d(tag, msg) }
    fun i(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.i(tag, msg) }
    fun v(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.v(tag, msg) }
    fun w(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.w(tag, msg) }
    fun w(tag: String, msg: String, tr: Throwable?) { if (BuildConfig.DEBUG) Log.w(tag, msg, tr) }
    fun e(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.e(tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable?) { if (BuildConfig.DEBUG) Log.e(tag, msg, tr) }
}
