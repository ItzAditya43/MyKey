package com.mobilekey.engine

import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small offline helpers that show up as chips in the suggestion bar. */
object Helpers {
    private val fmt = DecimalFormat("#,##0.####")
    private fun num(d: Double) = fmt.format(d).replace(" ", " ")

    // ---------- calculator ----------

    private val calcRe = Regex("""([-+*/×÷^%().\d\s]*\d[-+*/×÷^%().\d\s]*)=\s*$""")

    /** "12*4.5=" → "54". Needs at least one operator so plain "2024=" isn't treated as maths. */
    fun calculate(before: String): String? {
        val m = calcRe.find(before) ?: return null
        val expr = m.groupValues[1].trim()
        if (expr.none { it in "+-*/×÷^%" } || expr.length < 3) return null
        return try {
            val v = Parser(expr.replace('×', '*').replace('÷', '/')).parse()
            if (v.isNaN() || v.isInfinite()) null else num(v)
        } catch (e: Exception) { null }
    }

    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double { val v = expr(); skip(); if (i < s.length) throw IllegalArgumentException(); return v }
        fun skip() { while (i < s.length && s[i] == ' ') i++ }
        fun expr(): Double {
            var v = term()
            while (true) { skip(); if (i >= s.length) return v
                when (s[i]) { '+' -> { i++; v += term() }; '-' -> { i++; v -= term() }; else -> return v } }
        }
        fun term(): Double {
            var v = power()
            while (true) { skip(); if (i >= s.length) return v
                when (s[i]) {
                    '*' -> { i++; v *= power() }; '/' -> { i++; v /= power() }
                    '%' -> { i++; v /= 100.0 }
                    else -> return v
                } }
        }
        fun power(): Double { val b = unary(); skip(); return if (i < s.length && s[i] == '^') { i++; Math.pow(b, power()) } else b }
        fun unary(): Double { skip(); return if (i < s.length && s[i] == '-') { i++; -unary() } else atom() }
        fun atom(): Double {
            skip()
            if (i < s.length && s[i] == '(') { i++; val v = expr(); skip(); if (i < s.length && s[i] == ')') i++; return v }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(st, i).toDouble()
        }
    }

    // ---------- units ----------

    private val unitRe = Regex("""(-?\d+(?:[.,]\d+)?)\s?(km/h|mph|km|miles?|mi|cm|mm|m|ft|feet|inch(?:es)?|in|kg|lbs?|g|oz|°c|°f|c|f|l|liters?|litres?|gal|ml)\s$""", RegexOption.IGNORE_CASE)

    /** "5 km " → ["3.11 mi"]. Called after a space, so the number and unit are finished. */
    fun convert(before: String): List<String> {
        val m = unitRe.find(before) ?: return emptyList()
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return emptyList()
        return when (m.groupValues[2].lowercase()) {
            "km" -> listOf("${num(v * 0.621371)} mi")
            "mi", "mile", "miles" -> listOf("${num(v * 1.60934)} km")
            "m" -> listOf("${num(v * 3.28084)} ft")
            "ft", "feet" -> listOf("${num(v * 0.3048)} m")
            "cm" -> listOf("${num(v / 2.54)} in")
            "mm" -> listOf("${num(v / 25.4)} in")
            "in", "inch", "inches" -> listOf("${num(v * 2.54)} cm")
            "kg" -> listOf("${num(v * 2.20462)} lb")
            "lb", "lbs" -> listOf("${num(v * 0.453592)} kg")
            "g" -> listOf("${num(v / 28.3495)} oz")
            "oz" -> listOf("${num(v * 28.3495)} g")
            "°c", "c" -> listOf("${num(v * 9 / 5 + 32)} °F")
            "°f", "f" -> listOf("${num((v - 32) * 5 / 9)} °C")
            "l", "liter", "liters", "litre", "litres" -> listOf("${num(v * 0.264172)} gal")
            "ml" -> listOf("${num(v * 0.033814)} fl oz")
            "gal" -> listOf("${num(v * 3.78541)} L")
            "km/h" -> listOf("${num(v * 0.621371)} mph")
            "mph" -> listOf("${num(v * 1.60934)} km/h")
            else -> emptyList()
        }
    }

    // ---------- clipboard codes ----------

    private val codeRe = Regex("""(?<![\d-])(\d{4,8})(?![\d-])""")

    /** A one-time code inside copied text, e.g. "Your code is 482913". */
    fun code(clip: String): String? = if (clip.length <= 200) codeRe.find(clip)?.groupValues?.get(1) else null

    // ---------- smart shortcuts ----------

    /** Fills {date}, {time} and {day}. {cursor} is handled by the keyboard. */
    fun expand(text: String, locale: Locale = Locale.getDefault()): String {
        if ('{' !in text) return text
        val now = Date()
        return text
            .replace("{date}", SimpleDateFormat("d MMM yyyy", locale).format(now))
            .replace("{time}", SimpleDateFormat("HH:mm", locale).format(now))
            .replace("{day}", SimpleDateFormat("EEEE", locale).format(now))
    }
}
