package com.pocketpd.app.editor

import android.text.Editable
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * Syntax coloring for the plain-text pd editor, in the strict PC-98 palette:
 * `#N/#X/#A` headers and numbers in dim, the record verb (obj/msg/text/
 * connect…) in edit magenta, `~` classes in signal cyan, control classes and
 * strings in ink, comment payloads in the console edge tone. Line-local like
 * pd itself: a line not starting with '#' is a multi-line comment/array
 * continuation.
 */
object PdTextSpans {

    private const val EDGE = 0xFFA8A8C0.toInt()
    private const val DIM = 0xFF6C6C8A.toInt()
    private const val INK = 0xFFFCFCFC.toInt()
    private const val MAGENTA = 0xFFFC54FC.toInt()
    private const val CYAN = 0xFF54FCFC.toInt()

    /** Verbs that may follow the #N/#X header token. Unknown verbs fall
     *  through to the number/string rules — safe for foreign records. */
    private val VERBS = setOf(
        "canvas", "obj", "msg", "text", "floatatom", "listbox", "symbolatom",
        "connect", "coords", "restore", "array", "graph",
    )

    /** Repaints the whole buffer. pd files are small; a full pass per edit
     *  keeps the rules trivial and the cursor untouched (spans only). */
    fun apply(text: Editable) {
        for (s in text.getSpans(0, text.length, ForegroundColorSpan::class.java)) {
            text.removeSpan(s)
        }
        val s = text.toString()
        var lineStart = 0
        while (lineStart <= s.length) {
            val nl = s.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) s.length else nl
            paintLine(text, s, lineStart, lineEnd)
            if (nl < 0) break
            lineStart = nl + 1
        }
    }

    private fun paintLine(text: Editable, s: String, start: Int, end: Int) {
        var i = start
        while (i < end && s[i].isWhitespace()) i++
        if (i >= end) return
        if (!s.startsWith("#", i)) {
            // continuation of a multi-line #X text payload / #A data block
            span(text, EDGE, start, end)
            return
        }

        fun tokenEnd(from: Int): Int {
            var j = from
            while (j < end && !s[j].isWhitespace()) j++
            return j
        }
        fun skipSpaces(from: Int): Int {
            var j = from
            while (j < end && s[j].isWhitespace()) j++
            return j
        }

        val headerEnd = tokenEnd(i)
        span(text, EDGE, i, headerEnd) // #N / #X / #A
        i = skipSpaces(headerEnd)
        val verbEnd = tokenEnd(i)
        val verb = if (verbEnd > i) s.substring(i, verbEnd) else ""
        span(text, MAGENTA, i, verbEnd)
        i = skipSpaces(verbEnd)

        when (verb) {
            "obj" -> {
                // x y class args… — class colored, coords dim
                val xEnd = tokenEnd(i); span(text, DIM, i, xEnd); i = skipSpaces(xEnd)
                val yEnd = tokenEnd(i); span(text, DIM, i, yEnd); i = skipSpaces(yEnd)
                val clsEnd = tokenEnd(i)
                if (clsEnd > i) {
                    val cls = s.substring(i, clsEnd)
                    span(text, if (cls.endsWith("~")) CYAN else INK, i, clsEnd)
                    i = skipSpaces(clsEnd)
                }
                spanRest(text, s, i, end, INK)
            }
            "text" -> spanRest(text, s, i, end, EDGE) // comment body
            else -> spanRest(text, s, i, end, INK)
        }
    }

    /** Remaining tokens: `;` and numbers in dim, everything else [base]. */
    private fun spanRest(text: Editable, s: String, from: Int, end: Int, base: Int) {
        var i = from
        while (i < end) {
            var j = i
            while (j < end && !s[j].isWhitespace()) j++
            val tok = s.substring(i, j)
            val color = if (tok == ";" || isNumber(tok)) DIM else base
            span(text, color, i, j)
            i = j
            while (i < end && s[i].isWhitespace()) i++
        }
    }

    private fun isNumber(tok: String): Boolean = tok.toFloatOrNull() != null

    private fun span(text: Editable, color: Int, start: Int, end: Int) {
        if (end > start) {
            text.setSpan(
                ForegroundColorSpan(color), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }
}
