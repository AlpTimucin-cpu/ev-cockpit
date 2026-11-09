package com.evcockpit.mediahub

import java.util.Locale

/**
 * APTV-style electronic programme guide index.
 * Pure Kotlin: one scan for the whole list, O(1) lookup per visible TV tile.
 * Independent implementation; no third-party proprietary or GPL source copied.
 */
class EpgScheduleIndex(
    programmes: List<EpgProgramme>,
    private val nowMs: Long
) {
    private data class Slot(
        val now: EpgProgramme?,
        val next: EpgProgramme?
    )

    private val slots: Map<String, Slot> = programmes
        .filter { it.channelId.isNotBlank() && it.stopMs > it.startMs }
        .groupBy { it.channelId.trim().lowercase(Locale.ROOT) }
        .mapValues { (_, shows) ->
            val current = shows.asSequence()
                .filter { it.startMs <= nowMs && nowMs < it.stopMs }
                // When EPG providers overlap, use the latest valid start.
                .maxByOrNull { it.startMs }
            val following = shows.asSequence()
                .filter { it.startMs > nowMs }
                .minByOrNull { it.startMs }
            Slot(current, following)
        }

    private fun key(tvgId: String): String = tvgId.trim().lowercase(Locale.ROOT)

    fun current(tvgId: String): EpgProgramme? =
        slots[key(tvgId)]?.now

    fun next(tvgId: String): EpgProgramme? =
        slots[key(tvgId)]?.next

    fun hasCurrent(tvgId: String): Boolean = current(tvgId) != null

    fun progressPercent(tvgId: String): Int {
        val show = current(tvgId) ?: return 0
        val duration = show.stopMs - show.startMs
        if (duration <= 0) return 0
        return ((nowMs - show.startMs).coerceIn(0L, duration) * 100L / duration).toInt()
    }

    fun matchingProgramme(tvgId: String, query: String): Boolean {
        val normalized = query.trim()
        if (normalized.isBlank()) return true
        val slot = slots[key(tvgId)] ?: return false
        return slot.now?.title?.contains(normalized, ignoreCase = true) == true ||
            slot.next?.title?.contains(normalized, ignoreCase = true) == true
    }
}
