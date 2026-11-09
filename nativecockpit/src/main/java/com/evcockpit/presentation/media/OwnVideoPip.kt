package com.evcockpit.presentation.media

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational

/**
 * Optional helper for an Activity that owns its video playback and declares
 * android:supportsPictureInPicture="true" in its manifest.
 *
 * It cannot put Netflix, Disney+ or Prime Video into PiP: external apps
 * control their own playback, DRM and PiP policy.
 *
 * Requires an authoritative Park state. Stopped at a red light is not Park.
 */
fun Activity.enterOwnVideoPipWhenParked(
    verifiedParked: Boolean,
    ownsActiveVideo: Boolean
): Boolean {
    if (!verifiedParked || !ownsActiveVideo) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    if (!packageManager.hasSystemFeature(
        PackageManager.FEATURE_PICTURE_IN_PICTURE
    )) return false

    val parameters = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .build()

    return try {
        enterPictureInPictureMode(parameters)
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
