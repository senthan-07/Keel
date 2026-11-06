package dev.keel.app.pause

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
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
        layout.addView(text("Pause", 34f, bold = true))
        layout.addView(text("You were opening $appLabel.", 15f, MUTED, bottom = 28))
        if (suggestion != null) {
            val minutes = suggestion.estimateMinutes?.let { " ($it min)" }.orEmpty()
            layout.addView(text("Next step: ${suggestion.title}$minutes", 19f))
            suggestion.serves?.let { layout.addView(text("Serves: $it", 14f, MUTED)) }
            layout.addView(spacer(24))
            layout.addView(button("Do it now", primary = true) { onChoice(Choice.DO_IT_NOW, null) })
        } else {
            layout.addView(text("Nothing is planned next. Is this what you want to do?", 18f, bottom = 24))
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
        view.alpha = 0.5f
        handler.postDelayed({ countdown(view, left - 1) }, 1000)
    }

    /** One honest tap about why, which feeds the Sunday review. */
    private fun renderReasons(layout: LinearLayout) {
        layout.removeAllViews()
        layout.addView(text("Why are you opening it?", 22f, bottom = 24))
        for (reason in REASONS) {
            layout.addView(button(reason, primary = false) { onChoice(Choice.OPEN, reason) })
        }
        layout.addView(spacer(12))
        layout.addView(button("Never mind, don't open", primary = true) { onChoice(Choice.DONT_OPEN, null) })
    }

    private fun text(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false, bottom: Int = 10) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(bottom))
        }

    private fun spacer(height: Int) = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(if (primary) BACKGROUND else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            if (primary) setColor(ACCENT) else setStroke(dp(1), MUTED)
        }
        val vertical = dp(14)
        setPadding(dp(16), vertical, dp(16), vertical)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

    private companion object {
        val BACKGROUND = Color.parseColor("#FF101820")
        val ACCENT = Color.parseColor("#FFF2C14E")
        val MUTED = Color.parseColor("#FFA9B4BE")
        val REFOCUS_MS = listOf(800L, 2000L)
        val REASONS = listOf("Something specific", "Planned break", "Replying to someone", "Just bored")
    }
}
