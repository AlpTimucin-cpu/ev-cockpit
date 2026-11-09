package com.evcockpit.presentation.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.evcockpit.R
import com.evcockpit.presentation.voice.LocalAppAudioDucking

@Composable
fun RadioScreen(model: RadioViewModel = viewModel(), compact: Boolean = false) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val audioDucking = LocalAppAudioDucking.current
    val voiceDucked = if (audioDucking != null)
        audioDucking.ducked.collectAsStateWithLifecycle().value else false
    val player: ExoPlayer? = remember(context) {
        // A media service failure should not prevent the screen from opening.
        runCatching {
            ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
                )
                setHandleAudioBecomingNoisy(true)
            }
        }.getOrNull()
    }
    LaunchedEffect(player, voiceDucked) {
        // Only the application-owned radio is lowered; restore the user level.
        player?.let { activePlayer ->
            runCatching { activePlayer.volume = if (voiceDucked) 0.2f else 1f }
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val filteredStations = remember(state.stations, query) {
        val term = query.trim()
        if (term.isEmpty()) state.stations
        else state.stations.filter {
            it.name.contains(term, ignoreCase = true) ||
                (it.tags?.contains(term, ignoreCase = true) == true)
        }
    }
    var currentName by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var playbackFailed by remember { mutableStateOf(false) }
    DisposableEffect(player, lifecycle) {
        val activePlayer = player
        if (activePlayer == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    buffering = playbackState == Player.STATE_BUFFERING
                }
                override fun onPlayerError(error: PlaybackException) {
                    playbackFailed = true
                    buffering = false
                }
            }
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    runCatching { activePlayer.pause() }
                }
            }
            runCatching { activePlayer.addListener(listener) }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                runCatching { activePlayer.removeListener(listener) }
                runCatching { activePlayer.release() }
            }
        }
    }
    Column(if (compact) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
        if (!compact) Text(stringResource(R.string.radio_description),
            style = MaterialTheme.typography.bodySmall)
        if (player == null) {
            Text(stringResource(R.string.radio_playback_error))
        }
        currentName?.let { name ->
            Text(stringResource(R.string.radio_selected, name))
            if (buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (playbackFailed) Text(stringResource(R.string.radio_playback_error))
            Row {
                TextButton(onClick = {
                    player?.let { activePlayer ->
                        runCatching {
                            if (!playbackFailed && (playing || activePlayer.playWhenReady)) {
                                activePlayer.pause()
                            } else {
                                if (activePlayer.playerError != null) activePlayer.prepare()
                                playbackFailed = false
                                activePlayer.play()
                            }
                        }.onFailure { playbackFailed = true }
                    }
                }, enabled = player != null) {
                    Text(stringResource(if (playing || buffering) R.string.radio_pause else R.string.radio_play))
                }
                TextButton(onClick = {
                    runCatching { player?.stop() }
                    currentName = null
                    playbackFailed = false
                }, enabled = player != null) {
                    Text(stringResource(R.string.radio_stop))
                }
            }
        }
        when {
            state.loading -> CircularProgressIndicator()
            state.failed -> Text(stringResource(R.string.radio_load_error))
            state.stations.isEmpty() -> Text(stringResource(R.string.radio_empty))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(80) },
            label = { Text(stringResource(R.string.radio_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.radio_results, filteredStations.size, state.stations.size),
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = model::refresh, enabled = !state.loading) {
                Text(stringResource(R.string.radio_refresh))
            }
        }
        LazyColumn(if (compact) Modifier.fillMaxWidth().weight(1f)
            else Modifier.fillMaxWidth().height(260.dp)) {
            items(filteredStations, key = { it.id }) { station ->
                Column(Modifier.fillMaxWidth().clickable {
                    player?.let { activePlayer ->
                        runCatching {
                            currentName = station.name
                            playbackFailed = false
                            activePlayer.setMediaItem(MediaItem.fromUri(station.urlResolved))
                            activePlayer.prepare()
                            activePlayer.play()
                        }.onFailure { playbackFailed = true }
                    } ?: run { playbackFailed = true }
                }.padding(vertical = 12.dp, horizontal = 8.dp)) {
                    Text(station.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.radio_votes, station.votes),
                        style = MaterialTheme.typography.bodySmall)
                    if (!station.tags.isNullOrBlank()) Text(station.tags,
                        maxLines = 2, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
        if (!compact) Text(stringResource(R.string.radio_attribution),
            style = MaterialTheme.typography.labelSmall)
    }
}
