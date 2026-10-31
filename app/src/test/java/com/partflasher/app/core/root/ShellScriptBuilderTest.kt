package com.partflasher.app.core.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ShellScriptBuilderTest {

    // ------------------------------------------------------------------ quoting

    @Test
    fun quoteWrapsSimpleValueInSingleQuotes() {
        assertEquals("'abc'", ShellScriptBuilder.quote("abc"))
        assertEquals("''", ShellScriptBuilder.quote(""))
    }

    @Test
    fun quoteKeepsSpacesAndSpecialCharactersLiteral() {
        assertEquals("'hello world'", ShellScriptBuilder.quote("hello world"))
        assertEquals("'a; rm -rf /'", ShellScriptBuilder.quote("a; rm -rf /"))
        assertEquals("'\$(reboot)'", ShellScriptBuilder.quote("\$(reboot)"))
        assertEquals("'`id`'", ShellScriptBuilder.quote("`id`"))
        assertEquals("'a|b&c>d<e'", ShellScriptBuilder.quote("a|b&c>d<e"))
        assertEquals("'glob*?[x]'", ShellScriptBuilder.quote("glob*?[x]"))
        assertEquals("'\"double\"'", ShellScriptBuilder.quote("\"double\""))
        assertEquals("'\\back\\slash'", ShellScriptBuilder.quote("\\back\\slash"))
    }

    @Test
    fun quoteEscapesEmbeddedSingleQuotes() {
        val quoted = ShellScriptBuilder.quote("it's")
        assertEquals("'it'\\''s'", quoted)
    }

    @Test
    fun quoteEscapesInjectionAttemptWithSingleQuoteBreakout() {
        val malicious = "'; rm -rf /; echo '"
        val quoted = ShellScriptBuilder.quote(malicious)
        assertEquals("''\\''; rm -rf /; echo '\\'''", quoted)
    }

    @Test
    fun quoteRejectsNulBytes() {
        try {
            ShellScriptBuilder.quote("bad\u0000value")
            org.junit.Assert.fail("Expected IllegalArgumentException for NUL byte")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun quoteAllJoinsQuotedValues() {
        assertEquals("'a' 'b c' ''", ShellScriptBuilder.quoteAll(listOf("a", "b c", "")))
    }

    @Test
    fun buildJoinsLinesWithNewlines() {
        assertEquals("line1\nline2", ShellScriptBuilder.build("line1", "line2"))
        assertEquals("a\nb", ShellScriptBuilder.build(listOf("a", "b")))
    }

    // -------------------------------------------------- real shell round-trip

    @Test
    fun quotedValuesRoundTripThroughRealShell() {
        val values = listOf(
            "plain",
            "with spaces",
            "it's",
            "double\"quote",
            "\$(touch /tmp/partflasher-pwned)",
            "a; rm -rf /",
            "line1\nline2",
            "`uname -a`",
            "\\back\\slash",
            "a|b&c>d<e",
            "glob*?[x]",
            "\$HOME/*.img",
            "镜像 文件.img",
            "'; reboot; echo '",
        )
        for (value in values) {
            assertEquals("value did not round-trip: $value", value, evalWithSh(ShellScriptBuilder.quote(value)))
        }
        assertFalse("shell injection side effect happened", java.io.File("/tmp/partflasher-pwned").exists())
    }

    private fun evalWithSh(quoted: String): String {
        val process = ProcessBuilder("sh", "-c", "printf '%s' $quoted").start()
        val stdout = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
        process.errorStream.bufferedReader(Charsets.UTF_8).readText()
        process.waitFor()
        return stdout
    }

    private fun assertProcessAvailable() {
        try {
            ProcessBuilder("sh", "-c", "true").start().waitFor()
        } catch (e: IOException) {
            throw org.junit.AssumptionViolatedException("sh is unavailable on this host", e)
        }
    }
}
