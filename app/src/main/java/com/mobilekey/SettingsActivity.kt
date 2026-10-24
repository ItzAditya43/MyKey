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
    }

    override fun onResume() {
        super.onResume()
        if (stack.lastOrNull() == "home") swap(build("home"), forward = true, animate = false)
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

    @Deprecated("Used below Android 13")
    override fun onBackPressed() {
        if (stack.size > 1) back() else super.onBackPressed()
    }

    private fun build(page: String): View = when (page) {
        "look" -> look()
        "theme_editor" -> themeEditor()
        "languages" -> languages()
        "typing" -> typing()
        "correction" -> featurePage("Autocorrect", "Fixes, capitals and punctuation.", "Correction")
        "tools" -> tools()
        "toolbar" -> toolbarPage()
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
        val bar = k.row().apply { setPadding(k.dp(12), k.dp(8), k.dp(16), k.dp(6)) }
        bar.addView(k.text("←", 20f, p.fg, 500).apply {
            gravity = Gravity.CENTER
            background = k.ripple(p.surface2, 99f)
            contentDescription = "Back"
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(k.dp(44), k.dp(44)))
        val titles = k.column().apply { setPadding(k.dp(14), 0, 0, 0) }
        titles.addView(k.text(title, 26f, p.fg, 760, display = true))
        if (subtitle != null) titles.addView(k.text(subtitle, 13.5f, p.muted))
        bar.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        if (sticky != null) root.addView(sticky, k.lp(top = 6, bottom = 4).apply { leftMargin = k.dp(12); rightMargin = k.dp(12) })
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val col = k.column().apply { setPadding(k.dp(16), k.dp(2), k.dp(16), k.dp(48)) }
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

    /** A row of chips where exactly one is selected. */
    private fun LinearLayout.choice(label: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        addView(k.eyebrow(label))
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

    private fun home(): View {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = k.column().apply { setPadding(k.dp(18), k.dp(18), k.dp(18), k.dp(48)) }
        scroll.addView(col)

        col.addView(k.text("PRIVATE KEYBOARD", 11.5f, p.accent, 650).apply { letterSpacing = 0.14f })
        col.addView(k.text("MyKey", 46f, p.fg, 800, display = true), k.lp(top = 2))
        col.addView(k.text("Everything it learns stays on this phone.", 15f, p.muted), k.lp(top = 2, bottom = 18))

        col.addView(statusCard(), k.lp(bottom = 12))
        col.addView(k.field("Try typing here…"), k.lp(bottom = 8))

        val themeName = prefs.getString("theme", "Graphite") ?: "Graphite"
        val langs = Langs.enabled(prefs).joinToString(", ") { Langs.names[it] ?: it }
        val typing = Features.all.filter { it.category == "Typing" }
        val learned = Langs.enabled(prefs).sumOf { user.allWords(it).size }
        val st = Stats.get(this)
        val tiles = listOf(
            Tile("Aa", "Look & size", "$themeName · ${prefs.getInt("scale", KbConfig.DEF_SCALE)}% size", "look"),
            Tile("文", "Languages", langs, "languages"),
            Tile("⌨", "Typing", "${typing.count { f(it.id) }} of ${typing.size} on", "typing"),
            Tile("✓", "Autocorrect", if (f("autocorrect")) (if (f("ac_aggressive")) "On · aggressive" else "On") else "Off", "correction"),
            Tile("☺", "Tools", "Emoji, clipboard, voice, one-handed", "tools"),
            Tile("⋯", "Toolbar", "${Toolbar.visible(prefs).size} buttons above the keys", "toolbar"),
            Tile("✦", "Learning", if (f("incognito")) "Incognito is on" else "$learned words learned", "learning"),
            Tile("↦", "Shortcuts", "${user.allShortcuts().size} shortcuts", "shortcuts"),
            Tile("{ }", "Layouts", "Edit keys as JSON", "layouts"),
            Tile("▲", "Stats", "${st.get("words")} words · ${st.wpm()} wpm", "stats"),
            Tile("⇅", "Backup", "Export or restore everything", "backup"),
        )
        tiles.chunked(2).forEach { pair ->
            val r = k.row().apply { gravity = Gravity.TOP }
            pair.forEachIndexed { i, t ->
                r.addView(tile(t), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i == 1) leftMargin = k.dp(10)
                })
            }
            col.addView(r, k.lp(top = 10))
        }
        col.addView(k.row().apply {
            background = k.ripple(p.surface, 22f)
            setPadding(k.dp(18), k.dp(16), k.dp(18), k.dp(16))
            addView(k.text("Privacy", 16f, p.fg, 600), LinearLayout.LayoutParams(0, -2, 1f))
            addView(k.badge(if (f("gif_search")) "GIF SEARCH ONLINE" else "OFFLINE", p.accent))
            setOnClickListener { open("privacy") }
        }, k.lp(top = 10))
        col.addView(k.text("Open these settings from the gear in the keyboard's toolbar, or from the language panel (long-press the globe key).", 13f, p.muted).apply {
            gravity = Gravity.CENTER
        }, k.lp(top = 22))
        return scroll
    }

    private class Tile(val glyph: String, val title: String, val summary: String, val page: String)

    private fun tile(t: Tile) = k.column().apply {
        background = k.ripple(p.surface, 22f)
        setPadding(k.dp(16), k.dp(16), k.dp(16), k.dp(16))
        minimumHeight = k.dp(128)
        addView(k.text(t.glyph, 17f, p.accent, 700, display = true).apply {
            gravity = Gravity.CENTER
            background = k.rounded(withAlpha(p.accent, 0x24), 99f)
        }, LinearLayout.LayoutParams(k.dp(40), k.dp(40)))
        addView(k.text(t.title, 16.5f, p.fg, 640), k.lp(top = 14))
        addView(k.text(t.summary, 13f, p.muted).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, k.lp(top = 2))
        setOnClickListener { open(t.page) }
    }

    private fun statusCard(): View {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith(packageName) == true
        if (current) {
            return k.row().apply {
                background = k.rounded(withAlpha(p.accent, 0x1c), 22f)
                setPadding(k.dp(18), k.dp(16), k.dp(18), k.dp(16))
                addView(View(this@SettingsActivity).apply { background = k.rounded(p.accent, 99f) }, LinearLayout.LayoutParams(k.dp(10), k.dp(10)))
                addView(k.text("MyKey is your keyboard", 15.5f, p.fg, 600).apply { setPadding(k.dp(12), 0, 0, 0) })
            }
        }
        return k.column().apply {
            background = k.rounded(p.accent, 24f)
            setPadding(k.dp(20), k.dp(18), k.dp(20), k.dp(20))
            addView(k.text(if (enabled) "One step left" else "Two steps to start", 22f, p.accentFg, 760, display = true))
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

        init {
            box.addView(line)
            box.addView(kv)
            kv.listener = object : KeyboardView.Listener {
                override fun onText(text: String) {
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

    private fun look(): View {
        val pv = Preview(previewLang())
        return page("Look & size", "Changes show live above.", pv.box) {
            addView(k.eyebrow("Theme"))
            val swatches = ArrayList<ThemeSwatch>()
            val current = prefs.getString("theme", "Graphite")
            val items = Themes.all(this@SettingsActivity).map { t ->
                k.column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    val s = ThemeSwatch(this@SettingsActivity, t, p).also { it.picked = t.name == current }
                    swatches.add(s)
                    addView(s, LinearLayout.LayoutParams(-2, -2))
                    addView(k.text(t.name, 12.5f, p.muted, 500), k.lp(-2, top = 6))
                    setOnClickListener {
                        prefs.edit().putString("theme", t.name).remove("key_style").apply()
                        swatches.forEach { it.picked = it === s }
                        pv.refresh()
                    }
                }
            }
            addView(k.chipRow(items))
            addView(k.button("Create your own theme", "secondary") { open("theme_editor") }, k.lp(top = 12))

            // Favourites for the toolbar's theme button (multi-select).
            addView(k.eyebrow("Favourites for the theme button"))
            val favs = (prefs.getString("fav_themes", null) ?: listOf(current, "Crimson Noir", "Abyss").joinToString(","))
                .split(',').filter { it.isNotEmpty() }.toMutableList()
            val favChips = ArrayList<TextView>()
            Themes.all(this@SettingsActivity).forEach { th ->
                lateinit var chip: TextView
                chip = k.chip(th.name, th.name in favs) {
                    if (th.name in favs) { if (favs.size > 1) favs.remove(th.name) } else favs.add(th.name)
                    prefs.edit().putString("fav_themes", favs.joinToString(",")).apply()
                    favChips.forEach { c -> val on = c.text.toString() in favs
                        c.setTextColor(if (on) p.accentFg else p.fg); c.background = k.ripple(if (on) p.accent else p.surface2, 99f) }
                }
                favChips.add(chip)
            }
            addView(k.chipRow(favChips))

            section("Keyboard size") {
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
            val theme = Themes.byName(this@SettingsActivity, current)
            choice("Key style", KbConfig.styles, prefs.getString("key_style", if (theme.amoled) "outline" else "filled")!!) {
                prefs.edit().putString("key_style", it).apply(); pv.refresh()
            }
            choice("Press animation", KbConfig.anims, prefs.getString("press_anim", "pop")!!) {
                prefs.edit().putString("press_anim", it).apply(); pv.refresh()
            }
            choice("Letter font", KbConfig.fonts, prefs.getString("key_font", "system")!!) {
                prefs.edit().putString("key_font", it).apply(); pv.refresh()
            }
            section("Details") {
                toggles("number_row", "hints", "key_preview", "key_shadow", "bold_labels") { pv.refresh() }
            }

            choice("Background effect", KbConfig.bgEffects, prefs.getString("bg_effect", "none")!!) {
                prefs.edit().putString("bg_effect", it).apply(); pv.refresh()
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
            section("App profiles") {
                toggles("per_app_theme") { reload() }
                if (f("per_app_theme")) {
                    val apps = (prefs.getString("recent_apps", "") ?: "").split(',').filter { it.isNotEmpty() }
                    if (apps.isEmpty()) divided(k.text("Type in a few apps first; they'll show up here.", 13.5f, p.muted).apply { setPadding(0, k.dp(12), 0, k.dp(14)) })
                    apps.forEach { a ->
                        val bits = listOfNotNull(
                            prefs.getString("app_theme_$a", null),
                            prefs.getString("app_split_$a", null)?.let { if (it == "on") "split" else "no split" },
                            prefs.getString("app_hand_$a", null),
                            prefs.getInt("app_scale_$a", 0).takeIf { it > 0 }?.let { "$it%" },
                        )
                        divided(k.navRow(appName(a), if (bits.isEmpty()) "Default" else bits.joinToString(" · ")) { open("apptheme:$a") })
                    }
                }
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

            choice("Key sound", Sounds.packs, prefs.getString("sound_pack", "system")!!) {
                prefs.edit().putString("sound_pack", it).apply()
                if (it != "system") Sounds.play(this@SettingsActivity, it, prefs.getInt("sound_volume", 50) / 100f)
                pv.refresh()
            }
            section("Feel") {
                toggles("haptics", "sounds", "sounds_silent") { pv.refresh() }
                divided(k.slider("Key sound volume", "%", 0, 100, prefs.getInt("sound_volume", 50)) { save("sound_volume", it) })
            }
            choice("Vibration strength", KbConfig.hapticLevels.mapIndexed { i, s -> i.toString() to s }, prefs.getInt("haptic_level", 1).toString()) {
                save("haptic_level", it.toInt()); pv.refresh()
            }
            addView(k.button("Reset look to default", "secondary") {
                prefs.edit().apply {
                    listOf("theme", "key_h", "gap", "radius", "label", "scale", "side_margin", "bottom_gap",
                        "key_style", "press_anim", "key_font", "haptic_level", "sound_volume", "bg_dim", "key_opacity").forEach { remove(it) }
                }.apply()
                reload()
            }, k.lp(top = 22))
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
        return page("Languages", "Switch with the globe key, or swipe up on space.", pv.box) {
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

    private fun toolbarPage(): View {
        val theme = Themes.byName(this, KbConfig.themeName(this, prefs))
        val previewRow = k.row().apply {
            background = k.rounded(theme.bg, 18f)
            setPadding(k.dp(6), 0, k.dp(6), 0)
        }
        fun refreshPreview() {
            previewRow.removeAllViews()
            previewRow.addView(com.mobilekey.ui.IconView(this, "grid", theme.fg), LinearLayout.LayoutParams(k.dp(40), k.dp(48)))
            Toolbar.visible(prefs).forEach { item ->
                previewRow.addView(com.mobilekey.ui.IconView(this, item.icon, theme.fg), LinearLayout.LayoutParams(0, k.dp(48), 1f))
            }
        }
        refreshPreview()
        return page("Toolbar", "The row of buttons above the keys.", previewRow) {
            section("Toolbar") {
                toggles("quick_toolbar")
                divided(k.toggleRow("Show automatically", "When there's nothing to suggest. Off: only when you tap the grid button.",
                    prefs.getBoolean("toolbar_auto", true)) { prefs.edit().putBoolean("toolbar_auto", it).apply() })
            }
            addView(k.eyebrow("Buttons · switch on or off, arrows to reorder"))
            val list = k.card()
            fun render() {
                list.removeAllViews()
                val order = Toolbar.order(prefs).toMutableList()
                val hidden = Toolbar.hidden(prefs)
                order.forEachIndexed { i, item ->
                    val featureOff = item.feature != null && !f(item.feature)
                    val r = k.row().apply { setPadding(0, k.dp(8), 0, k.dp(8)) }
                    r.addView(com.mobilekey.ui.IconView(this@SettingsActivity, item.icon, p.fg), LinearLayout.LayoutParams(k.dp(36), k.dp(36)))
                    val texts = k.column().apply { setPadding(k.dp(10), 0, k.dp(6), 0) }
                    texts.addView(k.text(item.name, 15.5f, p.fg, 520))
                    if (featureOff) texts.addView(k.text("Feature is off in Tools", 12.5f, p.muted))
                    r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                    fun arrow(label: String, enabled: Boolean, move: Int) = k.text(label, 18f, if (enabled) p.fg else p.line, 600).apply {
                        gravity = Gravity.CENTER
                        if (enabled) {
                            background = k.ripple(p.surface2, 99f)
                            setOnClickListener {
                                val it2 = order.removeAt(i); order.add(i + move, it2)
                                Toolbar.setOrder(prefs, order); render(); refreshPreview()
                            }
                        }
                    }
                    r.addView(arrow("↑", i > 0, -1), LinearLayout.LayoutParams(k.dp(36), k.dp(36)).apply { rightMargin = k.dp(4) })
                    r.addView(arrow("↓", i < order.size - 1, 1), LinearLayout.LayoutParams(k.dp(36), k.dp(36)).apply { rightMargin = k.dp(8) })
                    r.addView(com.mobilekey.ui.Toggle(this@SettingsActivity, p).apply {
                        set(item.id !in hidden, false)
                        onChange = { on -> Toolbar.setHidden(prefs, item.id, !on); refreshPreview() }
                    })
                    k.addDivided(list, r)
                }
            }
            addView(list, k.lp())
            render()
            addView(k.button("Reset toolbar", "secondary") {
                prefs.edit().remove("toolbar_order").remove("toolbar_hidden").remove("toolbar_auto").apply(); reload()
            }, k.lp(top = 18))
        }
    }

    private fun tools(): View = page("Tools", "Panels and modes from the toolbar.") {
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
