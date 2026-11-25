/*
 * Copyright (C) 2026 Paranoid Android
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package co.aospa.sense.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.provider.Settings
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import co.aospa.sense.R
import kotlin.math.abs
import kotlin.math.sin

/**
 * Face capture animation inspired by the Face ID scan indicator and HyperOS face rings.
 *
 * The view is deliberately drawn with plain [Canvas] primitives so it works with the
 * platform-only build (no Lottie/Material dependency) and follows the system accent color.
 * The looping animation only runs while the window is visible and is disabled completely
 * when the user has turned off animator duration scale (reduced motion).
 */
class FaceUnlockAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private enum class State { IDLE, SCANNING, SUCCESS, ERROR }

    private val useFaceIdStyle = resources.getBoolean(R.bool.config_face_scan_style_ios)
    private val durationScale = Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    )

    private val accentColor = resolveThemeColor(
        android.R.attr.colorControlActivated,
        context.getColor(R.color.theme_accent_primary)
    )
    private val highlightColor = context.getColor(R.color.theme_accent_200)
    private val errorColor = context.getColor(R.color.face_enroll_vendor_color)
    private val successColor = context.getColor(R.color.face_scan_success)
    private val trackColor = alphaOf(accentColor, 45)
    private val scanColor = alphaOf(highlightColor, 180)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = trackColor
        strokeWidth = dp(3f)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentColor
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val cometPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = highlightColor
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentColor
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = alphaOf(highlightColor, 90)
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentColor
        strokeWidth = dp(2f)
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = highlightColor
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(dp(1f), dp(10f)), 0f)
    }
    private val successRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = successColor
        strokeWidth = dp(4f)
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = successColor
        strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val errorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = errorColor
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
    }

    private val ringRect = RectF()
    private val faceRect = RectF()
    private val scanClipPath = Path()
    private val checkPath = Path()
    private val partialCheckPath = Path()
    private val pathMeasure = PathMeasure()

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    private var state = State.IDLE
    private var progress = 0f
    private var displayedProgress = 0f
    private var scanPhase = 0f
    private var successPhase = 0f
    private var errorPhase = 0f

    private var progressAnimator: ValueAnimator? = null
    private var scanAnimator: ValueAnimator? = null
    private var statusAnimator: ValueAnimator? = null
    private var attached = false
    private var windowVisible = true

    init {
        contentDescription = resources.getString(R.string.face_scan_content_description)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = paddingLeft + (w - paddingLeft - paddingRight) / 2f
        centerY = paddingTop + (h - paddingTop - paddingBottom) / 2f
        radius = minOf(w - paddingLeft - paddingRight, h - paddingTop - paddingBottom) / 2f - dp(8f)
        if (radius <= 0f) return

        ringRect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        val half = radius * 0.66f
        faceRect.set(centerX - half, centerY - half, centerX + half, centerY + half)
        scanClipPath.reset()
        if (useFaceIdStyle) {
            scanClipPath.addRoundRect(
                faceRect, radius * 0.18f, radius * 0.18f, Path.Direction.CCW
            )
        } else {
            scanClipPath.addCircle(centerX, centerY, radius * 0.72f, Path.Direction.CCW)
        }

        checkPath.reset()
        checkPath.moveTo(centerX - radius * 0.22f, centerY)
        checkPath.lineTo(centerX - radius * 0.05f, centerY + radius * 0.15f)
        checkPath.lineTo(centerX + radius * 0.24f, centerY - radius * 0.18f)
        pathMeasure.setPath(checkPath, false)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        updateScanAnimator()
    }

    override fun onDetachedFromWindow() {
        attached = false
        cancelAnimators()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        updateScanAnimator()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        canvas.save()
        if (state == State.ERROR && durationScale > 0f) {
            val shake = (sin(errorPhase * Math.PI * 6.0) * (1f - errorPhase) * dp(5f)).toFloat()
            canvas.translate(shake, 0f)
        }

        drawRing(canvas)
        when (state) {
            State.SUCCESS -> drawSuccess(canvas)
            State.ERROR -> {
                drawGuides(canvas)
                drawError(canvas)
            }
            else -> {
                drawGuides(canvas)
                if (state == State.SCANNING) drawScanLine(canvas)
            }
        }
        canvas.restore()
    }

    /** Reset to an empty ring, e.g. when (re)starting enrollment. */
    fun reset() {
        cancelAnimators()
        state = State.IDLE
        progress = 0f
        displayedProgress = 0f
        scanPhase = 0f
        successPhase = 0f
        errorPhase = 0f
        invalidate()
    }

    /** Start the looping scan indicator. Progress can be fed in independently. */
    fun startScanning() {
        cancelStatusAnimator()
        state = State.SCANNING
        successPhase = 0f
        errorPhase = 0f
        updateScanAnimator()
        invalidate()
    }

    /** Stop the loop without losing the current progress (used while the activity is paused). */
    fun stopScanning() {
        stopScanAnimator()
    }

    /** Capture progress in the range 0..100. */
    fun setProgress(value: Float) {
        if (state == State.SUCCESS || state == State.ERROR) return
        progress = value.coerceIn(0f, 100f)
        animateProgress(progress, fast = false)
    }

    /** Show the completed ring and a drawn-in check mark. */
    fun setSuccess() {
        if (state == State.SUCCESS) return
        state = State.SUCCESS
        stopScanAnimator()
        cancelStatusAnimator()
        animateProgress(100f, fast = true)
        successPhase = if (durationScale <= 0f) 1f else 0f
        statusAnimator = startStatusAnimation(SUCCESS_DURATION_MS) { successPhase = it }
        announceForAccessibility(resources.getString(R.string.face_scan_captured))
    }

    /** Show a short shake and an error ring before the caller navigates away. */
    fun setError() {
        state = State.ERROR
        stopScanAnimator()
        cancelStatusAnimator()
        errorPhase = if (durationScale <= 0f) 1f else 0f
        statusAnimator = startStatusAnimation(ERROR_DURATION_MS) { errorPhase = it }
        announceForAccessibility(resources.getString(R.string.face_scan_failed))
    }

    private fun drawRing(canvas: Canvas) {
        canvas.drawCircle(centerX, centerY, radius, trackPaint)
        val sweep = displayedProgress * 3.6f
        if (sweep > 0f) {
            val activePaint = when (state) {
                State.SUCCESS -> successRingPaint
                State.ERROR -> errorRingPaint
                else -> progressPaint
            }
            canvas.drawArc(ringRect, -90f, sweep, false, activePaint)
        }
        if (state == State.SCANNING && durationScale > 0f) {
            canvas.drawArc(ringRect, -90f + scanPhase * 320f, 26f, false, cometPaint)
        }
    }

    private fun drawGuides(canvas: Canvas) {
        if (useFaceIdStyle) {
            val pulse = 0.5 + 0.5 * sin(scanPhase * Math.PI * 2.0)
            bracketPaint.alpha = if (state == State.SCANNING) {
                (140 + pulse * 115).toInt()
            } else {
                200
            }
            drawCornerBrackets(canvas)
        } else {
            if (durationScale > 0f) {
                for (i in 0 until 3) {
                    val phase = (scanPhase + i / 3f) % 1f
                    pulsePaint.alpha = ((1f - phase) * 90f).toInt().coerceIn(0, 255)
                    canvas.drawCircle(centerX, centerY, radius * (0.48f + phase * 0.3f), pulsePaint)
                }
                canvas.save()
                canvas.rotate(scanPhase * 360f, centerX, centerY)
                canvas.drawCircle(centerX, centerY, radius * 0.72f, dashPaint)
                canvas.restore()
            }
        }
        drawFaceOutline(canvas)
    }

    private fun drawCornerBrackets(canvas: Canvas) {
        val left = faceRect.left
        val top = faceRect.top
        val right = faceRect.right
        val bottom = faceRect.bottom
        val corner = radius * 0.2f
        val arm = radius * 0.16f

        // Four rounded corners, like the Face ID scan frame.
        canvas.drawArc(RectF(left, top, left + 2 * corner, top + 2 * corner), 180f, 90f, false, bracketPaint)
        canvas.drawLine(left, top + corner, left, top + corner + arm, bracketPaint)
        canvas.drawLine(left + corner, top, left + corner + arm, top, bracketPaint)

        canvas.drawArc(RectF(right - 2 * corner, top, right, top + 2 * corner), 270f, 90f, false, bracketPaint)
        canvas.drawLine(right, top + corner, right, top + corner + arm, bracketPaint)
        canvas.drawLine(right - corner, top, right - corner - arm, top, bracketPaint)

        canvas.drawArc(RectF(right - 2 * corner, bottom - 2 * corner, right, bottom), 0f, 90f, false, bracketPaint)
        canvas.drawLine(right, bottom - corner, right, bottom - corner - arm, bracketPaint)
        canvas.drawLine(right - corner, bottom, right - corner - arm, bottom, bracketPaint)

        canvas.drawArc(RectF(left, bottom - 2 * corner, left + 2 * corner, bottom), 90f, 90f, false, bracketPaint)
        canvas.drawLine(left, bottom - corner, left, bottom - corner - arm, bracketPaint)
        canvas.drawLine(left + corner, bottom, left + corner + arm, bottom, bracketPaint)
    }

    private fun drawFaceOutline(canvas: Canvas) {
        canvas.drawOval(
            RectF(
                centerX - radius * 0.32f, centerY - radius * 0.42f,
                centerX + radius * 0.32f, centerY + radius * 0.38f
            ),
            facePaint
        )
        val eyeY = centerY - radius * 0.1f
        val eyeHalfWidth = radius * 0.09f
        val eyeHalfHeight = radius * 0.06f
        canvas.drawArc(
            RectF(centerX - radius * 0.23f, eyeY - eyeHalfHeight,
                centerX - radius * 0.23f + eyeHalfWidth * 2, eyeY + eyeHalfHeight),
            190f, 160f, false, facePaint
        )
        canvas.drawArc(
            RectF(centerX + radius * 0.23f - eyeHalfWidth * 2, eyeY - eyeHalfHeight,
                centerX + radius * 0.23f, eyeY + eyeHalfHeight),
            190f, 160f, false, facePaint
        )
        canvas.drawArc(
            RectF(centerX - radius * 0.12f, centerY + radius * 0.04f,
                centerX + radius * 0.12f, centerY + radius * 0.22f),
            20f, 140f, false, facePaint
        )
    }

    private fun drawScanLine(canvas: Canvas) {
        val band = radius * 0.24f
        val y = faceRect.top + scanPhase * (faceRect.height())
        val gradient = LinearGradient(
            centerX, y - band, centerX, y + band,
            intArrayOf(Color.TRANSPARENT, scanColor, Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        scanPaint.shader = gradient
        canvas.save()
        canvas.clipPath(scanClipPath)
        canvas.drawRect(faceRect.left, y - band, faceRect.right, y + band, scanPaint)
        canvas.restore()
    }

    private fun drawSuccess(canvas: Canvas) {
        successRingPaint.alpha = (220f * successPhase).toInt().coerceIn(0, 255)
        canvas.drawCircle(centerX, centerY, radius, successRingPaint)
        partialCheckPath.reset()
        pathMeasure.getSegment(
            0f, pathMeasure.length * successPhase.coerceIn(0f, 1f), partialCheckPath, true
        )
        if (!partialCheckPath.isEmpty) canvas.drawPath(partialCheckPath, checkPaint)
    }

    private fun drawError(canvas: Canvas) {
        canvas.drawArc(ringRect, 0f, 360f, false, errorRingPaint)
        val d = radius * 0.2f
        canvas.drawLine(centerX - d, centerY - d, centerX + d, centerY + d, errorRingPaint)
        canvas.drawLine(centerX + d, centerY - d, centerX - d, centerY + d, errorRingPaint)
    }

    private fun animateProgress(target: Float, fast: Boolean) {
        progressAnimator?.cancel()
        val capped = target.coerceIn(0f, 100f)
        val delta = abs(capped - displayedProgress)
        if (durationScale <= 0f || delta < 0.5f) {
            displayedProgress = capped
            postInvalidateOnAnimation()
            return
        }
        val maxDuration = if (fast) 350f else 1000f
        val animator = ValueAnimator.ofFloat(displayedProgress, capped).apply {
            duration = (delta * 10f * durationScale).toLong()
                .coerceAtLeast(1L).coerceAtMost(maxDuration.toLong())
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                displayedProgress = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (progressAnimator == animation) progressAnimator = null
                }
            })
        }
        progressAnimator = animator
        animator.start()
    }

    private fun startStatusAnimation(baseDurationMs: Long, onUpdate: (Float) -> Unit): ValueAnimator {
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (baseDurationMs * durationScale).toLong().coerceAtLeast(1L)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                onUpdate(it.animatedValue as Float)
                postInvalidateOnAnimation()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (statusAnimator == animation) statusAnimator = null
                }
            })
            start()
        }
    }

    private fun updateScanAnimator() {
        if (state == State.SCANNING && attached && windowVisible && durationScale > 0f) {
            if (scanAnimator != null) return
            scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SCAN_DURATION_MS
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener {
                    scanPhase = it.animatedValue as Float
                    postInvalidateOnAnimation()
                }
                start()
            }
        } else {
            stopScanAnimator()
        }
    }

    private fun stopScanAnimator() {
        scanAnimator?.cancel()
        scanAnimator = null
    }

    private fun cancelStatusAnimator() {
        statusAnimator?.cancel()
        statusAnimator = null
    }

    private fun cancelAnimators() {
        progressAnimator?.cancel()
        progressAnimator = null
        stopScanAnimator()
        cancelStatusAnimator()
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )

    private fun resolveThemeColor(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) value.data else fallback
    }

    private fun alphaOf(color: Int, alpha: Int): Int = Color.argb(
        alpha, Color.red(color), Color.green(color), Color.blue(color)
    )

    private companion object {
        const val SCAN_DURATION_MS = 2200L
        const val SUCCESS_DURATION_MS = 550L
        const val ERROR_DURATION_MS = 500L
    }
}
