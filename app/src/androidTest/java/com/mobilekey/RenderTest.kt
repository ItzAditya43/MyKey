package com.mobilekey

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders panels off-screen to PNGs in the app's cache (nothing appears on the display). */
@RunWith(AndroidJUnit4::class)
class RenderTest {
    @Test fun renderLanguagePanel() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = Prefs.get(ctx)
        val saved = prefs.getString("langs", null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            listOf("en,es,ja" to "lang3", "en" to "lang1").forEach { (langs, name) ->
                prefs.edit().putString("langs", langs).commit()
                val t = Themes.byName(ctx, KbConfig.themeName(ctx, prefs))
                val v = LangPanel(ctx, t, "en", {}, { _, _ -> }, {}, {}, {})
                v.setBackgroundColor(t.bg)
                val h = (300 * ctx.resources.displayMetrics.density).toInt()
                v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                v.layout(0, 0, 1080, h)
                val b = Bitmap.createBitmap(1080, h, Bitmap.Config.ARGB_8888)
                v.draw(Canvas(b))
                File(ctx.cacheDir, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        prefs.edit().putString("langs", saved).commit()
    }
}
