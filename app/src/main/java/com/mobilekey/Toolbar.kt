package com.mobilekey

import android.content.SharedPreferences

/** The quick toolbar's buttons: which show, in what order. Stored in preferences. */
object Toolbar {
    class Item(val id: String, val icon: String, val name: String, val feature: String?)

    val all = listOf(
        Item("emoji", "emoji", "Emoji and symbols", "emoji"),
        Item("gif", "gif", "GIFs and stickers", "gifs"),
        Item("clipboard", "clipboard", "Clipboard history", "clipboard"),
        Item("snippets", "snippet", "Snippets", "snippets"),
        Item("edit", "edit", "Editing panel", "edit_toolbar"),
        Item("voice", "voice", "Voice typing", "voice"),
        Item("undo", "undo", "Undo", "undo_redo"),
        Item("redo", "redo", "Redo", "undo_redo"),
        Item("theme", "theme", "Switch theme", "tool_theme"),
        Item("resize", "resize", "Resize keyboard", "tool_resize"),
        Item("lang", "globe", "Languages", null),
        Item("hand", "hand", "One-handed mode", null),
        Item("float", "float", "Floating keyboard", null),
        Item("incognito", "incognito", "Incognito", null),
        Item("settings", "settings", "Settings", null),
    )
    private val byId = all.associateBy { it.id }

    fun order(p: SharedPreferences): List<Item> {
        val saved = (p.getString("toolbar_order", "") ?: "").split(',').mapNotNull { byId[it] }
        return saved + all.filter { it !in saved }
    }

    /** Settings is off the toolbar by default: long-press the globe key instead. */
    fun hidden(p: SharedPreferences): Set<String> = (p.getString("toolbar_hidden", null) ?: "settings").split(',').filter { it.isNotEmpty() }.toSet()

    /** Buttons that actually show: in your order, not hidden, and their feature switched on. */
    fun visible(p: SharedPreferences): List<Item> {
        val h = hidden(p)
        return order(p).filter { it.id !in h && (it.feature == null || Features.isOn(p, it.feature)) }
    }

    fun setOrder(p: SharedPreferences, items: List<Item>) = p.edit().putString("toolbar_order", items.joinToString(",") { it.id }).apply()

    fun setHidden(p: SharedPreferences, id: String, hide: Boolean) {
        val h = hidden(p).toMutableSet()
        if (hide) h.add(id) else h.remove(id)
        p.edit().putString("toolbar_hidden", h.joinToString(",")).apply()
    }
}
