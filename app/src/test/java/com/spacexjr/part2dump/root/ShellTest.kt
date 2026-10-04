package com.spacexjr.part2dump.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShellTest {

    @Test
    fun quoteWrapsInSingleQuotes() {
        assertEquals("'ls -la'", Shell.quote("ls -la"))
        assertEquals("''", Shell.quote(""))
    }

    @Test
    fun quoteEscapesEmbeddedSingleQuotes() {
        assertEquals("'it'\\''s'", Shell.quote("it's"))
        assertEquals("''\\'''", Shell.quote("'"))
        assertEquals("'a'\\''b'\\''c'", Shell.quote("a'b'c"))
    }

    @Test
    fun quoteRoundTripsThroughAPosixShell() {
        val samples = listOf(
            "ls -la",
            "it's",
            "'",
            "",
            "a'b'c",
            "; rm -rf / #",
            "\$(id)",
            "back\\slash",
            "new\nline",
            "trailing space ",
            "acentuação"
        )
        for (sample in samples) {
            assertEquals("falhou para: $sample", sample, shRoundTrip(Shell.quote(sample)))
        }
    }

    @Test
    fun quoteIsSafeAgainstCommandInjection() {
        val sentinel = File.createTempFile("p2d-injection", ".tmp")
        val payload = "'; touch " + sentinel.absolutePath + "; echo 'injetado"
        val target = File.createTempFile("p2d-target", ".tmp")
        try {
            assertTrue(sentinel.delete())
            assertTrue(target.delete())
            val command = "printf '%s' " + Shell.quote(payload) + " > " + Shell.quote(target.absolutePath)
            shRun(command)
            assertFalse("o payload injetado não pode ser executado", sentinel.exists())
            assertEquals(payload, target.readText())
        } finally {
            sentinel.delete()
            target.delete()
        }
    }

    @Test
    fun uidPatternReadsPlainUid() {
        assertEquals(0, Shell.uidPattern("0"))
        assertEquals(0, Shell.uidPattern("  0\n"))
        assertEquals(1000, Shell.uidPattern("1000"))
    }

    @Test
    fun uidPatternReadsIdStyleOutput() {
        assertEquals(0, Shell.uidPattern("uid=0(root) gid=0(root) groups=0(root)"))
        assertEquals(10123, Shell.uidPattern("uid=10123(u0_a123) gid=10123(u0_a123)"))
    }

    @Test
    fun uidPatternReadsSuDeniedOutput() {
        assertNull(Shell.uidPattern("permission denied"))
        assertNull(Shell.uidPattern(""))
        assertNull(Shell.uidPattern("   \n  \n"))
    }

    @Test
    fun uidPatternPrefersLeadingUidOverLaterMatches() {
        assertEquals(0, Shell.uidPattern("0\nuid=1000(other)"))
    }

    @Test
    fun directAndSuShellsUseExpectedArgv() {
        assertEquals(listOf("sh", "-c"), shellArgv(Shell.direct()))
        assertEquals(listOf("/system/bin/su", "-c"), shellArgv(Shell.viaSu("/system/bin/su")))
    }

    @Test
    fun suCandidatesCoverCommonLocations() {
        assertTrue(Shell.SU_CANDIDATES.contains("su"))
        assertTrue(Shell.SU_CANDIDATES.contains("/system/bin/su"))
        assertTrue(Shell.SU_CANDIDATES.contains("/system/xbin/su"))
        assertTrue(Shell.SU_CANDIDATES.contains("/debug_ramdisk/su"))
        assertTrue(Shell.SU_CANDIDATES.contains("/magisk/su"))
    }

    @Test
    fun shellResultSuccessRequiresZeroExitAndNoTimeout() {
        assertTrue(ShellResult(0, "", "").isSuccess)
        assertFalse(ShellResult(1, "", "").isSuccess)
        assertFalse(ShellResult(0, "", "", timedOut = true).isSuccess)
    }

    @Test
    fun shellResultOutputHelpersNormaliseLines() {
        val result = ShellResult(0, "  a  \n\n b \n", "err\n")
        assertEquals("a  \n\n b", result.trimmedStdout())
        assertEquals(listOf("a", "b"), result.outputLines())
    }

    private fun shRun(command: String) {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
    }

    private fun shRoundTrip(quoted: String): String {
        val process = ProcessBuilder("sh", "-c", "printf '%s' $quoted").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        return output
    }

    private fun shellArgv(shell: Shell): List<String> {
        val field = Shell::class.java.getDeclaredField("argv")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(shell) as List<String>
    }
}
