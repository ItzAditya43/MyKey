package com.mobilekey

import com.mobilekey.engine.Edit
import com.mobilekey.engine.Helpers
import com.mobilekey.engine.Kana
import com.mobilekey.engine.Suggest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpersTest {
    @Test fun calculatorBasics() {
        assertEquals("54", Helpers.calculate("12*4.5="))
        assertEquals("7", Helpers.calculate("1+2*3="))
        assertEquals("9", Helpers.calculate("(1+2)*3="))
        assertEquals("2.5", Helpers.calculate("10/4="))
        assertEquals("1,024", Helpers.calculate("2^10="))
        assertEquals("5", Helpers.calculate("text 2+3="))
        assertEquals("-3", Helpers.calculate("-5+2="))
        assertEquals("54", Helpers.calculate("12×4.5="))
    }

    @Test fun calculatorIgnoresNonMaths() {
        assertNull(Helpers.calculate("2024="))
        assertNull(Helpers.calculate("hello="))
        assertNull(Helpers.calculate("5/0="))
        assertNull(Helpers.calculate("1+="))
        assertNull(Helpers.calculate("no equals 1+2"))
    }

    @Test fun units() {
        assertEquals(listOf("3.1069 mi"), Helpers.convert("5 km "))
        assertEquals(listOf("86 °F"), Helpers.convert("It is 30°C "))
        assertEquals(listOf("2.2046 lb"), Helpers.convert("1kg "))
        assertEquals(listOf("62.1371 mph"), Helpers.convert("100 km/h "))
        assertTrue(Helpers.convert("5 km").isEmpty()) // not finished (no space yet)
        assertTrue(Helpers.convert("hello ").isEmpty())
    }

    @Test fun oneTimeCodes() {
        assertEquals("482913", Helpers.code("Your code is 482913. Don't share it."))
        assertEquals("1234", Helpers.code("PIN 1234"))
        assertNull(Helpers.code("Call 98765-43210"))
        assertNull(Helpers.code("no digits here"))
        assertNull(Helpers.code("x".repeat(250) + " 123456"))
    }

    @Test fun smartShortcutPlaceholders() {
        val out = Helpers.expand("Meeting on {date} at {time}")
        assertTrue(!out.contains("{date}") && !out.contains("{time}"))
        assertEquals("no placeholders", Helpers.expand("no placeholders"))
        assertTrue(Helpers.expand("{cursor} stays").contains("{cursor}"))
    }
}

class KanaTest {
    private fun type(s: String): String {
        var pend = ""
        val out = StringBuilder()
        for (c in s) { val (k, p) = Kana.feed(pend, c); out.append(k); pend = p }
        return out.toString() + Kana.flush(pend)
    }

    @Test fun romaji() {
        assertEquals("にほんご", type("nihongo"))
        assertEquals("ありがとう", type("arigatou"))
        assertEquals("きょう", type("kyou"))
        assertEquals("がっこう", type("gakkou"))
        assertEquals("しんぶん", type("shinbun"))
        assertEquals("こんや", type("konnya"))
        assertEquals("ちゃ", type("cha"))
        assertEquals("ん", type("n"))
        assertEquals("ー", type("-"))
    }

    @Test fun dakutenCycle() {
        assertEquals('が', Kana.cycle('か'))
        assertEquals('か', Kana.cycle('が'))
        assertEquals('ば', Kana.cycle('は'))
        assertEquals('ぱ', Kana.cycle('ば'))
        assertEquals('は', Kana.cycle('ぱ'))
        assertEquals('っ', Kana.cycle('つ'))
        assertNull(Kana.cycle('ん'))
    }

    @Test fun katakana() {
        assertEquals("ニホン", Kana.toKatakana("にほん"))
        assertTrue(Kana.isHiragana("ひらがなー"))
        assertTrue(!Kana.isHiragana("カタカナ"))
    }
}

class EditDistanceTest {
    private val none: (Char, Char) -> Boolean = { _, _ -> false }

    @Test fun distances() {
        assertEquals(0f, Edit.distance("hello", "hello", 2f, none))
        assertEquals(0.5f, Edit.distance("helo", "hello", 2f, none))
        assertEquals(0.5f, Edit.distance("untill", "until", 2f, none))
        assertEquals(1f, Edit.distance("tommorow", "tomorrow", 2f, none))
        assertEquals(1f, Edit.distance("helo", "help", 2f, none))
        assertEquals(0.8f, Edit.distance("teh", "the", 2f, none))
        assertEquals(1f, Edit.distance("cat", "car", 2f, none))
        assertTrue(Edit.distance("abc", "xyzzy", 2f, none) > 2f)
    }

    @Test fun nearKeysCostLess() {
        val near: (Char, Char) -> Boolean = { a, b -> (a == 'r' && b == 't') || (a == 't' && b == 'r') }
        assertEquals(0.6f, Edit.distance("cat", "car", 2f) { a, b -> near(a, b) })
    }
}

class CaseTest {
    @Test fun matchCase() {
        assertEquals("Hello", Suggest.matchCase("Helo", "hello"))
        assertEquals("HELLO", Suggest.matchCase("HELO", "hello"))
        assertEquals("hello", Suggest.matchCase("helo", "hello"))
        assertEquals("I", Suggest.matchCase("i", "I"))
    }
}

class LayoutParseTest {
    @Test fun parsesStringsObjectsAndTypes() {
        val l = Layouts.parse("""{"name":"T","long":{"e":"éè"},"hints":{".":"!?"},
            "rows":[["q","e"],[{"type":"shift"},{"label":".com","out":".com","w":1.4},{"type":"backspace"}],["."]]}""")
        assertEquals(3, l.rows.size)
        assertEquals(listOf("é", "è"), l.rows[0][1].long)
        assertEquals("shift", l.rows[1][0].type)
        assertEquals(1.5f, l.rows[1][0].w)
        assertEquals(".com", l.rows[1][1].out)
        assertEquals(1.4f, l.rows[1][1].w)
        assertEquals("!?", l.rows[2][0].hint)
    }

    @Test fun flickAndListLongPress() {
        val l = Layouts.parse("""{"rows":[[{"label":"あ","flick":"あいうえお"},{"label":"x","out":"x","long":["Thanks!","Ty"]}]]}""")
        assertEquals(5, l.rows[0][0].flick.size)
        assertEquals(listOf("Thanks!", "Ty"), l.rows[0][1].long)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownType() {
        Layouts.parse("""{"rows":[[{"type":"teleport"}]]}""")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsMissingRows() {
        Layouts.parse("""{"name":"x"}""")
    }

    @Test fun emojiKeyAndNumberRow() {
        val l = Layouts.parse("""{"rows":[["q","w"],[{"type":"symbols"},",",{"type":"space"},".",{"type":"enter"}]]}""")
        val e = Layouts.withEmojiKey(l)
        assertEquals("emoji", e.rows[1][1].type)
        assertEquals(",", e.rows[1][3].long.first())
        val n = Layouts.withNumberRow(l)
        assertEquals("1", n.rows[0][0].out)
        val d = Layouts.withDigitLongPress(l)
        assertEquals("1", d.rows[0][0].long.first())
        assertEquals("@", Layouts.forEmail(l).rows[1][1].out)
    }
}
