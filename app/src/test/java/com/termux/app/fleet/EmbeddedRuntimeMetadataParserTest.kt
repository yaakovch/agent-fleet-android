package com.termux.app.fleet

import android.annotation.SuppressLint
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
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

    @Test
    @SuppressLint("NewApi")
    fun installedRuntimeVerificationRejectsChangedReleaseBytes() {
        val root = Files.createTempDirectory("agent-fleet-installed-runtime").toFile()
        val runtimeRoot = File(root, "wtmux")
        val version = "git-abcdef0"
        val release = File(runtimeRoot, "releases/$version")
        try {
            val script = File(release, "scripts/wtmux").apply {
                parentFile!!.mkdirs()
                writeText("safe")
                Files.setPosixFilePermissions(parentFile!!.toPath(), PosixFilePermissions.fromString("rwx------"))
                Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
            }
            Files.setPosixFilePermissions(release.toPath(), PosixFilePermissions.fromString("rwx------"))
            fun checksum(file: File): String = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val manifest = JSONObject()
                .put("formatVersion", 2)
                .put("version", version)
                .put(
                    "components",
                    JSONObject()
                        .put("clientRuntime", JSONObject().put("sequence", 7).put("version", version))
                        .put("hostRuntime", JSONObject().put("sequence", 6).put("version", version))
                        .put("providerAdapters", JSONObject().put("sequence", 5).put("version", version))
                        .put("contracts", JSONObject().put("sequence", 4).put("version", "1.0.0"))
                )
                .put(
                    "source",
                    JSONObject()
                        .put("schemaVersion", 1)
                        .put("repository", "https://github.com/yaakovch/wtmux")
                        .put("commit", "abcdef0".padEnd(40, '0'))
                        .put("license", "MIT")
                        .put("contractPackageVersion", "1.0.0")
                )
                .put(
                    "target",
                    JSONObject()
                        .put("platform", "termux")
                        .put("architecture", "arm64")
                        .put("prefix", runtimeRoot.absolutePath)
                )
                .put("files", JSONArray().put(
                    JSONObject()
                        .put("path", "scripts/wtmux")
                        .put("sha256", checksum(script))
                        .put("size", script.length())
                        .put("mode", 493)
                ))
            val manifestFile = File(release, "runtime-manifest.json").apply {
                writeText(manifest.toString())
                Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rw-------"))
            }
            val expectedManifestSha256 = checksum(manifestFile)

            assertEquals(
                expectedManifestSha256,
                verifyInstalledRuntimeRelease(release, version, expectedManifestSha256)
            )
            manifest.getJSONObject("source").put("license", "NOASSERTION")
            manifestFile.writeText(manifest.toString())
            val legacyManifestSha256 = checksum(manifestFile)
            assertEquals(
                legacyManifestSha256,
                verifyInstalledRuntimeRelease(release, version, legacyManifestSha256)
            )
            manifest.getJSONObject("source").put("license", "MIT")
            manifestFile.writeText(manifest.toString())
            manifest.getJSONObject("target").put("architecture", "universal")
            manifestFile.writeText(manifest.toString())
            val universalManifestSha256 = checksum(manifestFile)
            assertEquals(
                universalManifestSha256,
                verifyInstalledRuntimeRelease(release, version, universalManifestSha256)
            )
            manifest.getJSONObject("target").put("architecture", "arm64")
            manifestFile.writeText(manifest.toString())
            Files.setPosixFilePermissions(manifestFile.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
            assertThrows(IllegalArgumentException::class.java) {
                verifyInstalledRuntimeRelease(release, version, expectedManifestSha256)
            }
            Files.setPosixFilePermissions(manifestFile.toPath(), PosixFilePermissions.fromString("rw-------"))
            Files.setPosixFilePermissions(script.parentFile!!.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
            assertThrows(IllegalArgumentException::class.java) {
                verifyInstalledRuntimeRelease(release, version, expectedManifestSha256)
            }
            Files.setPosixFilePermissions(script.parentFile!!.toPath(), PosixFilePermissions.fromString("rwx------"))

            script.writeText("evil")
            assertThrows(IllegalArgumentException::class.java) {
                verifyInstalledRuntimeRelease(release, version, expectedManifestSha256)
            }

            script.writeText("safe")
            File(release, "scripts/json.py").writeText("raise SystemExit('unlisted')")
            assertThrows(IllegalArgumentException::class.java) {
                verifyInstalledRuntimeRelease(release, version, expectedManifestSha256)
            }
            File(release, "scripts/json.py").delete()

            manifest.getJSONObject("source").put("repository", "https://example.invalid/wtmux")
            manifestFile.writeText(manifest.toString())
            assertThrows(IllegalArgumentException::class.java) {
                verifyInstalledRuntimeRelease(release, version, checksum(manifestFile))
            }

            manifest.getJSONObject("source").put("repository", "https://github.com/yaakovch/wtmux")
            manifest.getJSONObject("source").put("license", "GPL-3.0-only")
            manifestFile.writeText(manifest.toString())
            assertThrows(IllegalArgumentException::class.java) {
                verifyInstalledRuntimeRelease(release, version, checksum(manifestFile))
            }

            manifest.getJSONObject("source").put("license", "MIT")
            manifest.getJSONArray("files").getJSONObject(0)
                .put("sha256", checksum(script.apply { writeText("rewritten") }))
                .put("size", script.length())
            manifestFile.writeText(manifest.toString())
            assertThrows(IllegalArgumentException::class.java) {
                verifyInstalledRuntimeRelease(release, version, expectedManifestSha256)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun runtimeExecutionAdmissionFailsBeforeUnverifiedFilesCanRun() {
        assertEquals("git-safe", admittedRuntimeExecutionTarget("git-safe") { true })
        assertThrows(IllegalArgumentException::class.java) {
            admittedRuntimeExecutionTarget("git-corrupt") { false }
        }
        assertThrows(IllegalArgumentException::class.java) {
            admittedRuntimeExecutionTarget("") { true }
        }
    }

    private val descriptor = """
        {
          "schemaVersion":1,
          "baselineVersion":"git-abcdef0",
          "sourceRepository":"https://github.com/yaakovch/wtmux",
          "wtmuxCommit":"abcdef0123456789abcdef0123456789abcdef01",
          "contractPackageVersion":"1.3.0",
          "components":{
            "clientRuntime":{"sequence":45,"version":"git-abcdef0"},
            "hostRuntime":{"sequence":38,"version":"git-abcdef0"},
            "providerAdapters":{"sequence":13,"version":"git-abcdef0"},
            "contracts":{"sequence":13,"version":"1.3.0"}
          },
          "protocolVersion":2,
          "supportedAbis":["arm64-v8a"],
          "runtime":{
            "file":"runtime.tar","sha256":"${"ab".repeat(32)}","size":123,
            "formatVersion":2,"manifestSha256":"${"bd".repeat(32)}",
            "sbomSha256":"${"bc".repeat(32)}","licenseSha256":"${"de".repeat(32)}"
          },
          "registry":{"file":"registry.tar","sha256":"${"ac".repeat(32)}","size":321},
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

    private val closurePackages = packages
        .replace(
            "\"rootPackages\":[\"python\"],",
            """
            "rootPackages":["python"],
            "runtimeRootsSha256":"${"89".repeat(32)}",
            "closureManifestFile":"agent-fleet-runtime-closure-v1.json",
            "closureManifestSha256":"${"9a".repeat(32)}",
            "closureManifestSize":321,
            """.trimIndent()
        )
        .replace(
            "\"recipe\":\"packages/python/build.sh\",\"license\":\"custom\"",
            """
            "recipe":"packages/python/build.sh","license":"custom",
            "essential":true,"preDepends":[],"depends":[],"provides":[],"resolvedDependencies":[]
            """.trimIndent()
        )

    @Test
    fun parsesCrossRepositoryDescriptorAndPackageFloors() {
        val value = EmbeddedRuntimeMetadataParser.descriptor(descriptor)
        assertEquals("git-abcdef0", value.baselineVersion)
        assertEquals(45L, value.components.getValue("clientRuntime").sequence)
        assertEquals("1.3.0", value.contractPackageVersion)
        assertEquals(2, value.protocolVersion)
        assertEquals("registry.tar", value.registry.file)
        assertEquals("12".repeat(16), value.trustedRuntimeKeys.single().keyId)
        assertEquals("python", EmbeddedRuntimeMetadataParser.packages(packages).single().name)
    }

    @Test
    fun acceptsCompleteClosureAttestationAndRejectsPartialMetadata() {
        val value = EmbeddedRuntimeMetadataParser.packages(closurePackages).single()
        assertTrue(value.essential)
        assertEquals(emptyList<String>(), value.resolvedDependencies)

        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedRuntimeMetadataParser.packages(
                closurePackages.replace(
                    "\"closureManifestSize\":321,",
                    ""
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedRuntimeMetadataParser.packages(
                closurePackages.replace(
                    "\"provides\":[],",
                    ""
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedRuntimeMetadataParser.packages(
                closurePackages.replace(
                    "\"resolvedDependencies\":[]",
                    "\"resolvedDependencies\":[\"missing\"]"
                )
            )
        }
    }

    @Test
    fun acceptsCanonicalVersionedDependenciesAndProvidesAcrossRuntimeRepositories() {
        val root = JSONObject(closurePackages)
        val template = root.getJSONArray("packages").getJSONObject(0)
        val app = JSONObject(template.toString())
            .put("name", "app")
            .put("version", "1.0")
            .put("file", "app_1.0_aarch64.deb")
            .put("sha256", "12".repeat(32))
            .put("sourcePackage", "app")
            .put("recipe", "packages/app/build.sh")
            .put(
                "depends",
                JSONArray()
                    .put(JSONArray().put("provider:any (>= 1.2) [aarch64]").put("fallback"))
                    .put(JSONArray().put("virtual-api (>= 2.0)"))
            )
            .put("resolvedDependencies", JSONArray().put("provider"))
        val provider = JSONObject(template.toString())
            .put("name", "provider")
            .put("version", "2.1")
            .put("file", "provider_2.1_aarch64.deb")
            .put("sha256", "34".repeat(32))
            .put("sourcePackage", "provider")
            .put("recipe", "packages/provider/build.sh")
            .put("essential", false)
            .put("multiArch", "allowed")
            .put("provides", JSONArray().put("virtual-api (= 2.1)"))
        root.put("rootPackages", JSONArray().put("app"))
            .put("totalSize", 200)
            .put("packages", JSONArray().put(app).put(provider))

        val values = EmbeddedRuntimeMetadataParser.packages(root.toString())

        assertEquals(listOf("provider"), values.first { it.name == "app" }.resolvedDependencies)
        assertEquals(
            listOf("virtual-api (= 2.1)"),
            values.first { it.name == "provider" }.provides
        )
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedRuntimeMetadataParser.packages(
                root.toString().replace(
                    "provider:any (>= 1.2) [aarch64]",
                    "provider (=> 1.2)"
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedRuntimeMetadataParser.packages(
                root.toString().replace("provider:any", "provider:native")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedRuntimeMetadataParser.packages(
                root.toString().replace("\"multiArch\":\"allowed\"", "\"multiArch\":\"same\"")
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            val invalid = JSONObject(root.toString())
            invalid.getJSONArray("packages").getJSONObject(1).put("version", "1.0")
            EmbeddedRuntimeMetadataParser.packages(invalid.toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            EmbeddedRuntimeMetadataParser.packages(
                root.toString().replace("virtual-api (= 2.1)", "virtual-api (= 1.0)")
            )
        }
    }

    @Test
    fun comparesDebianEpochTildeRevisionAndNumericSegmentsLikeDpkg() {
        val ordered = listOf(
            "1.0~rc1-1" to "1.0-1",
            "1.0-1" to "1.0-2",
            "1.0-2" to "1.0-10",
            "2.0-99" to "1:1.0-1"
        )
        ordered.forEach { (lower, higher) ->
            assertTrue("$lower must sort before $higher", compareDebianVersions(lower, higher) < 0)
            assertTrue("$higher must sort after $lower", compareDebianVersions(higher, lower) > 0)
        }
        assertEquals(0, compareDebianVersions("1:01.002-03", "1:1.2-3"))
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
        assertTrue(shouldInstallEmbeddedBaseline(status.copy(baseline = "git-new0000"), explicitRepair = false))
        assertFalse(shouldInstallEmbeddedBaseline(
            status.copy(baseline = "git-new0000", repairNeeded = false),
            explicitRepair = false
        ))
        assertTrue(shouldRestorePreservedRuntime(true, "git-hotfix0", status.copy(
            baseline = "git-new0000", current = "git-new0000", previous = "git-hotfix0"
        )))
        assertFalse(shouldRestorePreservedRuntime(false, "git-hotfix0", status))
    }

    @Test
    fun requiresThePackagedRegistryBindingBeforeTheManagedLoader() {
        val registry = "/data/data/com.yaakovch.fleet/files/home/.local/share/agent-fleet/wtmux/registry/current/machines"
        val valid = """
            # BEGIN wtmux-runtime registry
            WTMUX_SHARED_REGISTRY_DIR='$registry'
            # END wtmux-runtime registry
            # BEGIN wtmux-managed shared-registry
            loader
            # END wtmux-managed shared-registry
        """.trimIndent()
        assertTrue(embeddedRegistryBindingIsCurrent(valid, registry))
        assertFalse(embeddedRegistryBindingIsCurrent(valid.replace(registry, "/stale/registry"), registry))
        val lines = valid.lines()
        assertFalse(embeddedRegistryBindingIsCurrent(
            (lines.drop(3) + lines.take(3)).joinToString("\n"),
            registry
        ))
    }
}
