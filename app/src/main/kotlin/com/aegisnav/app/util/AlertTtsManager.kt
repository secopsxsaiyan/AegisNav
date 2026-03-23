package com.aegisnav.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.aegisnav.app.AppLifecycleTracker
import com.aegisnav.app.crash.CrashReporter
import com.aegisnav.app.data.repository.AppPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Alert TTS categories for per-category enable/disable settings.
 */
enum class TtsCategory { TRACKER, POLICE, SURVEILLANCE, CONVOY, NAVIGATION }

/**
 * AlertTtsManager - singleton TTS wrapper for non-navigation voice alerts.
 *
 * Uses QUEUE_ADD so alerts don't clobber each other or NavigationViewModel's
 * navigation instructions (which use their own TTS instance with QUEUE_FLUSH).
 *
 * Audio focus: requests AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK before speaking,
 * which ducks music to ~10% volume while TTS plays at full volume over
 * Bluetooth or speaker. Focus is released when utterance completes.
 *
 * Injected into:
 *  - ScanService   → tracker + police alerts
 *  - PoliceReportingCoordinator → police activity alerts (with cooldown)
 *  - NavigationViewModel → ALPR / speed / red-light camera proximity alerts
 */
@Singleton
class AlertTtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPrefs: AppPreferencesRepository,
    private val crashReporter: CrashReporter
) {
    private val tag = "AlertTtsManager"

    private var tts: TextToSpeech? = null
    private var ready = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private val maxRetries = 3
    private val retryDelayMs = 3_000L

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .setWillPauseWhenDucked(false)
        .build()

    init {
        initTts()
    }

    /**
     * Configures a successfully initialized TTS instance (language, audio attributes, utterance listener).
     */
    private fun onTtsReady() {
        tts?.language = Locale.US
        // Route TTS through notification audio stream so it plays over Bluetooth music
        tts?.setAudioAttributes(audioAttributes)
        // Release audio focus when utterance completes
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { abandonFocus() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { abandonFocus() }
            override fun onError(utteranceId: String?, errorCode: Int) { abandonFocus() }
        })
        AppLog.i(tag, "TTS initialized successfully")
    }

    /**
     * Initializes TTS with the default engine. On failure, retries up to [maxRetries] times
     * (with a [retryDelayMs] delay between each) before falling back to explicit engine selection.
     * This handles API 36 cold-boot and post-reinstall races where the TTS engine service
     * isn't immediately available.
     */
    private fun initTts(attempt: Int = 1) {
        AppLog.i(tag, "TTS init attempt $attempt/$maxRetries (default engine)")
        tts = TextToSpeech(context) { status ->
            AppLog.i(tag, "Available TTS engines: ${tts?.engines?.map { it.name }}")
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                onTtsReady()
            } else {
                AppLog.w(tag, "TTS init failed (attempt $attempt): status=$status")
                crashReporter.captureMessage("TTS init failed: status=$status")
                if (attempt < maxRetries) {
                    AppLog.i(tag, "Retrying TTS init in ${retryDelayMs}ms (attempt ${attempt + 1}/$maxRetries)")
                    handler.postDelayed({ initTts(attempt + 1) }, retryDelayMs)
                } else {
                    AppLog.w(tag, "TTS default engine exhausted retries — trying fallback engines")
                    initTtsWithFallbackEngines()
                }
            }
        }
    }

    /**
     * Attempts TTS initialization with each installed engine in turn after the default engine
     * fails all retries. Iterates through [tts.engines] and tries each by package name.
     */
    private fun initTtsWithFallbackEngines(engineIndex: Int = 0) {
        val engines = tts?.engines ?: emptyList()
        if (engineIndex >= engines.size) {
            AppLog.e(tag, "TTS init failed for all engines — voice alerts disabled")
            crashReporter.captureMessage("TTS init failed for all engines")
            return
        }
        val engine = engines[engineIndex]
        AppLog.i(tag, "Trying TTS with fallback engine: ${engine.name}")
        tts?.shutdown()
        tts = TextToSpeech(context, { status ->
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                onTtsReady()
                AppLog.i(tag, "TTS initialized with fallback engine: ${engine.name}")
            } else {
                AppLog.w(tag, "TTS init failed with engine ${engine.name}: status=$status")
                initTtsWithFallbackEngines(engineIndex + 1)
            }
        }, engine.name)
    }

    /**
     * Reinitializes TTS from scratch. Call this if the user installs a TTS engine after first
     * launch, or to manually recover from a failed init state.
     */
    fun reinitialize() {
        AppLog.i(tag, "Reinitializing TTS...")
        handler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
        ready = false
        initTts()
    }

    // Audio focus is requested per-utterance and abandoned on completion.
    // No continuous hold — music unducks between announcements.

    private fun requestFocus() {
        val result = audioManager.requestAudioFocus(focusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Duck music volume to ~10% for alert clarity
            // setVolumePercent is not available — ducking is handled by the OS via AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AppLog.d(tag, "Audio focus granted (duck)")
        } else {
            AppLog.w(tag, "Audio focus request denied: $result")
            crashReporter.captureMessage("TTS audio focus denied: result=$result")
        }
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        AppLog.d(tag, "Audio focus released")
    }

    /**
     * Speak [message] via TTS. Requests audio focus (duck music) before speaking.
     * Uses QUEUE_ADD to avoid interrupting other speech.
     * No-op if TTS is not ready.
     */
    fun speak(message: String, utteranceId: String = "alert") {
        // Suppress TTS when app is in background or killed
        if (!AppLifecycleTracker.isInForeground || AppLifecycleTracker.isKilled) {
            AppLog.d(tag, "TTS suppressed (background/killed) — skipping speak: $message")
            return
        }
        if (!ready) {
            AppLog.w(tag, "TTS not ready — skipping speak: $message")
            crashReporter.captureMessage("TTS not ready — skipped: $message")
            return
        }
        requestFocus()
        // TTS params: max volume for the alert stream
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(message, TextToSpeech.QUEUE_ADD, params, utteranceId)
    }

    /**
     * Speak [message] only if both master TTS and the specific [category] toggle are enabled.
     * If either is false, logs and skips.
     */
    fun speakIfEnabled(message: String, category: TtsCategory, utteranceId: String = "alert") {
        if (!appPrefs.ttsMasterEnabled.value) {
            AppLog.d(tag, "TTS master disabled — skipping speak: $message")
            return
        }
        val categoryEnabled = when (category) {
            TtsCategory.TRACKER      -> appPrefs.ttsTrackerEnabled.value
            TtsCategory.POLICE       -> appPrefs.ttsPoliceEnabled.value
            TtsCategory.SURVEILLANCE -> appPrefs.ttsSurveillanceEnabled.value
            TtsCategory.CONVOY       -> appPrefs.ttsConvoyEnabled.value
            TtsCategory.NAVIGATION   -> appPrefs.ttsMasterEnabled.value
        }
        if (!categoryEnabled) {
            AppLog.d(tag, "TTS category $category disabled — skipping speak: $message")
            return
        }
        speak(message, utteranceId)
    }

    /**
     * Immediately stop any in-progress TTS speech (and clear the queue).
     * Call before priority announcements (250ft, at-turn) to ensure they
     * are never blocked by a queued "continue straight" or old announcement.
     */
    fun interrupt() {
        tts?.stop()
    }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        abandonFocus()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
