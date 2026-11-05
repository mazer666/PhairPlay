package com.phairplay.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LogReductionCoverageTest — proves that every log statement of the app goes through
 * the central reduction.
 *
 * WHY: `com.phairplay.util.Logger` passes every message through
 * `com.phairplay.util.LogRedactor`, which removes the addresses, the MAC addresses,
 * the UUIDs and the registered personal strings. A log statement that calls `Timber`
 * directly bypasses that reduction — and the guarantee "a log excerpt that a user
 * pastes into a public issue tracker carries no address of the household" would be
 * false again.
 *
 * No log statement of the app logs an identifier today, so the current exposure is
 * nil. The exposure is the *next* developer who writes `Timber.i("client $ip")` in a
 * file nobody reviews. This test is the guard against it.
 *
 * WHAT WE TEST:
 * - no file outside `util/Logger.kt` calls `Timber` directly,
 * - the only permitted direct use is `Timber.plant(…)`, which installs the tree and
 *   writes nothing.
 */
class LogReductionCoverageTest {

    @Test
    fun `no source file calls Timber directly`() {
        val offenders = mutableListOf<String>()

        sourceFiles().forEach { file ->
            val relative = file.relativeTo(repoRoot()).path
            if (relative.endsWith("com/phairplay/util/Logger.kt")) return@forEach
            file.readLines().forEachIndexed { index, line ->
                val code = codeOf(line)
                if (!code.contains("Timber.")) return@forEachIndexed
                // `Timber.plant(...)` installs the tree — it writes nothing.
                if (code.contains("Timber.plant(")) return@forEachIndexed
                offenders += "$relative:${index + 1}"
            }
        }

        assertEquals(
            "these files call Timber directly and bypass the central reduction: $offenders",
            emptyList<String>(),
            offenders
        )
    }

    @Test
    fun `the logger itself is the only place that talks to Timber`() {
        val logger = File(repoRoot(), "app/src/main/kotlin/com/phairplay/util/Logger.kt")

        assertTrue("the logger is missing", logger.isFile)
        assertTrue(
            "the logger does not talk to Timber",
            logger.readText().contains("Timber.")
        )
    }

    // ── The helpers ─────────────────────────────────────────────────────────

    /**
     * The code of a source line — the comments are removed.
     *
     * WHY: a KDoc header may *mention* Timber ("a sanitised copy that can be handed
     * to Timber"), and such a mention is a comment, not a call.
     */
    private fun codeOf(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) return ""
        return line.substringBefore("//")
    }

    /** Every Kotlin file of the production source set. */
    private fun sourceFiles(): List<File> {
        val main = File(repoRoot(), "app/src/main/kotlin")
        assertTrue("the production source set is missing", main.isDirectory)
        return main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * The root of the repository.
     *
     * WHY the search upwards: the test runs with the project directory as the working
     * directory — the `app` module in the app build, the `test-runner` directory in the
     * JVM runner. Walking up until the marker appears works in both cases.
     */
    private fun repoRoot(): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            if (File(directory, "app/src/main/kotlin").isDirectory) return directory
            directory = directory.parentFile
        }
        throw AssertionError(
            "the repository root was not found below ${System.getProperty("user.dir")}"
        )
    }
}
