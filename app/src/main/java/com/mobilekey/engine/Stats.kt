package com.mobilekey.engine

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/** Typing counters. Kept in memory, written to disk every few events. */
class Stats private constructor(private val p: SharedPreferences) {
    private val counts = HashMap<String, Long>()
    private var dirty = 0
    private var lastKey = 0L
    /** Off when the Stats switch is off: nothing is counted. */
    var enabled = true

    init {
        KEYS.forEach { counts[it] = p.getLong(it, 0) }
    }

    fun add(key: String, n: Long = 1) {
        if (!enabled) return
        counts[key] = (counts[key] ?: 0) + n
        if (++dirty >= 25) flush()
    }

    /** Call on every key press; gaps under 2.5 s count as active typing time. */
    fun keyPress() {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        val gap = now - lastKey
        if (lastKey != 0L && gap < 2500) add("active_ms", gap)
        lastKey = now
        add("keys")
    }

    fun get(key: String) = counts[key] ?: 0

    fun wpm(): Int {
        val min = get("active_ms") / 60000.0
        return if (min < 0.2) 0 else (get("words") / min).toInt()
    }

    fun flush() {
        if (dirty == 0) return
        val e = p.edit()
        counts.forEach { (k, v) -> e.putLong(k, v) }
        e.apply()
        dirty = 0
    }

    fun reset() {
        KEYS.forEach { counts[it] = 0 }
        p.edit().clear().apply()
    }

    companion object {
        val KEYS = listOf("keys", "words", "corrections", "undos", "glides", "emoji", "pastes", "voice", "active_ms")
        @Volatile private var instance: Stats? = null
        fun get(ctx: Context): Stats = instance ?: synchronized(this) {
            instance ?: Stats(ctx.applicationContext.getSharedPreferences("stats", Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
