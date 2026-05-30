package com.infotainment.lcarsmusic

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatSeekBar

class LCARSMediaSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.seekBarStyle
) : AppCompatSeekBar(context, attrs, defStyleAttr) {

    // Store the height as a class property so we can use it during onMeasure
    private var customBarHeight: Int = 0

    init {
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.LCARSMediaSeekBar)

        val defaultSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 22.5f, resources.displayMetrics
        ).toInt()

        val fillColor = typedArray.getColor(R.styleable.LCARSMediaSeekBar_lcarsFillColor, Color.BLACK)
        val trackColor = typedArray.getColor(R.styleable.LCARSMediaSeekBar_lcarsTrackColor, Color.LTGRAY)
        val thumbColor = typedArray.getColor(R.styleable.LCARSMediaSeekBar_lcarsThumbColor, Color.WHITE)
        val thumbBorderColor = typedArray.getColor(R.styleable.LCARSMediaSeekBar_lcarsThumbBorderColor, Color.BLACK)

        // Assign to the class-level variable
        customBarHeight = typedArray.getDimensionPixelSize(R.styleable.LCARSMediaSeekBar_lcarsBarHeight, defaultSize)
        val thumbWidth = typedArray.getDimensionPixelSize(R.styleable.LCARSMediaSeekBar_lcarsThumbWidth, defaultSize)

        typedArray.recycle()

        // 1. Build the Square Thumb
        val thumbDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(thumbColor)
            setStroke(3, thumbBorderColor)
            setSize(thumbWidth, customBarHeight)
        }
        this.thumb = thumbDrawable

        // 2. Build the Background Track
        val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(trackColor)
        }

        // 3. Build the Progress Fill
        val fillDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
        }
        val clipProgress = ClipDrawable(fillDrawable, Gravity.START, ClipDrawable.HORIZONTAL)

        // 4. Assemble the LayerDrawable
        val layerDrawable = LayerDrawable(arrayOf(backgroundDrawable, clipProgress))
        layerDrawable.setId(0, android.R.id.background)
        layerDrawable.setId(1, android.R.id.progress)

        this.progressDrawable = layerDrawable

        // 5. Force layout behaviors (API 29 min/max setters removed!)
        this.splitTrack = false
        this.setPadding(0, 0, 0, 0)
        this.thumbOffset = 0
    }

    // 6. Force the exact height programmatically on all API levels
    @Synchronized
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Override whatever Android thinks the height should be, and lock it to customBarHeight
        setMeasuredDimension(measuredWidth, customBarHeight)
    }
}