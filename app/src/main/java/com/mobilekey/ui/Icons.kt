package com.mobilekey.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Line icons drawn on a 24-unit grid, so they stay crisp at any size and take the theme colour. */
class IconView(ctx: Context, var icon: String, var color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    var active = false
        set(v) { field = v; invalidate() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8.2f; textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height) * 0.62f
        c.save()
        c.translate((width - s) / 2, (height - s) / 2)
        c.scale(s / 24f, s / 24f)
        p.color = color
        p.alpha = if (active || isEnabled) 255 else 110
        p.strokeWidth = 1.9f
        p.style = Paint.Style.STROKE
        draw(c, icon)
        c.restore()
    }

    private fun draw(c: Canvas, name: String) {
        when (name) {
            "emoji" -> {
                c.drawCircle(12f, 12f, 9f, p)
                fillDot(c, 9f, 10f); fillDot(c, 15f, 10f)
                c.drawArc(RectF(7.5f, 9f, 16.5f, 17f), 30f, 120f, false, p)
            }
            "clipboard" -> {
                c.drawRoundRect(RectF(5f, 5f, 19f, 21f), 2.5f, 2.5f, p)
                c.drawRoundRect(RectF(9f, 3f, 15f, 7f), 1.5f, 1.5f, p)
                c.drawLine(8.5f, 12f, 15.5f, 12f, p); c.drawLine(8.5f, 16f, 13.5f, 16f, p)
            }
            "edit" -> {
                c.drawLine(12f, 3f, 12f, 21f, p); c.drawLine(3f, 12f, 21f, 12f, p)
                arrow(c, 12f, 3f, 0); arrow(c, 12f, 21f, 2); arrow(c, 3f, 12f, 3); arrow(c, 21f, 12f, 1)
            }
            "voice" -> {
                c.drawRoundRect(RectF(9f, 3f, 15f, 14f), 3f, 3f, p)
                c.drawArc(RectF(5.5f, 6.5f, 18.5f, 17.5f), 20f, 140f, false, p)
                c.drawLine(12f, 17.5f, 12f, 21f, p)
            }
            "hand" -> {
                c.drawRoundRect(RectF(3f, 6f, 21f, 18f), 2.5f, 2.5f, p)
                c.drawLine(13f, 6f, 13f, 18f, p)
                val path = Path().apply { moveTo(8f, 10f); lineTo(6f, 12f); lineTo(8f, 14f) }
                c.drawPath(path, p)
            }
            "float" -> {
                c.drawRoundRect(RectF(4f, 8f, 20f, 18f), 2.5f, 2.5f, p)
                c.drawLine(9f, 5f, 15f, 5f, p)
            }
            "incognito" -> {
                c.drawCircle(7.5f, 15f, 3.2f, p); c.drawCircle(16.5f, 15f, 3.2f, p)
                c.drawLine(10.7f, 15f, 13.3f, 15f, p)
                c.drawLine(3f, 10f, 21f, 10f, p)
                val hat = Path().apply { moveTo(6f, 10f); lineTo(8f, 4.5f); lineTo(16f, 4.5f); lineTo(18f, 10f) }
                c.drawPath(hat, p)
            }
            "settings" -> {
                c.drawCircle(12f, 12f, 3.2f, p)
                c.drawCircle(12f, 12f, 7.2f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(12f + 7.2f * cos(a).toFloat(), 12f + 7.2f * sin(a).toFloat(), 12f + 9.6f * cos(a).toFloat(), 12f + 9.6f * sin(a).toFloat(), p)
                }
            }
            "undo", "redo" -> {
                c.save()
                if (name == "redo") c.scale(-1f, 1f, 12f, 12f)
                c.drawArc(RectF(5f, 7f, 20f, 20f), 180f, 200f, false, p)
                val path = Path().apply { moveTo(5f, 9f); lineTo(5f, 14f); lineTo(10f, 14f) }
                c.save(); c.rotate(-20f, 5f, 13.5f); c.drawPath(path, p); c.restore()
                c.restore()
            }
            "snippet" -> {
                c.drawRoundRect(RectF(3.5f, 4f, 20.5f, 17f), 3f, 3f, p)
                val tail = Path().apply { moveTo(8f, 17f); lineTo(7f, 21f); lineTo(12f, 17f) }
                c.drawPath(tail, p)
                c.drawLine(7.5f, 8.5f, 16.5f, 8.5f, p); c.drawLine(7.5f, 12.5f, 13.5f, 12.5f, p)
            }
            "theme" -> {
                c.drawCircle(12f, 12f, 8.5f, p)
                val half = Path().apply { addArc(RectF(3.5f, 3.5f, 20.5f, 20.5f), 90f, 180f); close() }
                p.style = Paint.Style.FILL; c.drawPath(half, p); p.style = Paint.Style.STROKE
            }
            "resize" -> {
                c.drawLine(12f, 3f, 12f, 21f, p)
                val up = Path().apply { moveTo(8f, 7f); lineTo(12f, 3f); lineTo(16f, 7f) }
                val dn = Path().apply { moveTo(8f, 17f); lineTo(12f, 21f); lineTo(16f, 17f) }
                c.drawPath(up, p); c.drawPath(dn, p)
                c.drawLine(5f, 12f, 19f, 12f, p)
            }
            "gif" -> {
                c.drawRoundRect(RectF(2.5f, 6f, 21.5f, 18f), 3f, 3f, p)
                textPaint.color = p.color
                c.drawText("GIF", 12f, 15f, textPaint)
            }
            "globe" -> {
                c.drawCircle(12f, 12f, 9f, p); c.drawOval(8f, 3f, 16f, 21f, p); c.drawLine(3f, 12f, 21f, 12f, p)
            }
            "keyboard" -> {
                c.drawRoundRect(RectF(2.5f, 6f, 21.5f, 18f), 2.5f, 2.5f, p)
                for (x in listOf(6.5f, 10f, 13.5f, 17f)) fillDot(c, x, 10f, 1.1f)
                c.drawLine(8f, 14.5f, 16f, 14.5f, p)
            }
            "check" -> {
                val path = Path().apply { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19.5f, 7f) }
                c.drawPath(path, p)
            }
            "hold" -> {
                c.drawRoundRect(RectF(4f, 11f, 20f, 21f), 2.5f, 2.5f, p)
                c.drawRoundRect(RectF(7f, 3f, 17f, 8.5f), 2f, 2f, p)
                c.drawLine(12f, 8.5f, 12f, 11f, p)
            }
            "layout" -> {
                c.drawRoundRect(RectF(3f, 4f, 21f, 20f), 2.5f, 2.5f, p)
                c.drawLine(3f, 9.5f, 21f, 9.5f, p); c.drawLine(9.5f, 9.5f, 9.5f, 20f, p)
            }
            "chart" -> {
                c.drawLine(5f, 20f, 5f, 13f, p); c.drawLine(10f, 20f, 10f, 8f, p); c.drawLine(15f, 20f, 15f, 11f, p); c.drawLine(20f, 20f, 20f, 4f, p)
            }
            "shield" -> {
                val path = Path().apply { moveTo(12f, 3f); lineTo(19.5f, 6f); lineTo(19f, 12.5f); quadTo(18f, 18.5f, 12f, 21f); quadTo(6f, 18.5f, 5f, 12.5f); lineTo(4.5f, 6f); close() }
                c.drawPath(path, p)
            }
            "backup" -> {
                c.drawArc(RectF(4f, 4f, 20f, 20f), 210f, 290f, false, p)
                val head = Path().apply { moveTo(4.5f, 7f); lineTo(5.6f, 11.4f); lineTo(9.6f, 9.8f) }
                c.drawPath(head, p); c.drawLine(12f, 8f, 12f, 12.5f, p); c.drawLine(12f, 12.5f, 15f, 14.5f, p)
            }
            "sparkle" -> {
                val path = Path().apply { moveTo(12f, 3f); quadTo(13f, 11f, 21f, 12f); quadTo(13f, 13f, 12f, 21f); quadTo(11f, 13f, 3f, 12f); quadTo(11f, 11f, 12f, 3f); close() }
                c.drawPath(path, p)
            }
            "toolbar" -> {
                c.drawRoundRect(RectF(2.5f, 8f, 21.5f, 16f), 4f, 4f, p)
                for (x in listOf(7f, 12f, 17f)) fillDot(c, x, 12f, 1.3f)
            }
            "brush" -> {
                c.drawLine(19.5f, 4.5f, 10f, 14f, p)
                val tip = Path().apply { moveTo(10f, 14f); quadTo(6f, 13.5f, 5f, 18f); quadTo(4.5f, 20f, 3f, 20.5f); quadTo(9f, 21.5f, 10.5f, 17f); close() }
                c.drawPath(tip, p)
            }
            "drag" -> { c.drawLine(5f, 8f, 19f, 8f, p); c.drawLine(5f, 12f, 19f, 12f, p); c.drawLine(5f, 16f, 19f, 16f, p) }
            "plus" -> { c.drawLine(12f, 5f, 12f, 19f, p); c.drawLine(5f, 12f, 19f, 12f, p) }
            "grid" -> {
                for (x in listOf(6f, 12f, 18f)) for (y in listOf(6f, 12f, 18f)) fillDot(c, x, y, 1.6f)
            }
            "back" -> {
                val path = Path().apply { moveTo(14.5f, 5f); lineTo(7.5f, 12f); lineTo(14.5f, 19f) }
                c.drawPath(path, p)
            }
            "close" -> { c.drawLine(6f, 6f, 18f, 18f, p); c.drawLine(18f, 6f, 6f, 18f, p) }
            "search" -> { c.drawCircle(10.5f, 10.5f, 6f, p); c.drawLine(15f, 15f, 20f, 20f, p) }
            "pin" -> {
                val path = Path().apply { moveTo(9f, 3f); lineTo(15f, 3f); lineTo(14f, 9f); lineTo(17f, 13f); lineTo(7f, 13f); lineTo(10f, 9f); close() }
                c.drawPath(path, p); c.drawLine(12f, 13f, 12f, 21f, p)
            }
            "trash" -> {
                c.drawLine(4f, 6.5f, 20f, 6.5f, p)
                c.drawRoundRect(RectF(6.5f, 6.5f, 17.5f, 20.5f), 2f, 2f, p)
                c.drawLine(9.5f, 3.5f, 14.5f, 3.5f, p)
            }
            "backspace" -> {
                val path = Path().apply { moveTo(8f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(8f, 19f); lineTo(2.5f, 12f); close() }
                c.drawPath(path, p)
                c.drawLine(11.5f, 9f, 17f, 15f, p); c.drawLine(17f, 9f, 11.5f, 15f, p)
            }
            "swap" -> {
                c.drawLine(4f, 9f, 20f, 9f, p); arrow(c, 20f, 9f, 1)
                c.drawLine(20f, 15f, 4f, 15f, p); arrow(c, 4f, 15f, 3)
            }
            "expand" -> {
                c.drawLine(4f, 12f, 20f, 12f, p); arrow(c, 4f, 12f, 3); arrow(c, 20f, 12f, 1)
            }
        }
    }

    private fun fillDot(c: Canvas, x: Float, y: Float, r: Float = 1.3f) {
        p.style = Paint.Style.FILL
        c.drawCircle(x, y, r, p)
        p.style = Paint.Style.STROKE
    }

    /** Arrow head at (x, y) pointing up(0), right(1), down(2) or left(3). */
    private fun arrow(c: Canvas, x: Float, y: Float, dir: Int) {
        c.save()
        c.rotate(dir * 90f, x, y)
        val path = Path().apply { moveTo(x - 3f, y + 3f); lineTo(x, y); lineTo(x + 3f, y + 3f) }
        c.drawPath(path, p)
        c.restore()
    }
}
