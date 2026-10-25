package com.mobilekey.engine

import kotlin.math.ln

/** [list] is in display priority: index 0 is the main (middle) suggestion. */
class Suggestions(val list: List<String>, val auto: String?)

object Suggest {
    /** Words never offered as suggestions or corrections (set by the keyboard when the filter is on). */
    @Volatile var offensive: Set<String> = emptySet()

    fun loadOffensive(ctx: android.content.Context): Set<String> =
        ctx.assets.open("dict/offensive.txt").bufferedReader().useLines { l -> l.mapNotNull { it.split('\t').getOrNull(1) }.toSet() }

    /** Corrections the word lists can't express (they contain texting forms like "dont"). */
    private val FIX = mapOf(
        "en" to mapOf(
            "i" to "I", "im" to "I'm", "ive" to "I've", "dont" to "don't", "cant" to "can't",
            "wont" to "won't", "didnt" to "didn't", "doesnt" to "doesn't", "isnt" to "isn't",
            "wasnt" to "wasn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
            "wouldnt" to "wouldn't", "thats" to "that's", "whats" to "what's", "youre" to "you're",
            "theyre" to "they're", "teh" to "the", "adn" to "and", "recieve" to "receive", "alot" to "a lot",
        ),
        "es" to mapOf("q" to "que", "xq" to "porque", "tb" to "también"),
    )

    fun run(
        typed: String,
        lang: String,
        dicts: List<Dictionary>,
        user: UserModel,
        near: (Char, Char) -> Boolean,
        autocorrect: Boolean,
        aggressive: Boolean,
    ): Suggestions {
        val lower = typed.lowercase()
        val dictScore = dicts.maxOfOrNull { it.score(lower) ?: -1f } ?: -1f
        val userKnown = user.knows(lang, lower)
        val known = dictScore >= 0f || userKnown
        // The word lists come from subtitles, so they contain rare misspellings ("helo", "untill").
        // A rare listed word with a far more common neighbour one letter away is treated as a typo.
        val weak = dictScore >= 0f && !userKnown && lower.length >= 4 && dictScore < RARE
        val scores = HashMap<String, Float>()
        val corrections = HashSet<String>()
        val closeCommon = HashSet<String>()

        fun add(w: String, s: Float, isCorrection: Boolean) {
            if (user.isBlocked(w)) return
            if (w.lowercase() != lower && w.lowercase() in offensive) return
            val cur = scores[w]
            if (cur == null || s > cur) scores[w] = s
            if (isCorrection) corrections.add(w)
        }

        FIX[lang]?.get(lower)?.let { add(it, 100f, true) }
        user.prefix(lang, lower).forEach { (w, c) -> add(w, 14f + ln(c + 1f) * 2f, false) }
        dicts.forEachIndexed { di, d ->
            val penalty = if (di == 0) 0f else 1.5f
            d.completions(lower, 4).forEach { (w, s) -> add(w, s - 1.5f - penalty, false) }
            d.unaccented(lower)?.let {
                val ws = d.score(it) ?: 0f
                add(it, ws + 3f - penalty, true)
                // "manana" is in the list as a rare misspelling of "mañana": fix it like other rare typos.
                val accentFix = dictScore >= 0f && !userKnown && dictScore < 8.5f && ws - dictScore >= 4.6f
                if ((weak && ws - dictScore >= MUCH_MORE_COMMON) || accentFix) closeCommon.add(it)
            }
            if (!known && lower.length >= 2) {
                d.corrections(lower, if (aggressive) 2f else 1.4f, near).forEach { (w, dist) ->
                    if (w != lower) add(w, (d.score(w) ?: 0f) - dist * CLOSENESS - penalty, true)
                }
            } else if (weak) {
                d.corrections(lower, 1f, near).forEach { (w, dist) ->
                    val ws = d.score(w) ?: 0f
                    if (w != lower && ws - dictScore >= MUCH_MORE_COMMON) {
                        add(w, ws - dist * CLOSENESS - penalty, true)
                        closeCommon.add(w)
                    }
                }
            }
        }

        val ranked = scores.entries.sortedByDescending { it.value }.map { it.key }
        val auto = when {
            !autocorrect -> null
            FIX[lang]?.containsKey(lower) == true && !userKnown -> FIX[lang]?.get(lower)
            !known -> ranked.firstOrNull { it in corrections }
            closeCommon.isNotEmpty() -> ranked.firstOrNull { it in closeCommon }
            else -> null
        }
        val main = auto ?: typed
        val rest = ranked.filter { !it.equals(main, true) && !it.equals(typed, true) }
        val list = buildList {
            add(main)
            if (auto != null) add(typed)
            addAll(rest)
        }.distinctBy { it.lowercase() }.take(3).map { matchCase(typed, it) }
        return Suggestions(list, auto?.let { matchCase(typed, it) })
    }

    /** How much one typo outweighs word frequency: the closest fix wins unless another is far more common. */
    private const val CLOSENESS = 6f

    /** ln(count) below this means "rare in the word list" (about 1,000 uses). */
    private const val RARE = 6.9f
    /** ln of 300: a neighbour this much more common makes a rare word look like a typo. */
    private const val MUCH_MORE_COMMON = 5.7f

    fun matchCase(typed: String, w: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> w.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> w.replaceFirstChar { it.titlecase() }
        else -> w
    }
}
