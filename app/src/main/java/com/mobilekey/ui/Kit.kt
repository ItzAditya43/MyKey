package com.mobilekey.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.mobilekey.KbTheme

/** Settings palette, separate from keyboard themes. */
class Palette(
    val bg: Int, val surface: Int, val surface2: Int, val line: Int,
    val fg: Int, val muted: Int, val accent: Int, val accentFg: Int,
    val danger: Int, val dark: Boolean,
) {
    companion object {
        fun of(ctx: Context): Palette {
            val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return if (dark) Palette(
                c("#0A0C0B"), c("#141716"), c("#1D2120"), c("#262B29"),
                c("#F1F4F2"), c("#8D9792"), c("#3FD0B0"), c("#03211A"), c("#FF7B6B"), true,
            ) else Palette(
                c("#F3F4F1"), c("#FFFFFF"), c("#ECEEEA"), c("#E2E5E0"),
                c("#121815"), c("#6A736E"), c("#0E7C66"), c("#FFFFFF"), c("#C2412D"), false,
            )
        }
        private fun c(s: String) = Color.parseColor(s)
    }
}

object Fonts {
    private val cache = HashMap<String, Typeface>()
    fun display(ctx: Context, weight: Int = 760): Typeface = cache.getOrPut("d$weight") {
        Typeface.Builder(ctx.assets, "display.ttf")
            .setFontVariationSettings("'wght' $weight, 'opsz' 36, 'wdth' 88").build()
    }
    fun body(ctx: Context, weight: Int = 420): Typeface = cache.getOrPut("b$weight") {
        Typeface.Builder(ctx.assets, "body.ttf").setFontVariationSettings("'wght' $weight").build()
    }
}

fun blend(a: Int, b: Int, f: Float): Int {
    fun ch(x: Int, s: Int) = (x shr s) and 0xff
    fun mix(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * f).toInt()
    return (0xff shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

fun withAlpha(c: Int, a: Int) = (c and 0x00ffffff) or (a shl 24)

/** Animated on/off switch. */
class Toggle(ctx: Context, private val p: Palette) : View(ctx) {
    var checked = false
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private var pos = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    init {
        isClickable = true
        setOnClickListener { toggle() }
    }

    fun set(v: Boolean, animate: Boolean) {
        checked = v
        anim?.cancel()
        val target = if (v) 1f else 0f
        if (!animate) { pos = target; invalidate(); return }
        anim = ValueAnimator.ofFloat(pos, target).apply {
            duration = 200
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun toggle() {
        if (!isEnabled) return
        set(!checked, true)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onChange?.invoke(checked)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((50 * d).toInt(), (30 * d).toInt())

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        paint.color = blend(p.line, p.accent, pos)
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, paint)
        val r = h / 2 - 3 * d
        val cx = h / 2 + (w - h) * pos
        paint.color = blend(p.surface, p.accentFg, pos)
        c.drawCircle(cx, h / 2, r, paint)
    }
}

/** A miniature keyboard drawn in a theme's colours, used as a theme picker card. */
class ThemeSwatch(ctx: Context, val theme: KbTheme, private val p: Palette) : View(ctx) {
    var picked = false
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((116 * d).toInt(), (78 * d).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val inset = 3 * d
        val r = RectF(inset, inset, w - inset, h - inset)
        paint.color = theme.bg
        c.drawRoundRect(r, 14 * d, 14 * d, paint)
        val rows = listOf(10, 9, 7)
        val kh = (r.height() - 14 * d) / 4
        val gap = 2.2f * d
        rows.forEachIndexed { ri, n ->
            val kw = (r.width() - 12 * d) / 10
            val start = r.left + 6 * d + (10 - n) * kw / 2
            for (i in 0 until n) {
                paint.color = theme.key
                val x = start + i * kw
                val y = r.top + 6 * d + ri * kh
                c.drawRoundRect(x + gap / 2, y + gap / 2, x + kw - gap / 2, y + kh - gap / 2, 2.5f * d, 2.5f * d, paint)
            }
        }
        val y = r.top + 6 * d + 3 * kh
        val kw = (r.width() - 12 * d) / 10
        paint.color = theme.mod
        c.drawRoundRect(r.left + 6 * d + gap / 2, y + gap / 2, r.left + 6 * d + kw * 2 - gap / 2, y + kh - gap / 2, 2.5f * d, 2.5f * d, paint)
        paint.color = theme.key
        c.drawRoundRect(r.left + 6 * d + kw * 2 + gap / 2, y + gap / 2, r.left + 6 * d + kw * 7.6f - gap / 2, y + kh - gap / 2, 2.5f * d, 2.5f * d, paint)
        paint.color = theme.acc
        c.drawRoundRect(r.left + 6 * d + kw * 7.6f + gap / 2, y + gap / 2, r.right - 6 * d - gap / 2, y + kh - gap / 2, 2.5f * d, 2.5f * d, paint)
        if (picked) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * d
            paint.color = p.accent
            c.drawRoundRect(RectF(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d), 16 * d, 16 * d, paint)
            // Check badge in the corner of the selected theme.
            paint.style = Paint.Style.FILL
            c.drawCircle(w - 13 * d, 13 * d, 9 * d, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * d
            paint.color = p.accentFg
            paint.strokeCap = Paint.Cap.ROUND
            c.drawLine(w - 17 * d, 13 * d, w - 14 * d, 16 * d, paint)
            c.drawLine(w - 14 * d, 16 * d, w - 9 * d, 10 * d, paint)
            paint.style = Paint.Style.FILL
        }
    }
}

/** View builders so every screen shares the same spacing, type and surfaces. */
class Kit(val ctx: Context, val p: Palette) {
    val density = ctx.resources.displayMetrics.density
    fun dp(v: Float) = (v * density).toInt()
    fun dp(v: Int) = dp(v.toFloat())

    fun rounded(color: Int, radius: Float, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = radius * density
        setColor(color)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    fun ripple(color: Int, radius: Float, stroke: Int = 0) = RippleDrawable(
        ColorStateList.valueOf(withAlpha(p.fg, 0x1f)), rounded(color, radius, stroke), rounded(Color.WHITE, radius),
    )

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    fun column(pad: Int = 0) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    fun row() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun text(s: CharSequence, size: Float = 15f, color: Int = p.fg, weight: Int = 420, display: Boolean = false) =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            typeface = if (display) Fonts.display(ctx, weight) else Fonts.body(ctx, weight)
            setLineSpacing(0f, 1.15f)
        }

    fun eyebrow(s: String) = text(s.uppercase(), 12f, p.muted, 620).apply {
        letterSpacing = 0.09f
        setPadding(dp(18), dp(26), dp(18), dp(8))
    }

    fun card() = column().apply {
        background = rounded(p.surface, 20f)
        setPadding(dp(18), dp(2), dp(18), dp(2))
        clipToOutline = true
    }

    fun divider() = View(ctx).apply { setBackgroundColor(p.line) }

    fun addDivided(parent: LinearLayout, child: View) {
        if (parent.childCount > 0) parent.addView(divider().apply { tag = "div" }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            // Rows with an icon tile get a divider that starts after the icon.
            if (child.tag == "inset") leftMargin = dp(46)
        })
        parent.addView(child, lp())
    }

    fun toggleRow(title: String, desc: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): LinearLayout {
        val r = row().apply { setPadding(0, dp(13), 0, dp(13)); minimumHeight = dp(58) }
        val texts = column()
        texts.addView(text(title, 16f, p.fg, 540))
        if (desc.isNotEmpty()) texts.addView(text(desc, 13.5f, p.muted), lp(top = 1))
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(14) })
        val t = Toggle(ctx, p).apply {
            set(checked, false)
            isEnabled = enabled
            this.onChange = onChange
        }
        r.addView(t)
        if (enabled) r.setOnClickListener { t.toggle() } else r.alpha = 0.45f
        return r
    }

    fun navRow(title: String, value: String, onClick: () -> Unit) = row().apply {
        setPadding(0, dp(16), 0, dp(16))
        addView(text(title, 16f, p.fg, 520), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text("$value  ›", 14f, p.muted))
        setOnClickListener { onClick() }
    }

    fun slider(title: String, unit: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit): LinearLayout {
        val box = column().apply { setPadding(0, dp(14), 0, dp(12)) }
        val head = row()
        head.addView(text(title, 16f, p.fg, 540), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueText = text("$value $unit".trim(), 14f, p.accent, 680)
        head.addView(valueText)
        box.addView(head)
        box.addView(Slider(ctx, p, min, max, value) { v -> valueText.text = "$v $unit".trim(); onChange(v) }, lp(h = dp(36), top = 6))
        return box
    }

    /** Segmented control for 2–4 short choices, with a sliding highlight. */
    fun segmented(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit): View {
        val bar = FrameLayout(ctx).apply { background = rounded(p.surface2, 14f); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val pill = View(ctx).apply { background = rounded(p.surface, 11f); elevation = dp(2).toFloat() }
        val row = row()
        bar.addView(pill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        bar.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        var sel = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
        val labels = ArrayList<TextView>()
        fun place(animate: Boolean) {
            val w = (row.width / options.size.toFloat())
            pill.layoutParams = (pill.layoutParams as FrameLayout.LayoutParams).apply { width = w.toInt() }
            if (animate) pill.animate().translationX(w * sel).setDuration(220).setInterpolator(DecelerateInterpolator(2f)).start()
            else pill.translationX = w * sel
            labels.forEachIndexed { i, t -> t.setTextColor(if (i == sel) p.fg else p.muted); t.typeface = Fonts.body(ctx, if (i == sel) 650 else 500) }
        }
        options.forEachIndexed { i, (id, label) ->
            val t = text(label, 14f, p.muted, 500).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { if (sel != i) { sel = i; place(true); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onPick(id) } }
            }
            labels.add(t)
            row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        row.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) row.post { place(false) } }
        return bar
    }

    /** Rounded coloured square with a line icon, for list rows. */
    fun iconTile(icon: String, tint: Int, size: Int = 32): View = IconView(ctx, icon, Color.WHITE).apply {
        background = rounded(tint, 9f)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    /** Settings list row: icon tile, title, current value, chevron. */
    fun linkRow(icon: String, tint: Int, title: String, value: String, onClick: () -> Unit) = row().apply {
        tag = "inset"
        setPadding(0, dp(10), 0, dp(10))
        minimumHeight = dp(56)
        addView(iconTile(icon, tint))
        val texts = column().apply { setPadding(dp(14), 0, dp(8), 0) }
        texts.addView(text(title, 16f, p.fg, 560))
        if (value.isNotEmpty()) texts.addView(text(value, 13f, p.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text("›", 22f, p.line, 400))
        setOnClickListener { onClick() }
    }

    fun button(label: String, kind: String = "primary", onClick: (TextView) -> Unit) = text(label, 15f, when (kind) {
        "primary" -> p.accentFg
        "danger" -> p.danger
        else -> p.fg
    }, 620).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(13), dp(18), dp(13))
        background = when (kind) {
            "primary" -> ripple(p.accent, 16f)
            "danger" -> ripple(withAlpha(p.danger, 0x1c), 16f)
            else -> ripple(p.surface2, 16f)
        }
        setOnClickListener { onClick(this) }
    }

    fun chip(label: String, selected: Boolean, onClick: () -> Unit) = text(label, 14f, if (selected) p.accentFg else p.fg, 560).apply {
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = ripple(if (selected) p.accent else p.surface2, 99f)
        setOnClickListener { onClick() }
    }

    fun chipRow(items: List<View>) = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        val r = row()
        items.forEachIndexed { i, v -> r.addView(v, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(8) }) }
        addView(r)
    }

    fun field(hint: String, mono: Boolean = false) = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(p.muted)
        setTextColor(p.fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (mono) 12.5f else 15.5f)
        typeface = if (mono) Typeface.MONOSPACE else Fonts.body(ctx)
        background = rounded(p.surface2, 14f)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = !mono
        ellipsize = if (mono) null else TextUtils.TruncateAt.END
    }

    fun badge(label: String, color: Int = p.muted) = text(label, 11f, color, 650).apply {
        letterSpacing = 0.06f
        background = rounded(withAlpha(color, 0x22), 99f)
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }
}

/** Smooth slider: rounded track, accent fill, soft white thumb that grows while dragging. */
class Slider(ctx: Context, private val p: Palette, private val min: Int, private val max: Int, value: Int, private val onChange: (Int) -> Unit) : View(ctx) {
    private var v = value.coerceIn(min, max)
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragging = false
    private var grow = 0f

    init { isClickable = true }

    private fun xFor(value: Int): Float {
        val pad = 12 * d
        return pad + (width - 2 * pad) * (value - min) / (max - min).toFloat().coerceAtLeast(1f)
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val pad = 12 * d
        paint.color = p.line
        c.drawRoundRect(pad, cy - 3 * d, width - pad, cy + 3 * d, 3 * d, 3 * d, paint)
        val x = xFor(v)
        paint.color = p.accent
        c.drawRoundRect(pad, cy - 3 * d, x, cy + 3 * d, 3 * d, 3 * d, paint)
        val r = (10 + 3 * grow) * d
        paint.color = 0x33000000
        c.drawCircle(x, cy + 1.5f * d, r + 1 * d, paint)
        paint.color = if (p.dark) 0xFFF1F4F2.toInt() else Color.WHITE
        c.drawCircle(x, cy, r, paint)
        paint.color = p.accent
        c.drawCircle(x, cy, 3.5f * d, paint)
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; animateGrow(1f); update(e.x) }
            android.view.MotionEvent.ACTION_MOVE -> update(e.x)
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { dragging = false; animateGrow(0f); parent?.requestDisallowInterceptTouchEvent(false) }
        }
        return true
    }

    private fun update(x: Float) {
        val pad = 12 * d
        val nv = (min + ((x - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * (max - min) + 0.5f).toInt()
        if (nv != v) {
            v = nv
            if ((max - min) <= 40 || v % 2 == 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange(v)
            invalidate()
        }
    }

    private fun animateGrow(to: Float) {
        ValueAnimator.ofFloat(grow, to).apply {
            duration = 140
            addUpdateListener { grow = it.animatedValue as Float; invalidate() }
            start()
        }
    }
}

/**
 * Drag-to-reorder for the children of a LinearLayout (vertical or horizontal).
 * Call [attach] on each child's drag handle; [onMoved] gets (from, to) when an item is dropped in a new place.
 */
class DragReorder(private val list: LinearLayout, private val skip: (View) -> Boolean = { false }, private val onMoved: (Int, Int) -> Unit) {
    private val vertical = list.orientation == LinearLayout.VERTICAL

    fun attach(handle: View, item: View, immediate: Boolean) {
        var start = 0f
        var startIndex = -1
        var dragging = false
        val longPress = Runnable { begin(item); dragging = true }
        handle.setOnTouchListener { v, e ->
            val raw = if (vertical) e.rawY else e.rawX
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    start = raw; startIndex = items().indexOf(item)
                    if (immediate) { begin(item); dragging = true } else v.postDelayed(longPress, 260)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!dragging) { if (kotlin.math.abs(raw - start) > 12 * list.resources.displayMetrics.density) v.removeCallbacks(longPress); return@setOnTouchListener immediate }
                    list.parent?.requestDisallowInterceptTouchEvent(true)
                    var offset = raw - start
                    val its = items()
                    var idx = its.indexOf(item)
                    // Swap with a neighbour once the dragged item passes its middle.
                    while (true) {
                        val next = its.getOrNull(idx + 1)
                        val prev = its.getOrNull(idx - 1)
                        if (offset > 0 && next != null && offset > size(next) / 2f) {
                            swap(item, next); offset -= size(next); start += size(next); idx++
                        } else if (offset < 0 && prev != null && -offset > size(prev) / 2f) {
                            swap(item, prev); offset += size(prev); start -= size(prev); idx--
                        } else break
                    }
                    if (vertical) item.translationY = offset else item.translationX = offset
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    if (dragging) {
                        dragging = false
                        item.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(160).start()
                        item.elevation = 0f
                        val end = items().indexOf(item)
                        if (end != startIndex && startIndex >= 0) onMoved(startIndex, end)
                        true
                    } else {
                        if (!immediate && e.actionMasked == android.view.MotionEvent.ACTION_UP) v.performClick()
                        immediate
                    }
                }
                else -> false
            }
        }
    }

    private fun items() = (0 until list.childCount).map { list.getChildAt(it) }.filter { !skip(it) }
    private fun size(v: View) = (if (vertical) v.height else v.width).toFloat() + gap(v)
    private fun gap(v: View) = (v.layoutParams as? LinearLayout.LayoutParams)?.let { if (vertical) it.topMargin + it.bottomMargin else it.leftMargin + it.rightMargin }?.toFloat() ?: 0f

    private fun begin(item: View) {
        item.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        item.elevation = 12 * list.resources.displayMetrics.density
        item.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start()
    }

    /** Moves [item] to where [other] is, in the parent layout. */
    private fun swap(item: View, other: View) {
        val i = list.indexOfChild(other)
        list.removeView(item)
        list.addView(item, i)
    }
}
