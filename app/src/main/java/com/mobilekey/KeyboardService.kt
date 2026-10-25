package com.mobilekey

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PointF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.mobilekey.engine.Candidate
import com.mobilekey.engine.Converter
import com.mobilekey.engine.Zenith
import com.mobilekey.engine.Dictionary
import com.mobilekey.engine.Glide
import com.mobilekey.engine.Helpers
import com.mobilekey.engine.Kana
import com.mobilekey.engine.Stats
import com.mobilekey.engine.Suggest
import com.mobilekey.engine.Suggestions
import com.mobilekey.engine.UserModel
import com.mobilekey.ui.IconView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot

class KeyboardService : InputMethodService(), KeyboardView.Listener {
    companion object {
        private val MORSE = mapOf(
            ".-" to "a", "-..." to "b", "-.-." to "c", "-.." to "d", "." to "e", "..-." to "f", "--." to "g", "...." to "h",
            ".." to "i", ".---" to "j", "-.-" to "k", ".-.." to "l", "--" to "m", "-." to "n", "---" to "o", ".--." to "p",
            "--.-" to "q", ".-." to "r", "..." to "s", "-" to "t", "..-" to "u", "...-" to "v", ".--" to "w", "-..-" to "x",
            "-.--" to "y", "--.." to "z", "-----" to "0", ".----" to "1", "..---" to "2", "...--" to "3", "....-" to "4",
            "....." to "5", "-...." to "6", "--..." to "7", "---.." to "8", "----." to "9", ".-.-.-" to ".", "--..--" to ",",
            "..--.." to "?", "-.-.--" to "!", ".----." to "'", "-..-." to "/", "---..." to ":", "-.-.-." to ";", ".-..-." to "\"",
            "-...-" to "=", ".-.-." to "+", "-....-" to "-", ".--.-." to "@", "-.--." to "(", "-.--.-" to ")",
        )
    }

    private enum class Mode { LETTERS, SYMBOLS, NUMPAD }

    private lateinit var prefs: SharedPreferences
    private lateinit var user: UserModel
    private lateinit var stats: Stats

    // Views
    private var root: FrameLayout? = null
    private var card: LinearLayout? = null
    private var handle: LinearLayout? = null
    private var body: LinearLayout? = null
    private var content: FrameLayout? = null
    private var sideLeft: LinearLayout? = null
    private var sideRight: LinearLayout? = null
    private var kv: KeyboardView? = null
    private var bar: CandidateBar? = null
    private var theme: KbTheme? = null
    private var navInset = 0

    // Engines
    private val dicts = ConcurrentHashMap<String, Dictionary>()
    private val converters = ConcurrentHashMap<String, Converter>()
    private val loading = HashSet<String>()
    private val loader = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // State
    private var langs = listOf("en")
    private var lang = "en"
    private var mode = Mode.LETTERS
    private var panel = "keys"
    private var toolbarOpen = false
    private var emojiSearch = false
    private val emojiQuery = StringBuilder()
    private var handMode = "full"
    private var info: EditorInfo? = null
    private var app: String? = null
    /** Package of the app you're typing in, for per-app themes. */
    private var pkg: String? = null
    private var lastClip: String? = null
    private var lastClipAt = 0L
    private var emojiSuggestions: List<String> = emptyList()
    /** Zenith's scores for the word being typed, keyed by "lang|text before|typed". */
    private var zenScores: Pair<String, Map<String, Float>>? = null
    private val zenRelease = Runnable { Zenith.release() }
    /** Emoji search and GIF search share the query line in the bar. */
    private var gifSearch = false
    private var gifQuery = ""
    private var inlineViews: List<View> = emptyList()
    private val morse = StringBuilder()
    private var selStart = 0
    private var selEnd = 0
    /** Words removed by swipe-delete or Clear all, for the Restore chip. */
    private var lastDeleted: String? = null
    private var passwordField = false
    private var emailField2 = false
    private var appNoAutocorrect = false
    private var resizeBar: LinearLayout? = null
    private val morseCommit = Runnable { commitMorse() }
    private var emailField = false
    private var suggestOn = true
    private var learnOn = true
    /** Glide works in any text field except passwords, even where apps turn suggestions off. */
    private var glideOk = true

    private val composing = StringBuilder()
    private val reading = StringBuilder()
    private var romaji = ""
    private var cjkCands: List<Candidate> = emptyList()
    private var prevWord: String? = null
    /** (what you typed, what autocorrect put, the separator) for undo-on-backspace. */
    private var lastCorrection: Triple<String, String, String>? = null
    /** Last glided word plus its trailing space, so a tap on an alternative can replace it. */
    private var lastGlide: String? = null
    private var glideAlts: List<String> = emptyList()
    private var autoSpace = false
    private var lastSpaceAt = 0L
    private var shift = 0
    private var userShift = false
    private var lastShiftTap = 0L

    private var offensiveCache: Set<String>? = null
    private var centers: Map<Char, PointF>? = null
    private val near: (Char, Char) -> Boolean = { a, b ->
        val m = centers ?: kv?.keyCenters()?.takeIf { it.isNotEmpty() }?.also { centers = it }
        val pa = m?.get(a)
        val pb = m?.get(b)
        pa != null && pb != null && hypot(pa.x - pb.x, pa.y - pb.y) < (kv?.unitWidth ?: 0f) * 1.5f
    }
    private val noNear: (Char, Char) -> Boolean = { _, _ -> false }

    private fun f(id: String) = Features.isOn(prefs, id)
    private val isCjk get() = lang in Langs.cjk && mode == Mode.LETTERS && suggestOn
    private val dp get() = resources.displayMetrics.density

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!f("clipboard")) return@OnPrimaryClipChangedListener
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = try { cm.primaryClip } catch (e: Exception) { null } ?: return@OnPrimaryClipChangedListener
        val sensitive = Build.VERSION.SDK_INT >= 33 &&
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        if (sensitive || clip.itemCount == 0) return@OnPrimaryClipChangedListener
        clip.getItemAt(0).coerceToText(this)?.toString()?.let {
            user.addClip(it)
            lastClip = it
            lastClipAt = SystemClock.uptimeMillis()
            main.post { if (composing.isEmpty()) refreshBar() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        user = UserModel.get(this)
        stats = Stats.get(this)
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).addPrimaryClipChangedListener(clipListener)
        loader.execute { try { EmojiPanel.preload(this) } catch (_: Throwable) {} }
    }

    override fun onDestroy() {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).removePrimaryClipChangedListener(clipListener)
        stats.flush()
        super.onDestroy()
    }

    // ---------- views ----------

    override fun onCreateInputView(): View {
        val view = KeyboardView(this).also { it.listener = this }
        val b = CandidateBar(this).also {
            it.onPick = ::pick
            it.onForget = ::forget
            it.onCandidate = ::pickCandidate
            it.onTool = ::tool
            it.onToggle = ::toggleToolbar
        }
        kv = view
        bar = b
        content = FrameLayout(this).apply { addView(view, FrameLayout.LayoutParams(-1, -2)) }
        sideLeft = sideColumn()
        sideRight = sideColumn()
        body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(sideLeft, LinearLayout.LayoutParams((52 * dp).toInt(), -1))
            addView(content, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sideRight, LinearLayout.LayoutParams((52 * dp).toInt(), -1))
        }
        handle = dragHandle()
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(handle, LinearLayout.LayoutParams(-1, (26 * dp).toInt()))
            addView(b, LinearLayout.LayoutParams(-1, (44 * dp).toInt()))
            addView(body, LinearLayout.LayoutParams(-1, -2))
        }
        root = FrameLayout(this).apply {
            addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            setOnApplyWindowInsetsListener { _, ins ->
                navInset = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
                applyPadding()
                ins
            }
        }
        applySettings()
        loadLayout()
        return root!!
    }

    private fun sideColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        addView(IconView(this@KeyboardService, "swap", 0).apply {
            contentDescription = "Move to other side"
            setOnClickListener { setHand(if (handMode == "left") "right" else "left") }
        }, LinearLayout.LayoutParams(-1, (56 * dp).toInt()))
        addView(IconView(this@KeyboardService, "expand", 0).apply {
            contentDescription = "Full width"
            setOnClickListener { setHand("full") }
        }, LinearLayout.LayoutParams(-1, (56 * dp).toInt()))
    }

    /** Floating mode: drag the pill to move the keyboard, tap the icon to dock it again. */
    private fun dragHandle() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        val pill = View(this@KeyboardService)
        addView(View(this@KeyboardService), LinearLayout.LayoutParams((36 * dp).toInt(), 1))
        addView(pill, LinearLayout.LayoutParams(0, (5 * dp).toInt(), 1f).apply { setMargins((60 * dp).toInt(), 0, (60 * dp).toInt(), 0) })
        addView(IconView(this@KeyboardService, "expand", 0).apply {
            contentDescription = "Dock keyboard"
            setOnClickListener { setHand("full") }
        }, LinearLayout.LayoutParams((36 * dp).toInt(), -1))
        var startX = 0f; var startY = 0f; var tx = 0f; var ty = 0f
        setOnTouchListener { _, e ->
            val c = card ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = e.rawX; startY = e.rawY; tx = c.translationX; ty = c.translationY }
                MotionEvent.ACTION_MOVE -> {
                    val r = root ?: return@setOnTouchListener true
                    c.translationX = (tx + e.rawX - startX).coerceIn(0f, (r.width - c.width).toFloat().coerceAtLeast(0f))
                    c.translationY = (ty + e.rawY - startY).coerceIn(0f, (r.height - c.height).toFloat().coerceAtLeast(0f))
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putFloat("float_x", c.translationX).putFloat("float_y", c.translationY).apply()
                    root?.requestLayout()
                }
            }
            true
        }
    }

    private fun applyPadding() {
        val c = card ?: return
        val side = (prefs.getInt("side_margin", 0) * dp).toInt()
        val bottom = (prefs.getInt("bottom_gap", 0) * dp).toInt()
        if (handMode == "float") c.setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), (8 * dp).toInt())
        else c.setPadding(side, 0, side, bottom + navInset)
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val r = root ?: return
        val c = card ?: return
        if (handMode != "float" || r.height == 0) return
        val loc = IntArray(2)
        c.getLocationInWindow(loc)
        // The app keeps its full height; only the floating card takes touches.
        outInsets.contentTopInsets = r.height
        outInsets.visibleTopInsets = r.height
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(loc[0], loc[1], loc[0] + c.width, loc[1] + c.height)
    }

    private fun setHand(m: String) {
        val p = pkg
        if (p != null && f("per_app_theme") && prefs.contains("app_hand_$p")) prefs.edit().putString("app_hand_$p", m).apply()
        else prefs.edit().putString("hand_mode", m).apply()
        handMode = m
        applyHandMode()
    }

    private fun applyHandMode() {
        val c = card ?: return
        val r = root ?: return
        val screenW = resources.displayMetrics.widthPixels
        val floating = handMode == "float"
        // Floating needs the keyboard window to fill the screen so the card can sit anywhere.
        val full = if (floating) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        window.window?.findViewById<View>(android.R.id.inputArea)?.let { a -> a.layoutParams = a.layoutParams.apply { height = full } }
        (r.parent as? View)?.let { p -> p.layoutParams = p.layoutParams?.apply { height = full } }
        r.layoutParams = (r.layoutParams ?: FrameLayout.LayoutParams(-1, full)).apply { height = full }
        handle?.visibility = if (floating) View.VISIBLE else View.GONE
        c.layoutParams = FrameLayout.LayoutParams(if (floating) (screenW * 0.82f).toInt() else -1, -2, if (floating) Gravity.TOP or Gravity.START else Gravity.BOTTOM)
        if (floating) {
            c.translationX = prefs.getFloat("float_x", screenW * 0.09f)
            c.translationY = prefs.getFloat("float_y", resources.displayMetrics.heightPixels * 0.45f)
            c.clipToOutline = true
        } else {
            c.translationX = 0f
            c.translationY = 0f
            c.clipToOutline = false
        }
        val oneHanded = handMode == "left" || handMode == "right"
        sideLeft?.visibility = if (handMode == "right") View.VISIBLE else View.GONE
        sideRight?.visibility = if (handMode == "left") View.VISIBLE else View.GONE
        content?.layoutParams = LinearLayout.LayoutParams(if (oneHanded) (screenW * 0.8f).toInt() else 0, -2, if (oneHanded) 0f else 1f)
        applyBackground()
        applyPadding()
        r.requestLayout()
    }

    private fun applyBackground() {
        val t = theme ?: return
        val c = card ?: return
        val img = BgImage.drawable(this)
        val effect = prefs.getString("bg_effect", "none")
        c.background = img
            ?: if (effect == "gradient" || effect == "aurora") EffectDrawable(t, effect == "aurora")
            else if (handMode == "float") GradientDrawable().apply { cornerRadius = 22 * dp; setColor(t.bg) } else ColorDrawable(t.bg)
        kv?.transparentBg = true
        bar?.applyTheme(t, transparent = true)
        listOf(sideLeft, sideRight, handle).forEach { col ->
            col ?: return@forEach
            for (i in 0 until col.childCount) (col.getChildAt(i) as? IconView)?.let { it.color = t.fg; it.invalidate() }
        }
        handle?.getChildAt(1)?.background = GradientDrawable().apply { cornerRadius = 3 * dp; setColor(KbTheme.mix(t.bg, t.fg, 0.35f)) }
    }

    // ---------- input lifecycle ----------

    override fun onStartInputView(ei: EditorInfo, restarting: Boolean) {
        super.onStartInputView(ei, restarting)
        info = ei
        pkg = ei.packageName
        rememberApp(ei.packageName)
        applySettings()
        val cls = ei.inputType and InputType.TYPE_MASK_CLASS
        val variation = ei.inputType and InputType.TYPE_MASK_VARIATION
        val password = (cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )) || (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val isPrivate = password && f("password_detect")
        val noSuggest = (ei.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
        val isWebField = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )
        val profile = f("per_app_theme") && pkg != null
        val appNoSuggest = profile && prefs.getString("app_suggest_$pkg", null) == "off"
        appNoAutocorrect = profile && prefs.getString("app_ac_$pkg", null) == "off"
        passwordField = password
        emailField2 = cls == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        suggestOn = cls == InputType.TYPE_CLASS_TEXT && !isPrivate && !noSuggest && !isWebField && !appNoSuggest
        glideOk = cls == InputType.TYPE_CLASS_TEXT && !password
        val locked = f("lock_private") && (getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
        learnOn = suggestOn && f("learn_words") && !f("incognito") && !locked &&
            (ei.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        app = if (f("per_app")) ei.packageName else null
        emailField = f("field_aware") && isWebField
        mode = if (f("field_aware") && cls in setOf(
                InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME,
            )) Mode.NUMPAD else Mode.LETTERS
        if (f("clip_autoclear")) user.clearClips(3_600_000)
        main.removeCallbacks(zenRelease)
        if (suggestOn && langs.any { zenOn(it) }) Zenith.ensure(this)

        composing.clear(); reading.clear(); romaji = ""; cjkCands = emptyList()
        prevWord = null; lastCorrection = null; lastGlide = null; autoSpace = false; lastDeleted = null
        selStart = ei.initialSelStart; selEnd = ei.initialSelEnd
        shift = 0; userShift = false
        emojiSearch = false; toolbarOpen = false
        showPanel("keys")
        kv?.enterLabel = enterLabel(ei)
        loadLayout()
        updateShift()
        refreshBar()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        Zenith.cancel()
        commitComposing()
        if (emailField2 && f("email_chips")) rememberEmail()
        inlineViews = emptyList()
        if (morse.isNotEmpty()) { main.removeCallbacks(morseCommit); commitMorse() }
        kv?.let { if (it.aimChanges > 0) saveAim(it) }
        stats.flush()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        // Give the model's memory back if the keyboard stays hidden for a while.
        main.removeCallbacks(zenRelease)
        if (f("zenith_unload")) main.postDelayed(zenRelease, 5 * 60_000L)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val hadSel = selStart != selEnd
        // You tapped somewhere in the text while a panel was open: go back to typing.
        if ((panel != "keys" || emojiSearch) && (newSelStart != oldSelStart || newSelEnd != oldSelEnd) &&
            SystemClock.uptimeMillis() - lastOwnEdit > 450) main.post { backToKeys() }
        selStart = newSelStart; selEnd = newSelEnd
        if (hadSel != (selStart != selEnd)) main.post { refreshBar() }
        // The cursor moved away from the word being typed (tap elsewhere, app changed the text).
        val active = composing.isNotEmpty() || reading.isNotEmpty() || romaji.isNotEmpty()
        if (active && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            composing.clear(); reading.clear(); romaji = ""; cjkCands = emptyList()
            currentInputConnection?.finishComposingText()
            refreshBar()
        }
        if (!active) updateShift()
    }

    // ---------- settings ----------

    private fun applySettings() {
        langs = Langs.enabled(prefs)
        lang = prefs.getString("lang", langs[0]) ?: langs[0]
        if (lang !in langs) lang = langs[0]
        handMode = KbConfig.handMode(prefs, pkg)
        stats.enabled = f("stats") && !f("incognito")
        theme = Themes.byName(this, KbConfig.themeName(this, prefs, pkg))
        kv?.let {
            KbConfig.apply(this, it, prefs, pkg)
            if (it.offsets.isEmpty()) loadAim(it)
        }
        Suggest.offensive = if (f("offensive_filter")) (offensiveCache ?: Suggest.loadOffensive(this).also { offensiveCache = it }) else emptySet()
        if (f("sounds")) Sounds.preload(this, prefs.getString("sound_pack", "system") ?: "system", f("sounds_silent"))
        bar?.toggleVisible = f("quick_toolbar")
        applyHandMode()
        for (l in langs) {
            if (dicts.containsKey(l) || converters.containsKey(l) || !loading.add(l)) continue
            loader.execute {
                try {
                    if (l in Langs.cjk) converters[l] = Converter.load(this, "dict/$l.txt", scoreIsLog = l == "zh")
                    else dicts[l] = Dictionary.load(this, l)
                } catch (_: Throwable) { }
                main.post { loading.remove(l); refreshBar() }
            }
        }
    }

    private fun loadLayout() {
        val view = kv ?: return
        val allow = f("custom_layouts")
        val l = when (mode) {
            Mode.SYMBOLS -> Layouts.load(this, "symbols", allow)
            Mode.NUMPAD -> Layouts.load(this, "numpad", allow)
            Mode.LETTERS -> KbConfig.lettersLayout(this, prefs, lang, emailField)
        }
        view.spaceLabel = Langs.names[lang] ?: lang
        view.applyLayout(l)
        centers = null
    }

    private fun enterLabel(ei: EditorInfo): String {
        if (!f("enter_action") || (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "↵"
        return when (ei.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> "Back"
            else -> "↵"
        }
    }

    // ---------- panels and toolbar ----------

    private var panelArg: String? = null
    /** Last time MyKey itself changed the text or cursor; cursor moves after this are yours. */
    private var lastOwnEdit = 0L

    private fun ownEdit() { lastOwnEdit = SystemClock.uptimeMillis() }

    /** Any panel or search open → back to the typing keys. */
    private fun backToKeys() {
        if (panel == "keys" && !emojiSearch) return
        emojiSearch = false
        gifSearch = false
        showPanel("keys")
    }

    @Deprecated("Still sent by most apps when the focused text box is tapped")
    override fun onViewClicked(focusChanged: Boolean) {
        @Suppress("DEPRECATION")
        super.onViewClicked(focusChanged)
        backToKeys()
    }
    private var emojiPanel: EmojiPanel? = null
    private var cachedPanelHeight = 0

    private fun showPanel(name: String) {
        val c = content ?: return
        val view = kv ?: return
        panel = name
        c.removeAllViews()
        val t = theme ?: return
        val h = view.desiredHeight()
        val p: View = when (name) {
            "emoji" -> {
                // Reuse the panel when nothing changed, so it opens instantly.
                val cached = emojiPanel
                val wantKey = "${t.name}|${EmojiPanel.recents(this).isNotEmpty()}"
                if (cached != null && cached.key == wantKey && cachedPanelHeight == h) {
                    (cached.parent as? ViewGroup)?.removeView(cached)
                    cached.reopen()
                    cached
                } else EmojiPanel(this, t, onEmoji = ::insertEmoji, onBack = { showPanel("keys") },
                    onBackspace = { onSpecial("backspace") }, onSearch = ::startEmojiSearch, onGif = null,
                    onSpace = { ownEdit(); currentInputConnection?.commitText(" ", 1) })
                    .also { emojiPanel = it; cachedPanelHeight = h }
            }
            "gif" -> GifPanel(this, t, onLocal = ::sendGif, onRemote = if (f("gif_search")) ::sendRemoteGif else null,
                onSearch = ::startGifSearch, onBack = { showPanel("keys") })
            "gifresults" -> GifResultsPanel(this, t, gifQuery, onRemote = ::sendRemoteGif,
                onBack = { showPanel("gif") }, onSearchAgain = ::startGifSearch)
            "lang" -> LangPanel(this, t, lang, onPick = ::switchTo, onRemove = ::removeLang,
                onLayout = { l, id -> Langs.setLayout(l, id, prefs); switchTo(l, stay = true) },
                onSettings = { openSettings("languages") }, onBack = { showPanel("keys") })
            "snippets" -> SnippetPanel(this, t, onInsert = { ownEdit(); commitComposing(); currentInputConnection?.commitText(it, 1) }, onBack = { showPanel("keys") })
            "clip" -> {
                if (f("clip_autoclear")) user.clearClips(3_600_000)
                ClipPanel(this, t, user, f("clipboard"), onPaste = {
                    ownEdit(); commitComposing(); currentInputConnection?.commitText(it, 1); stats.add("pastes")
                }, onBack = { showPanel("keys") })
            }
            "edit" -> EditPanel(this, t, onAction = ::editAction, onBack = { showPanel("keys") })
            else -> view
        }
        c.addView(p, FrameLayout.LayoutParams(-1, if (p === view) -2 else h))
        panelArg = null
        refreshBar()
    }

    private fun tools(): List<Pair<String, String>> = Toolbar.visible(prefs).map { it.icon to it.id }

    private fun toggleToolbar() {
        if (emojiSearch) {
            emojiSearch = false
            if (gifSearch) { gifSearch = false; showPanel("gif"); return }
            showPanel("emoji"); return
        }
        if (panel != "keys") { showPanel("keys"); return }
        toolbarOpen = !toolbarOpen
        refreshBar()
    }

    private fun tool(action: String) {
        when (action) {
            "emoji" -> showPanel(if (panel == "emoji") "keys" else "emoji")
            "gif" -> showPanel(if (panel == "gif" || panel == "gifresults") "keys" else "gif")
            "clipboard" -> showPanel(if (panel == "clip") "keys" else "clip")
            "edit" -> showPanel(if (panel == "edit") "keys" else "edit")
            "snippets" -> showPanel(if (panel == "snippets") "keys" else "snippets")
            "voice" -> startVoice()
            "theme" -> cycleTheme()
            "resize" -> toggleResize()
            "undo" -> currentInputConnection?.performContextMenuAction(android.R.id.undo)
            "redo" -> currentInputConnection?.performContextMenuAction(android.R.id.redo)
            "lang" -> showPanel(if (panel == "lang") "keys" else "lang")
            "hand" -> setHand(if (handMode == "left" || handMode == "right") "full" else prefs.getString("last_hand", "right") ?: "right")
            "float" -> setHand(if (handMode == "float") "full" else "float")
            "incognito" -> {
                val on = !f("incognito")
                prefs.edit().putBoolean("f_incognito", on).apply()
                info?.let { learnOn = suggestOn && f("learn_words") && !on }
                Toast.makeText(this, if (on) "Incognito on: nothing is learned" else "Incognito off", Toast.LENGTH_SHORT).show()
            }
            "settings" -> onSpecial("settings")
        }
        if (action == "hand" && handMode != "full") prefs.edit().putString("last_hand", handMode).apply()
        refreshBar()
    }

    private fun refreshBar() {
        val b = bar ?: return
        b.setToggleIcon(if (toolbarOpen || panel != "keys" || emojiSearch) "back" else "grid")
        when {
            selStart != selEnd && f("sel_chips") && panel == "keys" && !emojiSearch -> b.chips(selectionChips(), emptyList())
            emojiSearch && gifSearch -> b.search(if (emojiQuery.isEmpty()) "Search GIFs, then press enter" else emojiQuery.toString(), emptyList()) {}
            emojiSearch -> b.search(emojiQuery.toString(), EmojiPanel.search(this, emojiQuery.toString()), ::insertEmoji)
            inlineViews.isNotEmpty() && panel == "keys" && composing.isEmpty() && reading.isEmpty() -> b.inline(inlineViews)
            panel != "keys" || (toolbarOpen && f("quick_toolbar")) -> b.toolbar(tools(), activeTools())
            isCjk && (reading.isNotEmpty() || romaji.isNotEmpty()) -> {
                updateCjkCandidates()
                b.candidates(cjkCands.map { it.text })
                zenithKanji()
            }
            else -> updateSuggestions()
        }
    }

    private fun activeTools() = buildSet {
        if (f("incognito")) add("incognito")
        if (handMode == "left" || handMode == "right") add("hand")
        if (handMode == "float") add("float")
        when (panel) { "emoji" -> add("emoji"); "gif", "gifresults" -> add("gif"); "clip" -> add("clipboard"); "edit" -> add("edit"); "lang" -> add("lang"); "snippets" -> add("snippets") }
    }

    private fun insertEmoji(e: String) {
        ownEdit()
        commitComposing()
        currentInputConnection?.commitText(e, 1)
        EmojiPanel.addRecent(this, e)
        stats.add("emoji")
        if (emojiSearch) refreshBar()
    }

    private fun startGifSearch() {
        gifSearch = true
        emojiSearch = true
        emojiQuery.clear()
        showPanel("keys")
    }

    private fun startEmojiSearch() {
        gifSearch = false
        emojiSearch = true
        emojiQuery.clear()
        showPanel("keys")
    }

    private fun editAction(a: String) {
        val ic = currentInputConnection ?: return
        ownEdit()
        commitComposing()
        fun key(code: Int, shiftOn: Boolean) {
            val meta = if (shiftOn) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
            ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        }
        val sel = a.startsWith("sel_")
        when (a.removePrefix("sel_")) {
            "up" -> key(KeyEvent.KEYCODE_DPAD_UP, sel)
            "down" -> key(KeyEvent.KEYCODE_DPAD_DOWN, sel)
            "left" -> key(KeyEvent.KEYCODE_DPAD_LEFT, sel)
            "right" -> key(KeyEvent.KEYCODE_DPAD_RIGHT, sel)
            "home" -> key(KeyEvent.KEYCODE_MOVE_HOME, sel)
            "end" -> key(KeyEvent.KEYCODE_MOVE_END, sel)
            "select_all" -> ic.performContextMenuAction(android.R.id.selectAll)
            "copy" -> ic.performContextMenuAction(android.R.id.copy)
            "cut" -> ic.performContextMenuAction(android.R.id.cut)
            "paste" -> { ic.performContextMenuAction(android.R.id.paste); stats.add("pastes") }
            "undo" -> ic.performContextMenuAction(android.R.id.undo)
            "redo" -> ic.performContextMenuAction(android.R.id.redo)
            "backspace" -> onSpecial("backspace")
            "clear" -> {
                val all = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text?.toString()
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText("", 1)
                lastDeleted = all?.takeIf { it.isNotBlank() }
                showPanel("keys")
            }
        }
    }

    /** Hands off to the phone's voice keyboard (e.g. Google voice typing), which switches back when done. */
    private fun startVoice() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        for (ime in imm.enabledInputMethodList) {
            if (ime.packageName == packageName) continue
            val sub = imm.getEnabledInputMethodSubtypeList(ime, true).firstOrNull { it.mode == "voice" }
                ?: (0 until ime.subtypeCount).map { ime.getSubtypeAt(it) }.firstOrNull { it.mode == "voice" }
            if (sub != null) {
                commitComposing()
                stats.add("voice")
                switchInputMethod(ime.id, sub)
                return
            }
        }
        Toast.makeText(this, "No voice keyboard found. Turn on Google voice typing in system keyboard settings.", Toast.LENGTH_LONG).show()
    }

    // ---------- keys ----------

    private fun isWordText(t: String) = t.isNotEmpty() && t.all { it.isLetterOrDigit() || it == '\'' }

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        ownEdit()
        stats.keyPress()
        lastCorrection = null
        if (emojiSearch) {
            emojiQuery.append(text.lowercase())
            refreshBar()
            return
        }
        if (toolbarOpen) { toolbarOpen = false }
        lastDeleted = null
        if (!suggestOn) {
            ic.commitText(text, 1)
            afterChar()
            if (!isWordText(text)) updateShift()
            if (emailField2 || passwordField) refreshBar()
            return
        }
        if (isCjk) { cjkText(text); return }
        if (isWordText(text)) {
            lastGlide = null
            composing.append(text)
            ic.setComposingText(composing, 1)
            afterChar()
            refreshBar()
            return
        }
        commitWord(smartQuote(text), correct = true)
    }

    /** Curly quotes: opening after a space or bracket, closing otherwise. */
    private fun smartQuote(t: String): String {
        if (!f("smart_punct") || (t != "\"" && t != "'") || composing.isNotEmpty()) return t
        val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
        val opening = before.isEmpty() || before[0].isWhitespace() || before[0] in "([{“‘"
        return if (t == "\"") (if (opening) "“" else "”") else (if (opening) "‘" else "’")
    }

    private fun afterChar() {
        if (shift == 1) {
            shift = 0
            userShift = false
            kv?.shiftState = 0
        }
    }

    private fun commitWord(sep: String, correct: Boolean) {
        val ic = currentInputConnection ?: return
        val typed = composing.toString()
        var sepOut = sep
        if (typed.isNotEmpty()) {
            var out = typed
            val sc = if (f("shortcuts")) user.shortcut(typed.lowercase())?.let { Helpers.expand(it) } else null
            if (sc != null && sc.contains("{cursor}")) {
                // Smart shortcut: put the cursor where {cursor} was.
                val (before, after) = sc.split("{cursor}", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                ic.commitText(before, 1)
                ic.commitText(after, 0)
                composing.clear()
                autoSpace = false
                refreshBar()
                return
            }
            if (sc != null) out = sc
            else if (correct && f("autocorrect") && !appNoAutocorrect) suggest(typed)?.auto?.let { out = it }
            if (f("smart_punct") && sc == null) out = out.replace('\'', '’')
            ic.commitText(out, 1)
            composing.clear()
            learn(out.replace('’', '\''))
            stats.add("words")
            if (out != typed && sc == null) { lastCorrection = Triple(typed, out, sep); stats.add("corrections") }
            prevWord = out.substringAfterLast(' ').takeIf { it.isNotEmpty() }
            autoSpace = false
        } else if (sep == " " && f("double_space") && SystemClock.uptimeMillis() - lastSpaceAt < 700) {
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceAt = 0
                prevWord = null
                updateShift()
                refreshBar()
                return
            }
        } else if (f("smart_punct") && autoSpace && sep.length == 1 && sep[0] in ".,!?;:") {
            // "word " + "." → "word. "
            val before = ic.getTextBeforeCursor(1, 0)?.toString()
            if (before == " ") { ic.deleteSurroundingText(1, 0); sepOut = "$sep " }
        }
        if (f("auto_space") && sepOut == sep && sep.length == 1 && sep[0] in ".,!?;:" && !(typed.isNotEmpty() && typed.all { it.isDigit() })) {
            sepOut = "$sep "
        }
        autoSpace = sepOut.length == 2 && sepOut.endsWith(" ") && f("auto_space")
        lastGlide = null
        if (sepOut.isNotEmpty()) ic.commitText(sepOut, 1)
        if (sepOut.endsWith(" ")) lastSpaceAt = SystemClock.uptimeMillis()
        if (sep.any { it in ".!?\n" }) prevWord = null
        if (typed.isNotEmpty() && sepOut.endsWith(" ")) zenithLookBack()
        updateShift()
        refreshBar()
    }

    /**
     * Once two words follow a correctly spelled word, ask Zenith whether a near spelling fits much
     * better ("want to by a car" → "buy") and fix it in place. Each word is checked once.
     */
    private fun zenithLookBack() {
        if (!zenOn(lang) || !f("zenith_context_fix") || !f("autocorrect") || appNoAutocorrect || Zenith.model == null) return
        val d = dicts[lang] ?: return
        val text = textBefore("", 200)
        val m = LOOKBACK.find(text) ?: return
        val (word, right1, right2) = m.destructured
        val lower = word.lowercase()
        // Skip names and acronyms (capitals past the first letter) and contractions (the fix list handles those).
        if (word.length < 2 || '\'' in word || !d.contains(lower) || word.drop(1).any { it.isUpperCase() }) return
        val cands = Suggest.neighbours(lower, d, if (f("proximity")) near else noNear).map { Suggest.matchCase(word, it) }
        if (cands.isEmpty()) return
        val at = m.groups[1]!!.range.first
        val left = text.substring(0, at)
        val right = "$right1 $right2"
        val tail = text.substring(at)
        val l = lang
        Zenith.sideRequest({ zm, alive -> Zenith.fixInContext(zm, l, left, word, right, cands, LOOKBACK_MARGIN, alive) }) { fix ->
            val ic = currentInputConnection ?: return@sideRequest
            if (composing.isNotEmpty() || lang != l || textBefore("", 200) != text) return@sideRequest
            ownEdit()
            ic.beginBatchEdit()
            ic.deleteSurroundingText(tail.length, 0)
            ic.commitText(fix + tail.substring(word.length), 1)
            ic.endBatchEdit()
            stats.add("corrections"); stats.add("context_fixes")
            Toast.makeText(this, "“$word” → “$fix”", Toast.LENGTH_SHORT).show()
        }
    }

    private fun learn(word: String, by: Int = 1) {
        if (!learnOn) return
        user.learn(lang, word, by)
        val p = prevWord
        if (p != null && f("next_word")) user.learnPair(lang, p.lowercase(), word, app)
    }

    private fun commitComposing() {
        val ic = currentInputConnection
        if (composing.isNotEmpty() || reading.isNotEmpty() || romaji.isNotEmpty()) {
            if (romaji.isNotEmpty()) { reading.append(Kana.flush(romaji)); romaji = ""; ic?.setComposingText(reading, 1) }
            ic?.finishComposingText()
            composing.clear(); reading.clear(); cjkCands = emptyList()
        }
    }

    override fun onSpecial(type: String) {
        val ic = currentInputConnection ?: return
        ownEdit()
        if (type != "settings") stats.keyPress()
        if (emojiSearch) {
            when (type) {
                "backspace" -> if (emojiQuery.isNotEmpty()) emojiQuery.setLength(emojiQuery.length - 1)
                "space" -> emojiQuery.append(' ')
                "enter" -> {
                    emojiSearch = false
                    if (gifSearch) {
                        gifSearch = false
                        gifQuery = emojiQuery.toString().trim()
                        if (gifQuery.isNotEmpty()) showPanel("gifresults") else showPanel("gif")
                    } else showPanel("emoji")
                    return
                }
                else -> {}
            }
            if (type in setOf("backspace", "space")) { refreshBar(); return }
        }
        if (type == "morse_dot" || type == "morse_dash") {
            morse.append(if (type == "morse_dot") '.' else '-')
            ic.setComposingText(morse.toString().replace('.', '·').replace('-', '−'), 1)
            main.removeCallbacks(morseCommit)
            main.postDelayed(morseCommit, prefs.getInt("morse_delay", 650).toLong())
            return
        }
        if (morse.isNotEmpty() && type == "backspace") {
            morse.setLength(morse.length - 1)
            ic.setComposingText(morse.toString().replace('.', '·').replace('-', '−'), 1)
            if (morse.isEmpty()) { ic.finishComposingText(); main.removeCallbacks(morseCommit) }
            return
        }
        if (morse.isNotEmpty() && type == "space") { main.removeCallbacks(morseCommit); commitMorse(); return }
        when (type) {
            "backspace" -> {
                if (isCjk && (reading.isNotEmpty() || romaji.isNotEmpty())) {
                    if (romaji.isNotEmpty()) romaji = romaji.dropLast(1) else reading.setLength(reading.length - 1)
                    showCjkComposing()
                    return
                }
                val lg = lastGlide
                if (lg != null) {
                    lastGlide = null
                    val before = ic.getTextBeforeCursor(lg.length, 0)?.toString()
                    if (before == lg) { ic.deleteSurroundingText(lg.length, 0); refreshBar(); return }
                }
                val lc = lastCorrection
                lastCorrection = null
                if (lc != null && f("undo_autocorrect")) {
                    val (typed, out, sep) = lc
                    val before = ic.getTextBeforeCursor(out.length + sep.length, 0)?.toString()
                    if (before == out + sep) {
                        ic.deleteSurroundingText(before.length, 0)
                        ic.commitText(typed, 1)
                        stats.add("undos")
                        // You insisted on this word, so it's learned as known right away.
                        if (learnOn) user.learn(lang, typed, 2)
                        refreshBar()
                        return
                    }
                }
                if (composing.isNotEmpty()) {
                    composing.setLength(composing.length - Character.charCount(composing.codePointBefore(composing.length)))
                    ic.setComposingText(composing, 1)
                    if (composing.isEmpty()) ic.finishComposingText()
                } else {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
                autoSpace = false
                updateShift()
                refreshBar()
            }
            "space" -> {
                if (isCjk) {
                    if (reading.isNotEmpty() || romaji.isNotEmpty()) { pickCandidate(0); return }
                    ic.commitText(if (lang == "ja") "　" else " ", 1)
                    return
                }
                onText(" ")
            }
            "enter" -> {
                if (isCjk && (reading.isNotEmpty() || romaji.isNotEmpty())) {
                    reading.append(Kana.flush(romaji)); romaji = ""
                    val raw = reading.toString()
                    reading.clear(); cjkCands = emptyList()
                    ic.commitText(raw, 1)
                    refreshBar()
                    return
                }
                commitWord("", correct = false)
                val ei = info
                val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val noAction = ei == null || (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
                    action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
                when {
                    !noAction -> ic.performEditorAction(action)
                    ei?.inputType == InputType.TYPE_NULL -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                    else -> ic.commitText("\n", 1)
                }
                prevWord = null
                updateShift()
            }
            "shift" -> {
                if (f("case_cycle") && selStart != selEnd) { cycleSelectionCase(); return }
                if (f("case_cycle") && composing.length > 1 && !isCjk) {
                    val c = cycleCase(composing.toString())
                    composing.setLength(0); composing.append(c)
                    ic.setComposingText(composing, 1)
                    refreshBar()
                    return
                }
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == 0 -> 1
                    shift == 1 && now - lastShiftTap < 350 -> 2
                    else -> 0
                }
                lastShiftTap = now
                userShift = shift != 0
                kv?.shiftState = shift
            }
            "symbols" -> {
                commitComposing()
                mode = if (mode == Mode.LETTERS) Mode.SYMBOLS else Mode.LETTERS
                loadLayout()
                updateShift()
                refreshBar()
            }
            // Globe key: tap opens emoji (tap again for the keys); long-press opens settings.
            "lang" -> showPanel(if (panel == "emoji") "keys" else "emoji")
            "next_lang" -> nextLanguage()
            "kana_mod" -> {
                if (reading.isNotEmpty()) {
                    Kana.cycle(reading.last())?.let { reading.setCharAt(reading.length - 1, it) }
                    showCjkComposing()
                }
            }
            "cursor_up", "cursor_down" -> {
                commitComposing()
                sendDownUpKeyEvents(if (type == "cursor_up") KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
            }
            "cursor_left", "cursor_right" -> {
                commitComposing()
                sendDownUpKeyEvents(if (type == "cursor_left") KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            "emoji" -> if (f("emoji")) showPanel("emoji")
            "hide" -> { commitComposing(); requestHideSelf(0) }
            "lang_panel" -> { commitComposing(); showPanel(if (panel == "lang") "keys" else "lang") }
            "clipboard" -> showPanel("clip")
            "voice" -> startVoice()
            "settings" -> openSettings(null)
        }
    }

    // ---------- Morse ----------

    private fun commitMorse() {
        val ic = currentInputConnection ?: return
        if (morse.isEmpty()) return
        var ch = MORSE[morse.toString()] ?: ""
        if (shift > 0) ch = ch.uppercase()
        morse.clear()
        ic.setComposingText("", 1)
        ic.finishComposingText()
        if (ch.isNotEmpty()) { ic.commitText(ch, 1); afterChar() }
    }

    // ---------- Japanese / Mandarin ----------

    private fun cjkText(t: String) {
        val ic = currentInputConnection ?: return
        val c = t.singleOrNull()
        when {
            lang == "ja" && prefs.getString("ja_mode", "flick") == "romaji" && c != null && (c.isLetter() && c.code < 128 || c == '-') -> {
                val (out, pend) = Kana.feed(romaji, c)
                reading.append(out); romaji = pend
            }
            lang == "zh" && c != null && c.isLetter() && c.code < 128 -> reading.append(c.lowercaseChar())
            lang == "zh" && t == "'" -> return
            lang == "ja" && Kana.isHiragana(t) -> reading.append(t)
            else -> {
                // Punctuation, digits and anything else: finish the current word first.
                if (reading.isNotEmpty() || romaji.isNotEmpty()) pickCandidate(0)
                val mapped = when (t) {
                    "," -> if (lang == "ja") "、" else "，"
                    "." -> "。"
                    "?" -> "？"
                    "!" -> "！"
                    else -> t
                }
                ic.commitText(mapped, 1)
                refreshBar()
                return
            }
        }
        afterChar()
        showCjkComposing()
    }

    private fun showCjkComposing() {
        val ic = currentInputConnection ?: return
        val shown = reading.toString() + romaji
        ic.setComposingText(shown, 1)
        if (shown.isEmpty()) ic.finishComposingText()
        refreshBar()
    }

    private fun updateCjkCandidates() {
        val r = reading.toString() + (if (romaji.isEmpty()) "" else Kana.flush(romaji))
        if (r.isEmpty()) { cjkCands = emptyList(); return }
        val conv = converters[lang]
        val raw = if (lang == "ja") listOf(r, Kana.toKatakana(r)) else listOf(r)
        cjkCands = conv?.candidates(r, raw, user.convLearned(lang, r)) ?: raw.map { Candidate(it, r.length) }
    }

    private fun pickCandidate(i: Int) {
        val ic = currentInputConnection ?: return
        if (romaji.isNotEmpty()) { reading.append(Kana.flush(romaji)); romaji = "" }
        if (cjkCands.isEmpty() || cjkCands.first().len > reading.length) updateCjkCandidates()
        val cand = cjkCands.getOrNull(i) ?: return
        val len = cand.len.coerceAtMost(reading.length)
        val part = reading.substring(0, len)
        ic.commitText(cand.text, 1)
        if (learnOn) user.learnConv(lang, part, cand.text)
        stats.add("words")
        reading.delete(0, len)
        cjkCands = emptyList()
        if (reading.isNotEmpty()) ic.setComposingText(reading, 1)
        refreshBar()
    }

    // ---------- language, cursor, delete ----------

    private fun nextLanguage() {
        commitComposing()
        if (langs.size <= 1) {
            // One language: never jump to another keyboard app; offer to add one instead.
            showPanel("lang")
            return
        }
        lang = langs[(langs.indexOf(lang) + 1) % langs.size]
        prefs.edit().putString("lang", lang).apply()
        mode = Mode.LETTERS
        prevWord = null
        loadLayout()
        updateShift()
        refreshBar()
        flashLanguage()
    }

    /** Shows which language you're now in: the name on the space bar lights up briefly. */
    private fun flashLanguage() {
        kv?.flashSpace()
    }

    private fun openSettings(page: String?) {
        commitComposing()
        requestHideSelf(0)
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).also { if (page != null) it.putExtra("page", page) })
    }

    /** From the language panel: turn the language on if needed and switch to it ([stay] keeps the panel open). */
    private fun switchTo(l: String, stay: Boolean = false) {
        ownEdit()
        val list = Langs.enabled(prefs).toMutableList()
        if (l !in list) list.add(l)
        prefs.edit().putString("langs", list.joinToString(",")).putString("lang", l).apply()
        langs = list
        lang = l
        mode = Mode.LETTERS
        prevWord = null
        applySettings()
        loadLayout()
        showPanel(if (stay) "lang" else "keys")
        updateShift()
        if (!stay) flashLanguage()
    }

    private fun removeLang(l: String) {
        val list = Langs.enabled(prefs).toMutableList()
        if (list.size <= 1 || l !in list) {
            Toast.makeText(this, "Keep at least one language.", Toast.LENGTH_SHORT).show()
            return
        }
        list.remove(l)
        if (lang == l) lang = list[0]
        prefs.edit().putString("langs", list.joinToString(",")).putString("lang", lang).apply()
        langs = list
        loadLayout()
        showPanel("lang")
    }

    override fun onLongSpace() {
        if (!f("lang_space_long")) return
        if (langs.size > 1) nextLanguage()
        else (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onCursorMove(steps: Int) {
        commitComposing()
        val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(steps)) { sendDownUpKeyEvents(code) }
    }

    override fun onDeleteWords(count: Int) {
        commitComposing()
        val ic = currentInputConnection ?: return
        val removed = StringBuilder()
        repeat(count) {
            val before = ic.getTextBeforeCursor(100, 0)?.toString() ?: return@repeat
            if (before.isEmpty()) return@repeat
            var i = before.length
            while (i > 0 && before[i - 1].isWhitespace()) i--
            while (i > 0 && !before[i - 1].isWhitespace()) i--
            removed.insert(0, before.substring(i))
            ic.deleteSurroundingText(before.length - i, 0)
        }
        lastDeleted = removed.toString().takeIf { it.isNotBlank() }
        prevWord = null
        updateShift()
        refreshBar()
    }

    override fun onGlide(path: List<PointF>) {
        if (lang in Langs.cjk || mode != Mode.LETTERS || !glideOk) return
        val centersNow = kv?.keyCenters() ?: return
        val unit = kv?.unitWidth ?: return
        val ds = activeDicts()
        val l = lang
        val mine = user.allWords(lang).map { UserModel.Word(it.word, it.count) }
        loader.execute {
            val words = try { Glide.decode(path, centersNow, unit, ds, mine) } catch (_: Throwable) { emptyList() }
            main.post { if (words.isNotEmpty() && lang == l) commitGlide(words) }
        }
    }

    private fun commitGlide(words: List<String>) {
        val ic = currentInputConnection ?: return
        commitWord("", correct = true)
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{“‘\"'") ic.commitText(" ", 1)
        var w = words[0]
        if (shift > 0) w = w.replaceFirstChar { it.titlecase() }
        ic.commitText("$w ", 1)
        lastGlide = "$w "
        glideAlts = words.drop(1).map { if (shift > 0) it.replaceFirstChar { c -> c.titlecase() } else it }
        autoSpace = true
        learn(w)
        prevWord = w
        stats.add("glides"); stats.add("words")
        afterChar()
        bar?.set(listOf(w) + glideAlts, false)
    }

    private fun updateShift() {
        val view = kv ?: return
        if (shift == 2) return
        val ei = info
        val caps = mode == Mode.LETTERS && f("auto_cap") && composing.isEmpty() && ei != null &&
            ei.inputType != InputType.TYPE_NULL && lang !in Langs.cjk &&
            (currentInputConnection?.getCursorCapsMode(ei.inputType) ?: 0) != 0
        shift = if (caps) 1 else if (userShift) shift else 0
        view.shiftState = shift
    }

    // ---------- suggestions ----------

    private fun activeDicts(): List<Dictionary> {
        val others = if (f("multilingual") && lang in Langs.latin) {
            langs.filter { it != lang && it in Langs.latin }.mapNotNull { dicts[it] }
        } else emptyList()
        return listOfNotNull(dicts[lang]) + others
    }

    private fun suggest(typed: String): Suggestions? {
        val ds = activeDicts()
        if (ds.isEmpty()) return null
        val z = zenScores?.takeIf { zenOn(lang) && it.first == zenKey(typed) }?.second
        return Suggest.run(
            typed, lang, ds, user,
            if (f("proximity")) near else noNear,
            f("autocorrect") && !appNoAutocorrect, f("ac_aggressive"),
            lm = z,
        )
    }

    private val KANJI_ORDER_WEIGHT = 0.7f
    /** "… word right1 right2 " at the end of the text (letters only, so nothing with punctuation in between). */
    private val LOOKBACK = Regex("""(?:^|\s)(\p{L}[\p{L}']*) (\p{L}[\p{L}']*) (\p{L}[\p{L}']*) $""")
    /** With words on both sides the model is far surer; e^3 ≈ 20× more likely is enough. */
    private val LOOKBACK_MARGIN = 3f

    private fun zenOn(l: String) = f("zenith") && f("zenith_$l") && l in Zenith.langs

    /** Text before the word being typed (the composing word is already in the field). */
    private fun textBefore(typed: String, n: Int = 400): String {
        val t = currentInputConnection?.getTextBeforeCursor(n + typed.length, 0)?.toString() ?: return ""
        return if (typed.isNotEmpty() && t.endsWith(typed)) t.dropLast(typed.length) else t
    }

    private var zenBefore = ""
    private fun zenKey(typed: String) = "$lang|$zenBefore|$typed"

    /** Re-rank the word being typed with Zenith; the bar updates when the answer arrives (about 30–50 ms). */
    private fun zenithRerank(typed: String, s: Suggestions?) {
        if (!zenOn(lang) || Zenith.model == null || s == null || s.pool.isEmpty()) return
        zenBefore = textBefore(typed)
        val key = zenKey(typed)
        if (zenScores?.first == key) return
        val l = lang
        val before = zenBefore
        val pool = s.pool
        Zenith.request({ m, alive -> Zenith.scoreWords(m, l, before, pool, alive) }) { r ->
            zenScores = key to r.mapKeys { it.key.lowercase() }
            if (composing.toString() == typed && lang == l) showWordSuggestions(typed, rerank = false)
        }
    }

    /**
     * Re-ranks the kanji choices for the current reading by how well each fits the text before it.
     * Your own past choices stay first; Zenith is mixed with the converter's order, not a replacement.
     */
    private fun zenithKanji() {
        if (lang != "ja" || !zenOn("ja") || Zenith.model == null || cjkCands.size < 2) return
        val shown = reading.toString() + romaji
        val r = reading.toString() + (if (romaji.isEmpty()) "" else Kana.flush(romaji))
        val learned = user.convLearned(lang, r).toSet()
        val cands = cjkCands
        // Only whole-reading choices compete; partial conversions keep their place.
        val slots = cands.indices.filter { cands[it].len == r.length && cands[it].text !in learned }.take(12)
        if (slots.size < 2) return
        val before = textBefore(shown, 40)
        val texts = slots.map { cands[it].text }
        Zenith.request({ m, alive -> Zenith.rankTexts(m, "ja", before, texts, 32, alive) }) { sc ->
            if (cjkCands !== cands || reading.toString() + romaji != shown) return@request
            val mx = sc.values.maxOrNull() ?: return@request
            val norm = mx + kotlin.math.ln(sc.values.sumOf { kotlin.math.exp((it - mx).toDouble()) }).toFloat()
            // Converter order as a prior: each step down costs a little.
            val order = texts.withIndex().sortedByDescending { (i, t) -> (sc[t] ?: -99f) - norm - KANJI_ORDER_WEIGHT * kotlin.math.ln(1f + i) }.map { it.value }
            val out = cands.toMutableList()
            slots.forEachIndexed { k, slot -> out[slot] = cands.first { it.text == order[k] } }
            cjkCands = out
            bar?.candidates(out.map { it.text })
        }
    }

    /** After committed Japanese text: Zenith's likely next phrases. */
    private fun zenithPhrases() {
        if (!zenOn("ja") || !f("zenith_next") || Zenith.model == null || reading.isNotEmpty() || romaji.isNotEmpty()) return
        val before = textBefore("", 32)
        if (before.isBlank() || before.last().isWhitespace()) return
        Zenith.request({ m, alive -> Zenith.continuations(m, before, 3, alive) }) { list ->
            if (lang != "ja" || reading.isNotEmpty() || romaji.isNotEmpty() || panel != "keys" || list.isEmpty()) return@request
            if (textBefore("", 32) != before) return@request
            bar?.set(list, false)
        }
    }

    /** Next words after a space, from Zenith, with your own habits first. */
    private fun zenithNext(learned: List<String>) {
        if (!zenOn(lang) || !f("zenith_next") || Zenith.model == null) return
        val before = textBefore("")
        if (before.isBlank() || !(before.endsWith(" ") || before.endsWith("\n"))) return
        val l = lang
        val ds = activeDicts()
        Zenith.request({ m, alive ->
            Zenith.nextWords(m, l, before, 3, { w -> val lw = w.lowercase(); ds.any { it.contains(lw) } || user.knows(l, lw) || w == "I" }, alive)
        }) { words ->
            if (composing.isNotEmpty() || lang != l || panel != "keys" || selStart != selEnd) return@request
            if (textBefore("") != before) return@request
            val list = (learned.take(1) + words + learned.drop(1)).distinctBy { it.lowercase() }.take(3)
            if (list.isEmpty()) return@request
            val chips = helperChips()
            if (chips.isNotEmpty()) bar?.chips(chips, list) else bar?.set(list, false)
        }
    }

    private fun updateSuggestions() {
        val b = bar ?: return
        if (!suggestOn || lang in Langs.cjk) {
            val chips = fieldChips()
            if (suggestOn && chips.isEmpty() && lang == "ja") zenithPhrases()
            when {
                chips.isNotEmpty() -> b.chips(chips, emptyList())
                f("quick_toolbar") && prefs.getBoolean("toolbar_auto", true) -> b.toolbar(tools(), activeTools())
                else -> b.set(emptyList(), false)
            }
            return
        }
        val typed = composing.toString()
        if (typed.isEmpty()) {
            val p = prevWord
            val list = if (p != null && f("next_word") && learnOn) user.next(lang, p.lowercase(), 3, app) else emptyList()
            if (f("next_word")) zenithNext(list)
            val chips = helperChips()
            when {
                chips.isNotEmpty() -> b.chips(chips, list)
                // Nothing to suggest: show the toolbar instead of an empty strip (unless you chose tap-only).
                list.isEmpty() && f("quick_toolbar") && prefs.getBoolean("toolbar_auto", true) -> b.toolbar(tools(), activeTools())
                else -> b.set(list, false)
            }
            return
        }
        showWordSuggestions(typed, rerank = true)
    }

    private fun showWordSuggestions(typed: String, rerank: Boolean) {
        val b = bar ?: return
        val s = suggest(typed)
        var list = s?.list ?: listOf(typed)
        emojiSuggestions = if (f("emoji_suggest")) EmojiPanel.forWord(this, lang, typed) else emptyList()
        if (emojiSuggestions.isNotEmpty()) list = list.take(2) + emojiSuggestions.first()
        dateFor(typed)?.let { list = list.take(2) + it }
        b.set(list, s?.auto != null)
        if (rerank) zenithRerank(typed, s)
    }

    /** Password and email fields, where normal suggestions are off. */
    private fun fieldChips(): List<Pair<String, () -> Unit>> {
        val ic = currentInputConnection ?: return emptyList()
        val out = ArrayList<Pair<String, () -> Unit>>()
        if (passwordField && f("pass_gen")) out.add("🔑 Strong password" to {
            ic.commitText(generatePassword(), 1)
            Toast.makeText(this, "Password inserted. Save it in your password manager.", Toast.LENGTH_SHORT).show()
        })
        if (emailField2 && f("email_chips")) {
            val before = ic.getTextBeforeCursor(80, 0)?.toString() ?: ""
            val typed = before.substringAfterLast(' ').substringAfterLast(',')
            emails().filter { it.startsWith(typed, ignoreCase = true) && !it.equals(typed, true) }.take(3).forEach { e ->
                out.add(e to { ic.commitText(e.substring(typed.length), 1); refreshBar() })
            }
        }
        out.addAll(helperChips())
        return out
    }

    /** Chips for the moment right after typing: calculator answer, unit conversions, paste. */
    private fun helperChips(): List<Pair<String, () -> Unit>> {
        val ic = currentInputConnection ?: return emptyList()
        val out = ArrayList<Pair<String, () -> Unit>>()
        lastDeleted?.let { d ->
            if (f("undo_delete")) out.add("↺ Restore “${d.trim().take(18)}${if (d.trim().length > 18) "…" else ""}”" to {
                ic.commitText(d, 1); lastDeleted = null; refreshBar()
            })
        }
        val before = ic.getTextBeforeCursor(60, 0)?.toString() ?: ""
        if (f("calculator")) Helpers.calculate(before)?.let { r -> out.add("= $r" to { ic.commitText(r, 1); refreshBar() }) }
        if (f("units")) Helpers.convert(before).forEach { c -> out.add("≈ $c" to { ic.commitText("($c) ", 1); refreshBar() }) }
        val clip = lastClip
        if (f("paste_chip") && clip != null && SystemClock.uptimeMillis() - lastClipAt < 90_000) {
            Helpers.code(clip)?.let { code -> out.add("Code $code" to { ic.commitText(code, 1); lastClip = null; refreshBar() }) }
            val preview = clip.replace('\n', ' ').let { if (it.length > 24) it.take(24) + "…" else it }
            out.add("Paste “$preview”" to { ic.commitText(clip, 1); lastClip = null; stats.add("pastes"); refreshBar() })
        }
        return out
    }

    private fun sendGif(f: java.io.File) {
        val ic = currentInputConnection ?: return
        ownEdit()
        val type = GifProvider.typeOf(f.name)
        val accepted = info?.contentMimeTypes?.any { android.content.ClipDescription.compareMimeTypes(type, it) } == true
        if (!accepted) {
            Toast.makeText(this, "This app doesn't accept GIFs or stickers from keyboards.", Toast.LENGTH_SHORT).show()
            return
        }
        commitComposing()
        val content = android.view.inputmethod.InputContentInfo(GifProvider.uri(f), android.content.ClipDescription(f.name, arrayOf(type)), null)
        ic.commitContent(content, android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
    }

    private fun selectionChips(): List<Pair<String, () -> Unit>> {
        val ic = currentInputConnection ?: return emptyList()
        return listOf(
            "Copy" to { ic.performContextMenuAction(android.R.id.copy); Unit },
            "Cut" to { ic.performContextMenuAction(android.R.id.cut); Unit },
            "Paste" to { ic.performContextMenuAction(android.R.id.paste); Unit },
            "Select all" to { ic.performContextMenuAction(android.R.id.selectAll); Unit },
            "Aa  Case" to { cycleSelectionCase() },
        )
    }

    private fun cycleCase(t: String): String = when {
        t == t.lowercase() -> t.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
        t == t.uppercase() -> t.lowercase()
        else -> t.uppercase()
    }

    private fun cycleSelectionCase() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)?.toString() ?: return
        if (sel.isEmpty()) return
        val start = minOf(selStart, selEnd)
        ic.commitText(cycleCase(sel), 1)
        ic.setSelection(start, start + sel.length)
    }

    private fun dateFor(word: String): String? {
        if (!f("date_chips")) return null
        val offset = when (word.lowercase()) {
            "today", "hoy", "сегодня" -> 0
            "tomorrow", "mañana", "manana", "завтра" -> 1
            "yesterday", "ayer", "вчера" -> -1
            else -> return null
        }
        val c = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, offset) }
        return java.text.SimpleDateFormat("EEE, d MMM yyyy", java.util.Locale.getDefault()).format(c.time)
    }

    private fun generatePassword(): String {
        val r = java.security.SecureRandom()
        val sets = listOf("ABCDEFGHJKLMNPQRSTUVWXYZ", "abcdefghijkmnopqrstuvwxyz", "23456789", "!@#\$%^&*-_=+?")
        val all = sets.joinToString("")
        val chars = sets.map { it[r.nextInt(it.length)] } + List(12) { all[r.nextInt(all.length)] }
        return chars.shuffled(r).joinToString("")
    }

    private fun emails(): List<String> = (prefs.getString("emails", "") ?: "").split(',').filter { it.isNotEmpty() }

    private fun rememberEmail() {
        val t = currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text?.toString()?.trim() ?: return
        if (!Regex("[^@\\s]+@[^@\\s]+\\.[a-zA-Z]{2,}").matches(t)) return
        val list = (listOf(t) + emails().filter { !it.equals(t, true) }).take(5)
        prefs.edit().putString("emails", list.joinToString(",")).apply()
    }

    /** Toolbar theme button: cycles your favourite themes (set in Look & size). */
    private fun cycleTheme() {
        val favs = (prefs.getString("fav_themes", null) ?: listOf(prefs.getString("theme", "Graphite"), "Crimson Noir", "Abyss").joinToString(","))
            .split(',').filter { it.isNotEmpty() }.distinct()
        if (favs.isEmpty()) return
        val cur = prefs.getString("theme", "Graphite")
        val next = favs[(favs.indexOf(cur) + 1).mod(favs.size)]
        prefs.edit().putString("theme", next).apply()
        applySettings()
        showPanel(panel)
        Toast.makeText(this, next, Toast.LENGTH_SHORT).show()
    }

    /** Resize mode: a bar on top of the keyboard; drag it up or down to change the height. */
    private fun toggleResize() {
        val c = card ?: return
        val existing = resizeBar
        if (existing != null) { c.removeView(existing); resizeBar = null; return }
        val t = theme ?: return
        val barView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(KbTheme.mix(t.bg, t.acc, 0.25f))
            setPadding((14 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }
        val label = android.widget.TextView(this).apply { setTextColor(t.fg); textSize = 14f }
        fun show() { label.text = "⇕  Drag to resize · ${prefs.getInt("scale", KbConfig.DEF_SCALE)}%" }
        show()
        barView.addView(label, LinearLayout.LayoutParams(0, -1, 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        barView.addView(android.widget.TextView(this).apply {
            text = "Done"; setTextColor(t.acc); textSize = 15f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            setOnClickListener { toggleResize() }
        }, LinearLayout.LayoutParams(-2, -1))
        var startY = 0f; var startScale = 100
        label.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startScale = prefs.getInt("scale", KbConfig.DEF_SCALE) }
                MotionEvent.ACTION_MOVE -> {
                    val sc = (startScale - ((e.rawY - startY) / (3 * dp)).toInt()).coerceIn(70, 140)
                    prefs.edit().putInt("scale", sc).apply()
                    kv?.let { KbConfig.apply(this, it, prefs, pkg) }
                    show()
                }
            }
            true
        }
        c.addView(barView, 0, LinearLayout.LayoutParams(-1, (40 * dp).toInt()))
        resizeBar = barView
    }

    private fun sendRemoteGif(g: GifOnline.Gif) {
        val accepted = info?.contentMimeTypes?.any { android.content.ClipDescription.compareMimeTypes("image/gif", it) } == true
        if (!accepted) {
            Toast.makeText(this, "This app doesn't accept GIFs from keyboards.", Toast.LENGTH_SHORT).show()
            return
        }
        GifOnline.download(this, g) { f -> if (f != null) sendGif(f) else Toast.makeText(this, "Couldn't download that GIF.", Toast.LENGTH_SHORT).show() }
    }

    // ---------- password manager / autofill suggestions ----------

    override fun onCreateInlineSuggestionsRequest(uiExtras: android.os.Bundle): android.view.inputmethod.InlineSuggestionsRequest? {
        if (!f("autofill_chips")) return null
        val t = theme ?: return null
        val chip = KbTheme.mix(t.bg, t.acc, 0.22f)
        val style = androidx.autofill.inline.v1.InlineSuggestionUi.newStyleBuilder()
            .setChipStyle(androidx.autofill.inline.common.ViewStyle.Builder()
                .setBackground(android.graphics.drawable.Icon.createWithResource(this, R.drawable.chip_bg).setTint(chip))
                .setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0).build())
            .setTitleStyle(androidx.autofill.inline.common.TextViewStyle.Builder().setTextColor(t.fg).setTextSize(14f).build())
            .setSubtitleStyle(androidx.autofill.inline.common.TextViewStyle.Builder().setTextColor(KbTheme.mix(t.fg, t.bg, 0.4f)).setTextSize(12f).build())
            .setStartIconStyle(androidx.autofill.inline.common.ImageViewStyle.Builder().setLayoutMargin(0, 0, (6 * dp).toInt(), 0).build())
            .build()
        val styles = androidx.autofill.inline.UiVersions.newStylesBuilder().addStyle(style).build()
        val spec = android.widget.inline.InlinePresentationSpec.Builder(
            android.util.Size((90 * dp).toInt(), (32 * dp).toInt()), android.util.Size((260 * dp).toInt(), (38 * dp).toInt()),
        ).setStyle(styles).build()
        return android.view.inputmethod.InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(6).build()
    }

    override fun onInlineSuggestionsResponse(response: android.view.inputmethod.InlineSuggestionsResponse): Boolean {
        val list = response.inlineSuggestions
        if (list.isEmpty()) { inlineViews = emptyList(); refreshBar(); return true }
        val views = arrayOfNulls<View>(list.size)
        var done = 0
        list.forEachIndexed { i, s ->
            s.inflate(this, android.util.Size(ViewGroup.LayoutParams.WRAP_CONTENT, (38 * dp).toInt()), mainExecutor) { v ->
                views[i] = v
                if (++done == list.size) { inlineViews = views.filterNotNull(); refreshBar() }
            }
        }
        return true
    }

    private fun rememberApp(p: String?) {
        if (p == null || p == packageName) return
        val list = (listOf(p) + (prefs.getString("recent_apps", "") ?: "").split(',').filter { it.isNotEmpty() && it != p }).take(12)
        prefs.edit().putString("recent_apps", list.joinToString(",")).apply()
    }

    private fun loadAim(view: KeyboardView) {
        val o = try { org.json.JSONObject(prefs.getString("aim", "{}") ?: "{}") } catch (e: Exception) { return }
        o.keys().forEach { k -> o.optJSONArray(k)?.let { view.offsets[k] = PointF(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) } }
    }

    private fun saveAim(view: KeyboardView) {
        val o = org.json.JSONObject()
        view.offsets.forEach { (k, v) -> o.put(k, org.json.JSONArray(listOf(v.x.toDouble(), v.y.toDouble()))) }
        prefs.edit().putString("aim", o.toString()).apply()
        view.aimChanges = 0
    }

    private fun pick(word: String) {
        val ic = currentInputConnection ?: return
        if (word in emojiSuggestions && composing.isNotEmpty()) {
            // Emoji suggestion: keep the typed word, add the emoji after it.
            commitWord(" ", correct = true)
            insertEmoji(word)
            ic.commitText(" ", 1)
            emojiSuggestions = emptyList()
            return
        }
        lastCorrection = null
        if (lang in Langs.cjk) {
            ic.commitText(word, 1)
            stats.add("words")
            refreshBar()
            return
        }
        val lg = lastGlide
        if (lg != null && composing.isEmpty()) {
            // Replace the glided word with the alternative you tapped.
            val before = ic.getTextBeforeCursor(lg.length, 0)?.toString()
            if (before == lg) ic.deleteSurroundingText(lg.length, 0)
        }
        ic.commitText("$word ", 1)
        composing.clear()
        learn(word, 2)
        stats.add("words")
        prevWord = word
        lastGlide = null
        autoSpace = true
        lastSpaceAt = SystemClock.uptimeMillis()
        updateShift()
        refreshBar()
    }

    private fun forget(word: String) {
        if (!f("forget_long_press")) return
        user.forget(lang, word)
        Toast.makeText(this, "“$word” won't be suggested again", Toast.LENGTH_SHORT).show()
        refreshBar()
    }
}
