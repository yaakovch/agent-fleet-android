package com.termux.app.fleet

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object FleetSnapshotParser {
    private const val MAX_SNAPSHOT_BYTES = 256 * 1024
    private const val MAX_COLLECTION_ITEMS = 2_000

    fun parse(json: String): FleetSnapshot {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_SNAPSHOT_BYTES) { "Fleet snapshot is too large" }
        val root = try {
            JSONObject(json)
        } catch (error: JSONException) {
            throw IllegalArgumentException("Fleet snapshot is not valid JSON", error)
        }
        val identityFields = setOf("fleetId", "physicalHosts", "endpoints", "executionTargets")
        val identityFieldCount = identityFields.count(root::has)
        require(identityFieldCount == 0 || identityFieldCount == identityFields.size) {
            "Identity graph fields are incomplete"
        }
        val hasIdentityGraph = identityFieldCount == identityFields.size
        root.requireExactFields(
            required = setOf("revision", "generatedAt", "hosts", "sessions", "schedules", "attention"),
            optional = setOf("presentationRevision", "limits", "presets", "pairingRequests") + identityFields
        )
        rejectPrivateFields(root)

        val presentationRevision = if (root.has("presentationRevision")) root.requiredString("presentationRevision", 64) else null
        return FleetSnapshot(
            revision = root.requiredString("revision", 64),
            presentationRevision = presentationRevision,
            generatedAt = root.requiredString("generatedAt", 40),
            hosts = root.requiredArray("hosts", 256).mapObjects { host ->
                host.requireExactFields(setOf(
                    "id", "name", "platform", "transport", "status", "lastSeenAt", "errorCode", "capabilities",
                    "wtmuxVersion", "agentVersion", "protocolVersion", "timeZone"
                ))
                host.requiredString("transport", 16)
                host.requiredString("errorCode", 64, allowEmpty = true)
                host.requiredString("wtmuxVersion", 64, allowEmpty = true)
                host.requiredString("agentVersion", 64, allowEmpty = true)
                host.requiredInt("protocolVersion", 1, 1)
                host.requiredString("timeZone", 64, allowEmpty = true)
                FleetHost(
                    id = host.requiredString("id", 160),
                    name = host.requiredString("name", 128),
                    status = host.requiredString("status", 32),
                    platform = host.requiredString("platform", 32),
                    lastSeenAt = host.optionalString("lastSeenAt", 40),
                    capabilities = host.requiredArray("capabilities", 32).mapStrings(64).toSet()
                )
            },
            sessions = root.requiredArray("sessions", 500).mapObjects { session ->
                session.requireExactFields(
                    required = setOf(
                        "id", "hostId", "internalName", "name", "title", "project", "tool", "backend", "activity",
                        "attached", "updatedAt", "pendingScheduleCount"
                    ),
                    optional = setOf("nameMode", "projectPath", "locationKind", "physicalHostId", "executionTargetId")
                )
                require(session.has("projectPath") == session.has("locationKind")) { "Incomplete session path metadata" }
                require(session.has("physicalHostId") == hasIdentityGraph && session.has("executionTargetId") == hasIdentityGraph) {
                    "Session identity graph fields are incomplete or unnegotiated"
                }
                FleetSession(
                    id = session.requiredString("id", 320),
                    hostId = session.requiredString("hostId", 160),
                    internalName = session.requiredString("internalName", 96),
                    name = session.requiredString("name", 128),
                    title = session.requiredString("title", 120, allowEmpty = true).also { title ->
                        require(title.isEmpty() || presentationRevision != null && session.has("nameMode")) {
                            "Session title was not negotiated"
                        }
                    },
                    nameMode = if (session.has("nameMode")) session.requiredString("nameMode", 16).also {
                        require(it in setOf("automatic", "manual")) { "Invalid nameMode" }
                    } else "automatic",
                    project = session.requiredString("project", 128, allowEmpty = true),
                    tool = session.requiredString("tool", 32),
                    backend = session.requiredString("backend", 32),
                    activity = session.requiredString("activity", 32),
                    attached = session.requiredBoolean("attached"),
                    updatedAt = session.optionalString("updatedAt", 40),
                    pendingScheduleCount = session.requiredInt("pendingScheduleCount", 0, 10_000),
                    projectPath = if (session.has("projectPath")) session.requiredString("projectPath", 2_048, allowEmpty = true) else "",
                    locationKind = if (session.has("locationKind")) session.requiredString("locationKind", 16) else "project",
                    physicalHostId = if (hasIdentityGraph) session.requiredString("physicalHostId", 160) else session.requiredString("hostId", 160),
                    executionTargetId = if (hasIdentityGraph) session.requiredString("executionTargetId", 16).also {
                        require(it in setOf("linux", "windows")) { "Invalid executionTargetId" }
                    } else if (session.requiredString("backend", 32) == "windows") "windows" else "linux"
                )
            },
            schedules = root.requiredArray("schedules", 500).mapObjects { schedule ->
                schedule.requireExactFields(setOf(
                    "id", "hostId", "sessionId", "kind", "backend", "agent", "deliverAt", "status", "createdAt",
                    "updatedAt", "completedAt", "outcomeCode"
                ))
                require(schedule.requiredString("kind", 32) == "scheduled-message")
                schedule.requiredString("backend", 16)
                schedule.requiredString("createdAt", 40, allowEmpty = true)
                schedule.requiredString("updatedAt", 40, allowEmpty = true)
                schedule.optionalString("completedAt", 40)
                schedule.requiredString("outcomeCode", 64, allowEmpty = true)
                FleetSchedule(
                    id = schedule.requiredString("id", 160),
                    hostId = schedule.requiredString("hostId", 160),
                    sessionId = schedule.requiredString("sessionId", 320),
                    deliverAt = schedule.requiredString("deliverAt", 40),
                    status = schedule.requiredString("status", 32)
                )
            },
            attention = root.requiredArray("attention", 500).mapObjects { attention ->
                attention.requireExactFields(setOf(
                    "id", "hostId", "kind", "sessionId", "agent", "resetAt", "state", "detectedAt", "updatedAt"
                ))
                require(attention.requiredString("kind", 32) == "hard-limit")
                attention.optionalString("detectedAt", 40)
                attention.optionalString("updatedAt", 40)
                FleetAttention(
                    id = attention.requiredString("id", 160),
                    hostId = attention.requiredString("hostId", 160),
                    sessionId = attention.requiredString("sessionId", 320),
                    agent = attention.requiredString("agent", 32),
                    resetAt = attention.optionalString("resetAt", 40),
                    state = attention.requiredString("state", 32)
                )
            }.filter { it.state in setOf("detected", "offering", "offered") },
            limits = root.optionalArray("limits", 100).mapObjects { limit ->
                limit.requireExactFields(setOf(
                    "id", "hostId", "provider", "profileAlias", "status", "primary", "secondary", "updatedAt"
                ))
                FleetLimit(
                    id = limit.requiredString("id", 160),
                    hostId = limit.requiredString("hostId", 160),
                    provider = limit.requiredString("provider", 32),
                    profileAlias = limit.requiredString("profileAlias", 64),
                    status = limit.requiredString("status", 32),
                    primary = limit.optionalWindow("primary"),
                    secondary = limit.optionalWindow("secondary"),
                    updatedAt = limit.requiredString("updatedAt", 40)
                )
            }
        ).let { parsedSnapshot ->
            val snapshot = if (hasIdentityGraph) parseIdentityGraph(root, parsedSnapshot) else parsedSnapshot
            root.optionalArray("presets", 100).mapObjects { preset ->
                preset.requireExactFields(setOf("id", "name", "hostId", "project", "backend", "tool", "profileAlias"))
                preset.requiredString("id", 160)
                preset.requiredString("name", 128)
                preset.requiredString("hostId", 160)
                preset.requiredString("project", 128)
                require(preset.requiredString("backend", 16) in setOf("linux", "windows"))
                require(preset.requiredString("tool", 16) in setOf("shell", "codex", "claude", "copilot"))
                preset.requiredString("profileAlias", 64, allowEmpty = true)
            }
            root.optionalArray("pairingRequests", 256).mapObjects { pairing ->
                pairing.requireExactFields(setOf("id", "deviceName", "platform", "peer", "requestedAt", "expiresAt", "status"))
                pairing.requiredString("id", 160)
                pairing.requiredString("deviceName", 128)
                pairing.requiredString("platform", 32)
                pairing.requiredString("peer", 253)
                pairing.requiredString("requestedAt", 40)
                pairing.requiredString("expiresAt", 40)
                require(pairing.requiredString("status", 32) in setOf("awaiting-review", "approved", "rejected"))
            }
            val hostIds = snapshot.hosts.map { it.id }.toSet()
            require(snapshot.sessions.all { it.hostId in hostIds }) { "Session references an unknown host" }
            require(snapshot.sessions.map { it.id }.toSet().size == snapshot.sessions.size) { "Duplicate session id" }
            require(snapshot.limits.all { it.hostId in hostIds }) { "Limit profile references an unknown host" }
            snapshot
        }
    }

    private fun parseIdentityGraph(root: JSONObject, snapshot: FleetSnapshot): FleetSnapshot {
        val fleetId = root.requiredString("fleetId", 160)
        val physicalHosts = root.requiredArray("physicalHosts", 256).mapObjects { host ->
            host.requireExactFields(setOf(
                "id", "name", "platform", "status", "lastSeenAt", "errorCode",
                "endpointIds", "executionTargetIds", "legacyHostIds"
            ))
            FleetPhysicalHost(
                id = host.requiredString("id", 160),
                name = host.requiredString("name", 256),
                platform = host.requiredString("platform", 32).also {
                    require(it in setOf("wsl", "linux", "termux")) { "Invalid physical host platform" }
                },
                status = host.requiredString("status", 32).also {
                    require(it in setOf("healthy", "connecting", "offline")) { "Invalid physical host status" }
                },
                lastSeenAt = host.optionalString("lastSeenAt", 40),
                errorCode = host.requiredString("errorCode", 64, allowEmpty = true),
                endpointIds = host.requiredArray("endpointIds", 16).mapStrings(160).also(::requireUnique),
                executionTargetIds = host.requiredArray("executionTargetIds", 16).mapStrings(16).also { ids ->
                    requireUnique(ids)
                    require(ids.all { it in setOf("linux", "windows") }) { "Invalid execution target id" }
                },
                legacyHostIds = host.requiredArray("legacyHostIds", 16).mapStrings(160).also(::requireUnique)
            )
        }
        requireUnique(physicalHosts.map(FleetPhysicalHost::id))
        requireUnique(physicalHosts.flatMap(FleetPhysicalHost::legacyHostIds))
        val physicalIds = physicalHosts.map(FleetPhysicalHost::id).toSet()
        val legacyAliases = physicalHosts.flatMap(FleetPhysicalHost::legacyHostIds).toSet()
        require(snapshot.hosts.all { it.id in legacyAliases }) { "Legacy host is missing from the identity graph" }

        val endpoints = root.requiredArray("endpoints", 512).mapObjects { endpoint ->
            endpoint.requireExactFields(setOf(
                "id", "physicalHostId", "network", "address", "port", "sshEngine", "authentication",
                "status", "identityState", "sshHostKeySha256", "tailscaleNodeId", "errorCode"
            ))
            FleetEndpoint(
                id = endpoint.requiredString("id", 160),
                physicalHostId = endpoint.requiredString("physicalHostId", 160).also {
                    require(it in physicalIds) { "Endpoint references an unknown physical host" }
                },
                network = endpoint.requiredString("network", 16).also {
                    require(it in setOf("local", "tailnet", "direct")) { "Invalid endpoint network" }
                },
                address = endpoint.requiredString("address", 253),
                port = endpoint.requiredInt("port", 1, 65_535),
                sshEngine = endpoint.requiredString("sshEngine", 32).also {
                    require(it in setOf("openssh", "tailscale-cli")) { "Invalid SSH engine" }
                },
                authentication = endpoint.requiredString("authentication", 32).also {
                    require(it in setOf("tailnet-ssh", "key")) { "Invalid endpoint authentication" }
                },
                status = endpoint.requiredString("status", 32).also {
                    require(it in setOf("healthy", "connecting", "offline")) { "Invalid endpoint status" }
                },
                identityState = endpoint.requiredString("identityState", 32).also {
                    require(it in setOf("verified", "unverified", "reverify-required")) { "Invalid endpoint identity state" }
                },
                sshHostKeySha256 = endpoint.requiredString("sshHostKeySha256", 96, allowEmpty = true),
                tailscaleNodeId = endpoint.requiredString("tailscaleNodeId", 128, allowEmpty = true),
                errorCode = endpoint.requiredString("errorCode", 64, allowEmpty = true)
            )
        }
        requireUnique(endpoints.map(FleetEndpoint::id))

        val targets = root.requiredArray("executionTargets", 512).mapObjects { target ->
            target.requireExactFields(setOf("id", "physicalHostId", "kind", "label", "status", "fingerprint"))
            val id = target.requiredString("id", 16).also {
                require(it in setOf("linux", "windows")) { "Invalid execution target id" }
            }
            val kind = target.requiredString("kind", 32).also {
                require(it in setOf("linux", "windows-git-bash")) { "Invalid execution target kind" }
            }
            require((id == "linux") == (kind == "linux")) { "Execution target kind does not match its id" }
            FleetExecutionTarget(
                id = id,
                physicalHostId = target.requiredString("physicalHostId", 160).also {
                    require(it in physicalIds) { "Execution target references an unknown physical host" }
                },
                kind = kind,
                label = target.requiredString("label", 128),
                status = target.requiredString("status", 32).also {
                    require(it in setOf("available", "unavailable", "unknown")) { "Invalid execution target status" }
                },
                fingerprint = target.requiredString("fingerprint", 160, allowEmpty = true)
            )
        }
        requireUnique(targets.map { "${it.physicalHostId}:${it.id}" })

        physicalHosts.forEach { host ->
            require(host.endpointIds.all { endpointId ->
                endpoints.any { it.id == endpointId && it.physicalHostId == host.id }
            }) { "Physical host references an unknown endpoint" }
            require(host.executionTargetIds.all { targetId ->
                targets.any { it.id == targetId && it.physicalHostId == host.id }
            }) { "Physical host references an unknown execution target" }
        }
        snapshot.sessions.forEach { session ->
            val physicalHost = physicalHosts.firstOrNull { it.id == session.physicalHostId }
            require(physicalHost != null && session.hostId in physicalHost.legacyHostIds) {
                "Session physical host identity is inconsistent"
            }
            require(targets.any {
                it.physicalHostId == session.physicalHostId && it.id == session.executionTargetId
            }) { "Session references an unknown execution target" }
            require(session.executionTargetId == session.backend) { "Session backend and execution target differ" }
        }
        return snapshot.copy(
            fleetId = fleetId,
            physicalHosts = physicalHosts,
            endpoints = endpoints,
            executionTargets = targets
        )
    }

    private fun requireUnique(values: List<String>) {
        require(values.toSet().size == values.size) { "Identity graph contains a duplicate id" }
    }

    private fun JSONObject.requiredArray(name: String, maximum: Int = MAX_COLLECTION_ITEMS): JSONArray =
        try {
            getJSONArray(name).also { require(it.length() <= maximum) { "$name has too many entries" } }
        } catch (error: JSONException) {
            throw IllegalArgumentException("Missing or invalid $name", error)
        }

    private fun JSONObject.optionalArray(name: String, maximum: Int = MAX_COLLECTION_ITEMS): JSONArray =
        if (has(name)) requiredArray(name, maximum) else JSONArray()

    private fun JSONObject.optionalWindow(name: String): FleetLimitWindow? {
        if (isNull(name)) return null
        val value = try { getJSONObject(name) } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid $name window", error)
        }
        value.requireExactFields(setOf("usedPercent", "remainingPercent", "resetsAt", "windowMinutes"))
        return FleetLimitWindow(
            usedPercent = value.requiredDouble("usedPercent", 0.0, 100.0),
            remainingPercent = value.requiredDouble("remainingPercent", 0.0, 100.0),
            resetsAt = value.requiredString("resetsAt", 40),
            windowMinutes = value.requiredInt("windowMinutes", 1, 525_600)
        )
    }

    private fun JSONObject.requiredString(name: String, max: Int, allowEmpty: Boolean = false): String {
        val value = try { getString(name) } catch (error: JSONException) {
            throw IllegalArgumentException("Missing or invalid $name", error)
        }
        require(value.length <= max && (allowEmpty || value.isNotBlank()) && value.none { it.isISOControl() }) {
            "Invalid $name"
        }
        return value
    }

    private fun JSONObject.optionalString(name: String, max: Int): String? {
        if (isNull(name)) return null
        return requiredString(name, max, allowEmpty = true)
    }

    private fun JSONObject.requiredBoolean(name: String): Boolean = try {
        getBoolean(name)
    } catch (error: JSONException) {
        throw IllegalArgumentException("Missing or invalid $name", error)
    }

    private fun JSONObject.requiredInt(name: String, min: Int, max: Int): Int {
        val value = try { getInt(name) } catch (error: JSONException) {
            throw IllegalArgumentException("Missing or invalid $name", error)
        }
        require(value in min..max) { "Invalid $name" }
        return value
    }

    private fun JSONObject.requiredDouble(name: String, min: Double, max: Double): Double {
        val value = try { getDouble(name) } catch (error: JSONException) {
            throw IllegalArgumentException("Missing or invalid $name", error)
        }
        require(value in min..max) { "Invalid $name" }
        return value
    }

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { index ->
            val value = try { getJSONObject(index) } catch (error: JSONException) {
                throw IllegalArgumentException("Collection entry is not an object", error)
            }
            transform(value)
        }

    private fun JSONArray.mapStrings(max: Int): List<String> = (0 until length()).map { index ->
        val value = try { getString(index) } catch (error: JSONException) {
            throw IllegalArgumentException("Collection entry is not a string", error)
        }
        require(value.isNotBlank() && value.length <= max && value.none { it.isISOControl() }) { "Invalid string entry" }
        value
    }

    private fun JSONObject.requireExactFields(required: Set<String>, optional: Set<String> = emptySet()) {
        val actual = keys().asSequence().toSet()
        require(required.all(actual::contains) && actual.all { it in required || it in optional }) { "Invalid object fields" }
    }

    private fun rejectPrivateFields(value: Any?) {
        when (value) {
            is JSONObject -> value.keys().asSequence().forEach { key ->
                require(key.lowercase() !in setOf("message", "prompt", "output", "transcript", "panetitle", "command")) {
                    "Private field is forbidden"
                }
                rejectPrivateFields(value.opt(key))
            }
            is JSONArray -> (0 until value.length()).forEach { rejectPrivateFields(value.opt(it)) }
        }
    }
}
