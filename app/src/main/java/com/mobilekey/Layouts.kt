package com.mobilekey

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Key(
    val label: String,
    val out: String,
    val type: String?,
    val w: Float,
    val long: List<String>,
    /** Japanese flick key: centre, left, up, right, down. */
    val flick: List<String> = emptyList(),
    /** Small hint shown on the key; defaults to the first long-press option. */
    val hint: String? = null,
)

data class KbLayout(val name: String, val rows: List<List<Key>>)

/**
 * Layout files are JSON (see README). Built-ins live in assets/layouts; an edited copy
 * saved from Settings lives in filesDir/layouts and takes priority.
 */
object Layouts {
    val TYPES = setOf(
        "shift", "backspace", "space", "enter", "symbols", "lang",
        "emoji", "clipboard", "cursor_left", "cursor_right", "kana_mod", "voice", "settings", "morse_dot", "morse_dash",
    )
    val editable = listOf(
        "en_qwerty", "en_qwertz", "en_azerty", "en_dvorak", "en_colemak",
        "en_morse", "es", "ru", "ja_flick", "ja_romaji", "zh_pinyin", "symbols", "numpad",
    )

    fun parse(json: String): KbLayout {
        val o = JSONObject(json)
        val long = o.optJSONObject("long")
        val hints = o.optJSONObject("hints")
        val rowsArr = o.optJSONArray("rows") ?: throw IllegalArgumentException("\"rows\" is missing.")
        if (rowsArr.length() == 0) throw IllegalArgumentException("\"rows\" is empty.")
        val rows = (0 until rowsArr.length()).map { r ->
            val row = rowsArr.optJSONArray(r)
                ?: throw IllegalArgumentException("Row ${r + 1} must be a list of keys.")
            (0 until row.length()).map { i -> key(row.get(i), long, r + 1, i + 1).let { k -> hints?.optString(k.out)?.takeIf { it.isNotEmpty() }?.let { h -> k.copy(hint = h) } ?: k } }
        }
        return KbLayout(o.optString("name", "Custom"), rows)
    }

    private fun key(v: Any, long: JSONObject?, r: Int, i: Int): Key = when (v) {
        is String -> {
            if (v.isEmpty()) throw IllegalArgumentException("Row $r, key $i is an empty string.")
            Key(v, v, null, 1f, split(long?.opt(v)))
        }
        is JSONObject -> {
            val type = v.optString("type").ifEmpty { null }
            if (type != null && type !in TYPES) {
                throw IllegalArgumentException("Row $r, key $i: unknown type \"$type\".")
            }
            val out = v.optString("out", v.optString("label", ""))
            if (type == null && out.isEmpty()) {
                throw IllegalArgumentException("Row $r, key $i needs \"out\", \"label\" or \"type\".")
            }
            Key(
                label = v.optString("label", if (type == null) out else ""),
                out = out,
                type = type,
                w = v.optDouble("w", defaultWidth(type).toDouble()).toFloat(),
                long = split(v.opt("long") ?: long?.opt(out)),
                flick = split(v.opt("flick")),
            )
        }
        else -> throw IllegalArgumentException("Row $r, key $i must be a string or an object.")
    }

    private fun defaultWidth(type: String?) = when (type) {
        "shift", "backspace" -> 1.5f
        "space" -> 4f
        "enter" -> 1.6f
        "symbols" -> 1.4f
        else -> 1f
    }

    private fun split(x: Any?): List<String> = when (x) {
        is String -> x.codePoints().toArray().map { String(Character.toChars(it)) }
        is JSONArray -> (0 until x.length()).map { x.optString(it) }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    fun assetText(ctx: Context, name: String): String =
        ctx.assets.open("layouts/$name.json").bufferedReader().use { it.readText() }

    fun overrideFile(ctx: Context, name: String) = File(File(ctx.filesDir, "layouts"), "$name.json")

    fun text(ctx: Context, name: String, allowOverride: Boolean = true): String {
        val f = overrideFile(ctx, name)
        return if (allowOverride && f.exists()) f.readText() else assetText(ctx, name)
    }

    fun load(ctx: Context, name: String, allowOverride: Boolean): KbLayout =
        try {
            parse(text(ctx, name, allowOverride))
        } catch (e: Exception) {
            parse(assetText(ctx, name))
        }

    fun save(ctx: Context, name: String, json: String) {
        parse(json)
        val f = overrideFile(ctx, name)
        f.parentFile?.mkdirs()
        f.writeText(json)
    }

    fun reset(ctx: Context, name: String) {
        overrideFile(ctx, name).delete()
    }

    fun withNumberRow(l: KbLayout): KbLayout {
        val first = l.rows.firstOrNull() ?: return l
        if (first.all { it.type == null && it.out.length == 1 && it.out[0].isDigit() }) return l
        val digits = "1234567890".map { Key(it.toString(), it.toString(), null, 1f, emptyList()) }
        return l.copy(rows = listOf(digits) + l.rows)
    }

    /** Without a number row, long-pressing the first ten top-row keys gives 1–0. */
    fun withDigitLongPress(l: KbLayout): KbLayout {
        val first = l.rows.firstOrNull() ?: return l
        if (first.any { it.out.length == 1 && it.out[0].isDigit() }) return l
        val row = first.mapIndexed { i, k ->
            if (i < 10 && k.type == null) k.copy(long = listOf(((i + 1) % 10).toString()) + k.long) else k
        }
        return l.copy(rows = listOf(row) + l.rows.drop(1))
    }

    /** Emoji key instead of comma; comma becomes the period key's first long-press option. */
    fun withEmojiKey(l: KbLayout): KbLayout {
        val last = l.rows.lastOrNull() ?: return l
        if (last.none { it.type == null && it.out == "," }) return l
        val row = last.map { k ->
            when {
                k.type == null && k.out == "," -> Key("", "", "emoji", k.w, emptyList())
                k.type == null && k.out == "." -> k.copy(long = listOf(",") + k.long.filter { it != "," })
                else -> k
            }
        }
        return l.copy(rows = l.rows.dropLast(1) + listOf(row))
    }

    /** Email and web-address fields: comma becomes @, period offers .com and friends. */
    fun forEmail(l: KbLayout): KbLayout {
        val last = l.rows.lastOrNull() ?: return l
        val row = last.map { k ->
            when {
                k.type == null && k.out == "," -> Key("@", "@", null, k.w, listOf(",", "_", "-"))
                k.type == null && k.out == "." -> k.copy(long = listOf(".com", ".net", ".org", "/") + k.long)
                else -> k
            }
        }
        return l.copy(rows = l.rows.dropLast(1) + listOf(row))
    }
}
