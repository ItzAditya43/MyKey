package com.mobilekey

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Online GIF search through GIPHY or Tenor, using your own free API key.
 * Only runs when you open the GIF tab or search; nothing about your typing is sent.
 */
object GifOnline {
    class Gif(val id: String, val preview: String, val full: String)

    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val previews = HashMap<String, ByteArray>()

    fun enabled(ctx: Context): Boolean {
        val p = Prefs.get(ctx)
        return Features.isOn(p, "gif_search") && !p.getString("gif_key", "").isNullOrBlank()
    }

    /** Empty query means trending. */
    fun search(ctx: Context, query: String, done: (List<Gif>, String?) -> Unit) {
        val p = Prefs.get(ctx)
        val key = p.getString("gif_key", "")!!.trim()
        val provider = p.getString("gif_provider", "giphy")
        io.execute {
            val result = try {
                val q = URLEncoder.encode(query, "UTF-8")
                val url = if (provider == "tenor") {
                    if (query.isEmpty()) "https://tenor.googleapis.com/v2/featured?key=$key&client_key=mykey&limit=30&media_filter=tinygif,gif&contentfilter=medium"
                    else "https://tenor.googleapis.com/v2/search?q=$q&key=$key&client_key=mykey&limit=30&media_filter=tinygif,gif&contentfilter=medium"
                } else {
                    if (query.isEmpty()) "https://api.giphy.com/v1/gifs/trending?api_key=$key&limit=30&rating=pg-13"
                    else "https://api.giphy.com/v1/gifs/search?api_key=$key&q=$q&limit=30&rating=pg-13"
                }
                val o = JSONObject(String(get(url)))
                val list = ArrayList<Gif>()
                if (provider == "tenor") {
                    val a = o.getJSONArray("results")
                    for (i in 0 until a.length()) {
                        val r = a.getJSONObject(i)
                        val m = r.getJSONObject("media_formats")
                        list.add(Gif(r.getString("id"), m.getJSONObject("tinygif").getString("url"), m.getJSONObject("gif").getString("url")))
                    }
                } else {
                    val a = o.getJSONArray("data")
                    for (i in 0 until a.length()) {
                        val r = a.getJSONObject(i)
                        val im = r.getJSONObject("images")
                        list.add(Gif(r.getString("id"), im.getJSONObject("fixed_width_small").getString("url"), im.getJSONObject("downsized").getString("url")))
                    }
                }
                list to null
            } catch (e: Exception) {
                emptyList<Gif>() to "Couldn't load GIFs. Check your internet connection and API key."
            }
            main.post { done(result.first, result.second) }
        }
    }

    fun preview(g: Gif, done: (Drawable?) -> Unit) {
        io.execute {
            val d = try {
                val bytes = synchronized(previews) { previews[g.id] } ?: get(g.preview).also { b -> synchronized(previews) { if (previews.size > 120) previews.clear(); previews[g.id] = b } }
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
            } catch (e: Exception) { null }
            main.post { done(d) }
        }
    }

    /** Downloads the full GIF into the cache so it can be handed to the app you're typing in. */
    fun download(ctx: Context, g: Gif, done: (File?) -> Unit) {
        io.execute {
            val f = try {
                val dir = File(ctx.cacheDir, "online_gifs").apply { mkdirs() }
                dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(20)?.forEach { it.delete() }
                File(dir, "${g.id.filter { it.isLetterOrDigit() }}.gif").also { if (!it.exists()) it.writeBytes(get(g.full)) }
            } catch (e: Exception) { null }
            main.post { done(f) }
        }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "MyKey")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
}
