package com.mobilekey

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.mobilekey.ui.IconView

/**
 * The strip above the keys. Shows one of: three suggestions (main one in the middle),
 * a scrolling candidate list (Japanese / Mandarin), the quick toolbar, or emoji search.
 */
class CandidateBar(context: Context) : LinearLayout(context) {
    var onPick: (String) -> Unit = {}
    var onForget: (String) -> Unit = {}
    var onCandidate: (Int) -> Unit = {}
    var onTool: (String) -> Unit = {}
    var onToggle: () -> Unit = {}

    private val dp = resources.displayMetrics.density
    private var theme: KbTheme? = null
    private val toggle = IconView(context, "grid", 0).apply {
        setOnClickListener { onToggle() }
        contentDescription = "Toolbar"
    }
    private val content = FrameLayout(context)

    private val slots = List(3) {
        TextView(context).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textSize = 17f
            setPadding(8, 0, 8, 0)
            setOnClickListener { (tag as? String)?.let(onPick) }
            setOnLongClickListener { (tag as? String)?.let { onForget(it); true } ?: false }
        }
    }
    private val suggestRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        listOf(slots[1], slots[0], slots[2]).forEach { addView(it, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)) }
    }
    private val scrollRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(scrollRow, FrameLayout.LayoutParams(-2, -1))
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(toggle, LayoutParams((44 * dp).toInt(), LayoutParams.MATCH_PARENT))
        addView(content, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        content.addView(suggestRow)
        content.addView(scroller)
        scroller.visibility = View.GONE
    }

    var toggleVisible = true
        set(v) { field = v; toggle.visibility = if (v) View.VISIBLE else View.GONE }

    fun setToggleIcon(icon: String) { toggle.icon = icon; toggle.invalidate() }

    fun applyTheme(t: KbTheme, transparent: Boolean) {
        theme = t
        if (transparent) setBackgroundColor(0) else setBackgroundColor(t.bg)
        slots.forEach { it.setTextColor(t.fg) }
        toggle.color = t.fg
        toggle.invalidate()
    }

    fun set(list: List<String>, mainIsCorrection: Boolean) {
        scroller.visibility = View.GONE
        suggestRow.visibility = View.VISIBLE
        for (i in 0..2) {
            val w = list.getOrNull(i)
            slots[i].text = w ?: ""
            slots[i].tag = w
            slots[i].typeface = if (i == 0 && mainIsCorrection) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun showScroll(build: LinearLayout.() -> Unit) {
        suggestRow.visibility = View.GONE
        scroller.visibility = View.VISIBLE
        scrollRow.removeAllViews()
        scrollRow.build()
        scroller.scrollTo(0, 0)
    }

    private fun chip(label: String, bold: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(theme?.fg ?: 0)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
        setOnClickListener { onClick() }
    }

    /** Japanese / Mandarin conversion candidates, first one highlighted. */
    fun candidates(list: List<String>) = showScroll {
        list.forEachIndexed { i, s -> addView(chip(s, i == 0) { onCandidate(i) }, LayoutParams(-2, -1)) }
    }

    fun toolbar(items: List<Pair<String, String>>, active: Set<String>) = showScroll {
        val w = (52 * dp).toInt()
        items.forEach { (icon, action) ->
            addView(IconView(context, icon, theme?.fg ?: 0).apply {
                this.active = action in active
                alpha = if (action in active) 1f else 0.85f
                if (action in active) setBackgroundColor(KbTheme.mix(theme!!.bg, theme!!.acc, 0.25f))
                contentDescription = action
                setOnClickListener { onTool(action) }
            }, LayoutParams(w, -1))
        }
    }

    /** Suggestions from your password manager / autofill service (already-inflated views). */
    fun inline(views: List<View>) = showScroll {
        views.forEach { v ->
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            addView(v, LayoutParams(-2, -2).apply { leftMargin = (6 * dp).toInt(); gravity = Gravity.CENTER_VERTICAL })
        }
    }

    /** Helper chips (paste, code, calculator, units) followed by plain predictions. */
    fun chips(items: List<Pair<String, () -> Unit>>, plain: List<String>) = showScroll {
        val t = theme ?: return@showScroll
        items.forEach { (label, action) ->
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (220 * dp).toInt()
                gravity = Gravity.CENTER
                setTextColor(t.fg)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 99 * dp
                    setColor(KbTheme.mix(t.bg, t.acc, 0.22f))
                    setStroke((1 * dp).toInt(), KbTheme.mix(t.bg, t.acc, 0.55f))
                }
                setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
                setOnClickListener { action() }
            }, LayoutParams(-2, (32 * dp).toInt()).apply { leftMargin = (6 * dp).toInt() })
        }
        plain.forEach { w -> addView(chip(w, false) { onPick(w) }, LayoutParams(-2, -1)) }
    }

    /** Emoji search: the query on the left, matching emoji to the right. */
    fun search(query: String, results: List<String>, onEmoji: (String) -> Unit) = showScroll {
        addView(TextView(context).apply {
            text = if (query.isEmpty()) "Search emoji" else query
            textSize = 15f
            setTextColor(theme?.fg ?: 0)
            alpha = if (query.isEmpty()) 0.6f else 1f
            setPadding((8 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            gravity = Gravity.CENTER_VERTICAL
        }, LayoutParams(-2, -1))
        results.take(40).forEach { e -> addView(chip(e, false) { onEmoji(e) }, LayoutParams(-2, -1)) }
    }
}
