package com.termux.app.migration

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.system.Os
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class AgentFleetMigrationResult(
    val sourcePackage: String,
    val preferenceStores: Int,
    val files: Int,
    val registryRelease: String?
)

/**
 * Fleet-only migration between the legacy com.termux build and com.yaakovch.fleet.
 * Runtime packages, shell history, arbitrary home files, caches, and transcripts are deliberately excluded.
 */
object AgentFleetMigrationArchive {
    const val SCHEMA = "agent-fleet-migration-v1"
    const val LEGACY_PACKAGE = "com.termux"
    const val FLEET_PACKAGE = "com.yaakovch.fleet"
    private const val MANIFEST = "manifest.json"
    private const val MAX_ARCHIVE_BYTES = 16L * 1024L * 1024L
    private const val MAX_ENTRY_BYTES = 4L * 1024L * 1024L
    private const val MAX_ENTRIES = 256
    private const val MAX_PREF_ENTRIES = 512
    private const val RUNTIME_REGISTRY_BEGIN = "# BEGIN wtmux-runtime registry"
    private const val RUNTIME_REGISTRY_END = "# END wtmux-runtime registry"
    private const val MANAGED_REGISTRY_BEGIN = "# BEGIN wtmux-managed shared-registry"
    private const val MANAGED_REGISTRY_END = "# END wtmux-managed shared-registry"

    private val preferenceStores = listOf(
        "agent_fleet_terminal_drawer",
        "agent_fleet_terminal_tabs",
        "agent_fleet_workspace_v1",
        "agent_fleet_workspace_presentation",
        "agent_fleet_locations",
        "agent-fleet-native-session",
        "agent-fleet-terminal-appearance",
        "agent_fleet_display_density"
    )
    private val fixedHomeFiles = listOf(
        ".config/wtmux/wtmux.conf",
        ".config/wtmux/client-policy.json"
    )

    fun export(context: Context, output: OutputStream): AgentFleetMigrationResult {
        require(context.packageName in setOf(LEGACY_PACKAGE, FLEET_PACKAGE)) { "Unsupported migration source" }
        val payloads = linkedMapOf<String, ByteArray>()
        preferenceStores.forEach { name ->
            val bytes = encodePreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), name)
            if (bytes != null) payloads["preferences/$name.json"] = bytes
        }

        val home = File(context.filesDir, "home")
        fixedHomeFiles.forEach { relative ->
            val file = File(home, relative)
            if (safeRegularFile(home, file)) payloads["home/$relative"] = boundedBytes(file)
        }
        collectSshFiles(home, payloads)
        val registryRelease = collectRegistry(home, payloads)

        val records = JSONArray()
        payloads.forEach { (path, bytes) ->
            records.put(JSONObject().put("path", path).put("size", bytes.size).put("sha256", sha256(bytes)))
        }
        val manifest = JSONObject()
            .put("schema", SCHEMA)
            .put("schemaVersion", 1)
            .put("sourcePackage", context.packageName)
            .put("preferenceStores", JSONArray(preferenceStores.filter { "preferences/$it.json" in payloads }))
            .put("registryRelease", registryRelease ?: JSONObject.NULL)
            .put("entries", records)
            .toString(2).plus("\n").toByteArray(Charsets.UTF_8)

        val counting = LimitedOutputStream(output, MAX_ARCHIVE_BYTES)
        ZipOutputStream(counting).use { zip ->
            writeZipEntry(zip, MANIFEST, manifest)
            payloads.forEach { (path, bytes) -> writeZipEntry(zip, path, bytes) }
        }
        return AgentFleetMigrationResult(
            sourcePackage = context.packageName,
            preferenceStores = payloads.keys.count { it.startsWith("preferences/") },
            files = payloads.keys.count { it.startsWith("home/") },
            registryRelease = registryRelease
        )
    }

    fun import(context: Context, input: InputStream): AgentFleetMigrationResult {
        require(context.packageName in setOf(LEGACY_PACKAGE, FLEET_PACKAGE)) { "Unsupported migration destination" }
        val entries = readArchive(input)
        val manifestBytes = entries.remove(MANIFEST) ?: error("Migration manifest is missing")
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.length() == 6 && manifest.getString("schema") == SCHEMA && manifest.getInt("schemaVersion") == 1) {
            "Migration manifest is unsupported"
        }
        val sourcePackage = manifest.getString("sourcePackage")
        require(sourcePackage in setOf(LEGACY_PACKAGE, FLEET_PACKAGE) && sourcePackage != context.packageName) {
            "Migration archive must come from the other Agent Fleet app"
        }
        val records = manifest.getJSONArray("entries")
        require(records.length() == entries.size && records.length() <= MAX_ENTRIES) { "Migration entry count is invalid" }
        val expectedPaths = linkedSetOf<String>()
        repeat(records.length()) { index ->
            val record = records.getJSONObject(index)
            require(record.length() == 3)
            val path = safeEntry(record.getString("path"))
            val bytes = entries[path] ?: error("Migration entry is missing: $path")
            require(expectedPaths.add(path) && record.getLong("size") == bytes.size.toLong() &&
                record.getString("sha256") == sha256(bytes)) { "Migration entry failed verification: $path" }
        }
        require(expectedPaths == entries.keys) { "Migration contains undeclared entries" }

        val stores = manifest.getJSONArray("preferenceStores")
        require(stores.length() <= preferenceStores.size)
        val decodedPreferences = linkedMapOf<String, JSONObject>()
        repeat(stores.length()) { index ->
            val name = stores.getString(index)
            require(name in preferenceStores && name !in decodedPreferences) { "Migration preference store is invalid" }
            val bytes = entries["preferences/$name.json"] ?: error("Migration preference data is missing")
            decodedPreferences[name] = JSONObject(bytes.toString(Charsets.UTF_8)).also { validatePreferences(it, name) }
        }
        require(entries.keys.filter { it.startsWith("preferences/") }.toSet() ==
            decodedPreferences.keys.map { "preferences/$it.json" }.toSet()) {
            "Migration contains an unselected preference store"
        }

        val registryRelease = manifest.optString("registryRelease").takeIf { it.matches(Regex("[a-f0-9]{16}")) }
        validateFileEntries(entries.keys, registryRelease)
        entries["home/.config/wtmux/client-policy.json"]?.let { rewriteClientPolicy(it, context.packageName) }
        if (registryRelease != null) {
            val prefix = "home/.local/share/wtmux/registry/releases/$registryRelease/"
            validateRegistry(entries.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) })
        }
        installFiles(context, entries.filterKeys { it.startsWith("home/") }, sourcePackage, registryRelease)
        decodedPreferences.forEach { (name, value) -> installPreferences(context, name, value, sourcePackage) }
        return AgentFleetMigrationResult(sourcePackage, decodedPreferences.size, entries.keys.count { it.startsWith("home/") }, registryRelease)
    }

    fun import(context: Context, uri: Uri): AgentFleetMigrationResult =
        requireNotNull(context.contentResolver.openInputStream(uri)) { "Migration archive is unavailable" }.use { import(context, it) }

    fun repairMigratedPrivateRoots(context: Context): Int {
        require(context.packageName in setOf(LEGACY_PACKAGE, FLEET_PACKAGE)) { "Unsupported migration destination" }
        val sourcePackage = if (context.packageName == FLEET_PACKAGE) LEGACY_PACKAGE else FLEET_PACKAGE
        var repaired = 0
        preferenceStores.forEach { name ->
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = preferences.edit()
            var changed = false
            preferences.all.forEach preference@{ (key, raw) ->
                when (raw) {
                    is String -> rewriteRoot(raw, sourcePackage, context.packageName).takeIf { it != raw }?.let {
                        editor.putString(key, it)
                        changed = true
                    }
                    is Set<*> -> {
                        val strings = raw.map { it as? String ?: return@preference }.toSet()
                        val rewritten = strings.map { rewriteRoot(it, sourcePackage, context.packageName) }.toSet()
                        if (rewritten != strings) {
                            editor.putStringSet(key, rewritten)
                            changed = true
                        }
                    }
                }
            }
            if (changed) {
                check(editor.commit()) { "Unable to repair migrated settings" }
                repaired++
            }
        }
        val home = File(context.filesDir, "home")
        val registryMachines = activeRegistryMachines(home)?.absolutePath
        listOf(".config/wtmux/wtmux.conf", ".ssh/config").forEach { relative ->
            val file = File(home, relative)
            if (safeRegularFile(home, file)) {
                val original = boundedBytes(file).toString(Charsets.UTF_8)
                val rewritten = if (relative == ".config/wtmux/wtmux.conf") {
                    rewriteWtmuxConfig(original, sourcePackage, context.packageName, registryMachines)
                } else {
                    rewriteRoot(original, sourcePackage, context.packageName)
                }
                if (rewritten != original) {
                    atomicWrite(file, rewritten.toByteArray(Charsets.UTF_8))
                    repaired++
                }
            }
        }
        return repaired
    }

    private fun encodePreferences(preferences: SharedPreferences, name: String): ByteArray? {
        if (preferences.all.isEmpty()) return null
        require(preferences.all.size <= MAX_PREF_ENTRIES)
        val values = JSONArray()
        preferences.all.toSortedMap().forEach { (key, raw) ->
            require(key.length in 1..160 && key.none(Char::isISOControl))
            val item = JSONObject().put("key", key)
            when (raw) {
                is String -> item.put("type", "string").put("value", boundedString(raw))
                is Boolean -> item.put("type", "boolean").put("value", raw)
                is Int -> item.put("type", "int").put("value", raw)
                is Long -> item.put("type", "long").put("value", raw)
                is Float -> item.put("type", "float").put("value", raw.toDouble())
                is Set<*> -> {
                    val strings = raw.map { it as? String ?: error("Unsupported preference set") }.sorted()
                    require(strings.size <= 128)
                    item.put("type", "strings").put("value", JSONArray(strings.map(::boundedString)))
                }
                else -> error("Unsupported preference value in $name")
            }
            values.put(item)
        }
        return JSONObject().put("schemaVersion", 1).put("name", name).put("entries", values)
            .toString(2).plus("\n").toByteArray(Charsets.UTF_8)
    }

    private fun validatePreferences(value: JSONObject, name: String) {
        require(value.length() == 3 && value.getInt("schemaVersion") == 1 && value.getString("name") == name)
        val entries = value.getJSONArray("entries")
        require(entries.length() <= MAX_PREF_ENTRIES)
        val keys = mutableSetOf<String>()
        repeat(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            require(item.length() == 3)
            val key = item.getString("key")
            require(key.length in 1..160 && key.none(Char::isISOControl) && keys.add(key))
            when (item.getString("type")) {
                "string" -> boundedString(item.getString("value"))
                "boolean" -> item.getBoolean("value")
                "int" -> item.getInt("value")
                "long" -> item.getLong("value")
                "float" -> require(item.getDouble("value").isFinite())
                "strings" -> {
                    val strings = item.getJSONArray("value")
                    require(strings.length() <= 128)
                    repeat(strings.length()) { boundedString(strings.getString(it)) }
                }
                else -> error("Unsupported migration preference type")
            }
        }
    }

    private fun installPreferences(context: Context, name: String, value: JSONObject, sourcePackage: String) {
        val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        val entries = value.getJSONArray("entries")
        repeat(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            val key = item.getString("key")
            when (item.getString("type")) {
                "string" -> editor.putString(key, rewriteRoot(item.getString("value"), sourcePackage, context.packageName))
                "boolean" -> editor.putBoolean(key, item.getBoolean("value"))
                "int" -> editor.putInt(key, item.getInt("value"))
                "long" -> editor.putLong(key, item.getLong("value"))
                "float" -> editor.putFloat(key, item.getDouble("value").toFloat())
                "strings" -> {
                    val array = item.getJSONArray("value")
                    editor.putStringSet(key, (0 until array.length()).map {
                        rewriteRoot(array.getString(it), sourcePackage, context.packageName)
                    }.toSet())
                }
            }
        }
        check(editor.commit()) { "Unable to commit migrated settings" }
    }

    private fun collectSshFiles(home: File, payloads: MutableMap<String, ByteArray>) {
        val ssh = File(home, ".ssh")
        if (!ssh.isDirectory || ssh.canonicalPath != ssh.absolutePath) return
        ssh.walkTopDown().maxDepth(2).filter { it.isFile }.forEach { file ->
            if (!safeRegularFile(ssh, file)) return@forEach
            val relative = file.relativeTo(home).invariantSeparatorsPath
            require(relative.split('/').all { safeSegment(it) })
            payloads["home/$relative"] = boundedBytes(file)
        }
    }

    private fun collectRegistry(home: File, payloads: MutableMap<String, ByteArray>): String? {
        val registry = File(home, ".local/share/wtmux/registry")
        val releases = File(registry, "releases")
        val current = File(registry, "current")
        if (!current.exists() || !releases.isDirectory) return null
        val release = runCatching { current.canonicalFile }.getOrNull() ?: return null
        if (release.parentFile?.canonicalFile != releases.canonicalFile || !release.name.matches(Regex("[a-f0-9]{16}"))) return null
        val manifest = File(release, "registry-manifest.json")
        val machines = File(release, "machines")
        if (!safeRegularFile(release, manifest) || !machines.isDirectory) return null
        val collected = linkedMapOf<String, ByteArray>()
        val files = sequenceOf(manifest) + machines.walkTopDown().filter { it.isFile }
        files.forEach { file ->
            if (!safeRegularFile(release, file) || file.extension != "json") return null
            val relative = file.relativeTo(release).invariantSeparatorsPath
            require(relative.split('/').all { safeSegment(it) })
            collected[relative] = boundedBytes(file)
        }
        validateRegistry(collected)
        collected.forEach { (relative, bytes) ->
            payloads["home/.local/share/wtmux/registry/releases/${release.name}/$relative"] = bytes
        }
        return release.name
    }

    private fun validateFileEntries(paths: Set<String>, registryRelease: String?) {
        paths.forEach { path ->
            require(path.startsWith("home/") || path.startsWith("preferences/")) {
                "Migration entry is outside the allowlist: $path"
            }
        }
        paths.filter { it.startsWith("home/") }.forEach { path ->
            val relative = path.removePrefix("home/")
            val fixed = relative in fixedHomeFiles
            val ssh = relative.startsWith(".ssh/") && relative.removePrefix(".ssh/").split('/').let {
                it.size in 1..2 && it.all(::safeSegment)
            }
            val registry = registryRelease != null &&
                relative.startsWith(".local/share/wtmux/registry/releases/$registryRelease/") &&
                relative.removePrefix(".local/share/wtmux/registry/releases/$registryRelease/")
                    .split('/').all(::safeSegment) && relative.endsWith(".json")
            require(fixed || ssh || registry) { "Migration file is outside the allowlist: $path" }
        }
        paths.filter { it.startsWith("preferences/") }.forEach { path ->
            require(path.removePrefix("preferences/").removeSuffix(".json") in preferenceStores)
        }
    }

    private fun installFiles(
        context: Context,
        entries: Map<String, ByteArray>,
        sourcePackage: String,
        registryRelease: String?
    ) {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val registryMachines = registryRelease?.let {
            File(home, ".local/share/wtmux/registry/current/machines").absolutePath
        }
        entries.forEach { (path, original) ->
            val relative = path.removePrefix("home/")
            val target = File(home, relative)
            require(target.canonicalPath.startsWith(home.canonicalPath + File.separator))
            val bytes = when (relative) {
                ".config/wtmux/wtmux.conf" ->
                    rewriteWtmuxConfig(
                        original.toString(Charsets.UTF_8), sourcePackage, context.packageName, registryMachines
                    ).toByteArray(Charsets.UTF_8)
                ".config/wtmux/client-policy.json" ->
                    rewriteClientPolicy(original, context.packageName)
                ".ssh/config" ->
                    rewriteRoot(original.toString(Charsets.UTF_8), sourcePackage, context.packageName).toByteArray(Charsets.UTF_8)
                else -> original
            }
            atomicWrite(target, bytes)
        }
        if (registryRelease != null) {
            val registry = File(home, ".local/share/wtmux/registry")
            val release = File(registry, "releases/$registryRelease")
            require(File(release, "registry-manifest.json").isFile) { "Migrated registry manifest was not activated" }
            require(File(release, "machines").isDirectory) { "Migrated registry machine directory was not activated" }
            validateRegistry(
                release.walkTopDown().filter { it.isFile }.associate {
                    it.relativeTo(release).invariantSeparatorsPath to boundedBytes(it)
                }
            )
            registry.mkdirs()
            val current = File(registry, "current")
            if (current.exists()) {
                require(current.canonicalPath != current.absolutePath) { "Existing registry pointer is unsafe" }
                check(current.delete()) { "Unable to replace registry pointer" }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                java.nio.file.Files.createSymbolicLink(current.toPath(), File("releases/$registryRelease").toPath())
            } else {
                Os.symlink("releases/$registryRelease", current.absolutePath)
            }
        }
        File(home, ".ssh").takeIf(File::isDirectory)?.apply {
            setReadable(false, false); setWritable(false, false); setExecutable(false, false)
            setReadable(true, true); setWritable(true, true); setExecutable(true, true)
        }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, ".${target.name}.migration-${UUID.randomUUID()}")
        try {
            temporary.outputStream().use { it.write(bytes); it.flush(); it.fdSync() }
            temporary.setReadable(false, false)
            temporary.setWritable(false, false)
            temporary.setExecutable(false, false)
            temporary.setReadable(true, true)
            temporary.setWritable(true, true)
            check(temporary.renameTo(target)) { "Unable to activate migrated file ${target.name}" }
        } finally {
            temporary.delete()
        }
    }

    private fun java.io.FileOutputStream.fdSync() = fd.sync()

    private fun readArchive(input: InputStream): LinkedHashMap<String, ByteArray> {
        val limited = LimitedInputStream(input, MAX_ARCHIVE_BYTES)
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(limited).use { zip ->
            var uncompressed = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && result.size < MAX_ENTRIES)
                val path = safeEntry(entry.name)
                require(path !in result) { "Migration contains a duplicate entry" }
                result[path] = readBounded(zip, MAX_ENTRY_BYTES).also {
                    uncompressed += it.size
                    require(uncompressed <= MAX_ARCHIVE_BYTES) { "Migration expands beyond the safety limit" }
                }
                zip.closeEntry()
            }
        }
        require(result.isNotEmpty()) { "Migration archive is empty" }
        return result
    }

    private fun safeEntry(value: String): String {
        require(value.length in 1..240 && !value.startsWith('/') && '\\' !in value)
        require(value.split('/').all(::safeSegment))
        return value
    }

    private fun safeSegment(value: String): Boolean =
        value.isNotEmpty() && value !in setOf(".", "..") && value.length <= 96 && value.none(Char::isISOControl)

    private fun safeRegularFile(root: File, file: File): Boolean = runCatching {
        file.isFile && file.length() in 0..MAX_ENTRY_BYTES &&
            file.canonicalPath == file.absolutePath &&
            file.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }.getOrDefault(false)

    private fun boundedBytes(file: File): ByteArray = file.inputStream().use { readBounded(it, MAX_ENTRY_BYTES) }

    private fun readBounded(input: InputStream, maximum: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maximum) { "Migration entry is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun writeZipEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply { time = 0L }
        zip.putNextEntry(entry)
        ByteArrayInputStream(bytes).copyTo(zip)
        zip.closeEntry()
    }

    private fun boundedString(value: String): String {
        require(value.length <= 256 * 1024 && '\u0000' !in value) { "Migration preference is too large" }
        return value
    }

    private fun rewriteRoot(value: String, source: String, destination: String): String =
        listOf("/data/data", "/data/user/0").fold(value) { rewritten, root ->
            rewritten.replace("$root/$source/", "$root/$destination/")
        }

    private fun rewriteWtmuxConfig(
        value: String,
        source: String,
        destination: String,
        registryMachines: String?
    ): String {
        val rooted = rewriteRoot(value, source, destination)
        if (registryMachines == null) return rooted

        val lines = rooted.split('\n').toMutableList().apply {
            if (lastOrNull().isNullOrEmpty()) removeAt(lastIndex)
        }
        val runtimeStarts = lines.indices.filter { lines[it] == RUNTIME_REGISTRY_BEGIN }
        val runtimeEnds = lines.indices.filter { lines[it] == RUNTIME_REGISTRY_END }
        require(runtimeStarts.size == runtimeEnds.size && runtimeStarts.size <= 1 &&
            (runtimeStarts.isEmpty() || runtimeStarts.single() < runtimeEnds.single())) {
            "Runtime registry config markers are malformed"
        }
        val managedStarts = lines.indices.filter { lines[it] == MANAGED_REGISTRY_BEGIN }
        val managedEnds = lines.indices.filter { lines[it] == MANAGED_REGISTRY_END }
        require(managedStarts.size == managedEnds.size && managedStarts.size <= 1 &&
            (managedStarts.isEmpty() || managedStarts.single() < managedEnds.single())) {
            "Managed registry config markers are malformed"
        }

        if (runtimeStarts.isNotEmpty()) {
            var start = runtimeStarts.single()
            var end = runtimeEnds.single()
            while (start > 0 && lines[start - 1].isBlank()) start--
            while (end + 1 < lines.size && lines[end + 1].isBlank()) end++
            lines.subList(start, end + 1).clear()
        }
        val insertion = lines.indexOf(MANAGED_REGISTRY_BEGIN).let { if (it >= 0) it else lines.size }
        val escapedRegistry = registryMachines.replace("'", "'\"'\"'")
        val block = mutableListOf<String>()
        if (insertion > 0 && lines[insertion - 1].isNotBlank()) block += ""
        block += RUNTIME_REGISTRY_BEGIN
        block += "WTMUX_SHARED_REGISTRY_DIR='$escapedRegistry'"
        block += RUNTIME_REGISTRY_END
        if (insertion < lines.size && lines[insertion].isNotBlank()) block += ""
        lines.addAll(insertion, block)
        return lines.joinToString("\n") + "\n"
    }

    private fun activeRegistryMachines(home: File): File? = runCatching {
        val registry = File(home, ".local/share/wtmux/registry")
        val releases = File(registry, "releases").canonicalFile
        val current = File(registry, "current")
        val release = current.canonicalFile
        require(release.parentFile?.canonicalFile == releases && File(release, "registry-manifest.json").isFile)
        require(File(release, "machines").isDirectory)
        File(current, "machines")
    }.getOrNull()

    private fun rewriteClientPolicy(bytes: ByteArray, destination: String): ByteArray {
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        val urls = value.getJSONArray("apkManifestUrls")
        repeat(urls.length()) { index ->
            val url = urls.getString(index)
            val rewritten = when (destination) {
                FLEET_PACKAGE -> if ("/agent-fleet/fleet/latest" in url) url
                    else url.replace("/agent-fleet/latest", "/agent-fleet/fleet/latest")
                LEGACY_PACKAGE -> url.replace("/agent-fleet/fleet/latest", "/agent-fleet/latest")
                else -> error("Unsupported migration destination")
            }
            urls.put(index, rewritten)
        }
        return value.toString(2).plus("\n").toByteArray(Charsets.UTF_8)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun validateRegistry(files: Map<String, ByteArray>) {
        val manifestBytes = files["registry-manifest.json"] ?: error("Registry manifest is missing")
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.getInt("formatVersion") == 1 && manifest.getInt("schemaVersion") == 1)
        val records = manifest.getJSONArray("records")
        require(records.length() in 1..128 && files.size == records.length() + 1)
        val expected = mutableSetOf<String>()
        repeat(records.length()) { index ->
            val record = records.getJSONObject(index)
            val path = safeEntry(record.getString("path"))
            require(path.startsWith("machines/") && path.endsWith(".json") && expected.add(path))
            val bytes = files[path] ?: error("Registry machine record is missing")
            require(record.getLong("size") == bytes.size.toLong() && record.getString("sha256") == sha256(bytes))
            JSONObject(bytes.toString(Charsets.UTF_8))
        }
        require(files.keys == expected + "registry-manifest.json")
    }

    private class LimitedInputStream(input: InputStream, private val limit: Long) : java.io.FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) add(it.toLong()) }
        private fun add(value: Long) { count += value; require(count <= limit) { "Migration archive is too large" } }
    }

    private class LimitedOutputStream(output: OutputStream, private val limit: Long) : java.io.FilterOutputStream(output) {
        private var count = 0L
        override fun write(value: Int) { add(1); super.write(value) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { add(length.toLong()); out.write(buffer, offset, length) }
        private fun add(value: Long) { count += value; require(count <= limit) { "Migration archive is too large" } }
    }
}
