package com.infotainment.lcarsmusic

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class LcarsVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var visualizer: Visualizer? = null
    private var waveformData: ByteArray? = null
    private var fftData: ByteArray? = null
    private var isPaused = false
    private var isDecayingToZero = false

    private val decayRunnable = object : Runnable {
        override fun run() {
            val fftStillActive = decayFftData()
            val waveformStillActive = decayWaveformData()

            postInvalidateOnAnimation()

            if (fftStillActive || waveformStillActive) {
                postOnAnimation(this)
            } else {
                isDecayingToZero = false
            }
        }
    }

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

    fun setAudioSessionId(sessionId: Int) {
        Log.d(TAG, "VIEW_SET_AUDIO_SESSION_REQUEST sessionId=$sessionId paused=$isPaused")
        activeSessionId = sessionId

        if (sessionId <= 0) {
            Log.d(TAG, "VIEW_SET_AUDIO_SESSION_SKIPPED_INVALID sessionId=$sessionId")
            releaseVisualizer(clearSession = true)
            return
        }

        if (!hasRecordAudioPermission()) {
            Log.d(TAG, "VIEW_SET_AUDIO_SESSION_SKIPPED_MISSING_RECORD_AUDIO sessionId=$sessionId")
            releaseVisualizer(clearSession = false)
            return
        }

        if (isPaused) {
            Log.d(TAG, "VIEW_SET_AUDIO_SESSION_DEFERRED_WHILE_PAUSED sessionId=$sessionId")
            releaseVisualizer(clearSession = false)
            return
        }

        initializeVisualizer(sessionId)
    }

    fun pause() {
        isPaused = true
        Log.d(TAG, "VIEW_PAUSE_RELEASE_VISUALIZER sessionId=$activeSessionId")
        releaseVisualizer(clearSession = false)
    }

    fun resume() {
        isPaused = false
        cancelDecayAnimation()
        Log.d(TAG, "VIEW_RESUME_VISUALIZER sessionId=$activeSessionId")

        if (activeSessionId > 0 && hasRecordAudioPermission()) {
            initializeVisualizer(activeSessionId)
        } else {
            Log.d(
                TAG,
                "VIEW_RESUME_SKIPPED sessionId=$activeSessionId hasPermission=${hasRecordAudioPermission()}"
            )
        }
    }

    fun release() {
        Log.d(TAG, "VIEW_RELEASE_VISUALIZER sessionId=$activeSessionId")
        cancelDecayAnimation()
        releaseVisualizer(clearSession = true)
    }

    fun animateToZero() {
        if (isDecayingToZero) return
        if (fftData == null && waveformData == null) return

        isDecayingToZero = true
        removeCallbacks(decayRunnable)
        postOnAnimation(decayRunnable)
    }

    private fun initializeVisualizer(sessionId: Int) {
        Log.d(TAG, "VISUALIZER_INITIALIZATION_START sessionId=$sessionId")
        releaseVisualizer(clearSession = false)

        try {
            val maxCaptureRate = Visualizer.getMaxCaptureRate()
            val actualCaptureRate = minOf(REQUESTED_CAPTURE_RATE_MHZ, maxCaptureRate)
            val captureSize = getSafeCaptureSize()

            Log.d(
                TAG,
                "VISUALIZER_CAPTURE_CONFIG sessionId=$sessionId requestedRate=$REQUESTED_CAPTURE_RATE_MHZ actualRate=$actualCaptureRate maxRate=$maxCaptureRate captureSize=$captureSize"
            )

            val nativeVisualizer = Visualizer(sessionId)
            nativeVisualizer.captureSize = captureSize
            nativeVisualizer.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        Log.d(
                            TAG,
                            "WAVEFORM_CAPTURE size=${waveform?.size ?: 0} sample=${sampleBytes(waveform)} samplingRate=$samplingRate"
                        )
                        if (mode == Mode.TIME_DOMAIN && waveform != null && waveform.isNotEmpty()) {
                            cancelDecayAnimation()
                            waveformData = waveform
                            postInvalidateOnAnimation()
                        }
                    }

                    override fun onFftDataCapture(
                        v: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        Log.d(
                            TAG,
                            "FFT_CAPTURE size=${fft?.size ?: 0} sample=${sampleBytes(fft)} samplingRate=$samplingRate"
                        )
                        if (mode == Mode.FREQUENCY_DOMAIN && fft != null && fft.isNotEmpty()) {
                            cancelDecayAnimation()
                            fftData = fft
                            postInvalidateOnAnimation()
                        }
                    }
                },
                actualCaptureRate,
                true,
                true
            )
            nativeVisualizer.enabled = true
            visualizer = nativeVisualizer

            Log.d(TAG, "VISUALIZER_INITIALIZATION_SUCCESS sessionId=$sessionId enabled=${nativeVisualizer.enabled}")
        } catch (e: IllegalStateException) {
            Log.d(TAG, "VISUALIZER_INITIALIZATION_FAILED_ILLEGAL_STATE sessionId=$sessionId", e)
            releaseVisualizer(clearSession = false)
        } catch (e: RuntimeException) {
            Log.d(TAG, "VISUALIZER_INITIALIZATION_FAILED_RUNTIME sessionId=$sessionId", e)
            releaseVisualizer(clearSession = false)
        } catch (e: Exception) {
            Log.d(TAG, "VISUALIZER_INITIALIZATION_FAILED_EXCEPTION sessionId=$sessionId", e)
            releaseVisualizer(clearSession = false)
        }
    }

    private fun releaseVisualizer(clearSession: Boolean) {
        val existingVisualizer = visualizer
        visualizer = null

        if (existingVisualizer != null) {
            try {
                existingVisualizer.enabled = false
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

        if (clearSession) {
            activeSessionId = 0
        }
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

    private fun decayFftData(): Boolean {
        val data = fftData ?: return false
        var stillActive = false

        for (i in data.indices) {
            val value = data[i].toInt()
            val decayed = (value * DECAY_FACTOR).roundToInt()

            if (abs(decayed) > DECAY_STOP_THRESHOLD) {
                stillActive = true
                data[i] = decayed.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
            } else {
                data[i] = 0
            }
        }

        return stillActive
    }

    private fun decayWaveformData(): Boolean {
        val data = waveformData ?: return false
        var stillActive = false

        for (i in data.indices) {
            val unsignedSample = data[i].toInt() and 0xFF
            val centeredAmplitude = unsignedSample - WAVEFORM_CENTER
            val decayedAmplitude = (centeredAmplitude * DECAY_FACTOR).roundToInt()

            if (abs(decayedAmplitude) > DECAY_STOP_THRESHOLD) {
                stillActive = true
                data[i] = (WAVEFORM_CENTER + decayedAmplitude).coerceIn(0, 255).toByte()
            } else {
                data[i] = WAVEFORM_CENTER.toByte()
            }
        }

        return stillActive
    }

    private fun cancelDecayAnimation() {
        isDecayingToZero = false
        removeCallbacks(decayRunnable)
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
        const val DECAY_FACTOR = 0.85f
        const val DECAY_STOP_THRESHOLD = 1
        const val WAVEFORM_CENTER = 128
    }
}
