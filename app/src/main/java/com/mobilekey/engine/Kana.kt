package com.mobilekey.engine

/** Romaji → hiragana, dakuten cycling, katakana. */
object Kana {
    private val table = HashMap<String, String>()
    private val prefixes = HashSet<String>()

    init {
        val v = "aiueo"
        val rows = mapOf(
            "" to "あいうえお", "k" to "かきくけこ", "s" to "さしすせそ", "t" to "たちつてと", "n" to "なにぬねの",
            "h" to "はひふへほ", "m" to "まみむめも", "r" to "らりるれろ", "g" to "がぎぐげご", "z" to "ざじずぜぞ",
            "d" to "だぢづでど", "b" to "ばびぶべぼ", "p" to "ぱぴぷぺぽ", "x" to "ぁぃぅぇぉ", "l" to "ぁぃぅぇぉ",
            "v" to "ゔぁゔぃゔゔぇゔぉ",
        )
        for ((c, k) in rows) if (c != "v") for (i in 0..4) table[c + v[i]] = k[i].toString()
        table.putAll(mapOf(
            "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ",
            "ya" to "や", "yu" to "ゆ", "yo" to "よ", "ye" to "いぇ", "wa" to "わ", "wi" to "うぃ", "we" to "うぇ", "wo" to "を",
            "shi" to "し", "chi" to "ち", "tsu" to "つ", "fu" to "ふ", "ji" to "じ", "nn" to "ん", "n'" to "ん", "-" to "ー",
            "xtu" to "っ", "ltu" to "っ", "xtsu" to "っ", "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
            "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ", "xwa" to "ゎ",
            "she" to "しぇ", "che" to "ちぇ", "je" to "じぇ", "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
            "ti" to "てぃ", "di" to "でぃ", "tu" to "とぅ", "du" to "どぅ", "thi" to "てぃ", "dhi" to "でぃ",
            "," to "、", "." to "。", "?" to "？", "!" to "！", "[" to "「", "]" to "」", "~" to "〜",
        ))
        val yo = mapOf(
            "ky" to "き", "gy" to "ぎ", "sy" to "し", "sh" to "し", "ch" to "ち", "cy" to "ち", "ty" to "ち", "j" to "じ",
            "jy" to "じ", "zy" to "じ", "ny" to "に", "hy" to "ひ", "my" to "み", "ry" to "り", "by" to "び", "py" to "ぴ", "dy" to "ぢ",
        )
        for ((p, k) in yo) {
            table[p + "a"] = k + "ゃ"; table[p + "u"] = k + "ゅ"; table[p + "o"] = k + "ょ"
        }
        for (key in table.keys) for (i in 1..key.length) prefixes.add(key.substring(0, i))
    }

    /** Feed one typed character. Returns (kana produced, romaji still pending). */
    fun feed(pending: String, c: Char): Pair<String, String> {
        var pend = pending + c.lowercaseChar()
        val out = StringBuilder()
        while (pend.isNotEmpty()) {
            table[pend]?.let { out.append(it); pend = ""; return out.toString() to pend }
            if (pend in prefixes) break
            if (pend.length >= 2 && pend[0] == pend[1] && pend[0] in "bcdfghjkmpqrstvwxyz") {
                out.append('っ'); pend = pend.substring(1); continue
            }
            if (pend[0] == 'n' && pend.length >= 2) { out.append('ん'); pend = pend.substring(1); continue }
            out.append(pend[0]); pend = pend.substring(1)
        }
        return out.toString() to pend
    }

    /** What pending romaji turns into when the word is committed. */
    fun flush(pending: String) = if (pending == "n") "ん" else pending

    private val dakuten = HashMap<Char, Char>().apply {
        val pairs = "かがきぎくぐけげこごさざしじすずせぜそぞただちぢてでとどあぁいぃうぅえぇおぉやゃゆゅよょわゎ"
        var i = 0
        while (i < pairs.length) { put(pairs[i], pairs[i + 1]); put(pairs[i + 1], pairs[i]); i += 2 }
        val tri = "はばぱひびぴふぶぷへべぺほぼぽつっづ"
        i = 0
        while (i < tri.length) { put(tri[i], tri[i + 1]); put(tri[i + 1], tri[i + 2]); put(tri[i + 2], tri[i]); i += 3 }
    }

    /** ゛゜小 key: か→が→か, は→ば→ぱ→は, つ→っ→づ→つ. */
    fun cycle(c: Char): Char? = dakuten[c]

    fun toKatakana(s: String) = buildString {
        for (c in s) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c)
    }

    fun isHiragana(s: String) = s.isNotEmpty() && s.all { it in 'ぁ'..'ゖ' || it == 'ー' }
}
