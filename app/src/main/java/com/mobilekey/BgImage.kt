package com.mobilekey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.File

/** Keyboard background photo: stored once in app storage, drawn scaled to fill with a dark overlay. */
object BgImage {
    private var cache: Pair<String, Bitmap>? = null

    fun file(ctx: Context): File? =
        Prefs.get(ctx).getString("bg_image", null)?.let { File(ctx.filesDir, it) }?.takeIf { it.exists() }

    fun bitmap(ctx: Context): Bitmap? {
        val f = file(ctx) ?: return null
        val key = f.path + f.lastModified()
        cache?.let { if (it.first == key) return it.second }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= ctx.resources.displayMetrics.widthPixels) s *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        cache = key to b
        return b
    }

    fun drawable(ctx: Context): Drawable? =
        bitmap(ctx)?.let { CropDrawable(it, Prefs.get(ctx).getInt("bg_dim", 35)) }

    /** Copies a picked photo into app storage, scaled down to about screen width. */
    fun import(ctx: Context, uri: Uri) {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= ctx.resources.displayMetrics.widthPixels) s *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return
        remove(ctx)
        val name = "bg_${System.currentTimeMillis()}.jpg"
        File(ctx.filesDir, name).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        Prefs.get(ctx).edit().putString("bg_image", name).apply()
    }

    fun remove(ctx: Context) {
        file(ctx)?.delete()
        Prefs.get(ctx).edit().remove("bg_image").apply()
        cache = null
    }
}

class CropDrawable(private val b: Bitmap, dimPercent: Int) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = (dimPercent * 255 / 100) shl 24 }
    override fun draw(c: Canvas) {
        val r = bounds
        val s = maxOf(r.width() / b.width.toFloat(), r.height() / b.height.toFloat())
        val w = (b.width * s).toInt()
        val h = (b.height * s).toInt()
        val dst = Rect(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2)
        c.save(); c.clipRect(r)
        c.drawBitmap(b, null, dst, paint)
        c.restore()
        c.drawRect(r, dim)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** Theme-tinted backgrounds. Kept mostly black so OLED pixels stay off; "aurora" drifts slowly. */
class EffectDrawable(private val t: KbTheme, private val animated: Boolean) : Drawable() {
    private val base = Paint().apply { color = t.bg }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val start = android.os.SystemClock.uptimeMillis()

    override fun draw(c: Canvas) {
        val r = bounds
        c.drawRect(r, base)
        val time = if (animated) (android.os.SystemClock.uptimeMillis() - start) / 1000.0 else 0.0
        val blobs = listOf(Triple(0.2, 0.0, t.acc), Triple(0.8, 1.7, KbTheme.mix(t.acc, t.border, 0.5f)))
        for ((fx, phase, col) in blobs) {
            val cx = r.left + r.width() * (fx + 0.12 * kotlin.math.sin(time * 0.25 + phase)).toFloat()
            val cy = r.top + r.height() * (0.85 + 0.15 * kotlin.math.cos(time * 0.2 + phase)).toFloat()
            val rad = r.width() * 0.55f
            glow.shader = android.graphics.RadialGradient(cx, cy, rad, (col and 0xFFFFFF) or (0x3A shl 24), 0, android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, rad, glow)
        }
        if (animated) scheduleSelf({ invalidateSelf() }, android.os.SystemClock.uptimeMillis() + 50)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
