package com.mobilekey

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class KbTheme(
    val name: String,
    val bg: Int,
    val key: Int,
    val mod: Int,
    val fg: Int,
    val acc: Int,
    val accFg: Int,
    val border: Int = mix(key, fg, 0.16f),
    val amoled: Boolean = false,
    /** Colour for special-key icons and labels (shift, backspace, ?123…). Defaults to the letter colour. */
    val modFg: Int = fg,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("bg", hex(bg)); put("key", hex(key)); put("mod", hex(mod)); put("fg", hex(fg))
        put("acc", hex(acc)); put("accFg", hex(accFg)); put("border", hex(border))
        if (amoled) put("amoled", true)
        if (modFg != fg) put("modFg", hex(modFg))
    }

    companion object {
        fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

        fun mix(a: Int, b: Int, f: Float): Int {
            fun ch(x: Int, s: Int) = (x shr s) and 0xff
            fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * f).toInt()
            return (0xff shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
        }

        fun fromJson(o: JSONObject): KbTheme {
            fun c(k: String) = Color.parseColor(o.getString(k))
            val key = c("key")
            val fg = c("fg")
            return KbTheme(
                o.optString("name", "Custom"), c("bg"), key, c("mod"), fg, c("acc"), c("accFg"),
                if (o.has("border")) c("border") else mix(key, fg, 0.16f),
                o.optBoolean("amoled", false),
                if (o.has("modFg")) c("modFg") else fg,
            )
        }
    }
}

object Themes {
    fun all(ctx: Context): List<KbTheme> {
        val list = ArrayList<KbTheme>()
        custom(ctx)?.let { list.add(it) }
        if (Build.VERSION.SDK_INT >= 31) list.add(dynamic(ctx))
        val arr = JSONArray(ctx.assets.open("themes.json").bufferedReader().use { it.readText() })
        for (i in 0 until arr.length()) list.add(KbTheme.fromJson(arr.getJSONObject(i)))
        return list
    }

    fun byName(ctx: Context, name: String?): KbTheme {
        val all = all(ctx)
        return all.firstOrNull { it.name == name } ?: all.first { it.name == "Graphite" }
    }

    /** Theme made in the theme editor, stored as JSON in preferences. */
    fun custom(ctx: Context): KbTheme? = Prefs.get(ctx).getString("custom_theme", null)?.let {
        try { KbTheme.fromJson(JSONObject(it)).copy(name = "Custom") } catch (e: Exception) { null }
    }

    fun saveCustom(ctx: Context, t: KbTheme) {
        Prefs.get(ctx).edit()
            .putString("custom_theme", t.copy(name = "Custom").toJson().toString())
            .putString("theme", "Custom")
            .apply()
    }

    /** Material You colours taken from the wallpaper (Android 12+). */
    private fun dynamic(ctx: Context): KbTheme {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        fun c(id: Int) = ctx.getColor(id)
        return if (night) KbTheme(
            "Wallpaper colours",
            c(android.R.color.system_neutral1_900), c(android.R.color.system_neutral2_700),
            c(android.R.color.system_neutral2_800), c(android.R.color.system_neutral1_50),
            c(android.R.color.system_accent1_200), c(android.R.color.system_accent1_800),
        ) else KbTheme(
            "Wallpaper colours",
            c(android.R.color.system_neutral2_100), c(android.R.color.system_neutral1_10),
            c(android.R.color.system_neutral2_200), c(android.R.color.system_neutral1_900),
            c(android.R.color.system_accent1_600), c(android.R.color.system_accent1_0),
        )
    }
}
