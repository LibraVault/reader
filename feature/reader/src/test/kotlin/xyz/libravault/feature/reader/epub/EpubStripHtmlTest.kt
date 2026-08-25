package xyz.libravault.feature.reader.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    // ── Block-boundary newlines (#630) ───────────────────────────────────────

    @Test
    fun `paragraphs are separated by a newline`() {
        val out = stripHtml("<p>First para.</p><p>Second para.</p>")
        assertNotNull(out)
        assertEquals("First para.\nSecond para.", out!!.trim())
    }

    @Test
    fun `issue repro- paragraphs, inline formatting, blockquote and hr all produce line boundaries`() {
        val html = "<p>First para.</p><p>Second para.</p>" +
            "<p><em>emph</em> text and <b>bold</b> and <blockquote>a quote</blockquote></p>" +
            "<hr/><p>After rule</p>"
        val out = stripHtml(html)
        assertNotNull(out)
        // Per HTML5 tree-construction rules, a <blockquote> implicitly closes
        // an open <p> (it's in the "special" tag list that does this), so
        // Jsoup's parser makes it a sibling of the third <p>, not a child —
        // each still gets its own line.
        val lines = out!!.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(
            listOf("First para.", "Second para.", "emph text and bold and", "a quote", "After rule"),
            lines,
        )
    }

    @Test
    fun `headings introduce a newline`() {
        val out = stripHtml("<h1>Chapter One</h1><p>Body text.</p>")
        assertNotNull(out)
        assertEquals("Chapter One\nBody text.", out!!.trim())
    }

    @Test
    fun `list items each get their own line`() {
        val out = stripHtml("<ul><li>One</li><li>Two</li><li>Three</li></ul>")
        assertNotNull(out)
        val lines = out!!.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("One", "Two", "Three"), lines)
    }

    @Test
    fun `br introduces a newline without a surrounding block`() {
        val out = stripHtml("Line one<br>Line two")
        assertNotNull(out)
        val lines = out!!.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("Line one", "Line two"), lines)
    }

    @Test
    fun `inline formatting alone does not introduce a newline`() {
        val out = stripHtml("<p>Hello <b>bold</b> and <i>italic</i> world</p>")
        assertNotNull(out)
        assertEquals("Hello bold and italic world", out!!.trim())
    }

    @Test
    fun `stripHtml output lets EpubTextPreprocessor actually remove a scene break separator`() {
        // The regression this issue describes end-to-end: a scene-break line
        // between two paragraphs must survive as its own line so
        // EpubTextPreprocessor's per-line regex can anchor on it and strip it.
        val html = "<p>End of chapter.</p><p>***</p><p>Next chapter begins.</p>"
        val stripped = stripHtml(html)
        assertNotNull(stripped)
        val cleaned = EpubTextPreprocessor.clean(stripped!!)
        assertFalse(cleaned.contains("*"), "scene-break separator should have been stripped, got: $cleaned")
        assertTrue(cleaned.contains("End of chapter."))
        assertTrue(cleaned.contains("Next chapter begins."))
    }
}