package com.mobilekey

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("mobilekey", Context.MODE_PRIVATE)
}

/** One user-facing switch. `ready = false` means it is planned and shown greyed out in settings. */
class Feature(
    val id: String,
    val category: String,
    val title: String,
    val desc: String,
    val ready: Boolean,
    val default: Boolean = true,
)

object Features {
    val all = listOf(
        Feature("key_preview", "Typing", "Key popup preview", "Enlarged letter above your finger.", true),
        Feature("long_press", "Typing", "Long-press for accents and symbols", "Hold a key, slide to the option, let go.", true),
        Feature("glide", "Typing", "Glide typing", "Slide across the letters of a word without lifting.", true),
        Feature("glide_trail", "Typing", "Show glide trail", "Draws your finger's path while gliding.", true),
        Feature("space_cursor", "Typing", "Space bar cursor control", "Slide along the space bar to move the cursor.", true),
        Feature("backspace_swipe", "Typing", "Swipe left on backspace to delete words", "Further left deletes more words.", true),
        Feature("backspace_accel", "Typing", "Backspace repeat speeds up", "Holding backspace deletes faster over time.", true),
        Feature("number_row", "Typing", "Number row", "When off, long-press the top row for digits.", true),
        Feature("hints", "Typing", "Hint characters on keys", "Small symbol showing the long-press option.", true),
        Feature("field_aware", "Typing", "Keyboard matches the field", "Number pad for numbers, @ key for email addresses.", true),
        Feature("enter_action", "Typing", "Enter key shows the action", "Send, Search, Go, Next or Done.", true),
        Feature("split", "Typing", "Split layout", "A gap down the middle for two thumbs. Best in landscape.", true, default = false),
        Feature("split_landscape", "Typing", "Split automatically in landscape", "", true),
        Feature("undo_redo", "Typing", "Undo and redo", "Buttons in the toolbar and editing panel.", true),
        Feature("swipe_gestures", "Typing", "Swipe gestures", "Swipe up on a key for its symbol, up on space to switch language, down on space to hide.", true),
        Feature("adaptive_keys", "Typing", "Keys learn your aim", "Key edges shift slightly toward where your thumbs actually land.", true),
        Feature("emoji_key", "Typing", "Emoji key on the keyboard", "Replaces the comma key. Comma moves to the period key's long-press.", true, default = false),
        Feature("trackpad", "Typing", "Space bar trackpad", "Long-press space, then slide in any direction to move the cursor, even between lines.", true),
        Feature("case_cycle", "Typing", "Shift changes the word's case", "Tap shift while typing a word, or with text selected, to cycle Word / WORD / word.", true),
        Feature("undo_delete", "Typing", "Restore deleted words", "After swiping backspace, a Restore chip brings the words back.", true),

        Feature("multilingual", "Languages", "Type English and Spanish together", "Suggestions from both without switching.", true),
        Feature("lang_space_long", "Languages", "Long-press space to switch language", "With one language, opens the keyboard picker.", true),

        Feature("learn_words", "Learning", "Learn new words", "Stored on this phone only.", true),
        Feature("next_word", "Learning", "Next-word prediction", "Suggests what you usually type next.", true),
        Feature("per_app", "Learning", "Per-app habits", "Each app also keeps its own next-word habits.", true),
        Feature("forget_long_press", "Learning", "Long-press a suggestion to forget it", "It is never suggested again.", true),
        Feature("incognito", "Learning", "Incognito mode", "Nothing is learned while this is on.", true, default = false),
        Feature("stats", "Learning", "Typing stats", "Counts words, fixes and speed. On this phone only.", true),

        Feature("autocorrect", "Correction", "Autocorrect", "Fixes typos when you press space.", true),
        Feature("ac_aggressive", "Correction", "Aggressive autocorrect", "Fixes bigger typos, but guesses wrong more often.", true, default = false),
        Feature("proximity", "Correction", "Key-proximity correction", "Neighbouring-key mistakes count as small typos.", true),
        Feature("undo_autocorrect", "Correction", "Undo autocorrect with backspace", "Backspace right after a fix restores your word.", true),
        Feature("auto_cap", "Correction", "Auto-capitalise", "Capital letter at the start of sentences.", true),
        Feature("double_space", "Correction", "Double-space for period", "", true),
        Feature("smart_punct", "Correction", "Smart punctuation", "Removes the space before . , ! ? and uses curly quotes.", true),
        Feature("shortcuts", "Correction", "Text shortcuts", "Your own expansions, like omw → on my way!", true),
        Feature("emoji_suggest", "Correction", "Emoji suggestions", "Type “pizza” or “gato” and the emoji appears in the bar.", true),
        Feature("calculator", "Correction", "Calculator", "Type 12*4.5= and the answer appears in the bar.", true),
        Feature("units", "Correction", "Unit conversion", "Type 5 km or 30°C and conversions appear in the bar.", true),
        Feature("date_chips", "Correction", "Date suggestions", "Type today, tomorrow or yesterday (also hoy, mañana, ayer) to insert the date.", true),
        Feature("auto_space", "Correction", "Space after punctuation", "Adds a space after . , ! ? : ; automatically (not inside numbers).", true, default = false),
        Feature("offensive_filter", "Correction", "Block offensive words", "Never suggests or autocorrects to swear words. You can still type them.", true),

        Feature("custom_layouts", "Look", "Use edited layouts", "Off restores the built-in layouts without deleting your edits.", true),
        Feature("haptics", "Look", "Vibrate on key press", "", true),
        Feature("sounds", "Look", "Key sounds", "", true, default = false),
        Feature("sounds_silent", "Look", "Key sounds in silent and vibrate mode", "Plays through media volume, so you hear keys even when the phone is on vibrate.", true, default = false),
        Feature("key_shadow", "Look", "Key shadows", "A subtle edge under each key.", true, default = false),
        Feature("bold_labels", "Look", "Bold letters", "", true, default = false),
        Feature("auto_theme", "Look", "Day and night themes", "Uses your night theme when the phone is in dark mode.", true, default = false),
        Feature("per_app_theme", "Look", "App profiles", "Different theme, size, split or position for specific apps. Great for games.", true, default = false),

        Feature("quick_toolbar", "Tools", "Quick toolbar", "Emoji, clipboard, editing, voice and more above the keys.", true),
        Feature("clipboard", "Tools", "Clipboard history", "Keeps what you copy. Pin items to keep them.", true),
        Feature("clip_autoclear", "Tools", "Clear clipboard history after 1 hour", "Pinned items stay.", true),
        Feature("paste_chip", "Tools", "Paste suggestion", "Right after you copy, a Paste chip appears. Codes get their own chip.", true),
        Feature("snippets", "Tools", "Saved snippets", "Longer texts (addresses, templates) in their own panel.", true),
        Feature("gifs", "Tools", "Your GIFs and stickers", "GIFs you add in settings, sent as real GIFs in apps that support it.", true),
        Feature("gif_search", "Tools", "Online GIF search", "Off by default. MyKey goes online only when you open GIFs or search them.", true, default = false),
        Feature("autofill_chips", "Tools", "Password and address suggestions", "Saved logins and addresses from your password manager appear in the bar.", true),
        Feature("sel_chips", "Tools", "Selected-text actions", "With text selected, the bar offers copy, cut, paste, select all and case.", true),
        Feature("email_chips", "Tools", "Your email addresses", "Email fields offer addresses you've entered before.", true),
        Feature("pass_gen", "Tools", "Password generator", "Password fields offer a strong random password, made on the phone.", true),
        Feature("tool_theme", "Tools", "Theme switch button", "A toolbar button that cycles your favourite themes.", true),
        Feature("tool_resize", "Tools", "Resize button", "A toolbar button to drag the keyboard taller or shorter.", true),
        Feature("emoji", "Tools", "Emoji and kaomoji panel", "With search and recents.", true),
        Feature("voice", "Tools", "Voice typing", "Hands off to your phone's voice keyboard and comes back after.", true),
        Feature("edit_toolbar", "Tools", "Editing panel", "Arrows, select, copy, cut, paste.", true),

        Feature("password_detect", "Privacy", "Private in password fields", "No suggestions or learning in password fields.", true),
        Feature("lock_private", "Privacy", "Private on the lock screen", "Nothing is learned while the phone is locked.", true),
        Feature("spellcheck_words", "Privacy", "System spell check uses your words", "When MyKey is chosen as the spell checker in system settings.", true),
        Feature("accessibility", "Privacy", "Screen reader typing", "With TalkBack: touch to hear a key, lift to type it.", true),
    )

    private val byId = all.associateBy { it.id }

    fun isOn(p: SharedPreferences, id: String): Boolean {
        val f = byId[id] ?: return false
        return f.ready && p.getBoolean("f_$id", f.default)
    }
}

object Langs {
    val supported = listOf("en", "es", "ru", "ja", "zh")
    val names = mapOf("en" to "English", "es" to "Español", "ru" to "Русский", "ja" to "日本語", "zh" to "中文")
    val latin = setOf("en", "es")
    /** Languages typed through a reading + conversion step instead of word suggestions. */
    val cjk = setOf("ja", "zh")
    val voiceTags = mapOf("en" to "en-US", "es" to "es-ES", "ru" to "ru-RU", "ja" to "ja-JP", "zh" to "zh-CN")
    val englishLayouts = listOf(
        "en_qwerty" to "QWERTY", "en_qwertz" to "QWERTZ", "en_azerty" to "AZERTY",
        "en_dvorak" to "Dvorak", "en_colemak" to "Colemak", "en_morse" to "Morse code",
    )

    fun enabled(p: SharedPreferences): List<String> =
        (p.getString("langs", "en") ?: "en").split(',').filter { it in supported }.ifEmpty { listOf("en") }

    fun layoutFor(lang: String, p: SharedPreferences): String = when (lang) {
        "en" -> p.getString("en_layout", "en_qwerty") ?: "en_qwerty"
        "ja" -> if (p.getString("ja_mode", "flick") == "romaji") "ja_romaji" else "ja_flick"
        "zh" -> "zh_pinyin"
        else -> lang
    }
}
