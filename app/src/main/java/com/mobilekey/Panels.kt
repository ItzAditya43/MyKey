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
    private val onGif: ((File) -> Unit)?,
    private val onRemoteGif: ((GifOnline.Gif) -> Unit)? = null,
    private val onGifSearch: (() -> Unit)? = null,
    private val startOnGifs: Boolean = false,
) : Panel(ctx, t) {
    private var onGifTab = false

    private val grid = GridView(ctx)
    private val tabs = LinearLayout(ctx).apply { orientation = HORIZONTAL }
    private val toneWin = PopupWindow(ctx).apply { isOutsideTouchable = true; setBackgroundDrawable(null) }

    private class Tab(val name: String, val icon: String, val items: () -> List<String>, val kind: String = "emoji")

    init {
        val data = data(ctx)
        val list = ArrayList<Tab>()
        list.add(Tab("Recent", "🕘", { recents(ctx) }))
        data.keys.forEach { g -> list.add(Tab(g, data[g]?.firstOrNull()?.first ?: "?", { data[g]!!.map { it.first } })) }
        list.add(Tab("Kaomoji", "ツ", { KAOMOJI }, "wide"))
        symbols(ctx).forEach { (name, chars) -> list.add(Tab(name, SYMBOL_ICONS[name] ?: chars.first(), { chars }, "symbol")) }
        if (onGif != null) list.add(Tab("GIF", "GIF", { emptyList() }, "gif"))
        list.forEachIndexed { i, tab ->
            tabs.addView(label(tab.icon, if (tab.icon.length > 2) 13f else 19f, tab.icon.length > 2).apply {
                setPadding(px(10f), 0, px(10f), 0)
                contentDescription = tab.name
                setOnClickListener { select(i, list) }
            }, LayoutParams(-2, -1))
        }
        addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }, LayoutParams(-1, px(40f)))
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.isVerticalScrollBarEnabled = false
        grid.selector = GradientDrawable()
        addView(grid, LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(ctx).apply { orientation = HORIZONTAL; setPadding(px(4f), px(4f), px(4f), px(4f)) }
        bottom.addView(textButton("ABC", onBack), LayoutParams(px(64f), -1).apply { rightMargin = px(6f) })
        bottom.addView(iconButton("search", "Search", { if (onGifTab && onGifSearch != null && GifOnline.enabled(context)) onGifSearch.invoke() else onSearch() }), LayoutParams(px(52f), -1).apply { rightMargin = px(6f) })
        bottom.addView(View(ctx), LayoutParams(0, -1, 1f))
        bottom.addView(iconButton("backspace", "Delete", onBackspace), LayoutParams(px(64f), -1))
        addView(bottom, LayoutParams(-1, px(48f)))
        select(if (startOnGifs) list.indexOfFirst { it.kind == "gif" }.coerceAtLeast(0) else if (recents(ctx).isEmpty()) 1 else 0, list)
    }

    private fun select(i: Int, list: List<Tab>) {
        for (c in 0 until tabs.childCount) {
            tabs.getChildAt(c).background = if (c == i) GradientDrawable().apply {
                cornerRadius = 10 * dp; setColor(KbTheme.mix(t.bg, t.acc, 0.3f))
            } else null
        }
        val tab = list[i]
        onGifTab = tab.kind == "gif"
        if (tab.kind == "gif") { showGifs(); return }
        val items = tab.items().map { if (tab.kind == "emoji") preferredTone(context, it) else it }
        val wide = tab.kind == "wide"
        grid.numColumns = when (tab.kind) { "wide" -> 3; "symbol" -> 9; else -> 8 }
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val v = (convert as? TextView) ?: label("", if (wide) 15f else if (tab.kind == "symbol") 22f else 26f).apply {
                    layoutParams = AbsListView.LayoutParams(-1, px(if (wide) 44f else 48f))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                v.text = items[p]
                return v
            }
        }
        grid.setOnItemClickListener { _, _, p, _ ->
            val e = items[p]
            if (tab.kind != "symbol") addRecent(context, e)
            onEmoji(e)
        }
        grid.setOnItemLongClickListener { _, view, p, _ ->
            val base = baseOf(context, items[p]) ?: return@setOnItemLongClickListener false
            showTones(view, base)
            true
        }
    }

    /** Long-press: the six skin-tone versions. The one you pick is remembered for that emoji. */
    private fun showTones(anchor: View, base: String) {
        val variants = listOf(base) + (tones(context)[base] ?: return)
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(modColor()); setStroke(px(1f), t.border) }
            setPadding(px(4f), px(4f), px(4f), px(4f))
        }
        variants.forEach { v ->
            row.addView(label(v, 26f).apply {
                setOnClickListener {
                    Prefs.get(context).edit().putString("tone_$base", v).apply()
                    addRecent(context, v)
                    onEmoji(v)
                    toneWin.dismiss()
                    (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
                }
            }, LayoutParams(px(46f), px(50f)))
        }
        toneWin.contentView = row
        toneWin.width = px(46f) * variants.size + px(8f)
        toneWin.height = px(58f)
        toneWin.showAsDropDown(anchor, -(toneWin.width - anchor.width) / 2, -anchor.height - px(62f))
    }

    private fun showGifs() {
        val files = gifFiles(context)
        grid.setOnItemLongClickListener(null)
        val online = onRemoteGif != null && GifOnline.enabled(context)
        if (files.isEmpty() && !online) {
            grid.numColumns = 1
            grid.setOnItemClickListener(null)
            grid.adapter = message("Add your own GIFs and stickers in MyKey settings → Tools, or turn on online GIF search there.")
            return
        }
        bindGifGrid(grid, files, emptyList(), onGif, onRemoteGif)
        if (online) GifOnline.search(context, "") { gifs, err ->
            if (onGifTab) bindGifGrid(grid, files, gifs, onGif, onRemoteGif)
            if (err != null && files.isEmpty()) { grid.numColumns = 1; grid.adapter = message(err) }
        }
    }

    private fun message(text: String) = object : BaseAdapter() {
        override fun getCount() = 1
        override fun getItem(p: Int) = ""
        override fun getItemId(p: Int) = 0L
        override fun getView(p: Int, c: View?, parent: ViewGroup?) = label(text, 14f).apply {
            alpha = 0.7f
            setPadding(px(16f), px(30f), px(16f), px(30f))
        }
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

        private val glyphPaint = android.graphics.Paint()

        /**
         * True if this phone's emoji font can draw it. Brand-new emoji (newer than the phone's font)
         * would show as empty boxes in every app, so they're left out until the phone supports them.
         */
        fun supported(e: String) = glyphPaint.hasGlyph(e)

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
