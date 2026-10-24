package com.mobilekey.engine

import android.content.Context
import java.text.Normalizer
import kotlin.math.ln

/**
 * Read-only word list for one language, loaded from assets/dict/<lang>.txt
 * ("word count" per line, most frequent first).
 */
class Dictionary private constructor(
    val lang: String,
    private val words: Array<String>,
    private val scores: FloatArray,
) {
    private val index = HashMap<String, Int>(words.size * 2)
    private val sorted: IntArray
    private val byLen: Array<IntArray>
    private val unaccent = HashMap<String, Int>()

    init {
        words.forEachIndexed { i, w -> index.putIfAbsent(w, i) }
        sorted = words.indices.sortedWith { a, b -> words[a].compareTo(words[b]) }.toIntArray()
        val buckets = Array(MAX_LEN + 1) { ArrayList<Int>() }
        words.forEachIndexed { i, w -> buckets[w.length].add(i) }
        byLen = Array(MAX_LEN + 1) { buckets[it].toIntArray() }
        // Words are in frequency order, so the first word to claim a stripped form is the most common.
        words.forEachIndexed { i, w ->
            val s = strip(w)
            if (s != w) unaccent.putIfAbsent(s, i)
        }
    }

    val size get() = words.size

    fun contains(w: String) = index.containsKey(w)

    fun forEach(cb: (String, Float) -> Unit) {
        for (i in words.indices) cb(words[i], scores[i])
    }

    fun score(w: String): Float? = index[w]?.let { scores[it] }

    /** Most frequent words that start with [prefix], excluding the prefix itself. */
    fun completions(prefix: String, n: Int): List<Pair<String, Float>> {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[sorted[mid]] < prefix) lo = mid + 1 else hi = mid
        }
        val found = ArrayList<Pair<String, Float>>()
        var i = lo
        while (i < sorted.size && found.size < 400) {
            val w = words[sorted[i]]
            if (!w.startsWith(prefix)) break
            if (w != prefix) found.add(w to scores[sorted[i]])
            i++
        }
        return found.sortedByDescending { it.second }.take(n)
    }

    /** "manana" → "mañana", "esta" → "está" style lookups. */
    fun unaccented(w: String): String? = unaccent[w]?.let { words[it] }

    fun corrections(w: String, maxDist: Float, near: (Char, Char) -> Boolean): List<Pair<String, Float>> {
        val res = ArrayList<Pair<String, Float>>()
        val l = w.length
        for (len in maxOf(1, l - 2)..minOf(MAX_LEN, l + 2)) {
            for (i in byLen[len]) {
                val d = Edit.distance(w, words[i], maxDist, near)
                if (d <= maxDist) res.add(words[i] to d)
            }
        }
        return res
    }

    companion object {
        const val MAX_LEN = 32
        private val SINGLE = mapOf(
            "en" to setOf("a", "i"),
            "es" to setOf("a", "e", "o", "u", "y"),
            "ru" to setOf("я", "в", "и", "с", "к", "о", "у", "а"),
        )

        fun strip(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

        fun load(ctx: Context, lang: String): Dictionary {
            val words = ArrayList<String>(50000)
            val scores = ArrayList<Float>(50000)
            val single = SINGLE[lang] ?: emptySet()
            ctx.assets.open("dict/$lang.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val sp = line.lastIndexOf(' ')
                    if (sp <= 0) continue
                    val w = line.substring(0, sp).trim().lowercase()
                    val c = line.substring(sp + 1).trim().toLongOrNull() ?: continue
                    if (w.isEmpty() || w.length > MAX_LEN) continue
                    if (w.length == 1 && w !in single) continue
                    if (!w.all { it.isLetter() || it == '\'' }) continue
                    words.add(w)
                    scores.add(ln(c.toFloat() + 1f))
                }
            }
            return Dictionary(lang, words.toTypedArray(), scores.toFloatArray())
        }
    }
}

object Edit {
    /**
     * Damerau–Levenshtein distance where hitting a neighbouring key costs 0.6 instead of 1,
     * swapping two letters costs 0.8 and a missing/extra doubled letter costs 0.5.
     * Returns 99 once it can't stay under [max].
     */
    fun distance(a: String, b: String, max: Float, near: (Char, Char) -> Boolean): Float {
        val n = a.length
        val m = b.length
        if (kotlin.math.abs(n - m) > max) return 99f
        var pp = FloatArray(m + 1)
        var p = FloatArray(m + 1) { it.toFloat() }
        var c = FloatArray(m + 1)
        for (i in 1..n) {
            c[0] = i.toFloat()
            var rowMin = c[0]
            val ca = a[i - 1]
            for (j in 1..m) {
                val cb = b[j - 1]
                val sub = if (ca == cb) 0f else if (near(ca, cb)) 0.6f else 1f
                // Missing or extra doubled letters (helo/hello, untill/until) are the most common typo: half cost.
                val del = if (i > 1 && ca == a[i - 2]) 0.5f else 1f
                val ins = if (j > 1 && cb == b[j - 2]) 0.5f else 1f
                var v = minOf(p[j] + del, c[j - 1] + ins, p[j - 1] + sub)
                if (i > 1 && j > 1 && ca == b[j - 2] && a[i - 2] == cb) v = minOf(v, pp[j - 2] + 0.8f)
                c[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return 99f
            val t = pp
            pp = p
            p = c
            c = t
        }
        return p[m]
    }
}
