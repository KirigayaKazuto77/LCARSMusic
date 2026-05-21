package com.infotainment.lcarsmusic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * SegmentedSeekBar
 *
 * A vertical seek bar with a segmented track, designed for equalizer UIs.
 * All drawing is done on Canvas — no drawable files are required.
 *
 * The top and bottom segments are decorative rounded caps and are always
 * drawn in [inactiveColor]. Progress (0f–100f) only fills the middle segments.
 *
 * Progress : 0f (bottom middle) → 100f (top middle)
 *
 * ── XML attributes ────────────────────────────────────────────────────────
 *   ssb_progress        float    Initial progress value.       Default: 50
 *   ssb_segmentCount    integer  Total segments incl. 2 caps.  Default: 12
 *                                Must be >= 3.
 *   ssb_activeColor     color    Filled-portion color.         Default: #04AA6D
 *   ssb_inactiveColor   color    Unfilled-portion color.       Default: #1A3D2A
 *   ssb_thumbColor      color    Thumb indicator line color.   Default: #FFFFFF
 */
class SegmentedSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called whenever the progress value changes. */
    var onProgressChangedListener: ((progress: Float, fromUser: Boolean) -> Unit)? = null

    /** Current progress in the range [0f, 100f]. Setting this triggers a redraw. */
    var progress: Float = 50f
        set(value) {
            val clamped = value.coerceIn(0f, 100f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    /**
     * Total number of segments including the two decorative end caps.
     * Active (progress-tracking) segments = segmentCount - 2.
     * Must be >= 3.
     */
    var segmentCount: Int = 12
        set(value) {
            field = value.coerceAtLeast(3)
            invalidate()
        }

    /** Color of the filled (active) portion of the middle segments. */
    var activeColor: Int = Color.parseColor("#04AA6D")
        set(value) {
            field = value
            activePaint.color = value
            invalidate()
        }

    /** Color of the unfilled portions and the two decorative end caps. */
    var inactiveColor: Int = Color.parseColor("#1A3D2A")
        set(value) {
            field = value
            inactivePaint.color = value
            invalidate()
        }

    /** Color of the horizontal thumb line. */
    var thumbColor: Int = Color.WHITE
        set(value) {
            field = value
            thumbPaint.color = value
            invalidate()
        }

    // ── Fixed dimensions (dp) ─────────────────────────────────────────────────

    private val GAP_DP       = 5f   // vertical gap between each segment
    private val CORNER_DP    = 20f  // corner radius on the top/bottom cap segments
    private val THUMB_H_DP   = 8f   // thickness of the thumb indicator line
    private val THUMB_COR_DP = 4f   // corner radius of the thumb indicator line

    private val density get() = resources.displayMetrics.density

    // ── Paints ────────────────────────────────────────────────────────────────

    private val activePaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#04AA6D") }
    private val inactivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A3D2A") }
    private val thumbPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // ── Reusable drawing objects (never allocated inside onDraw) ──────────────

    private val segRect   = RectF()
    private val thumbRect = RectF()
    private val path      = Path()
    private val radii     = FloatArray(8)

    // ── Init: read XML attributes ─────────────────────────────────────────────

    init {
        context.theme.obtainStyledAttributes(
            attrs, R.styleable.SegmentedSeekBar, defStyleAttr, 0
        ).apply {
            try {
                progress      = getFloat(R.styleable.SegmentedSeekBar_ssb_progress,      50f)
                segmentCount  = getInteger(R.styleable.SegmentedSeekBar_ssb_segmentCount, 12)
                activeColor   = getColor(R.styleable.SegmentedSeekBar_ssb_activeColor,    Color.parseColor("#04AA6D"))
                inactiveColor = getColor(R.styleable.SegmentedSeekBar_ssb_inactiveColor,  Color.parseColor("#1A3D2A"))
                thumbColor    = getColor(R.styleable.SegmentedSeekBar_ssb_thumbColor,     Color.WHITE)
            } finally {
                recycle()
            }
        }
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w           = width.toFloat()
        val h           = height.toFloat()
        val gap         = GAP_DP       * density
        val corner      = CORNER_DP    * density
        val thumbH      = THUMB_H_DP   * density
        val thumbCorner = THUMB_COR_DP * density

        // Height of each segment after subtracting all gap space
        val segH = (h - gap * (segmentCount - 1)) / segmentCount

        // ── Compute Y bounds of the middle-segment zone ───────────────────────
        //
        //   Segment 0          → top decorative cap    (always inactive)
        //   Segments 1 .. n-2  → middle progress zone  (active / inactive)
        //   Segment n-1        → bottom decorative cap (always inactive)
        //
        //   middleTopY    = top edge of segment 1   → where progress=100 maps
        //   middleBottomY = bottom edge of segment n-2 → where progress=0 maps

        val middleTopY    = segH + gap
        val middleBottomY = (segmentCount - 2) * (segH + gap) + segH

        // Map progress (0–100) to a Y coordinate within the middle zone.
        //   progress=100 → progressY=middleTopY    (fully filled)
        //   progress=0   → progressY=middleBottomY (empty)
        val progressY = middleTopY + (1f - progress / 100f) * (middleBottomY - middleTopY)

        // ── Draw each segment ─────────────────────────────────────────────────

        for (i in 0 until segmentCount) {
            val top    = i * (segH + gap)
            val bottom = top + segH

            // Rounded caps only on the very first and very last segment
            val topCorner = if (i == 0)                corner else 0f
            val botCorner = if (i == segmentCount - 1) corner else 0f

            radii[0] = topCorner; radii[1] = topCorner   // top-left
            radii[2] = topCorner; radii[3] = topCorner   // top-right
            radii[4] = botCorner; radii[5] = botCorner   // bottom-right
            radii[6] = botCorner; radii[7] = botCorner   // bottom-left

            segRect.set(0f, top, w, bottom)
            path.reset()
            path.addRoundRect(segRect, radii, Path.Direction.CW)

            when {
                // Decorative end caps → always inactive, never show progress
                i == 0 || i == segmentCount - 1 -> {
                    canvas.drawPath(path, inactivePaint)
                }

                // Middle segment fully above the progress line → inactive
                bottom <= progressY -> {
                    canvas.drawPath(path, inactivePaint)
                }

                // Middle segment fully below the progress line → active
                top >= progressY -> {
                    canvas.drawPath(path, activePaint)
                }

                // Middle segment straddles the line → split at pixel accuracy
                else -> {
                    canvas.drawPath(path, inactivePaint)       // full segment inactive
                    canvas.save()
                    canvas.clipRect(0f, progressY, w, bottom)  // clip to active zone
                    canvas.drawPath(path, activePaint)          // overdraw active portion
                    canvas.restore()
                }
            }
        }

        // ── Thumb: horizontal bar pinned to progressY ─────────────────────────
        thumbRect.set(0f, progressY - thumbH / 2f, w, progressY + thumbH / 2f)
        canvas.drawRoundRect(thumbRect, thumbCorner, thumbCorner, thumbPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val h    = height.toFloat()
                val gap  = GAP_DP * density
                val segH = (h - gap * (segmentCount - 1)) / segmentCount

                // Same middle-zone bounds as onDraw
                val middleTopY    = segH + gap
                val middleBottomY = (segmentCount - 2) * (segH + gap) + segH

                // Invert the Y→progress mapping, clamped to [0, 100]
                val newProgress = ((middleBottomY - event.y) /
                        (middleBottomY - middleTopY) * 100f).coerceIn(0f, 100f)

                if (newProgress != progress) {
                    progress = newProgress
                    onProgressChangedListener?.invoke(progress, true)
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // Required by accessibility lint when overriding onTouchEvent
    override fun performClick(): Boolean = super.performClick()
}