package com.mobilekey.engine

import android.content.Context

/** One conversion choice. [len] is how many characters of the reading it consumes. */
data class Candidate(val text: String, val len: Int)

/**
 * Reading → text conversion used for Japanese (kana → kanji, Mozc data) and Mandarin
 * (pinyin → hanzi, AOSP PinyinIME data). Lower cost is better.
 * Readings are kept in one sorted array to keep memory small inside the keyboard process.
 */
class Converter private constructor(
    private val keys: Array<String>,
    private val start: IntArray,
    private val surf: Array<String>,
    private val cost: IntArray,
    private val maxKey: Int,
    private val wordPenalty: Int,
    private val unknownCost: Int,
) {
    private fun find(k: String): Int {
        var lo = 0
        var hi = keys.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = keys[mid].compareTo(k)
            if (c == 0) return mid
            if (c < 0) lo = mid + 1 else hi = mid - 1
        }
        return -1
    }

    private fun entries(i: Int): List<Pair<String, Int>> =
        (start[i] until start[i + 1]).map { surf[it] to cost[it] }.sortedBy { it.second }

    fun exact(k: String): List<Pair<String, Int>> = find(k).let { if (it < 0) emptyList() else entries(it) }

    /** Words whose reading starts with [prefix] (but is longer), cheapest first. */
    fun predict(prefix: String, n: Int): List<Pair<String, Int>> {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < prefix) lo = mid + 1 else hi = mid
        }
        val out = ArrayList<Pair<String, Int>>()
        var i = lo
        var scanned = 0
        while (i < keys.size && keys[i].startsWith(prefix) && scanned < 600) {
            if (keys[i].length > prefix.length) out.addAll(entries(i).take(2))
            i++; scanned++
        }
        return out.sortedBy { it.second }.take(n)
    }

    /** Best whole-reading conversion: shortest path over a lattice of dictionary words. */
    fun convert(reading: String): String {
        val n = reading.length
        if (n == 0) return ""
        val best = IntArray(n + 1) { Int.MAX_VALUE }
        val back = IntArray(n + 1)
        val word = arrayOfNulls<String>(n + 1)
        best[0] = 0
        for (i in 0 until n) {
            if (best[i] == Int.MAX_VALUE) continue
            for (j in i + 1..minOf(n, i + maxKey)) {
                val e = find(reading.substring(i, j))
                if (e >= 0) {
                    val top = entries(e).first()
                    val c = best[i] + top.second + wordPenalty
                    if (c < best[j]) { best[j] = c; back[j] = i; word[j] = top.first }
                }
            }
            val c = best[i] + unknownCost
            if (c < best[i + 1]) { best[i + 1] = c; back[i + 1] = i; word[i + 1] = reading.substring(i, i + 1) }
        }
        val parts = ArrayList<String>()
        var j = n
        while (j > 0) { parts.add(word[j]!!); j = back[j] }
        return parts.reversed().joinToString("")
    }

    /**
     * Candidate list: whole-reading conversion, exact words, words for the longest prefix
     * (picking one converts just that part), predictions, then the raw forms.
     */
    fun candidates(reading: String, raw: List<String>, learned: List<String>): List<Candidate> {
        if (reading.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Candidate>()
        fun add(t: String, len: Int) { if (t.isNotEmpty() && t !in out) out[t] = Candidate(t, len) }
        learned.forEach { add(it, reading.length) }
        val ex = exact(reading)
        if (ex.isNotEmpty()) add(ex.first().first, reading.length)
        add(convert(reading), reading.length)
        ex.forEach { add(it.first, reading.length) }
        for (len in minOf(reading.length - 1, maxKey) downTo 1) {
            val e = exact(reading.substring(0, len))
            if (e.isNotEmpty()) { e.take(6).forEach { add(it.first, len) }; break }
        }
        predict(reading, 8).forEach { add(it.first, reading.length) }
        raw.forEach { add(it, reading.length) }
        return out.values.toList()
    }

    companion object {
        /** File lines: reading \t text \t cost-or-score. [scoreIsLog] means higher is better (Mandarin). */
        fun load(ctx: Context, asset: String, scoreIsLog: Boolean): Converter {
            val k = ArrayList<String>(200000)
            val st = ArrayList<Int>(200000)
            val s = ArrayList<String>(300000)
            val c = ArrayList<Int>(300000)
            var last: String? = null
            var maxKey = 1
            ctx.assets.open(asset).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val t1 = line.indexOf('\t')
                    val t2 = line.indexOf('\t', t1 + 1)
                    if (t1 <= 0 || t2 <= t1) continue
                    val key = line.substring(0, t1)
                    val cost = if (scoreIsLog) ((14.0 - line.substring(t2 + 1).toDouble()) * 600).toInt()
                    else line.substring(t2 + 1).toIntOrNull() ?: continue
                    if (key != last) {
                        k.add(key); st.add(s.size); last = key
                        if (key.length > maxKey) maxKey = key.length
                    }
                    s.add(line.substring(t1 + 1, t2)); c.add(cost)
                }
            }
            st.add(s.size)
            return Converter(
                k.toTypedArray(), st.toIntArray(), s.toTypedArray(), c.toIntArray(),
                minOf(maxKey, 16),
                wordPenalty = if (scoreIsLog) 900 else 2000,
                unknownCost = if (scoreIsLog) 12000 else 16000,
            )
        }
    }
}
