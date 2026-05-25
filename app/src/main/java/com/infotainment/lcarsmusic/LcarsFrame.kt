package com.infotainment.lcarsmusic

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
import kotlin.random.Random

class LcarsFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val contentContainer: FrameLayout
    private var isInitialized = false

    // Palettes expanded with lighter/darker shades for variety
    private val leftPalette = listOf(
        Color.parseColor("#FF0000"), // Red
        Color.parseColor("#FF6666"), // Light Red
        Color.parseColor("#8B0000"), // Dark Red
        Color.parseColor("#0000FF"), // Blue
        Color.parseColor("#ADD8E6"), // Light Blue
        Color.parseColor("#00008B"), // Dark Blue
        Color.parseColor("#FFFFFF")  // White
    )

    private val rightPalette = listOf(
        Color.parseColor("#0000FF"), // Blue
        Color.parseColor("#ADD8E6"), // Light Blue
        Color.parseColor("#00008B"), // Dark Blue
        Color.parseColor("#8A2BE2"), // Violet
        Color.parseColor("#D8BFD8"), // Thistle (Light Violet)
        Color.parseColor("#4B0082"), // Indigo
        Color.parseColor("#FFA500"), // Orange
        Color.parseColor("#FFD580")  // Light Orange
    )

    init {
        LayoutInflater.from(context).inflate(R.layout.view_lcars_frame, this, true)
        contentContainer = findViewById(R.id.content_container)
            ?: throw IllegalStateException("view_lcars_frame.xml must contain content_container")

        isInitialized = true
        startColorAnimation()
    }

    private fun startColorAnimation() {
        val leftCol = findViewById<LinearLayout>(R.id.left_column)
        val rightCol = findViewById<LinearLayout>(R.id.right_column)

        if (leftCol != null) animateChildren(leftCol, leftPalette)
        if (rightCol != null) animateChildren(rightCol, rightPalette)
    }

    private fun animateChildren(parent: ViewGroup, palette: List<Int>) {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)

            // Randomly pick start and end colors from the expanded palette
            val startColor = palette[Random.nextInt(palette.size)]
            val endColor = palette[Random.nextInt(palette.size)]

            child.setBackgroundColor(startColor)

            val animator = ObjectAnimator.ofInt(child, "backgroundColor", startColor, endColor)
            animator.setEvaluator(ArgbEvaluator())
            animator.duration = 2500 // Updated to 2500ms
            animator.repeatCount = ValueAnimator.INFINITE
            animator.repeatMode = ValueAnimator.REVERSE

            // Staggered effect
            animator.startDelay = (i * 400).toLong()
            animator.start()
        }
    }

    override fun addView(child: View, index: Int, params: ViewGroup.LayoutParams?) {
        if (!isInitialized) {
            super.addView(child, index, params)
            return
        }

        val isDecorative = child.id == R.id.corner_tl ||
                child.id == R.id.corner_tr ||
                child.id == R.id.corner_bl ||
                child.id == R.id.corner_br ||
                child.id == R.id.left_column ||
                child.id == R.id.right_column ||
                child.id == R.id.content_container

        if (isDecorative) {
            super.addView(child, index, params)
        } else {
            val lp = if (params is FrameLayout.LayoutParams) {
                params
            } else {
                FrameLayout.LayoutParams(
                    params?.width ?: FrameLayout.LayoutParams.WRAP_CONTENT,
                    params?.height ?: FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }
            lp.gravity = Gravity.CENTER
            contentContainer.addView(child, index, lp)
        }
    }
}