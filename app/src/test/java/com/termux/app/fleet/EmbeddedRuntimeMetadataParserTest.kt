package com.termux.app.fleet

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmbeddedRuntimeMetadataParserTest {

    @Test
    fun identifiesAppOwnedChildrenAsTermuxAndEnablesExecWhenInstalled() {
        val prefix = Files.createTempDirectory("termux-prefix").toFile()
        try {
            val environment = mutableMapOf<String, String>()
            enableTermuxExec(environment, prefix)
            assertEquals("agent-fleet", environment["TERMUX_VERSION"])
            assertFalse(environment.containsKey("LD_PRELOAD"))

            val library = File(prefix, "lib/libtermux-exec.so")
            library.parentFile!!.mkdirs()
            library.createNewFile()
            enableTermuxExec(environment, prefix)
            assertEquals(library.absolutePath, environment["LD_PRELOAD"])

            val inherited = mutableMapOf("TERMUX_VERSION" to "existing-version")
            enableTermuxExec(inherited, prefix)
            assertEquals("existing-version", inherited["TERMUX_VERSION"])
        } finally {
            prefix.deleteRecursively()
        }
    }

    @Test
    fun packageQueryOnlyAcceptsFullyInstalledPackages() {
        val values = installedPackageVersions(
            "bash\t5.3.9-1\tinstall ok installed\n" +
                "python\t3.14.6-1\tinstall ok unpacked\n" +
                "tmux:arm64\t3.7b\tinstall ok installed\n"
        )
        assertEquals(mapOf("bash" to "5.3.9-1", "tmux" to "3.7b"), values)
    }

    @Test
    fun createsTheRuntimeHomeBeforeTheFirstOfflinePackageCheck() {
        val root = Files.createTempDirectory("agent-fleet-first-launch").toFile()
        val home = File(root, "files/home")
        try {
            assertFalse(home.exists())
            assertEquals(home, ensureEmbeddedRuntimeHome(home))
            assertTrue(home.isDirectory)
            assertTrue(home.canRead())
            assertTrue(home.canWrite())
        } finally {
            root.deleteRecursively()
        }
    }

    private val descriptor = """
        {
          "schemaVersion":1,
          "baselineVersion":"git-abcdef0",
          "wtmuxCommit":"abcdef0123456789abcdef0123456789abcdef01",
          "protocolVersion":2,
          "supportedAbis":["arm64-v8a"],
          "runtime":{"file":"runtime.tar","sha256":"${"ab".repeat(32)}","size":123},
          "packageLock":{"file":"packages.json","sha256":"${"cd".repeat(32)}","size":456,"packages":1,"payloadSize":789},
          "sbom":{"file":"packages.spdx.json","sha256":"${"ef".repeat(32)}","size":321},
          "trustedRuntimeKeys":[{"keyId":"${"12".repeat(16)}","file":"key.pem","sha256":"${"34".repeat(32)}"}]
        }
    """.trimIndent()

    private val packages = """
        {
          "schemaVersion":2,
          "applicationId":"com.yaakovch.fleet",
          "prefix":"/data/data/com.yaakovch.fleet/files/usr",
          "architecture":"aarch64",
          "repository":"https://github.com/yaakovch/agent-fleet-termux-packages",
          "bundleUrl":"https://github.com/yaakovch/agent-fleet-termux-packages/releases/download/agent-fleet-runtime-c7ca367-4/agent-fleet-runtime-aarch64.zip",
          "upstreamCommit":"${"56".repeat(20)}",
          "forkCommit":"${"67".repeat(20)}",
          "rootPackages":["python"],
          "totalSize":100,
          "packages":[{
            "name":"python","version":"3.14.6-1","architecture":"aarch64",
            "file":"python_3.14.6-1_aarch64.deb","sha256":"${"78".repeat(32)}","size":100,
            "description":"Python","homepage":"https://python.org/downloads/#source","sourcePackage":"python",
            "recipe":"packages/python/build.sh","license":"custom"
          }]
        }
    """.trimIndent()

    @Test
    fun parsesCrossRepositoryDescriptorAndPackageFloors() {
        val value = EmbeddedRuntimeMetadataParser.descriptor(descriptor)
        assertEquals("git-abcdef0", value.baselineVersion)
        assertEquals(2, value.protocolVersion)
        assertEquals("12".repeat(16), value.trustedRuntimeKeys.single().keyId)
        assertEquals("python", EmbeddedRuntimeMetadataParser.packages(packages).single().name)
    }

    @Test
    fun rejectsUnknownDescriptorFieldsAndUnsafePackageFile() {
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedRuntimeMetadataParser.descriptor(descriptor.replace("\"schemaVersion\":1,", "\"schemaVersion\":1,\"secret\":true,"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedRuntimeMetadataParser.packages(packages.replace("python_3.14.6-1_aarch64.deb", "../python.deb"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedRuntimeMetadataParser.packages(packages.replace("com.yaakovch.fleet", "com.termux"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedRuntimeMetadataParser.packages(packages.replace("agent-fleet-runtime-c7ca367-4", "other-runtime"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedRuntimeMetadataParser.packages(packages.replace("https://python.org", "file:///data/local/tmp/python"))
        }
    }

    @Test
    fun acceptsLegacyHttpHomepageAsInertMetadata() {
        assertEquals(1, EmbeddedRuntimeMetadataParser.packages(packages.replace("https://python.org", "http://python.org")).size)
    }

    @Test
    fun requiresArm64AsPrimaryAbiInsteadOfTranslationFallback() {
        assertTrue(supportsEmbeddedRuntime("arm64-v8a", listOf("arm64-v8a")))
        assertFalse(supportsEmbeddedRuntime("x86_64", listOf("arm64-v8a")))
    }

    @Test
    fun activatesANewApkBaselineWithoutRequiringManualTermuxCommands() {
        val status = EmbeddedRuntimeStatus(
            supported = true, usable = true, repairNeeded = true,
            embeddedBaseline = "git-new0000", baseline = "git-old0000",
            current = "git-hotfix0", previous = "", missingOrOldPackages = 0,
            packageCount = 70, trustedKeyIds = emptyList(), detail = "APK recovery baseline is not installed"
        )
        assertTrue(shouldInstallEmbeddedBaseline(status, explicitRepair = false))
        assertFalse(shouldInstallEmbeddedBaseline(status.copy(baseline = "git-new0000"), explicitRepair = false))
        assertTrue(shouldRestorePreservedRuntime(true, "git-hotfix0", status.copy(
            baseline = "git-new0000", current = "git-new0000", previous = "git-hotfix0"
        )))
        assertFalse(shouldRestorePreservedRuntime(false, "git-hotfix0", status))
    }
}
