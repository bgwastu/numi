package net.wastu.numi.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Rendering checks only: never sends SMS, calls Telegram, or changes provider data. */
@RunWith(AndroidJUnit4::class)
class IncomingSmsFormattingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sender = "MetadataSender"

    private fun render(body: String) = IncomingSmsTelegramMessage.format(context, sender, body, 1_000, null)

    private fun body(part: String): String = unescape(
        part.substringBefore("\n\n<code>$sender</code>")
            .replace(Regex("\\n\\n\\([0-9]+/[0-9]+\\)$"), "")
    )

    private fun unescape(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    @Test fun metadataUsesPlainPunctuation() {
        val rendered = render("A short message").single()
        assertTrue(rendered.contains("<code>$sender</code>, <code>"))
        assertFalse(rendered.any { it.code in listOf(0x00B7, 0x2022, 0x2026, 0x2027, 0x22C5) })
    }

    @Test fun htmlInMessageIsTextRatherThanMarkup() {
        val original = "<b>Keep this literal</b> & preserve every character"
        val rendered = render(original).single()
        assertFalse(rendered.contains("<b>Keep this literal</b>"))
        assertEquals(original, body(rendered))
    }

    @Test fun multipartPreservesCompleteUnicodeAndWhitespace() {
        val original = ("A long message 😀 & <text>\n\n".repeat(450)) + "last character"
        val parts = render(original)
        assertTrue(parts.size > 1)
        assertEquals(original, parts.joinToString("") { body(it) })
        parts.forEach { part ->
            assertTrue(unescape(part).replace(Regex("</?code>"), "").length <= 4096)
            assertFalse(body(part).lastOrNull()?.isHighSurrogate() == true)
        }
    }

    @Test fun punctuationInUserContentIsPreserved() {
        val original = "Sender's content: first · second • third …"
        assertEquals(original, body(render(original).single()))
    }
}
