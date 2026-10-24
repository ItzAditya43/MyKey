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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
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
                c("#0E1210"), c("#161C19"), c("#1F2723"), c("#29332E"),
                c("#ECF2EE"), c("#8E9C94"), c("#3FD0B0"), c("#06231C"), c("#FF8A7A"), true,
            ) else Palette(
                c("#F2F4F1"), c("#FFFFFF"), c("#E7ECE6"), c("#DADFD8"),
                c("#16201B"), c("#5B6961"), c("#0E7C66"), c("#FFFFFF"), c("#C2412D"), false,
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

    fun eyebrow(s: String) = text(s.uppercase(), 11.5f, p.muted, 600).apply {
        letterSpacing = 0.12f
        setPadding(dp(4), dp(22), 0, dp(8))
    }

    fun card() = column().apply {
        background = rounded(p.surface, 22f)
        setPadding(dp(18), dp(6), dp(18), dp(6))
        clipToOutline = true
    }

    fun divider() = View(ctx).apply { setBackgroundColor(p.line) }

    fun addDivided(parent: LinearLayout, child: View) {
        if (parent.childCount > 0) parent.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        parent.addView(child, lp())
    }

    fun toggleRow(title: String, desc: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): LinearLayout {
        val r = row().apply { setPadding(0, dp(14), 0, dp(14)) }
        val texts = column()
        texts.addView(text(title, 16f, p.fg, 520))
        if (desc.isNotEmpty()) texts.addView(text(desc, 13.5f, p.muted), lp(top = 2))
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
        val box = column().apply { setPadding(0, dp(14), 0, dp(8)) }
        val head = row()
        head.addView(text(title, 16f, p.fg, 520), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pill = text("$value $unit", 13f, p.accent, 650).apply {
            background = rounded(withAlpha(p.accent, 0x22), 99f)
            setPadding(dp(10), dp(3), dp(10), dp(3))
        }
        head.addView(pill)
        box.addView(head)
        box.addView(SeekBar(ctx).apply {
            this.min = min
            this.max = max
            progress = value
            progressTintList = ColorStateList.valueOf(p.accent)
            thumbTintList = ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = ColorStateList.valueOf(p.line)
            setPadding(dp(4), dp(10), dp(4), dp(6))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    pill.text = "$v $unit"
                    if (fromUser && v % 2 == 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onChange(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }, lp(top = 4))
        return box
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
