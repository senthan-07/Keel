package dev.keel.app.pause

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import dev.keel.app.R
import dev.keel.domain.Choice
import dev.keel.domain.PauseSuggestion

/**
 * The pause screen. It's plain Views because it runs inside a service.
 *
 * Fully opaque, because thumbnails showed through an earlier version. Not focusable, so Back still
 * reaches the app underneath: there is always a way out, which research suggests matters more than
 * the wait. Audio focus is requested again shortly after showing, because an app's player can start
 * after the first request and keep playing behind the screen.
 */
class PauseOverlay(
    private val context: Context,
    private val windowType: Int,
    private val delaySeconds: Int,
    private val appLabel: String,
    private val headline: String?,
    private val suggestion: PauseSuggestion?,
    private val onChoice: (Choice, String?) -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build(),
        )
        .setOnAudioFocusChangeListener { }
        .build()
    private var root: LinearLayout? = null

    /** Returns false if the window couldn't be added, for example when the permission was revoked. */
    fun show(): Boolean {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(BACKGROUND)
            val pad = dp(28)
            setPadding(pad, pad, pad, pad)
        }
        renderChoices(layout)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        try {
            windowManager.addView(layout, params)
        } catch (_: WindowManager.BadTokenException) {
            handler.removeCallbacksAndMessages(null)
            return false
        } catch (_: WindowManager.InvalidDisplayException) {
            handler.removeCallbacksAndMessages(null)
            return false
        }
        root = layout
        audio.requestAudioFocus(focus)
        for (delay in REFOCUS_MS) handler.postDelayed({ audio.requestAudioFocus(focus) }, delay)
        return true
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        root?.let {
            windowManager.removeView(it)
            audio.abandonAudioFocusRequest(focus)
        }
        root = null
    }

    private fun renderChoices(layout: LinearLayout) {
        layout.removeAllViews()
        layout.addView(text("Pause", 40f, weight = 600))
        layout.addView(text("You were opening $appLabel.", 15f, MUTED, bottom = if (headline == null) 28 else 10))
        headline?.let { layout.addView(text(it, 16f, weight = 500, bottom = 28)) }
        if (suggestion != null) {
            val minutes = suggestion.estimateMinutes?.let { " ($it min)" }.orEmpty()
            // Imported plan steps can be very long, and the buttons must stay on screen.
            layout.addView(
                text("Next step: ${suggestion.title}$minutes", 20f, weight = 600).apply {
                    maxLines = 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
            suggestion.serves?.let {
                layout.addView(
                    text("Serves: $it", 14f, MUTED).apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                )
            }
            layout.addView(spacer(24))
            layout.addView(button("Do it now", primary = true) { onChoice(Choice.DO_IT_NOW, null) })
        } else {
            layout.addView(text("Nothing is planned next. Is this what you want to do?", 20f, weight = 600, bottom = 24))
        }
        layout.addView(button("Don't open", primary = suggestion == null) { onChoice(Choice.DONT_OPEN, null) })
        val open = button("Open for 5 min", primary = false) { renderReasons(layout) }
        layout.addView(open)
        countdown(open, delaySeconds)
    }

    private fun countdown(view: TextView, left: Int) {
        if (left <= 0) {
            view.text = "Open for 5 min"
            view.isEnabled = true
            view.alpha = 1f
            return
        }
        view.text = "Open in ${left}s"
        view.isEnabled = false
        view.alpha = 0.6f
        handler.postDelayed({ countdown(view, left - 1) }, 1000)
    }

    /** One honest tap about why, which feeds the Sunday review. */
    private fun renderReasons(layout: LinearLayout) {
        layout.removeAllViews()
        layout.addView(text("Why are you opening it?", 24f, weight = 600, bottom = 24))
        for (reason in REASONS) {
            layout.addView(button(reason, primary = false) { onChoice(Choice.OPEN, reason) })
        }
        layout.addView(spacer(12))
        layout.addView(button("Never mind, don't open", primary = true) { onChoice(Choice.DONT_OPEN, null) })
    }

    /** Inter, matching the app. Falls back to the system font if it can't be loaded. */
    private fun font(weight: Int): Typeface = runCatching {
        Typeface.create(context.resources.getFont(R.font.inter_variable), weight, false)
    }.getOrDefault(Typeface.DEFAULT)

    private fun text(value: String, size: Float, color: Int = TEXT, weight: Int = 400, bottom: Int = 10) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            typeface = font(weight)
            setPadding(0, 0, 0, dp(bottom))
        }

    private fun spacer(height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 16f
        typeface = font(600)
        fontFeatureSettings = "tnum"
        gravity = Gravity.CENTER
        setTextColor(TEXT)
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (primary) ACCENT else ELEVATED)
        }
        val vertical = dp(16)
        setPadding(dp(16), vertical, dp(16), vertical)
        isClickable = true
        setOnClickListener { onClick() }
        // Returning false leaves the tap to the click listener, so screen readers still work.
        @Suppress("ClickableViewAccessibility")
        setOnTouchListener { view, event ->
            pressFeedback(view, event)
            false
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) }
    }

    /** The same 0.96 press as the app, skipped when animations are off or the button is disabled. */
    private fun pressFeedback(view: View, event: MotionEvent) {
        if (!view.isEnabled || !ValueAnimator.areAnimatorsEnabled()) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(150)
                .setInterpolator(PathInterpolator(0.23f, 1f, 0.32f, 1f)).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(150)
                .setInterpolator(PathInterpolator(0.23f, 1f, 0.32f, 1f)).start()
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    private companion object {
        val BACKGROUND = Color.parseColor("#FF0A0A0A")
        val ELEVATED = Color.parseColor("#FF222222")
        val ACCENT = Color.parseColor("#FF007BA7")
        val TEXT = Color.parseColor("#FFF5F5F5")
        val MUTED = Color.parseColor("#FFA1A1AA")
        val REFOCUS_MS = listOf(800L, 2000L)
        val REASONS = listOf("Something specific", "Planned break", "Replying to someone", "Just bored")
    }
}
