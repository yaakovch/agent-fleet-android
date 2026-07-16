package com.termux.app.fleet

data class AgentFleetAttachmentCandidate(
    val handle: String,
    val running: Boolean,
    val order: Int
)

object AgentFleetAttachmentPolicy {
    const val MAX_RETAINED_ATTACHMENTS = 4

    @JvmStatic
    fun sessionId(commandDescription: String?): String? {
        if (commandDescription == null || !commandDescription.startsWith(AgentFleetContract.WORKSPACE_SESSION_PREFIX)) return null
        return commandDescription.removePrefix(AgentFleetContract.WORKSPACE_SESSION_PREFIX)
            .takeIf { it.matches(Regex("[A-Za-z0-9._: -]{1,180}")) }
    }

    @JvmStatic
    fun canonicalIndex(candidates: List<AgentFleetAttachmentCandidate>, currentHandle: String?): Int {
        val current = candidates.indexOfFirst { it.running && it.handle == currentHandle }
        if (current >= 0) return current
        return candidates.indices.firstOrNull { candidates[it].running } ?: -1
    }

    @JvmStatic
    fun evictionOrder(
        sessionIds: Collection<String>,
        lastUsed: Map<String, Long>,
        protectedIds: Collection<String>,
        currentId: String?,
        limit: Int = MAX_RETAINED_ATTACHMENTS
    ): List<String> {
        val protected = protectedIds.toSet() + listOfNotNull(currentId)
        val candidates = sessionIds.distinct().filterNot(protected::contains)
            .sortedWith(compareBy<String> { lastUsed[it] ?: Long.MIN_VALUE }.thenBy { it })
        return candidates.take((sessionIds.distinct().size - limit).coerceAtLeast(0))
    }
}
