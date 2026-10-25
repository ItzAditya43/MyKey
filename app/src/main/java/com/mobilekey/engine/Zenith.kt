package com.mobilekey.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Zenith predictions for the keyboard. Every request runs on one background thread; a newer
 * request cancels older ones, and stale results are dropped. When the model isn't loaded the
 * keyboard just keeps its dictionary suggestions.
 */
object Zenith {
    @Volatile var model: ZenithModel? = null
        private set
    @Volatile var failed: String? = null
        private set
    @Volatile private var loading = false
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "zenith").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val gen = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())
    private var masks: Masks? = null

    /** Recent request times in ms, for the Stats page. */
    private val times = ArrayDeque<Float>()

    val langs = setOf("en", "es", "ja")

    fun ensure(ctx: Context) {
        if (model != null || loading || failed != null) return
        loading = true
        val app = ctx.applicationContext
        exec.execute {
            try {
                val m = ZenithModel.load(app)
                masks = Masks(m.tok)
                model = m
            } catch (t: Throwable) {
                failed = t.message ?: t.javaClass.simpleName
            }
            loading = false
        }
    }

    /** Frees the model's memory (about 80 MB); it loads again next time it's needed. */
    fun release() {
        gen.incrementAndGet()
        exec.execute { model?.close(); model = null; masks = null }
    }

    fun cancel() { gen.incrementAndGet() }

    /** Runs [work] in the background and hands its result to [done] on the main thread, unless a newer request came in. */
    fun <T : Any> request(work: (m: ZenithModel, alive: () -> Boolean) -> T?, done: (T) -> Unit) {
        val g = gen.incrementAndGet()
        exec.execute {
            if (g != gen.get()) return@execute
            val m = model ?: return@execute
            val t0 = SystemClock.elapsedRealtimeNanos()
            val r = try { work(m) { g == gen.get() } } catch (_: Throwable) { null }
            if (g == gen.get()) record((SystemClock.elapsedRealtimeNanos() - t0) / 1e6f)
            if (r != null) main.post { if (g == gen.get()) done(r) }
        }
    }

    private fun record(ms: Float) = synchronized(times) {
        times.addLast(ms)
        if (times.size > 300) times.removeFirst()
    }

    /** "median, p95" of recent requests, or null before any. */
    fun latency(): Pair<Float, Float>? = synchronized(times) {
        if (times.isEmpty()) return null
        val s = times.sorted()
        s[s.size / 2] to s[((s.size - 1) * 0.95).toInt()]
    }

    // ---------------------------------------------------------------- English / Spanish

    /**
     * log P(word is the next word | before) for each of [words] (spelled as they'd be inserted).
     * A word = its tokens, then a word end: the next token starts a new word, is punctuation or ends
     * the message. Single tokens with punctuation attached ("▁later.") also count for the word.
     */
    fun scoreWords(m: ZenithModel, lang: String, before: String, words: List<String>, alive: () -> Boolean): Map<String, Float>? {
        val mk = masks ?: return null
        val ctx = m.context(lang, latinContext(before))
        val out = HashMap<String, Float>()
        // Single-token words (most common ones) come straight from the context's distribution, with a
        // typical word-end cost; only multi-token words need a model run. Keeps a keystroke near one run.
        for (w in words.sortedBy { m.tok.encode(it).size }) {
            if (!alive()) return null
            val ids = m.tok.encode(w)
            if (ids.isEmpty() || ctx.ids.size + ids.size > ZenithModel.WINDOW) continue
            var total = if (ids.size == 1) ctx.next[ids[0]] + SINGLE_END else {
                val (lp, after) = m.score(ctx, ids)
                lp + mk.endMass(after.next)
            }
            mk.punctVariants[ZenithTokenizer.META + w]?.forEach { v -> total = logAdd(total, ctx.next[v]) }
            out[w] = total
        }
        return out
    }

    /** The most likely next words after [before] (which ends at a word boundary). [known] filters out non-words. */
    fun nextWords(m: ZenithModel, lang: String, before: String, k: Int, known: (String) -> Boolean, alive: () -> Boolean): List<String>? {
        val mk = masks ?: return null
        val ctx = m.context(lang, latinContext(before))
        if (ctx.ids.size + 2 > ZenithModel.WINDOW) return null
        val found = HashMap<String, Float>()
        fun keep(w: String, s: Float) { found[w] = found[w]?.let { logAdd(it, s) } ?: s }
        // Words that come as one token with punctuation attached are finished already.
        for ((id, base) in mk.variantOf) keep(base, ctx.next[id])
        val starts = topK(ctx.next, 10) { mk.wordStart[it] }
        val partial = ArrayList<Triple<String, Float, ZenithModel.State>>()
        for (t in starts.take(6)) {
            if (!alive()) return null
            val text = m.tok.pieces[t].substring(1)
            val after = m.feed(ctx, intArrayOf(t)).first
            keep(text, ctx.next[t] + mk.endMass(after.next))
            partial.add(Triple(text, ctx.next[t], after))
        }
        // Longer words: extend the three most likely starts by one more piece.
        for ((text, lp, st) in partial.sortedByDescending { it.second }.take(3)) {
            if (st.ids.size + 1 > ZenithModel.WINDOW) continue
            for (j in topK(st.next, 2) { mk.wordPiece[it] }) {
                if (!alive()) return null
                val after = m.feed(st, intArrayOf(j)).first
                keep(text + m.tok.pieces[j], lp + st.next[j] + mk.endMass(after.next))
            }
        }
        return found.entries.filter { WORD.matches(it.key) && known(it.key) }
            .sortedByDescending { it.value }.map { it.key }
            .distinctBy { it.lowercase() }.take(k)
    }

    /** Text up to the current word, without trailing spaces (a bare "▁" token never occurs before a word in training). */
    private fun latinContext(before: String) = before.takeLast(400).trimEnd(' ')

    /**
     * Real-word mistakes, checked once two more words follow: is "by" in "… want to by a car" more
     * likely "buy"? Scores left + candidate + right for each candidate; returns the better word when
     * it wins by [margin] nats, else null.
     */
    fun fixInContext(m: ZenithModel, lang: String, left: String, word: String, right: String, cands: List<String>, margin: Float, alive: () -> Boolean): String? {
        val all = listOf(word) + cands
        val sc = rankTexts(m, lang, left.trimEnd(' '), all.map { " $it $right" }, 160, alive) ?: return null
        val own = sc[" $word $right"] ?: return null
        val best = all.maxByOrNull { sc[" $it $right"] ?: -1e9f } ?: return null
        return best.takeIf { it != word && (sc[" $it $right"] ?: -1e9f) - own >= margin }
    }

    // ---------------------------------------------------------------- Japanese

    /**
     * Relative log P(before + c) for each candidate c. Scored from the first token where the
     * candidates' tokenisations differ, so merges across the boundary (今日 + は → 今日は) are fair.
     */
    fun rankTexts(m: ZenithModel, lang: String, before: String, cands: List<String>, chars: Int = 32, alive: () -> Boolean): Map<String, Float>? {
        val ctxText = before.takeLast(chars)
        val base = m.context(lang, ctxText, reserve = 0)
        val seqs = cands.map { m.contextIds(lang, ctxText + it, reserve = 0) }
        if (seqs.any { it.size > ZenithModel.WINDOW }) return null
        var common = base.ids.size
        for (s in seqs) {
            var c = 0
            while (c < common && c < s.size && s[c] == base.ids[c]) c++
            common = c
        }
        common = common.coerceAtLeast(1)
        // Roll back to just before the split point (cache slots past it are masked).
        val from = ZenithModel.State(base.ids.copyOf(common - 1), base.k, base.v, FloatArray(0))
        val out = HashMap<String, Float>()
        cands.forEachIndexed { i, c ->
            if (!alive()) return null
            val s = seqs[i]
            if (s.size <= common) { out[c] = 0f; return@forEachIndexed }
            val (_, rows) = m.feed(from, s.copyOfRange(common - 1, s.size), rows = true)
            var lp = 0f
            for (j in common until s.size) lp += rows[j - common][s[j]]
            out[c] = lp
        }
        return out
    }

    /**
     * Likely next phrases after committed Japanese text. Token healing: the last context token is
     * dropped and the continuation must reproduce it, so a context cut mid-token (今日 when training
     * saw 今日は) stays in distribution. The healed text is stripped from the results.
     */
    fun continuations(m: ZenithModel, before: String, k: Int, alive: () -> Boolean): List<String>? {
        val mk = masks ?: return null
        val ids = m.contextIds("ja", before.takeLast(32), reserve = 6)
        val heal = if (ids.size > 1) m.tok.pieces[ids.last()].replace(ZenithTokenizer.META.toString(), "") else ""
        val baseIds = if (heal.isNotEmpty()) ids.copyOf(ids.size - 1) else ids
        val atStart = baseIds.size == 1
        val base = m.stateFor(baseIds)
        class Cand(val st: ZenithModel.State?, val score: Float, val text: String, val last: Int)
        var beam = listOf(Cand(base, 0f, "", -1))
        val done = ArrayList<Pair<String, Float>>()
        for (step in 0 until 4 + (if (heal.isEmpty()) 0 else 1)) {
            if (beam.isEmpty() || !alive()) break
            val next = ArrayList<Cand>()
            for (c in beam) {
                val st = c.st ?: continue
                val row = st.next
                val healing = c.text.length < heal.length
                val first = atStart && c.text.isEmpty()
                val allowed = if (healing) mk.healMask(heal.substring(c.text.length), first) else null
                for (j in topK(row, 16) { (allowed == null || allowed[it]) && !mk.special[it] }) {
                    val p = m.tok.pieces[j]
                    if (p.startsWith(ZenithTokenizer.META) && !first) continue
                    val t2 = c.text + p.replace(ZenithTokenizer.META.toString(), "")
                    if (heal.isNotEmpty() && !(t2.startsWith(heal) || heal.startsWith(t2))) continue
                    next.add(Cand(st, c.score + row[j], t2, j))
                }
            }
            // Keep the best, then feed only those (one model run each).
            val kept = next.sortedByDescending { it.score }.take(4)
            val fed = ArrayList<Cand>()
            for (c in kept) {
                if (!alive()) return null
                if (c.text.length - heal.length >= 2 && c.text.startsWith(heal)) {
                    done.add(c.text.substring(heal.length) to c.score)
                } else if (c.st!!.ids.size + 1 <= ZenithModel.WINDOW) {
                    fed.add(Cand(m.feed(c.st, intArrayOf(c.last)).first, c.score, c.text, c.last))
                }
            }
            beam = fed
        }
        return done.sortedByDescending { it.second }.map { it.first }
            .filter { s -> s.isNotBlank() && s.none { it == '\n' } }.distinct().take(k)
    }

    // ---------------------------------------------------------------- helpers

    /** log P(word ends) assumed for one-token words (measured ends are usually -0.1 to -0.5). */
    private const val SINGLE_END = -0.25f

    private val WORD = Regex("^[\\p{L}][\\p{L}'’\\-]*$")

    private fun logAdd(a: Float, b: Float): Float {
        val hi = max(a, b)
        return hi + ln(exp((a - hi).toDouble()) + exp((b - hi).toDouble())).toFloat()
    }

    private fun topK(row: FloatArray, k: Int, ok: (Int) -> Boolean): List<Int> {
        val best = IntArray(k) { -1 }
        for (i in row.indices) {
            if (!ok(i)) continue
            val v = row[i]
            if (best[k - 1] >= 0 && v <= row[best[k - 1]]) continue
            var p = k - 1
            while (p > 0 && (best[p - 1] < 0 || row[best[p - 1]] < v)) { best[p] = best[p - 1]; p-- }
            best[p] = i
        }
        return best.filter { it >= 0 }
    }

    /** Per-vocabulary flags, computed once per model. */
    private class Masks(tok: ZenithTokenizer) {
        val n = tok.size
        val special = BooleanArray(n) { tok.isSpecial(it) }
        /** Ends the current word: starts a new one, is punctuation, a newline or a special token. */
        val terminator = BooleanArray(n)
        /** "▁" + letters: the start of a Latin word. */
        val wordStart = BooleanArray(n)
        /** Letters only, no "▁": continues a word. */
        val wordPiece = BooleanArray(n)
        /** "▁later." → "later": one-token words with punctuation attached. */
        val punctVariants = HashMap<String, MutableList<Int>>()
        val variantOf = ArrayList<Pair<Int, String>>()
        private val pieces = tok.pieces
        private val healCache = LinkedHashMap<Pair<String, Boolean>, BooleanArray>(16, 0.75f, true)

        init {
            for (i in 0 until n) {
                val p = tok.pieces[i]
                if (tok.isSpecial(i) || tok.isByte(i)) { terminator[i] = tok.isSpecial(i); continue }
                val meta = p.startsWith(ZenithTokenizer.META)
                val rest = if (meta) p.substring(1) else p
                if (meta || rest.all { isPunct(it) } || rest.contains('\n')) terminator[i] = true
                if (rest.isNotEmpty() && rest.all { it.isLetter() || it == '\'' || it == '’' }) {
                    if (meta && rest[0].isLetter()) wordStart[i] = true
                    if (!meta) wordPiece[i] = true
                }
                if (meta && rest.length >= 2 && isPunct(rest.last()) && rest[0].isLetter()) {
                    val core = rest.trimEnd { isPunct(it) }
                    if (core.isNotEmpty() && core.all { it.isLetter() || it == '\'' }) {
                        punctVariants.getOrPut(ZenithTokenizer.META + core) { ArrayList() }.add(i)
                        variantOf.add(i to core)
                    }
                }
            }
            terminator[ZenithTokenizer.EOS] = true
        }

        fun endMass(row: FloatArray): Float {
            var mx = Float.NEGATIVE_INFINITY
            for (i in 0 until n) if (terminator[i] && row[i] > mx) mx = row[i]
            var sum = 0.0
            for (i in 0 until n) if (terminator[i]) sum += exp((row[i] - mx).toDouble())
            return mx + ln(sum).toFloat()
        }

        /** Pieces consistent with the text still to be reproduced while healing. */
        fun healMask(rest: String, wordStartOk: Boolean): BooleanArray = synchronized(healCache) {
            healCache.getOrPut(rest to wordStartOk) {
                if (healCache.size > 32) healCache.remove(healCache.keys.first())
                BooleanArray(n) { i ->
                    if (special[i] || i in 9 until 265) return@BooleanArray false
                    val p = pieces[i]
                    if (wordStartOk != p.startsWith(ZenithTokenizer.META)) return@BooleanArray false
                    val t = p.replace(ZenithTokenizer.META.toString(), "")
                    t.isNotEmpty() && (t.startsWith(rest) || rest.startsWith(t))
                }
            }
        }

        private fun isPunct(c: Char) = Character.getType(c).let {
            it == Character.CONNECTOR_PUNCTUATION.toInt() || it == Character.DASH_PUNCTUATION.toInt() ||
                it == Character.START_PUNCTUATION.toInt() || it == Character.END_PUNCTUATION.toInt() ||
                it == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || it == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                it == Character.OTHER_PUNCTUATION.toInt()
        }
    }
}
