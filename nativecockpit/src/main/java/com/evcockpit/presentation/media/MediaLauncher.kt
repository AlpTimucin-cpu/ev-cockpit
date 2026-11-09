package com.evcockpit.presentation.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

sealed interface MediaLaunchState {
    data class Launched(val packageName: String) : MediaLaunchState
    data object NotInstalled : MediaLaunchState
    data object Blocked : MediaLaunchState
}

/**
 * Audio-only launch shortcuts from the uploaded reference ZIP.
 * Installed official apps own subscriptions and playback. No video/DRM bypass.
 */
enum class OfficialMusicApp(val title: String, val packageId: String) {
    SPOTIFY("Spotify", "com.spotify.music"),
    YOUTUBE_MUSIC("YouTube Music", "com.google.android.apps.youtube.music")
}

/** External DRM players keep their own playback, focus and picture-in-picture lifecycle. */
object MediaLauncher {
    // Real package IDs must match the installed media-engine APK.
    val mediaEnginePackages = listOf(
        "me.app.fermataX.auto.evcockpitpreview.debug",
        "me.app.fermataX.auto.evcockpitpreview"
    )

    /** No package name guessing; every candidate is declared in manifest queries. */
    fun launchMediaEngine(context: Context): MediaLaunchState {
        var blocked = false
        for (packageName in mediaEnginePackages) {
            when (val result = launchPackage(context, packageName)) {
                is MediaLaunchState.Launched -> return result
                MediaLaunchState.Blocked -> blocked = true
                MediaLaunchState.NotInstalled -> Unit
            }
        }
        return if (blocked) MediaLaunchState.Blocked else MediaLaunchState.NotInstalled
    }

    fun launchOfficialApp(context: Context, site: MediaSite): MediaLaunchState =
        when (site) {
            MediaSite.NETFLIX -> launchPackage(context, "com.netflix.mediaclient")
            MediaSite.DISNEY -> launchPackage(context, "com.disney.disneyplus")
            MediaSite.PRIME -> launchPackage(context, "com.amazon.avod.thirdpartyclient")
            // YouTube deliberately uses our protected in-app WebView.
            MediaSite.YOUTUBE -> MediaLaunchState.Blocked
        }

    /** Phone media tab only. Does not expose Android Auto templates. */
    fun launchOfficialMusicApp(context: Context, app: OfficialMusicApp): MediaLaunchState =
        launchPackage(context, app.packageId)

    /** Compatibility for existing callers expecting Result<Unit>. */
    fun openOfficialApp(context: Context, site: MediaSite): Result<Unit> =
        when (val result = launchOfficialApp(context, site)) {
            is MediaLaunchState.Launched -> Result.success(Unit)
            MediaLaunchState.NotInstalled -> Result.failure(
                ActivityNotFoundException("External media application is not installed")
            )
            MediaLaunchState.Blocked -> Result.failure(
                SecurityException("External media application launch is restricted")
            )
        }

    private fun launchPackage(context: Context, packageName: String): MediaLaunchState {
        val manager = context.packageManager
        try {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(packageName, 0)
            val intent = manager.getLaunchIntentForPackage(packageName)
                ?: manager.getLeanbackLaunchIntentForPackage(packageName)
                ?: return MediaLaunchState.Blocked

            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return MediaLaunchState.Launched(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            return MediaLaunchState.NotInstalled
        } catch (_: ActivityNotFoundException) {
            // Application was disabled/uninstalled after package lookup.
            return MediaLaunchState.Blocked
        } catch (error: SecurityException) {
            Log.w("EYA-MEDIA-UAT", "Restricted external media launch", error)
            return MediaLaunchState.Blocked
        } catch (error: RuntimeException) {
            Log.w("EYA-MEDIA-UAT", "External media launch failed", error)
            return MediaLaunchState.Blocked
        }
    }
}
