package dev.keel.spike

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The pause screen. It is not focusable, so Back still reaches the app underneath:
 * there is always a way out, which the research suggests matters more than the wait.
 */
class PauseOverlay(
    private val context: Context,
    private val windowType: Int,
    private val delaySeconds: Int,
    private val useAudioFocus: Boolean,
    private val onChoice: (String) -> Unit,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
        .setOnAudioFocusChangeListener { }
        .build()
    private var root: View? = null

    val isShowing get() = root != null

    /** Returns false if the window could not be added, for example when the permission was revoked. */
    fun show(onDrawn: () -> Unit): Boolean {
        if (root != null) return true
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF101820"))
            setPadding(64, 64, 64, 64)
        }
        layout.addView(label("Pause", 34f))
        layout.addView(label("Next step: Compare 2 term plans (15 min)", 18f))
        layout.addView(label("Serves: Family safety net, 2030", 14f))
        layout.addView(button("Do it now") { onChoice("do_it_now") })
        layout.addView(button("Don't open") { onChoice("dont_open") })
        val open = button("Open for 5 min") { onChoice("open_5_min") }
        open.isEnabled = delaySeconds == 0
        layout.addView(open)
        if (delaySeconds > 0) handler.postDelayed({ open.isEnabled = true }, delaySeconds * 1000L)

        layout.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                layout.viewTreeObserver.removeOnPreDrawListener(this)
                onDrawn()
                return true
            }
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windowManager.addView(layout, params)
        } catch (_: WindowManager.BadTokenException) {
            handler.removeCallbacksAndMessages(null)
            return false
        }
        root = layout
        if (useAudioFocus) audio.requestAudioFocus(focus)
        return true
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        root?.let {
            windowManager.removeView(it)
            if (useAudioFocus) audio.abandonAudioFocusRequest(focus)
        }
        root = null
    }

    private fun label(text: String, size: Float) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(0, 16, 0, 16)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
