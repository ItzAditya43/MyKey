package com.mobilekey

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Key sound packs, synthesised once into tiny WAV files in the cache (no audio files shipped).
 * "system" uses Android's own key click instead.
 */
object Sounds {
    val packs = listOf("system" to "System", "soft" to "Soft", "mechanical" to "Mechanical", "typewriter" to "Typewriter", "bubble" to "Bubble")

    private var pool: SoundPool? = null
    private var poolMedia: SoundPool? = null
    private val ids = HashMap<String, Int>()

    /** SoundPool loads asynchronously, so load the pack before the first key press. */
    fun preload(ctx: Context, pack: String, media: Boolean = false) {
        if (pack != "system" || media) play(ctx, if (pack == "system") "soft" else pack, 0f, media)
    }

    /** [media] plays on the media stream, which isn't muted by silent/vibrate mode. */
    fun play(ctx: Context, pack: String, volume: Float, media: Boolean = false) {
        val p = (if (media) poolMedia else pool) ?: SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (media) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build().also { if (media) poolMedia = it else pool = it }
        val key = (if (media) "m:" else "s:") + pack
        val id = ids.getOrPut(key) { p.load(file(ctx, pack).path, 1) }
        p.play(id, volume, volume, 1, 0, 1f)
    }

    private fun file(ctx: Context, pack: String): File {
        val f = File(ctx.cacheDir, "key_$pack.wav")
        if (!f.exists()) write(f, synth(pack))
        return f
    }

    private fun synth(pack: String): ShortArray {
        val rate = 44100
        val rnd = Random(7)
        val len = when (pack) { "typewriter" -> 0.05; "bubble" -> 0.07; else -> 0.04 }
        val n = (rate * len).toInt()
        return ShortArray(n) { i ->
            val t = i / rate.toDouble()
            val v = when (pack) {
                "soft" -> sin(2 * PI * 520 * t) * exp(-t * 90) * 0.35
                "mechanical" -> (rnd.nextDouble(-1.0, 1.0) * exp(-t * 260) * 0.55 + sin(2 * PI * 180 * t) * exp(-t * 60) * 0.25)
                "typewriter" -> (rnd.nextDouble(-1.0, 1.0) * exp(-t * 420) * 0.7 + sin(2 * PI * 1900 * t) * exp(-t * 150) * 0.15)
                "bubble" -> sin(2 * PI * (380 + 900 * t / len) * t) * exp(-t * 55) * 0.35
                else -> 0.0
            }
            (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun write(f: File, pcm: ShortArray) {
        RandomAccessFile(f, "rw").use { o ->
            val data = pcm.size * 2
            fun i32(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff); o.write(v shr 16 and 0xff); o.write(v shr 24 and 0xff) }
            fun i16(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff) }
            o.setLength(0)
            o.writeBytes("RIFF"); i32(36 + data); o.writeBytes("WAVEfmt ")
            i32(16); i16(1); i16(1); i32(44100); i32(44100 * 2); i16(2); i16(16)
            o.writeBytes("data"); i32(data)
            val buf = ByteArray(data)
            pcm.forEachIndexed { i, s -> buf[2 * i] = (s.toInt() and 0xff).toByte(); buf[2 * i + 1] = (s.toInt() shr 8 and 0xff).toByte() }
            o.write(buf)
        }
    }
}
