package com.mobilekey.engine

import java.io.InputStream
import java.text.Normalizer

/**
 * Zenith's Unigram tokenizer, read from assets/zenith/zenith_kb.tok.
 * Matches the Hugging Face tokenizer: NFKC, metaspace "▁" (split per word),
 * Viterbi over piece scores, byte fallback for characters no piece covers.
 */
class ZenithTokenizer private constructor(
    val pieces: Array<String>,
    private val scores: DoubleArray,
    private val byteOffset: Int,
    private val firstNormal: Int,
) {
    private val ids = HashMap<String, Int>(pieces.size * 2)
    private val unkScore: Double

    init {
        var min = Double.MAX_VALUE
        for (i in firstNormal until pieces.size) {
            ids.putIfAbsent(pieces[i], i)
            if (scores[i] < min) min = scores[i]
        }
        unkScore = min - 10.0
    }

    val size get() = pieces.size

    fun id(piece: String): Int? = ids[piece]

    fun isSpecial(id: Int) = id < SPECIAL

    fun isByte(id: Int) = id in byteOffset until byteOffset + 256

    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(' ', META)
        if (s.isEmpty()) return IntArray(0)
        if (s[0] != META) s = META + s
        val out = ArrayList<Int>(s.length)
        // Split before every ▁ (each word keeps its leading ▁), as the HF Metaspace pre-tokenizer does.
        var start = 0
        for (i in 1..s.length) {
            if (i == s.length || s[i] == META) {
                viterbi(s.substring(start, i), out)
                start = i
            }
        }
        return out.toIntArray()
    }

    private fun viterbi(word: String, out: MutableList<Int>) {
        val cps = word.codePoints().toArray()
        val n = cps.size
        // Char offsets of each code point, so substrings stay cheap.
        val off = IntArray(n + 1)
        for (i in 0 until n) off[i + 1] = off[i] + Character.charCount(cps[i])
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val from = IntArray(n + 1)
        val tok = IntArray(n + 1)
        best[0] = 0.0
        for (i in 0 until n) {
            if (best[i] == Double.NEGATIVE_INFINITY) continue
            var single = false
            for (len in 1..minOf(MAX_PIECE, n - i)) {
                val id = ids[word.substring(off[i], off[i + len])] ?: continue
                if (len == 1) single = true
                val sc = best[i] + scores[id]
                // Exact ties go to the later split, as Hugging Face's lattice does ("...." → "...", ".").
                if (sc >= best[i + len]) {
                    best[i + len] = sc
                    from[i + len] = i
                    tok[i + len] = id
                }
            }
            if (!single) {
                val sc = best[i] + unkScore
                if (sc > best[i + 1]) {
                    best[i + 1] = sc
                    from[i + 1] = i
                    tok[i + 1] = -1
                }
            }
        }
        val rev = ArrayList<Int>()
        var p = n
        while (p > 0) {
            val q = from[p]
            if (tok[p] >= 0) {
                rev.add(tok[p])
            } else {
                val bytes = String(Character.toChars(cps[q])).toByteArray(Charsets.UTF_8)
                for (b in bytes.indices.reversed()) rev.add(byteOffset + (bytes[b].toInt() and 0xff))
            }
            p = q
        }
        for (i in rev.indices.reversed()) out.add(rev[i])
    }

    /** Pieces back to text: ▁ becomes a space, byte runs are joined back into UTF-8. */
    fun decode(seq: IntArray, dropLeadingSpace: Boolean = true): String {
        val sb = StringBuilder()
        val buf = java.io.ByteArrayOutputStream()
        fun flush() {
            if (buf.size() > 0) { sb.append(buf.toString(Charsets.UTF_8.name())); buf.reset() }
        }
        for (id in seq) {
            if (isByte(id)) { buf.write(id - byteOffset); continue }
            flush()
            if (isSpecial(id)) continue
            sb.append(pieces[id].replace(META, ' '))
        }
        flush()
        return if (dropLeadingSpace && sb.startsWith(" ")) sb.substring(1) else sb.toString()
    }

    companion object {
        const val META = '▁'
        const val MAX_PIECE = 16
        const val SPECIAL = 9
        const val EOS = 2
        val LANG_TAG = mapOf("en" to 4, "es" to 5, "ja" to 6)

        fun load(input: InputStream): ZenithTokenizer {
            val lines = input.bufferedReader(Charsets.UTF_8).readLines()
            require(lines[0].startsWith("ZENITH-UNIGRAM")) { "not a Zenith tokenizer" }
            val meta = org.json.JSONObject(lines[1])
            val n = meta.getInt("vocab_size")
            val byteOffset = meta.optInt("byte_offset", 9)
            val pieces = Array(n) { "" }
            val scores = DoubleArray(n)
            for (i in 0 until n) {
                val line = lines[i + 2]
                val tab = line.lastIndexOf('\t')
                pieces[i] = unescape(line.substring(0, tab))
                scores[i] = line.substring(tab + 1).toDouble()
            }
            return ZenithTokenizer(pieces, scores, byteOffset, byteOffset + 256)
        }

        private fun unescape(s: String): String {
            if ('\\' !in s) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        't' -> sb.append('\t'); 'n' -> sb.append('\n'); '\\' -> sb.append('\\')
                        else -> { sb.append(c); sb.append(s[i + 1]) }
                    }
                    i += 2
                } else { sb.append(c); i++ }
            }
            return sb.toString()
        }
    }
}
