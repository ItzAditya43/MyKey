package com.mobilekey

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Typeface
import com.mobilekey.ui.Fonts

/** Shared by the keyboard and the settings preview, so the preview is exactly what you'll get. */
object KbConfig {
    const val DEF_HEIGHT = 52
    const val DEF_GAP = 5
    const val DEF_RADIUS = 8
    const val DEF_LABEL = 21
    /** Overall keyboard size, percent. */
    const val DEF_SCALE = 100

    val fonts = listOf("system" to "System", "figtree" to "Figtree", "grotesque" to "Grotesque", "mono" to "Mono", "serif" to "Serif")
    val styles = listOf("filled" to "Filled", "outline" to "Outlined", "borderless" to "Borderless")
    val anims = listOf("none" to "None", "pop" to "Pop", "ripple" to "Ripple", "glow" to "Glow", "fade" to "Fade")
    val hapticLevels = listOf("Light", "Medium", "Strong")

    fun font(ctx: Context, id: String?): Typeface = when (id) {
        "figtree" -> Fonts.body(ctx, 460)
        "grotesque" -> Fonts.display(ctx, 560)
        "mono" -> Typeface.MONOSPACE
        "serif" -> Typeface.SERIF
        else -> Typeface.DEFAULT
    }

    val trails = listOf("comet" to "Comet", "neon" to "Neon", "rainbow" to "Rainbow")
    val bgEffects = listOf("none" to "None", "gradient" to "Gradient", "aurora" to "Aurora (animated)")

    /** Keyboard position for this app: its profile's choice, or the normal setting. */
    fun handMode(p: SharedPreferences, app: String?): String {
        if (app != null && Features.isOn(p, "per_app_theme")) p.getString("app_hand_$app", null)?.let { return it }
        return p.getString("hand_mode", "full") ?: "full"
    }

    /** Theme for this moment: per-app choice, then night theme in dark mode, then your main theme. */
    fun themeName(ctx: Context, p: SharedPreferences, app: String? = null): String {
        if (app != null && Features.isOn(p, "per_app_theme")) p.getString("app_theme_$app", null)?.let { return it }
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (night && Features.isOn(p, "auto_theme")) p.getString("theme_night", null)?.let { return it }
        if (!night && Features.isOn(p, "auto_theme")) p.getString("theme_day", null)?.let { return it }
        return p.getString("theme", "Graphite") ?: "Graphite"
    }

    fun apply(ctx: Context, kv: KeyboardView, p: SharedPreferences, app: String? = null) {
        fun f(id: String) = Features.isOn(p, id)
        val theme = Themes.byName(ctx, themeName(ctx, p, app))
        val landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val profile = app != null && f("per_app_theme")
        val appScale = if (profile) p.getInt("app_scale_$app", 0) else 0
        val scale = (if (appScale > 0) appScale else p.getInt("scale", DEF_SCALE)) / 100f * (if (landscape) 0.72f else 1f)
        kv.theme = theme
        kv.keyHeightDp = p.getInt("key_h", DEF_HEIGHT) * scale
        kv.gapDp = p.getInt("gap", DEF_GAP).toFloat()
        kv.radiusDp = p.getInt("radius", DEF_RADIUS).toFloat()
        kv.labelSp = p.getInt("label", DEF_LABEL).toFloat()
        kv.showHints = f("hints")
        kv.preview = f("key_preview")
        kv.longPress = f("long_press")
        kv.spaceCursor = f("space_cursor")
        kv.backspaceSwipe = f("backspace_swipe")
        kv.accel = f("backspace_accel")
        kv.haptics = f("haptics")
        kv.hapticLevel = p.getInt("haptic_level", 1)
        kv.sounds = f("sounds")
        kv.soundVolume = p.getInt("sound_volume", 50) / 100f
        kv.keyStyle = p.getString("key_style", if (theme.amoled) "outline" else "filled") ?: "filled"
        kv.pressAnim = p.getString("press_anim", "pop") ?: "pop"
        kv.labelFont = font(ctx, p.getString("key_font", "system"))
        kv.boldLabels = f("bold_labels")
        kv.shadow = f("key_shadow")
        kv.keyAlpha = if (p.getString("bg_image", null) != null) p.getInt("key_opacity", 70) * 255 / 100 else 255
        kv.glide = f("glide")
        kv.glideTrail = f("glide_trail")
        val appSplit = if (profile) p.getString("app_split_$app", null) else null
        kv.split = when (appSplit) { "on" -> true; "off" -> false; else -> f("split") || (landscape && f("split_landscape")) }
        kv.soundsInSilent = f("sounds_silent")
        kv.soundPack = p.getString("sound_pack", "system") ?: "system"
        kv.trailStyle = p.getString("trail_style", "comet") ?: "comet"
        kv.longPressMs = p.getInt("long_press_ms", 350).toLong()
        kv.glideStart = p.getInt("glide_start", 9) / 10f
        kv.repeatSpeed = p.getInt("repeat_speed", 3)
        kv.swipeGestures = f("swipe_gestures")
        kv.adaptive = f("adaptive_keys")
        kv.trackpad = f("trackpad")
        kv.refresh()
    }

    fun lettersLayout(ctx: Context, p: SharedPreferences, lang: String, email: Boolean = false): KbLayout {
        var l = Layouts.load(ctx, Langs.layoutFor(lang, p), Features.isOn(p, "custom_layouts"))
        val special = l.rows.any { r -> r.any { it.flick.isNotEmpty() || it.type?.startsWith("morse") == true } }
        if (!special) l = if (Features.isOn(p, "number_row")) Layouts.withNumberRow(l) else Layouts.withDigitLongPress(l)
        if (email) l = Layouts.forEmail(l)
        else if (!special && Features.isOn(p, "emoji_key")) l = Layouts.withEmojiKey(l)
        return l
    }
}
