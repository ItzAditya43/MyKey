package com.mobilekey

import android.content.Context
import com.mobilekey.engine.UserModel
import org.json.JSONObject

/** Export and restore: settings, learned data and edited layouts in one JSON file. */
object Backup {
    fun export(ctx: Context): JSONObject {
        val prefs = JSONObject()
        Prefs.get(ctx).all.forEach { (k, v) ->
            val type = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> return@forEach }
            if (k == "bg_image") return@forEach
            prefs.put(k, JSONObject().put("t", type).put("v", v))
        }
        val layouts = JSONObject()
        Layouts.editable.forEach { n ->
            val f = Layouts.overrideFile(ctx, n)
            if (f.exists()) layouts.put(n, f.readText())
        }
        return JSONObject()
            .put("app", "MyKey")
            .put("version", 1)
            .put("prefs", prefs)
            .put("user", UserModel.get(ctx).export())
            .put("layouts", layouts)
    }

    fun import(ctx: Context, o: JSONObject) {
        require(o.optString("app") == "MyKey") { "this isn't a MyKey backup" }
        val e = Prefs.get(ctx).edit()
        o.optJSONObject("prefs")?.let { p ->
            p.keys().forEach { k ->
                val x = p.getJSONObject(k)
                when (x.getString("t")) {
                    "b" -> e.putBoolean(k, x.getBoolean("v"))
                    "i" -> e.putInt(k, x.getInt("v"))
                    "l" -> e.putLong(k, x.getLong("v"))
                    "f" -> e.putFloat(k, x.getDouble("v").toFloat())
                    "s" -> e.putString(k, x.getString("v"))
                }
            }
        }
        e.apply()
        o.optJSONObject("user")?.let { UserModel.get(ctx).import(it) }
        o.optJSONObject("layouts")?.let { l -> l.keys().forEach { n -> runCatching { Layouts.save(ctx, n, l.getString(n)) } } }
    }
}
