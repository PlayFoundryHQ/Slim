package io.github.playfoundryhq.slim

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.exp

class WaveGestureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnLetterSelectedListener {
        fun onLetterSelected(letter: String)
        fun onLetterReleased()
    }

    var listener: OnLetterSelectedListener? = null

    // Letters shown in the index. Only letters that actually have apps are
    // displayed (set via setLetters), keeping the column compact.
    private var alphabet: List<String> = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".map { it.toString() }

    private var activeIndex = -1
    private var isDragging = false
    private var touchY = 0f

    // Auto-hide: the index fades out after a spell of no interaction and fades
    // back the instant the user reaches for it. Alpha (not visibility) is used
    // so the column stays touchable while invisible — a swipe along the edge
    // both reveals it and starts scrubbing.
    private val idleHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        animate().alpha(HIDDEN_ALPHA).setDuration(FADE_OUT_MS).start()
    }

    // Adaptive palette (updated from MainActivity to stay readable on any wallpaper)
    private var activeColor = Color.parseColor("#f8fafc") // text_primary
    private var inactiveColor = Color.parseColor("#64748b") // text_muted

    // Medium weight keeps the index legible at a glance even when inactive.
    private val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // Pre-allocated styling paints
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inactiveColor
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
        typeface = mediumTypeface
        setShadowLayer(3f, 0f, 1f, Color.parseColor("#60000000"))
    }

    private val textBounds = Rect()

    // Haptics system
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /**
     * Replaces the displayed letters with only those that have at least one
     * app, so empty letters never clutter the index.
     */
    fun setLetters(letters: List<String>) {
        if (letters.isEmpty() || letters == alphabet) return
        alphabet = letters
        activeIndex = -1
        invalidate()
        // Briefly show the refreshed index, then let it fade again.
        reveal()
        scheduleHide()
    }

    /** Fades the index back in and cancels any pending auto-hide. */
    private fun reveal() {
        idleHandler.removeCallbacks(hideRunnable)
        if (alpha < 1f) {
            animate().alpha(1f).setDuration(FADE_IN_MS).start()
        }
    }

    /** Arms the auto-hide timer, restarting it if already pending. */
    private fun scheduleHide() {
        idleHandler.removeCallbacks(hideRunnable)
        idleHandler.postDelayed(hideRunnable, IDLE_TIMEOUT_MS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleHide()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        idleHandler.removeCallbacks(hideRunnable)
    }

    /**
     * Cancels an active drag without waiting for ACTION_UP/CANCEL.
     * Any floating window that takes focus (overlay, dialog, system prompt, etc.)
     * can swallow the terminal touch event, leaving isDragging stuck as true.
     * Call this whenever the host window loses focus so the state is always clean.
     */
    fun cancelDrag() {
        if (!isDragging) return
        isDragging = false
        activeIndex = -1
        invalidate()
        scheduleHide()
    }

    /** Updates colors so the index stays readable on light or dark wallpapers. */
    fun setPalette(active: Int, inactive: Int) {
        if (active == activeColor && inactive == inactiveColor) return
        activeColor = active
        inactiveColor = inactive
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (height == 0 || alphabet.isEmpty()) return

        val itemHeight = height.toFloat() / alphabet.size
        val centerX = width / 2f

        for (i in alphabet.indices) {
            val letter = alphabet[i]
            val itemCenterY = (i * itemHeight) + (itemHeight / 2f)

            var displacementX = 0f
            var scale = 1.0f

            if (isDragging) {
                val dy = itemCenterY - touchY
                val maxDisplacement = -30f * resources.displayMetrics.density // dp to pixels
                val sigma = 40f * resources.displayMetrics.density // Gaussian width spread

                // Apply Gaussian formula
                val factor = exp(-(dy * dy) / (2 * sigma * sigma))
                displacementX = maxDisplacement * factor
                scale = 1.0f + 0.6f * factor
            }

            // Configure Paint dynamically
            if (i == activeIndex) {
                textPaint.color = activeColor
                textPaint.isFakeBoldText = true
            } else {
                // Fade inactive letters subtly — keep them legible even on black
                textPaint.color = Color.argb(
                    210,
                    Color.red(inactiveColor),
                    Color.green(inactiveColor),
                    Color.blue(inactiveColor)
                )
                textPaint.isFakeBoldText = false
            }

            val currentTextSize = 15f * resources.displayMetrics.density * scale
            textPaint.textSize = currentTextSize

            // Center text vertically
            textPaint.getTextBounds(letter, 0, letter.length, textBounds)
            val textY = itemCenterY + (textBounds.height() / 2f)

            canvas.drawText(letter, centerX + displacementX, textY, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (alphabet.isEmpty()) return false
        val itemHeight = height.toFloat() / alphabet.size
        touchY = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                reveal()
                isDragging = true
                val index = (touchY / itemHeight).toInt().coerceIn(0, alphabet.size - 1)

                if (index != activeIndex) {
                    activeIndex = index
                    triggerTick()
                    listener?.onLetterSelected(alphabet[activeIndex])
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                activeIndex = -1
                listener?.onLetterReleased()
                invalidate()
                scheduleHide()
            }
        }
        return true
    }

    companion object {
        // Fade the index out after this long with no interaction.
        private const val IDLE_TIMEOUT_MS = 4000L
        private const val FADE_OUT_MS = 500L
        private const val FADE_IN_MS = 180L
        // Fully hidden when idle; bump to e.g. 0.15f if you'd rather keep a faint hint.
        private const val HIDDEN_ALPHA = 0f
    }

    private fun triggerTick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(10)
        }
    }
}
