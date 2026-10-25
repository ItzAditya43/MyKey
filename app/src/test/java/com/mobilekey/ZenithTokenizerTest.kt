package com.mobilekey

import com.mobilekey.engine.ZenithTokenizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ZenithTokenizerTest {
    private val tok = File("src/main/assets/zenith/zenith_kb.tok").inputStream().use { ZenithTokenizer.load(it) }

    /** Every line must encode to exactly the ids the Hugging Face tokenizer produced. */
    @Test
    fun matchesHuggingFace() {
        val lines = javaClass.classLoader!!.getResourceAsStream("zenith_tok_vectors.jsonl")!!.bufferedReader().readLines()
        val bad = ArrayList<String>()
        for (line in lines) {
            val o = JSONObject(line)
            val text = o.getString("t")
            val want = o.getJSONArray("ids").let { a -> IntArray(a.length()) { a.getInt(it) } }
            val got = tok.encode(text)
            if (!got.contentEquals(want)) bad.add("$text\n  want ${want.toList()}\n  got  ${got.toList()}")
        }
        assertEquals("mismatches (${bad.size}/${lines.size}):\n" + bad.take(5).joinToString("\n"), 0, bad.size)
    }

    @Test
    fun decodesBack() {
        for (s in listOf("hello world", "mañana está", "今日は いい天気", "emoji 😀👍🏽 ok", "I'm don't")) {
            assertEquals(s, tok.decode(tok.encode(s)))
        }
    }
}
