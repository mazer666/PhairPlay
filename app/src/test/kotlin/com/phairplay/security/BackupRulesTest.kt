package com.phairplay.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * BackupRulesTest — proves that nothing of the app leaves the device.
 *
 * WHY: the app holds the long-term Ed25519 identity of the receiver
 * (`shared_prefs/phairplay_prefs.xml` → `pairing_ed25519_seed`) and the public keys of
 * the paired controllers (`shared_prefs/phairplay_pairings.xml`). `android:allowBackup
 * ="false"` switches the **cloud** backup off on every Android version, but the
 * platform documentation is explicit that it does not switch the **device-to-device**
 * transfer off on Android 12 and newer — that one follows the data extraction rules.
 *
 * The attack: the owner replaces the television, the new device is set up from the old
 * one, and the identity of the receiver — the key every AirPlay sender pairs with —
 * is carried over to a machine that has nothing to do with it.
 *
 * WHAT WE TEST:
 * - the data extraction rules exclude every domain from the cloud backup,
 * - the data extraction rules exclude every domain from the device transfer,
 * - the legacy backup rules (Android 11 and older) exclude every domain,
 * - the manifest names both rule files and still switches the backup off.
 */
class BackupRulesTest {

    @Test
    fun `the data extraction rules exclude every domain from the cloud backup`() {
        val domains = excludedDomains(DATA_EXTRACTION_RULES, "cloud-backup")

        assertEquals(
            "the cloud backup does not exclude every domain: $domains",
            ALL_DOMAINS,
            domains
        )
    }

    @Test
    fun `the data extraction rules exclude every domain from the device transfer`() {
        val domains = excludedDomains(DATA_EXTRACTION_RULES, "device-transfer")

        assertEquals(
            "the device transfer does not exclude every domain: $domains",
            ALL_DOMAINS,
            domains
        )
    }

    @Test
    fun `the legacy backup rules exclude every domain`() {
        val domains = excludedDomains(BACKUP_RULES, null)

        assertEquals(
            "the legacy backup rules do not exclude every domain: $domains",
            ALL_LEGACY_DOMAINS,
            domains
        )
    }

    @Test
    fun `the manifest names both rule files and switches the backup off`() {
        val manifest = readFile(MANIFEST)

        assertTrue(
            "the manifest does not name the data extraction rules",
            manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\"")
        )
        assertTrue(
            "the manifest does not name the legacy backup rules",
            manifest.contains("android:fullBackupContent=\"@xml/backup_rules\"")
        )
        assertTrue(
            "the manifest does not switch the backup off",
            manifest.contains("android:allowBackup=\"false\"")
        )
    }

    @Test
    fun `the rule files exist`() {
        assertTrue("the data extraction rules are missing", repoFile(DATA_EXTRACTION_RULES).isFile)
        assertTrue("the legacy backup rules are missing", repoFile(BACKUP_RULES).isFile)
    }

    // ── The helpers ─────────────────────────────────────────────────────────

    /**
     * Reads the `domain` attribute of every `exclude` element below [section].
     *
     * @param relativePath the path of the rule file below the repository root.
     * @param section the name of the section, or null for the legacy format where the
     *   `exclude` elements are at the top level.
     */
    private fun excludedDomains(relativePath: String, section: String?): Set<String> {
        val document = parse(repoFile(relativePath))
        val scope: Element = if (section == null) {
            document.documentElement
        } else {
            val found = document.getElementsByTagName(section)
            assertTrue("the section $section is missing in $relativePath", found.length == 1)
            found.item(0) as Element
        }
        val excludes = scope.getElementsByTagName("exclude")
        val domains = mutableSetOf<String>()
        for (index in 0 until excludes.length) {
            val element = excludes.item(index) as Element
            domains += element.getAttribute("domain")
        }
        return domains
    }

    /** Parses an XML file without resolving anything outside it. */
    private fun parse(file: File): Document {
        val factory = DocumentBuilderFactory.newInstance()
        // The file is part of the repository and is not fetched from anywhere, but a
        // parser that resolves external entities has no business in a security test.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        return factory.newDocumentBuilder().parse(file)
    }

    /** Reads a file of the repository. */
    private fun readFile(relativePath: String): String = repoFile(relativePath).readText()

    /**
     * Finds a file of the repository.
     *
     * WHY the search upwards: the test runs with the project directory as the working
     * directory — the `app` module in the app build, the `test-runner` directory in the
     * JVM runner. Walking up until the file appears works in both cases and does not
     * depend on a system property that the build would have to set.
     */
    private fun repoFile(relativePath: String): File {
        var directory: File? = File(System.getProperty("user.dir")).absoluteFile
        while (directory != null) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        throw AssertionError(
            "the file $relativePath was not found below ${System.getProperty("user.dir")}"
        )
    }

    companion object {
        private const val MANIFEST = "app/src/main/AndroidManifest.xml"
        private const val DATA_EXTRACTION_RULES = "app/src/main/res/xml/data_extraction_rules.xml"
        private const val BACKUP_RULES = "app/src/main/res/xml/backup_rules.xml"

        /** Every domain the data extraction rules have to exclude. */
        private val ALL_DOMAINS = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref"
        )

        /** Every domain the legacy backup rules have to exclude. */
        private val ALL_LEGACY_DOMAINS = setOf("root", "file", "database", "sharedpref", "external")
    }
}
