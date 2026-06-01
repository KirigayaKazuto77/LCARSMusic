package com.infotainment.lcarsmusic

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
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

    private val lcarsPalette = listOf(
        Color.parseColor("#FF6753"),
        Color.parseColor("#FF977B"),
        Color.parseColor("#DD7700"),
        Color.parseColor("#CC5500"),
        Color.parseColor("#FF9911"),
        Color.parseColor("#FFEECC"),
        Color.parseColor("#CCCC66"),
        Color.parseColor("#FFCC33"),
        Color.parseColor("#FFAA00"),
        Color.parseColor("#CC99FF"),
        Color.parseColor("#5566FF"),
        Color.parseColor("#8899FF"),
        Color.parseColor("#99CCFF"),
        Color.parseColor("#9966FF"),
        Color.parseColor("#AAAAFF"),
        Color.parseColor("#DDBBFF"),
        Color.parseColor("#F5F6FA"),
        Color.parseColor("#6699FF"),
        Color.parseColor("#2266FF"),
        Color.parseColor("#52526A"),
        Color.parseColor("#88BBFF"),
        Color.parseColor("#9966CC"),
        Color.parseColor("#FFCC99"),
        Color.parseColor("#99AA22"),
        Color.parseColor("#99CC33"),
        Color.parseColor("#2233FF"),
        Color.parseColor("#EBF0FF"),
        Color.parseColor("#FF7744"),
        Color.parseColor("#CC6666"),
        Color.parseColor("#FF8833"),
        Color.parseColor("#999933"),
        Color.parseColor("#CC55FF"),
        Color.parseColor("#FFAA44"),
        Color.parseColor("#88FFFF")
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

        // Passing each layout independently ensures color uniqueness is isolated per column
        if (leftCol != null) animateChildren(leftCol)
        if (rightCol != null) animateChildren(rightCol)
    }

    private fun animateChildren(parent: ViewGroup) {
        // Keeps track of assigned/targeted colors within THIS column to prevent collisions
        val activeColors = mutableSetOf<Int>()

        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)

            // Step 1: Assign a unique baseline color to start with
            var currentStartColor = pickUniqueColor(activeColors)
            activeColors.add(currentStartColor)
            child.setBackgroundColor(currentStartColor)

            // Step 2: Define a looping transition routine
            fun triggerNextFade(isFirstRun: Boolean) {
                // Pick a unique destination color and reserve it
                val currentEndColor = pickUniqueColor(activeColors)
                activeColors.add(currentEndColor)

                val animator = ObjectAnimator.ofInt(child, "backgroundColor", currentStartColor, currentEndColor)
                animator.setEvaluator(ArgbEvaluator())
                animator.duration = 1500

                // Apply the layout's staggered start delay only on boot-up
                if (isFirstRun) {
                    animator.startDelay = (i * 400).toLong()
                }

                animator.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // Animation complete: Free up the old color asset, swap references, and cycle
                        activeColors.remove(currentStartColor)
                        currentStartColor = currentEndColor
                        triggerNextFade(false)
                    }
                })
                animator.start()
            }

            // Fire engine cycle
            triggerNextFade(true)
        }
    }

    private fun pickUniqueColor(activeColors: Set<Int>): Int {
        // Filter out colors that are actively occupying structural units in the column
        val availableColors = lcarsPalette.filter { it !in activeColors }

        return if (availableColors.isNotEmpty()) {
            availableColors[Random.nextInt(availableColors.size)]
        } else {
            // Safety fallback in case column elements exceed the palette size
            lcarsPalette[Random.nextInt(lcarsPalette.size)]
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

    private var frameAnimator: AnimatorSet? = null

    /**
     * Triggered every time the "Now Playing" view is opened.
     * Starts fully closed, slides open, and fades the art in.
     */
    fun runEntranceSequence() {
        post {
            frameAnimator?.cancel()

            val leftSideViews = listOfNotNull(
                findViewById<View>(R.id.corner_tl),
                findViewById<View>(R.id.left_column),
                findViewById<View>(R.id.corner_bl)
            )

            // Calculate the rigid start position
            val leftCorner = findViewById<View>(R.id.corner_tl)
            val rightCorner = findViewById<View>(R.id.corner_tr)
            val rigidSlideDistance = (width - (leftCorner?.width ?: 0) - (rightCorner?.width ?: 0)).toFloat()

            // 1. Force Initial "Closed" State using the rigid distance
            leftSideViews.forEach { it.translationX = rigidSlideDistance }
            contentContainer.alpha = 0f

            // 2. Slide Left (Open Door)
            val openAnimations = leftSideViews.map { view ->
                ObjectAnimator.ofFloat(view, "translationX", rigidSlideDistance, 0f).setDuration(500)
            }
            val openSet = AnimatorSet().apply { playTogether(openAnimations) }

            // 3. Fade In Content
            val fadeArt = ObjectAnimator.ofFloat(contentContainer, "alpha", 0f, 1f).setDuration(500)

            frameAnimator = AnimatorSet().apply {
                playSequentially(openSet, fadeArt)
                start()
            }
        }
    }

    /**
     * Triggered on Track Change (Prev/Next).
     * Fades out -> Closes -> Opens -> Fades in.
     * @param updateMediaArt Callback executed precisely when the art is hidden.
     */
    fun runTrackChangeSequence(updateMediaArt: () -> Unit) {
        post {
            frameAnimator?.cancel()

            val leftSideViews = listOfNotNull(
                findViewById<View>(R.id.corner_tl),
                findViewById<View>(R.id.left_column),
                findViewById<View>(R.id.corner_bl)
            )

            // 1. Fade Out Current Art
            val fadeOut = ObjectAnimator.ofFloat(contentContainer, "alpha", contentContainer.alpha, 0f).apply {
                duration = 500
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        updateMediaArt()
                    }
                })
            }

            // 2. Slide Right (Close Door) AS A RIGID UNIT
            // We calculate a single distance based on the widest points (the corners)
            // so the entire door stops exactly when the corners touch.
            val leftCorner = findViewById<View>(R.id.corner_tl)
            val rightCorner = findViewById<View>(R.id.corner_tr)

            val rigidSlideDistance = (width - (leftCorner?.width ?: 0) - (rightCorner?.width ?: 0)).toFloat()

            val closeAnimations = leftSideViews.map { view ->
                // Every single left view receives the exact same rigidSlideDistance
                ObjectAnimator.ofFloat(view, "translationX", view.translationX, rigidSlideDistance).setDuration(500)
            }
            val closeSet = AnimatorSet().apply { playTogether(closeAnimations) }

            // 3. Slide Left (Open Door) - Return smoothly to origin 0f
            val openAnimations = leftSideViews.map { view ->
                ObjectAnimator.ofFloat(view, "translationX", 0f).setDuration(500)
            }
            val openSet = AnimatorSet().apply { playTogether(openAnimations) }

            // 4. Fade In New Art
            val fadeIn = ObjectAnimator.ofFloat(contentContainer, "alpha", 0f, 1f).setDuration(500)

            // Execute the chain sequentially
            frameAnimator = AnimatorSet().apply {
                playSequentially(fadeOut, closeSet, openSet, fadeIn)
                start()
            }
        }
    }
}