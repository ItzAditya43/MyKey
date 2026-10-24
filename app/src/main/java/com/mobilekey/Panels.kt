package com.mobilekey

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import android.widget.PopupWindow
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import java.io.File
import com.mobilekey.engine.UserModel
import com.mobilekey.ui.IconView

/** Shared look for panels that replace the keys. */
abstract class Panel(ctx: Context, protected val t: KbTheme) : LinearLayout(ctx) {
    protected val dp = resources.displayMetrics.density
    protected fun px(v: Float) = (v * dp).toInt()

    init {
        orientation = VERTICAL
    }

    protected fun surface(color: Int, r: Float = 12f) = RippleDrawable(
        ColorStateList.valueOf(KbTheme.mix(color, t.fg, 0.25f)),
        GradientDrawable().apply { cornerRadius = r * dp; setColor(color) },
        GradientDrawable().apply { cornerRadius = r * dp; setColor(0xFFFFFFFF.toInt()) },
    )

    protected fun keyColor() = if (t.amoled) KbTheme.mix(t.bg, t.fg, 0.08f) else t.key
    protected fun modColor() = if (t.amoled) KbTheme.mix(t.bg, t.fg, 0.05f) else t.mod

    protected fun label(s: String, size: Float = 15f, bold: Boolean = false) = TextView(context).apply {
        text = s
        textSize = size
        setTextColor(t.fg)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    protected fun iconButton(icon: String, desc: String, onClick: () -> Unit) = IconView(context, icon, t.fg).apply {
        contentDescription = desc
        background = surface(modColor(), 10f)
        setOnClickListener { onClick() }
    }

    protected fun textButton(s: String, onClick: () -> Unit) = label(s, 14f, true).apply {
        background = surface(modColor(), 10f)
        setPadding(px(14f), 0, px(14f), 0)
        setOnClickListener { onClick() }
    }
}

/** One grid for your saved GIFs (first) and online results (after). */
fun Panel.bindGifGrid(grid: GridView, local: List<File>, remote: List<GifOnline.Gif>, onLocal: ((File) -> Unit)?, onRemote: ((GifOnline.Gif) -> Unit)?) {
    val px96 = (96 * resources.displayMetrics.density).toInt()
    grid.numColumns = 3
    grid.adapter = object : BaseAdapter() {
        override fun getCount() = local.size + remote.size
        override fun getItem(p: Int): Any = if (p < local.size) local[p] else remote[p - local.size]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val v = (convert as? ImageView) ?: ImageView(context).apply {
                layoutParams = AbsListView.LayoutParams(-1, px96)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(3, 3, 3, 3)
            }
            v.setImageDrawable(null)
            v.tag = p
            if (p < local.size) {
                try {
                    val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(local[p])) { dec, info, _ ->
                        val sc = maxOf(1, info.size.width / (px96 * 2))
                        dec.setTargetSize(info.size.width / sc, info.size.height / sc)
                    }
                    v.setImageDrawable(d); (d as? AnimatedImageDrawable)?.start()
                } catch (_: Exception) {}
            } else {
                GifOnline.preview(remote[p - local.size]) { d ->
                    if (v.tag == p) { v.setImageDrawable(d); (d as? AnimatedImageDrawable)?.start() }
                }
            }
            return v
        }
    }
    grid.setOnItemClickListener { _, _, p, _ ->
        if (p < local.size) onLocal?.invoke(local[p]) else onRemote?.invoke(remote[p - local.size])
    }
}

/** GIF panel (its own toolbar button): your saved GIFs first, then online trending when that's on. */
class GifPanel(
    ctx: Context, t: KbTheme,
    onLocal: (File) -> Unit,
    onRemote: ((GifOnline.Gif) -> Unit)?,
    onSearch: () -> Unit,
    onBack: () -> Unit,
) : Panel(ctx, t) {
    init {
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(6f), px(4f), px(6f), px(4f)) }
        head.addView(iconButton("back", "Back to keys", onBack), LayoutParams(px(44f), px(40f)))
        head.addView(label("GIFs & stickers", 16f, true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(12f), 0, 0, 0) }, LayoutParams(0, -1, 1f))
        val online = onRemote != null && GifOnline.enabled(ctx)
        if (online) head.addView(iconButton("search", "Search GIFs", onSearch), LayoutParams(px(44f), px(40f)))
        addView(head, LayoutParams(-1, px(48f)))
        val grid = GridView(ctx).apply { stretchMode = GridView.STRETCH_COLUMN_WIDTH; isVerticalScrollBarEnabled = false; selector = GradientDrawable() }
        val files = EmojiPanel.gifFiles(ctx)
        val status = label(when {
            files.isEmpty() && !online -> "Add your own GIFs and stickers in MyKey settings → Tools, or turn on online GIF search there."
            online -> "Loading trending…"
            else -> ""
        }, 14f).apply { alpha = 0.7f; setPadding(px(16f), px(6f), px(16f), px(6f)) }
        if (status.text.isNotEmpty()) addView(status, LayoutParams(-1, -2))
        addView(grid, LayoutParams(-1, 0, 1f))
        bindGifGrid(grid, files, emptyList(), onLocal, onRemote)
        if (online) GifOnline.search(ctx, "") { gifs, err ->
            status.text = err ?: ""
            status.visibility = if (err == null) View.GONE else View.VISIBLE
            bindGifGrid(grid, files, gifs, onLocal, onRemote)
        }
    }
}

/** Results of an online GIF search. */
class GifResultsPanel(
    ctx: Context, t: KbTheme, query: String,
    onRemote: (GifOnline.Gif) -> Unit,
    onBack: () -> Unit,
    onSearchAgain: () -> Unit,
) : Panel(ctx, t) {
    init {
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(6f), px(4f), px(6f), px(4f)) }
        head.addView(iconButton("back", "Back", onBack), LayoutParams(px(44f), px(40f)))
        head.addView(label("GIFs: $query", 16f, true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(12f), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LayoutParams(0, -1, 1f))
        head.addView(iconButton("search", "Search again", onSearchAgain), LayoutParams(px(44f), px(40f)))
        addView(head, LayoutParams(-1, px(48f)))
        val grid = GridView(ctx).apply { stretchMode = GridView.STRETCH_COLUMN_WIDTH; isVerticalScrollBarEnabled = false; selector = GradientDrawable() }
        val status = label("Searching…", 14f).apply { alpha = 0.7f }
        addView(status, LayoutParams(-1, px(40f)))
        addView(grid, LayoutParams(-1, 0, 1f))
        GifOnline.search(ctx, query) { gifs, err ->
            status.visibility = if (gifs.isEmpty()) View.VISIBLE else View.GONE
            status.text = err ?: if (gifs.isEmpty()) "No GIFs found for “$query”." else ""
            bindGifGrid(grid, emptyList(), gifs, null, onRemote)
        }
    }
}

// ---------------------------------------------------------------------------------------

class EmojiPanel(
    ctx: Context, t: KbTheme,
    private val onEmoji: (String) -> Unit,
    private val onBack: () -> Unit,
    private val onBackspace: () -> Unit,
    private val onSearch: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onGif: ((File) -> Unit)?,
    @Suppress("UNUSED_PARAMETER") onRemoteGif: ((GifOnline.Gif) -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") onGifSearch: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") startOnGifs: Boolean = false,
) : Panel(ctx, t) {

    private val grid: EmojiGrid
    private val tabRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val tabScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
    private val tabViews = ArrayList<TextView>()
    private val toneWin = PopupWindow(ctx).apply { isOutsideTouchable = true; setBackgroundDrawable(null) }
    private var currentTab = -1
    private val hasRecents: Boolean
    val key: String

    init {
        val sections = ArrayList<EmojiGrid.Section>()
        val icons = ArrayList<String>()
        val recent = recents(ctx)
        hasRecents = recent.isNotEmpty()
        key = "${t.name}|$hasRecents"
        if (hasRecents) { sections.add(EmojiGrid.Section("Recently used", recent.toMutableList(), 8)); icons.add("🕘") }
        data(ctx).forEach { (g, list) ->
            sections.add(EmojiGrid.Section(g.replace(" & ", " and "), list.map { preferredTone(ctx, it.first) }.toMutableList(), 8))
            icons.add(list.first().first)
        }
        symbols(ctx).forEach { (name, chars) -> sections.add(EmojiGrid.Section("Symbols · $name", chars, 9)); icons.add(SYMBOL_ICONS[name] ?: chars.first()) }
        sections.add(EmojiGrid.Section("Kaomoji", KAOMOJI, 3, wide = true)); icons.add("ツ")

        grid = EmojiGrid(ctx, t, sections,
            onTap = { e, sec -> if (!sec.title.startsWith("Symbols")) addRecent(context, e); onEmoji(e) },
            onLong = { e, anchor -> baseOf(context, e)?.let { showTones(anchor, it) } },
            onSection = { i -> selectTab(i, scroll = true) },
        )

        icons.forEachIndexed { i, icon ->
            val tv = label(icon, if (icon.length > 2) 13f else 20f, icon.length > 2).apply {
                contentDescription = sections[i].title
                setPadding(px(11f), 0, px(11f), 0)
                setOnClickListener { grid.scrollToSection(i) }
            }
            tabViews.add(tv)
            tabRow.addView(tv, LayoutParams(-2, -1))
        }
        tabScroll.addView(tabRow, LayoutParams(-2, -1))
        addView(tabScroll, LayoutParams(-1, px(42f)))
        addView(grid, LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(6f), px(4f), px(6f), px(6f)) }
        bottom.addView(textButton("ABC", onBack), LayoutParams(px(68f), -1).apply { rightMargin = px(6f) })
        bottom.addView(iconButton("search", "Search emoji", onSearch), LayoutParams(px(52f), -1).apply { rightMargin = px(6f) })
        bottom.addView(View(ctx), LayoutParams(0, -1, 1f))
        val del = iconButton("backspace", "Delete") { onBackspace() }
        // Hold backspace to keep deleting.
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val rep = object : Runnable { override fun run() { onBackspace(); h.postDelayed(this, 70) } }
        del.setOnLongClickListener { h.post(rep); true }
        del.setOnTouchListener { _, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) h.removeCallbacks(rep)
            false
        }
        bottom.addView(del, LayoutParams(px(68f), -1))
        addView(bottom, LayoutParams(-1, px(50f)))
        selectTab(0, scroll = false)
    }

    /** Reopening the cached panel: refresh the recents row and jump back to the top. */
    fun reopen() {
        if (hasRecents) grid.setSectionItems(0, recents(context))
        grid.scrollTo(0, 0)
        selectTab(0, scroll = true)
    }

    private fun selectTab(i: Int, scroll: Boolean) {
        if (i == currentTab) return
        currentTab = i
        tabViews.forEachIndexed { j, v ->
            v.background = if (j == i) GradientDrawable().apply { cornerRadius = 12 * dp; setColor(KbTheme.mix(t.bg, t.acc, 0.28f)) } else null
            v.alpha = if (j == i) 1f else 0.75f
        }
        if (scroll) tabViews.getOrNull(i)?.let { v -> tabScroll.smoothScrollTo((v.left - tabScroll.width / 2 + v.width / 2).coerceAtLeast(0), 0) }
    }

    /** Long-press: the six skin-tone versions. The one you pick is remembered for that emoji. */
    private fun showTones(anchor: android.graphics.Rect, base: String) {
        val variants = listOf(base) + (tones(context)[base] ?: return)
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = 16 * dp; setColor(modColor()); setStroke(px(1f), t.border) }
            elevation = 8 * dp
            setPadding(px(4f), px(4f), px(4f), px(4f))
        }
        variants.forEach { v ->
            row.addView(label(v, 28f).apply {
                setOnClickListener {
                    Prefs.get(context).edit().putString("tone_$base", v).apply()
                    addRecent(context, v)
                    onEmoji(v)
                    toneWin.dismiss()
                    grid.replace(base, v)
                }
            }, LayoutParams(px(48f), px(52f)))
        }
        toneWin.contentView = row
        toneWin.width = px(48f) * variants.size + px(8f)
        toneWin.height = px(60f)
        val loc = IntArray(2)
        grid.getLocationInWindow(loc)
        val x = (loc[0] + anchor.centerX() - toneWin.width / 2).coerceIn(0, maxOf(0, rootView.width - toneWin.width))
        toneWin.showAtLocation(grid, Gravity.NO_GRAVITY, x, loc[1] + anchor.top - toneWin.height - px(6f))
    }

    companion object {
        val KAOMOJI = listOf(
            "(＾▽＾)", "(´・ω・`)", "(╯°□°)╯︵ ┻━┻", "┬─┬ノ( º _ ºノ)", "¯\\_(ツ)_/¯", "(っ˘ω˘ς )", "( ˘ ³˘)♥",
            "(ง •̀_•́)ง", "(•‿•)", "ʕ•ᴥ•ʔ", "(=^･ω･^=)", "(ಥ﹏ಥ)", "(＞﹏＜)", "(⌐■_■)", "ヽ(°〇°)ﾉ", "(´∀｀)♡",
            "(*≧ω≦)", "(￣ー￣)", "(¬‿¬)", "( ͡° ͜ʖ ͡°)", "(ノ´ヮ`)ノ*: ･ﾟ", "(✿◠‿◠)", "(o^▽^o)", "( ´ ▽ ` )ﾉ",
            "(｡•́︿•̀｡)", "(￣□￣;)", "(⊙_⊙)", "(^_^;)", "m(_ _)m", "٩(◕‿◕｡)۶", "(ノ_<。)", "(* ^ ω ^)",
        )
        private val SYMBOL_ICONS = mapOf("Common" to "★", "Arrows" to "→", "Shapes" to "■", "Currency" to "€",
            "Greek" to "Ω", "Math" to "½", "Marks" to "✓", "Brackets" to "「", "Box" to "┼")
        private var cache: LinkedHashMap<String, List<Pair<String, String>>>? = null
        private var toneCache: Map<String, List<String>>? = null
        private var toneBase: Map<String, String>? = null
        private var symbolCache: List<Pair<String, List<String>>>? = null

        private val glyphPaint = ThreadLocal.withInitial { android.graphics.Paint() }

        /**
         * True if this phone's emoji font can draw it. Brand-new emoji (newer than the phone's font)
         * would show as empty boxes in every app, so they're left out until the phone supports them.
         */
        fun supported(e: String) = glyphPaint.get()!!.hasGlyph(e)

        /** Loads and checks all emoji data off the main thread, so the panel opens instantly. */
        fun preload(ctx: Context) { data(ctx); tones(ctx); symbols(ctx) }

        /** Emoji grouped by category, from assets/emoji.txt (group, emoji, name), limited to what the phone can show. */
        fun data(ctx: Context): LinkedHashMap<String, List<Pair<String, String>>> {
            cache?.let { return it }
            val m = LinkedHashMap<String, MutableList<Pair<String, String>>>()
            ctx.assets.open("emoji.txt").bufferedReader().useLines { lines ->
                lines.forEach { l ->
                    val p = l.split('\t')
                    if (p.size == 3 && supported(p[1])) m.getOrPut(p[0]) { ArrayList() }.add(p[1] to p[2])
                }
            }
            @Suppress("UNCHECKED_CAST")
            return (m as LinkedHashMap<String, List<Pair<String, String>>>).also { cache = it }
        }

        fun tones(ctx: Context): Map<String, List<String>> {
            toneCache?.let { return it }
            val m = HashMap<String, List<String>>()
            ctx.assets.open("emoji_tones.txt").bufferedReader().useLines { lines ->
                lines.forEach { l ->
                    val p = l.split('\t')
                    if (p.size == 2 && supported(p[0])) {
                        val v = p[1].split(' ').filter { supported(it) }
                        if (v.size == 5) m[p[0]] = v
                    }
                }
            }
            toneBase = m.flatMap { (b, vs) -> vs.map { it to b } + (b to b) }.toMap()
            return m.also { toneCache = it }
        }

        fun baseOf(ctx: Context, e: String): String? { tones(ctx); return toneBase?.get(e) }

        fun preferredTone(ctx: Context, e: String): String =
            if (tones(ctx).containsKey(e)) Prefs.get(ctx).getString("tone_$e", e) ?: e else e

        fun symbols(ctx: Context): List<Pair<String, List<String>>> {
            symbolCache?.let { return it }
            val out = ArrayList<Pair<String, List<String>>>()
            ctx.assets.open("symbols.txt").bufferedReader().useLines { lines ->
                lines.forEach { l ->
                    val p = l.split('\t', limit = 2)
                    if (p.size == 2) out.add(p[0] to p[1].codePoints().toArray().map { String(Character.toChars(it)) }.filter { supported(it) })
                }
            }
            return out.also { symbolCache = it }
        }

        fun gifFiles(ctx: Context): List<File> =
            File(ctx.filesDir, "gifs").listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

        private var nameIndex: List<Pair<String, String>>? = null

        fun search(ctx: Context, q: String): List<String> {
            val query = q.trim().lowercase()
            if (query.isEmpty()) return recents(ctx).take(20)
            val all = nameIndex ?: data(ctx).values.flatten().also { nameIndex = it }
            val starts = all.filter { (_, n) -> n.split(' ', ':', '-').any { it.startsWith(query) } }
            val contains = all.filter { (_, n) -> n.contains(query) }
            return (starts + contains).map { preferredTone(ctx, it.first) }.distinct()
        }

        private var keywords: Map<String, Map<String, String>>? = null

        /** Emoji for a typed word: emoji names (English) plus a small Spanish/Japanese keyword list. */
        fun forWord(ctx: Context, lang: String, word: String): List<String> {
            val w = word.lowercase()
            if (w.length < 2) return emptyList()
            val kw = keywords ?: HashMap<String, HashMap<String, String>>().also { m ->
                ctx.assets.open("emoji_keywords.txt").bufferedReader().useLines { lines ->
                    lines.forEach { l -> val p = l.split('\t'); if (p.size == 3) m.getOrPut(p[0]) { HashMap() }[p[1]] = p[2] }
                }
                keywords = m
            }
            val out = ArrayList<String>()
            kw[lang]?.get(w)?.takeIf { supported(it) }?.let { out.add(it) }
            if (lang == "en" || out.isEmpty()) {
                val all = nameIndex ?: data(ctx).values.flatten().also { nameIndex = it }
                all.filter { (_, n) -> n == w || n.split(' ').any { it == w } }.take(3).forEach { out.add(preferredTone(ctx, it.first)) }
            }
            return out.distinct().take(2)
        }

        fun recents(ctx: Context): List<String> =
            Prefs.get(ctx).getString("emoji_recent", "")!!.split('\u0001').filter { it.isNotEmpty() && supported(it) }

        fun addRecent(ctx: Context, e: String) {
            val list = (listOf(e) + recents(ctx).filter { it != e }).take(32)
            Prefs.get(ctx).edit().putString("emoji_recent", list.joinToString("\u0001")).apply()
        }
    }
}

// ---------------------------------------------------------------------------------------

/**
 * One continuous, virtualised emoji grid: every category in a single scroll with headings.
 * Only the visible rows are drawn, so scrolling and flinging stay smooth with thousands of emoji.
 */
@android.annotation.SuppressLint("ViewConstructor")
class EmojiGrid(
    ctx: Context, private val t: KbTheme, private val sections: List<Section>,
    private val onTap: (String, Section) -> Unit,
    private val onLong: (String, android.graphics.Rect) -> Unit,
    private val onSection: (Int) -> Unit,
) : View(ctx) {
    class Section(val title: String, val items: List<String>, val cols: Int, val wide: Boolean = false)

    private class Row(val section: Int, val top: Float, val height: Float, val header: Boolean, val start: Int)

    private val d = resources.displayMetrics.density
    private val headerH = 30 * d
    private val rows = ArrayList<Row>()
    private val sectionTops = FloatArray(sections.size)
    private var contentH = 0f
    private var laidOutWidth = 0
    private val scroller = android.widget.OverScroller(ctx)
    private val emojiPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textAlign = android.graphics.Paint.Align.CENTER }
    private val headerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = t.fg; alpha = 150; textSize = 12.5f * d; isFakeBoldText = true }
    private val pressPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = KbTheme.mix(t.bg, t.fg, 0.14f) }
    private var pressed: Pair<Int, Int>? = null   // (row index, column)
    private var pressAnim = 0f
    private var lastSection = -1

    private val gestures = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent): Boolean {
            scroller.forceFinished(true)
            pressed = hit(e.x, e.y)
            pressAnim = 0f
            animatePress()
            invalidate()
            return true
        }
        override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
            val h = hit(e.x, e.y) ?: return true
            item(h)?.let { (txt, sec) -> performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); onTap(txt, sec) }
            releasePress()
            return true
        }
        override fun onLongPress(e: android.view.MotionEvent) {
            val h = hit(e.x, e.y) ?: return
            item(h)?.let { (txt, _) -> onLong(txt, cellRect(h)) }
            releasePress()
        }
        override fun onScroll(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, dx: Float, dy: Float): Boolean {
            if (pressed != null) releasePress()
            scrollTo(0, (scrollY + dy).toInt().coerceIn(0, maxScroll()))
            return true
        }
        override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scrollY, 0, -vy.toInt(), 0, 0, 0, maxScroll(), 0, (40 * d).toInt())
            postInvalidateOnAnimation()
            return true
        }
    })

    private fun maxScroll() = (contentH - height).toInt().coerceAtLeast(0)

    private fun build(w: Int) {
        rows.clear()
        var y = 0f
        sections.forEachIndexed { si, s ->
            sectionTops[si] = y
            rows.add(Row(si, y, headerH, true, 0)); y += headerH
            val cellH = if (s.wide) 46 * d else (w / s.cols.toFloat()).coerceAtMost(54 * d)
            var i = 0
            while (i < s.items.size) { rows.add(Row(si, y, cellH, false, i)); y += cellH; i += s.cols }
        }
        contentH = y + 8 * d
        laidOutWidth = w
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { if (w != laidOutWidth) build(w) }

    private fun firstVisible(): Int {
        var lo = 0; var hi = rows.size - 1
        while (lo < hi) { val m = (lo + hi + 1) / 2; if (rows[m].top <= scrollY) lo = m else hi = m - 1 }
        return lo
    }

    override fun onDraw(c: android.graphics.Canvas) {
        if (rows.isEmpty()) return
        val bottom = scrollY + height
        var r = firstVisible()
        while (r < rows.size && rows[r].top < bottom) {
            val row = rows[r]
            val s = sections[row.section]
            if (row.header) {
                c.drawText(s.title.uppercase(), 14 * d, row.top + headerH * 0.68f, headerPaint)
            } else {
                val cw = width / s.cols.toFloat()
                emojiPaint.textSize = if (s.wide) 15 * d else minOf(row.height * 0.6f, 30 * d)
                emojiPaint.color = t.fg
                for (col in 0 until s.cols) {
                    val idx = row.start + col
                    if (idx >= s.items.size) break
                    val cx = cw * col + cw / 2
                    val cy = row.top + row.height / 2
                    val isPressed = pressed?.let { it.first == r && it.second == col } == true
                    if (isPressed) {
                        val inset = 3 * d
                        c.drawRoundRect(cw * col + inset, row.top + inset, cw * (col + 1) - inset, row.top + row.height - inset, 12 * d, 12 * d, pressPaint)
                        c.save(); val sc = 1f + 0.12f * pressAnim; c.scale(sc, sc, cx, cy)
                    }
                    c.drawText(s.items[idx], cx, cy - (emojiPaint.descent() + emojiPaint.ascent()) / 2, emojiPaint)
                    if (isPressed) c.restore()
                }
            }
            r++
        }
        // Report which category is on screen so the tab bar can follow.
        val sec = rows[firstVisible()].section.let { si -> if (scrollY >= maxScroll() - 2 && maxScroll() > 0) sections.size - 1 else si }
        if (sec != lastSection) { lastSection = sec; onSection(sec) }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { scrollTo(0, scroller.currY.coerceIn(0, maxScroll())); postInvalidateOnAnimation() }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        val handled = gestures.onTouchEvent(e)
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) releasePress()
        return handled
    }

    fun setSectionItems(i: Int, items: List<String>) {
        (sections[i].items as? MutableList<String>)?.let { it.clear(); it.addAll(items) }
        if (width > 0) build(width)
        invalidate()
    }

    fun scrollToSection(i: Int) {
        val target = sectionTops.getOrElse(i) { 0f }.toInt().coerceIn(0, maxScroll())
        scroller.forceFinished(true)
        scroller.startScroll(0, scrollY, 0, target - scrollY, 320)
        postInvalidateOnAnimation()
    }

    /** Swap an emoji for its chosen skin tone everywhere it appears. */
    fun replace(base: String, v: String) {
        sections.forEach { s -> (s.items as? MutableList<String>)?.let { l -> l.indices.forEach { if (l[it] == base || EmojiPanel.baseOf(context, l[it]) == base) l[it] = v } } }
        invalidate()
    }

    private fun hit(x: Float, y: Float): Pair<Int, Int>? {
        val yy = y + scrollY
        var lo = 0; var hi = rows.size - 1
        while (lo < hi) { val m = (lo + hi + 1) / 2; if (rows[m].top <= yy) lo = m else hi = m - 1 }
        val row = rows.getOrNull(lo) ?: return null
        if (row.header || yy > row.top + row.height) return null
        val s = sections[row.section]
        val col = (x / (width / s.cols.toFloat())).toInt().coerceIn(0, s.cols - 1)
        return if (row.start + col < s.items.size) lo to col else null
    }

    private fun item(h: Pair<Int, Int>): Pair<String, Section>? {
        val row = rows[h.first]; val s = sections[row.section]
        return s.items.getOrNull(row.start + h.second)?.let { it to s }
    }

    private fun cellRect(h: Pair<Int, Int>): android.graphics.Rect {
        val row = rows[h.first]; val cw = width / sections[row.section].cols.toFloat()
        val top = (row.top - scrollY).toInt()
        return android.graphics.Rect((cw * h.second).toInt(), top, (cw * (h.second + 1)).toInt(), top + row.height.toInt())
    }

    private fun animatePress() {
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 90
            addUpdateListener { pressAnim = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun releasePress() { if (pressed != null) { pressed = null; invalidate() } }
}

// ---------------------------------------------------------------------------------------

/** Saved longer texts, one tap to insert. Managed in settings → Shortcuts. */
class SnippetPanel(
    ctx: Context, t: KbTheme,
    private val onInsert: (String) -> Unit,
    private val onBack: () -> Unit,
) : Panel(ctx, t) {
    init {
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(6f), px(4f), px(6f), px(4f)) }
        head.addView(iconButton("back", "Back to keys", onBack), LayoutParams(px(44f), px(40f)))
        head.addView(label("Snippets", 16f, true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(12f), 0, 0, 0) }, LayoutParams(0, -1, 1f))
        addView(head, LayoutParams(-1, px(48f)))
        val list = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(px(6f), 0, px(6f), px(6f)) }
        val items = Snippets.all(ctx)
        if (items.isEmpty()) list.addView(label("No snippets yet. Add addresses, templates or anything you paste often in MyKey settings → Shortcuts.", 14f).apply {
            alpha = 0.7f; setPadding(px(16f), px(30f), px(16f), px(30f))
        })
        items.forEach { s ->
            list.addView(TextView(ctx).apply {
                text = s
                textSize = 14.5f
                setTextColor(t.fg)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                background = surface(keyColor(), 12f)
                setPadding(px(14f), px(12f), px(14f), px(12f))
                setOnClickListener { onInsert(s) }
            }, LayoutParams(-1, -2).apply { topMargin = px(6f) })
        }
        addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(list) }, LayoutParams(-1, 0, 1f))
    }
}

/** Snippets are stored as a JSON array in preferences. */
object Snippets {
    fun all(ctx: Context): List<String> {
        val a = try { org.json.JSONArray(Prefs.get(ctx).getString("snippets", "[]")) } catch (e: Exception) { org.json.JSONArray() }
        return (0 until a.length()).map { a.getString(it) }
    }
    fun save(ctx: Context, list: List<String>) = Prefs.get(ctx).edit().putString("snippets", org.json.JSONArray(list).toString()).apply()
}

// ---------------------------------------------------------------------------------------

/** Quick language switcher: tap to switch (turns the language on if needed), long-press to remove. */
class LangPanel(
    ctx: Context, t: KbTheme,
    private val current: String,
    private val onPick: (String) -> Unit,
    private val onRemove: (String) -> Unit,
    private val onSettings: () -> Unit,
    private val onBack: () -> Unit,
) : Panel(ctx, t) {
    init {
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(6f), px(4f), px(6f), px(4f)) }
        head.addView(iconButton("back", "Back to keys", onBack), LayoutParams(px(44f), px(40f)))
        head.addView(label("Languages", 16f, true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(12f), 0, 0, 0) }, LayoutParams(0, -1, 1f))
        head.addView(textButton("All settings", onSettings), LayoutParams(-2, px(36f)))
        addView(head, LayoutParams(-1, px(48f)))
        val enabled = Langs.enabled(Prefs.get(ctx))
        val sub = mapOf("en" to "English", "es" to "Spanish", "ru" to "Russian", "ja" to "Japanese", "zh" to "Mandarin")
        val grid = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(px(6f), 0, px(6f), px(6f)) }
        Langs.supported.chunked(2).forEach { pair ->
            val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
            pair.forEach { l ->
                val on = l in enabled
                val isCur = l == current
                val card = LinearLayout(ctx).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(px(16f), px(10f), px(12f), px(10f))
                    background = surface(if (isCur) KbTheme.mix(t.bg, t.acc, 0.35f) else if (on) keyColor() else modColor(), 14f)
                    alpha = if (on || isCur) 1f else 0.6f
                    setOnClickListener { onPick(l) }
                    setOnLongClickListener { onRemove(l); true }
                    contentDescription = sub[l]
                }
                card.addView(TextView(ctx).apply { text = Langs.names[l]; textSize = 17f; setTextColor(t.fg); typeface = Typeface.DEFAULT_BOLD })
                card.addView(TextView(ctx).apply {
                    text = when { isCur -> "In use"; on -> "${sub[l]} · tap to switch"; else -> "${sub[l]} · tap to add" }
                    textSize = 12.5f; setTextColor(t.fg); alpha = 0.7f
                })
                row.addView(card, LayoutParams(0, -1, 1f).apply { setMargins(px(4f), px(4f), px(4f), px(4f)) })
            }
            if (pair.size == 1) row.addView(View(ctx), LayoutParams(0, -1, 1f).apply { setMargins(px(4f), px(4f), px(4f), px(4f)) })
            grid.addView(row, LayoutParams(-1, 0, 1f))
        }
        addView(grid, LayoutParams(-1, 0, 1f))
        addView(label("Long-press a language to remove it", 12f).apply { alpha = 0.55f }, LayoutParams(-1, px(22f)))
    }
}

// ---------------------------------------------------------------------------------------

class ClipPanel(
    ctx: Context, t: KbTheme,
    private val user: UserModel,
    private val enabled: Boolean,
    private val onPaste: (String) -> Unit,
    private val onBack: () -> Unit,
) : Panel(ctx, t) {
    private val list = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(px(6f), 0, px(6f), px(6f)) }

    init {
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(6f), px(4f), px(6f), px(4f)) }
        head.addView(iconButton("back", "Back to keys", onBack), LayoutParams(px(44f), px(40f)))
        head.addView(label("Clipboard", 16f, true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(12f), 0, 0, 0) }, LayoutParams(0, -1, 1f))
        head.addView(textButton("Clear") { user.clearClips(); render() }, LayoutParams(-2, px(36f)))
        addView(head, LayoutParams(-1, px(48f)))
        addView(ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; addView(list) }, LayoutParams(-1, 0, 1f))
        render()
    }

    private fun render() {
        list.removeAllViews()
        val clips = user.clips()
        if (!enabled || clips.isEmpty()) {
            list.addView(label(if (!enabled) "Clipboard history is off. Turn it on in MyKey settings → Tools." else "Things you copy will show up here.\nLong-press an item to pin it.", 14f).apply {
                alpha = 0.7f
                setPadding(px(16f), px(30f), px(16f), px(30f))
            })
            return
        }
        clips.forEach { c ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = surface(keyColor(), 12f)
                setPadding(px(14f), px(10f), px(6f), px(10f))
                setOnClickListener { onPaste(c.text) }
                setOnLongClickListener { user.pinClip(c.id, !c.pinned); render(); true }
            }
            row.addView(TextView(context).apply {
                text = c.text
                textSize = 14.5f
                setTextColor(t.fg)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LayoutParams(0, -2, 1f))
            row.addView(IconView(context, "pin", if (c.pinned) t.acc else t.fg).apply {
                alpha = if (c.pinned) 1f else 0.45f
                contentDescription = if (c.pinned) "Unpin" else "Pin"
                setOnClickListener { user.pinClip(c.id, !c.pinned); render() }
            }, LayoutParams(px(40f), px(40f)))
            row.addView(IconView(context, "trash", t.fg).apply {
                alpha = 0.6f
                contentDescription = "Delete"
                setOnClickListener { user.deleteClip(c.id); render() }
            }, LayoutParams(px(40f), px(40f)))
            list.addView(row, LayoutParams(-1, -2).apply { topMargin = px(6f) })
        }
    }
}

// ---------------------------------------------------------------------------------------

class EditPanel(
    ctx: Context, t: KbTheme,
    private val onAction: (String) -> Unit,
    private val onBack: () -> Unit,
) : Panel(ctx, t) {
    private var selecting = false
    private lateinit var selectBtn: TextView

    init {
        setPadding(px(4f), px(4f), px(4f), px(4f))
        val rows = listOf(
            listOf("select" to "Select", "up" to "▲", "select_all" to "Select all", "backspace" to "⌫"),
            listOf("left" to "◀", "down" to "▼", "right" to "▶", "copy" to "Copy"),
            listOf("home" to "⇤ Start", "end" to "End ⇥", "undo" to "Undo", "cut" to "Cut"),
            listOf("abc" to "ABC", "redo" to "Redo", "paste" to "Paste", "clear" to "Clear all"),
        )
        rows.forEach { r ->
            val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
            r.forEach { (id, text) ->
                val b = label(text, if (text.length <= 2) 20f else 14.5f, text.length > 2).apply {
                    background = surface(if (id in setOf("up", "down", "left", "right")) keyColor() else modColor(), 12f)
                    val act = { onAction(if (selecting && id in setOf("up", "down", "left", "right", "home", "end")) "sel_$id" else id) }
                    setOnClickListener {
                        when (id) {
                            "abc" -> onBack()
                            "select" -> { selecting = !selecting; restyle() }
                            else -> act()
                        }
                    }
                    // Arrows and backspace repeat while held.
                    if (id in setOf("up", "down", "left", "right", "backspace")) {
                        val h = android.os.Handler(android.os.Looper.getMainLooper())
                        val rep = object : Runnable { override fun run() { act(); h.postDelayed(this, 60) } }
                        setOnLongClickListener { h.post(rep); true }
                        setOnTouchListener { v, e ->
                            if (e.actionMasked == android.view.MotionEvent.ACTION_UP || e.actionMasked == android.view.MotionEvent.ACTION_CANCEL) h.removeCallbacks(rep)
                            false
                        }
                    }
                }
                if (id == "select") selectBtn = b
                row.addView(b, LayoutParams(0, -1, 1f).apply { setMargins(px(3f), px(3f), px(3f), px(3f)) })
            }
            addView(row, LayoutParams(-1, 0, 1f))
        }
    }

    private fun restyle() {
        selectBtn.background = surface(if (selecting) t.acc else modColor(), 12f)
        selectBtn.setTextColor(if (selecting) t.accFg else t.fg)
    }
}
