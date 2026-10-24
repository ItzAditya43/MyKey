package com.mobilekey

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Your own long-press options and hints, per layout and key, layered over the built-in layouts.
 * Stored as JSON in preferences: { layout: { key: { "long": [...], "hint": "x" } } }.
 */
object LongPress {
    private fun all(p: SharedPreferences): JSONObject =
        try { JSONObject(p.getString("long_overrides", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun save(p: SharedPreferences, o: JSONObject) = p.edit().putString("long_overrides", o.toString()).apply()

    fun has(p: SharedPreferences, layout: String, key: String) = all(p).optJSONObject(layout)?.has(key) == true

    fun set(p: SharedPreferences, layout: String, key: String, long: List<String>, hint: String?) {
        val o = all(p)
        val l = o.optJSONObject(layout) ?: JSONObject().also { o.put(layout, it) }
        l.put(key, JSONObject().put("long", JSONArray(long)).apply { if (hint != null) put("hint", hint) })
        save(p, o)
    }

    fun reset(p: SharedPreferences, layout: String, key: String?) {
        val o = all(p)
        if (key == null) o.remove(layout) else o.optJSONObject(layout)?.remove(key)
        save(p, o)
    }

    /** Applies your edits to a freshly loaded layout. */
    fun apply(p: SharedPreferences, layout: String, l: KbLayout): KbLayout {
        val edits = all(p).optJSONObject(layout) ?: return l
        return l.copy(rows = l.rows.map { row ->
            row.map { k ->
                val e = if (k.type == null) edits.optJSONObject(k.out) else null
                if (e == null) k else {
                    val arr = e.optJSONArray("long")
                    val long = if (arr == null) k.long else (0 until arr.length()).map { arr.getString(it) }
                    k.copy(long = long, hint = if (e.has("hint")) e.getString("hint") else null)
                }
            }
        })
    }
}
