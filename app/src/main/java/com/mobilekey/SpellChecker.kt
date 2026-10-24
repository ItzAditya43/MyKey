package com.mobilekey

import android.service.textservice.SpellCheckerService
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import com.mobilekey.engine.Dictionary
import com.mobilekey.engine.Suggest
import com.mobilekey.engine.UserModel
import java.util.concurrent.ConcurrentHashMap

/**
 * System spell checker: underlines typos in any app using MyKey's word lists and your learned words.
 * Turn it on in system settings → Languages & input → Spell checker → MyKey.
 */
class SpellChecker : SpellCheckerService() {
    private val dicts = ConcurrentHashMap<String, Dictionary>()

    override fun createSession(): Session = object : Session() {
        private var lang = "en"

        override fun onCreate() {
            val l = locale?.take(2) ?: "en"
            lang = if (l in setOf("en", "es", "ru")) l else "en"
        }

        private fun dict(): Dictionary? = dicts[lang] ?: try {
            Dictionary.load(this@SpellChecker, lang).also { dicts[lang] = it }
        } catch (e: Exception) { null }

        override fun onGetSuggestions(info: TextInfo, limit: Int): SuggestionsInfo {
            val word = info.text.trim()
            val d = dict()
            if (d == null || word.isEmpty() || word.any { !it.isLetter() && it != '\'' }) {
                return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, emptyArray())
            }
            val prefs = Prefs.get(this@SpellChecker)
            val user = UserModel.get(this@SpellChecker)
            val lower = word.lowercase()
            val known = d.contains(lower) || (Features.isOn(prefs, "spellcheck_words") && user.knows(lang, lower))
            if (known) return SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, emptyArray())
            val s = Suggest.run(word, lang, listOf(d), user, { _, _ -> false }, autocorrect = true, aggressive = true)
            val list = s.list.filter { !it.equals(word, true) }.take(limit.coerceAtLeast(1))
            return SuggestionsInfo(
                SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or (if (list.isNotEmpty()) SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS else 0),
                list.toTypedArray(),
            )
        }

        override fun onGetSentenceSuggestionsMultiple(infos: Array<out TextInfo>, limit: Int): Array<SentenceSuggestionsInfo> =
            infos.map { ti ->
                val text = ti.text
                val words = Regex("[\\p{L}']+").findAll(text).toList()
                val results = words.map { m -> onGetSuggestions(TextInfo(m.value), limit) }
                SentenceSuggestionsInfo(results.toTypedArray(), words.map { it.range.first }.toIntArray(), words.map { it.value.length }.toIntArray())
            }.toTypedArray()
    }
}
