package com.infotainment.lcarsmusic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.media.audiofx.Visualizer
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot

class LcarsVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var visualizer: Visualizer? = null
    private var waveformData: ByteArray? = null
    private var fftData: ByteArray? = null

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
            invalidate()
        }

    fun setAudioSessionId(sessionId: Int) {
        if (visualizer != null) {
            visualizer?.release()
        }

        try {
            visualizer = Visualizer(sessionId).apply {
                captureSize = 512
                setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        v: Visualizer?,
                        waveform: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (mode == Mode.TIME_DOMAIN) {
                            waveformData = waveform
                            invalidate()
                        }
                    }

                    override fun onFftDataCapture(
                        v: Visualizer?,
                        fft: ByteArray?,
                        samplingRate: Int
                    ) {
                        if (mode == Mode.FREQUENCY_DOMAIN) {
                            fftData = fft
                            invalidate()
                        }
                    }
                }, 30000, true, true) // 30,000 mHz = 30 fps
                enabled = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pause() {
        visualizer?.enabled = false
    }

    fun resume() {
        visualizer?.enabled = true
    }

    fun release() {
        visualizer?.release()
        visualizer = null
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
        val height = height.toFloat()
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
        val height = height.toFloat()
        
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

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        release()
    }
}
