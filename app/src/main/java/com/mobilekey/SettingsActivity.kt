package com.mobilekey

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.mobilekey.engine.Stats
import com.mobilekey.engine.UserModel
import com.mobilekey.ui.Kit
import com.mobilekey.ui.Palette
import com.mobilekey.ui.ThemeSwatch
import com.mobilekey.ui.withAlpha
import org.json.JSONObject

class SettingsActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var user: UserModel
    private lateinit var p: Palette
    private lateinit var k: Kit
    private lateinit var host: FrameLayout
    private val stack = ArrayList<String>()
    private var backCallback: Any? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun f(id: String) = Features.isOn(prefs, id)
    private fun setF(id: String, on: Boolean) = prefs.edit().putBoolean("f_$id", on).apply()
    private fun feature(id: String) = Features.all.first { it.id == id }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        user = UserModel.get(this)
        p = Palette.of(this)
        k = Kit(this, p)
        host = FrameLayout(this)
        host.setOnApplyWindowInsetsListener { v, ins ->
            val bars = ins.getInsets(WindowInsets.Type.systemBars())
            val ime = ins.getInsets(WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            ins
        }
        setContentView(host)
        window.insetsController?.setSystemBarsAppearance(
            if (p.dark) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        stack.add("home")
        swap(build("home"), forward = true, animate = false)
        intent?.getStringExtra("page")?.let { open(it) }
    }

    /** Opening settings with a "page" extra jumps straight to that page (used by the keyboard and for testing). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("page")?.let { pg ->
            while (stack.size > 1) stack.removeAt(stack.size - 1)
            swap(build("home"), forward = true, animate = false)
            open(pg)
        }
    }

    override fun onResume() {
        super.onResume()
        // Show current values when you come back (e.g. after switching theme from the keyboard).
        val top = stack.lastOrNull() ?: return
        if (top !in setOf("shortcuts", "learning", "layouts", "theme_editor")) swap(build(top), forward = true, animate = false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Coming back from the system keyboard picker doesn't call onResume.
        if (hasFocus && stack.lastOrNull() == "home") swap(build("home"), forward = true, animate = false)
    }

    // ---------- navigation ----------

    private fun open(page: String) {
        stack.add(page)
        swap(build(page), forward = true, animate = true)
        updateBack()
    }

    private fun back() {
        if (stack.size <= 1) { finish(); return }
        stack.removeAt(stack.size - 1)
        swap(build(stack.last()), forward = false, animate = true)
        updateBack()
    }

    private fun reload() = swap(build(stack.last()), forward = true, animate = false)

    private fun swap(view: View, forward: Boolean, animate: Boolean) {
        currentFocus?.let { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0) }
        val old = if (host.childCount > 0) host.getChildAt(host.childCount - 1) else null
        host.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (!animate || old == null) {
            if (old != null) host.removeView(old)
            return
        }
        val w = host.width.toFloat().coerceAtLeast(1f)
        view.translationX = if (forward) w * 0.25f else -w * 0.25f
        view.alpha = 0f
        view.animate().translationX(0f).alpha(1f).setDuration(280).setInterpolator(DecelerateInterpolator(2.2f)).start()
        old.animate().translationX(if (forward) -w * 0.12f else w * 0.12f).alpha(0f).setDuration(200)
            .withEndAction { host.removeView(old) }.start()
    }

    private fun updateBack() {
        if (Build.VERSION.SDK_INT < 33) return
        if (stack.size > 1 && backCallback == null) {
            val cb = OnBackInvokedCallback { back() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        } else if (stack.size <= 1 && backCallback != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback as OnBackInvokedCallback)
            backCallback = null
        }
    }

    /** Android 11–12 only; Android 13+ uses the OnBackInvokedCallback registered in [updateBack]. */
    @Deprecated("Used below Android 13")
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (stack.size > 1) back() else super.onBackPressed()
    }

    private fun build(page: String): View = when (page) {
        "look", "theme" -> themePage()
        "size" -> sizePage()
        "keys" -> keysPage()
        "feel" -> feelPage()
        "profiles" -> profilesPage()
        "theme_editor" -> themeEditor()
        "languages" -> languages()
        "typing" -> typing()
        "correction" -> featurePage("Autocorrect", "Fixes, capitals and punctuation.", "Correction")
        "tools" -> tools()
        "toolbar" -> toolbarPage()
        "longpress" -> longPressPage()
        "learning" -> learning()
        "shortcuts" -> shortcuts()
        "layouts" -> layouts()
        "stats" -> stats()
        "backup" -> backup()
        "privacy" -> privacy()
        else -> if (page.startsWith("apptheme:")) appTheme(page.removePrefix("apptheme:")) else home()
    }

    // ---------- scaffolding ----------

    private fun page(title: String, subtitle: String?, sticky: View? = null, fill: LinearLayout.() -> Unit): View {
        val root = k.column()
        val bar = k.row().apply { setPadding(k.dp(10), k.dp(6), k.dp(16), k.dp(6)); minimumHeight = k.dp(56) }
        bar.addView(com.mobilekey.ui.IconView(this, "back", p.fg).apply {
            background = k.ripple(p.surface2, 99f)
            contentDescription = "Back"
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(k.dp(40), k.dp(40)))
        val small = k.text(title, 17f, p.fg, 650).apply {
            setPadding(k.dp(14), 0, 0, 0)
            maxLines = 1
            alpha = if (sticky != null) 1f else 0f
        }
        bar.addView(small, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        if (sticky != null) root.addView(sticky, k.lp(top = 2, bottom = 6).apply { leftMargin = k.dp(12); rightMargin = k.dp(12) })
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = k.column().apply { setPadding(k.dp(16), 0, k.dp(16), k.dp(56)) }
        if (sticky == null) {
            // Large title that hands over to the small one in the bar as you scroll.
            col.addView(k.text(title, 32f, p.fg, 780, display = true).apply { setPadding(k.dp(4), k.dp(4), 0, 0) })
            if (subtitle != null) col.addView(k.text(subtitle, 14.5f, p.muted).apply { setPadding(k.dp(4), k.dp(2), 0, k.dp(4)) })
            scroll.setOnScrollChangeListener { _, _, y, _, _ ->
                small.alpha = (y / k.dp(48).toFloat()).coerceIn(0f, 1f)
                bar.elevation = if (y > k.dp(8)) k.dp(2).toFloat() else 0f
            }
            bar.setBackgroundColor(p.bg)
        } else if (subtitle != null) {
            col.addView(k.text(subtitle, 13.5f, p.muted).apply { setPadding(k.dp(4), k.dp(2), 0, 0) })
        }
        col.fill()
        scroll.addView(col)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun LinearLayout.section(label: String, build: LinearLayout.() -> Unit): LinearLayout {
        addView(k.eyebrow(label))
        val c = k.card()
        c.build()
        addView(c, k.lp())
        return c
    }

    private fun LinearLayout.divided(v: View) = k.addDivided(this, v)

    private fun LinearLayout.toggles(vararg ids: String, onChange: () -> Unit = {}) = ids.forEach { id ->
        val ft = feature(id)
        divided(k.toggleRow(ft.title, ft.desc, f(id)) { setF(id, it); onChange() })
    }

    /** One-of choice: a segmented control for a few short options, chips for longer lists. */
    private fun LinearLayout.choice(label: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        addView(k.eyebrow(label))
        if (options.size <= 4 && options.all { it.second.length <= 11 }) {
            addView(k.segmented(options, current, onPick), k.lp())
            return
        }
        val chips = ArrayList<TextView>()
        options.forEach { (id, name) ->
            chips.add(k.chip(name, id == current) {
                chips.forEachIndexed { i, c ->
                    val sel = options[i].first == id
                    c.setTextColor(if (sel) p.accentFg else p.fg)
                    c.background = k.ripple(if (sel) p.accent else p.surface2, 99f)
                }
                onPick(id)
            })
        }
        addView(k.chipRow(chips))
    }

    // ---------- home ----------

    private class Link(val icon: String, val tint: Int, val title: String, val value: String, val page: String, val keywords: String = "")

    private fun links(): List<Pair<String, List<Link>>> {
        fun c(h: String) = android.graphics.Color.parseColor(h)
        val themeName = prefs.getString("theme", "Graphite") ?: "Graphite"
        val langs = Langs.enabled(prefs).joinToString(", ") { Langs.names[it] ?: it }
        val typing = Features.all.filter { it.category == "Typing" }
        val learned = Langs.enabled(prefs).sumOf { user.allWords(it).size }
        val st = Stats.get(this)
        return listOf(
            "Look" to listOf(
                Link("brush", c("#FF6B4A"), "Theme", KbConfig.themeName(this, prefs), "theme", "theme colour amoled oled background photo gradient aurora day night dark"),
                Link("resize", c("#F0563A"), "Size", "${prefs.getInt("scale", KbConfig.DEF_SCALE)}% · keys ${prefs.getInt("key_h", KbConfig.DEF_HEIGHT)}dp", "size", "height width margin gap roundness letter number row"),
                Link("keyboard", c("#C04BD6"), "Keys & animation", KbConfig.styles.firstOrNull { it.first == prefs.getString("key_style", "") }?.second ?: "Style, animation, font", "keys", "outline borderless pop ripple glow fade font bold shadow hint trail"),
                Link("voice", c("#7B61FF"), "Sound & vibration", listOf(if (f("haptics")) "Vibration on" else "Vibration off", if (f("sounds")) "sound on" else "sound off").joinToString(" · "), "feel", "haptic vibrate sound click volume silent"),
            ),
            "Typing" to listOf(
                Link("globe", c("#3D8BFF"), "Languages", langs, "languages", "english spanish russian japanese mandarin chinese layout qwerty switch"),
                Link("keyboard", c("#8E6CFF"), "Typing", "${typing.count { f(it.id) }} of ${typing.size} on", "typing", "glide swipe gesture trackpad timing long-press backspace split"),
                Link("check", c("#22B573"), "Autocorrect", if (f("autocorrect")) "On" else "Off", "correction", "spelling capital punctuation emoji calculator units date"),
                Link("hold", c("#FF9F1C"), "Hold keys", "What each key gives on long-press", "longpress", "long press popup symbols accents hint"),
                Link("snippet", c("#E8A500"), "Shortcuts & snippets", "${user.allShortcuts().size} shortcuts", "shortcuts", "text expansion"),
            ),
            "Tools" to listOf(
                Link("toolbar", c("#FF4D8D"), "Toolbar", "${Toolbar.visible(prefs).size} buttons", "toolbar", "buttons order theme button favourites"),
                Link("emoji", c("#00B3A4"), "Panels & chips", "Emoji, GIFs, clipboard, voice", "tools", "emoji gif clipboard snippets voice password email one-handed floating"),
                Link("hand", c("#2BB3E0"), "App profiles", if (f("per_app_theme")) "On" else "Off", "profiles", "per app game split position size"),
                Link("layout", c("#5A9BD4"), "Layouts (advanced)", "Edit keys as JSON", "layouts", "json custom keys"),
            ),
            "You" to listOf(
                Link("sparkle", c("#A259FF"), "Learning", if (f("incognito")) "Incognito is on" else "$learned words learned", "learning", "words dictionary incognito forget"),
                Link("chart", c("#3A86FF"), "Stats", "${st.get("words")} words · ${st.wpm()} wpm", "stats", "speed"),
                Link("shield", c("#2DBE60"), "Privacy", if (f("gif_search")) "Online only for GIF search" else "Fully offline", "privacy", "spell check lock screen"),
                Link("backup", c("#8A8F98"), "Backup", "Export or restore everything", "backup", "export import file"),
            ),
        )
    }

    private val pageOf = mapOf("Typing" to "typing", "Correction" to "correction", "Learning" to "learning", "Look" to "keys",
        "Tools" to "tools", "Privacy" to "privacy", "Languages" to "languages")
    private val featurePage = mapOf("number_row" to "size", "hints" to "keys", "key_preview" to "keys", "key_shadow" to "keys",
        "bold_labels" to "keys", "haptics" to "feel", "sounds" to "feel", "sounds_silent" to "feel", "auto_theme" to "theme",
        "per_app_theme" to "profiles", "custom_layouts" to "layouts", "tool_theme" to "toolbar", "tool_resize" to "toolbar", "quick_toolbar" to "toolbar")

    private fun home(): View {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val col = k.column().apply { setPadding(k.dp(16), k.dp(14), k.dp(16), k.dp(56)) }
        scroll.addView(col)

        val head = k.row().apply { setPadding(k.dp(4), 0, k.dp(4), 0) }
        head.addView(k.text("MyKey", 36f, p.fg, 800, display = true), LinearLayout.LayoutParams(0, -2, 1f))
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val active = isCurrentKeyboard()
        head.addView(k.row().apply {
            background = k.rounded(if (active) withAlpha(p.accent, 0x22) else withAlpha(p.danger, 0x22), 99f)
            setPadding(k.dp(10), k.dp(5), k.dp(12), k.dp(5))
            addView(View(this@SettingsActivity).apply { background = k.rounded(if (active) p.accent else p.danger, 99f) }, LinearLayout.LayoutParams(k.dp(7), k.dp(7)))
            addView(k.text(if (active) "Active" else "Not set up", 13f, if (active) p.accent else p.danger, 650).apply { setPadding(k.dp(6), 0, 0, 0) })
        })
        col.addView(head)
        col.addView(k.text("Private keyboard. Everything it learns stays on this phone.", 14.5f, p.muted).apply { setPadding(k.dp(4), k.dp(2), 0, k.dp(14)) })
        if (!active) col.addView(statusCard(), k.lp(bottom = 12))

        val search = k.field("Search settings").apply {
            setCompoundDrawablesRelative(null, null, null, null)
            background = k.rounded(p.surface2, 14f)
            setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(12))
        }
        col.addView(search, k.lp(bottom = 8))
        val groupsBox = k.column()
        val results = k.column()
        col.addView(results)
        col.addView(groupsBox)

        links().forEach { (group, items) ->
            groupsBox.addView(k.eyebrow(group))
            val card = k.card()
            items.forEach { l -> k.addDivided(card, k.linkRow(l.icon, l.tint, l.title, l.value) { open(l.page) }) }
            groupsBox.addView(card, k.lp())
        }
        groupsBox.addView(k.eyebrow("Try it"))
        groupsBox.addView(k.field("Type here to test MyKey…"), k.lp())
        groupsBox.addView(k.text("Long-press the globe key on the keyboard to open these settings. Tap it for emoji.", 13f, p.muted).apply {
            gravity = Gravity.CENTER; setPadding(k.dp(24), k.dp(22), k.dp(24), 0)
        })

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) {
                val q = e.toString().trim().lowercase()
                results.removeAllViews()
                groupsBox.visibility = if (q.isEmpty()) View.VISIBLE else View.GONE
                if (q.isEmpty()) return
                val all = links().flatMap { it.second }
                val card = k.card()
                all.filter { it.title.lowercase().contains(q) || it.keywords.contains(q) }.forEach { l ->
                    k.addDivided(card, k.linkRow(l.icon, l.tint, l.title, l.value) { open(l.page) })
                }
                Features.all.filter { it.title.lowercase().contains(q) || it.desc.lowercase().contains(q) }.take(12).forEach { ft ->
                    val target = featurePage[ft.id] ?: pageOf[ft.category] ?: "home"
                    val link = all.firstOrNull { it.page == target }
                    k.addDivided(card, k.linkRow(link?.icon ?: "settings", link?.tint ?: p.muted, ft.title, link?.title ?: "") { open(target) })
                }
                if (card.childCount == 0) card.addView(k.text("Nothing matches “$q”.", 14.5f, p.muted).apply { setPadding(0, k.dp(18), 0, k.dp(18)) })
                results.addView(k.eyebrow("Results"))
                results.addView(card, k.lp())
            }
        })
        return scroll
    }

    /** Is MyKey the keyboard in use? Android 14+ has an official call; older versions read the setting. */
    private fun isCurrentKeyboard(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val info = if (Build.VERSION.SDK_INT >= 34) imm.currentInputMethodInfo else null
        val secure = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (info != null) return info.packageName == packageName
        return secure?.startsWith(packageName) == true
    }

    private fun statusCard(): View {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        return k.column().apply {
            background = k.rounded(p.accent, 20f)
            setPadding(k.dp(20), k.dp(18), k.dp(20), k.dp(20))
            addView(k.text(if (enabled) "One step left" else "Two steps to start", 21f, p.accentFg, 760, display = true))
            addView(k.text(
                if (enabled) "MyKey is turned on. Switch to it to start typing."
                else "Turn MyKey on in system settings, then switch to it.",
                14.5f, withAlpha(p.accentFg, 0xd0),
            ), k.lp(top = 4, bottom = 14))
            val r = k.row()
            if (!enabled) r.addView(lightButton("Turn on") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = k.dp(8) })
            r.addView(lightButton("Switch keyboard") {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(r)
        }
    }

    private fun lightButton(label: String, onClick: () -> Unit) = k.text(label, 15f, p.accent, 650).apply {
        gravity = Gravity.CENTER
        setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12))
        background = k.ripple(p.accentFg, 14f)
        setOnClickListener { onClick() }
    }

    // ---------- live preview ----------

    private inner class Preview(var lang: String) {
        val kv = KeyboardView(this@SettingsActivity)
        val line = k.text("", 16f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(k.dp(16), k.dp(12), k.dp(16), k.dp(6))
        }
        val box = k.column().apply {
            // Round the corners for every background, including photos and effects.
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, k.dp(22).toFloat())
            }
            clipToOutline = true
        }
        private val typed = StringBuilder()
        private var symbols = false
        var custom: KbLayout? = null
        var themeOverride: KbTheme? = null
        /** Called with what a key types when tapped (used by the long-press editor). */
        var onKeyTap: ((String) -> Unit)? = null

        init {
            box.addView(line)
            box.addView(kv)
            kv.listener = object : KeyboardView.Listener {
                override fun onText(text: String) {
                    onKeyTap?.let { it(text); return }
                    typed.append(text)
                    if (kv.shiftState == 1) kv.shiftState = 0
                    show()
                }
                override fun onSpecial(type: String) {
                    when (type) {
                        "backspace" -> if (typed.isNotEmpty()) typed.setLength(typed.length - Character.charCount(typed.codePointBefore(typed.length)))
                        "space" -> typed.append(' ')
                        "enter" -> typed.setLength(0)
                        "shift" -> kv.shiftState = (kv.shiftState + 1) % 3
                        "symbols" -> { symbols = !symbols; refresh() }
                        "lang" -> {
                            val l = Langs.enabled(prefs)
                            lang = l[(l.indexOf(lang) + 1) % l.size]
                            refresh()
                        }
                    }
                    show()
                }
                override fun onCursorMove(steps: Int) {}
                override fun onDeleteWords(count: Int) {
                    repeat(count) {
                        var i = typed.length
                        while (i > 0 && typed[i - 1] == ' ') i--
                        while (i > 0 && typed[i - 1] != ' ') i--
                        typed.setLength(i)
                    }
                    show()
                }
                override fun onLongSpace() {}
                override fun onGlide(path: List<android.graphics.PointF>) {
                    typed.append("〰 ")
                    show()
                }
            }
            refresh()
        }

        fun show() {
            val t = kv.theme
            line.setTextColor(if (typed.isEmpty()) withAlpha(t.fg, 0x80) else t.fg)
            line.text = if (typed.isEmpty()) "Type here to try it" else typed
        }

        fun refresh() {
            KbConfig.apply(this@SettingsActivity, kv, prefs)
            themeOverride?.let { kv.theme = it }
            val l = custom ?: if (symbols) Layouts.load(this@SettingsActivity, "symbols", f("custom_layouts"))
            else KbConfig.lettersLayout(this@SettingsActivity, prefs, lang)
            kv.spaceLabel = Langs.names[lang] ?: lang
            kv.applyLayout(l)
            // Same background the keyboard uses: photo, then gradient/aurora effect, then the plain colour.
            val effect = prefs.getString("bg_effect", "none")
            val bg = BgImage.drawable(this@SettingsActivity)
                ?: if (effect == "gradient" || effect == "aurora") EffectDrawable(kv.theme, effect == "aurora") else null
            kv.transparentBg = bg != null
            box.background = bg ?: k.rounded(kv.theme.bg, 22f)
            show()
        }
    }

    private fun previewLang(): String {
        val l = Langs.enabled(prefs)
        val cur = prefs.getString("lang", l[0]) ?: l[0]
        return if (cur in l) cur else l[0]
    }

    // ---------- look ----------

    // ---------- theme ----------

    private fun themePage(): View {
        val pv = Preview(previewLang())
        return page("Theme", "Tap a theme to use it.", pv.box) {
            addView(k.eyebrow("Themes"))
            val swatches = ArrayList<ThemeSwatch>()
            val current = KbConfig.themeName(this@SettingsActivity, prefs)
            // A wrapping grid, so every theme is visible at once.
            Themes.all(this@SettingsActivity).chunked(3).forEach { rowThemes ->
                val r = k.row().apply { gravity = Gravity.TOP }
                rowThemes.forEach { t ->
                    r.addView(k.column().apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(0, k.dp(4), 0, k.dp(8))
                        val sw = ThemeSwatch(this@SettingsActivity, t, p).also { it.picked = t.name == current }
                        swatches.add(sw)
                        addView(sw, LinearLayout.LayoutParams(-2, -2))
                        addView(k.text(t.name, 12.5f, if (t.name == current) p.fg else p.muted, if (t.name == current) 650 else 500).apply {
                            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        }, k.lp(-2, top = 4))
                        setOnClickListener {
                            prefs.edit().putString("theme", t.name).remove("key_style").apply()
                            if (f("auto_theme")) prefs.edit().putString(if (p.dark) "theme_night" else "theme_day", t.name).apply()
                            swatches.forEach { it.picked = it === sw }
                            pv.refresh()
                        }
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                }
                repeat(3 - rowThemes.size) { r.addView(View(this@SettingsActivity), LinearLayout.LayoutParams(0, 1, 1f)) }
                addView(r, k.lp())
            }
            addView(k.button("Create your own theme", "secondary") { open("theme_editor") }, k.lp(top = 8))

            choice("Background", KbConfig.bgEffects, prefs.getString("bg_effect", "none")!!) {
                prefs.edit().putString("bg_effect", it).apply(); pv.refresh()
            }
            section("Background photo") {
                val has = BgImage.file(this@SettingsActivity) != null
                divided(k.navRow(if (has) "Change photo" else "Choose a photo", if (has) "Set" else "None") {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                    }, REQ_IMAGE)
                })
                if (has) {
                    divided(k.slider("Darken photo", "%", 0, 85, prefs.getInt("bg_dim", 35)) { save("bg_dim", it); pv.refresh() })
                    divided(k.slider("Key opacity", "%", 0, 100, prefs.getInt("key_opacity", 70)) { save("key_opacity", it); pv.refresh() })
                    divided(k.navRow("Remove photo", "") { BgImage.remove(this@SettingsActivity); reload() })
                }
            }
            section("Day and night") {
                toggles("auto_theme") { reload() }
            }
            if (f("auto_theme")) {
                val names = Themes.all(this@SettingsActivity).map { it.name to it.name }
                choice("Day theme", names, prefs.getString("theme_day", prefs.getString("theme", "Graphite"))!!) {
                    prefs.edit().putString("theme_day", it).apply(); pv.refresh()
                }
                choice("Night theme", names, prefs.getString("theme_night", "Crimson Noir")!!) {
                    prefs.edit().putString("theme_night", it).apply(); pv.refresh()
                }
            }
        }
    }

    // ---------- size ----------

    private fun sizePage(): View {
        val pv = Preview(previewLang())
        return page("Size", "Drag a slider and watch the keyboard.", pv.box) {
            section("Keyboard") {
                divided(k.slider("Overall size", "%", 70, 140, prefs.getInt("scale", KbConfig.DEF_SCALE)) { save("scale", it); pv.refresh() })
                divided(k.slider("Side margins", "dp", 0, 40, prefs.getInt("side_margin", 0)) { save("side_margin", it); pv.refresh() })
                divided(k.slider("Space below keyboard", "dp", 0, 40, prefs.getInt("bottom_gap", 0)) { save("bottom_gap", it); pv.refresh() })
            }
            section("Keys") {
                divided(k.slider("Key height", "dp", 38, 72, prefs.getInt("key_h", KbConfig.DEF_HEIGHT)) { save("key_h", it); pv.refresh() })
                divided(k.slider("Gap between keys", "dp", 0, 12, prefs.getInt("gap", KbConfig.DEF_GAP)) { save("gap", it); pv.refresh() })
                divided(k.slider("Corner roundness", "dp", 0, 24, prefs.getInt("radius", KbConfig.DEF_RADIUS)) { save("radius", it); pv.refresh() })
                divided(k.slider("Letter size", "sp", 14, 28, prefs.getInt("label", KbConfig.DEF_LABEL)) { save("label", it); pv.refresh() })
            }
            section("Rows") { toggles("number_row") { pv.refresh() } }
            addView(k.button("Reset size", "secondary") {
                prefs.edit().apply { listOf("key_h", "gap", "radius", "label", "scale", "side_margin", "bottom_gap").forEach { remove(it) } }.apply()
                reload()
            }, k.lp(top = 22))
        }
    }

    // ---------- keys & animation ----------

    private fun keysPage(): View {
        val pv = Preview(previewLang())
        val theme = Themes.byName(this, KbConfig.themeName(this, prefs))
        return page("Keys & animation", "Tap keys above to feel the animation.", pv.box) {
            choice("Key style", KbConfig.styles, prefs.getString("key_style", if (theme.amoled) "outline" else "filled")!!) {
                prefs.edit().putString("key_style", it).apply(); pv.refresh()
            }
            choice("Press animation", KbConfig.anims, prefs.getString("press_anim", "pop")!!) {
                prefs.edit().putString("press_anim", it).apply(); pv.refresh()
            }
            choice("Letter font", KbConfig.fonts, prefs.getString("key_font", "system")!!) {
                prefs.edit().putString("key_font", it).apply(); pv.refresh()
            }
            choice("Glide trail", KbConfig.trails, prefs.getString("trail_style", "comet")!!) {
                prefs.edit().putString("trail_style", it).apply(); pv.refresh()
            }
            section("Details") { toggles("hints", "key_preview", "key_shadow", "bold_labels") { pv.refresh() } }
        }
    }

    // ---------- sound & vibration ----------

    private fun feelPage(): View = page("Sound & vibration", "How keys sound and feel.") {
        section("Vibration") { toggles("haptics") }
        choice("Vibration strength", KbConfig.hapticLevels.mapIndexed { i, s -> i.toString() to s }, prefs.getInt("haptic_level", 1).toString()) {
            save("haptic_level", it.toInt())
        }
        section("Sound") {
            toggles("sounds", "sounds_silent")
            divided(k.slider("Volume", "%", 0, 100, prefs.getInt("sound_volume", 50)) { save("sound_volume", it) })
        }
        choice("Key sound", Sounds.packs, prefs.getString("sound_pack", "system")!!) {
            prefs.edit().putString("sound_pack", it).apply()
            if (it != "system") Sounds.play(this@SettingsActivity, it, prefs.getInt("sound_volume", 50) / 100f, f("sounds_silent"))
        }
        addView(k.text("Tip: if you hear nothing, your phone may be on silent or vibrate. Turn on “Key sounds in silent and vibrate mode”.", 13f, p.muted), k.lp(top = 12))
    }

    // ---------- app profiles ----------

    private fun profilesPage(): View = page("App profiles", "A different keyboard for specific apps.") {
        section("Profiles") { toggles("per_app_theme") { reload() } }
        if (f("per_app_theme")) {
            addView(k.eyebrow("Apps you've typed in"))
            val apps = (prefs.getString("recent_apps", "") ?: "").split(',').filter { it.isNotEmpty() }
            val card = k.card()
            if (apps.isEmpty()) card.addView(k.text("Type in a few apps first; they'll show up here.", 14f, p.muted).apply { setPadding(0, k.dp(16), 0, k.dp(16)) })
            apps.forEach { a ->
                val bits = listOfNotNull(
                    prefs.getString("app_theme_$a", null),
                    prefs.getString("app_split_$a", null)?.let { if (it == "on") "split" else "no split" },
                    prefs.getString("app_hand_$a", null),
                    prefs.getInt("app_scale_$a", 0).takeIf { it > 0 }?.let { "$it%" },
                )
                k.addDivided(card, k.navRow(appName(a), if (bits.isEmpty()) "Default" else bits.joinToString(" · ")) { open("apptheme:$a") })
            }
            addView(card, k.lp())
        }
    }

    private fun save(key: String, v: Int) = prefs.edit().putInt(key, v).apply()

    /** Android doesn't let keyboards look up other apps' names, so tidy the package name instead. */
    private fun appName(pkg: String): String {
        val known = mapOf("com.whatsapp" to "WhatsApp", "org.telegram.messenger" to "Telegram", "com.instagram.android" to "Instagram",
            "com.android.chrome" to "Chrome", "com.google.android.gm" to "Gmail", "com.discord" to "Discord", "com.android.settings" to "Settings",
            "com.google.android.youtube" to "YouTube", "com.twitter.android" to "X", "com.snapchat.android" to "Snapchat")
        return known[pkg] ?: pkg.split('.').last { it !in setOf("android", "app", "mobile") }.replaceFirstChar { it.uppercase() }
    }

    private fun appTheme(pkg: String): View {
        val pv = Preview(previewLang()).apply { themeOverride = prefs.getString("app_theme_$pkg", null)?.let { Themes.byName(this@SettingsActivity, it) }; refresh() }
        return page(appName(pkg), "Profile used only in this app.", pv.box) {
            fun setOrClear(key: String, v: String) { if (v.isEmpty()) prefs.edit().remove(key).apply() else prefs.edit().putString(key, v).apply() }
            val names = listOf("" to "Default") + Themes.all(this@SettingsActivity).map { it.name to it.name }
            choice("Theme", names, prefs.getString("app_theme_$pkg", "")!!) { id ->
                setOrClear("app_theme_$pkg", id)
                pv.themeOverride = if (id.isEmpty()) null else Themes.byName(this@SettingsActivity, id)
                pv.refresh()
            }
            choice("Split keyboard", listOf("" to "Default", "on" to "Split", "off" to "Not split"), prefs.getString("app_split_$pkg", "")!!) {
                setOrClear("app_split_$pkg", it)
                pv.kv.split = when (it) { "on" -> true; "off" -> false; else -> f("split") }
                pv.kv.refresh()
            }
            choice("Keyboard position", listOf("" to "Default", "full" to "Full width", "left" to "One-handed left", "right" to "One-handed right", "float" to "Floating"),
                prefs.getString("app_hand_$pkg", "")!!) { setOrClear("app_hand_$pkg", it) }
            choice("Autocorrect in this app", listOf("" to "Default", "off" to "Off"), prefs.getString("app_ac_$pkg", "")!!) { setOrClear("app_ac_$pkg", it) }
            choice("Suggestions in this app", listOf("" to "Default", "off" to "Off"), prefs.getString("app_suggest_$pkg", "")!!) { setOrClear("app_suggest_$pkg", it) }
            section("Size") {
                val cur = prefs.getInt("app_scale_$pkg", 0)
                divided(k.toggleRow("Own size for this app", "Off uses your normal keyboard size.", cur > 0) { on ->
                    if (on) save("app_scale_$pkg", prefs.getInt("scale", KbConfig.DEF_SCALE)) else prefs.edit().remove("app_scale_$pkg").apply()
                    reload()
                })
                if (cur > 0) divided(k.slider("Size in this app", "%", 70, 140, cur) { save("app_scale_$pkg", it) })
            }
            addView(k.text("Tip for games: Split + a smaller size keeps the middle of the screen free.", 13f, p.muted), k.lp(top = 12))
            addView(k.text(pkg, 12.5f, p.muted), k.lp(top = 8))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_GIF) {
            val uris = data?.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data?.data)
            val dir = java.io.File(filesDir, "gifs").apply { mkdirs() }
            uris.forEach { u ->
                val type = contentResolver.getType(u) ?: "image/gif"
                val ext = when { "webp" in type -> "webp"; "png" in type -> "png"; else -> "gif" }
                contentResolver.openInputStream(u)?.use { inp ->
                    java.io.File(dir, "${System.currentTimeMillis()}_${uris.indexOf(u)}.$ext").outputStream().use { inp.copyTo(it) }
                }
            }
            reload()
            return
        }
        val uri: Uri = data?.data ?: return
        when (requestCode) {
            REQ_IMAGE -> { BgImage.import(this, uri); reload() }
            REQ_EXPORT -> {
                contentResolver.openOutputStream(uri)?.use { it.write(Backup.export(this).toString(2).toByteArray()) }
                backupStatus = "Saved. Keep this file somewhere safe."
                reload()
            }
            REQ_IMPORT -> {
                backupStatus = try {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
                    Backup.import(this, JSONObject(text))
                    "Restored. Your words, shortcuts, look and layouts are back."
                } catch (e: Exception) {
                    "Couldn't read that file: ${e.message}"
                }
                reload()
            }
        }
    }

    // ---------- theme editor ----------

    private fun themeEditor(): View {
        val base = Themes.byName(this, prefs.getString("theme", "Graphite"))
        var t = base.copy(name = "Custom")
        val pv = Preview(previewLang()).apply { themeOverride = t; refresh() }
        val fields = listOf(
            "Background" to { x: KbTheme -> x.bg }, "Keys" to { x: KbTheme -> x.key }, "Special keys" to { x: KbTheme -> x.mod },
            "Letters" to { x: KbTheme -> x.fg }, "Accent (enter key)" to { x: KbTheme -> x.acc },
            "Accent text" to { x: KbTheme -> x.accFg }, "Key outline" to { x: KbTheme -> x.border },
            "Special key icons" to { x: KbTheme -> x.modFg },
        )
        fun with(i: Int, c: Int, x: KbTheme) = when (i) {
            0 -> x.copy(bg = c); 1 -> x.copy(key = c); 2 -> x.copy(mod = c); 3 -> x.copy(fg = c)
            4 -> x.copy(acc = c); 5 -> x.copy(accFg = c); 6 -> x.copy(border = c); else -> x.copy(modFg = c)
        }
        return page("Theme editor", "Starts from your current theme.", pv.box) {
            val editors = k.card()
            fields.forEachIndexed { i, (name, get) ->
                val row = k.column().apply { setPadding(0, k.dp(12), 0, k.dp(12)) }
                val head = k.row()
                val dot = View(this@SettingsActivity).apply { background = k.rounded(get(t), 99f, p.line) }
                head.addView(dot, LinearLayout.LayoutParams(k.dp(28), k.dp(28)))
                head.addView(k.text(name, 16f, p.fg, 520).apply { setPadding(k.dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                val hex = k.field("#RRGGBB").apply {
                    setText(KbTheme.hex(get(t)))
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                }
                head.addView(hex, LinearLayout.LayoutParams(k.dp(120), -2))
                row.addView(head)
                val sliders = k.column().apply { visibility = View.GONE }
                val hsv = FloatArray(3).also { Color.colorToHSV(get(t), it) }
                var syncing = false
                fun applyColor(c: Int, fromHex: Boolean) {
                    t = with(i, c, t)
                    dot.background = k.rounded(c, 99f, p.line)
                    if (!fromHex) { syncing = true; hex.setText(KbTheme.hex(c)); syncing = false }
                    pv.themeOverride = t
                    pv.refresh()
                }
                listOf("Hue" to 360, "Saturation" to 100, "Brightness" to 100).forEachIndexed { j, (label, max) ->
                    val v = if (j == 0) hsv[0].toInt() else (hsv[j] * 100).toInt()
                    sliders.addView(k.slider(label, if (j == 0) "°" else "%", 0, max, v) { nv ->
                        hsv[j] = if (j == 0) nv.toFloat() else nv / 100f
                        applyColor(Color.HSVToColor(hsv), false)
                    })
                }
                hex.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (syncing) return
                        val c = try { Color.parseColor(s.toString().trim()) } catch (e: Exception) { return }
                        Color.colorToHSV(c, hsv)
                        applyColor(c, true)
                    }
                })
                head.setOnClickListener { sliders.visibility = if (sliders.visibility == View.GONE) View.VISIBLE else View.GONE }
                row.addView(sliders)
                k.addDivided(editors, row)
            }
            addView(k.eyebrow("Colours · tap one to adjust"))
            addView(editors, k.lp())
            addView(k.button("Save as my theme") {
                Themes.saveCustom(this@SettingsActivity, t)
                it.text = "Saved and applied"
            }, k.lp(top = 18))

            addView(k.eyebrow("Share as JSON"))
            val json = k.field("Paste a theme JSON here", mono = true).apply {
                minLines = 4
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            addView(json, k.lp())
            val status = k.text("", 13f, p.muted)
            val r = k.row()
            r.addView(k.button("Copy current", "secondary") {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("MyKey theme", t.toJson().toString(2)))
                status.text = "Copied to clipboard."
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = k.dp(8) })
            r.addView(k.button("Use pasted", "secondary") {
                try {
                    t = KbTheme.fromJson(JSONObject(json.text.toString())).copy(name = "Custom")
                    Themes.saveCustom(this@SettingsActivity, t)
                    reload()
                } catch (e: Exception) {
                    status.text = "That isn't a theme JSON: ${e.message}"
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(r, k.lp(top = 8))
            addView(status, k.lp(top = 6))
        }
    }

    // ---------- languages ----------

    private fun languages(): View {
        val pv = Preview(previewLang())
        val sub = mapOf(
            "en" to "English", "es" to "Spanish · ñ key and accents", "ru" to "Russian · ЙЦУКЕН",
            "ja" to "Japanese · flick or romaji, kana to kanji", "zh" to "Mandarin · pinyin to characters",
        )
        return page("Languages", "Switch: swipe up on the space bar, or Languages in the toolbar.", pv.box) {
            section("Your languages") {
                Langs.supported.forEach { l ->
                    divided(k.toggleRow(Langs.names[l] ?: l, sub[l] ?: "", l in Langs.enabled(prefs)) { on ->
                        val list = Langs.enabled(prefs).toMutableList()
                        if (on) { if (l !in list) list.add(l) } else list.remove(l)
                        if (list.isEmpty()) { reload(); return@toggleRow }
                        prefs.edit().putString("langs", list.joinToString(",")).apply()
                        pv.lang = if (on) l else list[0]
                        pv.refresh()
                    })
                }
            }
            choice("English layout", Langs.englishLayouts, prefs.getString("en_layout", "en_qwerty")!!) {
                prefs.edit().putString("en_layout", it).apply()
                pv.lang = "en"; pv.refresh()
            }
            choice("Japanese input", listOf("flick" to "Flick (12-key)", "romaji" to "Romaji (QWERTY)"), prefs.getString("ja_mode", "flick")!!) {
                prefs.edit().putString("ja_mode", it).apply()
                if ("ja" in Langs.enabled(prefs)) { pv.lang = "ja"; pv.refresh() }
            }
            section("Switching") { toggles("multilingual", "lang_space_long") }
        }
    }

    // ---------- feature pages ----------

    private fun featurePage(title: String, subtitle: String, category: String, exclude: Set<String> = emptySet()): View =
        page(title, subtitle) {
            section("Switches") {
                Features.all.filter { it.category == category && it.id !in exclude }.forEach { ft ->
                    divided(k.toggleRow(ft.title, ft.desc, f(ft.id)) { setF(ft.id, it) })
                }
            }
        }

    private fun typing(): View {
        val pv = Preview(previewLang())
        return page("Typing", "Try gestures and timing live above.", pv.box) {
            section("Switches") {
                Features.all.filter { it.category == "Typing" && it.id !in setOf("number_row", "hints", "key_preview") }.forEach { ft ->
                    divided(k.toggleRow(ft.title, ft.desc, f(ft.id)) { setF(ft.id, it); pv.refresh() })
                }
            }
            section("Timing") {
                divided(k.slider("Long-press delay", "ms", 200, 700, prefs.getInt("long_press_ms", 350)) { save("long_press_ms", it); pv.refresh() })
                divided(k.slider("Glide starts after", "× key", 5, 20, prefs.getInt("glide_start", 9)) { save("glide_start", it); pv.refresh() })
                divided(k.slider("Backspace speed", "", 1, 5, prefs.getInt("repeat_speed", 3)) { save("repeat_speed", it); pv.refresh() })
                divided(k.slider("Morse letter pause", "ms", 300, 1500, prefs.getInt("morse_delay", 650)) { save("morse_delay", it) })
            }
            choice("Glide trail", KbConfig.trails, prefs.getString("trail_style", "comet")!!) {
                prefs.edit().putString("trail_style", it).apply(); pv.refresh()
            }
            if (f("adaptive_keys")) addView(k.button("Forget how I aim", "secondary") {
                prefs.edit().remove("aim").apply(); it.text = "Forgotten. MyKey will relearn as you type."
            }, k.lp(top = 18))
        }
    }

    private fun longPressPage(): View {
        val pv = Preview(previewLang())
        val layouts = listOf(Langs.layoutFor("en", prefs) to "English", "es" to "Español", "ru" to "Русский", "symbols" to "Symbols")
        var layout = Langs.layoutFor("en", prefs)
        var key: String? = null
        val editor = k.column()

        fun loadPreview() {
            pv.custom = Layouts.load(this, layout, f("custom_layouts"))
            pv.refresh()
        }
        fun currentKey(): Key? = key?.let { kk -> Layouts.load(this, layout, f("custom_layouts")).rows.flatten().firstOrNull { it.type == null && it.out == kk } }

        lateinit var render: () -> Unit
        render = render@{
            editor.removeAllViews()
            val kk = key
            val cur = currentKey()
            if (kk == null || cur == null) {
                editor.addView(k.column().apply {
                    background = k.rounded(p.surface, 20f)
                    setPadding(k.dp(20), k.dp(26), k.dp(20), k.dp(26))
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(k.iconTile("hold", p.accent, 44))
                    addView(k.text("Tap a key on the keyboard above", 17f, p.fg, 650).apply { gravity = Gravity.CENTER }, k.lp(top = 12))
                    addView(k.text("You'll choose what appears when you hold it, in what order, and which one shows as the small hint.", 14f, p.muted).apply { gravity = Gravity.CENTER }, k.lp(top = 4))
                }, k.lp(top = 12))
                return@render
            }
            val opts = cur.long.toMutableList()
            var hint: String? = cur.hint
            fun save() { LongPress.set(prefs, layout, kk, opts, hint); loadPreview() }

            val card = k.card().apply { setPadding(k.dp(18), k.dp(16), k.dp(18), k.dp(16)) }
            val head = k.row()
            head.addView(k.text(kk, 22f, p.accentFg, 700, display = true).apply {
                gravity = Gravity.CENTER; background = k.rounded(p.accent, 12f)
            }, LinearLayout.LayoutParams(k.dp(44), k.dp(44)))
            head.addView(k.column().apply {
                setPadding(k.dp(12), 0, 0, 0)
                addView(k.text("Hold “$kk”", 17f, p.fg, 650))
                addView(k.text(if (LongPress.has(prefs, layout, kk)) "Edited · ${opts.size} options" else "${opts.size} options", 13f, p.muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(k.text("Reset", 14f, p.accent, 650).apply {
                setPadding(k.dp(12), k.dp(8), k.dp(4), k.dp(8))
                setOnClickListener { LongPress.reset(prefs, layout, kk); loadPreview(); render() }
            })
            card.addView(head)

            // Chips: drag to reorder, tap to make it the hint, × to remove, + to add.
            val strip = k.row().apply { setPadding(0, k.dp(14), 0, k.dp(4)) }
            val addField = k.field("Type characters or words, then Add").apply { visibility = View.GONE }
            val addBtn = k.button("Add") {}.apply { visibility = View.GONE }
            val drag = com.mobilekey.ui.DragReorder(strip, skip = { it.tag == "add" }) { from, to ->
                opts.add(to, opts.removeAt(from)); save()
            }
            opts.forEach { o ->
                val isHint = (hint ?: opts.firstOrNull()) == o && hint != ""
                val chip = k.row().apply {
                    background = if (isHint) k.rounded(withAlpha(p.accent, 0x26), 99f, p.accent) else k.rounded(p.surface2, 99f)
                    setPadding(k.dp(14), 0, k.dp(6), 0)
                    addView(k.text(o, 17f, p.fg, 560))
                    addView(com.mobilekey.ui.IconView(this@SettingsActivity, "close", p.muted).apply {
                        contentDescription = "Remove $o"
                        setOnClickListener { opts.remove(o); if (hint == o) hint = null; save(); render() }
                    }, LinearLayout.LayoutParams(k.dp(28), k.dp(28)))
                    setOnClickListener { hint = o; save(); render() }
                }
                strip.addView(chip, LinearLayout.LayoutParams(-2, k.dp(40)).apply { rightMargin = k.dp(8) })
                drag.attach(chip, chip, immediate = false)
            }
            strip.addView(com.mobilekey.ui.IconView(this, "plus", p.accent).apply {
                tag = "add"
                contentDescription = "Add option"
                background = k.rounded(withAlpha(p.accent, 0x1f), 99f)
                setOnClickListener { addField.visibility = View.VISIBLE; addBtn.visibility = View.VISIBLE; addField.requestFocus() }
            }, LinearLayout.LayoutParams(k.dp(40), k.dp(40)))
            card.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
            card.addView(k.text("Hold and drag to reorder · tap to use as the key's hint · × removes", 12.5f, p.muted), k.lp(top = 6))

            val addRow = k.row().apply { setPadding(0, k.dp(12), 0, 0) }
            addRow.addView(addField, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = k.dp(8) })
            addBtn.setOnClickListener {
                val t = addField.text.toString().trim()
                if (t.isNotEmpty()) {
                    val parts = if (t.contains(' ')) t.split(' ').filter { it.isNotEmpty() }
                        else if (t.codePointCount(0, t.length) <= 6) t.codePoints().toArray().map { String(Character.toChars(it)) } else listOf(t)
                    opts.addAll(parts.filter { it !in opts }); save(); render()
                }
            }
            addRow.addView(addBtn)
            card.addView(addRow)
            editor.addView(card, k.lp(top = 12))

            val hintCard = k.card()
            hintCard.addView(k.toggleRow("Show a hint on the key", "The small character in the corner of the key.", hint != "") { on ->
                hint = if (on) null else ""; save(); render()
            })
            editor.addView(hintCard, k.lp(top = 12))
        }

        pv.onKeyTap = { t -> key = t.lowercase().takeIf { s2 -> currentKeyExists(layout, s2) } ?: t; render() }
        loadPreview()
        return page("Hold keys", "Tap a key above to edit what it gives on long-press.", pv.box) {
            choice("Layout", layouts, layout) { layout = it; key = null; loadPreview(); render() }
            addView(editor, k.lp())
            render()
            addView(k.button("Reset every key in this layout", "secondary") { LongPress.reset(prefs, layout, null); key = null; loadPreview(); render() }, k.lp(top = 22))
        }
    }

    private fun currentKeyExists(layout: String, out: String) =
        Layouts.load(this, layout, f("custom_layouts")).rows.flatten().any { it.type == null && it.out == out }

    private fun toolbarPage(): View {
        val theme = Themes.byName(this, KbConfig.themeName(this, prefs))
        val previewRow = k.row().apply {
            background = k.rounded(theme.bg, 18f)
            setPadding(k.dp(6), 0, k.dp(6), 0)
        }
        fun refreshPreview() {
            previewRow.removeAllViews()
            previewRow.addView(com.mobilekey.ui.IconView(this, "grid", theme.fg), LinearLayout.LayoutParams(k.dp(40), k.dp(50)))
            Toolbar.visible(prefs).forEach { item ->
                previewRow.addView(com.mobilekey.ui.IconView(this, item.icon, theme.fg), LinearLayout.LayoutParams(0, k.dp(50), 1f))
            }
        }
        refreshPreview()
        return page("Toolbar", "Drag ≡ to reorder, switch to show or hide.", previewRow) {
            // (theme button themes are further down this page)
            section("Behaviour") {
                toggles("quick_toolbar")
                divided(k.toggleRow("Show automatically", "When there's nothing to suggest. Off: only when you tap the grid button.",
                    prefs.getBoolean("toolbar_auto", true)) { prefs.edit().putBoolean("toolbar_auto", it).apply() })
            }
            addView(k.eyebrow("Buttons"))
            val list = k.column().apply { background = k.rounded(p.surface, 20f); setPadding(k.dp(10), k.dp(6), k.dp(6), k.dp(6)) }
            val favSection = k.column()
            val hidden = Toolbar.hidden(prefs)
            val order = Toolbar.order(prefs).toMutableList()
            val drag = com.mobilekey.ui.DragReorder(list) { from, to ->
                order.add(to, order.removeAt(from)); Toolbar.setOrder(prefs, order); refreshPreview()
            }
            order.forEach { item ->
                val featureOff = item.feature != null && !f(item.feature)
                val r = k.row().apply {
                    setPadding(k.dp(6), k.dp(6), 0, k.dp(6))
                    minimumHeight = k.dp(56)
                    background = k.rounded(p.surface, 14f)
                }
                r.addView(k.iconTile(item.icon, if (featureOff) p.line else p.accent, 34).apply { alpha = if (featureOff) 0.6f else 1f })
                val texts = k.column().apply { setPadding(k.dp(12), 0, k.dp(6), 0) }
                texts.addView(k.text(item.name, 15.5f, p.fg, 560))
                if (featureOff) texts.addView(k.text("Turned off in Panels & chips", 12.5f, p.muted))
                r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(com.mobilekey.ui.Toggle(this@SettingsActivity, p).apply {
                    set(item.id !in hidden, false)
                    onChange = { on -> Toolbar.setHidden(prefs, item.id, !on); refreshPreview() }
                })
                val handle = com.mobilekey.ui.IconView(this@SettingsActivity, "drag", p.muted).apply { contentDescription = "Drag to reorder ${item.name}" }
                r.addView(handle, LinearLayout.LayoutParams(k.dp(44), k.dp(44)).apply { leftMargin = k.dp(6) })
                list.addView(r, LinearLayout.LayoutParams(-1, -2))
                drag.attach(handle, r, immediate = true)
            }
            addView(list, k.lp())

            // Which themes the theme button switches between.
            addView(k.eyebrow("Themes for the theme button"))
            addView(k.text("Each tap of the theme button moves to the next ticked theme.", 13.5f, p.muted).apply { setPadding(k.dp(18), 0, k.dp(18), k.dp(10)) })
            val current = prefs.getString("theme", "Graphite")
            val favs = (prefs.getString("fav_themes", null) ?: listOf(current, "Crimson Noir", "Abyss").distinct().joinToString(","))
                .split(',').filter { it.isNotEmpty() }.toMutableList()
            val favCard = k.card()
            Themes.all(this@SettingsActivity).forEach { th ->
                val r = k.row().apply { setPadding(0, k.dp(8), 0, k.dp(8)) }
                r.addView(ThemeSwatch(this@SettingsActivity, th, p), LinearLayout.LayoutParams(k.dp(62), k.dp(42)).apply { rightMargin = k.dp(12) })
                r.addView(k.text(th.name, 15.5f, p.fg, 540), LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(com.mobilekey.ui.Toggle(this@SettingsActivity, p).apply {
                    set(th.name in favs, false)
                    onChange = { on ->
                        if (on) { if (th.name !in favs) favs.add(th.name) }
                        else if (favs.size > 1) favs.remove(th.name) else set(true, true)
                        prefs.edit().putString("fav_themes", favs.joinToString(",")).apply()
                    }
                })
                k.addDivided(favCard, r)
            }
            addView(favCard, k.lp())
            addView(k.button("Reset toolbar", "secondary") {
                prefs.edit().remove("toolbar_order").remove("toolbar_hidden").remove("toolbar_auto").apply(); reload()
            }, k.lp(top = 18))
        }
    }

    private fun tools(): View = page("Panels & chips", "Emoji, GIFs, clipboard, voice and smart chips.") {
        section("Toolbar and panels") { toggles("quick_toolbar", "emoji", "gifs", "clipboard", "clip_autoclear", "paste_chip", "snippets", "edit_toolbar", "undo_redo", "tool_theme", "tool_resize") }
        section("Smart chips") { toggles("sel_chips", "autofill_chips", "email_chips", "pass_gen") }
        section("Online GIF search") {
            toggles("gif_search") { reload() }
            if (f("gif_search")) {
                divided(k.text("Paste a free API key. GIPHY: developers.giphy.com → Create an app → API. Tenor: Google Cloud console → enable Tenor API → Credentials. Only your search words are sent, and only when you search.", 13.5f, p.muted).apply {
                    setPadding(0, k.dp(12), 0, k.dp(8))
                })
                val key = k.field("API key").apply {
                    setText(prefs.getString("gif_key", ""))
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(e: Editable?) { prefs.edit().putString("gif_key", e.toString().trim()).apply() }
                    })
                }
                addView(key, k.lp().apply { bottomMargin = k.dp(14) })
            }
        }
        if (f("gif_search")) choice("GIF provider", listOf("giphy" to "GIPHY", "tenor" to "Tenor"), prefs.getString("gif_provider", "giphy")!!) {
            prefs.edit().putString("gif_provider", it).apply()
        }
        section("Your GIFs and stickers") {
            val gifs = EmojiPanel.gifFiles(this@SettingsActivity)
            divided(k.navRow("Add GIFs or stickers", "${gifs.size} saved") {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/gif", "image/webp", "image/png"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }, REQ_GIF)
            })
            gifs.take(30).forEach { g ->
                divided(k.row().apply {
                    setPadding(0, k.dp(8), 0, k.dp(8))
                    addView(android.widget.ImageView(this@SettingsActivity).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        try {
                            val d = android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(g))
                            setImageDrawable(d); (d as? android.graphics.drawable.AnimatedImageDrawable)?.start()
                        } catch (_: Exception) {}
                    }, LinearLayout.LayoutParams(k.dp(56), k.dp(56)))
                    addView(k.text(g.name.substringAfter('_'), 14f, p.muted).apply {
                        setPadding(k.dp(12), 0, 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(k.text("Remove", 13.5f, p.danger, 600).apply { setOnClickListener { g.delete(); reload() } })
                })
            }
            divided(k.text("GIFs stay on this phone. Online GIF search would need internet access, which MyKey doesn't have by design.", 13f, p.muted).apply {
                setPadding(0, k.dp(12), 0, k.dp(14))
            })
        }
        section("Voice typing") {
            toggles("voice")
            divided(k.text("Tap the mic in the toolbar to hand off to your phone's voice keyboard (such as Google voice typing). It returns to MyKey when you're done. MyKey itself never records audio.", 13.5f, p.muted).apply {
                setPadding(0, k.dp(12), 0, k.dp(14))
            })
        }
        choice("Keyboard position", listOf("full" to "Full width", "left" to "One-handed left", "right" to "One-handed right", "float" to "Floating"),
            prefs.getString("hand_mode", "full")!!) { prefs.edit().putString("hand_mode", it).apply() }
        addView(k.text("Floating: drag the bar at the top of the keyboard to move it.", 13f, p.muted), k.lp(top = 8))
        section("Accessibility") { toggles("accessibility") }
    }

    private fun privacy(): View = page("Privacy", "What stays where.") {
        addView(k.column().apply {
            background = k.rounded(withAlpha(p.accent, 0x1c), 22f)
            setPadding(k.dp(18), k.dp(16), k.dp(18), k.dp(16))
            addView(k.text(if (f("gif_search")) "Online only for GIF search" else "Fully offline", 18f, p.fg, 740, display = true))
            addView(k.text("MyKey never sends what you type. Its only permission is internet access, used solely by online GIF search, which is off unless you turn it on, and then only when you open GIFs or search them. No microphone, contacts or files. Learned words, shortcuts, clipboard history and settings live in this app's private storage on the phone.", 14f, p.muted), k.lp(top = 4))
        }, k.lp(top = 8))
        section("Switches") { toggles("incognito", "lock_private", "password_detect", "stats") }
        section("System spell check") {
            toggles("spellcheck_words")
            divided(k.navRow("Choose MyKey as spell checker", "Open") {
                val tries = listOf(Intent("android.settings.SPELL_CHECKER_SETTINGS"), Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                for (i in tries) { try { startActivity(i); break } catch (_: Exception) {} }
            })
        }
    }

    // ---------- learning ----------

    private fun learning(): View = page("Learning", "Words and habits, on this phone only.") {
        section("Switches") {
            Features.all.filter { it.category == "Learning" }.forEach { ft ->
                divided(k.toggleRow(ft.title, ft.desc, f(ft.id)) { setF(ft.id, it) })
            }
        }

        addView(k.eyebrow("Learned words"))
        val add = k.row()
        val input = k.field("Add a word")
        add.addView(input, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = k.dp(8) })
        val list = k.card()
        val search = k.field("Search learned words")
        fun render() {
            list.removeAllViews()
            val q = search.text.toString().trim().lowercase()
            var shown = 0
            Langs.enabled(prefs).filter { it !in Langs.cjk }.forEach { l ->
                user.allWords(l).filter { q.isEmpty() || it.word.lowercase().contains(q) }.take(200).forEach { w ->
                    shown++
                    val r = k.row().apply { setPadding(0, k.dp(12), 0, k.dp(12)) }
                    r.addView(k.text(w.word, 15.5f, p.fg, 520), LinearLayout.LayoutParams(0, -2, 1f))
                    r.addView(k.text("×${w.count}", 13f, p.muted).apply { setPadding(0, 0, k.dp(10), 0) })
                    if (Langs.enabled(prefs).size > 1) r.addView(k.badge(l.uppercase()), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = k.dp(8) })
                    r.addView(k.text("Remove", 13.5f, p.danger, 600).apply {
                        setPadding(k.dp(10), k.dp(6), 0, k.dp(6))
                        setOnClickListener {
                            user.remove(l, w.word)
                            r.animate().alpha(0f).setDuration(160).withEndAction { render() }.start()
                        }
                    })
                    k.addDivided(list, r)
                }
            }
            if (shown == 0) list.addView(k.text(
                if (q.isEmpty()) "Nothing learned yet. New words you type twice show up here." else "No learned word contains “$q”.",
                14f, p.muted,
            ).apply { setPadding(0, k.dp(16), 0, k.dp(16)) })
        }
        add.addView(k.button("Add") {
            val w = input.text.toString().trim()
            if (w.isNotEmpty() && !w.contains(' ')) {
                user.add(previewLang(), w)
                input.setText("")
                render()
            }
        })
        addView(add, k.lp(bottom = 8))
        addView(search, k.lp(bottom = 10))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })
        addView(list, k.lp())
        render()

        val blocked = user.blockedWords()
        if (blocked.isNotEmpty()) section("Never suggested") {
            blocked.forEach { w ->
                val r = k.row().apply { setPadding(0, k.dp(12), 0, k.dp(12)) }
                r.addView(k.text(w, 15.5f), LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(k.text("Allow again", 13.5f, p.accent, 600).apply {
                    setOnClickListener { user.unblock(w); r.animate().alpha(0f).setDuration(160).withEndAction { r.visibility = View.GONE }.start() }
                })
                divided(r)
            }
        }

        var armed = false
        addView(k.button("Delete everything learned", "danger") { b ->
            if (!armed) {
                armed = true
                b.text = "Tap again to delete. Shortcuts stay."
                handler.postDelayed({ armed = false; b.text = "Delete everything learned" }, 3500)
            } else {
                user.clearLearned()
                b.text = "Deleted"
                render()
            }
        }, k.lp(top = 22))
    }

    // ---------- shortcuts ----------

    private fun shortcuts(): View = page("Shortcuts", "Type the short form, press space.") {
        val list = k.card()
        fun render() {
            list.removeAllViews()
            if (user.allShortcuts().isEmpty()) list.addView(k.text("No shortcuts yet.", 14f, p.muted).apply { setPadding(0, k.dp(16), 0, k.dp(16)) })
            user.allShortcuts().toList().forEach { (key, v) ->
                val r = k.row().apply { setPadding(0, k.dp(13), 0, k.dp(13)) }
                r.addView(k.text(key, 15f, p.accent, 650).apply {
                    background = k.rounded(withAlpha(p.accent, 0x1c), 8f)
                    setPadding(k.dp(8), k.dp(2), k.dp(8), k.dp(2))
                })
                r.addView(k.text(v, 15.5f).apply {
                    setPadding(k.dp(12), 0, k.dp(8), 0)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
                r.addView(k.text("Remove", 13.5f, p.danger, 600).apply {
                    setOnClickListener { user.removeShortcut(key); r.animate().alpha(0f).setDuration(160).withEndAction { render() }.start() }
                })
                k.addDivided(list, r)
            }
        }
        addView(k.eyebrow("New shortcut"))
        val from = k.field("Short form, e.g. addr")
        val to = k.field("Full text, e.g. 221B Baker Street")
        val error = k.text("", 13f, p.danger)
        addView(from, k.lp(bottom = 8))
        addView(to, k.lp(bottom = 8))
        addView(error)
        addView(k.button("Add shortcut") {
            val key = from.text.toString().trim().lowercase()
            val v = to.text.toString()
            error.text = when {
                key.isEmpty() || v.isEmpty() -> "Fill in both boxes."
                key.contains(' ') -> "The short form has to be one word."
                else -> { user.putShortcut(key, v); from.setText(""); to.setText(""); render(); "" }
            }
        }, k.lp(top = 4))
        addView(k.eyebrow("Your shortcuts"))
        addView(list, k.lp())
        addView(k.text("Smart shortcuts: {date}, {time} and {day} are filled in, and {cursor} sets where the cursor lands. Example: mtg → Meeting at {time} about {cursor}", 13f, p.muted), k.lp(top = 10))
        render()

        addView(k.eyebrow("Snippets"))
        val snip = k.field("Longer text: an address, a template…", mono = false).apply {
            isSingleLine = false; minLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        addView(snip, k.lp(bottom = 8))
        val snipList = k.card()
        fun renderSnips() {
            snipList.removeAllViews()
            val all = Snippets.all(this@SettingsActivity)
            if (all.isEmpty()) snipList.addView(k.text("No snippets yet. They appear in the keyboard's snippets panel.", 14f, p.muted).apply { setPadding(0, k.dp(16), 0, k.dp(16)) })
            all.forEachIndexed { i, t ->
                k.addDivided(snipList, k.row().apply {
                    setPadding(0, k.dp(12), 0, k.dp(12))
                    addView(k.text(t, 15f).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(k.text("Remove", 13.5f, p.danger, 600).apply {
                        setPadding(k.dp(10), 0, 0, 0)
                        setOnClickListener { Snippets.save(this@SettingsActivity, all.filterIndexed { j, _ -> j != i }); renderSnips() }
                    })
                })
            }
        }
        addView(k.button("Save snippet") {
            val t = snip.text.toString().trim()
            if (t.isNotEmpty()) { Snippets.save(this@SettingsActivity, Snippets.all(this@SettingsActivity) + t); snip.setText(""); renderSnips() }
        })
        addView(snipList, k.lp(top = 10))
        renderSnips()
    }

    // ---------- layouts ----------

    private fun layouts(): View {
        val names = Layouts.editable
        val labels = mapOf(
            "en_qwerty" to "QWERTY", "en_qwertz" to "QWERTZ", "en_azerty" to "AZERTY", "en_dvorak" to "Dvorak",
            "en_colemak" to "Colemak", "es" to "Español", "ru" to "Русский", "ja_flick" to "日本語 フリック",
            "ja_romaji" to "日本語 ローマ字", "zh_pinyin" to "中文 拼音", "symbols" to "Symbols", "numpad" to "Number pad",
        )
        val pv = Preview(previewLang())
        var current = names[0]
        val status = k.text("", 13.5f, p.muted)
        val editor = k.field("", mono = true).apply {
            gravity = Gravity.TOP
            minLines = 14
            setHorizontallyScrolling(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val reparse = Runnable {
            try {
                pv.custom = Layouts.parse(editor.text.toString())
                pv.refresh()
                status.setTextColor(p.muted)
                status.text = if (Layouts.overrideFile(this, current).exists()) "Edited version · preview is live" else "Built-in version · preview is live"
            } catch (e: Exception) {
                status.setTextColor(p.danger)
                status.text = "Can't read this yet: ${e.message}"
            }
        }
        fun load(name: String) {
            current = name
            editor.setText(Layouts.text(this, name))
            handler.removeCallbacks(reparse)
            reparse.run()
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handler.removeCallbacks(reparse)
                handler.postDelayed(reparse, 350)
            }
        })
        return page("Layouts", "Every key is editable JSON.", pv.box) {
            section("Use") { toggles("custom_layouts") }
            choice("Layout", names.map { it to (labels[it] ?: it) }, current) { load(it) }
            addView(status, k.lp(top = 12, bottom = 6))
            addView(editor, k.lp())
            val r = k.row()
            r.addView(k.button("Save") {
                try {
                    Layouts.save(this@SettingsActivity, current, editor.text.toString())
                    status.setTextColor(p.accent)
                    status.text = "Saved. The keyboard uses it next time it opens."
                } catch (e: Exception) {
                    status.setTextColor(p.danger)
                    status.text = "Not saved: ${e.message}"
                }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = k.dp(8) })
            r.addView(k.button("Restore built-in", "secondary") {
                Layouts.reset(this@SettingsActivity, current)
                load(current)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(r, k.lp(top = 12))
            addView(k.text(
                "A key is \"q\" or an object: label (what it shows), out (what it types), long (long-press options), flick (centre, left, up, right, down), w (width), type (shift, backspace, space, enter, symbols, lang, emoji, voice, settings, kana_mod, cursor_left, cursor_right).",
                13f, p.muted,
            ), k.lp(top = 16))
            load(current)
        }
    }

    // ---------- stats ----------

    private fun stats(): View = page("Stats", "Counted on this phone only.") {
        val st = Stats.get(this@SettingsActivity)
        if (!f("stats")) addView(k.text("Stats are turned off in Privacy, so these numbers aren't growing.", 13.5f, p.muted), k.lp(top = 6))
        val big = k.row().apply { gravity = Gravity.TOP }
        listOf(st.get("words").toString() to "words typed", st.wpm().toString() to "words per minute").forEachIndexed { i, (n, l) ->
            big.addView(k.column().apply {
                background = k.rounded(p.surface, 22f)
                setPadding(k.dp(18), k.dp(16), k.dp(18), k.dp(16))
                addView(k.text(n, 34f, p.accent, 800, display = true))
                addView(k.text(l, 13.5f, p.muted))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 1) leftMargin = k.dp(10) })
        }
        addView(big, k.lp(top = 8))
        section("Details") {
            val corr = st.get("corrections")
            val undo = st.get("undos")
            listOf(
                "Key presses" to st.get("keys").toString(),
                "Autocorrections" to corr.toString(),
                "Kept autocorrections" to if (corr == 0L) "–" else "${((corr - undo).coerceAtLeast(0) * 100 / corr)}%",
                "Glided words" to st.get("glides").toString(),
                "Emoji sent" to st.get("emoji").toString(),
                "Pastes" to st.get("pastes").toString(),
                "Voice sessions" to st.get("voice").toString(),
                "Active typing time" to "${st.get("active_ms") / 60000} min",
            ).forEach { (a, b) ->
                divided(k.row().apply {
                    setPadding(0, k.dp(13), 0, k.dp(13))
                    addView(k.text(a, 15.5f), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(k.text(b, 15.5f, p.fg, 650))
                })
            }
        }
        val top = Langs.enabled(prefs).filter { it !in Langs.cjk }.flatMap { user.allWords(it) }.sortedByDescending { it.count }.take(10)
        if (top.isNotEmpty()) section("Your most used words") {
            top.forEach { w ->
                divided(k.row().apply {
                    setPadding(0, k.dp(12), 0, k.dp(12))
                    addView(k.text(w.word, 15.5f), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(k.text("×${w.count}", 14f, p.muted))
                })
            }
        }
        addView(k.button("Reset stats", "danger") { st.reset(); reload() }, k.lp(top = 22))
    }

    // ---------- backup ----------

    private var backupStatus = ""

    private fun backup(): View = page("Backup", "One file with everything.") {
        addView(k.text("The file holds your learned words, habits, kanji choices, shortcuts, pinned clipboard items, look and edited layouts. It isn't encrypted, so keep it private.", 14f, p.muted), k.lp(top = 6, bottom = 8))
        addView(k.button("Export to a file") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "mykey-backup.json")
            }, REQ_EXPORT)
        }, k.lp(top = 8))
        addView(k.button("Restore from a file", "secondary") {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, REQ_IMPORT)
        }, k.lp(top = 10))
        if (backupStatus.isNotEmpty()) addView(k.text(backupStatus, 14f, p.accent, 600), k.lp(top = 14))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val REQ_IMAGE = 11
        private const val REQ_EXPORT = 12
        private const val REQ_IMPORT = 13
        private const val REQ_GIF = 14
    }
}
