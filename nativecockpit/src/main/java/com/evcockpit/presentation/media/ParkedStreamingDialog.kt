package com.evcockpit.presentation.media

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Compatibility entry point: always use the embedded Mola-style media hub.
 * No external media-engine Intent or companion application.
 */
@Composable
fun ParkedStreamingDialog(
    playbackAllowed: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onLaunchMediaEngine: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF07111D)) {
            MediaHomeScreen(
                phoneAllowed = playbackAllowed,
                // Legacy API has no measured OBD speed: fail closed for video.
                obdConnected = false,
                freshVehicleSpeedKmh = null,
                modifier = modifier.fillMaxSize()
            )
        }
    }
}
