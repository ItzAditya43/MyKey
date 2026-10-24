package com.mobilekey.engine

import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Glide typing decoder. Compares the finger path with the ideal path through each
 * candidate word's keys (both resampled to the same number of points), then mixes in
 * word frequency.
 */
object Glide {
    private const val N = 32

    fun decode(
        path: List<PointF>,
        centers: Map<Char, PointF>,
        keyW: Float,
        dicts: List<Dictionary>,
        userWords: List<UserModel.Word>,
    ): List<String> {
        if (path.size < 2 || centers.isEmpty() || keyW <= 0f) return emptyList()
        val starts = nearKeys(path.first(), centers, keyW * 1.1f)
        val ends = nearKeys(path.last(), centers, keyW * 1.1f)
        if (starts.isEmpty() || ends.isEmpty()) return emptyList()
        val sample = resample(path)
        val pathLen = length(path)

        val scored = HashMap<String, Float>()
        fun consider(word: String, freq: Float) {
            if (word.length < 2) return
            if (baseOf(word[0].lowercaseChar()) !in starts || baseOf(word[word.length - 1].lowercaseChar()) !in ends) return
            val base = Dictionary.strip(word.lowercase()).filter { it.isLetter() }
            if (base.length < 2 || base[0] !in starts || base.last() !in ends) return
            val pts = ArrayList<PointF>()
            var prev = ' '
            for (ch in base) {
                if (ch == prev) continue
                val c = centers[ch] ?: return
                pts.add(c)
                prev = ch
            }
            if (pts.size < 2) pts.add(pts[0])
            val ideal = resample(pts)
            var loc = 0f
            for (i in 0 until N) loc += hypot(ideal[i].x - sample[i].x, ideal[i].y - sample[i].y)
            loc /= N * keyW
            val ratio = length(pts) / pathLen.coerceAtLeast(1f)
            val lenPenalty = kotlin.math.abs(ln(ratio.coerceIn(0.2f, 5f)))
            val s = -loc * 6f - lenPenalty * 2.5f + freq * 0.35f
            val cur = scored[word]
            if (cur == null || s > cur) scored[word] = s
        }
        dicts.forEach { d -> d.forEach { w, f -> consider(w, f) } }
        userWords.forEach { consider(it.word, 12f + ln(it.count + 1f)) }
        return scored.entries.sortedByDescending { it.value }.take(4).map { it.key }
    }

    private val baseCache = HashMap<Char, Char>()
    /** Letter without its accent (é → e), cached because it runs for every dictionary word. */
    private fun baseOf(c: Char): Char = baseCache.getOrPut(c) { Dictionary.strip(c.toString()).firstOrNull() ?: c }

    private fun nearKeys(p: PointF, centers: Map<Char, PointF>, r: Float): Set<Char> {
        val near = centers.filter { hypot(it.value.x - p.x, it.value.y - p.y) < r }.keys
        if (near.isNotEmpty()) return near
        return setOfNotNull(centers.minByOrNull { hypot(it.value.x - p.x, it.value.y - p.y) }?.key)
    }

    private fun length(p: List<PointF>): Float {
        var l = 0f
        for (i in 1 until p.size) l += hypot(p[i].x - p[i - 1].x, p[i].y - p[i - 1].y)
        return l
    }

    private fun resample(p: List<PointF>): Array<PointF> {
        val total = length(p)
        if (total == 0f) return Array(N) { PointF(p[0].x, p[0].y) }
        val step = total / (N - 1)
        val out = ArrayList<PointF>(N)
        out.add(PointF(p[0].x, p[0].y))
        var acc = 0f
        var prev = PointF(p[0].x, p[0].y)
        var i = 1
        while (i < p.size && out.size < N) {
            val cur = p[i]
            val d = hypot(cur.x - prev.x, cur.y - prev.y)
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                val q = PointF(prev.x + t * (cur.x - prev.x), prev.y + t * (cur.y - prev.y))
                out.add(q)
                prev = q
                acc = 0f
            } else {
                acc += d
                prev = cur
                i++
            }
        }
        while (out.size < N) out.add(PointF(p.last().x, p.last().y))
        return out.toTypedArray()
    }
}
