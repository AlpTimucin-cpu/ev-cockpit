package com.evcockpit.mediahub

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Local, driver-safe podcast catalogue for Android Auto.
 * No RSS URL, stream URL or token is placed into car-facing media IDs.
 * Feeds are added on the phone; their audio episodes are cached when opened.
 */
object PodcastCarLibrary {
    const val ROOT = "evcockpit_podcast_root"
    const val FEED_PREFIX = "evcockpit_podcast_feed_"
    const val EPISODE_PREFIX = "evcockpit_podcast_episode_"
    private const val PREFS = "evcockpit_car_podcast_cache_v1"
    private const val MAX_EPISODES = 60

    private fun shortId(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun feedId(feed: PodcastFeed) = FEED_PREFIX + shortId(feed.url)
    fun episodeId(episode: PodcastEpisode) = EPISODE_PREFIX + shortId(episode.link)

    private fun allowedAudio(episode: PodcastEpisode): Boolean {
        if (!M3uParser.isHttpsUrl(episode.link)) return false
        val mime = episode.mimeType.lowercase().substringBefore(';').trim()
        if (mime.startsWith("video/")) return false
        if (mime.startsWith("audio/")) return true
        val path = try { Uri.parse(episode.link).path.orEmpty().lowercase() }
                   catch (_: Exception) { "" }
        return listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav")
            .any { path.endsWith(it) }
    }

    fun feeds(context: Context): List<PodcastFeed> = PodcastSources.feeds(context)

    fun cache(context: Context, feed: PodcastFeed, candidates: List<PodcastEpisode>) {
        val array = JSONArray()
        candidates.asSequence().filter(::allowedAudio).distinctBy { it.link }
            .take(MAX_EPISODES).forEach { episode ->
                array.put(JSONObject()
                    .put("name", episode.title.take(180))
                    .put("url", episode.link)
                    .put("type", episode.mimeType.take(80)))
            }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(feedId(feed), array.toString()).apply()
        // Refresh Android Auto's subscribed podcast tree; never broadcast URLs.
        context.sendBroadcast(android.content.Intent(
            AudioLibraryService.ACTION_CATALOG_UPDATED).setPackage(context.packageName))
    }

    fun episodes(context: Context, feed: PodcastFeed): List<PodcastEpisode> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(feedId(feed), null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until minOf(MAX_EPISODES, array.length())) {
                    val item = array.optJSONObject(i) ?: continue
                    val episode = PodcastEpisode(
                        item.optString("name").take(180),
                        item.optString("url"),
                        feed.title,
                        item.optString("type")
                    )
                    if (episode.title.isNotBlank() && allowedAudio(episode)) add(episode)
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun findFeed(context: Context, id: String): PodcastFeed? =
        feeds(context).firstOrNull { feedId(it) == id }

    fun findEpisode(context: Context, id: String): PodcastEpisode? {
        if (!id.startsWith(EPISODE_PREFIX)) return null
        return feeds(context).asSequence()
            .flatMap { episodes(context, it).asSequence() }
            .firstOrNull { episodeId(it) == id }
    }

    fun feedItem(feed: PodcastFeed): MediaItem = MediaItem.Builder()
        .setMediaId(feedId(feed))
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(feed.title).setSubtitle("Sesli podcast bölümleri")
            .setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST)
            .build()).build()

    fun rootItem(): MediaItem = MediaItem.Builder().setMediaId(ROOT)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle("Podcastlar").setSubtitle("Telefonda eklenen RSS kaynakları")
            .setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS)
            .build()).build()

    fun episodeItem(episode: PodcastEpisode, includeUri: Boolean = false): MediaItem =
        MediaItem.Builder().setMediaId(episodeId(episode))
            .also { if (includeUri) it.setUri(Uri.parse(episode.link)) }
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(episode.title).setSubtitle(episode.source)
                .setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .build()).build()
}
