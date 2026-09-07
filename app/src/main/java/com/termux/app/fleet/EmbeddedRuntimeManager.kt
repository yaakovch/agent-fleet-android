package com.termux.app.fleet

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

// Linux has exposed O_CLOEXEC with this stable value throughout every Android
// kernel supported by the app, while the SDK constant itself is unavailable
// before API 27.
private const val LINUX_O_CLOEXEC = 0x80000

data class EmbeddedRuntimeFile(val file: String, val sha256: String, val size: Long)

data class EmbeddedWtmuxRuntimeFile(
    val file: String,
    val sha256: String,
    val size: Long,
    val formatVersion: Int,
    val manifestSha256: String,
    val sbomSha256: String,
    val licenseSha256: String
)

data class EmbeddedRuntimeComponent(val sequence: Long, val version: String)

data class EmbeddedRuntimeKey(val keyId: String, val file: String, val sha256: String)

data class LockedTermuxPackage(
    val name: String,
    val version: String,
    val architecture: String,
    val file: String,
    val sha256: String,
    val size: Long,
    val essential: Boolean = false,
    val multiArch: String = "no",
    val preDepends: List<List<String>> = emptyList(),
    val depends: List<List<String>> = emptyList(),
    val provides: List<String> = emptyList(),
    val resolvedDependencies: List<String> = emptyList()
)

data class EmbeddedRuntimeDescriptor(
    val baselineVersion: String,
    val sourceRepository: String,
    val wtmuxCommit: String,
    val contractPackageVersion: String,
    val components: Map<String, EmbeddedRuntimeComponent>,
    val protocolVersion: Int,
    val supportedAbis: List<String>,
    val runtime: EmbeddedWtmuxRuntimeFile,
    val registry: EmbeddedRuntimeFile,
    val packageLock: EmbeddedRuntimeFile,
    val sbom: EmbeddedRuntimeFile,
    val trustedRuntimeKeys: List<EmbeddedRuntimeKey>
)

data class EmbeddedRuntimeStatus(
    val supported: Boolean,
    val usable: Boolean,
    val repairNeeded: Boolean,
    val embeddedBaseline: String,
    val baseline: String,
    val current: String,
    val previous: String,
    val missingOrOldPackages: Int,
    val packageCount: Int,
    val trustedKeyIds: List<String>,
    val detail: String
)

fun supportsEmbeddedRuntime(primaryAbi: String?, supportedAbis: List<String>): Boolean =
    primaryAbi != null && primaryAbi in supportedAbis

internal fun shouldInstallEmbeddedBaseline(status: EmbeddedRuntimeStatus, explicitRepair: Boolean): Boolean =
    explicitRepair || (status.supported && status.repairNeeded)

internal fun embeddedRegistryBindingIsCurrent(config: String, registryMachines: String): Boolean {
    val lines = config.lineSequence().toList()
    val escaped = registryMachines.replace("'", "'\"'\"'")
    val declaration = "WTMUX_SHARED_REGISTRY_DIR='$escaped'"
    val loader = "wtmux_load_shared_registry '$escaped'"
    // Current projections combine the verified binding and loader. They need
    // not have the old runtime-only marker or the APK's bootstrap identity.
    for (label in listOf("wtmux-fleet configuration", "wtmux-runtime registry")) {
        val begin = "# BEGIN $label"
        val end = "# END $label"
        val start = lines.indexOf(begin)
        if (start >= 0 && lines.indexOf(end) == start + 3) {
            return lines.count { it == begin } == 1 && lines.count { it == end } == 1 &&
                lines[start + 1] == declaration && lines[start + 2] == loader &&
                lines.count { it.startsWith("WTMUX_SHARED_REGISTRY_DIR=") } == 1 &&
                lines.count { it.startsWith("wtmux_load_shared_registry ") } == 1
        }
    }
    val runtimeStart = lines.indexOf("# BEGIN wtmux-runtime registry")
    val runtimeEnd = lines.indexOf("# END wtmux-runtime registry")
    val managedStart = lines.indexOf("# BEGIN wtmux-managed shared-registry")
    return runtimeStart >= 0 && runtimeEnd == runtimeStart + 2 &&
        lines.count { it == "# BEGIN wtmux-runtime registry" } == 1 &&
        lines.count { it == "# END wtmux-runtime registry" } == 1 &&
        lines[runtimeStart + 1] == "WTMUX_SHARED_REGISTRY_DIR='$escaped'" &&
        (managedStart < 0 || runtimeEnd < managedStart)
}

internal fun shouldRestorePreservedRuntime(
    preserveCurrent: Boolean,
    previousCurrent: String,
    status: EmbeddedRuntimeStatus
): Boolean = preserveCurrent && previousCurrent.isNotBlank() && previousCurrent != status.current && status.previous == previousCurrent

internal fun verifyInstalledRuntimeRelease(
    release: File,
    expectedVersion: String,
    expectedManifestSha256: String,
    expectedDescriptor: EmbeddedRuntimeDescriptor? = null
): String = inspectInstalledRuntimeRelease(
    release,
    expectedVersion,
    expectedManifestSha256,
    expectedDescriptor
)

private fun inspectInstalledRuntimeRelease(
    release: File,
    expectedVersion: String,
    expectedManifestSha256: String?,
    expectedDescriptor: EmbeddedRuntimeDescriptor? = null
): String {
    require(INSTALLED_RUNTIME_VERSION.matches(expectedVersion)) { "Installed runtime version is invalid" }
    expectedManifestSha256?.let {
        require(INSTALLED_RUNTIME_SHA256.matches(it)) { "Installed runtime manifest checksum is invalid" }
    }
    val parent = requireNotNull(release.parentFile).absoluteFile
    val root = release.absoluteFile
    val rootStat = Os.lstat(root.absolutePath)
    require(
        release.name == expectedVersion &&
            OsConstants.S_ISDIR(rootStat.st_mode) &&
            root.name == expectedVersion &&
            root.parentFile == parent
    ) {
        "Installed runtime release path is unsafe"
    }
    val manifestFile = File(root, "runtime-manifest.json")
    val manifestBytes = manifestFile.readStableRuntimeBytes(
        maximum = MAX_INSTALLED_RUNTIME_MANIFEST_BYTES,
        expectedMode = 384
    )
    val manifestSha256 = manifestBytes.sha256()
    require(expectedManifestSha256 == null || manifestSha256 == expectedManifestSha256) {
        "Installed runtime manifest does not match its verified artifact"
    }
    val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
    require(
        manifest.keys().asSequence().toSet() ==
            setOf("formatVersion", "version", "components", "source", "target", "files") &&
            manifest.getInt("formatVersion") == 2 &&
            manifest.getString("version") == expectedVersion
    ) {
        "Installed runtime manifest identity is invalid"
    }
    validateInstalledRuntimeIdentity(manifest, expectedVersion, root, expectedDescriptor)
    val files = manifest.getJSONArray("files")
    require(files.length() in 1..MAX_INSTALLED_RUNTIME_FILES) { "Installed runtime file list is invalid" }
    val seen = mutableSetOf<String>()
    var totalBytes = 0L
    repeat(files.length()) { index ->
        val entry = files.getJSONObject(index)
        require(entry.keys().asSequence().toSet() == setOf("path", "sha256", "size", "mode")) {
            "Installed runtime file entry is invalid"
        }
        val relative = entry.getString("path")
        require(
            relative.length in 1..MAX_INSTALLED_RUNTIME_PATH_CHARS &&
                !relative.startsWith('/') &&
                '\\' !in relative &&
                relative.split('/').all { it.isNotBlank() && it !in setOf(".", "..") } &&
                seen.add(relative)
        ) { "Installed runtime file path is unsafe" }
        val expectedSize = entry.getLong("size")
        require(
            (entry.get("size") is Int || entry.get("size") is Long) &&
                expectedSize in 0..MAX_INSTALLED_RUNTIME_FILE_BYTES
        ) { "Installed runtime file size is invalid" }
        totalBytes += expectedSize
        require(totalBytes <= MAX_INSTALLED_RUNTIME_TOTAL_BYTES) { "Installed runtime exceeds its verification budget" }
        val expectedSha256 = entry.getString("sha256")
        require(INSTALLED_RUNTIME_SHA256.matches(expectedSha256)) { "Installed runtime file checksum is invalid" }
        val expectedMode = entry.getInt("mode")
        require(expectedMode in setOf(420, 493)) { "Installed runtime file mode is invalid" }
        val file = File(root, relative)
        require(
            file.stableRuntimeSha256(expectedSize, expectedMode) == expectedSha256
        ) { "Installed runtime file integrity check failed: $relative" }
    }
    requireExactInstalledRuntimeTree(root, seen + "runtime-manifest.json")
    return manifestSha256
}

private fun validateInstalledRuntimeIdentity(
    manifest: JSONObject,
    expectedVersion: String,
    release: File,
    expectedDescriptor: EmbeddedRuntimeDescriptor?
) {
    val componentNames = setOf("clientRuntime", "hostRuntime", "providerAdapters", "contracts")
    val components = manifest.getJSONObject("components")
    require(components.keys().asSequence().toSet() == componentNames) {
        "Installed runtime component identity is invalid"
    }
    val parsedComponents = componentNames.associateWith { name ->
        val value = components.getJSONObject(name)
        require(value.keys().asSequence().toSet() == setOf("sequence", "version"))
        EmbeddedRuntimeComponent(
            sequence = value.getLong("sequence").also { require(it > 0) },
            version = value.getString("version").also { require(INSTALLED_RUNTIME_VERSION.matches(it)) }
        )
    }
    require(
        listOf("clientRuntime", "hostRuntime", "providerAdapters")
            .all { parsedComponents.getValue(it).version == expectedVersion }
    ) { "Installed runtime component versions do not match the release" }

    val source = manifest.getJSONObject("source")
    require(
        source.keys().asSequence().toSet() ==
            setOf("schemaVersion", "repository", "commit", "license", "contractPackageVersion") &&
            source.getInt("schemaVersion") == 1 &&
            source.getString("repository") == "https://github.com/yaakovch/wtmux" &&
            INSTALLED_RUNTIME_COMMIT.matches(source.getString("commit")) &&
            expectedVersion == "git-${source.getString("commit").take(7)}" &&
            source.getString("license") in setOf("MIT", "NOASSERTION") &&
            INSTALLED_RUNTIME_VERSION.matches(source.getString("contractPackageVersion")) &&
            parsedComponents.getValue("contracts").version == source.getString("contractPackageVersion")
    ) { "Installed runtime source identity is invalid" }

    val target = manifest.getJSONObject("target")
    val runtimeRoot = requireNotNull(requireNotNull(release.parentFile).parentFile).absolutePath
    fun normalizedUserZeroPath(value: String): String = if (value.startsWith("/data/data/")) {
        "/data/user/0/${value.removePrefix("/data/data/")}"
    } else {
        value
    }
    require(
        target.keys().asSequence().toSet() == setOf("platform", "architecture", "prefix") &&
            target.getString("platform") == "termux" &&
            target.getString("architecture") in setOf("arm64", "universal") &&
            normalizedUserZeroPath(target.getString("prefix")) == normalizedUserZeroPath(runtimeRoot)
    ) { "Installed runtime target identity is invalid" }

    expectedDescriptor?.let { descriptor ->
        require(
            expectedVersion == descriptor.baselineVersion &&
                parsedComponents == descriptor.components &&
                source.getString("repository") == descriptor.sourceRepository &&
                source.getString("commit") == descriptor.wtmuxCommit &&
                source.getString("contractPackageVersion") == descriptor.contractPackageVersion
        ) { "Installed baseline identity does not match the APK descriptor" }
    }
}

private fun File.readStableRuntimeBytes(maximum: Long, expectedMode: Int): ByteArray {
    require(maximum >= 1)
    if (Build.FINGERPRINT == "robolectric") {
        return readStableRuntimeBytesOnJvm(maximum, expectedMode)
    }
    val descriptor = Os.open(
        absolutePath,
        OsConstants.O_RDONLY or LINUX_O_CLOEXEC or OsConstants.O_NOFOLLOW,
        0
    )
    var input: FileInputStream? = null
    try {
        val before = Os.fstat(descriptor)
        require(
            OsConstants.S_ISREG(before.st_mode) &&
                before.st_size in 1..maximum &&
                (before.st_mode and 511) == expectedMode
        ) {
            "Installed runtime file is missing or unsafe: $name " +
                "(mode=${before.st_mode and 511}, size=${before.st_size})"
        }
        input = FileInputStream(descriptor)
        val bytes = input.readBoundedRuntimeBytes(maximum)
        val after = Os.fstat(descriptor)
        require(before.sameRuntimeFile(after) && bytes.size.toLong() == before.st_size) {
            "Installed runtime file changed while it was verified: $name"
        }
        return bytes
    } finally {
        if (input != null) input.close() else Os.close(descriptor)
    }
}

private fun File.stableRuntimeSha256(expectedSize: Long, expectedMode: Int): String {
    if (Build.FINGERPRINT == "robolectric") {
        return stableRuntimeSha256OnJvm(expectedSize, expectedMode)
    }
    val descriptor = Os.open(
        absolutePath,
        OsConstants.O_RDONLY or LINUX_O_CLOEXEC or OsConstants.O_NOFOLLOW,
        0
    )
    var input: FileInputStream? = null
    try {
        val before = Os.fstat(descriptor)
        require(
            OsConstants.S_ISREG(before.st_mode) &&
                before.st_size == expectedSize &&
                (before.st_mode and 511) == expectedMode
        )
        input = FileInputStream(descriptor)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= expectedSize)
            digest.update(buffer, 0, count)
        }
        val after = Os.fstat(descriptor)
        require(total == expectedSize && before.sameRuntimeFile(after)) {
            "Installed runtime file changed while it was verified"
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    } finally {
        if (input != null) input.close() else Os.close(descriptor)
    }
}

@SuppressLint("NewApi") // Robolectric-only host-JDK fallback for its zeroed ShadowLinux fstat.
private fun File.readStableRuntimeBytesOnJvm(maximum: Long, expectedMode: Int): ByteArray {
    val before = toPath().stableJvmRuntimeAttributes()
    require(
        before.isRegularFile &&
            before.size() in 1..maximum &&
            toPath().jvmRuntimeMode() == expectedMode
    ) { "Installed runtime file is missing or unsafe: $name" }
    val bytes = FileInputStream(this).use { it.readBoundedRuntimeBytes(maximum) }
    val after = toPath().stableJvmRuntimeAttributes()
    require(before.sameJvmRuntimeFile(after) && bytes.size.toLong() == before.size()) {
        "Installed runtime file changed while it was verified: $name"
    }
    return bytes
}

@SuppressLint("NewApi") // Robolectric-only host-JDK fallback for its zeroed ShadowLinux fstat.
private fun File.stableRuntimeSha256OnJvm(expectedSize: Long, expectedMode: Int): String {
    val before = toPath().stableJvmRuntimeAttributes()
    require(
        before.isRegularFile &&
            before.size() == expectedSize &&
            toPath().jvmRuntimeMode() == expectedMode
    )
    val digest = FileInputStream(this).use { input ->
        val value = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= expectedSize)
            value.update(buffer, 0, count)
        }
        require(total == expectedSize)
        value.digest().joinToString("") { "%02x".format(it) }
    }
    require(before.sameJvmRuntimeFile(toPath().stableJvmRuntimeAttributes())) {
        "Installed runtime file changed while it was verified"
    }
    return digest
}

@SuppressLint("NewApi") // Called only by the Robolectric fallback above.
private fun java.nio.file.Path.stableJvmRuntimeAttributes(): BasicFileAttributes {
    require(!Files.isSymbolicLink(this)) { "Installed runtime file is a symbolic link" }
    return Files.readAttributes(
        this,
        BasicFileAttributes::class.java,
        LinkOption.NOFOLLOW_LINKS
    )
}

@SuppressLint("NewApi") // Called only by the Robolectric fallback above.
private fun java.nio.file.Path.jvmRuntimeMode(): Int {
    val permissions = Files.getPosixFilePermissions(this, LinkOption.NOFOLLOW_LINKS)
    return permissions.fold(0) { mode, permission ->
        mode or when (permission) {
            PosixFilePermission.OWNER_READ -> 256
            PosixFilePermission.OWNER_WRITE -> 128
            PosixFilePermission.OWNER_EXECUTE -> 64
            PosixFilePermission.GROUP_READ -> 32
            PosixFilePermission.GROUP_WRITE -> 16
            PosixFilePermission.GROUP_EXECUTE -> 8
            PosixFilePermission.OTHERS_READ -> 4
            PosixFilePermission.OTHERS_WRITE -> 2
            PosixFilePermission.OTHERS_EXECUTE -> 1
        }
    }
}

@SuppressLint("NewApi") // Called only by the Robolectric fallback above.
private fun BasicFileAttributes.sameJvmRuntimeFile(other: BasicFileAttributes): Boolean =
    fileKey() == other.fileKey() &&
        size() == other.size() &&
        lastModifiedTime() == other.lastModifiedTime() &&
        isRegularFile == other.isRegularFile

private fun android.system.StructStat.sameRuntimeFile(other: android.system.StructStat): Boolean =
    st_dev == other.st_dev &&
        st_ino == other.st_ino &&
        st_mode == other.st_mode &&
        st_size == other.st_size &&
        st_mtime == other.st_mtime &&
        st_ctime == other.st_ctime

private fun InputStream.readBoundedRuntimeBytes(maximum: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maximum) { "Installed runtime manifest exceeds its safety limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun requireExactInstalledRuntimeTree(root: File, expectedFiles: Set<String>) {
    if (Build.FINGERPRINT == "robolectric") {
        requireExactInstalledRuntimeTreeOnJvm(root, expectedFiles)
        return
    }
    val expectedDirectories = mutableSetOf("")
    expectedFiles.forEach { relative ->
        val parts = relative.split('/')
        for (index in 1 until parts.size) {
            expectedDirectories += parts.take(index).joinToString("/")
        }
    }
    val pending = mutableListOf(root to "")
    val observedFiles = mutableSetOf<String>()
    val observedDirectories = mutableSetOf<String>()
    var visited = 0
    while (pending.isNotEmpty()) {
        val (directory, prefix) = pending.removeAt(pending.lastIndex)
        val directoryStat = Os.lstat(directory.absolutePath)
        require(
            OsConstants.S_ISDIR(directoryStat.st_mode) &&
                (directoryStat.st_mode and 511) == 448 &&
                observedDirectories.add(prefix)
        ) {
            "Installed runtime directory is missing, duplicated, or unsafe: ${prefix.ifEmpty { "." }}"
        }
        val children = requireNotNull(directory.listFiles()) { "Installed runtime tree is unreadable" }
        for (child in children) {
            visited += 1
            require(visited <= MAX_INSTALLED_RUNTIME_TREE_ENTRIES) {
                "Installed runtime tree exceeds its entry budget"
            }
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            val stat = Os.lstat(child.absolutePath)
            when {
                OsConstants.S_ISDIR(stat.st_mode) -> {
                    require(relative in expectedDirectories) {
                        "Installed runtime contains an unlisted directory: $relative"
                    }
                    pending += child to relative
                }
                OsConstants.S_ISREG(stat.st_mode) -> require(
                    relative in expectedFiles && observedFiles.add(relative)
                ) { "Installed runtime contains an unlisted or duplicated file: $relative" }
                else -> error("Installed runtime contains an unsafe entry: $relative")
            }
        }
    }
    require(observedFiles == expectedFiles && observedDirectories == expectedDirectories) {
        "Installed runtime tree changed while it was verified"
    }
}

@SuppressLint("NewApi") // Robolectric-only host-JDK fallback for incomplete ShadowLinux metadata.
private fun requireExactInstalledRuntimeTreeOnJvm(root: File, expectedFiles: Set<String>) {
    val expectedDirectories = mutableSetOf("")
    expectedFiles.forEach { relative ->
        val parts = relative.split('/')
        for (index in 1 until parts.size) {
            expectedDirectories += parts.take(index).joinToString("/")
        }
    }
    val pending = mutableListOf(root to "")
    val observedFiles = mutableSetOf<String>()
    val observedDirectories = mutableSetOf<String>()
    var visited = 0
    while (pending.isNotEmpty()) {
        val (directory, prefix) = pending.removeAt(pending.lastIndex)
        val directoryAttributes = directory.toPath().stableJvmRuntimeAttributes()
        require(
            directoryAttributes.isDirectory &&
                directory.toPath().jvmRuntimeMode() == 448 &&
                observedDirectories.add(prefix)
        ) { "Installed runtime directory is missing, duplicated, or unsafe: ${prefix.ifEmpty { "." }}" }
        requireNotNull(directory.listFiles()).forEach { child ->
            visited += 1
            require(visited <= MAX_INSTALLED_RUNTIME_TREE_ENTRIES)
            val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            val attributes = child.toPath().stableJvmRuntimeAttributes()
            when {
                attributes.isDirectory -> {
                    require(relative in expectedDirectories)
                    pending += child to relative
                }
                attributes.isRegularFile -> require(
                    relative in expectedFiles && observedFiles.add(relative)
                )
                else -> error("Installed runtime contains an unsafe entry: $relative")
            }
        }
    }
    require(observedFiles == expectedFiles && observedDirectories == expectedDirectories) {
        "Installed runtime tree changed while it was verified"
    }
}

private val INSTALLED_RUNTIME_VERSION = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")
private val INSTALLED_RUNTIME_SHA256 = Regex("[a-f0-9]{64}")
private val INSTALLED_RUNTIME_COMMIT = Regex("[a-f0-9]{40}")
private const val MAX_INSTALLED_RUNTIME_MANIFEST_BYTES = 2L * 1024 * 1024
private const val MAX_INSTALLED_RUNTIME_FILES = 4_096
private const val MAX_INSTALLED_RUNTIME_PATH_CHARS = 4_096
private const val MAX_INSTALLED_RUNTIME_FILE_BYTES = 64L * 1024 * 1024
private const val MAX_INSTALLED_RUNTIME_TOTAL_BYTES = 256L * 1024 * 1024
private const val MAX_INSTALLED_RUNTIME_TREE_ENTRIES = 16_384
private const val MAX_RUNTIME_RECEIPT_BYTES = 4L * 1024

internal fun admittedRuntimeExecutionTarget(
    current: String,
    verifier: (String) -> Boolean
): String = current.also {
    require(it.isNotBlank() && verifier(it)) {
        "Refusing to execute an externally unverified runtime"
    }
}

internal fun installedPackageVersions(output: String): Map<String, String> = output.lineSequence().mapNotNull { line ->
    val parts = line.split('\t', limit = 3)
    if (parts.size == 3 && parts[2].trim() == "install ok installed") {
        parts[0].substringBefore(':') to parts[1].trim()
    } else {
        null
    }
}.toMap()

internal fun ensureEmbeddedRuntimeHome(directory: File): File {
    if (!directory.isDirectory) directory.mkdirs()
    require(directory.isDirectory && directory.canRead() && directory.canWrite()) {
        "Agent Fleet home directory is unavailable"
    }
    return directory
}

internal val AGENT_FLEET_IMAGE_TOOL_PACKAGES = linkedMapOf(
    "bash" to "bash",
    "awk" to "gawk",
    "grep" to "grep",
    "sed" to "sed",
    "ssh" to "openssh",
    "python3" to "python",
    "cat" to "coreutils",
    "date" to "coreutils",
    "dirname" to "coreutils",
    "head" to "coreutils",
    "ln" to "coreutils",
    "mkdir" to "coreutils",
    "mktemp" to "coreutils",
    "mv" to "coreutils",
    "readlink" to "coreutils",
    "rm" to "coreutils",
    "sha256sum" to "coreutils",
    "sort" to "coreutils",
    "stat" to "coreutils",
    "tail" to "coreutils",
    "tr" to "coreutils",
    "uname" to "coreutils"
)

internal fun missingAgentFleetImageTools(binDirectory: File): List<String> =
    AGENT_FLEET_IMAGE_TOOL_PACKAGES.keys.filterNot { File(binDirectory, it).canExecute() }

internal fun agentFleetImageToolPackageNames(missingTools: Collection<String>): Set<String> =
    missingTools.mapNotNull(AGENT_FLEET_IMAGE_TOOL_PACKAGES::get).toSet()

/** Implements the ordering used by dpkg for epoch:upstream-revision versions. */
internal fun compareDebianVersions(left: String, right: String): Int {
    fun split(value: String): Triple<String, String, String> {
        require(value.isNotEmpty() && value.length <= 128 && value.none(Char::isWhitespace))
        val colon = value.indexOf(':')
        val epoch = if (colon >= 0) value.substring(0, colon) else "0"
        val remainder = if (colon >= 0) value.substring(colon + 1) else value
        require(epoch.isNotEmpty() && epoch.all(Char::isDigit) && remainder.isNotEmpty())
        val hyphen = remainder.lastIndexOf('-')
        val upstream = if (hyphen >= 0) remainder.substring(0, hyphen) else remainder
        val revision = if (hyphen >= 0) remainder.substring(hyphen + 1) else "0"
        require(upstream.isNotEmpty() && revision.isNotEmpty())
        return Triple(epoch, upstream, revision)
    }

    fun compareNumeric(leftDigits: String, rightDigits: String): Int {
        val normalizedLeft = leftDigits.trimStart('0').ifEmpty { "0" }
        val normalizedRight = rightDigits.trimStart('0').ifEmpty { "0" }
        return if (normalizedLeft.length != normalizedRight.length) {
            normalizedLeft.length.compareTo(normalizedRight.length).coerceIn(-1, 1)
        } else {
            normalizedLeft.compareTo(normalizedRight).coerceIn(-1, 1)
        }
    }

    fun order(character: Char?): Int = when {
        character == '~' -> -1
        character == null -> 0
        character.isLetter() -> character.code
        else -> character.code + 256
    }

    fun comparePart(leftPart: String, rightPart: String): Int {
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < leftPart.length || rightIndex < rightPart.length) {
            while (
                (leftIndex < leftPart.length && !leftPart[leftIndex].isDigit()) ||
                (rightIndex < rightPart.length && !rightPart[rightIndex].isDigit())
            ) {
                val comparison = order(leftPart.getOrNull(leftIndex))
                    .compareTo(order(rightPart.getOrNull(rightIndex)))
                if (comparison != 0) return comparison.coerceIn(-1, 1)
                if (leftIndex < leftPart.length) leftIndex += 1
                if (rightIndex < rightPart.length) rightIndex += 1
            }
            while (leftPart.getOrNull(leftIndex) == '0') leftIndex += 1
            while (rightPart.getOrNull(rightIndex) == '0') rightIndex += 1
            val leftStart = leftIndex
            val rightStart = rightIndex
            while (leftPart.getOrNull(leftIndex)?.isDigit() == true) leftIndex += 1
            while (rightPart.getOrNull(rightIndex)?.isDigit() == true) rightIndex += 1
            val leftDigits = leftPart.substring(leftStart, leftIndex)
            val rightDigits = rightPart.substring(rightStart, rightIndex)
            if (leftDigits.length != rightDigits.length) {
                return leftDigits.length.compareTo(rightDigits.length).coerceIn(-1, 1)
            }
            val digitComparison = leftDigits.compareTo(rightDigits)
            if (digitComparison != 0) return digitComparison.coerceIn(-1, 1)
        }
        return 0
    }

    val leftVersion = split(left)
    val rightVersion = split(right)
    compareNumeric(leftVersion.first, rightVersion.first).takeIf { it != 0 }?.let { return it }
    comparePart(leftVersion.second, rightVersion.second).takeIf { it != 0 }?.let { return it }
    return comparePart(leftVersion.third, rightVersion.third)
}

private fun debianVersionSatisfies(actual: String, operator: String, expected: String): Boolean {
    if (operator.isEmpty()) return true
    val comparison = compareDebianVersions(actual, expected)
    return when (operator) {
        "<<" -> comparison < 0
        "<=" -> comparison <= 0
        "=" -> comparison == 0
        ">=" -> comparison >= 0
        ">>" -> comparison > 0
        else -> false
    }
}

object EmbeddedRuntimeMetadataParser {
    private const val APPLICATION_ID = "com.yaakovch.fleet"
    private const val PREFIX = "/data/data/com.yaakovch.fleet/files/usr"
    private const val PACKAGE_REPOSITORY = "https://github.com/yaakovch/agent-fleet-termux-packages"
    private val SHA256 = Regex("^[a-f0-9]{64}$")
    private val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")
    private val COMMIT = Regex("^[a-f0-9]{40}$")
    private val KEY_ID = Regex("^[a-f0-9]{32}$")
    private val PACKAGE = Regex("^[a-z0-9][a-z0-9+.-]*$")
    private val DEBIAN_DEPENDENCY = Regex(
        "^([a-z0-9][a-z0-9+.-]{0,127})(?::([a-z0-9_-]+))?" +
            "(?: \\((<<|<=|=|>=|>>) ([A-Za-z0-9.+:~_-]{1,128})\\))?" +
            "(?: \\[([A-Za-z0-9_! -]{1,256})\\])?" +
            "(?: <([A-Za-z0-9_!+.-]{1,128})>)?$"
    )
    private val DEBIAN_ARCHITECTURE = Regex("^!?[a-z0-9][a-z0-9_-]{0,63}$")
    private val FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,159}$")
    private val DEB_FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._+~-]{0,199}\\.deb$")
    private val RECIPE = Regex("^(?:NOASSERTION|(?:packages|root-packages|x11-packages)/[a-z0-9][a-z0-9+.-]*/build\\.sh)$")
    private val RUNTIME_COMPONENTS = listOf("clientRuntime", "hostRuntime", "providerAdapters", "contracts")
    private val PACKAGE_LOCK_FIELDS = setOf(
        "schemaVersion", "applicationId", "prefix", "architecture", "repository", "bundleUrl",
        "upstreamCommit", "forkCommit", "rootPackages", "totalSize", "packages"
    )
    private val PACKAGE_LOCK_CLOSURE_FIELDS = setOf(
        "runtimeRootsSha256", "closureManifestFile", "closureManifestSha256", "closureManifestSize"
    )
    private val PACKAGE_FIELDS = setOf(
        "name", "version", "architecture", "file", "sha256", "size", "sourcePackage",
        "recipe", "homepage", "license", "description"
    )
    private val PACKAGE_CLOSURE_FIELDS = setOf(
        "essential", "preDepends", "depends", "provides", "resolvedDependencies"
    )

    fun descriptor(text: String): EmbeddedRuntimeDescriptor {
        require(text.length <= 64 * 1024) { "Embedded runtime descriptor is too large" }
        val root = JSONObject(text)
        root.requireFields(
            "schemaVersion", "baselineVersion", "sourceRepository", "wtmuxCommit",
            "contractPackageVersion", "components", "protocolVersion", "supportedAbis",
            "runtime", "registry", "packageLock", "sbom", "trustedRuntimeKeys"
        )
        require(root.getInt("schemaVersion") == 1) { "Unsupported embedded runtime descriptor" }
        val baseline = root.getString("baselineVersion").also { require(VERSION.matches(it)) }
        val sourceRepository = root.getString("sourceRepository").also {
            requireHttps(it)
            require(it == "https://github.com/yaakovch/wtmux")
        }
        val commit = root.getString("wtmuxCommit").also { require(COMMIT.matches(it)) }
        require(baseline == "git-${commit.take(7)}")
        val contractPackageVersion = root.getString("contractPackageVersion").also { require(VERSION.matches(it)) }
        val componentObject = root.getJSONObject("components").also {
            it.requireFields(*RUNTIME_COMPONENTS.toTypedArray())
        }
        val components = RUNTIME_COMPONENTS.associateWith { name ->
            val value = componentObject.getJSONObject(name).also { it.requireFields("sequence", "version") }
            EmbeddedRuntimeComponent(
                sequence = value.getLong("sequence").also { require(it > 0) },
                version = value.getString("version").also { require(VERSION.matches(it)) }
            )
        }
        require(RUNTIME_COMPONENTS.take(3).all { components.getValue(it).version == baseline })
        require(components.getValue("contracts").version == contractPackageVersion)
        val protocol = root.getInt("protocolVersion").also { require(it in 1..1024) }
        val abis = root.getJSONArray("supportedAbis").strings(4).also { values ->
            require(values.isNotEmpty() && values.distinct().size == values.size)
            require(values.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") })
        }
        val runtime = runtimeFile(root.getJSONObject("runtime"))
        val registry = file(root.getJSONObject("registry"), 16L * 1024L * 1024L)
        val packageLock = packageLockFile(root.getJSONObject("packageLock"))
        val sbom = file(root.getJSONObject("sbom"), 2L * 1024L * 1024L)
        val keys = root.getJSONArray("trustedRuntimeKeys").objects(4).map { value ->
            value.requireFields("keyId", "file", "sha256")
            EmbeddedRuntimeKey(
                value.getString("keyId").also { require(KEY_ID.matches(it)) },
                value.getString("file").also { require(FILE.matches(it)) },
                value.getString("sha256").also { require(SHA256.matches(it)) }
            )
        }.also { require(it.isNotEmpty() && it.map(EmbeddedRuntimeKey::keyId).distinct().size == it.size) }
        return EmbeddedRuntimeDescriptor(
            baseline, sourceRepository, commit, contractPackageVersion, components,
            protocol, abis, runtime, registry, packageLock, sbom, keys
        )
    }

    fun packages(text: String, expectedArchitecture: String = "aarch64"): List<LockedTermuxPackage> {
        require(text.length <= 2 * 1024 * 1024) { "Termux package lock is too large" }
        val root = JSONObject(text)
        val extended = root.hasCompleteExtension(
            PACKAGE_LOCK_FIELDS,
            PACKAGE_LOCK_CLOSURE_FIELDS,
            "Termux package lock"
        )
        require(root.getInt("schemaVersion") == 2 && root.getString("architecture") == expectedArchitecture)
        require(root.getString("applicationId") == APPLICATION_ID && root.getString("prefix") == PREFIX)
        require(root.getString("repository") == PACKAGE_REPOSITORY)
        val bundleUrl = root.getString("bundleUrl")
        requireHttps(bundleUrl)
        require(bundleUrl.startsWith("$PACKAGE_REPOSITORY/releases/download/agent-fleet-runtime-") &&
            bundleUrl.endsWith("/agent-fleet-runtime-$expectedArchitecture.zip"))
        require(COMMIT.matches(root.getString("upstreamCommit")) && COMMIT.matches(root.getString("forkCommit")))
        val rootPackages = root.getJSONArray("rootPackages").strings(64).also { roots ->
            require(roots.distinct().size == roots.size && roots.all(PACKAGE::matches))
        }
        if (extended) {
            require(root.get("runtimeRootsSha256") is String)
            require(SHA256.matches(root.getString("runtimeRootsSha256")))
            require(root.get("closureManifestFile") is String)
            require(root.getString("closureManifestFile") == "agent-fleet-runtime-closure-v1.json")
            require(root.get("closureManifestSha256") is String)
            require(SHA256.matches(root.getString("closureManifestSha256")))
            require(root.get("closureManifestSize") is Int || root.get("closureManifestSize") is Long)
            require(root.getLong("closureManifestSize") in 1..2L * 1024L * 1024L)
        }
        val values = root.getJSONArray("packages").objects(512).map { item ->
            val itemFields = item.keys().asSequence().toSet()
            val itemExtended = when (itemFields) {
                PACKAGE_FIELDS -> false
                PACKAGE_FIELDS + PACKAGE_CLOSURE_FIELDS,
                PACKAGE_FIELDS + PACKAGE_CLOSURE_FIELDS + "multiArch" -> true
                else -> throw IllegalArgumentException(
                    "Termux package record fields are incomplete"
                )
            }
            require(itemExtended == extended) { "Termux package lock mixes closure formats" }
            val homepage = item.getString("homepage")
            if (homepage != "NOASSERTION") requireWebMetadataUrl(homepage)
            require(item.getString("sourcePackage").matches(PACKAGE))
            require(item.getString("recipe").matches(RECIPE))
            requireBoundedMetadata(item.getString("license"), 256)
            requireBoundedMetadata(item.getString("description"), 4096, allowEmpty = true)
            val preDepends =
                if (extended) item.getJSONArray("preDepends").dependencyGroups(expectedArchitecture) else emptyList()
            val depends =
                if (extended) item.getJSONArray("depends").dependencyGroups(expectedArchitecture) else emptyList()
            val provides = if (extended) item.getJSONArray("provides").boundedStrings(128) else emptyList()
            val resolvedDependencies =
                if (extended) item.getJSONArray("resolvedDependencies").boundedStrings(128) else emptyList()
            val multiArch = if (extended) item.optString("multiArch", "no") else "no"
            if (extended) {
                require(item.get("essential") is Boolean)
                require(multiArch in setOf("no", "same", "foreign", "allowed"))
                require(provides.distinct().size == provides.size)
                provides.forEach { parseDebianDependency(it, expectedArchitecture, provided = true) }
                require(
                    resolvedDependencies.distinct().size == resolvedDependencies.size &&
                        resolvedDependencies.all(PACKAGE::matches)
                )
            }
            LockedTermuxPackage(
                item.getString("name").also { require(PACKAGE.matches(it)) },
                item.getString("version").also {
                    require(it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl))
                    require(compareDebianVersions(it, it) == 0)
                },
                item.getString("architecture").also { require(it == expectedArchitecture || it == "all") },
                item.getString("file").also { require(DEB_FILE.matches(it)) },
                item.getString("sha256").also { require(SHA256.matches(it)) },
                item.getLong("size").also {
                    require(item.get("size") is Int || item.get("size") is Long)
                    require(it in 1..64L * 1024L * 1024L)
                },
                essential = extended && item.getBoolean("essential"),
                multiArch = multiArch,
                preDepends = preDepends,
                depends = depends,
                provides = provides,
                resolvedDependencies = resolvedDependencies
            )
        }
        require(values.isNotEmpty() && values.map(LockedTermuxPackage::name).distinct().size == values.size)
        require(values.map(LockedTermuxPackage::file).distinct().size == values.size)
        require(rootPackages.toSet().all { rootPackage -> values.any { it.name == rootPackage } })
        require(root.get("totalSize") is Int || root.get("totalSize") is Long)
        require(values.sumOf(LockedTermuxPackage::size) == root.getLong("totalSize"))
        if (extended) {
            require(values.map(LockedTermuxPackage::name) == values.map(LockedTermuxPackage::name).sorted())
            validateLockedDependencyClosure(values, rootPackages, expectedArchitecture)
        }
        return values
    }

    private data class DebianDependency(
        val name: String,
        val applicable: Boolean,
        val requiresMultiArchAllowed: Boolean,
        val operator: String,
        val version: String
    )

    private fun parseDebianDependency(
        value: String,
        architecture: String,
        provided: Boolean = false
    ): DebianDependency {
        val match = requireNotNull(DEBIAN_DEPENDENCY.matchEntire(value)) {
            "Termux package dependency expression is invalid"
        }
        val qualifier = match.groupValues[2]
        val operator = match.groupValues[3]
        val version = match.groupValues[4]
        require(operator.isEmpty() == version.isEmpty()) {
            "Termux package dependency version restriction is invalid"
        }
        val architectures = match.groupValues[5].split(' ').filter(String::isNotBlank)
        val profiles = match.groupValues[6]
        require(architectures.all(DEBIAN_ARCHITECTURE::matches)) {
            "Termux package dependency architecture is invalid"
        }
        if (provided) {
            require(
                qualifier.isEmpty() && architectures.isEmpty() && profiles.isEmpty() &&
                    operator in setOf("", "=")
            ) { "Termux package Provides expression is invalid" }
            return DebianDependency(
                match.groupValues[1],
                applicable = true,
                requiresMultiArchAllowed = false,
                operator = operator,
                version = version
            )
        }
        require(profiles.isEmpty()) { "Termux package dependency profiles are unsupported" }
        require(qualifier != "native") {
            "Termux package :native dependency qualifier is unsupported"
        }
        require(qualifier in setOf("", "any", architecture)) {
            "Termux package cross-architecture dependency is unsupported"
        }
        val positives = architectures.filterNot { it.startsWith("!") }.toSet()
        val negatives = architectures.filter { it.startsWith("!") }.map { it.drop(1) }.toSet()
        require(
            positives.isEmpty() || negatives.isEmpty()
        ) { "Termux package dependency mixes architecture restrictions" }
        require((positives + negatives).none { it == "any" || it.startsWith("any-") || it.endsWith("-any") }) {
            "Termux package dependency architecture wildcard is unsupported"
        }
        return DebianDependency(
            name = match.groupValues[1],
            applicable = architecture !in negatives && (positives.isEmpty() || architecture in positives),
            requiresMultiArchAllowed = qualifier == "any",
            operator = operator,
            version = version
        )
    }

    private fun validateLockedDependencyClosure(
        packages: List<LockedTermuxPackage>,
        roots: List<String>,
        architecture: String
    ) {
        val byName = packages.associateBy(LockedTermuxPackage::name)
        val providers = mutableMapOf<String, MutableList<Pair<String, DebianDependency>>>()
        packages.forEach { item ->
            item.provides.forEach { provided ->
                val parsed = parseDebianDependency(provided, architecture, provided = true)
                providers.getOrPut(parsed.name) { mutableListOf() }.add(item.name to parsed)
            }
        }
        packages.forEach { item ->
            val groups = (item.preDepends + item.depends).mapNotNull { alternatives ->
                alternatives.map { parseDebianDependency(it, architecture) }
                    .filter(DebianDependency::applicable)
                    .takeIf { it.isNotEmpty() }
            }
            fun represented(dependency: String, group: List<DebianDependency>): Boolean =
                group.any { alternative ->
                    val selected = byName[dependency] ?: return@any false
                    if (alternative.requiresMultiArchAllowed && selected.multiArch != "allowed") {
                        return@any false
                    }
                    if (dependency == alternative.name) {
                        return@any debianVersionSatisfies(
                            selected.version,
                            alternative.operator,
                            alternative.version
                        )
                    }
                    val provided = providers[alternative.name].orEmpty()
                        .firstOrNull { it.first == dependency }
                        ?.second ?: return@any false
                    alternative.operator.isEmpty() ||
                        (provided.version.isNotEmpty() && debianVersionSatisfies(
                            provided.version,
                            alternative.operator,
                            alternative.version
                        ))
                }
            require(item.resolvedDependencies.all(byName::containsKey)) {
                "Termux resolved dependency is absent from the package lock"
            }
            require(groups.all { group -> item.resolvedDependencies.any { represented(it, group) } }) {
                "Termux package dependency group has no resolved edge"
            }
            require(item.resolvedDependencies.all { dependency -> groups.any { represented(dependency, it) } }) {
                "Termux package resolved edge is not declared"
            }
        }
        val pending = (roots + packages.filter(LockedTermuxPackage::essential).map(LockedTermuxPackage::name))
            .distinct()
            .sorted()
            .toMutableList()
        val selected = mutableSetOf<String>()
        val resolved = mutableMapOf<String, List<String>>()
        while (pending.isNotEmpty()) {
            val name = pending.removeAt(pending.lastIndex)
            if (!selected.add(name)) continue
            val item = requireNotNull(byName[name]) { "Termux runtime closure root is missing: $name" }
            item.resolvedDependencies.forEach { dependency ->
                if (dependency !in selected) pending.add(dependency)
            }
        }
        require(selected == byName.keys) { "Termux package lock is not the exact runtime closure" }
    }

    private fun requireHttps(value: String) {
        require(value.length <= 2048 && value.none(Char::isISOControl))
        val uri = URI(value)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
    }

    private fun requireWebMetadataUrl(value: String) {
        require(value.length <= 2048 && value.none(Char::isISOControl))
        val uri = URI(value)
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
    }

    private fun requireBoundedMetadata(value: String, maximum: Int, allowEmpty: Boolean = false) {
        require((allowEmpty || value.isNotBlank()) && value.length <= maximum && value.none(Char::isISOControl))
    }

    private fun file(value: JSONObject, maximum: Long): EmbeddedRuntimeFile {
        value.requireFields("file", "sha256", "size")
        return EmbeddedRuntimeFile(
            value.getString("file").also { require(FILE.matches(it)) },
            value.getString("sha256").also { require(SHA256.matches(it)) },
            value.getLong("size").also { require(it in 1..maximum) }
        )
    }

    private fun runtimeFile(value: JSONObject): EmbeddedWtmuxRuntimeFile {
        value.requireFields(
            "file", "sha256", "size", "formatVersion", "manifestSha256",
            "sbomSha256", "licenseSha256"
        )
        return EmbeddedWtmuxRuntimeFile(
            file = value.getString("file").also { require(FILE.matches(it)) },
            sha256 = value.getString("sha256").also { require(SHA256.matches(it)) },
            size = value.getLong("size").also { require(it in 1..32L * 1024L * 1024L) },
            formatVersion = value.getInt("formatVersion").also { require(it == 2) },
            manifestSha256 = value.getString("manifestSha256").also { require(SHA256.matches(it)) },
            sbomSha256 = value.getString("sbomSha256").also { require(SHA256.matches(it)) },
            licenseSha256 = value.getString("licenseSha256").also { require(SHA256.matches(it)) }
        )
    }

    private fun packageLockFile(value: JSONObject): EmbeddedRuntimeFile {
        value.requireFields("file", "sha256", "size", "packages", "payloadSize")
        require(value.getInt("packages") in 1..512)
        require(value.getLong("payloadSize") in 1..512L * 1024L * 1024L)
        return EmbeddedRuntimeFile(
            value.getString("file").also { require(FILE.matches(it)) },
            value.getString("sha256").also { require(SHA256.matches(it)) },
            value.getLong("size").also { require(it in 1..2L * 1024L * 1024L) }
        )
    }

    private fun JSONObject.requireFields(vararg names: String) {
        require(keys().asSequence().toSet() == names.toSet()) { "Embedded runtime fields are invalid" }
    }

    private fun JSONObject.hasCompleteExtension(
        base: Set<String>,
        extension: Set<String>,
        label: String
    ): Boolean {
        val fields = keys().asSequence().toSet()
        require(fields == base || fields == base + extension) { "$label fields are invalid" }
        return fields == base + extension
    }

    private fun JSONArray.strings(maximum: Int): List<String> {
        require(length() in 1..maximum)
        return List(length()) { getString(it) }
    }

    private fun JSONArray.boundedStrings(maximum: Int): List<String> {
        require(length() in 0..maximum)
        return List(length()) { index ->
            require(get(index) is String)
            getString(index)
        }
    }

    private fun JSONArray.dependencyGroups(architecture: String): List<List<String>> {
        require(length() in 0..128)
        return List(length()) { index ->
            getJSONArray(index).strings(32).also { expressions ->
                require(expressions.distinct().size == expressions.size)
                expressions.forEach { parseDebianDependency(it, architecture) }
            }
        }
    }

    private fun JSONArray.objects(maximum: Int): List<JSONObject> {
        require(length() in 1..maximum)
        return List(length()) { getJSONObject(it) }
    }
}

class EmbeddedRuntimeManager(private val context: Context) {
    private val dataRoot = context.filesDir.parentFile ?: error("Application data directory is unavailable")
    private val prefix = File(dataRoot, "files/usr")
    private val home = File(dataRoot, "files/home")
    private val runtimeRoot = File(home, ".local/share/agent-fleet/wtmux")
    private val legacyRuntimeRoot = File(home, ".local/share/wtmux")
    private val binDir = File(prefix, "bin")
    private val config = File(home, ".config/wtmux/wtmux.conf")
    private val staging = File(context.cacheDir, "agent-fleet-embedded-runtime")

    fun descriptor(): EmbeddedRuntimeDescriptor = EmbeddedRuntimeMetadataParser.descriptor(
        context.assets.open("agent-fleet/embedded-runtime-v1.json").bufferedReader().use { it.readText() }
    )

    fun packages(descriptor: EmbeddedRuntimeDescriptor = descriptor()): List<LockedTermuxPackage> {
        val bytes = context.assets.open("agent-fleet/${descriptor.packageLock.file}").use { it.readBytes() }
        require(bytes.size.toLong() == descriptor.packageLock.size && bytes.sha256() == descriptor.packageLock.sha256) {
            "Embedded package lock verification failed"
        }
        return EmbeddedRuntimeMetadataParser.packages(bytes.toString(Charsets.UTF_8))
    }

    fun inspect(): EmbeddedRuntimeStatus {
        migrateLegacyRuntimeRoot()
        val descriptor = descriptor()
        val locked = packages(descriptor)
        val supported = supportsEmbeddedRuntime(Build.SUPPORTED_ABIS.firstOrNull(), descriptor.supportedAbis)
        val outdated = if (supported && File(binDir, "dpkg-query").canExecute()) packagesNeedingRepair(locked) else locked
        val links = runtimeLinks()
        val usable = File(binDir, "bash").canExecute() && File(binDir, "python3").canExecute() &&
            (File(binDir, "wtmux").canExecute() || File(runtimeRoot, "current/scripts/wtmux").canExecute())
        val baselineReady = links["baseline"] == descriptor.baselineVersion
        val registryReady = embeddedRegistryReady()
        val repairNeeded = !supported || outdated.isNotEmpty() || !usable || !baselineReady || !registryReady
        val detail = when {
            !supported -> "Offline fleet runtime is available for arm64 devices only"
            !usable -> "Built-in terminal tools or wtmux need repair"
            outdated.isNotEmpty() -> "${outdated.size} built-in package${if (outdated.size == 1) "" else "s"} need repair"
            !baselineReady -> "APK recovery baseline is not installed"
            !registryReady -> "Fleet registry needs repair"
            else -> "Built-in terminal, fleet registry, and recovery baseline are ready"
        }
        return EmbeddedRuntimeStatus(
            supported, usable, repairNeeded, descriptor.baselineVersion,
            links["baseline"].orEmpty(), links["current"].orEmpty(), links["previous"].orEmpty(),
            outdated.size, locked.size, descriptor.trustedRuntimeKeys.map(EmbeddedRuntimeKey::keyId), detail
        )
    }

    fun isInstalledRuntimeVerified(version: String): Boolean = runCatching {
        require(INSTALLED_RUNTIME_VERSION.matches(version))
        require(Os.readlink(File(runtimeRoot, "current").absolutePath) == "releases/$version")
        verifyStoredRuntimeVersion(version)
    }.isSuccess

    private fun isStoredRuntimeVersionVerified(version: String): Boolean = runCatching {
        verifyStoredRuntimeVersion(version)
    }.isSuccess

    private fun verifyStoredRuntimeVersion(version: String) {
        require(INSTALLED_RUNTIME_VERSION.matches(version))
        val release = File(runtimeRoot, "releases/$version")
        require(OsConstants.S_ISDIR(Os.lstat(release.absolutePath).st_mode))
        val descriptor = descriptor()
        if (version == descriptor.baselineVersion) {
            verifyInstalledRuntimeRelease(
                release,
                version,
                descriptor.runtime.manifestSha256,
                descriptor
            )
        } else {
            val receipt = runtimeReceipt(version)
            verifyInstalledRuntimeRelease(release, version, receipt.manifestSha256)
        }
    }

    private data class RuntimeReceipt(
        val version: String,
        val artifactSha256: String,
        val manifestSha256: String
    )

    private fun runtimeReceipt(version: String): RuntimeReceipt {
        val file = File(runtimeRoot, "receipts/$version.json")
        val value = JSONObject(
            String(
                file.readStableRuntimeBytes(
                    maximum = MAX_RUNTIME_RECEIPT_BYTES,
                    expectedMode = 384
                ),
                Charsets.UTF_8
            )
        )
        require(
            value.keys().asSequence().toSet() ==
                setOf("schemaVersion", "version", "artifactSha256", "manifestSha256") &&
                value.getInt("schemaVersion") == 1 &&
                value.getString("version") == version
        ) { "Installed runtime receipt identity is invalid" }
        return RuntimeReceipt(
            version = version,
            artifactSha256 = value.getString("artifactSha256").also {
                require(INSTALLED_RUNTIME_SHA256.matches(it))
            },
            manifestSha256 = value.getString("manifestSha256").also {
                require(INSTALLED_RUNTIME_SHA256.matches(it))
            }
        )
    }

    private fun writeRuntimeReceipt(receipt: RuntimeReceipt) {
        require(
            INSTALLED_RUNTIME_VERSION.matches(receipt.version) &&
                INSTALLED_RUNTIME_SHA256.matches(receipt.artifactSha256) &&
                INSTALLED_RUNTIME_SHA256.matches(receipt.manifestSha256)
        )
        val directory = File(runtimeRoot, "receipts")
        require(directory.mkdirs() || directory.isDirectory) {
            "Runtime receipt directory is unavailable"
        }
        require(OsConstants.S_ISDIR(Os.lstat(directory.absolutePath).st_mode)) {
            "Runtime receipt path is unsafe"
        }
        val destination = File(directory, "${receipt.version}.json")
        val temporary = File.createTempFile(".${receipt.version}.", ".tmp", directory)
        try {
            Os.chmod(temporary.absolutePath, 384)
            val payload = JSONObject()
                .put("schemaVersion", 1)
                .put("version", receipt.version)
                .put("artifactSha256", receipt.artifactSha256)
                .put("manifestSha256", receipt.manifestSha256)
                .toString()
                .toByteArray(Charsets.UTF_8)
            require(payload.size.toLong() <= MAX_RUNTIME_RECEIPT_BYTES)
            FileOutputStream(temporary).use { output ->
                output.write(payload)
                output.fd.sync()
            }
            atomicRenameCompat(temporary, destination)
            fsyncDirectoryCompat(directory)
        } finally {
            temporary.delete()
        }
    }

    private fun migrateLegacyRuntimeRoot() {
        if (runtimeRoot.exists()) {
            require(OsConstants.S_ISDIR(Os.lstat(runtimeRoot.absolutePath).st_mode)) {
                "App-owned runtime path is not a directory"
            }
            return
        }
        if (!legacyRuntimeRoot.exists()) return
        val legacyStat = Os.lstat(legacyRuntimeRoot.absolutePath)
        require(OsConstants.S_ISDIR(legacyStat.st_mode)) {
            "The previous app-owned runtime is not a directory"
        }
        require(runtimeRoot.parentFile?.mkdirs() == true || runtimeRoot.parentFile?.isDirectory == true) {
            "App-owned runtime directory is unavailable"
        }
        atomicRenameCompat(legacyRuntimeRoot, runtimeRoot)
        fsyncDirectoryCompat(requireNotNull(runtimeRoot.parentFile))
    }

    @Synchronized
    fun repair(preserveCurrent: Boolean = false, progress: (String) -> Unit = {}): EmbeddedRuntimeStatus {
        val descriptor = descriptor()
        val previousCurrent = inspect().current
        require(supportsEmbeddedRuntime(Build.SUPPORTED_ABIS.firstOrNull(), descriptor.supportedAbis)) {
            "This APK has no offline fleet payload for ${Build.SUPPORTED_ABIS.firstOrNull() ?: "this device"}"
        }
        val locked = packages(descriptor)
        val outdated = packagesNeedingRepair(locked)
        staging.mkdirs()
        if (outdated.isNotEmpty()) {
            progress("Installing ${outdated.size} terminal packages…")
            val packageDirectory = File(staging, "packages").apply { mkdirs() }
            val files = outdated.map { item ->
                copyVerifiedAsset("agent-fleet/packages/${item.file}", File(packageDirectory, item.file), item.sha256, item.size)
            }
            installPackages(files)
        }
        progress("Installing Agent Fleet runtime…")
        val bundle = copyVerifiedAsset(
            "agent-fleet/${descriptor.runtime.file}", File(staging, descriptor.runtime.file),
            descriptor.runtime.sha256, descriptor.runtime.size
        )
        installBaseline(bundle, descriptor)
        progress("Installing Fleet registry…")
        val registry = copyVerifiedAsset(
            "agent-fleet/${descriptor.registry.file}", File(staging, descriptor.registry.file),
            descriptor.registry.sha256, descriptor.registry.size
        )
        installRegistry(registry, descriptor)
        progress("Checking terminal health…")
        try {
            runSetupAndDoctor()
        } catch (error: Exception) {
            runCatching { rollback() }
            throw error
        }
        val status = inspect()
        require(status.usable && status.baseline == descriptor.baselineVersion && status.missingOrOldPackages == 0) {
            "Built-in runtime did not pass its final health check"
        }
        if (shouldRestorePreservedRuntime(preserveCurrent, previousCurrent, status)) {
            val restored = rollback()
            require(restored.usable && restored.baseline == descriptor.baselineVersion && restored.current == previousCurrent) {
                "The newer healthy runtime could not be restored after baseline repair"
            }
            FleetControlSupervisor.restartForConfigurationChange()
            return restored
        }
        FleetControlSupervisor.restartForConfigurationChange()
        return status
    }

    @Synchronized
    fun rollback(): EmbeddedRuntimeStatus {
        val links = runtimeLinks()
        val current = links["current"].orEmpty()
        val previous = links["previous"].orEmpty()
        require(
            current.isNotBlank() && previous.isNotBlank() &&
                isInstalledRuntimeVerified(current) &&
                isStoredRuntimeVersionVerified(previous)
        ) { "Runtime rollback source or target is not externally verified" }
        val runtime = File(runtimeRoot, "current/scripts/wtmux-runtime")
        require(runtime.isFile) { "Runtime rollback is unavailable" }
        val result = runProcess(
            listOf(File(binDir, "python3").absolutePath, runtime.absolutePath, "rollback", "--root", runtimeRoot.absolutePath,
                "--bin-dir", binDir.absolutePath),
            timeoutSeconds = 30
        )
        require(result.exitCode == 0) { result.safeError("Runtime rollback failed") }
        require(
            runtimeLinks()["current"] == previous && isInstalledRuntimeVerified(previous)
        ) { "Rolled-back runtime files did not match their verified artifact" }
        runSetupAndDoctor()
        return inspect().also {
            require(it.current == previous && isInstalledRuntimeVerified(it.current))
            FleetControlSupervisor.restartForConfigurationChange()
        }
    }

    @Synchronized
    fun restoreBaseline(): EmbeddedRuntimeStatus {
        val descriptor = descriptor()
        val bundle = copyVerifiedAsset(
            "agent-fleet/${descriptor.runtime.file}",
            File(staging, descriptor.runtime.file),
            descriptor.runtime.sha256,
            descriptor.runtime.size
        )
        // Recovery must not execute a potentially corrupted active release.
        // The installer comes from the APK-hashed baseline artifact instead.
        installBaseline(bundle, descriptor)
        require(
            runtimeLinks()["current"] == descriptor.baselineVersion &&
                isInstalledRuntimeVerified(descriptor.baselineVersion)
        ) { "Restored baseline files did not match the APK descriptor" }
        runSetupAndDoctor()
        return inspect().also {
            require(it.current == descriptor.baselineVersion && isInstalledRuntimeVerified(it.current))
            FleetControlSupervisor.restartForConfigurationChange()
        }
    }

    @Synchronized
    fun installHotfix(bundle: File, expectedSha256: String): EmbeddedRuntimeStatus {
        require(bundle.isFile && bundle.length() in 1..32L * 1024L * 1024L && bundle.sha256() == expectedSha256) {
            "Runtime hotfix artifact verification failed"
        }
        val current = inspect().current
        require(current.isNotBlank() && isInstalledRuntimeVerified(current)) {
            "The active runtime must be repaired before installing a hotfix"
        }
        val installer = File(runtimeRoot, "current/scripts/wtmux-runtime")
        require(installer.isFile && installer.length() in 1..1024L * 1024L) {
            "The verified runtime installer is unavailable"
        }
        val installed = runProcess(
            listOf(
                File(binDir, "python3").absolutePath, installer.absolutePath, "install",
                "--bundle", bundle.absolutePath, "--sha256", expectedSha256,
                "--root", runtimeRoot.absolutePath, "--bin-dir", binDir.absolutePath
            ),
            timeoutSeconds = 60
        )
        require(installed.exitCode == 0) { installed.safeError("Runtime hotfix installation failed") }
        val installedVersion = runtimeLinks()["current"].orEmpty()
        require(installedVersion.isNotBlank()) { "Runtime hotfix did not activate a release" }
        val release = File(runtimeRoot, "releases/$installedVersion")
        val manifestSha256 = inspectInstalledRuntimeRelease(
            release,
            installedVersion,
            expectedManifestSha256 = null
        )
        writeRuntimeReceipt(RuntimeReceipt(installedVersion, expectedSha256, manifestSha256))
        require(isInstalledRuntimeVerified(installedVersion)) {
            "Runtime hotfix files did not match their verified artifact receipt"
        }
        try {
            runSetupAndDoctor()
        } catch (error: Exception) {
            runCatching { rollback() }
            throw error
        }
        val status = inspect()
        require(status.current == installedVersion && isInstalledRuntimeVerified(status.current))
        FleetControlSupervisor.restartForConfigurationChange()
        return status
    }

    /** Reinstalls only the verified offline packages owning a missing image-upload command. */
    internal fun repairImageUploadTools(): List<String> = synchronized(IMAGE_TOOL_REPAIR_LOCK) {
        val missing = missingAgentFleetImageTools(binDir)
        if (missing.isEmpty()) return@synchronized emptyList()
        val packageNames = agentFleetImageToolPackageNames(missing)
        val locked = packages().filter { it.name in packageNames }
        require(locked.map(LockedTermuxPackage::name).toSet() == packageNames) {
            "The offline image-tool packages are incomplete"
        }
        val packageDirectory = File(staging, "image-tool-repair").apply { mkdirs() }
        val files = locked.map { item ->
            copyVerifiedAsset("agent-fleet/packages/${item.file}", File(packageDirectory, item.file), item.sha256, item.size)
        }
        installPackages(files)
        missingAgentFleetImageTools(binDir)
    }

    internal fun outdatedPackages(locked: List<LockedTermuxPackage>): List<LockedTermuxPackage> {
        val query = File(binDir, "dpkg-query")
        val dpkg = File(binDir, "dpkg")
        if (!query.canExecute() || !dpkg.canExecute()) return locked
        val result = runProcess(
            listOf(query.absolutePath, "-W", "-f=\${binary:Package}\t\${Version}\t\${Status}\n") + locked.map(LockedTermuxPackage::name),
            timeoutSeconds = 20
        )
        val installed = installedPackageVersions(result.output)
        return locked.filter { item ->
            val version = installed[item.name] ?: return@filter true
            runProcess(
                listOf(dpkg.absolutePath, "--compare-versions", version, "ge", item.version), timeoutSeconds = 5, maxOutput = 1024
            ).exitCode != 0
        }
    }

    private fun packagesNeedingRepair(locked: List<LockedTermuxPackage>): List<LockedTermuxPackage> {
        val missingOwners = agentFleetImageToolPackageNames(missingAgentFleetImageTools(binDir))
        return (outdatedPackages(locked) + locked.filter { it.name in missingOwners }).distinctBy(LockedTermuxPackage::name)
    }

    private fun installPackages(files: List<File>) {
        val dpkg = File(binDir, "dpkg")
        require(dpkg.canExecute()) { "Termux package manager is unavailable" }
        // apt may still consult configured repositories while resolving local
        // archives. Unpack the verified closure directly, then configure it as
        // one transaction so repair remains offline by construction.
        val unpack = runProcess(
            listOf(dpkg.absolutePath, "--force-confold", "--unpack") + files.map(File::getAbsolutePath),
            timeoutSeconds = 300,
            maxOutput = 512 * 1024
        )
        require(unpack.exitCode == 0) { unpack.safeError("Offline terminal package unpack failed") }
        val configure = runProcess(
            listOf(dpkg.absolutePath, "--force-confold", "--configure", "-a"),
            timeoutSeconds = 300,
            maxOutput = 512 * 1024
        )
        require(configure.exitCode == 0) { configure.safeError("Offline terminal package configuration failed") }
    }

    private fun installBaseline(bundle: File, descriptor: EmbeddedRuntimeDescriptor) {
        val extraction = File(staging, "installer").apply { deleteRecursively(); mkdirs() }
        val tar = File(binDir, "tar")
        require(tar.canExecute()) { "Tar is unavailable after package repair" }
        val extracted = runProcess(
            listOf(tar.absolutePath, "-xf", bundle.absolutePath, "-C", extraction.absolutePath, "scripts/wtmux-runtime"),
            timeoutSeconds = 30
        )
        require(extracted.exitCode == 0) { extracted.safeError("Embedded runtime installer could not be extracted") }
        val installer = File(extraction, "scripts/wtmux-runtime")
        require(installer.isFile && installer.length() in 1..1024L * 1024L) { "Embedded runtime installer is invalid" }
        val installed = runProcess(
            listOf(
                File(binDir, "python3").absolutePath, installer.absolutePath, "install",
                "--bundle", bundle.absolutePath, "--sha256", descriptor.runtime.sha256,
                "--root", runtimeRoot.absolutePath, "--bin-dir", binDir.absolutePath, "--baseline"
            ),
            timeoutSeconds = 60
        )
        require(installed.exitCode == 0) { installed.safeError("Embedded wtmux runtime installation failed") }
        verifyInstalledRuntimeRelease(
            File(runtimeRoot, "releases/${descriptor.baselineVersion}"),
            descriptor.baselineVersion,
            descriptor.runtime.manifestSha256,
            descriptor
        )
    }

    private fun installRegistry(bundle: File, descriptor: EmbeddedRuntimeDescriptor) {
        admittedRuntimeExecutionTarget(
            runtimeLinks()["current"].orEmpty(),
            ::isInstalledRuntimeVerified
        )
        val runtime = File(runtimeRoot, "current/scripts/wtmux-runtime")
        require(runtime.isFile) { "Embedded Fleet registry installer is unavailable" }
        val installed = runProcess(
            listOf(
                File(binDir, "python3").absolutePath, runtime.absolutePath, "install-registry",
                "--bundle", bundle.absolutePath, "--sha256", descriptor.registry.sha256,
                "--root", runtimeRoot.absolutePath, "--config", config.absolutePath, "--preserve-current"
            ),
            timeoutSeconds = 30
        )
        require(installed.exitCode == 0) { installed.safeError("Embedded Fleet registry installation failed") }
    }

    private fun embeddedRegistryReady(): Boolean = runCatching {
        admittedRuntimeExecutionTarget(runtimeLinks()["current"].orEmpty(), ::isInstalledRuntimeVerified)
        val resolver = File(runtimeRoot, "current/lib/fleet_registry.py")
        require(resolver.isFile)
        val result = runProcess(
            listOf(File(binDir, "python3").absolutePath, resolver.absolutePath, "--active", runtimeRoot.absolutePath),
            timeoutSeconds = 15
        )
        require(result.exitCode == 0)
        val machines = result.output.trim()
        require(machines.startsWith(runtimeRoot.absolutePath + "/") && !machines.contains('\n'))
        require(config.isFile && config.length() in 1..1024L * 1024L)
        embeddedRegistryBindingIsCurrent(config.readText(Charsets.UTF_8), machines)
    }.getOrDefault(false)

    private fun runSetupAndDoctor() {
        admittedRuntimeExecutionTarget(
            runtimeLinks()["current"].orEmpty(),
            ::isInstalledRuntimeVerified
        )
        val bash = File(binDir, "bash")
        val setup = File(runtimeRoot, "current/setup.sh")
        val wtmux = File(runtimeRoot, "current/scripts/wtmux")
        require(bash.canExecute() && setup.isFile && wtmux.isFile) { "Installed runtime is incomplete" }
        val setupResult = runProcess(
            listOf(bash.absolutePath, setup.absolutePath, "--refresh-only", "--no-hooks"), timeoutSeconds = 90
        )
        require(setupResult.exitCode == 0) { setupResult.safeError("Runtime setup failed") }
        val doctor = runProcess(
            listOf(bash.absolutePath, wtmux.absolutePath, "doctor", "--config"), timeoutSeconds = 60
        )
        require(doctor.exitCode == 0 && doctor.output.contains("config ok:")) { doctor.safeError("Runtime health check failed") }
    }

    private fun runtimeLinks(): Map<String, String> = listOf("baseline", "current", "previous").associateWith { name ->
        runCatching { Os.readlink(File(runtimeRoot, name).absolutePath).substringAfterLast('/') }.getOrDefault("")
    }

    private fun copyVerifiedAsset(path: String, destination: File, expectedSha256: String, expectedSize: Long): File {
        if (destination.isFile && destination.length() == expectedSize && destination.sha256() == expectedSha256) return destination
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.${System.nanoTime()}")
        try {
            context.assets.open(path).use { input -> FileOutputStream(temporary).use { output -> input.copyTo(output) } }
            require(temporary.length() == expectedSize && temporary.sha256() == expectedSha256) {
                "Embedded asset verification failed: ${destination.name}"
            }
            Os.rename(temporary.absolutePath, destination.absolutePath)
        } finally {
            temporary.delete()
        }
        return destination
    }

    private fun runProcess(command: List<String>, timeoutSeconds: Long, maxOutput: Int = 256 * 1024): ProcessResult {
        val workingDirectory = ensureEmbeddedRuntimeHome(home)
        val process = ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .apply { configureEnvironment(environment()) }
            .start()
        val output = ByteArrayOutputStream()
        val truncated = AtomicBoolean(false)
        val readerFailure = AtomicReference<Throwable?>()
        val reader = thread(name = "embedded-runtime-process", isDaemon = true) {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val room = maxOutput - output.size()
                        if (room > 0) output.write(buffer, 0, minOf(room, count))
                        if (count > room) truncated.set(true)
                    }
                }
            } catch (error: Throwable) {
                readerFailure.set(error)
            }
        }
        val finished = try {
            process.waitForCompat(timeoutSeconds, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            process.terminateAndReapCompat()
            reader.join(1_000)
            Thread.currentThread().interrupt()
            throw IllegalStateException("Terminal preparation was interrupted", interrupted)
        }
        if (!finished) {
            process.terminateAndReapCompat()
            reader.join(2_000)
            throw IllegalStateException("Terminal preparation timed out")
        }
        reader.join(2_000)
        if (reader.isAlive) {
            process.closePipesCompat()
            reader.join(1_000)
            throw IllegalStateException("Terminal preparation output did not close")
        }
        readerFailure.get()?.let { throw IllegalStateException("Terminal preparation output failed", it) }
        process.closePipesCompat()
        return ProcessResult(process.exitValue(), output.toString(Charsets.UTF_8.name()), truncated.get())
    }

    private fun configureEnvironment(environment: MutableMap<String, String>) {
        environment["HOME"] = home.absolutePath
        environment["PREFIX"] = prefix.absolutePath
        environment["TMPDIR"] = File(prefix, "tmp").absolutePath
        environment["PATH"] = listOf(binDir, File(binDir, "applets"), File(home, ".local/bin")).joinToString(":")
        environment["LANG"] = "C.UTF-8"
        environment["DEBIAN_FRONTEND"] = "noninteractive"
        environment["DPKG_COLORS"] = "never"
        environment["WTMUX_CONFIG_PATH"] = config.absolutePath
        environment["WTMUX_RUNTIME_BIN_DIR"] = binDir.absolutePath
        enableTermuxExec(environment, prefix)
    }
}

private val IMAGE_TOOL_REPAIR_LOCK = Any()

private data class ProcessResult(val exitCode: Int, val output: String, val truncated: Boolean) {
    fun safeError(fallback: String): String {
        val detail = output.lineSequence().lastOrNull { it.isNotBlank() }
            ?.take(240)?.filterNot(Char::isISOControl).orEmpty()
        return if (detail.isBlank()) fallback else "$fallback: $detail${if (truncated) " (output truncated)" else ""}"
    }
}

private fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1024 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
    .joinToString("") { "%02x".format(it) }
