package com.mobilekey.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Everything the keyboard learns about you, in one on-device SQLite file.
 * Reads come from in-memory caches; writes go to the database on a background thread.
 * Shared by the keyboard and the settings screen (same process).
 */
class UserModel private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "user.db", null, 2) {

    class Word(val word: String, var count: Int)
    class Clip(val id: Long, val text: String, var pinned: Boolean, val time: Long)

    private val words = HashMap<String, HashMap<String, Word>>()
    private val shortcuts = LinkedHashMap<String, String>()
    private val blocked = HashSet<String>()
    private val conv = HashMap<String, LinkedHashMap<String, Int>>()
    private val clips = ArrayList<Clip>()
    private val io = Executors.newSingleThreadExecutor()

    init {
        val db = readableDatabase
        db.rawQuery("SELECT lang, k, word, count FROM words", null).use { c ->
            while (c.moveToNext()) {
                words.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)] = Word(c.getString(2), c.getInt(3))
            }
        }
        db.rawQuery("SELECT k, v FROM shortcuts", null).use { c ->
            while (c.moveToNext()) shortcuts[c.getString(0)] = c.getString(1)
        }
        if (shortcuts.isEmpty() && db.rawQuery("SELECT 1 FROM meta WHERE k='seeded'", null).use { it.count == 0 }) {
            putShortcut("omw", "on my way!")
            putShortcut("brb", "be right back")
            io.execute { writableDatabase.execSQL("INSERT OR IGNORE INTO meta VALUES('seeded','1')") }
        }
        db.rawQuery("SELECT word FROM blocked", null).use { c ->
            while (c.moveToNext()) blocked.add(c.getString(0))
        }
        db.rawQuery("SELECT lang, reading, text, count FROM conv ORDER BY count DESC", null).use { c ->
            while (c.moveToNext()) conv.getOrPut(c.getString(0) + "\t" + c.getString(1)) { LinkedHashMap() }[c.getString(2)] = c.getInt(3)
        }
        db.rawQuery("SELECT id, text, pinned, time FROM clips ORDER BY time DESC", null).use { c ->
            while (c.moveToNext()) clips.add(Clip(c.getLong(0), c.getString(1), c.getInt(2) == 1, c.getLong(3)))
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE words(lang TEXT, k TEXT, word TEXT, count INTEGER, PRIMARY KEY(lang, k))")
        db.execSQL("CREATE TABLE bigrams(lang TEXT, prev TEXT, word TEXT, count INTEGER, PRIMARY KEY(lang, prev, word))")
        db.execSQL("CREATE TABLE shortcuts(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE blocked(word TEXT PRIMARY KEY)")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)")
        onUpgrade(db, 1, 2)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS conv(lang TEXT, reading TEXT, text TEXT, count INTEGER, PRIMARY KEY(lang, reading, text))")
            db.execSQL("CREATE TABLE IF NOT EXISTS clips(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, pinned INTEGER, time INTEGER)")
        }
    }

    // ---------- words ----------

    /** A word counts as known once used twice, so a single typo isn't learned. */
    fun knows(lang: String, lower: String) = (words[lang]?.get(lower)?.count ?: 0) >= 2

    fun prefix(lang: String, lower: String): List<Pair<String, Int>> =
        words[lang]?.values
            ?.filter { it.word.lowercase().startsWith(lower) && !isBlocked(it.word) }
            ?.sortedByDescending { it.count }
            ?.take(3)
            ?.map { it.word to it.count }
            ?: emptyList()

    fun learn(lang: String, word: String, by: Int = 1) {
        if (word.length < 2 || word.contains(' ')) return
        val k = word.lowercase()
        if (k in blocked) return
        val map = words.getOrPut(lang) { HashMap() }
        val e = map[k]
        if (e == null) map[k] = Word(word, by) else e.count += by
        io.execute {
            val db = writableDatabase
            db.execSQL("INSERT OR IGNORE INTO words VALUES(?,?,?,0)", arrayOf(lang, k, word))
            db.execSQL("UPDATE words SET count = count + ? WHERE lang=? AND k=?", arrayOf(by, lang, k))
        }
    }

    /** [app] adds a per-app copy so each app can keep its own habits. */
    fun learnPair(lang: String, prev: String, word: String, app: String? = null) {
        if (word.contains(' ') || prev.isEmpty()) return
        val keys = listOfNotNull(lang, app?.let { "$lang|$it" })
        io.execute {
            val db = writableDatabase
            for (l in keys) {
                db.execSQL("INSERT OR IGNORE INTO bigrams VALUES(?,?,?,0)", arrayOf(l, prev, word))
                db.execSQL("UPDATE bigrams SET count = count + 1 WHERE lang=? AND prev=? AND word=?", arrayOf(l, prev, word))
            }
        }
    }

    fun next(lang: String, prev: String, n: Int, app: String? = null): List<String> {
        fun q(l: String) = readableDatabase.rawQuery(
            "SELECT word FROM bigrams WHERE lang=? AND prev=? ORDER BY count DESC LIMIT ?",
            arrayOf(l, prev, (n + 3).toString()),
        ).use { c -> ArrayList<String>().also { while (c.moveToNext()) it.add(c.getString(0)) } }
        val merged = (if (app != null) q("$lang|$app") else emptyList()) + q(lang)
        return merged.distinct().filter { !isBlocked(it) }.take(n)
    }

    fun isBlocked(w: String) = w.lowercase() in blocked

    fun forget(lang: String, word: String) {
        val k = word.lowercase()
        words[lang]?.remove(k)
        blocked.add(k)
        io.execute {
            val db = writableDatabase
            db.delete("words", "lang=? AND k=?", arrayOf(lang, k))
            db.delete("bigrams", "word=?", arrayOf(word))
            db.execSQL("INSERT OR IGNORE INTO blocked VALUES(?)", arrayOf(k))
        }
    }

    fun unblock(word: String) {
        blocked.remove(word)
        io.execute { writableDatabase.delete("blocked", "word=?", arrayOf(word)) }
    }

    fun blockedWords(): List<String> = blocked.sorted()

    /** Words added by hand start as known. */
    fun add(lang: String, word: String) {
        unblock(word.lowercase())
        learn(lang, word, 2)
    }

    fun remove(lang: String, word: String) {
        val k = word.lowercase()
        words[lang]?.remove(k)
        io.execute { writableDatabase.delete("words", "lang=? AND k=?", arrayOf(lang, k)) }
    }

    fun allWords(lang: String): List<Word> = words[lang]?.values?.sortedByDescending { it.count } ?: emptyList()

    // ---------- kanji / hanzi choices ----------

    fun learnConv(lang: String, reading: String, text: String) {
        val m = conv.getOrPut("$lang\t$reading") { LinkedHashMap() }
        m[text] = (m[text] ?: 0) + 1
        io.execute {
            val db = writableDatabase
            db.execSQL("INSERT OR IGNORE INTO conv VALUES(?,?,?,0)", arrayOf(lang, reading, text))
            db.execSQL("UPDATE conv SET count = count + 1 WHERE lang=? AND reading=? AND text=?", arrayOf(lang, reading, text))
        }
    }

    fun convLearned(lang: String, reading: String): List<String> =
        conv["$lang\t$reading"]?.entries?.sortedByDescending { it.value }?.map { it.key } ?: emptyList()

    // ---------- shortcuts ----------

    fun shortcut(k: String): String? = shortcuts[k]

    fun allShortcuts(): Map<String, String> = shortcuts

    fun putShortcut(k: String, v: String) {
        shortcuts[k] = v
        io.execute {
            writableDatabase.insertWithOnConflict(
                "shortcuts", null,
                ContentValues().apply { put("k", k); put("v", v) },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    fun removeShortcut(k: String) {
        shortcuts.remove(k)
        io.execute { writableDatabase.delete("shortcuts", "k=?", arrayOf(k)) }
    }

    // ---------- clipboard ----------

    fun clips(): List<Clip> = clips.sortedWith(compareByDescending<Clip> { it.pinned }.thenByDescending { it.time })

    fun addClip(text: String) {
        if (text.isBlank()) return
        val existing = clips.firstOrNull { it.text == text }
        if (existing != null) {
            clips.remove(existing)
            io.execute { writableDatabase.delete("clips", "id=?", arrayOf(existing.id.toString())) }
        }
        val now = System.currentTimeMillis()
        val pinned = existing?.pinned ?: false
        val id = writableDatabase.insert("clips", null, ContentValues().apply {
            put("text", text); put("pinned", if (pinned) 1 else 0); put("time", now)
        })
        clips.add(Clip(id, text, pinned, now))
        val unpinned = clips.filter { !it.pinned }.sortedByDescending { it.time }
        unpinned.drop(40).forEach { deleteClip(it.id) }
    }

    fun pinClip(id: Long, pinned: Boolean) {
        clips.firstOrNull { it.id == id }?.pinned = pinned
        io.execute { writableDatabase.execSQL("UPDATE clips SET pinned=? WHERE id=?", arrayOf(if (pinned) 1 else 0, id)) }
    }

    fun deleteClip(id: Long) {
        clips.removeAll { it.id == id }
        io.execute { writableDatabase.delete("clips", "id=?", arrayOf(id.toString())) }
    }

    fun clearClips(olderThanMs: Long = 0) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        clips.filter { !it.pinned && it.time <= cutoff }.forEach { deleteClip(it.id) }
    }

    // ---------- bulk ----------

    fun clearLearned() {
        words.clear()
        conv.clear()
        io.execute {
            writableDatabase.delete("words", null, null)
            writableDatabase.delete("bigrams", null, null)
            writableDatabase.delete("conv", null, null)
        }
    }

    fun export(): JSONObject {
        val o = JSONObject()
        o.put("words", JSONArray().apply {
            words.forEach { (lang, m) -> m.values.forEach { put(JSONArray(listOf(lang, it.word, it.count))) } }
        })
        o.put("bigrams", JSONArray().apply {
            readableDatabase.rawQuery("SELECT lang, prev, word, count FROM bigrams ORDER BY count DESC LIMIT 20000", null).use { c ->
                while (c.moveToNext()) put(JSONArray(listOf(c.getString(0), c.getString(1), c.getString(2), c.getInt(3))))
            }
        })
        o.put("conv", JSONArray().apply {
            conv.forEach { (k, m) -> m.forEach { (t, n) -> put(JSONArray(listOf(k, t, n))) } }
        })
        o.put("shortcuts", JSONObject(shortcuts as Map<*, *>))
        o.put("blocked", JSONArray(blocked.toList()))
        o.put("clips", JSONArray().apply { clips.filter { it.pinned }.forEach { put(it.text) } })
        return o
    }

    fun import(o: JSONObject) {
        o.optJSONArray("words")?.let { a ->
            for (i in 0 until a.length()) a.getJSONArray(i).let { learn(it.getString(0), it.getString(1), it.getInt(2)) }
        }
        o.optJSONArray("bigrams")?.let { a ->
            io.execute {
                val db = writableDatabase
                for (i in 0 until a.length()) {
                    val r = a.getJSONArray(i)
                    db.execSQL("INSERT OR IGNORE INTO bigrams VALUES(?,?,?,0)", arrayOf(r.getString(0), r.getString(1), r.getString(2)))
                    db.execSQL("UPDATE bigrams SET count = count + ? WHERE lang=? AND prev=? AND word=?", arrayOf(r.getInt(3), r.getString(0), r.getString(1), r.getString(2)))
                }
            }
        }
        o.optJSONArray("conv")?.let { a ->
            for (i in 0 until a.length()) {
                val r = a.getJSONArray(i)
                val (lang, reading) = r.getString(0).split("\t", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                repeat(r.getInt(2)) { learnConv(lang, reading, r.getString(1)) }
            }
        }
        o.optJSONObject("shortcuts")?.let { s -> s.keys().forEach { putShortcut(it, s.getString(it)) } }
        o.optJSONArray("blocked")?.let { a ->
            for (i in 0 until a.length()) {
                val w = a.getString(i)
                blocked.add(w)
                io.execute { writableDatabase.execSQL("INSERT OR IGNORE INTO blocked VALUES(?)", arrayOf(w)) }
            }
        }
        o.optJSONArray("clips")?.let { a ->
            for (i in 0 until a.length()) {
                addClip(a.getString(i))
                clips.firstOrNull { it.text == a.getString(i) }?.let { pinClip(it.id, true) }
            }
        }
    }

    companion object {
        @Volatile private var instance: UserModel? = null
        fun get(ctx: Context): UserModel =
            instance ?: synchronized(this) { instance ?: UserModel(ctx).also { instance = it } }
    }
}
