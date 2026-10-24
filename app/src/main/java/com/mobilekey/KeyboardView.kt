package com.mobilekey

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onSpecial(type: String)
        fun onCursorMove(steps: Int)
        fun onDeleteWords(count: Int)
        fun onLongSpace()
        fun onGlide(path: List<PointF>) {}
    }

    var listener: Listener? = null
    var theme: KbTheme = Themes.byName(context, null)
        set(v) { field = v; invalidate() }
    var layout: KbLayout? = null
        private set
    /** 0 = off, 1 = next letter, 2 = caps lock. */
    var shiftState = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var enterLabel = "↵"
        set(v) { field = v; invalidate() }
    var spaceLabel = ""
        set(v) { field = v; invalidate() }

    var keyHeightDp = 52f
    var gapDp = 5f
    var radiusDp = 8f
    var labelSp = 21f
    var showHints = true
    var preview = true
    var longPress = true
    var spaceCursor = true
    var backspaceSwipe = true
    var accel = true
    var haptics = true
    /** 0 light, 1 medium, 2 strong. */
    var hapticLevel = 1
    var sounds = false
    var soundVolume = 0.5f
    var soundPack = "system"
    var soundsInSilent = false
    /** "filled", "outline" or "borderless". */
    var keyStyle = "filled"
    /** "none", "pop", "ripple", "glow" or "fade". */
    var pressAnim = "pop"
    var labelFont: Typeface = Typeface.DEFAULT
        set(v) { field = v; fontNormal = Typeface.create(v, Typeface.NORMAL); fontBold = Typeface.create(v, Typeface.BOLD) }
    private var fontNormal: Typeface = Typeface.DEFAULT
    private var fontBold: Typeface = Typeface.DEFAULT_BOLD
    var boldLabels = false
    var shadow = false
    /** 0–255. Lower lets a background image show through. */
    var keyAlpha = 255
    /** Background is drawn by the parent (image), so don't paint the theme colour. */
    var transparentBg = false
    var split = false
    var glide = false
    var glideTrail = true
    /** "comet", "neon" or "rainbow". */
    var trailStyle = "comet"
    var longPressMs = 350L
    /** How far (in key widths) the finger travels before a swipe becomes a glide. */
    var glideStart = 0.9f
    /** Backspace repeat speed, 1 (slow) to 5 (fast). */
    var repeatSpeed = 3
    var swipeGestures = true
    var adaptive = false
    var trackpad = true
    private var trackpadMode = false
    private var cursorY = 0f
    /** Learned tap offsets per key, as a fraction of key size. Saved by the service. */
    val offsets = HashMap<String, PointF>()

    private val dp = resources.displayMetrics.density
    private fun spPx(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private val padX get() = 3 * dp
    private val padY get() = 4 * dp

    private class KR(val key: Key, val rect: RectF)
    private val keys = ArrayList<KR>()
    var unitWidth = 0f
        private set

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun applyLayout(l: KbLayout) {
        layout = l
        requestLayout()
        computeRects()
        invalidate()
    }

    fun refresh() {
        requestLayout()
        computeRects()
        invalidate()
    }

    fun desiredHeight(): Int = ((layout?.rows?.size ?: 4) * keyHeightDp * dp + 2 * padY).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), desiredHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = computeRects()

    private fun computeRects() {
        keys.clear()
        val l = layout ?: return
        if (width == 0) return
        val splitGap = if (split) width * 0.16f else 0f
        val avail = width - 2 * padX - splitGap
        val maxW = l.rows.maxOf { r -> r.sumOf { it.w.toDouble() } }.toFloat().coerceAtLeast(1f)
        unitWidth = avail / maxW
        val kh = keyHeightDp * dp
        val g = gapDp * dp
        l.rows.forEachIndexed { ri, row ->
            val rowW = row.sumOf { it.w.toDouble() }.toFloat().coerceAtLeast(0.01f)
            // Rows with special keys stretch to the edges; plain letter rows stay centred (like the ASDF row).
            val u = if (row.any { it.type != null || it.flick.isNotEmpty() }) avail / rowW else unitWidth
            var x = padX + (avail - rowW * u) / 2
            val y = padY + ri * kh
            var acc = 0f
            row.forEach { k ->
                val kw = k.w * u
                val startW = acc
                acc += k.w
                // Split: keys right of the row's middle shift over; a key across the middle stretches.
                val mid = rowW / 2
                val shiftL = if (split && startW >= mid - 0.001f) splitGap else 0f
                val extra = if (split && startW < mid - 0.001f && acc > mid + 0.001f) splitGap else 0f
                keys.add(KR(k, RectF(x + shiftL + g / 2, y + g / 2, x + shiftL + kw + extra - g / 2, y + kh - g / 2)))
                x += kw
            }
        }
    }

    /** Key centres of single-letter keys, used for typo distance and glide decoding. */
    fun keyCenters(): Map<Char, PointF> =
        keys.filter { it.key.type == null && it.key.out.length == 1 && it.key.flick.isEmpty() }
            .associate { it.key.out[0].lowercaseChar() to PointF(it.rect.centerX(), it.rect.centerY()) }

    // ---------- drawing ----------

    private fun isLetter(s: String) = s.codePointCount(0, s.length) == 1 && Character.isLetter(s.codePointAt(0))

    private fun applyShift(s: String) = if (shiftState > 0 && isLetter(s)) s.uppercase() else s

    private fun labelFor(k: Key): String = when (k.type) {
        "shift" -> when (shiftState) { 0 -> "⇧"; 1 -> "⬆"; else -> "⇪" }
        "backspace" -> "⌫"
        "space" -> k.label.ifEmpty { spaceLabel }
        "enter" -> k.label.ifEmpty { enterLabel }
        "symbols" -> k.label.ifEmpty { "?123" }
        "lang" -> k.label.ifEmpty { "🌐" }
        "emoji" -> k.label.ifEmpty { "☺" }
        "clipboard" -> k.label.ifEmpty { "📋" }
        "cursor_left" -> k.label.ifEmpty { "◀" }
        "cursor_right" -> k.label.ifEmpty { "▶" }
        "kana_mod" -> k.label.ifEmpty { "゛゜小" }
        "voice" -> k.label.ifEmpty { "🎤" }
        "settings" -> k.label.ifEmpty { "⚙" }
        else -> if (isLetter(k.out) && k.label == k.out) applyShift(k.label) else k.label
    }

    private fun spokenLabel(k: Key): String = when (k.type) {
        "shift" -> "Shift"
        "backspace" -> "Delete"
        "space" -> "Space"
        "enter" -> enterLabel.takeIf { it != "↵" } ?: "Enter"
        "symbols" -> if (k.label == "ABC") "Letters" else "Symbols"
        "lang" -> "Next language"
        "emoji" -> "Emoji"
        "cursor_left" -> "Cursor left"
        "cursor_right" -> "Cursor right"
        "kana_mod" -> "Dakuten"
        else -> labelFor(k)
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        if (!transparentBg) c.drawColor(t.bg)
        if (trackpadMode) {
            fill.color = t.fg; fill.alpha = 18
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            text.color = t.fg; text.alpha = 150; text.textSize = spPx(14f); text.typeface = fontNormal
            c.drawText("Slide to move the cursor", width / 2f, height / 2f, text)
            text.alpha = 255
            return
        }
        val r = radiusDp * dp
        val now = SystemClock.uptimeMillis()
        var animating = false
        for (kr in keys) {
            val k = kr.key
            val isMod = k.type != null && k.type != "space" && k.type != "enter"
            var color = when {
                k.type == "enter" -> t.acc
                isMod -> t.mod
                else -> t.key
            }
            val isPressed = kr === pressed && !optionsShowing && !gliding
            if (k.type == "shift" && shiftState == 2) color = KbTheme.mix(t.mod, t.acc, 0.45f)

            // Press animation progress (0 → 1 over its duration).
            val anim = anims[kr]
            var p = -1f
            if (anim != null) {
                p = ((now - anim) / ANIM_MS.toFloat()).coerceIn(0f, 1f)
                if (p >= 1f && !isPressed) anims.remove(kr) else animating = true
            }
            val rect = tmpRect.apply { set(kr.rect) }
            if (pressAnim == "pop" && p in 0f..1f) {
                val s = 1f + 0.09f * sin(Math.PI * p).toFloat()
                rect.inset(-(rect.width() * (s - 1)) / 2, -(rect.height() * (s - 1)) / 2)
            }

            val solid = keyStyle == "filled" || k.type == "enter"
            if (shadow && solid) {
                fill.color = KbTheme.mix(t.bg, 0xFF000000.toInt(), 0.45f)
                fill.alpha = keyAlpha
                c.drawRoundRect(rect.left, rect.top + 1.6f * dp, rect.right, rect.bottom + 1.6f * dp, r, r, fill)
            }
            if (solid || isPressed || (pressAnim == "fade" && p in 0f..1f)) {
                var fc = if (solid) color else t.key
                if (isPressed) fc = KbTheme.mix(if (solid) color else t.bg, t.fg, 0.18f)
                if (pressAnim == "fade" && p in 0f..1f) fc = KbTheme.mix(fc, t.acc, 0.35f * (1 - p))
                fill.color = fc
                fill.alpha = if (solid) keyAlpha else 255
                c.drawRoundRect(rect, r, r, fill)
            }
            if (keyStyle == "outline" && k.type != "enter") {
                stroke.color = if (isPressed) t.acc else t.border
                stroke.strokeWidth = (if (t.amoled) 1.5f else 1.2f) * dp
                c.drawRoundRect(rect, r, r, stroke)
            }
            if (pressAnim == "glow" && p in 0f..1f) {
                stroke.color = t.acc
                stroke.alpha = (255 * (1 - p)).toInt()
                stroke.strokeWidth = 2.2f * dp
                c.drawRoundRect(rect, r, r, stroke)
                stroke.alpha = 255
            }
            if (pressAnim == "ripple" && p in 0f..1f) {
                c.save()
                val clip = Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) }
                c.clipPath(clip)
                fill.color = t.fg
                fill.alpha = (70 * (1 - p)).toInt()
                val o = animOrigin[kr] ?: PointF(rect.centerX(), rect.centerY())
                c.drawCircle(o.x, o.y, hypot(rect.width(), rect.height()) * p, fill)
                c.restore()
            }

            val label = labelFor(k)
            text.color = when (k.type) {
                "enter" -> t.accFg
                null, "space" -> t.fg
                else -> t.modFg
            }
            if (k.label.isEmpty() && drawKeyIcon(c, k, rect, text.color)) {
                drawHint(c, k, rect, t)
                continue
            }
            val cps = label.codePointCount(0, label.length)
            val bold = k.type != null || boldLabels
            text.typeface = if (bold) fontBold else fontNormal
            text.textSize = spPx(labelSp) * when {
                k.type == "space" -> 0.6f
                cps > 2 -> 0.62f
                k.type != null -> 0.85f
                else -> 1f
            }
            val maxW = rect.width() - 6 * dp
            val tw = text.measureText(label)
            if (tw > maxW && tw > 0) text.textSize *= maxW / tw
            val ty = if (k.flick.size > 1) rect.centerY() - 3 * dp else rect.centerY()
            c.drawText(label, rect.centerX(), ty - (text.descent() + text.ascent()) / 2, text)

            drawHint(c, k, rect, t)
        }
        if (glideTrail && trail.isNotEmpty()) {
            while (trail.isNotEmpty() && now - trail[0].t > TRAIL_MS) trail.removeAt(0)
            for (i in 1 until trail.size) {
                val a = trail[i - 1]
                val b = trail[i]
                val f = 1f - ((now - b.t) / TRAIL_MS.toFloat()).coerceIn(0f, 1f)
                trailPaint.color = when (trailStyle) {
                    "rainbow" -> android.graphics.Color.HSVToColor(floatArrayOf(((b.t / 3L) % 360L).toFloat(), 0.75f, 1f))
                    else -> t.acc
                }
                if (trailStyle == "neon") {
                    trailPaint.alpha = (70 * f).toInt()
                    trailPaint.strokeWidth = (8f + 10f * f) * dp
                    c.drawLine(a.x, a.y, b.x, b.y, trailPaint)
                    trailPaint.color = KbTheme.mix(t.acc, 0xFFFFFFFF.toInt(), 0.65f)
                    trailPaint.alpha = (255 * f).toInt()
                    trailPaint.strokeWidth = (1.5f + 2f * f) * dp
                } else {
                    trailPaint.alpha = (235 * f).toInt()
                    trailPaint.strokeWidth = (2f + 5.5f * f) * dp
                }
                c.drawLine(a.x, a.y, b.x, b.y, trailPaint)
            }
            if (trail.isNotEmpty()) animating = true
        }
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawHint(c: Canvas, k: Key, rect: RectF, t: KbTheme) {
            if (k.flick.size > 1 && k.label.codePointCount(0, k.label.length) == 1) {
                hint.textAlign = Paint.Align.CENTER
                hint.color = t.fg
                hint.alpha = 120
                hint.textSize = spPx(labelSp) * 0.38f
                c.drawText(k.flick.drop(1).joinToString(""), rect.centerX(), rect.bottom - 5 * dp, hint)
                hint.textAlign = Paint.Align.RIGHT
            } else if (showHints && k.type == null && (k.long.isNotEmpty() || !k.hint.isNullOrEmpty()) && k.hint != "") {
                val h = k.hint ?: k.long[0]
                if (h.codePointCount(0, h.length) <= 2) {
                    hint.color = if (t.modFg != t.fg) t.modFg else t.fg
                    hint.alpha = if (t.modFg != t.fg) 220 else 130
                    hint.textSize = spPx(labelSp) * 0.45f
                    c.drawText(h, rect.right - 4 * dp, rect.top + hint.textSize + 2 * dp, hint)
                }
            }
            }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconPath = Path()

    /**
     * Drawn icons for special keys, on a 24-unit grid scaled with the letter size.
     * Returns false for keys that keep a text label (space, ?123, custom labels).
     */
    private fun drawKeyIcon(c: Canvas, k: Key, rect: RectF, color: Int): Boolean {
        val icon = when (k.type) {
            "shift" -> when (shiftState) { 0 -> "shift"; 1 -> "shift_on"; else -> "caps" }
            "backspace" -> "backspace"
            "lang" -> "globe"
            "emoji" -> "smile"
            "enter" -> when (enterLabel) {
                "Search" -> "search"; "Send" -> "send"; "Go", "Next" -> "arrow"; "Done" -> "done"; "Back" -> "arrow_back"
                else -> "return"
            }
            else -> return false
        }
        val size = minOf(rect.height() * 0.55f, rect.width() * 0.6f, spPx(labelSp) * 1.25f)
        c.save()
        c.translate(rect.centerX() - size / 2, rect.centerY() - size / 2)
        c.scale(size / 24f, size / 24f)
        val p = iconPaint
        p.color = color
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        val path = iconPath
        path.reset()
        fun arrowUp(bottom: Float) {
            path.moveTo(12f, 3.5f); path.lineTo(20.5f, 12f); path.lineTo(16f, 12f)
            path.lineTo(16f, bottom); path.lineTo(8f, bottom); path.lineTo(8f, 12f); path.lineTo(3.5f, 12f); path.close()
        }
        when (icon) {
            "shift" -> { arrowUp(20f); c.drawPath(path, p) }
            "shift_on" -> { arrowUp(20f); p.style = Paint.Style.FILL_AND_STROKE; c.drawPath(path, p) }
            "caps" -> {
                arrowUp(16f); p.style = Paint.Style.FILL_AND_STROKE; c.drawPath(path, p)
                c.drawRoundRect(8f, 18.5f, 16f, 21f, 1f, 1f, p)
            }
            "backspace" -> {
                path.moveTo(8.5f, 5f); path.lineTo(21f, 5f); path.lineTo(21f, 19f); path.lineTo(8.5f, 19f); path.lineTo(2.5f, 12f); path.close()
                c.drawPath(path, p)
                c.drawLine(11.5f, 9f, 17f, 15f, p); c.drawLine(17f, 9f, 11.5f, 15f, p)
            }
            "return" -> {
                path.moveTo(19f, 5f); path.lineTo(19f, 13f); path.lineTo(5.5f, 13f)
                path.moveTo(9.5f, 9f); path.lineTo(5.5f, 13f); path.lineTo(9.5f, 17f)
                c.drawPath(path, p)
            }
            "search" -> {
                p.strokeWidth = 2.3f
                c.drawCircle(10.5f, 10.5f, 6.5f, p); c.drawLine(15.3f, 15.3f, 20.5f, 20.5f, p)
            }
            "send" -> {
                path.moveTo(3f, 11f); path.lineTo(21f, 3.5f); path.lineTo(14f, 21f); path.lineTo(11f, 13f); path.close()
                c.drawPath(path, p)
                c.drawLine(11f, 13f, 21f, 3.5f, p)
            }
            "arrow", "arrow_back" -> {
                if (icon == "arrow_back") c.scale(-1f, 1f, 12f, 12f)
                c.drawLine(4f, 12f, 20f, 12f, p)
                path.moveTo(14f, 6f); path.lineTo(20f, 12f); path.lineTo(14f, 18f)
                c.drawPath(path, p)
            }
            "smile" -> {
                c.drawCircle(12f, 12f, 8.8f, p)
                p.style = Paint.Style.FILL
                c.drawCircle(9f, 10f, 1.3f, p); c.drawCircle(15f, 10f, 1.3f, p)
                p.style = Paint.Style.STROKE
                c.drawArc(8f, 10.5f, 16f, 17f, 25f, 130f, false, p)
            }
            "globe" -> {
                // Plain line globe: outline, one meridian, equator.
                p.strokeWidth = 1.8f
                c.drawCircle(12f, 12f, 9f, p)
                c.drawOval(8f, 3f, 16f, 21f, p)
                c.drawLine(3f, 12f, 21f, 12f, p)
            }
            "done" -> {
                p.strokeWidth = 2.4f
                path.moveTo(4.5f, 12.5f); path.lineTo(9.5f, 17.5f); path.lineTo(19.5f, 6.5f)
                c.drawPath(path, p)
            }
        }
        c.restore()
        return true
    }

    // ---------- touch ----------

    private val anims = HashMap<KR, Long>()
    private val animOrigin = HashMap<KR, PointF>()
    private var pressed: KR? = null
    private var activeId = -1
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var cursorX = 0f
    private var longFired = false
    private var swiping = false
    private var repeated = false
    private var gliding = false
    /** Points drawn as the trail: only the last ~0.25 s, so it stays short and cheap like a comet. */
    private val trail = ArrayList<TrailPoint>()
    /** Every point of the swipe, used only for decoding the word on release. */
    private val glidePath = ArrayList<PointF>()
    private class TrailPoint(val x: Float, val y: Float, val t: Long)
    private val tmpRect = RectF()
    private var repeatDelay = 0L
    private var repeatCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val longRunnable = Runnable { onLong() }
    private val repeatRunnable = object : Runnable {
        override fun run() {
            repeated = true
            repeatCount++
            // After about a second of holding, delete whole words instead of letters.
            if (accel && repeatCount > 14) listener?.onDeleteWords(1) else listener?.onSpecial("backspace")
            feedback()
            repeatDelay = if (accel && repeatCount > 14) 180L
                else if (accel) max(minRepeat(), (repeatDelay * 0.88f).toLong()) else baseRepeat()
            handler.postDelayed(this, repeatDelay)
        }
    }

    private fun keyAt(x: Float, y: Float): KR? {
        if (adaptive && offsets.isNotEmpty()) {
            // Pick the letter whose learned "real centre" is closest, among keys near the touch.
            val near = keys.filter { it.key.type == null && it.rect.width() > 0 &&
                x > it.rect.left - it.rect.width() * 0.5f && x < it.rect.right + it.rect.width() * 0.5f &&
                y > it.rect.top - it.rect.height() * 0.4f && y < it.rect.bottom + it.rect.height() * 0.4f }
            if (near.size > 1 && keys.firstOrNull { it.rect.contains(x, y) }?.key?.type == null) {
                return near.minByOrNull {
                    val o = offsets[it.key.out]
                    val cx = it.rect.centerX() + (o?.x ?: 0f) * it.rect.width()
                    val cy = it.rect.centerY() + (o?.y ?: 0f) * it.rect.height()
                    val dx = (x - cx) / it.rect.width()
                    val dy = (y - cy) / it.rect.height()
                    dx * dx + dy * dy
                }
            }
        }
        keys.firstOrNull { it.rect.contains(x, y) }?.let { return it }
        // Gaps between keys belong to the nearest key.
        return keys.minByOrNull {
            val dx = max(0f, max(it.rect.left - x, x - it.rect.right))
            val dy = max(0f, max(it.rect.top - y, y - it.rect.bottom))
            dx * dx + dy * dy
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Fast two-thumb typing: a new finger commits the previous key immediately.
                if (pressed != null) release(true)
                val i = e.actionIndex
                start(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activeId)
                if (i >= 0) {
                    if (gliding) for (h in 0 until e.historySize) addGlidePoint(e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalEventTime(h))
                    move(e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == activeId) release(true)
            }
            MotionEvent.ACTION_CANCEL -> release(false)
        }
        return true
    }

    private fun start(id: Int, x: Float, y: Float) {
        activeId = id
        downX = x; downY = y; lastX = x; lastY = y; cursorX = x
        longFired = false; swiping = false; repeated = false; gliding = false; trackpadMode = false
        val kr = keyAt(x, y) ?: return
        pressed = kr
        if (pressAnim != "none") {
            anims[kr] = SystemClock.uptimeMillis()
            animOrigin[kr] = PointF(x, y)
        }
        feedback()
        showPreview()
        invalidate()
        val k = kr.key
        if (k.type == "backspace") {
            repeatDelay = if (accel) baseRepeat() + 30 else baseRepeat()
            repeatCount = 0
            handler.postDelayed(repeatRunnable, longPressMs + 50)
        }
        if (k.flick.isEmpty() && ((k.long.isNotEmpty() && longPress) || k.type == "space" || k.type == "lang")) {
            handler.postDelayed(longRunnable, longPressMs)
        }
    }

    private fun flickIndex(dx: Float, dy: Float): Int {
        if (hypot(dx, dy) < 18 * dp) return 0
        return if (abs(dx) > abs(dy)) (if (dx < 0) 1 else 3) else (if (dy < 0) 2 else 4)
    }

    private fun move(x: Float, y: Float) {
        val kr = pressed ?: return
        lastX = x; lastY = y
        if (optionsShowing) {
            optionsView.select(x + locX(), y + locY())
            return
        }
        if (trackpadMode) {
            val stepX = 9 * dp
            val stepY = 26 * dp
            val sx = ((x - cursorX) / stepX).toInt()
            if (sx != 0) { listener?.onCursorMove(sx); cursorX += sx * stepX }
            val sy = ((y - cursorY) / stepY).toInt()
            if (sy != 0) {
                repeat(kotlin.math.abs(sy)) { listener?.onSpecial(if (sy < 0) "cursor_up" else "cursor_down") }
                cursorY += sy * stepY
            }
            return
        }
        val dx = x - downX
        val dy = y - downY
        if (kr.key.flick.isNotEmpty()) {
            showPreview(kr.key.flick.getOrNull(flickIndex(dx, dy)) ?: kr.key.flick[0])
            return
        }
        if (gliding) {
            addGlidePoint(x, y, SystemClock.uptimeMillis())
            postInvalidateOnAnimation()
            return
        }
        when (kr.key.type) {
            "space" -> if (spaceCursor && !longFired && (swiping || abs(dx) > 14 * dp)) {
                if (!swiping) {
                    swiping = true
                    cursorX = x
                    handler.removeCallbacks(longRunnable)
                }
                val step = 9 * dp
                val steps = ((x - cursorX) / step).toInt()
                if (steps != 0) {
                    listener?.onCursorMove(steps)
                    cursorX += steps * step
                }
            }
            "backspace" -> if (backspaceSwipe && !repeated && dx < -24 * dp) {
                swiping = true
                handler.removeCallbacks(repeatRunnable)
            }
            null -> if (!longFired) {
                // Glide starts once the finger travels about a key width from a letter key.
                if (glide && isLetter(kr.key.out) && hypot(dx, dy) > unitWidth * glideStart && !swipeUpLikely(dx, dy)) {
                    gliding = true
                    handler.removeCallbacks(longRunnable)
                    hidePreview()
                    trail.clear()
                    glidePath.clear()
                    val now = SystemClock.uptimeMillis()
                    addGlidePoint(downX, downY, now)
                    addGlidePoint(x, y, now)
                    postInvalidateOnAnimation()
                    return
                }
                // A vertical swipe up keeps the original key (it becomes a symbol gesture on release).
                if (swipeUpLikely(dx, dy) && -dy > kr.rect.height() * 0.3f) { handler.removeCallbacks(longRunnable); hidePreview(); return }
                val nk = keyAt(x, y)
                if (nk != null && nk !== kr && nk.key.type == null) {
                    pressed = nk
                    handler.removeCallbacks(longRunnable)
                    if (nk.key.long.isNotEmpty() && longPress) handler.postDelayed(longRunnable, longPressMs)
                    showPreview()
                    invalidate()
                }
            }
        }
    }

    private fun release(commit: Boolean) {
        handler.removeCallbacks(longRunnable)
        handler.removeCallbacks(repeatRunnable)
        hidePreview()
        val kr = pressed
        pressed = null
        activeId = -1
        invalidate()
        if (trackpadMode) { trackpadMode = false; return }
        if (kr == null) return
        if (gliding) {
            gliding = false
            if (commit && glidePath.size > 2) listener?.onGlide(ArrayList(glidePath))
            glidePath.clear()
            if (!glideTrail) trail.clear()
            invalidate()
            return
        }
        if (optionsShowing) {
            val s = optionsView.selected()
            dismissOptions()
            if (commit && s != null) listener?.onText(applyShift(s))
            return
        }
        if (!commit || longFired || repeated) return
        if (kr.key.flick.isNotEmpty()) {
            listener?.onText(kr.key.flick.getOrNull(flickIndex(lastX - downX, lastY - downY)) ?: kr.key.flick[0])
            return
        }
        if (swiping) {
            if (kr.key.type == "backspace") listener?.onDeleteWords(((downX - lastX) / (45 * dp)).toInt() + 1)
            return
        }
        val dx = lastX - downX
        val dy = lastY - downY
        if (swipeGestures && kr.key.type == "space" && abs(dy) > kr.rect.height() * 0.6f && abs(dx) < abs(dy)) {
            listener?.onSpecial(if (dy < 0) "lang" else "hide")
            return
        }
        if (swipeGestures && kr.key.type == null && swipeUpLikely(dx, dy) && -dy > kr.rect.height() * 0.45f) {
            // Swipe up on a letter types its first long-press option (symbol or digit).
            val alt = kr.key.long.firstOrNull() ?: kr.key.out.uppercase()
            listener?.onText(alt)
            return
        }
        if (adaptive && kr.key.type == null && kr.key.out.length == 1) learnAim(kr, downX, downY)
        fire(kr.key)
    }

    private fun swipeUpLikely(dx: Float, dy: Float) = swipeGestures && dy < 0 && abs(dx) < abs(dy) * 0.6f

    private fun baseRepeat() = (110 - repeatSpeed * 18).toLong().coerceAtLeast(25)
    private fun minRepeat() = (40 - repeatSpeed * 6).toLong().coerceAtLeast(12)

    /** Slowly moves this key's remembered centre toward where you actually tapped. */
    private fun learnAim(kr: KR, x: Float, y: Float) {
        val ox = ((x - kr.rect.centerX()) / kr.rect.width()).coerceIn(-0.5f, 0.5f)
        val oy = ((y - kr.rect.centerY()) / kr.rect.height()).coerceIn(-0.5f, 0.5f)
        val o = offsets.getOrPut(kr.key.out) { PointF() }
        o.x = (o.x * 0.94f + ox * 0.06f).coerceIn(-0.3f, 0.3f)
        o.y = (o.y * 0.94f + oy * 0.06f).coerceIn(-0.3f, 0.3f)
        aimChanges++
    }

    /** Counts learned taps so the service knows when to save offsets. */
    var aimChanges = 0

    private fun fire(k: Key) {
        if (k.type == null) listener?.onText(applyShift(k.out)) else listener?.onSpecial(k.type)
    }

    private fun onLong() {
        val kr = pressed ?: return
        longFired = true
        hidePreview()
        when (kr.key.type) {
            "space" -> {
                if (trackpad) {
                    // Trackpad: from now on, sliding moves the cursor in any direction.
                    trackpadMode = true
                    cursorX = lastX; cursorY = lastY
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    invalidate()
                } else listener?.onLongSpace()
                return
            }
            "lang" -> { listener?.onSpecial("lang_panel"); return }
        }
        showOptions(kr)
    }

    private val vibrator: android.os.Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
    }

    private fun feedback() {
        if (haptics) {
            // Drive the motor directly so it works even when the phone's system touch vibration is off.
            val v = vibrator
            if (v != null && v.hasVibrator()) {
                val effect = when (hapticLevel) {
                    0 -> android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_TICK)
                    2 -> android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK)
                    else -> android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK)
                }
                v.vibrate(effect)
            } else performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (sounds) {
            if (soundPack == "system" && !soundsInSilent) (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, soundVolume)
            else Sounds.play(context, if (soundPack == "system") "soft" else soundPack, soundVolume, soundsInSilent)
        }
    }

    // ---------- screen reader: touch to hear, lift to type ----------

    private var hovered: KR? = null

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!am.isTouchExplorationEnabled) return super.onHoverEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val kr = keyAt(e.x, e.y)
                if (kr != null && kr !== hovered) {
                    hovered = kr
                    contentDescription = spokenLabel(kr.key)
                    @Suppress("DEPRECATION")
                    announceForAccessibility(spokenLabel(kr.key))
                }
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                hovered?.let { if (it.rect.contains(e.x, e.y)) fire(it.key) }
                hovered = null
            }
        }
        return true
    }

    // ---------- popups ----------

    private fun locX(): Int { val l = IntArray(2); getLocationInWindow(l); return l[0] }
    private fun locY(): Int { val l = IntArray(2); getLocationInWindow(l); return l[1] }

    private fun bubble(color: Int) = GradientDrawable().apply {
        cornerRadius = 10 * dp
        setColor(color)
    }

    private val previewText = TextView(context).apply { gravity = Gravity.CENTER }
    private val previewWin = PopupWindow(previewText).apply {
        isClippingEnabled = false
        isTouchable = false
        animationStyle = 0
    }

    private fun showPreview(override: String? = null) {
        val kr = pressed
        if (!preview || kr == null || (kr.key.type != null) || windowToken == null) { hidePreview(); return }
        previewText.text = override ?: labelFor(kr.key)
        previewText.setTextColor(theme.fg)
        previewText.typeface = labelFont
        previewText.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp * 1.25f)
        previewText.background = bubble(if (theme.amoled) KbTheme.mix(theme.bg, theme.fg, 0.14f) else KbTheme.mix(theme.key, theme.fg, 0.08f))
        val w = max((kr.rect.width() * 1.3f).toInt(), (44 * dp).toInt())
        val h = (kr.rect.height() * 1.25f).toInt()
        val x = locX() + (kr.rect.centerX() - w / 2).toInt()
        val y = locY() + (kr.rect.top - h - 6 * dp).toInt()
        if (previewWin.isShowing) previewWin.update(x, y, w, h)
        else {
            previewWin.width = w
            previewWin.height = h
            previewWin.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
        }
    }

    private fun hidePreview() { if (previewWin.isShowing) previewWin.dismiss() }

    private inner class OptionsView(ctx: Context) : View(ctx) {
        var opts: List<String> = emptyList()
        var cols = 1
        var rows = 1
        var cellW = 0f
        var cellH = 0f
        var sel = 0
        var screenX = 0
        var screenY = 0
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)

        /** Up to 8 per row; extra options wrap into rows above, like the Emoji Keyboard. Returns (w, h). */
        fun setup(o: List<String>, minW: Float, rowH: Float): Pair<Int, Int> {
            opts = o
            p.textSize = spPx(labelSp)
            p.typeface = labelFont
            rows = (o.size + 7) / 8
            cols = (o.size + rows - 1) / rows
            cellW = maxOf(minW, o.maxOf { p.measureText(it) } + 18 * dp)
            cellH = rowH
            sel = 0
            return (cols * cellW + 8 * dp).toInt() to (rows * cellH).toInt()
        }

        private fun left(i: Int) = 4 * dp + (i % cols) * cellW
        private fun top(i: Int) = (rows - 1 - i / cols) * cellH

        fun select(windowX: Float, windowY: Float) {
            val col = ((windowX - screenX - 4 * dp) / cellW).toInt().coerceIn(0, cols - 1)
            val y = windowY - screenY
            val fromTop = if (y < 0) 0 else (y / cellH).toInt().coerceIn(0, rows - 1)
            val s = ((rows - 1 - fromTop) * cols + col).coerceIn(0, opts.size - 1)
            if (s != sel) { sel = s; invalidate() }
        }

        fun selected() = opts.getOrNull(sel)

        override fun onDraw(c: Canvas) {
            val t = theme
            bg.color = if (t.amoled) KbTheme.mix(t.bg, t.fg, 0.12f) else t.mod
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12 * dp, 12 * dp, bg)
            opts.forEachIndexed { i, o ->
                val l = left(i)
                val tp = top(i)
                if (i == sel) {
                    bg.color = t.acc
                    c.drawRoundRect(l, tp + 4 * dp, l + cellW, tp + cellH - 4 * dp, 8 * dp, 8 * dp, bg)
                }
                p.color = if (i == sel) t.accFg else t.fg
                c.drawText(o, l + cellW / 2, tp + cellH / 2f - (p.descent() + p.ascent()) / 2, p)
            }
        }
    }

    private val optionsView = OptionsView(context)
    private val optionsWin = PopupWindow(optionsView).apply {
        isClippingEnabled = false
        isTouchable = false
        animationStyle = 0
    }
    private val optionsShowing get() = optionsWin.isShowing

    private fun showOptions(kr: KR) {
        if (windowToken == null || kr.key.long.isEmpty()) return
        val opts = kr.key.long.map { applyShift(it) }
        val (w, h) = optionsView.setup(opts, max(kr.rect.width(), 34 * dp), kr.rect.height() * 1.15f)
        val rootW = rootView.width
        var x = locX() + kr.rect.left.toInt() - (4 * dp).toInt()
        x = x.coerceIn(0, max(0, rootW - w))
        val y = locY() + (kr.rect.top - h - 6 * dp).toInt()
        optionsView.screenX = x
        optionsView.screenY = y
        optionsWin.width = w
        optionsWin.height = h
        optionsWin.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
        optionsView.select(lastX + locX(), lastY + locY())
        optionsView.invalidate()
        invalidate()
    }

    private fun dismissOptions() { if (optionsWin.isShowing) optionsWin.dismiss() }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        hidePreview()
        dismissOptions()
    }

    private fun addGlidePoint(x: Float, y: Float, t: Long) {
        glidePath.add(PointF(x, y))
        if (glideTrail) trail.add(TrailPoint(x, y, t))
    }

    companion object {
        private const val ANIM_MS = 220L
        private const val TRAIL_MS = 240L
    }
}
