package com.mobilekey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilekey.engine.Converter
import com.mobilekey.engine.Dictionary
import com.mobilekey.engine.Glide
import com.mobilekey.engine.Suggest
import com.mobilekey.engine.UserModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on the phone. Uses a throwaway language ("zz") and test entries, cleaned up afterwards. */
@RunWith(AndroidJUnit4::class)
class DeviceTest {
    companion object {
        lateinit var ctx: Context
        lateinit var en: Dictionary
        lateinit var es: Dictionary
        @BeforeClass @JvmStatic fun setup() {
            ctx = InstrumentationRegistry.getInstrumentation().targetContext
            en = Dictionary.load(ctx, "en")
            es = Dictionary.load(ctx, "es")
        }
    }

    private val user get() = UserModel.get(ctx)
    private val none: (Char, Char) -> Boolean = { _, _ -> false }
    private fun auto(word: String, d: Dictionary, lang: String = "en") =
        Suggest.run(word, lang, listOf(d), user, none, autocorrect = true, aggressive = false).auto

    // ---------- assets ----------

    @Test fun everyLayoutParses() {
        for (name in Layouts.editable) {
            val l = Layouts.parse(Layouts.assetText(ctx, name))
            assertTrue("$name has rows", l.rows.isNotEmpty())
        }
    }

    @Test fun everyThemeLoads() {
        val all = Themes.all(ctx)
        assertTrue(all.size >= 15)
        assertTrue(all.any { it.name == "Crimson Noir" && it.amoled })
    }

    @Test fun dictionariesLoad() {
        assertTrue(en.size > 40000)
        assertTrue(es.size > 40000)
        assertTrue(Dictionary.load(ctx, "ru").size > 40000)
    }

    // ---------- autocorrect ----------

    @Test fun fixesCommonTypos() {
        assertEquals("the", auto("teh", en))
        assertEquals("receive", auto("recieve", en))
        assertEquals("hello", auto("helo", en))
        assertEquals("I'm", auto("im", en))
        assertEquals("mañana", auto("manana", es, "es"))
        assertEquals("tomorrow", auto("tommorow", en))
        assertEquals("until", auto("untill", en))
        assertEquals("don't", auto("dont", en))
        assertEquals("a lot", auto("alot", en))
        assertEquals("I", auto("i", en))
    }

    /** Same as the real keyboard: neighbouring keys count as small typos (QWERTY rows). */
    @Test fun fixesTyposWithKeyPositions() {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val pos = HashMap<Char, android.graphics.PointF>()
        rows.forEachIndexed { r, row -> row.forEachIndexed { i, ch -> pos[ch] = android.graphics.PointF(i + r * 0.5f, r.toFloat()) } }
        val near: (Char, Char) -> Boolean = { a, b -> val pa = pos[a]; val pb = pos[b]; pa != null && pb != null && kotlin.math.hypot(pa.x - pb.x, pa.y - pb.y) < 1.5f }
        fun fix(w: String) = Suggest.run(w, "en", listOf(en), user, near, true, false).auto
        assertEquals("hello", fix("helo"))
        assertEquals("the", fix("teh"))
        assertEquals("until", fix("untill"))
        assertEquals("keyboard", fix("keyboatd"))
        assertEquals("tomorrow", fix("tommorow"))
        assertEquals(null, fix("hello"))
    }

    @Test fun leavesRealWordsAlone() {
        for (w in listOf("hello", "the", "keyboard", "tomorrow", "tho", "lol", "okay", "gonna", "wanna")) assertEquals(w, null, auto(w, en))
    }

    @Test fun offensiveFilterBlocksSuggestions() {
        Suggest.offensive = Suggest.loadOffensive(ctx)
        try {
            val list = Suggest.run("shi", "en", listOf(en), user, none, true, false).list
            assertTrue(list.none { it.equals("shit", true) })
        } finally { Suggest.offensive = emptySet() }
    }

    // ---------- learning ----------

    @Test fun learnsAndForgets() {
        user.learn("zz", "Zorblax", 2)
        assertTrue(user.knows("zz", "zorblax"))
        assertTrue(user.prefix("zz", "zor").any { it.first == "Zorblax" })
        user.remove("zz", "Zorblax")
        assertTrue(!user.knows("zz", "zorblax"))
    }

    @Test fun clipboardHistory() {
        val text = "mykey-test-clip-${System.nanoTime()}"
        user.addClip(text)
        val c = user.clips().first { it.text == text }
        user.pinClip(c.id, true)
        assertTrue(user.clips().first().pinned)
        user.deleteClip(c.id)
        assertTrue(user.clips().none { it.text == text })
    }

    // ---------- Japanese / Mandarin ----------

    @Test fun japaneseConversion() {
        val ja = Converter.load(ctx, "dict/ja.txt", scoreIsLog = false)
        assertTrue(ja.candidates("にほんご", listOf("にほんご"), emptyList()).take(5).any { it.text == "日本語" })
        assertTrue(ja.candidates("とうきょう", listOf("とうきょう"), emptyList()).take(5).any { it.text == "東京" })
        assertTrue(ja.convert("わたしはがくせいです").contains("学生"))
    }

    @Test fun mandarinConversion() {
        val zh = Converter.load(ctx, "dict/zh.txt", scoreIsLog = true)
        assertTrue(zh.candidates("nihao", listOf("nihao"), emptyList()).take(5).any { it.text == "你好" })
        assertTrue(zh.candidates("zhongguo", listOf("zhongguo"), emptyList()).take(5).any { it.text == "中国" })
    }

    // ---------- glide ----------

    @Test fun glideFindsWord() {
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val centers = HashMap<Char, PointF>()
        rows.forEachIndexed { r, row -> row.forEachIndexed { i, ch -> centers[ch] = PointF(50f + i * 100f + r * 50f, 50f + r * 120f) } }
        val path = ArrayList<PointF>()
        val word = "hello"
        for (i in 0 until word.length - 1) {
            val a = centers[word[i]]!!; val b = centers[word[i + 1]]!!
            for (s in 0..10) path.add(PointF(a.x + (b.x - a.x) * s / 10f, a.y + (b.y - a.y) * s / 10f))
        }
        val res = Glide.decode(path, centers, 100f, listOf(en), emptyList())
        assertTrue("got $res", res.take(3).contains("hello"))
    }

    // ---------- long-press edits ----------

    @Test fun longPressOverrides() {
        val p = Prefs.get(ctx)
        val before = p.getString("long_overrides", null)
        try {
            LongPress.set(p, "test_layout", "e", listOf("3", "é"), "")
            val l = LongPress.apply(p, "test_layout", Layouts.parse("""{"rows":[["e","r"]]}"""))
            assertEquals(listOf("3", "é"), l.rows[0][0].long)
            assertEquals("", l.rows[0][0].hint)
            LongPress.reset(p, "test_layout", null)
        } finally {
            p.edit().apply { if (before == null) remove("long_overrides") else putString("long_overrides", before) }.commit()
        }
    }

    // ---------- drawing: every layout × theme × style ----------

    private fun draw(v: View, w: Int = 1080) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
        v.layout(0, 0, v.measuredWidth, maxOf(1, v.measuredHeight))
        val b = Bitmap.createBitmap(v.measuredWidth, maxOf(1, v.measuredHeight), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        b.recycle()
    }

    @Test fun keyboardDrawsEverything() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val kv = KeyboardView(ctx)
            val styles = KbConfig.styles.map { it.first }
            val anims = KbConfig.anims.map { it.first }
            for ((ti, theme) in Themes.all(ctx).withIndex()) for (name in Layouts.editable) {
                kv.theme = theme
                kv.keyStyle = styles[ti % styles.size]
                kv.pressAnim = anims[ti % anims.size]
                kv.split = ti % 3 == 0
                kv.shiftState = ti % 3
                kv.labelFont = KbConfig.font(ctx, KbConfig.fonts[ti % KbConfig.fonts.size].first)
                kv.applyLayout(Layouts.withEmojiKey(Layouts.withNumberRow(Layouts.parse(Layouts.assetText(ctx, name)))))
                draw(kv)
            }
        }
    }

    @Test fun panelsDraw() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val t = Themes.byName(ctx, "Crimson Noir")
            val panels = listOf<View>(
                EmojiPanel(ctx, t, {}, {}, {}, {}, null),
                GifPanel(ctx, t, {}, null, {}, {}),
                ClipPanel(ctx, t, user, true, {}, {}),
                EditPanel(ctx, t, {}, {}),
                SnippetPanel(ctx, t, {}, {}),
                LangPanel(ctx, t, "en", {}, {}, {}, {}),
            )
            panels.forEach { draw(it) }
            assertTrue(EmojiPanel.data(ctx).values.sumOf { it.size } > 1500)
            assertTrue(EmojiPanel.symbols(ctx).isNotEmpty())
            assertTrue(EmojiPanel.search(ctx, "pizza").contains("🍕"))
        }
    }

    // ---------- settings: open every page ----------

    @Test fun everySettingsPageOpens() {
        val pages = listOf("theme", "size", "keys", "feel", "profiles", "theme_editor", "languages", "typing", "correction", "tools", "toolbar", "longpress",
            "learning", "shortcuts", "layouts", "stats", "backup", "privacy", "apptheme:com.whatsapp")
        ActivityScenario.launch(SettingsActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val open = SettingsActivity::class.java.getDeclaredMethod("open", String::class.java).apply { isAccessible = true }
                val back = SettingsActivity::class.java.getDeclaredMethod("back").apply { isAccessible = true }
                for (pg in pages) { open.invoke(a, pg); back.invoke(a) }
            }
        }
    }

    /** Presses every safe button, switch, chip and slider on every settings page; restores settings after. */
    @Test fun clickEverythingOnEveryPage() {
        val p = Prefs.get(ctx)
        val snapshot = HashMap(p.all)
        val skip = listOf("delete", "reset", "clear", "remove", "export", "restore", "turn on", "switch keyboard", "choose", "add gifs",
            "open", "save as", "use pasted", "copy current", "forget", "allow again", "change photo", "add a word", "save snippet", "add shortcut")
        val pages = listOf("theme", "size", "keys", "feel", "profiles", "theme_editor", "languages", "typing", "correction", "tools", "toolbar", "longpress",
            "learning", "shortcuts", "layouts", "stats", "backup", "privacy", "apptheme:com.whatsapp", "home")
        val failures = ArrayList<String>()
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { sc ->
                for (pg in pages) {
                    sc.onActivity { a ->
                        val open = SettingsActivity::class.java.getDeclaredMethod("open", String::class.java).apply { isAccessible = true }
                        if (pg != "home") open.invoke(a, pg)
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    sc.onActivity { a ->
                        val host = (a.window.decorView.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0) as android.view.ViewGroup
                        val page = host.getChildAt(host.childCount - 1)
                        val views = ArrayList<View>()
                        fun walk(v: View) {
                            if (v.isClickable && v.isEnabled && v.visibility == View.VISIBLE) views.add(v)
                            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                        }
                        walk(page)
                        fun label(v: View): String = when (v) {
                            is android.widget.TextView -> v.text.toString()
                            is android.view.ViewGroup -> (0 until v.childCount).joinToString(" ") { label(v.getChildAt(it)) }
                            else -> v.contentDescription?.toString() ?: ""
                        }
                        for (v in views.take(150)) {
                            val text = label(v).lowercase()
                            if (skip.any { text.contains(it) } || text == "←" || v.contentDescription == "Back") continue
                            if (v is android.widget.EditText) continue
                            try { v.performClick() } catch (e: Throwable) { failures.add("$pg: '${text.take(40)}' -> $e") }
                        }
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    sc.onActivity { a ->
                        // Back to a clean home before the next page.
                        val stack = SettingsActivity::class.java.getDeclaredField("stack").apply { isAccessible = true }.get(a) as ArrayList<*>
                        val back = SettingsActivity::class.java.getDeclaredMethod("back").apply { isAccessible = true }
                        while (stack.size > 1) back.invoke(a)
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                }
            }
        } finally {
            val e = p.edit().clear()
            snapshot.forEach { (k, v) ->
                when (v) { is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v); is Float -> e.putFloat(k, v); is String -> e.putString(k, v) }
            }
            e.commit()
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
