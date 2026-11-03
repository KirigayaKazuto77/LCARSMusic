package com.infotainment.lcarsmusic

import android.media.audiofx.Equalizer
import android.util.Log

object EqualizerEngine {
    private const val TAG = MusicPlaybackService.VISUALIZER_LOG_TAG

    @Volatile
    private var equalizer: Equalizer? = null

    @Volatile
    var activeSessionId: Int = 0
        private set

    val activeEqualizer: Equalizer?
        get() = equalizer

    @Synchronized
    fun initialize(sessionId: Int): Equalizer? {
        if (sessionId < 0) {
            Log.w(TAG, "EQUALIZER_INIT_SKIPPED_INVALID_SESSION sessionId=$sessionId")
            return null
        }

        equalizer?.let { existingEqualizer ->
            if (activeSessionId == sessionId) {
                return if (enable(existingEqualizer, sessionId)) {
                    Log.d(TAG, "EQUALIZER_REUSED sessionId=$sessionId")
                    existingEqualizer
                } else {
                    runCatching { existingEqualizer.release() }
                    markInitializationFailed(sessionId)
                    null
                }
            }
        }

        release()

        return try {
            @Suppress("DEPRECATION")
            val newEqualizer = Equalizer(0, sessionId)
            if (!enable(newEqualizer, sessionId)) {
                runCatching { newEqualizer.release() }
                markInitializationFailed(sessionId)
                return null
            }

            equalizer = newEqualizer
            activeSessionId = sessionId
            val numberOfBands = runCatching { newEqualizer.numberOfBands }.getOrNull()
            Log.d(
                TAG,
                "EQUALIZER_INITIALIZED sessionId=$sessionId bands=$numberOfBands"
            )
            newEqualizer
        } catch (e: RuntimeException) {
            markInitializationFailed(sessionId)
            Log.e(TAG, "EQUALIZER_INIT_FAILED sessionId=$sessionId", e)
            null
        }
    }

    @Synchronized
    fun release() {
        val sessionId = activeSessionId
        equalizer?.let { existingEqualizer ->
            try {
                existingEqualizer.release()
                Log.d(TAG, "EQUALIZER_RELEASED sessionId=$sessionId")
            } catch (e: RuntimeException) {
                Log.w(TAG, "EQUALIZER_RELEASE_FAILED sessionId=$sessionId", e)
            }
        }
        equalizer = null
        activeSessionId = 0
    }

    fun getNumberOfBands(): Short? = runCatching {
        activeEqualizer?.numberOfBands
    }.getOrElse { error ->
        Log.w(TAG, "EQUALIZER_QUERY_BANDS_FAILED sessionId=$activeSessionId", error)
        null
    }

    fun getBandLevelRange(): ShortArray? = runCatching {
        activeEqualizer?.bandLevelRange
    }.getOrElse { error ->
        Log.w(TAG, "EQUALIZER_QUERY_LEVEL_RANGE_FAILED sessionId=$activeSessionId", error)
        null
    }

    fun getCenterFreq(band: Short): Int? = runCatching {
        activeEqualizer?.getCenterFreq(band)
    }.getOrElse { error ->
        Log.w(TAG, "EQUALIZER_QUERY_CENTER_FREQ_FAILED sessionId=$activeSessionId band=$band", error)
        null
    }

    fun getBandFreqRange(band: Short): IntArray? = runCatching {
        activeEqualizer?.getBandFreqRange(band)
    }.getOrElse { error ->
        Log.w(TAG, "EQUALIZER_QUERY_FREQ_RANGE_FAILED sessionId=$activeSessionId band=$band", error)
        null
    }

    fun getNumberOfPresets(): Short? = runCatching {
        activeEqualizer?.numberOfPresets
    }.getOrElse { error ->
        Log.w(TAG, "EQUALIZER_QUERY_PRESETS_FAILED sessionId=$activeSessionId", error)
        null
    }

    fun getPresetName(preset: Short): String? = runCatching {
        activeEqualizer?.getPresetName(preset)
    }.getOrElse { error ->
        Log.w(TAG, "EQUALIZER_QUERY_PRESET_NAME_FAILED sessionId=$activeSessionId preset=$preset", error)
        null
    }

    private fun enable(equalizer: Equalizer, sessionId: Int): Boolean {
        return try {
            equalizer.enabled = true
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "EQUALIZER_ENABLE_FAILED sessionId=$sessionId", e)
            false
        }
    }

    private fun markInitializationFailed(sessionId: Int) {
        equalizer = null
        activeSessionId = -1
        Log.d(TAG, "EQUALIZER_MARKED_BROKEN sessionId=$sessionId")
    }
}
