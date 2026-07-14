package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmbeddedRuntimeMetadataParserTest {
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
          "schemaVersion":1,
          "architecture":"aarch64",
          "repository":"https://packages.termux.dev/apt/termux-main",
          "indexUrl":"https://packages.termux.dev/Packages",
          "indexSha256":"${"56".repeat(32)}",
          "rootPackages":["python"],
          "totalSize":100,
          "packages":[{
            "name":"python","version":"3.14.6-1","architecture":"aarch64",
            "file":"python_3.14.6-1_aarch64.deb","sha256":"${"78".repeat(32)}","size":100,
            "description":"Python","homepage":"https://python.org","sourcePackage":"python",
            "recipeUrl":"https://example.invalid/build.sh","license":"custom"
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
    }

    @Test
    fun requiresArm64AsPrimaryAbiInsteadOfTranslationFallback() {
        assertTrue(supportsEmbeddedRuntime("arm64-v8a", listOf("arm64-v8a")))
        assertFalse(supportsEmbeddedRuntime("x86_64", listOf("arm64-v8a")))
    }
}
