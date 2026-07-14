package com.termux.app.fleet

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

data class EmbeddedRuntimeFile(val file: String, val sha256: String, val size: Long)

data class EmbeddedRuntimeKey(val keyId: String, val file: String, val sha256: String)

data class LockedTermuxPackage(
    val name: String,
    val version: String,
    val architecture: String,
    val file: String,
    val sha256: String,
    val size: Long
)

data class EmbeddedRuntimeDescriptor(
    val baselineVersion: String,
    val wtmuxCommit: String,
    val protocolVersion: Int,
    val supportedAbis: List<String>,
    val runtime: EmbeddedRuntimeFile,
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
    explicitRepair || (status.supported && status.embeddedBaseline != status.baseline)

internal fun installedPackageVersions(output: String): Map<String, String> = output.lineSequence().mapNotNull { line ->
    val parts = line.split('\t', limit = 3)
    if (parts.size == 3 && parts[2].trim() == "install ok installed") {
        parts[0].substringBefore(':') to parts[1].trim()
    } else {
        null
    }
}.toMap()

object EmbeddedRuntimeMetadataParser {
    private val SHA256 = Regex("^[a-f0-9]{64}$")
    private val VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$")
    private val COMMIT = Regex("^[a-f0-9]{40}$")
    private val KEY_ID = Regex("^[a-f0-9]{32}$")
    private val PACKAGE = Regex("^[a-z0-9][a-z0-9+.-]*$")
    private val FILE = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,159}$")

    fun descriptor(text: String): EmbeddedRuntimeDescriptor {
        require(text.length <= 64 * 1024) { "Embedded runtime descriptor is too large" }
        val root = JSONObject(text)
        root.requireFields(
            "schemaVersion", "baselineVersion", "wtmuxCommit", "protocolVersion", "supportedAbis",
            "runtime", "packageLock", "sbom", "trustedRuntimeKeys"
        )
        require(root.getInt("schemaVersion") == 1) { "Unsupported embedded runtime descriptor" }
        val baseline = root.getString("baselineVersion").also { require(VERSION.matches(it)) }
        val commit = root.getString("wtmuxCommit").also { require(COMMIT.matches(it)) }
        val protocol = root.getInt("protocolVersion").also { require(it in 1..1024) }
        val abis = root.getJSONArray("supportedAbis").strings(4).also { values ->
            require(values.isNotEmpty() && values.distinct().size == values.size)
            require(values.all { it in setOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") })
        }
        val runtime = file(root.getJSONObject("runtime"), 32L * 1024L * 1024L)
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
        return EmbeddedRuntimeDescriptor(baseline, commit, protocol, abis, runtime, packageLock, sbom, keys)
    }

    fun packages(text: String, expectedArchitecture: String = "aarch64"): List<LockedTermuxPackage> {
        require(text.length <= 2 * 1024 * 1024) { "Termux package lock is too large" }
        val root = JSONObject(text)
        root.requireFields(
            "schemaVersion", "architecture", "repository", "indexUrl", "indexSha256",
            "rootPackages", "totalSize", "packages"
        )
        require(root.getInt("schemaVersion") == 1 && root.getString("architecture") == expectedArchitecture)
        require(SHA256.matches(root.getString("indexSha256")))
        val values = root.getJSONArray("packages").objects(512).map { item ->
            require(item.keys().asSequence().toSet().containsAll(setOf(
                "name", "version", "architecture", "file", "sha256", "size"
            )))
            LockedTermuxPackage(
                item.getString("name").also { require(PACKAGE.matches(it)) },
                item.getString("version").also { require(it.isNotBlank() && it.length <= 128 && it.none(Char::isISOControl)) },
                item.getString("architecture").also { require(it == expectedArchitecture || it == "all") },
                item.getString("file").also { require(FILE.matches(it) && it.endsWith(".deb")) },
                item.getString("sha256").also { require(SHA256.matches(it)) },
                item.getLong("size").also { require(it in 1..64L * 1024L * 1024L) }
            )
        }
        require(values.isNotEmpty() && values.map(LockedTermuxPackage::name).distinct().size == values.size)
        require(values.sumOf(LockedTermuxPackage::size) == root.getLong("totalSize"))
        return values
    }

    private fun file(value: JSONObject, maximum: Long): EmbeddedRuntimeFile {
        value.requireFields("file", "sha256", "size")
        return EmbeddedRuntimeFile(
            value.getString("file").also { require(FILE.matches(it)) },
            value.getString("sha256").also { require(SHA256.matches(it)) },
            value.getLong("size").also { require(it in 1..maximum) }
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

    private fun JSONArray.strings(maximum: Int): List<String> {
        require(length() in 1..maximum)
        return List(length()) { getString(it) }
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
    private val runtimeRoot = File(home, ".local/share/wtmux")
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
        val descriptor = descriptor()
        val locked = packages(descriptor)
        val supported = supportsEmbeddedRuntime(Build.SUPPORTED_ABIS.firstOrNull(), descriptor.supportedAbis)
        val outdated = if (supported && File(binDir, "dpkg-query").canExecute()) outdatedPackages(locked) else locked
        val links = runtimeLinks()
        val usable = File(binDir, "bash").canExecute() && File(binDir, "python3").canExecute() &&
            (File(binDir, "wtmux").canExecute() || File(runtimeRoot, "current/scripts/wtmux").canExecute())
        val baselineReady = links["baseline"] == descriptor.baselineVersion
        val repairNeeded = !supported || outdated.isNotEmpty() || !usable || !baselineReady
        val detail = when {
            !supported -> "Offline fleet runtime is available for arm64 devices only"
            !usable -> "Built-in terminal tools or wtmux need repair"
            outdated.isNotEmpty() -> "${outdated.size} built-in package${if (outdated.size == 1) "" else "s"} need repair"
            !baselineReady -> "APK recovery baseline is not installed"
            else -> "Built-in terminal and recovery baseline are ready"
        }
        return EmbeddedRuntimeStatus(
            supported, usable, repairNeeded, descriptor.baselineVersion,
            links["baseline"].orEmpty(), links["current"].orEmpty(), links["previous"].orEmpty(),
            outdated.size, locked.size, descriptor.trustedRuntimeKeys.map(EmbeddedRuntimeKey::keyId), detail
        )
    }

    @Synchronized
    fun repair(progress: (String) -> Unit = {}): EmbeddedRuntimeStatus {
        val descriptor = descriptor()
        require(supportsEmbeddedRuntime(Build.SUPPORTED_ABIS.firstOrNull(), descriptor.supportedAbis)) {
            "This APK has no offline fleet payload for ${Build.SUPPORTED_ABIS.firstOrNull() ?: "this device"}"
        }
        val locked = packages(descriptor)
        val outdated = outdatedPackages(locked)
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
        return status
    }

    @Synchronized
    fun rollback(): EmbeddedRuntimeStatus {
        val runtime = File(runtimeRoot, "current/scripts/wtmux-runtime")
        require(runtime.isFile) { "Runtime rollback is unavailable" }
        val result = runProcess(
            listOf(File(binDir, "python3").absolutePath, runtime.absolutePath, "rollback", "--root", runtimeRoot.absolutePath,
                "--bin-dir", binDir.absolutePath),
            timeoutSeconds = 30
        )
        require(result.exitCode == 0) { result.safeError("Runtime rollback failed") }
        runSetupAndDoctor()
        return inspect()
    }

    @Synchronized
    fun restoreBaseline(): EmbeddedRuntimeStatus {
        val runtime = File(runtimeRoot, "current/scripts/wtmux-runtime")
        require(runtime.isFile) { "APK baseline recovery is unavailable" }
        val result = runProcess(
            listOf(File(binDir, "python3").absolutePath, runtime.absolutePath, "restore-baseline", "--root", runtimeRoot.absolutePath,
                "--bin-dir", binDir.absolutePath),
            timeoutSeconds = 30
        )
        require(result.exitCode == 0) { result.safeError("APK baseline recovery failed") }
        runSetupAndDoctor()
        return inspect()
    }

    @Synchronized
    fun installHotfix(bundle: File, expectedSha256: String): EmbeddedRuntimeStatus {
        require(bundle.isFile && bundle.length() in 1..32L * 1024L * 1024L && bundle.sha256() == expectedSha256) {
            "Runtime hotfix artifact verification failed"
        }
        val extraction = File(staging, "hotfix-installer").apply { deleteRecursively(); mkdirs() }
        val tar = File(binDir, "tar")
        require(tar.canExecute()) { "Tar is unavailable" }
        val extracted = runProcess(
            listOf(tar.absolutePath, "-xf", bundle.absolutePath, "-C", extraction.absolutePath, "scripts/wtmux-runtime"),
            timeoutSeconds = 30
        )
        require(extracted.exitCode == 0) { extracted.safeError("Runtime hotfix installer could not be extracted") }
        val installer = File(extraction, "scripts/wtmux-runtime")
        require(installer.isFile && installer.length() in 1..1024L * 1024L) { "Runtime hotfix installer is invalid" }
        val installed = runProcess(
            listOf(
                File(binDir, "python3").absolutePath, installer.absolutePath, "install",
                "--bundle", bundle.absolutePath, "--sha256", expectedSha256,
                "--root", runtimeRoot.absolutePath, "--bin-dir", binDir.absolutePath
            ),
            timeoutSeconds = 60
        )
        require(installed.exitCode == 0) { installed.safeError("Runtime hotfix installation failed") }
        try {
            runSetupAndDoctor()
        } catch (error: Exception) {
            runCatching { rollback() }
            throw error
        }
        return inspect()
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
    }

    private fun runSetupAndDoctor() {
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
        val process = ProcessBuilder(command)
            .directory(home)
            .redirectErrorStream(true)
            .apply { configureEnvironment(environment()) }
            .start()
        val output = ByteArrayOutputStream()
        var truncated = false
        val reader = thread(name = "embedded-runtime-process", isDaemon = true) {
            process.inputStream.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val room = maxOutput - output.size()
                    if (room > 0) output.write(buffer, 0, minOf(room, count))
                    if (count > room) truncated = true
                }
            }
        }
        var exitCode: Int? = null
        val waiter = thread(name = "embedded-runtime-waiter", isDaemon = true) {
            exitCode = process.waitFor()
        }
        waiter.join(TimeUnit.SECONDS.toMillis(timeoutSeconds))
        if (exitCode == null) {
            process.destroy()
            waiter.join(1_000)
            reader.join(1_000)
            throw IllegalStateException("Terminal preparation timed out")
        }
        reader.join(1_000)
        return ProcessResult(exitCode!!, output.toString(Charsets.UTF_8.name()), truncated)
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
