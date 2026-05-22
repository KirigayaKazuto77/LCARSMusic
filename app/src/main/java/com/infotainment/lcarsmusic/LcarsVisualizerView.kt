package com.infotainment.lcarsmusic

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.roundToInt

class LcarsVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    @Volatile
    private var visualizer: Visualizer? = null
    private var waveformData: ByteArray? = null
    private var fftData: ByteArray? = null
    @Volatile
    private var isPaused = false
    @Volatile
    private var isPlaybackActive = false
    @Volatile
    private var isDecayingToZero = false
    @Volatile
    private var isResting = false
    private var decayAnimator: ValueAnimator? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var audioHandler: Handler? = null
    @Volatile
    private var requestedSessionId = 0
    private var captureFrameCount = 0

    @Volatile
    var activeSessionId: Int = 0
        private set

    private val paint = Paint().apply {
        color = 0xFF04AA6D.toInt() // LCARS Greenish color
        style = Paint.Style.FILL
        strokeWidth = 4f
    }

    enum class Mode {
        FREQUENCY_DOMAIN, // FFT
        TIME_DOMAIN       // Waveform
    }

    var mode: Mode = Mode.FREQUENCY_DOMAIN
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    fun setAudioHandler(handler: Handler) {
        audioHandler = handler
    }

    fun setAudioSessionId(sessionId: Int, forceRebuild: Boolean = false) {
        Log.d(TAG, "VIEW_SET_AUDIO_SESSION_REQUEST sessionId=$sessionId paused=$isPaused forceRebuild=$forceRebuild")
        if (!forceRebuild && sessionId == activeSessionId && visualizer != null) {
            Log.d(TAG, "VIEW_SET_AUDIO_SESSION_SKIPPED_ALREADY_BOUND sessionId=$sessionId")
            return
        }

        requestedSessionId = sessionId

        if (sessionId <= 0) {
            Log.d(TAG, "VIEW_SET_AUDIO_SESSION_SKIPPED_INVALID sessionId=$sessionId")
            destroyVisualizerAsync(clearSession = true)
            return
        }

        if (!hasRecordAudioPermission()) {
            Log.d(TAG, "VIEW_SET_AUDIO_SESSION_SKIPPED_MISSING_RECORD_AUDIO sessionId=$sessionId")
            destroyVisualizerAsync(clearSession = false)
            return
        }

        if (isPaused) {
            Log.d(TAG, "VIEW_SET_AUDIO_SESSION_DEFERRED_WHILE_PAUSED sessionId=$sessionId")
            destroyVisualizerAsync(clearSession = false)
            return
        }

        bindVisualizerAsync(sessionId, forceRebuild)
    }

    fun pause() {
        isPaused = true
        Log.d(TAG, "VIEW_PAUSE_RELEASE_VISUALIZER sessionId=$activeSessionId")
        destroyVisualizerAsync(clearSession = false)
    }

    fun resume() {
        isPaused = false
        if (isPlaybackActive) {
            cancelDecayAnimation()
        }
        Log.d(TAG, "VIEW_RESUME_VISUALIZER sessionId=$requestedSessionId")

        if (requestedSessionId > 0 && hasRecordAudioPermission()) {
            bindVisualizerAsync(requestedSessionId, forceRebuild = false)
        } else {
            Log.d(
                TAG,
                "VIEW_RESUME_SKIPPED sessionId=$requestedSessionId hasPermission=${hasRecordAudioPermission()}"
            )
        }
    }

    fun release() {
        Log.d(TAG, "VIEW_RELEASE_VISUALIZER sessionId=$activeSessionId")
        cancelDecayAnimation()
        requestedSessionId = 0
        destroyVisualizerAsync(clearSession = true)
    }

    fun onPlaybackResumed() {
        isPlaybackActive = true
        isResting = false
        cancelDecayAnimation()
        postAudioEffectWork {
            runCatching {
                visualizer?.enabled = true
            }.onFailure { error ->
                Log.d(TAG, "VISUALIZER_ENABLE_ON_RESUME_FAILED sessionId=$activeSessionId", error)
            }
        }
    }

    fun onPlaybackPaused() {
        isPlaybackActive = false
        postAudioEffectWork {
            runCatching {
                visualizer?.enabled = false
            }.onFailure { error ->
                Log.d(TAG, "VISUALIZER_DISABLE_ON_PAUSE_FAILED sessionId=$activeSessionId", error)
            }
        }
        animateToZero()
    }

    fun animateToZero() {
        if (isDecayingToZero) return

        isDecayingToZero = true

        if (fftData == null && waveformData == null) {
            isDecayingToZero = false
            return
        }

        val baselineFftData = fftData?.clone()
        val baselineWaveformData = waveformData?.clone()
        var wasCanceled = false

        decayAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = DECAY_ANIMATION_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val multiplier = animator.animatedValue as Float
                applyFftDecayFrame(baselineFftData, multiplier)
                applyWaveformDecayFrame(baselineWaveformData, multiplier)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    wasCanceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!wasCanceled) {
                        fftData?.fill(0)
                        waveformData?.fill(WAVEFORM_CENTER.toByte())
                        isResting = true
                        invalidate()
                    }

                    isDecayingToZero = false
                    if (decayAnimator == animation) {
                        decayAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun bindVisualizerAsync(sessionId: Int, forceRebuild: Boolean) {
        postAudioEffectWork {
            bindVisualizerOnAudioThread(sessionId, forceRebuild)
        }
    }

    private fun bindVisualizerOnAudioThread(sessionId: Int, forceRebuild: Boolean) {
        if (sessionId != requestedSessionId) {
            Log.d(
                TAG,
                "VISUALIZER_INITIALIZATION_SKIPPED_STALE sessionId=$sessionId requested=$requestedSessionId"
            )
            return
        }
        if (!forceRebuild && sessionId == activeSessionId && visualizer != null) {
            Log.d(TAG, "VISUALIZER_REBIND_SKIPPED_ALREADY_BOUND sessionId=$sessionId")
            return
        }

        Log.d(TAG, "VISUALIZER_INITIALIZATION_START sessionId=$sessionId")
        destroyVisualizer(clearSession = false)
        Log.d(TAG, "Destroyed old visualizer, waiting 50ms before rebuilding native session...")
        audioHandler?.postDelayed({
            createVisualizerAfterReleaseDelay(sessionId, forceRebuild)
        }, AUDIO_DRIVER_RELEASE_DELAY_MS)
    }

    private fun createVisualizerAfterReleaseDelay(sessionId: Int, forceRebuild: Boolean) {
        if (sessionId != requestedSessionId) {
            Log.d(
                TAG,
                "VISUALIZER_DELAYED_INITIALIZATION_SKIPPED_STALE sessionId=$sessionId requested=$requestedSessionId"
            )
            return
        }
        if (!forceRebuild && sessionId == activeSessionId && visualizer != null) {
            Log.d(TAG, "VISUALIZER_DELAYED_REBIND_SKIPPED_ALREADY_BOUND sessionId=$sessionId")
            return
        }

        var pendingVisualizer: Visualizer? = null
        try {
            val maxCaptureRate = Visualizer.getMaxCaptureRate()
            val actualCaptureRate = minOf(REQUESTED_CAPTURE_RATE_MHZ, maxCaptureRate)
            val captureSize = getSafeCaptureSize()

            Log.d(
                TAG,
                "VISUALIZER_CAPTURE_CONFIG sessionId=$sessionId requestedRate=$REQUESTED_CAPTURE_RATE_MHZ actualRate=$actualCaptureRate maxRate=$maxCaptureRate captureSize=$captureSize"
            )

            val nativeVisualizer = Visualizer(sessionId)
            pendingVisualizer = nativeVisualizer
            nativeVisualizer.captureSize = captureSize
            nativeVisualizer.setDataCaptureListener(
                createDataCaptureListener(),
                actualCaptureRate,
                true,
                true
            )
            if (sessionId != requestedSessionId) {
                destroyUninstalledVisualizer(nativeVisualizer)
                Log.d(
                    TAG,
                    "VISUALIZER_INITIALIZATION_DISCARDED_STALE sessionId=$sessionId requested=$requestedSessionId"
                )
                return
            }
            nativeVisualizer.enabled = isPlaybackActive && !isPaused
            visualizer = nativeVisualizer
            pendingVisualizer = null
            activeSessionId = sessionId
            isResting = false
            isDecayingToZero = false

            Log.d(TAG, "Visualizer re-bound to session: $sessionId")
            Log.d(TAG, "Successfully bound new session and forced awake.")
            Log.d(TAG, "VISUALIZER_INITIALIZATION_SUCCESS sessionId=$sessionId enabled=${nativeVisualizer.enabled}")
        } catch (e: IllegalStateException) {
            Log.d(TAG, "VISUALIZER_INITIALIZATION_FAILED_ILLEGAL_STATE sessionId=$sessionId", e)
            pendingVisualizer?.let { destroyUninstalledVisualizer(it) }
            markVisualizerBindFailed()
        } catch (e: RuntimeException) {
            Log.d(TAG, "VISUALIZER_INITIALIZATION_FAILED_RUNTIME sessionId=$sessionId", e)
            pendingVisualizer?.let { destroyUninstalledVisualizer(it) }
            markVisualizerBindFailed()
        } catch (e: Exception) {
            Log.d(TAG, "VISUALIZER_INITIALIZATION_FAILED_EXCEPTION sessionId=$sessionId", e)
            pendingVisualizer?.let { destroyUninstalledVisualizer(it) }
            markVisualizerBindFailed()
        }
    }

    private fun createDataCaptureListener(): Visualizer.OnDataCaptureListener {
        return object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(
                v: Visualizer?,
                waveform: ByteArray?,
                samplingRate: Int
            ) {
                if (isDecayingToZero || isResting) return
                logCaptureFrame("WAVEFORM", waveform, samplingRate)

                if (waveform != null && waveform.isNotEmpty()) {
                    val capturedWaveform = waveform.clone()
                    uiHandler.post {
                        if (mode == Mode.TIME_DOMAIN && !isDecayingToZero && !isResting) {
                            cancelDecayAnimation()
                            waveformData = capturedWaveform
                            postInvalidateOnAnimation()
                        }
                    }
                }
            }

            override fun onFftDataCapture(
                v: Visualizer?,
                fft: ByteArray?,
                samplingRate: Int
            ) {
                if (isDecayingToZero || isResting) return
                logCaptureFrame("FFT", fft, samplingRate)

                if (fft != null && fft.isNotEmpty()) {
                    val capturedFft = fft.clone()
                    uiHandler.post {
                        if (mode == Mode.FREQUENCY_DOMAIN && !isDecayingToZero && !isResting) {
                            cancelDecayAnimation()
                            fftData = capturedFft
                            postInvalidateOnAnimation()
                        }
                    }
                }
            }
        }
    }

    private fun destroyVisualizerAsync(clearSession: Boolean) {
        postAudioEffectWork {
            destroyVisualizer(clearSession)
        }
    }

    private fun destroyVisualizer(clearSession: Boolean = false) {
        val existingVisualizer = visualizer
        visualizer = null

        if (existingVisualizer != null) {
            try {
                existingVisualizer.enabled = false
                existingVisualizer.setDataCaptureListener(null, 0, false, false)
                existingVisualizer.release()
                Log.d(TAG, "VISUALIZER_NATIVE_RELEASED sessionId=$activeSessionId clearSession=$clearSession")
            } catch (e: IllegalStateException) {
                Log.d(TAG, "VISUALIZER_RELEASE_FAILED_ILLEGAL_STATE sessionId=$activeSessionId", e)
            } catch (e: RuntimeException) {
                Log.d(TAG, "VISUALIZER_RELEASE_FAILED_RUNTIME sessionId=$activeSessionId", e)
            } catch (e: Exception) {
                Log.d(TAG, "VISUALIZER_RELEASE_FAILED_EXCEPTION sessionId=$activeSessionId", e)
            }
        }

        activeSessionId = 0
        if (clearSession) {
            requestedSessionId = 0
        }
    }

    private fun destroyUninstalledVisualizer(nativeVisualizer: Visualizer) {
        runCatching {
            nativeVisualizer.enabled = false
            nativeVisualizer.setDataCaptureListener(null, 0, false, false)
            nativeVisualizer.release()
        }.onFailure { error ->
            Log.d(TAG, "VISUALIZER_STALE_RELEASE_FAILED sessionId=$requestedSessionId", error)
        }
    }

    private fun logCaptureFrame(kind: String, data: ByteArray?, samplingRate: Int) {
        captureFrameCount += 1
        if (captureFrameCount % CAPTURE_LOG_FRAME_INTERVAL != 0) return

        Log.d(
            TAG,
            "Receiving capture data... type=$kind size=${data?.size ?: 0} sample=${sampleBytes(data)} samplingRate=$samplingRate"
        )
    }

    private fun markVisualizerBindFailed() {
        destroyVisualizer(clearSession = false)
        activeSessionId = -1
    }

    private fun postAudioEffectWork(block: () -> Unit) {
        val handler = audioHandler
        if (handler == null) {
            Log.w(TAG, "VISUALIZER_AUDIO_HANDLER_MISSING")
            return
        }
        handler.post(block)
    }

    private fun getSafeCaptureSize(): Int {
        return try {
            val range = Visualizer.getCaptureSizeRange()
            REQUESTED_CAPTURE_SIZE.coerceIn(range[0], range[1])
        } catch (e: RuntimeException) {
            Log.d(TAG, "VISUALIZER_CAPTURE_SIZE_RANGE_FAILED_USING_DEFAULT", e)
            REQUESTED_CAPTURE_SIZE
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "VIEW_PERMISSION_CHECK_RECORD_AUDIO granted=$granted")
        return granted
    }

    private fun sampleBytes(data: ByteArray?): String {
        if (data == null || data.isEmpty()) return "[]"
        return data.asList().take(SAMPLE_BYTE_COUNT).joinToString(
            prefix = "[",
            postfix = "]"
        ) { it.toString() }
    }

    override fun onDraw(canvas: Canvas) {
        if (isResting) return

        super.onDraw(canvas)
        when (mode) {
            Mode.FREQUENCY_DOMAIN -> drawFft(canvas)
            Mode.TIME_DOMAIN -> drawWaveform(canvas)
        }
    }

    private fun drawWaveform(canvas: Canvas) {
        val data = waveformData ?: return
        val width = width.toFloat()
        val height = (height - paddingBottom).coerceAtLeast(0).toFloat()
        val centerY = height / 2f
        val barWidth = width / data.size

        for (i in 0 until data.size - 1) {
            // Waveform data is unsigned 8-bit [0, 255], center is 128
            val value = (data[i].toInt() and 0xFF) - 128
            val barHeight = (value.toFloat() / 128f) * centerY
            
            val x = i * barWidth
            // Draw bidirectional bar
            canvas.drawRect(
                x,
                centerY - barHeight,
                x + barWidth - 1,
                centerY + barHeight,
                paint
            )
        }
    }

    private fun drawFft(canvas: Canvas) {
        val data = fftData ?: return
        val width = width.toFloat()
        val height = (height - paddingBottom).coerceAtLeast(0).toFloat()
        
        // FFT data format: [real0, imag0, real1, imag1, ...]
        // We calculate magnitude for each frequency bin
        val numBins = data.size / 2
        val barWidth = width / numBins

        for (i in 0 until numBins) {
            val r = data[i * 2].toInt()
            val im = data[i * 2 + 1].toInt()
            val magnitude = hypot(r.toDouble(), im.toDouble()).toFloat()
            
            // Normalize magnitude (empirical scaling)
            val barHeight = (magnitude / 128f) * height
            
            val x = i * barWidth
            // Draw unidirectional bar extending upward
            canvas.drawRect(
                x,
                height - barHeight,
                x + barWidth - 1,
                height,
                paint
            )
        }
    }

    private fun cancelDecayAnimation() {
        isResting = false
        isDecayingToZero = false
        decayAnimator?.cancel()
        decayAnimator = null
    }

    private fun applyFftDecayFrame(baselineData: ByteArray?, multiplier: Float) {
        val data = fftData ?: return
        val baseline = baselineData ?: return
        val frameSize = minOf(data.size, baseline.size)

        for (i in 0 until frameSize) {
            val baselineValue = baseline[i].toInt()
            val decayed = (baselineValue * multiplier).roundToInt()
            data[i] = decayed.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
        }
    }

    private fun applyWaveformDecayFrame(baselineData: ByteArray?, multiplier: Float) {
        val data = waveformData ?: return
        val baseline = baselineData ?: return
        val frameSize = minOf(data.size, baseline.size)

        for (i in 0 until frameSize) {
            val baselineSample = baseline[i].toInt() and 0xFF
            val centeredAmplitude = baselineSample - WAVEFORM_CENTER
            val decayedSample = WAVEFORM_CENTER + (centeredAmplitude * multiplier).roundToInt()
            data[i] = decayedSample.coerceIn(0, 255).toByte()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelDecayAnimation()
        release()
    }

    private companion object {
        const val TAG = MusicPlaybackService.VISUALIZER_LOG_TAG
        const val REQUESTED_CAPTURE_SIZE = 512
        const val REQUESTED_CAPTURE_RATE_MHZ = 30_000
        const val SAMPLE_BYTE_COUNT = 8
        const val DECAY_ANIMATION_DURATION_MS = 1000L
        const val AUDIO_DRIVER_RELEASE_DELAY_MS = 50L
        const val CAPTURE_LOG_FRAME_INTERVAL = 50
        const val WAVEFORM_CENTER = 128
    }
}
