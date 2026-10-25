package com.mobilekey

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilekey.engine.Dictionary
import com.mobilekey.engine.Suggest
import com.mobilekey.engine.UserModel
import com.mobilekey.engine.Zenith
import com.mobilekey.engine.ZenithModel
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Zenith on the phone: same answers as the PC, and how fast. Results are logged under the tag "Zenith". */
@RunWith(AndroidJUnit4::class)
class ZenithDeviceTest {
    companion object {
        lateinit var model: ZenithModel
        var pssBefore = 0
        @BeforeClass @JvmStatic fun setup() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            System.gc()
            pssBefore = Debug.getPss().toInt()
            model = ZenithModel.load(ctx)
        }
    }

    private val golden by lazy {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        JSONArray(ctx.assets.open("zenith_golden.json").bufferedReader().readText())
    }

    private fun ints(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }

    /** Top-5 next tokens match the Python ONNX run, fed whole and one token at a time (the KV cache path). */
    @Test fun matchesPythonTop5() {
        for (i in 0 until golden.length()) {
            val g = golden.getJSONObject(i)
            val ids = ints(g.getJSONArray("ids"))
            assertTrue("tokenizer: ${g.getString("text")}", ids.contentEquals(model.contextIds(g.getString("lang"), g.getString("text"))))
            val want = ints(g.getJSONArray("top"))
            val whole = model.feed(ZenithModel.State(IntArray(0), FloatArray(ZenithModel.KV), FloatArray(ZenithModel.KV), FloatArray(0)), ids).first
            var step = ZenithModel.State(IntArray(0), FloatArray(ZenithModel.KV), FloatArray(ZenithModel.KV), FloatArray(0))
            for (id in ids) step = model.feed(step, intArrayOf(id)).first
            for (s in listOf(whole, step)) {
                val top = s.next.indices.sortedByDescending { s.next[it] }.take(5).toIntArray()
                val lps = g.getJSONArray("lp")
                // Top-1 must match unless the PC's top two are a near-tie.
                if (lps.getDouble(0) - lps.getDouble(1) > 0.05) assertEquals("top-1 for ${g.getString("text")}", want[0], top[0])
                // ARM and x86 int8 kernels round differently, so near-ties at rank 5 may swap.
                assertTrue("top-5 for ${g.getString("text")}: $want vs ${top.toList()}", (want.toSet() intersect top.toSet()).size >= 4)
                assertTrue("log-prob for ${g.getString("text")}: ${s.next[want[0]]}", abs(s.next[want[0]] - g.getJSONArray("lp").getDouble(0).toFloat()) < 0.15f)
            }
        }
    }

    private fun zen(): ZenithModel {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        Zenith.ensure(ctx)
        val t0 = System.currentTimeMillis()
        while (Zenith.model == null && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(50)
        return Zenith.model!!
    }

    private fun report(key: String, msg: String) {
        Log.i("Zenith", "$key: $msg")
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString(key, msg) })
    }

    @Test fun reranksWhileTyping() {
        val m = zen()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val en = Dictionary.load(ctx, "en")
        val user = UserModel.get(ctx)
        fun top(before: String, typed: String): String {
            val first = Suggest.run(typed, "en", listOf(en), user, { _, _ -> false }, true, false)
            val t = System.nanoTime()
            val lm = Zenith.scoreWords(m, "en", before, first.pool) { true }!!.mapKeys { it.key.lowercase() }
            val after = Suggest.run(typed, "en", listOf(en), user, { _, _ -> false }, true, false, lm = lm)
            report("rerank $before|$typed", "${first.list} → ${after.list} auto=${after.auto} %.0f ms".format((System.nanoTime() - t) / 1e6))
            return after.list.first()
        }
        assertEquals("hello", top("Hi,", "helo"))
        top("Can you", "hel")
        top("I want to", "kn")
        top("Happy", "bir")
        top("Thank you so", "mu")
        top("Te quiero", "mu")
    }

    @Test fun contextFixesRealWords() {
        val m = zen()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val en = Dictionary.load(ctx, "en")
        fun fix(left: String, word: String, right: String): String? {
            val t = System.nanoTime()
            val r = Zenith.fixInContext(m, "en", left, word, right, Suggest.neighbours(word, en, { _, _ -> false }), 3f) { true }
            report("fix $word", "$left [$word→$r] $right %.0f ms".format((System.nanoTime() - t) / 1e6))
            return r
        }
        assertEquals("buy", fix("I want to ", "by", "a car"))
        assertEquals(null, fix("I'll go ", "by", "the store"))
        assertEquals(null, fix("See you ", "later", "tonight"))
        assertEquals(null, fix("I think ", "its", "fine"))
        fix("Are you ", "form", "here")
        fix("I could ", "of", "done")
    }

    @Test fun predictsNextWords() {
        val m = zen()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val en = Dictionary.load(ctx, "en")
        val es = Dictionary.load(ctx, "es")
        fun next(lang: String, d: Dictionary, before: String): List<String> {
            val t = System.nanoTime()
            val r = Zenith.nextWords(m, lang, before, 3, { d.contains(it.lowercase()) || it == "I" }) { true }!!
            report("next $before", "$r %.0f ms".format((System.nanoTime() - t) / 1e6))
            return r
        }
        assertTrue(next("en", en, "How are ").contains("you"))
        assertTrue(next("en", en, "Thank you so ").contains("much"))
        assertTrue(next("es", es, "Buenos ").contains("días"))
        next("en", en, "Let me know if you ")
    }

    @Test fun ranksKanjiInContext() {
        val m = zen()
        fun best(before: String, cands: List<String>): String {
            val t = System.nanoTime()
            val sc = Zenith.rankTexts(m, "ja", before, cands) { true }!!
            report("kanji $before", "$sc %.0f ms".format((System.nanoTime() - t) / 1e6))
            return sc.maxByOrNull { it.value }!!.key
        }
        assertEquals("帰る", best("今日は早く家に", listOf("変える", "帰る", "蛙", "返る", "かえる")))
        assertEquals("蛙", best("池の", listOf("変える", "帰る", "蛙", "返る", "かえる")))
        val t = System.nanoTime()
        val ph = Zenith.continuations(m, "よろしくお願い", 3) { true }!!
        report("phrases", "$ph %.0f ms".format((System.nanoTime() - t) / 1e6))
        assertTrue(ph.isNotEmpty())
        val t2 = System.nanoTime()
        report("phrases2", "${Zenith.continuations(m, "今日は", 3) { true }} %.0f ms".format((System.nanoTime() - t2) / 1e6))
    }

    @Test fun benchmark() {
        val zero = ZenithModel.State(IntArray(0), FloatArray(ZenithModel.KV), FloatArray(ZenithModel.KV), FloatArray(0))
        val ctx = model.contextIds("en", "Hey, I was thinking we could meet tomorrow after work and maybe grab some dinner at that new place near the station if you are free")
        repeat(3) { model.feed(zero, ctx) } // warm up
        val prefill = (0 until 10).map { val t = System.nanoTime(); model.feed(zero, ctx); (System.nanoTime() - t) / 1e6 }.sorted()
        var s = model.feed(zero, ctx.copyOf(20)).first
        val steps = ArrayList<Double>()
        for (i in 20 until ctx.size) {
            val t = System.nanoTime(); s = model.feed(s, intArrayOf(ctx[i])).first; steps.add((System.nanoTime() - t) / 1e6)
        }
        steps.sort()
        val pss = Debug.getPss().toInt()
        val msg = "load ${model.loadMs} ms; prefill ${ctx.size} tok median %.1f ms; step median %.1f p95 %.1f ms (n=${steps.size}); PSS +%d MB".format(
            prefill[prefill.size / 2], steps[steps.size / 2], steps[(steps.size * 0.95).toInt().coerceAtMost(steps.size - 1)], (pss - pssBefore) / 1024)
        Log.i("Zenith", msg)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("zenith", msg) })
    }
}
