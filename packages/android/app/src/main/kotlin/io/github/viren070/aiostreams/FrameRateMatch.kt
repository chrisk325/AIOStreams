package io.github.viren070.aiostreams

import android.app.Activity
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import kotlin.math.abs
import kotlin.math.max

class FrameRateMatch(
    private val activity: Activity,
    /** Pauses what plays while a TV blanks to switch modes; false when it was already paused. */
    private val hold: () -> Boolean,
    private val resume: () -> Unit,
) {
    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            update()
        }

    private val tv =
        activity.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    private val main = Handler(Looper.getMainLooper())

    private var playing: Double? = null
    private var chosen: Int? = null
    private var original: Int? = null
    private var waiting: Runnable? = null

    fun match(frameRate: Double?) {
        playing = frameRate
        update()
    }

    private fun update() {
        val fps = playing ?: return restore()
        val display = activity.window.decorView.display ?: return
        val active = display.mode
        val modes = display.supportedModes.filter { it.sameSize(active) }
        val best = when {
            !tv -> modes.maxByOrNull { it.refreshRate }
            enabled -> choose(modes, fps.toFloat())
            else -> null
        } ?: return restore()
        if (best.modeId == chosen) return
        if (original == null) original = activity.window.attributes.preferredDisplayModeId
        chosen = best.modeId
        prefer(best.modeId)
        if (!tv || best.modeId == active.modeId) return
        val held = hold()
        wait(display, best) { if (held) resume() }
    }

    private fun restore() {
        cancelWait()
        chosen = null
        val back = original ?: return
        original = null
        prefer(back)
    }

    private fun prefer(modeId: Int) {
        val params = activity.window.attributes
        if (params.preferredDisplayModeId == modeId) return
        params.preferredDisplayModeId = modeId
        activity.window.attributes = params
    }

    private fun wait(display: Display, mode: Display.Mode, then: () -> Unit) {
        cancelWait()
        val start = SystemClock.uptimeMillis()
        var stable = 0
        val poll = object : Runnable {
            override fun run() {
                stable = if (display.mode.refreshRate.near(mode.refreshRate)) stable + 1 else 0
                if (stable >= 2 || SystemClock.uptimeMillis() - start > SWITCH_TIMEOUT_MS) {
                    waiting = null
                    return then()
                }
                main.postDelayed(this, POLL_MS)
            }
        }
        waiting = poll
        main.postDelayed(poll, POLL_MS)
    }

    private fun cancelWait() {
        waiting?.let(main::removeCallbacks)
        waiting = null
    }

    private companion object {
        const val POLL_MS = 60L
        const val SWITCH_TIMEOUT_MS = 4000L

        fun Display.Mode.sameSize(other: Display.Mode) =
            physicalWidth == other.physicalWidth && physicalHeight == other.physicalHeight

        fun Float.near(target: Float) = abs(this - target) <= max(0.08f, target * 0.003f)

        // 2.5 times is 3:2 pulldown.
        fun choose(modes: List<Display.Mode>, fps: Float): Display.Mode? =
            listOf(1f, 2f, 2.5f).firstNotNullOfOrNull { times ->
                val target = fps * times
                modes.filter { it.refreshRate.near(target) }.minByOrNull { abs(it.refreshRate - target) }
            }
    }
}
