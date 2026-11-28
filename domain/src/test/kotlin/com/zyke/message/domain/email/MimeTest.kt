package com.zyke.message.domain.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mail reader: transfer encodings, charsets, nested parts, HTML, and hostile or cut-off input. */
class MimeTest {
    private fun parse(text: String, charset: java.nio.charset.Charset = Charsets.ISO_8859_1) = Mime.parse(text.toByteArray(charset))

    @Test
    fun quotedPrintableDecodesUtf8AndSoftBreaks() {
        val raw = "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nRs.250.00 paid to Caf=C3=A9 on 28-11-26. Your UPI transaction refer=\r\nence number is 612345678901.\r\n"
        val text = parse(raw).text
        assertTrue(text, "Café" in text)
        assertTrue(text, "reference number is 612345678901" in text)
    }

    @Test
    fun base64Part_andIso88591Charset() {
        val body = java.util.Base64.getMimeEncoder().encodeToString("Rs.5 debited at Café".toByteArray(Charsets.ISO_8859_1))
        val raw = "Content-Type: text/plain; charset=\"iso-8859-1\"\r\nContent-Transfer-Encoding: base64\r\n\r\n$body\r\n"
        assertEquals("Rs.5 debited at Café", parse(raw).text)
    }

    @Test
    fun unknownCharsetFallsBackAndNeverThrows() {
        val raw = "Content-Type: text/plain; charset=x-nonsense\r\n\r\nRs.1 £ ok"
        assertTrue("Rs.1" in parse(raw, Charsets.ISO_8859_1).text)
    }

    @Test
    fun invalidUtf8WithoutCharsetIsReadAsWindows1252() {
        val bytes = "Content-Type: text/plain\r\n\r\nRs.9 caf".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0xE9.toByte())
        assertEquals("Rs.9 café", Mime.parse(bytes).text)
    }

    @Test
    fun multipartAlternativePrefersAUsefulPlainPart_andFallsBackToHtml() {
        val alt = "Content-Type: multipart/alternative; boundary=\"B1\"\r\n\r\n--B1\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nRs.250.00 has been debited from account **1234 to VPA a@b on 28-11-26.\r\n--B1\r\nContent-Type: text/html; charset=utf-8\r\n\r\n<html><body><p>HTML version Rs.999</p></body></html>\r\n--B1--\r\n"
        assertTrue("debited from account" in parse(alt).text)
        assertFalse("HTML version" in parse(alt).text)
        val stub = "Content-Type: multipart/alternative; boundary=B2\r\n\r\n--B2\r\nContent-Type: text/plain\r\n\r\nView in browser\r\n--B2\r\nContent-Type: text/html\r\n\r\n<p>Rs.250.00 has been <b>debited</b> from account **1234</p>\r\n--B2--\r\n"
        assertEquals("Rs.250.00 has been debited from account **1234", parse(stub).text)
    }

    @Test
    fun nestedMultipart_ignoresAttachments() {
        val raw = "Content-Type: multipart/mixed; boundary=OUT\r\n\r\n--OUT\r\nContent-Type: multipart/alternative; boundary=IN\r\n\r\n--IN\r\nContent-Type: text/plain\r\n\r\nRs.100 credited to your account **9876 by VPA x@y on 01-01-27. Your UPI transaction reference number is 123456789012.\r\n--IN--\r\n--OUT\r\nContent-Type: application/pdf; name=a.pdf\r\nContent-Disposition: attachment; filename=a.pdf\r\nContent-Transfer-Encoding: base64\r\n\r\nJVBERi0xLjQK\r\n--OUT--\r\n"
        val text = parse(raw).text
        assertTrue("credited to your account" in text)
        assertFalse("JVBER" in text)
    }

    @Test
    fun aMessageCutShort_withNoClosingBoundary_isStillRead() {
        val raw = "Content-Type: multipart/alternative; boundary=\"cut\"\r\n\r\n--cut\r\nContent-Type: text/plain\r\n\r\nRs.250.00 has been debited from account **1234 to VPA a@b on 28-11-26. Your UPI transaction reference number is 612345678901."
        assertTrue("612345678901" in parse(raw).text)
    }

    @Test
    fun htmlIsStrippedToText_withEntitiesAndNoScripts() {
        val html = "<html><head><style>p{color:red}</style><script>alert(1)</script></head><body><!-- hidden --><table><tr><td>Rs.&nbsp;250.00</td><td>&#8377; 5 &amp; &lt;x&gt;</td></tr></table><br>Line two&nbsp;&rarr; ok</body></html>"
        val text = HtmlText.strip(html)
        assertFalse("alert" in text || "color" in text || "hidden" in text)
        assertTrue(text, "Rs. 250.00" in text)
        assertTrue(text, "₹ 5 & <x>" in text)
        assertTrue(text, "Line two -> ok" in text)
    }

    @Test
    fun encodedWordsInHeadersAreDecoded() {
        assertEquals("HDFC Bank – UPI alert", Mime.decodeHeader("=?UTF-8?Q?HDFC_Bank_=E2=80=93_UPI_alert?="))
        assertEquals("Café alert", Mime.decodeHeader("=?ISO-8859-1?B?Q2Fm6SBhbGVydA==?="))
        assertEquals("ab", Mime.decodeHeader("=?UTF-8?Q?a?= =?UTF-8?Q?b?="))
        assertEquals("plain text", Mime.decodeHeader("plain text"))
    }

    @Test
    fun addressOfTakesTheRealAddress_notTheDisplayName() {
        assertEquals("alerts@hdfcbank.net", Mime.addressOf("HDFC Bank InstaAlerts <Alerts@HDFCBank.net>"))
        assertEquals("alerts@hdfcbank.net", Mime.addressOf("\"alerts@hdfcbank.net\" <alerts@hdfcbank.net>"))
        // A display name that looks like an address does not fool it: the address in angle brackets wins.
        assertEquals("evil@example.com", Mime.addressOf("\"alerts@hdfcbank.net\" <evil@example.com>"))
        assertEquals("alerts@hdfcbank.net", Mime.addressOf("alerts@hdfcbank.net"))
        assertEquals("", Mime.addressOf("no address here"))
    }

    @Test
    fun datesAreReadWithTheirOffset() {
        val ms = Mime.parseDate("Sat, 28 Nov 2026 14:35:10 +0530 (IST)")!!
        assertEquals(java.time.Instant.parse("2026-11-28T09:05:10Z").toEpochMilli(), ms)
        assertNull(Mime.parseDate("yesterday"))
    }

    @Test
    fun hostileInputNeverThrows() {
        val deep = "Content-Type: multipart/mixed; boundary=a\r\n\r\n" + "--a\r\nContent-Type: multipart/mixed; boundary=a\r\n\r\n".repeat(50)
        parse(deep)
        parse("")
        parse("\r\n\r\n")
        parse("Content-Type: multipart/mixed\r\n\r\nno boundary")
        parse("Content-Type: text/plain\r\nContent-Transfer-Encoding: base64\r\n\r\n!!!???")
        assertEquals("", parse("Content-Type: image/png\r\n\r\nxxxx").text)
    }

    @Test
    fun headersAreUnfolded_andTheFirstOfARepeatedHeaderWins() {
        val h = Mime.parseHeaders("Subject: one\r\n two\r\nX: a\r\nX: b\r\n")
        assertEquals("one two", h["subject"])
        assertEquals("a", h["x"])
    }
}
