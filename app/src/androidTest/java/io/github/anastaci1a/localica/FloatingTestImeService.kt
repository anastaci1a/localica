package io.github.anastaci1a.localica

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Real, synthetic floating IME for API36 instrumentation only. Not a production keyboard.
 * No host-insets injection, editor text reads, auto-commits, or app Back dispatch.
 */
class FloatingTestImeService : InputMethodService() {
    companion object {
        const val TAG = "LocalicaFloatingIme"
        const val HIDE_SUFFIX = ".floating.HIDE"
        const val SNAPSHOT_SUFFIX = ".floating.SNAPSHOT"
        const val CARD_DESCRIPTION = "Localica synthetic floating IME"
    }

    private var root: View? = null
    private var card: View? = null
    private var switchButton: Button? = null
    private var switchRequested = false
    private var receiverRegistered = false
    private var inputGeneration = 0L
    private var showGeneration = 0L
    private var drawCount = 0L
    private var lastDrawUptime = -1L
    private var lastLoggedDrawGeneration = -1L
    private var lastRequestedTop = -1
    private var lastInsetsSignature = ""
    private var lastLayoutSignature = ""
    private var logBudget = 256
    private var inputActive = false

    // Runtime receiver deliberately exported only in the test APK for shell control.
    // -p <actualTestPackage> is mandatory; no extras can mutate editor contents.
    private val commands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.`package` != packageName) return
            when (intent.action) {
                packageName + HIDE_SUFFIX -> { emit("hideRequested"); requestHideSelf(0) }
                packageName + SNAPSHOT_SUFFIX -> emit("snapshot")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply { addAction(packageName + HIDE_SUFFIX); addAction(packageName + SNAPSHOT_SUFFIX) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(commands, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(commands, filter)
        }
        receiverRegistered = true
        emit("created")
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        // Deterministic fixture even on emulator hosts with a hardware keyboard.
        return true
    }

    override fun onConfigureWindow(win: Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        // Floating content in a transparent TYPE_INPUT_METHOD window. Never use
        // an application overlay or lie about host WindowInsets.
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        win.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        hideImeCaptionBar(win)
    }

    override fun onCreateInputView(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = CARD_DESCRIPTION
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 69, 84)); cornerRadius = dp(18).toFloat()
            }
            addView(TextView(context).apply {
                text = "Synthetic floating IME"
                setTextColor(Color.WHITE)
                textSize = 16f
            })
            addView(Button(context).apply {
                text = "Insert x"
                contentDescription = "Floating fixture insert x"
                setOnClickListener {
                    // Read the current connection only at the explicit click; never
                    // retain a connection across finish/restart or read its text.
                    val host = currentInputEditorInfo?.packageName
                    if (host == "io.github.anastaci1a.localica" || host == "io.github.anastaci1a.localica.debug") {
                        val committed = currentInputConnection?.commitText("x", 1) == true
                        emit("explicitCommit accepted=$committed")
                    } else emit("explicitCommit refusedHost")
                }
            })
            addView(Button(context).apply {
                text = "Hide IME"
                contentDescription = "Floating fixture hide IME"
                setOnClickListener { emit("hideButton"); requestHideSelf(0) }
            })
            addView(Button(context).apply {
                text = "Switch IME"
                visibility = if (switchRequested) View.VISIBLE else View.GONE
                setOnClickListener { switchToNextInputMethod(false) }
                switchButton = this
            })
        }
        card = panel
        return object : FrameLayout(this) {
            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                drawCount++
                lastDrawUptime = SystemClock.uptimeMillis()
                if (isShown && panel.isShown && lastLoggedDrawGeneration != showGeneration) {
                    lastLoggedDrawGeneration = showGeneration
                    emit("inputViewDrawn")
                }
            }
        }.apply {
            // A real opaque card floats above a transparent lower gap. The window
            // itself is full height so reported content top can equal decor bottom.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // The framework may replace input-view LayoutParams with WRAP_CONTENT.
            // Minimum height still fills available space (clamped by parent measure).
            minimumHeight = resources.displayMetrics.heightPixels
            setPadding(0, 0, 0, dp(72))
            setBackgroundColor(Color.TRANSPARENT)
            addView(panel, FrameLayout.LayoutParams(
                minOf(dp(280), maxWidth - dp(32)).coerceAtLeast(1),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END
            ).apply { rightMargin = dp(16) })
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val signature = "$left,$top,$right,$bottom/${panel.width},${panel.height}"
                if (signature != lastLayoutSignature) {
                    lastLayoutSignature = signature
                    emit("inputViewLayout")
                }
            }
            root = this
        }
    }

    override fun onComputeInsets(outInsets: Insets) {
        val decor = window?.window?.decorView
        val height = decor?.height ?: 0
        if (height <= 0) {
            super.onComputeInsets(outInsets)
            return
        }
        // Public IME contract: no bottom-aligned content/visible inset, but only
        // the genuine floating card is touchable. Framework still owns visibility.
        outInsets.contentTopInsets = height
        outInsets.visibleTopInsets = height
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()
        val panel = card
        val rect = Rect()
        if (panel != null && panel.isShown && panel.width > 0 && panel.height > 0) {
            val location = IntArray(2)
            panel.getLocationInWindow(location)
            rect.set(location[0], location[1], location[0] + panel.width, location[1] + panel.height)
            outInsets.touchableRegion.set(rect)
        }
        lastRequestedTop = height
        val signature = "$height/$rect"
        if (signature != lastInsetsSignature) {
            lastInsetsSignature = signature
            emit("computedInsets touch=$rect")
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputActive = true
        inputGeneration++
        emit("startInput restarting=$restarting")
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        window?.window?.let(::hideImeCaptionBar)
        emit("startInputView restarting=$restarting")
    }

    override fun onWindowShown() {
        super.onWindowShown()
        showGeneration++
        window?.window?.let(::hideImeCaptionBar)
        root?.invalidate()
        emit("windowShown")
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        emit("windowHidden")
    }

    override fun onFinishInput() {
        super.onFinishInput()
        inputActive = false
        emit("finishInput")
    }

    // API36 public callback: hiding the IME caption bar may delegate its switcher.
    override fun onCustomImeSwitcherButtonRequestedVisible(visible: Boolean) {
        super.onCustomImeSwitcherButtonRequestedVisible(visible)
        switchRequested = visible
        switchButton?.visibility = if (visible) View.VISIBLE else View.GONE
        emit("switcherRequested visible=$visible")
    }

    private fun hideImeCaptionBar(win: Window) {
        if (Build.VERSION.SDK_INT >= 30) win.insetsController?.hide(WindowInsets.Type.captionBar())
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun emit(event: String) {
        if (logBudget-- <= 0) return
        val dialog = window
        val native = dialog?.window
        val decor = native?.decorView
        val captionVisible = if (Build.VERSION.SDK_INT >= 30)
            decor?.rootWindowInsets?.isVisible(WindowInsets.Type.captionBar()) else null
        // Service facts only. No editor contents and no claim about HOST root insets.
        Log.i(TAG, "event=$event uptime=${SystemClock.uptimeMillis()} pid=${Process.myPid()} " +
            "inputGeneration=$inputGeneration showGeneration=$showGeneration inputActive=$inputActive " +
            "inputViewShown=$isInputViewShown rootShown=${root?.isShown} " +
            "dialogShowing=${dialog?.isShowing} decorAttached=${decor?.isAttachedToWindow} " +
            "decorShown=${decor?.isShown} decorHeight=${decor?.height} " +
            "windowType=${native?.attributes?.type} windowToken=${decor?.windowToken?.hashCode()} " +
            "cardShown=${card?.isShown} cardWidth=${card?.width} cardHeight=${card?.height} " +
            "drawCount=$drawCount drawShowGeneration=$lastLoggedDrawGeneration lastDrawUptime=$lastDrawUptime " +
            "requestedTop=$lastRequestedTop " +
            "captionVisible=$captionVisible")
    }

    override fun onDestroy() {
        emit("destroyed")
        if (receiverRegistered) { unregisterReceiver(commands); receiverRegistered = false }
        root = null; card = null; switchButton = null
        super.onDestroy()
    }
}
