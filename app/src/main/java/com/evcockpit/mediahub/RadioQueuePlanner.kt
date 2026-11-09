package com.evcockpit.mediahub

/** Pure Kotlin model; never includes stream secrets in results or logs. */
data class RadioSource(val id: String, val name: String, val url: String)
data class RadioRequest(val id: String = "", val uri: String? = null, val search: String? = null)
data class RadioQueue(val ids: List<String>, val index: Int)

/** Resolve radio MediaSession requests without accidentally accepting arbitrary video URLs. */
object RadioQueuePlanner {
    fun resolve(sources: List<RadioSource>, request: RadioRequest): RadioSource? {
        if (request.id.isNotBlank()) sources.firstOrNull { it.id == request.id }?.let { return it }
        if (request.uri != null) sources.firstOrNull { it.url == request.uri }?.let { return it }
        val query = request.search?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return sources.firstOrNull { it.name.equals(query, ignoreCase = true) }
            ?: sources.firstOrNull { it.name.contains(query, ignoreCase = true) }
    }

    /** Android binder transactions and player memory must not carry thousands of stations at once. */
    const val MAX_QUEUE_ITEMS = 41

    fun plan(sources: List<RadioSource>, requests: List<RadioRequest>, startIndex: Int): RadioQueue? {
        if (sources.isEmpty() || requests.isEmpty()) return null
        val selectedRequest = requests.getOrNull(if (startIndex < 0) 0 else startIndex)
            ?: requests.first()
        val selected = resolve(sources, selectedRequest) ?: return null
        val ids = if (requests.size == 1) {
            // Keep a bounded window around the requested station while preserving list order.
            val index = sources.indexOfFirst { it.id == selected.id }
            val windowSize = minOf(MAX_QUEUE_ITEMS, sources.size)
            val from = (index - windowSize / 2).coerceIn(0, sources.size - windowSize)
            sources.subList(from, from + windowSize).map { it.id }
        } else {
            // A caller can also supply a long queue: retain the requested item and neighbours.
            val resolved = requests.mapNotNull { resolve(sources, it)?.id }.distinct()
            val index = resolved.indexOf(selected.id)
            if (index < 0) return null
            val windowSize = minOf(MAX_QUEUE_ITEMS, resolved.size)
            val from = (index - windowSize / 2).coerceIn(0, resolved.size - windowSize)
            resolved.subList(from, from + windowSize)
        }
        val resultIndex = ids.indexOf(selected.id)
        if (resultIndex == -1) return null
        return RadioQueue(ids, resultIndex)
    }
}
