package com.termux.app.fleet

internal data class ModelControlRequestTicket(val id: Long)

internal enum class ModelControlRefreshDisposition { STARTED, QUEUED, IGNORED }

internal data class ModelControlRefreshDecision(
    val disposition: ModelControlRefreshDisposition,
    val ticket: ModelControlRequestTicket? = null
)

internal class ModelControlRequestGate {
    private var nextId = 1L
    private var activeId: Long? = null
    private var catalogPending = false

    @Synchronized
    fun beginRefresh(includeCatalog: Boolean): ModelControlRefreshDecision {
        if (activeId != null) {
            if (includeCatalog) {
                catalogPending = true
                return ModelControlRefreshDecision(ModelControlRefreshDisposition.QUEUED)
            }
            return ModelControlRefreshDecision(ModelControlRefreshDisposition.IGNORED)
        }
        val ticket = ModelControlRequestTicket(nextId++)
        activeId = ticket.id
        if (includeCatalog) catalogPending = false
        return ModelControlRefreshDecision(ModelControlRefreshDisposition.STARTED, ticket)
    }

    @Synchronized
    fun beginExclusive(): ModelControlRequestTicket? {
        if (activeId != null) return null
        return ModelControlRequestTicket(nextId++).also { activeId = it.id }
    }

    @Synchronized
    fun complete(ticket: ModelControlRequestTicket): Boolean {
        if (activeId != ticket.id) return false
        activeId = null
        return catalogPending.also { catalogPending = false }
    }

    @Synchronized
    fun reset() {
        activeId = null
        catalogPending = false
        nextId++
    }
}
