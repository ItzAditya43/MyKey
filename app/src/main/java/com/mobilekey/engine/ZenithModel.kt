package com.mobilekey.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln

/**
 * The on-device Zenith language model (assets/zenith/zenith_kb.onnx): 7 layers, 64-token
 * window, KV cache passed in and out. Not thread-safe: call it from one background thread.
 */
class ZenithModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    val tok: ZenithTokenizer,
    val loadMs: Long,
) {
    /** The model after reading [ids]: its cache and the log-probs of the next token. */
    class State(val ids: IntArray, val k: FloatArray, val v: FloatArray, val next: FloatArray)

    private val empty = State(IntArray(0), FloatArray(KV), FloatArray(KV), FloatArray(0))
    private var cached: State = empty

    /** Feeds [tokens] after [s]. Returns the new state, plus log-prob rows for every fed token but the last. */
    fun feed(s: State, tokens: IntArray, rows: Boolean = false): Pair<State, List<FloatArray>> {
        require(tokens.isNotEmpty() && s.ids.size + tokens.size <= WINDOW)
        val t = tokens.size
        val inputs = mapOf(
            "tokens" to OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(t) { tokens[it].toLong() }), longArrayOf(1, t.toLong())),
            "pos" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(s.ids.size.toLong())), longArrayOf(1)),
            "past_k" to OnnxTensor.createTensor(env, FloatBuffer.wrap(s.k), KV_SHAPE),
            "past_v" to OnnxTensor.createTensor(env, FloatBuffer.wrap(s.v), KV_SHAPE),
        )
        try {
            session.run(inputs).use { out ->
                val logits = (out.get("logits").get() as OnnxTensor).floatBuffer
                val k = FloatArray(KV).also { (out.get("new_k").get() as OnnxTensor).floatBuffer.get(it) }
                val v = FloatArray(KV).also { (out.get("new_v").get() as OnnxTensor).floatBuffer.get(it) }
                val extra = ArrayList<FloatArray>()
                if (rows) for (r in 0 until t - 1) extra.add(logSoftmax(logits, r * VOCAB))
                val next = logSoftmax(logits, (t - 1) * VOCAB)
                return State(s.ids + tokens, k, v, next) to extra
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /**
     * The state after `<lang>` + [text], reusing the cache from the previous call: only tokens after
     * the longest shared prefix are fed. Leaves [reserve] free slots for scoring candidates.
     */
    fun context(lang: String, text: String, reserve: Int = 8): State = stateFor(contextIds(lang, text, reserve))

    /** The state after exactly [ids] (language tag first), reusing the cache. */
    fun stateFor(ids: IntArray): State {
        val old = cached
        var common = 0
        while (common < old.ids.size && common < ids.size && old.ids[common] == ids[common]) common++
        if (common == ids.size && common == old.ids.size) return old
        // Rolling back is free: cache slots past `pos` are masked, so just feed from the shared prefix.
        if (common == ids.size) common-- // need the logits after the last token
        val base = if (common == 0) empty else State(ids.copyOf(common), old.k, old.v, FloatArray(0))
        val s = feed(base, ids.copyOfRange(common, ids.size)).first
        cached = s
        return s
    }

    fun contextIds(lang: String, text: String, reserve: Int = 8): IntArray {
        val tag = ZenithTokenizer.LANG_TAG[lang] ?: ZenithTokenizer.LANG_TAG.getValue("en")
        // Lines become <eos>-separated messages, as in training. Keep only the last few lines.
        val lines = text.split('\n').takeLast(4)
        val body = ArrayList<Int>()
        lines.forEachIndexed { i, l ->
            if (i > 0) body.add(ZenithTokenizer.EOS)
            if (l.isNotEmpty()) body.addAll(tok.encode(l).toList())
        }
        val room = WINDOW - 1 - reserve
        // Keep a stable start so the cache survives typing: when the text no longer fits, drop
        // tokens from the front until about REFILL remain.
        val keep = if (body.size <= room) body.size else REFILL + (body.size - REFILL) % (room - REFILL + 1)
        return (listOf(tag) + body.subList(body.size - keep, body.size)).toIntArray()
    }

    /** log P(tokens | s), and the state after them (for the next token's distribution). */
    fun score(s: State, tokens: IntArray): Pair<Float, State> {
        var lp = s.next[tokens[0]]
        if (tokens.size == 1 && s.ids.size + 1 > WINDOW) return lp to s
        val (after, rows) = feed(s, tokens, rows = true)
        for (i in 1 until tokens.size) lp += rows[i - 1][tokens[i]]
        return lp to after
    }

    fun close() {
        session.close()
    }

    companion object {
        const val VOCAB = 32000
        const val WINDOW = 64
        const val REFILL = 47
        private const val LAYERS = 7
        private const val HEAD = 64
        const val KV = LAYERS * WINDOW * HEAD
        private val KV_SHAPE = longArrayOf(LAYERS.toLong(), 1, 1, WINDOW.toLong(), HEAD.toLong())

        /** Copies the model out of the APK once (ORT maps a file instead of holding 18 MB of bytes). */
        fun load(ctx: Context, threads: Int = 2, level: OrtSession.SessionOptions.OptLevel = OrtSession.SessionOptions.OptLevel.ALL_OPT, arena: Boolean = true, prepack: Boolean = true): ZenithModel {
            val t0 = System.nanoTime()
            val dir = File(ctx.noBackupFilesDir, "zenith").apply { mkdirs() }
            val file = File(dir, "zenith_kb.onnx")
            // Re-copy after every app update, in case the model changed.
            val stamp = File(dir, "stamp")
            val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
            if (!file.exists() || !stamp.exists() || stamp.readText() != version) {
                val tmp = File(dir, "zenith_kb.onnx.tmp")
                ctx.assets.open("zenith/zenith_kb.onnx").use { i -> tmp.outputStream().use { i.copyTo(it) } }
                tmp.renameTo(file)
                stamp.writeText(version)
            }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(level)
                setCPUArenaAllocator(arena)
                setMemoryPatternOptimization(arena)
                if (!prepack) addConfigEntry("session.disable_prepacking", "1")
                // Don't keep worker threads spinning between keystrokes (battery).
                addConfigEntry("session.intra_op.allow_spinning", "0")
            }
            val session = env.createSession(file.absolutePath, opts)
            val tok = ctx.assets.open("zenith/zenith_kb.tok").use { ZenithTokenizer.load(it) }
            return ZenithModel(env, session, tok, (System.nanoTime() - t0) / 1_000_000)
        }

        fun logSoftmax(a: FloatBuffer, off: Int): FloatArray {
            var max = Float.NEGATIVE_INFINITY
            for (i in 0 until VOCAB) { val x = a.get(off + i); if (x > max) max = x }
            var sum = 0.0
            for (i in 0 until VOCAB) sum += exp((a.get(off + i) - max).toDouble())
            val z = max + ln(sum).toFloat()
            return FloatArray(VOCAB) { a.get(off + it) - z }
        }
    }
}
