package com.evcockpit.mediahub

import android.content.Context

/**
 * VOD resume position for channels explicitly added by the user.
 * Keys are local channel IDs only; tokens/URLs/DRM data are never retained here.
 */
object VodProgressStore {
    private const val PREFS = "evcockpit_vod_resume_v1"
    private const val MAX_POSITION_MS = 24L * 60L * 60L * 1000L

    fun position(context: Context, channelId: String): Long {
        if (channelId.isBlank()) return 0L
        val position = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong("position:" + channelId, 0L)
        return position.takeIf { it in 10_000L..MAX_POSITION_MS } ?: 0L
    }

    fun save(context: Context, channelId: String, positionMs: Long, durationMs: Long) {
        if (channelId.isBlank()) return
        if (positionMs < 10_000L || positionMs > MAX_POSITION_MS ||
            durationMs > 0L && durationMs - positionMs < 30_000L) {
            clear(context, channelId)
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("position:" + channelId, positionMs).apply()
    }

    fun clear(context: Context, channelId: String) {
        if (channelId.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("position:" + channelId).apply()
    }
}
