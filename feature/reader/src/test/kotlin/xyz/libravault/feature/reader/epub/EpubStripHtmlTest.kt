package xyz.libravault.feature.reader.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.feature.reader.epub.EpubReaderViewModel.Companion.stripHtml

/**
 * Tests for the [EpubReaderViewModel.stripHtml] static helper.
 *
 * The stripper is fed attacker-controlled HTML (EPUB chapter bodies). The
 * previous regex-based implementation (review finding #10 / WS3.6) was
 * O(n²) on long chapters and mishandled `<style>` containing `<` in CSS
 * comments, `<![CDATA[`, and other edge cases.
 *
 * These tests are pure JVM — Jsoup parses without needing Android runtime.
 *
 * Behaviour note: Jsoup with `Safelist.none()` strips ALL tags and
 * preserves HTML entities as-is in the text. So `&amp;` stays `&amp;`
 * (no re-decoding). That's intentional — it avoids any chance of
 * re-encoding entity-decoded text into TTS-readable but unintended
 * characters. The downstream TTS pipeline handles entity decoding if
 * needed.
 */
class EpubStripHtmlTest {

    @Test
    fun `plain text passes through`() {
        val out = stripHtml("Hello world")
        assertNotNull(out)
        assertEquals("Hello world", out)
    }

    @Test
    fun `simple tags are stripped`() {
        val out = stripHtml("<p>Hello <b>world</b></p>")
        assertNotNull(out)
        assertEquals("Hello world", out!!.trim())
    }

    @Test
    fun `script tag body is removed`() {
        val out = stripHtml("before<script>alert(1)</script>after")
        assertNotNull(out)
        assertTrue(!out!!.contains("alert"), "script body should be stripped: $out")
        assertTrue(out.contains("before") && out.contains("after"))
    }

    @Test
    fun `style tag body is removed`() {
        val out = stripHtml("before<style>body { color: red }</style>after")
        assertNotNull(out)
        assertTrue(!out!!.contains("color"), "CSS body should be stripped: $out")
        assertTrue(out.contains("before") && out.contains("after"))
    }

    @Test
    fun `CDATA inside a paragraph is preserved as text`() {
        // Jsoup.parse treats <![CDATA[...]]> inside an element as its
        // text content. .text() returns it with entities decoded.
        // This is fine for TTS — CDATA is by definition not parsed as HTML,
        // so any character inside it is safe to read.
        val out = stripHtml("<p><![CDATA[some <data> here]]></p>")
        assertNotNull(out)
        assertTrue(out!!.contains("some"),  "CDATA prefix kept: $out")
        assertTrue(out.contains("here"),   "CDATA suffix kept: $out")
        assertTrue(out.contains("data"),   "CDATA inner text kept: $out")
    }

    @Test
    fun `html entities are decoded by Jsoup parse text`() {
        // Jsoup.parse(html).text() decodes HTML entities into their
        // characters — that's what TTS expects (read "ampersand" not "&amp;").
        val out = stripHtml("&amp; &lt; &gt; &quot;")
        assertNotNull(out)
        assertTrue(out!!.contains("&"),     "amp decoded: $out")
        assertTrue(out.contains("<"),       "lt decoded: $out")
        assertTrue(out.contains(">"),       "gt decoded: $out")
        assertTrue(out.contains("\""),      "quot decoded: $out")
    }

    @Test
    fun `nbsp entity is decoded to a space`() {
        val out = stripHtml("a&nbsp;b")
        assertNotNull(out)
        assertEquals("a b", out!!.trim())
    }

    @Test
    fun `style block with HTML-like comment inside is fully stripped`() {
        // The OLD regex stripper would have failed here because [^>]+ is
        // greedy across the comment. Jsoup parses the comment properly.
        val html = """
            <style>
            /* <oops> this looks like a tag inside CSS */
            .foo { color: red }
            </style>
            <p>visible</p>
        """.trimIndent()
        val out = stripHtml(html)
        assertNotNull(out)
        assertTrue(!out!!.contains("oops"), "CSS comment body should not leak: $out")
        assertTrue(out.contains("visible"))
    }

    @Test
    fun `chapter exceeding 2 MB cap returns null`() {
        val huge = "a".repeat(3 * 1024 * 1024)
        assertNull(stripHtml(huge), "oversize chapter should be refused")
    }

    @Test
    fun `chapter at exactly 2 MB is accepted`() {
        val at = "a".repeat(2 * 1024 * 1024)
        val out = stripHtml(at)
        assertNotNull(out, "2 MB cap should be inclusive")
        assertEquals(2 * 1024 * 1024, out!!.length)
    }

    @Test
    fun `chapter just over 2 MB is rejected`() {
        val over = "a".repeat(2 * 1024 * 1024 + 1)
        assertNull(stripHtml(over))
    }

    @Test
    fun `empty input returns empty string not null`() {
        // Empty is valid; just empty. null is reserved for over-cap / parse
        // failure.
        val out = stripHtml("")
        assertNotNull(out)
        assertEquals("", out)
    }

    @Test
    fun `nested tags are stripped correctly`() {
        val out = stripHtml("<div><span><b>deep</b></span></div>")
        assertNotNull(out)
        assertEquals("deep", out!!.trim())
    }

    @Test
    fun `iframe and object tags have content stripped`() {
        val out = stripHtml("safe<iframe src='evil.com'>iframed content</iframe>end")
        assertNotNull(out)
        assertTrue(out!!.contains("safe"))
        assertTrue(out.contains("end"))
        assertTrue(!out.contains("iframed content"), "iframe body must be stripped: $out")
    }

    @Test
    fun `html comment is removed`() {
        val out = stripHtml("before<!-- secret -->after")
        assertNotNull(out)
        assertTrue(!out!!.contains("secret"), "comment body must not leak: $out")
        assertTrue(out.contains("before") && out.contains("after"))
    }

    @Test
    fun `svg with onload handler is neutralised`() {
        val out = stripHtml("safe<svg onload='alert(1)'></svg>end")
        assertNotNull(out)
        assertTrue(!out!!.contains("alert"), "svg onload must be neutralised: $out")
    }

    @Test
    fun `multi-megabyte run of plain text is accepted up to 2 MB then rejected`() {
        // Build a string with very few tags and lots of plain text.
        val builder = StringBuilder()
        for (i in 0 until 2_100_000) builder.append("word ")
        val out = stripHtml(builder.toString())
        assertNull(out, "2.1 MB of plain text should be rejected")
    }

    // MARK: - Block boundaries survive as newlines (#630)

    @Test
    fun `paragraph boundaries become newlines instead of collapsing to a space`() {
        val out = stripHtml("<p>First para.</p><p>Second para.</p>")
        assertNotNull(out)
        assertEquals("First para.\nSecond para.", out!!.trim())
    }

    @Test
    fun `hr scene break survives as its own line`() {
        // This is the exact structural cue
        // EpubTextPreprocessor.removeDecorativeSeparators needs — without a
        // line boundary around it, a decorative separator can never match on
        // real multi-paragraph content (#630).
        val out = stripHtml("<p>Before rule</p><hr/><p>After rule</p>")
        assertNotNull(out)
        val lines = out!!.trim().lines()
        assertTrue(lines.contains("Before rule"), "line-anchored content survives: $lines")
        assertTrue(lines.contains("After rule"), "line-anchored content survives: $lines")
    }

    @Test
    fun `heading and blockquote boundaries also produce newlines`() {
        val out = stripHtml("<h1>Chapter One</h1><p>Body text.</p><blockquote>A quote.</blockquote><p>More body.</p>")
        assertNotNull(out)
        val lines = out!!.trim().lines()
        assertEquals(listOf("Chapter One", "Body text.", "A quote.", "More body."), lines)
    }

    @Test
    fun `inline emphasis tags do not force a line break`() {
        // em/b/i/strong etc. aren't in LINE_BREAK_TAGS — they're inline
        // emphasis, not structural boundaries, so they should stay on the
        // same line as their surrounding text.
        val out = stripHtml("<p><em>emph</em> text and <b>bold</b> text</p>")
        assertNotNull(out)
        assertEquals("emph text and bold text", out!!.trim())
    }

    @Test
    fun `explicit br line break is preserved`() {
        val out = stripHtml("Line one<br/>Line two")
        assertNotNull(out)
        assertEquals(listOf("Line one", "Line two"), out!!.trim().lines())
    }

    @Test
    fun `full example from issue 630 round-trips with real line boundaries`() {
        val html = "<p>First para.</p><p>Second para.</p>" +
            "<p><em>emph</em> text and <b>bold</b> and <blockquote>a quote</blockquote></p>" +
            "<hr/><p>After rule</p>"
        val out = stripHtml(html)
        assertNotNull(out)
        // The specific line-count/format isn't the contract — what matters
        // is that every real content fragment is recoverable as its own
        // line-delimited unit, unlike the old doc.text() output which
        // collapsed everything onto a single line with no boundaries at all.
        val lines = out!!.trim().lines().filter { it.isNotBlank() }
        assertTrue(lines.any { it == "First para." })
        assertTrue(lines.any { it == "Second para." })
        assertTrue(lines.any { it.contains("emph text and bold and") })
        assertTrue(lines.any { it == "a quote" })
        assertTrue(lines.any { it == "After rule" })
    }
}