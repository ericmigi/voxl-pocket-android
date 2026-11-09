package com.tembo.voxl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.Surface
import android.view.TextureView
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-window remote desktop: video, cursor, touch/mouse/keyboard input. View coordinates
 * are device pixels, so "1:1" here is one desktop pixel per device pixel.
 */
@SuppressLint("ViewConstructor")
class RemoteView(context: Context, host: Host, private val onAuthFailed: () -> Unit) :
    FrameLayout(context), Session.Listener {

    val session = Session(context, host, this)
    private val desk = FrameLayout(context)       // W x H desktop pixels, scaled and moved
    private val cursorView = ImageView(context)
    private val main = Handler(Looper.getMainLooper())
    private val density get() = resources.displayMetrics.density // changes with display scaling
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // Read by the Compose chrome.
    var status by mutableStateOf<String?>("Connecting…")
    var displays by mutableIntStateOf(1)
    var display by mutableIntStateOf(0)
    var trackpad by mutableStateOf(true)
    var stickyMods by mutableStateOf(setOf<Int>())

    private var W = 0; private var H = 0

    /**
     * Pixels at the bottom hidden by the soft keyboard and key bar. The desktop may then be
     * moved up by that much (pinch-pan), and is moved to keep the pointer in sight.
     */
    var covered = 0
        set(v) {
            if (v == field) return
            field = v
            layoutDesk()
            followCursor()
        }
    private var resizeScale: Double? = null
    private var lastSz = ""
    private val ownSize = HashMap<Int, String>() // display -> its size before we resized it
    private val resized = HashSet<Int>()          // displays this view changed
    private var zoom = 1f
    private var offX = 0f; private var offY = 0f
    private var tx = 0f; private var ty = 0f       // desk position on screen, from layoutDesk()
    private var curX = 0f; private var curY = 0f   // desktop pixels
    private var hotX = 0; private var hotY = 0
    private var lastMove = ""
    private var scrollAccX = 0f; private var scrollAccY = 0f
    private var wheelAccX = 0f; private var wheelAccY = 0f
    private val heldKeys = HashMap<Int, Int>()     // Android key code -> keysym sent on press
    private var mouseButtons = 0
    private var mouseTouching = false

    // ponytail: fixed trackpad gain; add an acceleration curve if long moves feel slow.
    private val trackpadGain = 1.5f

    /**
     * 0 the Mac's own resolution (never resized, so windows stay put), 1 Large, 2 Medium,
     * 3 More space (pixel perfect). Every session starts at 0.
     */
    var textSize = 0
        set(v) {
            field = v
            zoom = 1f; offX = 0f; offY = 0f
            layoutDesk()
            sendResize()
        }

    init {
        setBackgroundColor(0xff000000.toInt())
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
        desk.pivotX = 0f; desk.pivotY = 0f
        addView(desk, LayoutParams(0, 0))
        cursorView.translationZ = 1f
        desk.addView(cursorView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    // MARK: Session.Listener

    override fun onHello(width: Int, height: Int, stripes: List<Stripe>, resizeScale: Double?, displays: Int, display: Int) {
        this.displays = displays; this.display = display
        W = width; H = height
        desk.removeAllViews()
        for (s in stripes) desk.addView(stripeView(s), LayoutParams(s.width, s.height).apply { topMargin = s.y })
        desk.addView(cursorView)
        desk.layoutParams = LayoutParams(W, H)
        this.resizeScale = resizeScale
        ownSize.putIfAbsent(display, "$width,$height") // first hello: before anyone here resized it
        lastSz = ""
        lastMove = ""
        curX = min(curX, W - 1f); curY = min(curY, H - 1f)
        layoutDesk()
        placeCursor()
        sendResize()
    }

    private fun stripeView(s: Stripe) = TextureView(context).apply {
        isOpaque = true
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                t.setDefaultBufferSize(s.width, s.height)
                s.setSurface(Surface(t))
                session.requestKeyframe() // frames sent before the surface existed were dropped
            }
            override fun onSurfaceTextureDestroyed(t: SurfaceTexture): Boolean { s.setSurface(null); return true }
            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
        }
    }

    override fun onCursor(image: Bitmap?, hotX: Int, hotY: Int) {
        cursorView.setImageBitmap(image)
        this.hotX = hotX; this.hotY = hotY
        placeCursor()
    }

    override fun onState(text: String?) { status = text }
    override fun onAuthFailed() = onAuthFailed.invoke()

    // MARK: geometry

    /**
     * Device pixels per desktop pixel at zoom 1. When the desktop fits and stretching it
     * would gain little, it is shown 1:1 so text stays sharp, with a thin border, instead
     * of being scaled a few percent and blurred.
     */
    private val fit: Float
        get() {
            if (W <= 0 || H <= 0 || width == 0) return 1f
            val stretch = min(width.toFloat() / W, height.toFloat() / H)
            return if (1f <= stretch && stretch < 1.25f) 1f else stretch
        }

    private val deskPerPx get() = 1f / (fit * zoom)

    private fun layoutDesk() {
        if (W <= 0) return
        val s = fit * zoom
        val mx = max(0f, (W * s - width) / 2); val my = max(0f, (H * s - height) / 2)
        offX = offX.coerceIn(-mx, mx)
        offY = offY.coerceIn(min(-my, (height - H * s) / 2 - covered), my) // up to the keyboard's top
        // Corner on a whole device pixel, or 1:1 still blurs by half a pixel.
        tx = (width / 2f + offX - W * s / 2).roundToInt().toFloat()
        ty = (height / 2f + offY - H * s / 2).roundToInt().toFloat()
        desk.scaleX = s; desk.scaleY = s
        desk.translationX = tx; desk.translationY = ty
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutDesk()
        followCursor() // a taller desktop above the same keyboard can hide the pointer
        // Debounced: dragging a split-screen divider resizes many times.
        main.removeCallbacks(resizeNow)
        if (resizeScale != null) main.postDelayed(resizeNow, 250)
    }

    private val resizeNow = Runnable { sendResize() }

    override fun onConfigurationChanged(c: android.content.res.Configuration) {
        super.onConfigurationChanged(c)
        sendResize() // a density change asks for a new size even when the pixel size stays
    }

    private fun sendResize() {
        // Mac displays only offer landscape modes; a portrait size makes the host pick a tiny one.
        // ponytail: portrait keeps the current desktop letterboxed; add rotated modes if the host learns them.
        val base = resizeScale ?: return
        if (textSize == 0) {
            // Back to the display's own size, only if we changed it.
            // ponytail: sets the same-sized mode, not necessarily the user's exact one (refresh rate, HiDPI).
            // Done only once sent; while disconnected it is retried after the next hello.
            if (display in resized) ownSize[display]?.let { if (session.send("sz,$it")) { lastSz = it; resized -= display } }
            return
        }
        if (width < height || width == 0) return
        // Desktop pixels asked per dp: the host's own scale (Mac points as big as dp) up to
        // this screen's density (one desktop pixel per device pixel).
        val native = density.toDouble()
        val scale = listOf(base, (base + native) / 2, native)[textSize.coerceIn(1, 3) - 1]
        val key = "${(width / density * scale).roundToInt()},${(height / density * scale).roundToInt()}"
        if (key == lastSz) return
        // Counted only once sent; while disconnected it is retried after the next hello.
        if (session.send("sz,$key")) { lastSz = key; resized += display }
    }

    private fun placeCursor() {
        cursorView.translationX = curX - hotX
        cursorView.translationY = curY - hotY
    }

    private fun toDeskX(x: Float) = (x - tx) / (fit * zoom)
    private fun toDeskY(y: Float) = (y - ty) / (fit * zoom)

    /** Moves the remote pointer to a desktop point (clamped) and sends `m` when it changed. */
    private fun moveTo(x: Float, y: Float) {
        if (W <= 0) return
        curX = x.coerceIn(0f, W - 1f); curY = y.coerceIn(0f, H - 1f)
        placeCursor()
        val s = "m,${curX.roundToInt()},${curY.roundToInt()}"
        if (s != lastMove) { lastMove = s; session.send(s) }
    }

    private fun moveToScreen(x: Float, y: Float) = moveTo(toDeskX(x), toDeskY(y))

    private fun button(b: Int, down: Boolean) = session.send("b,$b,${if (down) 1 else 0}")
    private fun click(b: Int = 1) { button(b, true); button(b, false) }

    private fun scroll(dx: Float, dy: Float) {
        scrollAccX -= dx * deskPerPx; scrollAccY -= dy * deskPerPx
        if (abs(scrollAccX) < 0.5f && abs(scrollAccY) < 0.5f) return
        session.send("sp,%.1f,%.1f".format(java.util.Locale.US, scrollAccX, scrollAccY))
        scrollAccX = 0f; scrollAccY = 0f
    }

    private fun relative(dx: Float, dy: Float) {
        val k = trackpadGain * deskPerPx
        moveTo(curX + dx * k, curY + dy * k)
        followCursor()
    }

    /** Keeps the cursor in sight when zoomed in (trackpad mode) or above the soft keyboard. */
    private fun followCursor() {
        if (zoom <= 1f && covered == 0) return
        val s = fit * zoom
        val px = tx + curX * s; val py = ty + curY * s; val m = 40 * density
        val bottom = height - covered - m
        if (px < m) offX += m - px else if (px > width - m) offX -= px - (width - m)
        if (py < m) offY += m - py else if (py > bottom) offY -= py - bottom
        layoutDesk()
    }

    // MARK: touch gestures

    private var downX = 0f; private var downY = 0f; private var downAt = 0L
    private var lastX = 0f; private var lastY = 0f
    private var maxPointers = 0
    private var moved = false
    private var dragging = false        // touch mode: left button held while dragging
    private var longHeld = false        // trackpad mode: hold, then drag
    private var twoX = 0f; private var twoY = 0f; private var startSpan = 0f; private var lastSpan = 0f
    private var pinching = false
    private var twoMoved = false

    private val longPress = Runnable {
        if (moved || maxPointers > 1) return@Runnable
        if (trackpad) { longHeld = true; button(1, true) } else { moveToScreen(downX, downY); click(3) }
        moved = true // no tap on release
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) { mouse(e); return true }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; downAt = SystemClock.uptimeMillis()
                maxPointers = 1; moved = false; dragging = false; longHeld = false; pinching = false; twoMoved = false
                main.postDelayed(longPress, 400)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                main.removeCallbacks(longPress)
                maxPointers = max(maxPointers, e.pointerCount)
                if (e.pointerCount == 2) {
                    twoX = midX(e); twoY = midY(e); startSpan = span(e); lastSpan = startSpan; twoMoved = false
                }
            }
            MotionEvent.ACTION_MOVE -> if (e.pointerCount >= 2) twoFingers(e) else if (maxPointers == 1) oneFinger(e)
            MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount == 2) scrollAccX = 0f.also { scrollAccY = 0f }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                val tap = e.actionMasked == MotionEvent.ACTION_UP && !moved && !twoMoved &&
                    SystemClock.uptimeMillis() - downAt < 350
                if (tap) {
                    if (!trackpad) moveToScreen(downX, downY)
                    click(if (maxPointers >= 2) 3 else 1)
                }
                if (dragging || longHeld) button(1, false)
                if (pinching && zoom < 1.05f) { zoom = 1f; offX = 0f; if (covered == 0) offY = 0f; layoutDesk() }
                dragging = false; longHeld = false; pinching = false
            }
        }
        return true
    }

    private fun oneFinger(e: MotionEvent) {
        if (!moved && hypot(e.x - downX, e.y - downY) < slop) return
        if (!moved) main.removeCallbacks(longPress)
        val first = !moved
        moved = true
        if (trackpad || longHeld) {
            relative(e.x - lastX, e.y - lastY)
        } else {
            if (first) { moveToScreen(downX, downY); button(1, true); dragging = true }
            moveToScreen(e.x, e.y)
        }
        lastX = e.x; lastY = e.y
    }

    private fun twoFingers(e: MotionEvent) {
        val cx = midX(e); val cy = midY(e); val sp = span(e)
        val dx = cx - twoX; val dy = cy - twoY
        if (!twoMoved && hypot(dx, dy) < slop && abs(sp - startSpan) < slop) return
        twoMoved = true
        if (!pinching && startSpan > 0 && abs(sp / startSpan - 1) > 0.15f) { pinching = true; lastSpan = sp }
        if (pinching) {
            offX += dx; offY += dy
            layoutDesk()
            val ax = toDeskX(cx); val ay = toDeskY(cy)
            zoom = (zoom * sp / max(lastSpan, 1f)).coerceIn(1f, 5f)
            val s = fit * zoom
            offX = cx - (ax - W / 2f) * s - width / 2f
            offY = cy - (ay - H / 2f) * s - height / 2f
            layoutDesk()
        } else {
            scroll(dx, dy)
        }
        twoX = cx; twoY = cy; lastSpan = sp
    }

    private fun midX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2
    private fun midY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2
    private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    // MARK: mouse

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(e)
        mouse(e)
        return true
    }

    private fun mouse(e: MotionEvent) {
        moveToScreen(e.x, e.y)
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            // Wheel ticks; Android's positive VSCROLL is up, the host's positive dy is down.
            wheelAccX += e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            wheelAccY -= e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val sx = wheelAccX.toInt(); val sy = wheelAccY.toInt()
            if (sx != 0 || sy != 0) { session.send("s,$sx,$sy"); wheelAccX -= sx; wheelAccY -= sy }
        }
        // Buttons from the state each event carries, so a press seen by any path counts once.
        // A touch stream with no button bits (tap-to-click touchpads) is a primary press.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> mouseTouching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mouseTouching = false
        }
        val now = when {
            e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL -> 0
            mouseTouching && e.buttonState == 0 -> MotionEvent.BUTTON_PRIMARY
            else -> e.buttonState
        }
        for ((mask, b) in listOf(MotionEvent.BUTTON_PRIMARY to 1, MotionEvent.BUTTON_TERTIARY to 2, MotionEvent.BUTTON_SECONDARY to 3)) {
            if ((now and mask) != (mouseButtons and mask)) button(b, now and mask != 0)
        }
        mouseButtons = now
    }

    override fun onResolvePointerIcon(e: MotionEvent, i: Int): PointerIcon =
        PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL) // the host draws the cursor

    // MARK: keyboard

    /** Every key event while the desktop is shown. Returns false for keys left to Android. */
    fun handleKey(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP ||
            code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_POWER) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return true // the host repeats held keys itself
                val sym = keysym(e) ?: return false
                heldKeys[code] = sym
                key(sym, true)
            }
            KeyEvent.ACTION_UP -> heldKeys.remove(code)?.let { key(it, false) }
            KeyEvent.ACTION_MULTIPLE -> e.characters?.let { typeText(it) }
        }
        return true
    }

    fun key(sym: Int, down: Boolean) {
        // Something copied in an app beside Voxl (split screen) may not have been seen yet:
        // sync before a V reaches the host, so Cmd+V there pastes it.
        if (down && (sym == 0x76 || sym == 0x56)) session.sendClipboard()
        session.send("k,$sym,${if (down) 1 else 0}")
    }

    /** Sends Cmd+`sym` as one shortcut; only the key when Cmd is already held on a hardware keyboard. */
    fun cmdCombo(sym: Int) {
        val cmd = 0xffeb
        val held = heldKeys.containsValue(cmd)
        if (!held) key(cmd, true)
        key(sym, true); key(sym, false)
        if (!held) key(cmd, false)
    }

    /** Taps a key with the key bar's sticky modifiers held, then clears them (one-shot). */
    fun tap(sym: Int) {
        val mods = stickyMods.toList()
        stickyMods = emptySet()
        mods.forEach { key(it, true) }
        key(sym, true); key(sym, false)
        mods.reversed().forEach { key(it, false) }
    }

    fun toggleSticky(sym: Int) { stickyMods = if (sym in stickyMods) stickyMods - sym else stickyMods + sym }

    private fun typeText(t: CharSequence) {
        t.codePoints().forEach { tap(if (it == '\n'.code) 0xff0d else keysym(it)) }
    }

    fun releaseAll() {
        session.send("r")
        heldKeys.clear()
        mouseButtons = 0
    }

    fun toggleKeyboard(show: Boolean) {
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (show) { requestFocus(); imm.showSoftInput(this, 0) } else imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) session.sendClipboard() // clipboard reads need focus; covers resume too
        else releaseAll()                    // keys held when another window took focus never come up
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(out: EditorInfo): InputConnection {
        // Visible-password: no suggestions or composing, so each typed character arrives at once.
        out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            // Composing IMEs (CJK, some swipe keyboards) propose text that changes until it is
            // committed; only the committed text is typed on the host.
            // ponytail: the composition isn't shown anywhere while it is being written.
            private var composing: CharSequence = ""
            override fun commitText(text: CharSequence, pos: Int): Boolean { composing = ""; typeText(text); return true }
            override fun setComposingText(text: CharSequence, pos: Int): Boolean { composing = text; return true }
            override fun finishComposingText(): Boolean { val t = composing; composing = ""; typeText(t); return true }
            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                repeat(before) { tap(0xff08) }; repeat(after) { tap(0xffff) }; return true
            }
            override fun sendKeyEvent(e: KeyEvent): Boolean {
                if (e.action == KeyEvent.ACTION_DOWN) keysym(e)?.let { tap(it) }
                return true
            }
            override fun performEditorAction(action: Int): Boolean { tap(0xff0d); return true }
        }
    }

    companion object {
        fun keysym(cp: Int) = if (cp < 0x100) cp else 0x0100_0000 + cp

        fun keysym(e: KeyEvent): Int? {
            val code = e.keyCode
            if (code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return 0xffbe + code - KeyEvent.KEYCODE_F1
            special[code]?.let { return it }
            // With Cmd/Ctrl/Opt held, the key's own character (Opt+E is E, not an accent).
            val mods = KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK
            val meta = if (e.metaState and mods != 0) e.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON) else e.metaState
            val c = e.getUnicodeChar(meta) and android.view.KeyCharacterMap.COMBINING_ACCENT_MASK
            return if (c == 0) null else keysym(c)
        }

        private val special = mapOf(
            KeyEvent.KEYCODE_ENTER to 0xff0d, KeyEvent.KEYCODE_NUMPAD_ENTER to 0xff8d, KeyEvent.KEYCODE_ESCAPE to 0xff1b,
            KeyEvent.KEYCODE_DEL to 0xff08, KeyEvent.KEYCODE_TAB to 0xff09, KeyEvent.KEYCODE_SPACE to 0x20,
            KeyEvent.KEYCODE_FORWARD_DEL to 0xffff, KeyEvent.KEYCODE_MOVE_HOME to 0xff50, KeyEvent.KEYCODE_MOVE_END to 0xff57,
            KeyEvent.KEYCODE_PAGE_UP to 0xff55, KeyEvent.KEYCODE_PAGE_DOWN to 0xff56, KeyEvent.KEYCODE_INSERT to 0xff63,
            KeyEvent.KEYCODE_DPAD_LEFT to 0xff51, KeyEvent.KEYCODE_DPAD_UP to 0xff52,
            KeyEvent.KEYCODE_DPAD_RIGHT to 0xff53, KeyEvent.KEYCODE_DPAD_DOWN to 0xff54,
            KeyEvent.KEYCODE_SHIFT_LEFT to 0xffe1, KeyEvent.KEYCODE_SHIFT_RIGHT to 0xffe2,
            KeyEvent.KEYCODE_CTRL_LEFT to 0xffe3, KeyEvent.KEYCODE_CTRL_RIGHT to 0xffe4,
            KeyEvent.KEYCODE_ALT_LEFT to 0xffe9, KeyEvent.KEYCODE_ALT_RIGHT to 0xffea,
            KeyEvent.KEYCODE_META_LEFT to 0xffeb, KeyEvent.KEYCODE_META_RIGHT to 0xffec, // Cmd = Super
            KeyEvent.KEYCODE_CAPS_LOCK to 0xffe5,
        )
    }
}
