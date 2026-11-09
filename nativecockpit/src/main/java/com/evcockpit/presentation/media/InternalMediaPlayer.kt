package com.evcockpit.presentation.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * App-owned local-media and direct HTTPS-stream player.
 * Not a Netflix/Disney/Prime DRM bypass and not a Fermata dependency.
 */
@Composable
fun InternalMediaPlayer(
    modifier: Modifier = Modifier,
    allowVideo: Boolean = true
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(context) {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
            )
            setHandleAudioBecomingNoisy(true)
        }
    }
    var title by remember { mutableStateOf("Oynatılacak dosya seçilmedi") }
    var error by remember { mutableStateOf<String?>(null) }
    var streamUrl by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }

    fun start(uri: Uri, name: String) {
        try {
            error = null
            title = name
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.playWhenReady = true
        } catch (failure: Exception) {
            Log.e("EV-Cockpit-Media3", "Could not open media", failure)
            error = "Medya açılamadı: dosya biçimini veya bağlantıyı kontrol edin."
        }
    }

    val chooseFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // URI stays readable for the active picker grant.
            }
            start(uri, "Seçilen yerel medya")
        }
    }

    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
            }
            override fun onPlayerError(failure: PlaybackException) {
                Log.w("EV-Cockpit-Media3", "Playback failed", failure)
                error = "Medya akışı oynatılamadı; dosyayı veya HTTPS adresini kontrol edin."
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("EV Cockpit · Dahili Media3", style = MaterialTheme.typography.titleSmall)
        Text(title, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(
            onClick = {
                chooseFile.launch(
                    if (allowVideo) arrayOf("audio/*", "video/*")
                    else arrayOf("audio/*")
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (allowVideo) "Müzik / video dosyası seç" else "Müzik dosyası seç") }

        // Direct HTTPS audio streams are supported; subscription catalogues are not.
        OutlinedTextField(
            value = streamUrl,
            onValueChange = { streamUrl = it.take(2048) },
            label = { Text("HTTPS yayın adresi (isteğe bağlı)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            onClick = {
                val uri = runCatching { Uri.parse(streamUrl.trim()) }.getOrNull()
                if (uri?.scheme?.lowercase() != "https" ||
                    uri.host.isNullOrBlank()
                ) {
                    error = "Lütfen geçerli bir HTTPS yayın adresi yazın."
                } else {
                    start(uri, "HTTPS medya yayını")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("HTTPS akışını oynat") }
        if (isBuffering) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) }

        // Media3 controls belong to the app, not an optional external package.
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = true
                    this.player = player
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().height(140.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (isPlaying) player.pause() else player.play() }) {
                Text(if (isPlaying) "Duraklat" else "Oynat")
            }
            TextButton(onClick = { player.stop(); title = "Durduruldu" }) {
                Text("Durdur")
            }
        }
        Text("Korunan DRM içerikleri kendi resmî uygulamalarında açılır.",
            style = MaterialTheme.typography.labelSmall)
    }
}
