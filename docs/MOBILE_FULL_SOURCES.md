# EV Cockpit — tüm ilgili Kotlin, XML ve Gradle kaynakları

Bu paket GitHub'daki `feature/radio-flow-drm-release-polish` dalından otomatik alınmış **eksiksiz dosya içeriklerini** kapsar. Değişmeyen hassas kokpit/harita/TTS kodları da yalnız okuma amaçlı eklenmiştir; yeniden yazılmamıştır.

Telefon tarayıcısında herhangi bir bölüme geçerek tam dosyayı kopyalayabilirsiniz. Kaynakta değişiklik yapmak için ana işlem GitHub PR üzerinden uygulanmıştır.

## 1. `nativecockpit/src/main/java/com/evcockpit/data/radio/RadioBrowserApi.kt`

````kotlin
package com.evcockpit.data.radio

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/** Radio Browser's JSON fields; blank values are rejected in RadioRepository. */
data class RadioStation(
    @SerializedName("stationuuid") val id: String = "",
    val name: String = "",
    @SerializedName("url_resolved") val urlResolved: String = "",
    val favicon: String = "",
    val tags: String = "",
    val votes: Int = 0
)

interface RadioBrowserApi {
    // Radio Browser Turkish stations; ranked in descending community vote order.
    @GET("json/stations/bycountryexact/Turkey")
    suspend fun stations(
        @Query("hidebroken") hideBroken: Boolean = true,
        @Query("order") order: String = "votes",
        @Query("reverse") reverse: Boolean = true,
        @Query("limit") limit: Int = 500
    ): List<RadioStation>
}
````

## 2. `nativecockpit/src/main/java/com/evcockpit/data/radio/RadioRepository.kt`

````kotlin
package com.evcockpit.data.radio

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Singleton
class RadioRepository @Inject constructor(private val api: RadioBrowserApi) {
    /** Cold stream; each refresh fetches current radio listings and wraps failures. */
    fun stations(): Flow<Result<List<RadioStation>>> = flow {
        emit(Result.success(playableStations(api.stations())))
    }.catch { error ->
        if (error is CancellationException) throw error
        emit(Result.failure(error))
    }.flowOn(Dispatchers.IO)

    companion object {
        // AndroidManifest usesCleartextTraffic=false: never silently upgrade an
        // HTTP-only stream to HTTPS or relax app-wide network security.
        internal fun playableStations(stations: List<RadioStation>): List<RadioStation> =
            stations.asSequence()
                .filter {
                    !it.id.isNullOrBlank() && !it.name.isNullOrBlank() &&
                        it.urlResolved?.toHttpUrlOrNull()?.isHttps == true
                }
                .sortedByDescending { it.votes }
                .distinctBy { it.urlResolved }
                .distinctBy { it.id }
                .toList()
    }
}
````

## 3. `nativecockpit/src/main/java/com/evcockpit/presentation/media/RadioViewModel.kt`

````kotlin
package com.evcockpit.presentation.media

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.evcockpit.data.radio.RadioRepository
import com.evcockpit.data.radio.RadioStation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class RadioUiState(
    val stations: List<RadioStation> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false
)

@HiltViewModel
class RadioViewModel @Inject constructor(
    private val repository: RadioRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(RadioUiState())
    val state = mutableState.asStateFlow()
    private var request: Job? = null

    init { refresh() }

    fun refresh() {
        request?.cancel()
        request = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, failed = false)
            try {
                repository.stations().collect { result ->
                    result.fold(
                        onSuccess = { stations ->
                            mutableState.value = RadioUiState(
                                stations = stations, loading = false, failed = false
                            )
                        },
                        onFailure = { error ->
                            if (error is CancellationException) throw error
                            mutableState.value = mutableState.value.copy(
                                loading = false, failed = true
                            )
                        }
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, failed = true)
            }
        }
    }
}
````

## 4. `nativecockpit/src/main/java/com/evcockpit/presentation/media/RadioScreen.kt`

````kotlin
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

@Composable
fun RadioScreen(model: RadioViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            setHandleAudioBecomingNoisy(true)
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.radio_description), style = MaterialTheme.typography.bodySmall)
        currentName?.let { name ->
            Text(stringResource(R.string.radio_selected, name))
            if (buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (playbackFailed) Text(stringResource(R.string.radio_playback_error))
            Row {
                TextButton(onClick = {
                    if (!playbackFailed && (playing || player.playWhenReady)) player.pause()
                    else { if (player.playerError != null) player.prepare(); playbackFailed = false; player.play() }
                }) {
                    Text(stringResource(if (playing || buffering) R.string.radio_pause else R.string.radio_play))
                }
                TextButton(onClick = { player.stop(); currentName = null; playbackFailed = false }) {
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
        LazyColumn(Modifier.fillMaxWidth().height(260.dp)) {
            items(filteredStations, key = { it.id }) { station ->
                Column(Modifier.fillMaxWidth().clickable {
                    currentName = station.name
                    playbackFailed = false
                    player.setMediaItem(MediaItem.fromUri(station.urlResolved))
                    player.prepare()
                    player.play()
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
        Text(stringResource(R.string.radio_attribution), style = MaterialTheme.typography.labelSmall)
    }
}
````

## 5. `nativecockpit/src/main/java/com/evcockpit/presentation/media/ParkedStreamingDialog.kt`

````kotlin
package com.evcockpit.presentation.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.evcockpit.R

/** Phone media browser: embedded YouTube, external licensed DRM apps, and radio. */
@Composable
fun ParkedStreamingDialog(
    playbackAllowed: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onLaunchMediaEngine: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var radioTab by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<MediaSite?>(null) }
    var appError by remember { mutableStateOf<Pair<MediaSite, MediaLaunchState>?>(null) }

    LaunchedEffect(playbackAllowed) {
        if (!playbackAllowed) selected = null
    }

    // YouTube only: fullscreen ChromeClient stays owned by the WebView host.
    val service = selected
    if (service == MediaSite.YOUTUBE && playbackAllowed) {
        Dialog(
            onDismissRequest = { selected = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF0A0F1A)) {
                MediaWebViewScreen(
                    service = service,
                    playbackAllowed = playbackAllowed,
                    onClose = { selected = null }
                )
            }
        }
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = modifier.fillMaxWidth().heightIn(max = 600.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF0A1728),
            contentColor = Color.White,
            shadowElevation = 12.dp
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.native_in_app_media_browser),
                    style = MaterialTheme.typography.titleLarge
                )
                TabRow(selectedTabIndex = if (radioTab) 1 else 0) {
                    Tab(selected = !radioTab, onClick = { radioTab = false },
                        text = { Text(stringResource(R.string.media_video_tab)) })
                    Tab(selected = radioTab, onClick = { radioTab = true },
                        text = { Text(stringResource(R.string.media_radio_tab)) })
                }
                if (radioTab) {
                    RadioScreen()
                } else {
                    Text(
                        if (playbackAllowed)
                            stringResource(R.string.native_in_app_media_on_the_phone)
                        else stringResource(R.string.native_video_is_unavailable_on_this_screen),
                        color = Color(0xFFABC5D5),
                        style = MaterialTheme.typography.bodySmall
                    )
                    onLaunchMediaEngine?.let { launch ->
                        OutlinedButton(
                            onClick = launch,
                            enabled = playbackAllowed,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text(stringResource(R.string.media_engine_open)) }
                    }
                    MediaSite.entries.forEach { site ->
                        Button(
                            enabled = playbackAllowed,
                            onClick = {
                                if (site == MediaSite.YOUTUBE) {
                                    selected = site
                                } else {
                                    when (val result = MediaLauncher.launchOfficialApp(context, site)) {
                                        is MediaLaunchState.Launched -> onDismiss()
                                        else -> appError = site to result
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text(site.title) }
                    }
                    Text(
                        stringResource(R.string.native_sign_in_to_your_account_on_the_site_playback_support_depends_on_the_se),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB7CEDF)
                    )
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.native_close))
                }
            }
        }
    }

    appError?.let { (site, state) ->
        AlertDialog(
            onDismissRequest = { appError = null },
            containerColor = Color(0xEE15263A),
            tonalElevation = 14.dp,
            shape = RoundedCornerShape(24.dp),
            title = { Text(site.title, color = Color.White) },
            text = {
                Text(
                    stringResource(
                        if (state == MediaLaunchState.NotInstalled)
                            R.string.media_external_not_installed
                        else R.string.media_external_launch_blocked
                    ),
                    color = Color(0xFFE0ECF7)
                )
            },
            confirmButton = {
                TextButton(onClick = { appError = null }) {
                    Text(stringResource(R.string.native_close))
                }
            }
        )
    }
}
````

## 6. `nativecockpit/src/main/java/com/evcockpit/presentation/media/MediaLauncher.kt`

````kotlin
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
````

## 7. `nativecockpit/src/main/java/com/evcockpit/presentation/media/MediaWebViewScreen.kt`

````kotlin
package com.evcockpit.presentation.media

import androidx.compose.ui.res.stringResource
import com.evcockpit.R

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface MediaPageUiState {
    data object Empty : MediaPageUiState
    data object Loading : MediaPageUiState
    data object Ready : MediaPageUiState
    data class Error(val message: String) : MediaPageUiState
}

class MediaWebViewViewModel : ViewModel() {
    private val mutableState = MutableStateFlow<MediaPageUiState>(MediaPageUiState.Empty)
    val uiState: StateFlow<MediaPageUiState> = mutableState.asStateFlow()
    fun start() { mutableState.value = MediaPageUiState.Loading }
    fun ready() { if (mutableState.value == MediaPageUiState.Loading) mutableState.value = MediaPageUiState.Ready }
    fun error(message: String) { mutableState.value = MediaPageUiState.Error(message.take(160)) }
    fun clear() { mutableState.value = MediaPageUiState.Empty }
}

private class MediaBrowserHost(context: Context) : FrameLayout(context) {
    var releaseFullscreen: (() -> Unit)? = null
}

private fun WebView.pauseMedia() {
    // No JavaScript interface is exposed to websites. onPause alone does not pause HTML media.
    runCatching {
        evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause();});", null)
        onPause()
    }
    CookieManager.getInstance().flush()
}

/** One owned browser per selected site. The cockpit and existing MediaEngine service are untouched. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MediaWebViewScreen(
    service: MediaSite,
    playbackAllowed: Boolean,
    onClose: () -> Unit,
    model: MediaWebViewViewModel = viewModel(key = "web-" + service.name)
) {
    var fullscreenView by remember(service) { mutableStateOf<View?>(null) }
    var browser by remember(service) { mutableStateOf<WebView?>(null) }
    var exitFullscreen by remember(service) { mutableStateOf<(() -> Unit)?>(null) }
    var generation by remember(service) { mutableIntStateOf(0) }
    var rendererLost by remember(service) { mutableStateOf(false) }
    val allowed by rememberUpdatedState(playbackAllowed)
    val page by model.uiState.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current

    DisposableEffect(browser, owner) {
        val web = browser
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    exitFullscreen?.invoke()
                    web?.pauseMedia()
                }
                Lifecycle.Event.ON_RESUME -> if (allowed && !rendererLost) web?.onResume()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(playbackAllowed) {
        if (!playbackAllowed) { exitFullscreen?.invoke(); browser?.pauseMedia() }
    }
    BackHandler {
        when {
            exitFullscreen != null -> exitFullscreen?.invoke()
            !rendererLost && browser?.canGoBack() == true -> browser?.goBack()
            else -> {
                // Back must release HTML media before closing the Compose host.
                browser?.pauseMedia()
                onClose()
            }
        }
    }

    fullscreenView?.let { customView ->
        FullscreenVideoDialog(
            videoView = customView,
            onDismiss = { exitFullscreen?.invoke() }
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(service.title, color = Color.White)
            TextButton(onClick = {
                exitFullscreen?.invoke()
                browser?.pauseMedia()
                onClose()
            }) { Text(stringResource(R.string.native_close)) }
        }
        if (!playbackAllowed) {
            Text(stringResource(R.string.native_video_is_unavailable_for_this_connection), modifier = Modifier.padding(16.dp))
            return@Column
        }
        when (val state = page) {
            MediaPageUiState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is MediaPageUiState.Error -> Row(Modifier.padding(12.dp)) {
                Text(state.message, color = Color(0xFFFFB17C), modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    exitFullscreen?.invoke()
                    rendererLost = false
                    model.start()
                    generation++
                }) { Text(stringResource(R.string.native_try_again)) }
            }
            else -> Unit
        }
        key(service, generation) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    MediaBrowserHost(context).apply {
                        val host = this
                        val web = WebView(context)
                        with(web.settings) {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            mediaPlaybackRequiresUserGesture = false
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            safeBrowsingEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            userAgentString = MediaSitePolicy.userAgent(service, userAgentString)
                        }
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(web, service != MediaSite.YOUTUBE)
                        }
                        val chrome = object : WebChromeClient() {
                            private var custom: View? = null
                            private var completion: CustomViewCallback? = null
                            override fun onPermissionRequest(request: PermissionRequest) {
                                if (allowed && MediaSitePolicy.grantsProtectedMedia(
                                        service, web.url, request.origin.toString()) &&
                                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) {
                                    request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                                } else request.deny()
                            }
                            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                if (!allowed || custom != null) { callback.onCustomViewHidden(); return }
                                (view.parent as? ViewGroup)?.removeView(view)
                                custom = view
                                completion = callback
                                fullscreenView = view
                                web.visibility = View.GONE
                                exitFullscreen = { onHideCustomView() }
                                Log.d("EYA-MEDIA-UAT", "web_fullscreen_show:" + service.name)
                            }
                            override fun onHideCustomView() {
                                val view = custom ?: return
                                (view.parent as? ViewGroup)?.removeView(view)
                                custom = null
                                val callback = completion
                                completion = null
                                web.visibility = View.VISIBLE
                                if (fullscreenView === view) {
                                    fullscreenView = null
                                    exitFullscreen = null
                                }
                                // A destroyed renderer may invalidate this callback.
                                runCatching { callback?.onCustomViewHidden() }
                                    .onFailure {
                                        Log.w("EYA-MEDIA-UAT", "Fullscreen callback invalidated", it)
                                    }
                                Log.d("EYA-MEDIA-UAT", "web_fullscreen_hide:" + service.name)
                            }
                        }
                        web.webChromeClient = chrome
                        host.releaseFullscreen = { chrome.onHideCustomView() }
                        web.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (!request.isForMainFrame) return false
                                if (allowed && MediaSitePolicy.allows(service, request.url.toString())) return false
                                model.error("This link cannot be opened within the site.")
                                return true
                            }
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                if (!allowed || !MediaSitePolicy.allows(service, url)) {
                                    view.stopLoading()
                                    model.error("Could not validate the page address.")
                                } else model.start()
                            }
                            override fun onPageFinished(view: WebView, url: String?) {
                                if (MediaSitePolicy.allows(service, url)) {
                                    CookieManager.getInstance().flush()
                                    model.ready()
                                }
                            }
                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) model.error("Could not load the page. Check your connection.")
                            }
                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame && response.statusCode >= 400)
                                    model.error("The site returned HTTP ${response.statusCode}.")
                            }
                            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                handler.cancel()
                                model.error("Could not verify the secure connection.")
                            }
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                exitFullscreen?.invoke()
                                rendererLost = true
                                host.removeView(view)
                                view.destroy()
                                browser = null
                                model.error("Web content closed. You can try again.")
                                return true
                            }
                        }
                        host.addView(web, FrameLayout.LayoutParams(-1, -1))
                        browser = web
                        web.loadUrl(service.url)
                    }
                },
                onRelease = { host ->
                    host.releaseFullscreen?.invoke()
                    host.releaseFullscreen = null
                    // Release the view owned by THIS host, never a replacement from a later generation.
                    val web = (0 until host.childCount).map { host.getChildAt(it) }
                        .filterIsInstance<WebView>().firstOrNull()
                    web?.let {
                        it.pauseMedia()
                        it.stopLoading()
                        it.webChromeClient = null
                        it.webViewClient = WebViewClient()
                        host.removeView(it)
                        it.removeAllViews()
                        it.destroy()
                        if (browser === it) browser = null
                    }
                    host.removeAllViews()
                }
            )
        }
    }
}
````

## 8. `nativecockpit/src/main/java/com/evcockpit/presentation/media/FullscreenVideoDialog.kt`

````kotlin
package com.evcockpit.presentation.media

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Own dialog window: the browser toolbar cannot reduce the video bounds. */
@Composable
internal fun FullscreenVideoDialog(videoView: View, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = false
        )
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            val controller = window?.let {
                WindowCompat.getInsetsController(it, it.decorView)
            }
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                FrameLayout(context).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    (videoView.parent as? ViewGroup)?.removeView(videoView)
                    addView(videoView, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                }
            },
            onRelease = { host -> host.removeAllViews() }
        )
    }
}
````

## 9. `nativecockpit/src/main/java/com/evcockpit/presentation/map/MapLibreCockpit.kt`

````kotlin
package com.evcockpit.presentation.map

import androidx.compose.ui.res.stringResource
import com.evcockpit.R

import android.content.ComponentCallbacks2
import android.util.Log
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path
import com.google.gson.JsonObject
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import com.evcockpit.domain.model.GeoCoordinate
import com.evcockpit.domain.model.GpsFix
import com.evcockpit.domain.model.ChargingStation
import com.evcockpit.domain.model.GeoViewport
import com.evcockpit.domain.model.WeatherWaypoint
import com.evcockpit.domain.model.ProfileSpeedSegment
import com.evcockpit.domain.usecase.StationCorridor
import com.evcockpit.domain.model.validRouteGeometry

private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"
private const val FALLBACK_STYLE = "https://tiles.openfreemap.org/styles/fiord"
private const val ROUTE_SRC = "eya-route-source"
private const val ROUTE_LAYER = "eya-route-line"
private const val STATION_SRC = "eya-station-source"
private const val STATION_CORRIDOR_SRC = "eya-route-dc-corridor-source"
private const val STATION_ALL_LAYER = "eya-station-all"
private const val STATION_RING_LAYER = "eya-station-route-outline"
private const val STATION_PLANNED_LAYER = "eya-station-plan-outline"
private const val STATION_DC_LAYER = "eya-station-dc-icons"
private const val STATION_ICON = "eya-station-bolt"
private const val STATION_CLUSTER_LAYER = "eya-station-cluster-circles"
private const val STATION_CLUSTER_COUNT_LAYER = "eya-station-cluster-count"
private const val SPEED_SRC = "eya-osrm-speed-profile"
private const val SPEED_SLOW = "eya-osrm-speed-slow"
private const val SPEED_NORMAL = "eya-osrm-speed-normal"
private const val WEATHER_SRC = "eya-weather-route"
private const val WEATHER_LAYER = "eya-weather-markers"

/** One MapView for this composition; Compose owns transparent cockpit overlays. */
@Composable
fun MapLibreCockpit(
    gpsFix: GpsFix?,
    routePoints: List<GeoCoordinate>,
    stations: List<ChargingStation>,
    plannedStopIds: Set<String> = emptySet(),
    weatherPoints: List<WeatherWaypoint>,
    speedProfile: List<ProfileSpeedSegment> = emptyList(),
    onViewportChanged: (GeoViewport) -> Unit,
    onStationTapped: (ChargingStation) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // Map callback must never capture stale Compose lists/lambdas.
    val stationIndex = remember(stations) { stations.associateBy { it.id } }
    val latestStationIndex by rememberUpdatedState(stationIndex)
    val latestViewport by rememberUpdatedState(onViewportChanged)
    val latestTap by rememberUpdatedState(onStationTapped)
    var mapStatus by remember { mutableStateOf("Loading map…") }
    var mapObject by remember { mutableStateOf<MapLibreMap?>(null) }
    var activeStyle by remember { mutableStateOf<Style?>(null) }
    var fallback by remember { mutableStateOf(false) }
    var centeredOnRealGps by remember { mutableStateOf(false) }
    var fittedRouteKey by remember { mutableStateOf<Int?>(null) }
    // This object follows the lifecycle of the ONE MapView.
    val carAnimator = remember(context) { VehicleMarkerAnimator() }

    // MapLibre SDK needs initialization BEFORE MapView is constructed.
    val view = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).also { it.onCreate(null) }
    }

    DisposableEffect(view, owner) {
        var started = false
        var resumed = false
        fun start() { if (!started) { view.onStart(); started = true } }
        fun resume() { start(); if (!resumed) { view.onResume(); resumed = true } }
        fun pause() {
            carAnimator.clear() // no animation while backgrounded
            if (resumed) { view.onPause(); resumed = false }
        }
        fun stop() { pause(); if (started) { view.onStop(); started = false } }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        else if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(config: Configuration) = Unit
            override fun onLowMemory() { view.onLowMemory() }
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) view.onLowMemory()
            }
        }
        context.registerComponentCallbacks(callbacks)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            context.unregisterComponentCallbacks(callbacks)
            stop()
            carAnimator.release()
            view.onDestroy() // exactly once, on Composition disposal
        }
    }

    DisposableEffect(view) {
        var idleListener: MapLibreMap.OnCameraIdleListener? = null
        var clickListener: MapLibreMap.OnMapClickListener? = null
        var callbackMap: MapLibreMap? = null
        val failed = MapView.OnDidFailLoadingMapListener { message ->
            if (!fallback) {
                fallback = true
                mapStatus = "Retrying the dark map…"
                mapObject?.setStyle(Style.Builder().fromUri(FALLBACK_STYLE)) { style ->
                    activeStyle = style
                    mapStatus = "Map ready · Fiord fallback style"
                }
            } else {
                mapStatus = "Map connection failed · route not drawn"
            }
        }
        view.addOnDidFailLoadingMapListener(failed)
        view.getMapAsync { map ->
            callbackMap = map
            mapObject = map
            map.uiSettings.isAttributionEnabled = true
            map.uiSettings.isLogoEnabled = true
            fun publishViewport() {
                val bounds=map.projection.visibleRegion.latLngBounds
                latestViewport(GeoViewport(bounds.southWest.latitude,bounds.southWest.longitude,
                    bounds.northEast.latitude,bounds.northEast.longitude))
            }
            idleListener = MapLibreMap.OnCameraIdleListener { publishViewport() }
            map.addOnCameraIdleListener(idleListener!!)
            clickListener = MapLibreMap.OnMapClickListener { point ->
                val clusters = map.queryRenderedFeatures(map.projection.toScreenLocation(point),
                    STATION_CLUSTER_LAYER)
                val cluster = clusters.firstOrNull { it.hasProperty("cluster_id") }
                if (cluster != null) {
                    val source = map.style?.getSourceAs<GeoJsonSource>(STATION_SRC)
                    val expansion = try { source?.getClusterExpansionZoom(cluster)?.toDouble() }
                        catch (_: Exception) { null }
                    val center = (cluster.geometry() as? Point)?.let {
                        LatLng(it.latitude(), it.longitude())
                    } ?: point
                    val zoom = (expansion ?: map.cameraPosition.zoom + 2.0)
                        .coerceAtLeast(map.cameraPosition.zoom + 1.0)
                        .coerceAtMost(17.0)
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(center, zoom))
                    return@OnMapClickListener true
                }
                val hits=map.queryRenderedFeatures(map.projection.toScreenLocation(point),
                    STATION_PLANNED_LAYER,STATION_DC_LAYER,STATION_RING_LAYER,STATION_ALL_LAYER)
                val id=hits.firstOrNull()?.let { f ->
                    if(f.hasProperty("stationId")) f.getStringProperty("stationId") else null
                }
                val station=latestStationIndex[id]
                if(station!=null) { latestTap(station); true } else false
            }
            map.addOnMapClickListener(clickListener!!)
            // Türkiye panoraması yalnız kamera başlangıcıdır; GPS değildir.
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(39.0, 35.0)).zoom(5.2).build()
            map.setStyle(Style.Builder().fromUri(DARK_STYLE)) { style ->
                activeStyle = style
                mapStatus = "Map ready · OpenFreeMap"
                publishViewport()
            }
        }
        onDispose {
            callbackMap?.let { m ->
                idleListener?.let { m.removeOnCameraIdleListener(it) }
                clickListener?.let { m.removeOnMapClickListener(it) }
            }
            view.removeOnDidFailLoadingMapListener(failed)
        }
    }

    // Show only actual OSRM geometry, fit once per selected alternative.
    LaunchedEffect(activeStyle, routePoints) {
        activeStyle?.let {
            updateRoute(it, routePoints)
            ensureRouteOverlayZOrder(it)
        }
        if (activeStyle != null && validRouteGeometry(routePoints)) {
            val signature=routePoints.hashCode()
            if(fittedRouteKey!=signature){
                val b=LatLngBounds.Builder()
                routePoints.forEach { b.include(LatLng(it.latitude,it.longitude)) }
                try { mapObject?.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 76)) }
                catch (_:Exception) { /* keep real geometry if fit is unsupported */ }
                fittedRouteKey=signature
            }
        }
        if(routePoints.isEmpty())fittedRouteKey=null
    }
    LaunchedEffect(activeStyle, stations, routePoints, plannedStopIds) {
        val style = activeStyle ?: return@LaunchedEffect
        // Serialize the 13k+ stations off the UI thread; the source clusters them.
        val geo = withContext(Dispatchers.Default) {
            buildStationGeoJson(stations, routePoints, plannedStopIds)
        }
        updateStations(style, geo)
        ensureRouteOverlayZOrder(style)
        Log.d("EYA-MAP-UAT", "stationSource=" + (style.getSourceAs<GeoJsonSource>(STATION_SRC) != null) +
            " dcRingLayer=" + (style.getLayer(STATION_RING_LAYER) != null))
    }
    LaunchedEffect(activeStyle, speedProfile) {
        activeStyle?.let {
            updateSpeedProfile(it, speedProfile)
            ensureRouteOverlayZOrder(it)
        }
    }
    LaunchedEffect(activeStyle, weatherPoints) {
        activeStyle?.let {
            updateWeather(it, weatherPoints)
            ensureRouteOverlayZOrder(it)
        }
    }
    LaunchedEffect(activeStyle, gpsFix, routePoints) {
        activeStyle?.let { carAnimator.update(it, gpsFix, routePoints, System.currentTimeMillis()) }
        if (!centeredOnRealGps && activeStyle != null &&
            gpsFix?.usable(System.currentTimeMillis()) == true) {
            mapObject?.moveCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(gpsFix.coordinate.latitude, gpsFix.coordinate.longitude), 13.5))
            centeredOnRealGps = true // later movement animation is Step 5
        }
    }

    Box(modifier.background(Color(0xFF0A0F1A))) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (!mapStatus.startsWith("Map ready")) {
            Text(mapStatus, modifier = Modifier.align(Alignment.Center)
                .background(Color(0xDF0B2032), RoundedCornerShape(12.dp))
                .padding(14.dp), color = Color.White, fontSize = 12.sp)
        }
        // Explicit provider attribution additionally to MapLibre's native attribution.
        Text(stringResource(R.string.native_openfreemap_openstreetmap),
            color = Color(0xFFD6E2EB), fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                .padding(bottom = 72.dp, end = 16.dp)
                .background(Color(0xB9091B2A), RoundedCornerShape(6.dp))
                .clickable {
                    val uri = android.net.Uri.parse("https://www.openstreetmap.org/copyright")
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                    try { context.startActivity(intent) } catch (_: Exception) { }
                }.padding(horizontal = 7.dp, vertical = 3.dp))
    }
}

/**
 * MapLibre's last-added layer wins. Weather/EPDK arrive on independent Flows,
 * so addLayerAbove only at creation is insufficient after style/source refresh.
 * Reorder *existing app-owned layers* every time any overlay source updates.
 *
 * Bottom -> top: route, OSRM profile speeds, charging markers, weather icons.
 * No tile/provider layers, MapLibre resources or data sources are replaced.
 */
private fun ensureRouteOverlayZOrder(style: Style) {
    if (style.getLayer(ROUTE_LAYER) == null) return

    var below = ROUTE_LAYER
    val overlayOrder = listOf(
        SPEED_NORMAL, SPEED_SLOW,
        STATION_CLUSTER_LAYER, STATION_CLUSTER_COUNT_LAYER,
        STATION_ALL_LAYER, STATION_RING_LAYER, STATION_PLANNED_LAYER,
        STATION_DC_LAYER, WEATHER_LAYER
    )
    // Avoid repeated native remove/re-add cycles when the asynchronous map
    // updates do not actually change the visible layer order.
    val existingOrder = style.layers.map { it.id }
    if (OverlayZOrder.isCorrect(existingOrder, ROUTE_LAYER, overlayOrder)) return
    overlayOrder.forEach { id ->
        val layer = style.getLayer(id) ?: return@forEach
        // The Layer overload transfers native ownership back to this instance.
        // Removing by ID invalidates the instance and cannot be re-added.
        if (!style.removeLayer(layer)) return@forEach
        style.addLayerAbove(layer, below)
        below = id
    }
    Log.d("EYA-MAP-UAT", "overlayZOrder repaired; top=$below aboveRoute=$ROUTE_LAYER")
}

private fun updateRoute(style: Style, points: List<GeoCoordinate>) {
    val valid = validRouteGeometry(points)
    val geo = if (valid) {
        val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })
        FeatureCollection.fromFeature(Feature.fromGeometry(line))
    } else FeatureCollection.fromFeatures(emptyArray<Feature>())
    val source = style.getSourceAs<GeoJsonSource>(ROUTE_SRC)
    if (source != null) source.setGeoJson(geo)
    else {
        style.addSource(GeoJsonSource(ROUTE_SRC, geo))
        style.addLayer(LineLayer(ROUTE_LAYER, ROUTE_SRC).withProperties(
            lineColor(android.graphics.Color.rgb(0, 176, 255)), lineWidth(5f)
        ))
    }
}

/** Feature properties carry only station IDs; details come from verified repository. */
private fun buildStationGeoJson(
    stations: List<ChargingStation>, route: List<GeoCoordinate>,
    plannedStopIds: Set<String>
): FeatureCollection {
    val routeIndex=if(route.size>=2) StationCorridor.index(route) else null
    val features=stations.map { station ->
        val props=JsonObject().apply {
            addProperty("stationId",station.id)
            addProperty("dc",station.hasDc)
            addProperty("corridor",routeIndex?.isDcAlongRoute(station)==true)
            addProperty("proposedStop",station.id in plannedStopIds)
        }
        Feature.fromGeometry(Point.fromLngLat(station.coordinate.longitude,
            station.coordinate.latitude),props)
    }
    val dcAlong = features.count { it.getBooleanProperty("corridor") }
    Log.d("EYA-MAP-UAT", "routeVertices=" + route.size +
        " stations=" + stations.size + " corridorDc5km=" + dcAlong +
        " proposed=" + plannedStopIds.size)
    return FeatureCollection.fromFeatures(features)
}

private fun updateStations(style: Style, geo: FeatureCollection) {
    // Route-DC rings are NOT clustered; they remain visible at overview zoom.
    val corridorFeatures = geo.features().orEmpty().filter { feature ->
        feature.hasProperty("corridor") && feature.getBooleanProperty("corridor")
    }
    val corridorGeo = FeatureCollection.fromFeatures(corridorFeatures)
    style.getSourceAs<GeoJsonSource>(STATION_CORRIDOR_SRC)?.setGeoJson(corridorGeo)
        ?: style.addSource(GeoJsonSource(STATION_CORRIDOR_SRC, corridorGeo))
    Log.d("EYA-MAP-UAT", "unclusteredCorridorDc=" + corridorFeatures.size)
    val existing=style.getSourceAs<GeoJsonSource>(STATION_SRC)
    if(existing!=null){existing.setGeoJson(geo);return}
    // Clustering belongs to the data source: thousands of points do not become
    // thousands of on-screen circles at low zoom levels.
    val options = GeoJsonOptions()
        .withCluster(true)
        .withClusterRadius(50)
        .withClusterMaxZoom(14)
    style.addSource(GeoJsonSource(STATION_SRC, geo, options))
    style.addImage(STATION_ICON, lightningBitmap())
    val isCluster = Expression.has("point_count")
    val isSingle = Expression.not(isCluster)
    style.addLayer(CircleLayer(STATION_CLUSTER_LAYER, STATION_SRC)
        .withFilter(isCluster)
        .withProperties(
            circleColor(android.graphics.Color.rgb(16, 115, 155)),
            circleRadius(21f),
            circleStrokeWidth(2f),
            circleStrokeColor(android.graphics.Color.WHITE)
        ))
    style.addLayer(SymbolLayer(STATION_CLUSTER_COUNT_LAYER, STATION_SRC)
        .withFilter(isCluster)
        .withProperties(
            textField(Expression.get("point_count_abbreviated")),
            textColor(android.graphics.Color.WHITE),
            textSize(13f),
            textAllowOverlap(true)
        ))
    style.addLayer(CircleLayer(STATION_ALL_LAYER, STATION_SRC)
        .withFilter(isSingle)
        .withProperties(
            circleColor(android.graphics.Color.rgb(35, 164, 205)),
            circleRadius(5f),
            circleOpacity(.72f),
            circleStrokeWidth(1f),
            circleStrokeColor(android.graphics.Color.WHITE)
        ))
    style.addLayer(CircleLayer(STATION_RING_LAYER, STATION_CORRIDOR_SRC)
        .withFilter(Expression.eq(Expression.get("corridor"), Expression.literal(true)))
        .withProperties(
            circleRadius(15f),
            circleColor(android.graphics.Color.TRANSPARENT),
            circleStrokeWidth(3f),
            circleStrokeColor(android.graphics.Color.rgb(16, 235, 235))
        ))
    // Do not claim these candidates are verified reachable driving detours.
    style.addLayer(CircleLayer(STATION_PLANNED_LAYER, STATION_SRC)
        .withFilter(Expression.all(isSingle,
            Expression.eq(Expression.get("proposedStop"), Expression.literal(true))))
        .withProperties(
            circleRadius(19f),
            circleColor(android.graphics.Color.TRANSPARENT),
            circleStrokeWidth(4f),
            circleStrokeColor(android.graphics.Color.rgb(255, 194, 67))
        ))
    val chargingLayer = SymbolLayer(STATION_DC_LAYER, STATION_SRC)
        .withFilter(Expression.all(isSingle,
            Expression.eq(Expression.get("dc"), Expression.literal(true))))
        .withProperties(
            iconImage(STATION_ICON), iconSize(.50f), iconAllowOverlap(false)
        )
    // The route ID in this project is "eya-route-line", not "route-layer".
    // On first insertion put DC symbols above the route immediately.
    // ensureRouteOverlayZOrder also repositions them after async source updates.
    if (style.getLayer(ROUTE_LAYER) != null) style.addLayerAbove(chargingLayer, ROUTE_LAYER)
    else style.addLayer(chargingLayer)
}

/** Generate a vector-like bolt locally; no external icon/font/license dependency. */
private fun lightningBitmap(): Bitmap {
    val bmp=Bitmap.createBitmap(72,82,Bitmap.Config.ARGB_8888)
    val canvas=AndroidCanvas(bmp)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.FILL;color=android.graphics.Color.rgb(0,230,118)
    }
    val path=Path().apply {
        moveTo(39f,3f);lineTo(14f,43f);lineTo(34f,43f)
        lineTo(25f,79f);lineTo(61f,30f);lineTo(42f,30f);close()
    }
    canvas.drawPath(path,paint)
    return bmp
}

private fun updateWeather(style: Style, samples: List<WeatherWaypoint>) {
    val features = samples.filter { it.coordinate.valid() }.map { item ->
        val props = JsonObject().apply {
            addProperty("weatherIcon", "eya-weather-" + item.iconKey)
            addProperty("tempText", item.temperatureC.toInt().toString() + "°C")
        }
        Feature.fromGeometry(
            Point.fromLngLat(item.coordinate.longitude, item.coordinate.latitude), props
        )
    }
    val data = FeatureCollection.fromFeatures(features)
    val source = style.getSourceAs<GeoJsonSource>(WEATHER_SRC)
    Log.d("EYA-MAP-UAT", "weatherPoints=" + features.size +
        " routeLayer=" + (style.getLayer(ROUTE_LAYER) != null))
    if (source != null) {
        source.setGeoJson(data)
    } else {
        style.addSource(GeoJsonSource(WEATHER_SRC, data))
        listOf("sun", "cloud", "rain").forEach { kind ->
            style.addImage("eya-weather-" + kind, weatherBitmap(kind))
        }
        val weatherLayer = SymbolLayer(WEATHER_LAYER, WEATHER_SRC).withProperties(
            iconImage(Expression.get("weatherIcon")),
            iconSize(0.8f),
            iconAllowOverlap(true),
            textField(Expression.get("tempText")),
            textSize(12f),
            textColor(android.graphics.Color.WHITE),
            textHaloColor(android.graphics.Color.rgb(4, 28, 45)),
            textHaloWidth(2f),
            textAllowOverlap(true)
        )
        if (style.getLayer(ROUTE_LAYER) != null) style.addLayerAbove(weatherLayer, ROUTE_LAYER)
        else style.addLayer(weatherLayer)
        Log.d("EYA-MAP-UAT", "weatherLayer=" + (style.getLayer(WEATHER_LAYER) != null))
    }
}

/** Draw weather pictograms locally; no extra tile, image or font requests. */
private fun weatherBitmap(type: String): Bitmap {
    val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.rgb(4, 28, 45)
    canvas.drawCircle(24f, 24f, 22f, paint)
    paint.color = if (type == "sun") android.graphics.Color.rgb(255, 194, 67)
        else android.graphics.Color.rgb(200, 227, 245)
    if (type == "sun") {
        canvas.drawCircle(24f, 24f, 11f, paint)
    } else {
        canvas.drawOval(12f, 19f, 36f, 32f, paint)
        canvas.drawCircle(20f, 19f, 8f, paint)
        canvas.drawCircle(28f, 19f, 7f, paint)
        if (type == "rain") {
            paint.color = android.graphics.Color.rgb(42, 182, 238)
            paint.strokeWidth = 3f
            canvas.drawLine(17f, 34f, 13f, 41f, paint)
            canvas.drawLine(30f, 34f, 26f, 41f, paint)
        }
    }
    return bitmap
}


/** OSRM PROFILE speeds, never real-time traffic or measured congestion. */
private fun updateSpeedProfile(style: Style, segments: List<ProfileSpeedSegment>) {
    val features = segments.asSequence()
        .filter { it.from.valid() && it.to.valid() &&
            it.speedKmh.isFinite() && it.speedKmh in 0.0..300.0 }
        .take(19_999)
        .map { segment ->
            val props = JsonObject().apply {
                addProperty("slow", segment.speedKmh < 30.0)
            }
            Feature.fromGeometry(
                LineString.fromLngLats(listOf(
                    Point.fromLngLat(segment.from.longitude, segment.from.latitude),
                    Point.fromLngLat(segment.to.longitude, segment.to.latitude)
                )), props
            )
        }.toList()
    val geo = FeatureCollection.fromFeatures(features)
    val src = style.getSourceAs<GeoJsonSource>(SPEED_SRC)
    Log.d("EYA-MAP-UAT", "OSRM profileSpeedSegments=" + features.size + " LIVE_TRAFFIC=false")
    if (src != null) { src.setGeoJson(geo); return }
    style.addSource(GeoJsonSource(SPEED_SRC, geo))
    val normal = LineLayer(SPEED_NORMAL, SPEED_SRC).withFilter(
        Expression.eq(Expression.get("slow"), Expression.literal(false))
    ).withProperties(
        lineColor(android.graphics.Color.rgb(69, 201, 130)),
        lineWidth(3f)
    )
    if (style.getLayer(ROUTE_LAYER) != null) style.addLayerAbove(normal, ROUTE_LAYER)
    else style.addLayer(normal)
    val slow = LineLayer(SPEED_SLOW, SPEED_SRC).withFilter(
        Expression.eq(Expression.get("slow"), Expression.literal(true))
    ).withProperties(
        lineColor(android.graphics.Color.rgb(236, 89, 87)),
        lineWidth(3f)
    )
    style.addLayerAbove(slow, SPEED_NORMAL)
    Log.d("EYA-MAP-UAT", "speedLayers normal=" + (style.getLayer(SPEED_NORMAL) != null) +
        " slow=" + (style.getLayer(SPEED_SLOW) != null))
}
````

## 10. `nativecockpit/src/main/java/com/evcockpit/presentation/navigation/DebugRouteSimulation.kt`

````kotlin
package com.evcockpit.presentation.navigation

import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import com.evcockpit.domain.model.*

/**
 * DEBUG ONLY. Follows existing OSRM geometry; never injects Android mock GPS,
 * mutates LocationRepository or writes real OBD values.
 */
internal data class SimulationFrame(
    val routeId: String?,
    val fix: GpsFix?,
    val telemetry: VehicleTelemetry
)

internal object DebugRouteSimulation {
    private const val SPEED_M_S = 12.5 // 45 km/h, test fixture
    private const val BATTERY_KWH = 60.0 // Test fixture, NOT an EV Cockpit specification
    private const val WH_PER_KM = 180.0 // Test fixture, NOT measured consumption
    private const val EARTH_RADIUS_M = 6_371_000.0

    private data class Geometry(val path: List<GeoCoordinate>, val cumulative: DoubleArray) {
        val length: Double get() = cumulative.last()
    }

    /**
     * Every emission has a fresh timestamp and moves along the ORIGINAL route.
     * Route changes are handled by collectLatest in NavigationViewModel.
     * tickMillis is configurable only for fast, deterministic test collection.
     */
    fun frames(route: RouteSnapshot?, tickMillis: Long = 1_000L): Flow<SimulationFrame> = flow {
        require(tickMillis > 0L)
        val geometry = geometry(route)
        val start = if (geometry != null && route != null) startMeters(route, geometry) else 0.0
        var tick = 0L
        while (true) {
            val along = if (geometry != null) {
                (start + tick.toDouble() * SPEED_M_S).coerceAtMost(geometry.length)
            } else 0.0
            emit(makeFrame(geometry, route?.routeId, along, System.currentTimeMillis()))
            if (geometry != null && along >= geometry.length) tick = 0L
            else tick++
            delay(tickMillis)
        }
    }.flowOn(Dispatchers.Default)

    /** Deterministic injection point for JVM UAT: no Android mock provider. */
    fun frameAt(route: RouteSnapshot?, metersAlong: Double, nowMs: Long): SimulationFrame =
        makeFrame(geometry(route), route?.routeId, metersAlong, nowMs)

    private fun geometry(route: RouteSnapshot?): Geometry? {
        val actual = route ?: return null
        if (actual.routeId.isNullOrBlank() ||
            !validRouteGeometry(actual.routePoints)) return null
        val path = actual.routePoints
        val cumulative = DoubleArray(path.size)
        for (i in 1..path.lastIndex) {
            cumulative[i] = cumulative[i - 1] + haversine(path[i - 1], path[i])
        }
        return cumulative.lastOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { Geometry(path, cumulative) }
    }

    /** Choose a real upcoming OSRM turn with room for both 200 m and 50 m cues. */
    private fun startMeters(route: RouteSnapshot, geometry: Geometry): Double {
        val projected = route.maneuvers.asSequence()
            .filter { it.location.valid() && it.instructionTr.isNotBlank() }
            .map { nearestDistance(geometry, it.location) }
            .filter { it.isFinite() && it in 0.0..geometry.length }
            .toList().sorted()
        val target = projected.firstOrNull { it >= 230.0 }
            ?: projected.firstOrNull() ?: 0.0
        return (target - 250.0).coerceAtLeast(0.0)
    }

    private fun makeFrame(
        geometry: Geometry?, routeId: String?, metersAlong: Double, nowMs: Long
    ): SimulationFrame {
        val safeMeters = metersAlong.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val along = geometry?.let { safeMeters.coerceAtMost(it.length) } ?: 0.0
        val point = geometry?.let { interpolated(it, along) }
        val soc = (80.0 - (along / 1_000.0 * WH_PER_KM / 1_000.0) /
            BATTERY_KWH * 100.0).coerceIn(0.0, 100.0)
        fun timed(value: Double) = TimedValue(value, nowMs)
        return SimulationFrame(
            routeId = routeId?.takeIf { geometry != null },
            fix = point?.let {
                GpsFix(
                    coordinate = it,
                    accuracyMeters = 4f,
                    recordedAtMillis = nowMs,
                    speedMetersPerSecond = SPEED_M_S.toFloat()
                )
            },
            telemetry = VehicleTelemetry(
                socPercent = timed(soc),
                speedKmh = timed(SPEED_M_S * 3.6),
                batteryTemperatureC = timed(22.0),
                powerKw = timed(8.1)
            )
        )
    }

    private fun interpolated(geometry: Geometry, meters: Double): GeoCoordinate {
        val path = geometry.path
        val d = geometry.cumulative
        val index = (0 until path.lastIndex).firstOrNull { d[it + 1] >= meters }
            ?: return path.last()
        val segment = d[index + 1] - d[index]
        val fraction = if (segment > 0.0)
            ((meters - d[index]) / segment).coerceIn(0.0, 1.0) else 0.0
        return GeoCoordinate(
            path[index].latitude + (path[index + 1].latitude - path[index].latitude) * fraction,
            path[index].longitude + (path[index + 1].longitude - path[index].longitude) * fraction
        )
    }

    private fun nearestDistance(geometry: Geometry, point: GeoCoordinate): Double {
        val path = geometry.path
        val d = geometry.cumulative
        var bestOffset = Double.POSITIVE_INFINITY
        var along = 0.0
        val cosLat = cos(Math.toRadians(point.latitude))
        for (i in 0 until path.lastIndex) {
            val a = path[i]; val b = path[i + 1]
            val ax = (a.longitude - point.longitude) * 111_320.0 * cosLat
            val ay = (a.latitude - point.latitude) * 111_195.0
            val dx = (b.longitude - a.longitude) * 111_320.0 * cosLat
            val dy = (b.latitude - a.latitude) * 111_195.0
            val len2 = dx * dx + dy * dy
            if (len2 <= .01) continue
            val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val offset = hypot(ax + t * dx, ay + t * dy)
            if (offset < bestOffset) {
                bestOffset = offset
                along = d[i] + t * (d[i + 1] - d[i])
            }
        }
        return if (bestOffset.isFinite()) along else 0.0
    }

    private fun haversine(a: GeoCoordinate, b: GeoCoordinate): Double {
        val p = Math.toRadians(a.latitude)
        val q = Math.toRadians(b.latitude)
        val dp = q - p
        val dl = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dp / 2).pow(2) + cos(p) * cos(q) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
````

## 11. `nativecockpit/src/main/java/com/evcockpit/presentation/navigation/NavigationViewModel.kt`

````kotlin
package com.evcockpit.presentation.navigation

import android.content.Context
import android.util.Log
import com.evcockpit.BuildConfig
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.evcockpit.car.CarNavigationOwnership
import com.evcockpit.data.location.FusedCurrentLocationProvider
import com.evcockpit.domain.model.*
import com.evcockpit.domain.repository.LocationRepository
import com.evcockpit.domain.repository.RouteRepository
import com.evcockpit.domain.repository.VehicleRepository
import com.evcockpit.domain.usecase.NavigationSpeechBridge
import com.evcockpit.domain.usecase.RouteProgressTracker
import com.evcockpit.domain.usecase.EvBaselineEnergyEstimator
import com.evcockpit.domain.usecase.BaselineEnergyInputs
import com.evcockpit.domain.usecase.BaselineEnergyResult
import com.evcockpit.domain.usecase.DepartureSoc
import com.evcockpit.domain.usecase.ConsumptionBasis
import com.evcockpit.presentation.cockpit.CockpitUiState
import com.evcockpit.presentation.route.RoutePlannerUiState
import com.evcockpit.presentation.voice.NativeVoiceGuide
import com.evcockpit.presentation.voice.VoicePreference

data class VoiceUiState(
    val enabled: Boolean = true,
    val volume: Float = 0.8f,
    val status: NativeVoiceGuide.Status = NativeVoiceGuide.Status.LOADING,
    val error: String? = null
)

sealed interface RouteLoadStatus {
    data object Idle : RouteLoadStatus
    data object Empty : RouteLoadStatus
    data object Loading : RouteLoadStatus
    data object GpsLost : RouteLoadStatus
    data class Success(val routeId: String) : RouteLoadStatus
    data class Error(val message: String) : RouteLoadStatus
}

data class NavigationUiState(
    val cockpit: CockpitUiState = CockpitUiState(),
    val planner: RoutePlannerUiState = RoutePlannerUiState(),
    val voice: VoiceUiState = VoiceUiState(),
    val routeState: NavigationRouteState = NavigationRouteState(),
    val loadStatus: RouteLoadStatus = RouteLoadStatus.Empty,
    val simulationEnabled: Boolean = false
)

/** Single source of truth for the route editor AND the main navigation panel. */
data class NavigationRouteState(
    val startLocation: GeoCoordinate? = null,
    val startLabel: String? = null,
    val destination: RoutePlace? = null,
    val routeResult: RouteSnapshot? = null,
    val calculating: Boolean = false,
    val error: String? = null
)

/** Single Activity ViewModel; one shared RouteRepository feeds planner, cockpit and car. */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val routes: RouteRepository,
    private val locationRepository: LocationRepository,
    private val currentLocation: FusedCurrentLocationProvider,
    private val vehicleRepository: VehicleRepository
) : ViewModel() {
    private val prefs = VoicePreference(context)
    // Read only explicitly provisioned, independently verified calibration inputs.
    // No hard-coded battery capacity, consumption or assumed SoC.
    private val modelPrefs = context.applicationContext.getSharedPreferences(
        "eya_energy_calibration", Context.MODE_PRIVATE
    )
    private val guide = NativeVoiceGuide(context)
    private val speechBridge = NavigationSpeechBridge()
    private val progress = RouteProgressTracker()
    private val _foreground = MutableStateFlow(false)
    // Debug builds start in TEST mode; release builds can never enable it.
    private val _simulationEnabled = MutableStateFlow(BuildConfig.DEBUG)
    val isSimulationMode: StateFlow<Boolean> = _simulationEnabled.asStateFlow()
    private val _simulationFrame = MutableStateFlow<SimulationFrame?>(null)
    private val simulationTag = "EYA-SIM-UAT"
    private var debugDemoRouteAttempted = false
    private var debugDemoRouteJob: Job? = null
    private var debugDemoRouteActive = false
    private var gpsWaitSinceMs = System.currentTimeMillis()

    // Single fail-closed selector for simulation vs measured GPS/OBD.
    // Route id and frame belong to the same combine emission: a stale frame
    // cannot drive the new route's map progress or TTS announcements.
    private val sources = combine(
        vehicleRepository.telemetry, locationRepository.gpsFix,
        _simulationEnabled, _simulationFrame, routes.currentRoute
    ) { telemetry, gps, enabled, frame, route ->
        NavigationSourceSelector.select(
            liveTelemetry = telemetry,
            liveGps = gps,
            simulationEnabled = enabled,
            debugBuild = BuildConfig.DEBUG,
            frame = frame,
            currentRouteId = route?.routeId
        )
    }
    // Exposed effective location, sourced from the same flow as cockpit and TTS.
    // Do not inject synthetic locations into FusedLocationProviderClient.
    val gpsLocation: StateFlow<GpsFix?> = sources.map { it.gps }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _gpsError = MutableStateFlow<String?>(null)
    val gpsError: StateFlow<String?> = _gpsError.asStateFlow()
    private val _planner = MutableStateFlow(RoutePlannerUiState())
    private val _voice = MutableStateFlow(
        VoiceUiState(prefs.enabled, prefs.volume, guide.status)
    )
    private var originJob: Job? = null
    private var destinationJob: Job? = null
    private var gpsJob: Job? = null
    private var planningJob: Job? = null

    /** Tick only expires previously measured values, never invents a new GPS fix. */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000L)
        }
    }
    private val cockpit = combine(
        sources, routes.currentRoute, ticker
    ) { source, route, now ->
        val usableGps = source.gps?.takeIf { it.usable(now) }
        val lastGpsAt = source.gps?.recordedAtMillis
            ?.takeIf { it <= now && it >= gpsWaitSinceMs } ?: gpsWaitSinceMs
        val telemetry = source.telemetry
        val energy = estimateEnergy(route, telemetry, now, source.simulated)
        val baseRoute = progress.enrich(route, usableGps, now)
        val visualRoute = if (energy is BaselineEnergyResult.Estimate) {
            baseRoute?.copy(estimatedArrivalSocPercent = energy.arrivalSocPercent)
        } else baseRoute?.copy(estimatedArrivalSocPercent = null)
        CockpitUiState(
            socPercent = telemetry.socPercent.current(now),
            speedKmh = telemetry.speedKmh.current(now),
            voltageV = telemetry.voltageV.current(now),
            batteryTemperatureC = telemetry.batteryTemperatureC.current(now),
            powerKw = telemetry.powerKw.current(now),
            chargingNeededEstimate = (energy as? BaselineEnergyResult.Estimate)?.chargingStopNeeded,
            chargingEnergyKwh = (energy as? BaselineEnergyResult.Estimate)?.requiredChargingEnergyKwh,
            energyEstimateStatus = (energy as? BaselineEnergyResult.Unavailable)?.reason,
            socSourceLabel = when {
                source.simulated -> "SIMULATION · TEST DATA"
                energy is BaselineEnergyResult.Estimate && energy.usedLiveObd ->
                    "LIVE OBD starting SoC · MODEL/ESTIMATE"
                energy is BaselineEnergyResult.Estimate &&
                    !modelPrefs.contains("explicitAssumedSocPct") ->
                    "Default starting SoC 80% · MODEL/ESTIMATE"
                energy is BaselineEnergyResult.Estimate ->
                    "Selected starting SoC · MODEL/ESTIMATE"
                else -> null
            },
            simulationEnabled = source.simulated,
            gpsWarning = if (!source.simulated && _foreground.value &&
                now - lastGpsAt > 10_000L && usableGps == null
            ) "Waiting for GPS signal..." else null,
            route = visualRoute,
            gpsFix = usableGps
        )
    }
    /**
     * Fail-closed bridge to the tested energy estimator.
     * Calibration keys must be set from a separately validated configuration.
     * Unverified SoC/usable capacity never become made-up vehicle measurements.
     */
    private fun estimateEnergy(
        route: RouteSnapshot?,
        telemetry: VehicleTelemetry,
        now: Long,
        simulated: Boolean
    ): BaselineEnergyResult? {
        if (route?.routeId == null) return null
        // Damaged/old preference types must not crash the navigation UI.
        fun readFloat(key: String): Double? = runCatching {
            if (modelPrefs.contains(key)) modelPrefs.getFloat(key, Float.NaN)
                .toDouble().takeIf { it.isFinite() } else null
        }.getOrNull()
        // These deterministic values are only TEST FIXTURES in DEBUG simulation.
        val capacity = if (simulated) 60.0 else readFloat("usableBatteryKwh") ?: Double.NaN
        val consumption = if (simulated) 180.0 else readFloat("referenceWhPerKm") ?: Double.NaN
        val reserve = if (simulated) 15.0 else readFloat("arrivalReservePct") ?: Double.NaN
        if (!capacity.isFinite() || capacity <= 0.0 ||
            !consumption.isFinite() || consumption <= 0.0 ||
            !reserve.isFinite() || reserve !in 0.0..100.0
        ) return BaselineEnergyResult.Unavailable("No calibrated capacity/consumption/reserve data")

        val fresh = telemetry.socPercent.current(now)
        val timed = telemetry.socPercent.measuredAtMillis
        val soc = when {
            simulated -> DepartureSoc.ExplicitAssumption(fresh ?: 80.0)
            fresh != null && timed != null -> DepartureSoc.FreshObd(fresh, timed)
            else -> DepartureSoc.ExplicitAssumption(
                readFloat("explicitAssumedSocPct") ?: 80.0
            ) // Explicit, visibly labelled 80% default, never a live measurement.
        }

        return EvBaselineEnergyEstimator.estimate(
            BaselineEnergyInputs(
                route = route, departureSoc = soc,
                usableBatteryKwh = capacity,
                consumptionWhPerKm = consumption,
                consumptionBasis = ConsumptionBasis.REFERENCE_MODEL,
                requiredArrivalSocPercent = reserve,
                nowMs = now,
                speedKmh = telemetry.speedKmh.current(now)
            )
        )
    }

    private val plannerState = combine(
        _planner, routes.options, routes.selectedIndex
    ) { state, options, index ->
        state.copy(options = options, selectedIndex = index)
    }
    // One projection: MapLibre, route dialog and progress panel observe the same
    // emission. No second independently collected routeState StateFlow.
    val uiState: StateFlow<NavigationUiState> = combine(
        cockpit, plannerState, _voice
    ) { cockpit, planner, voice ->
        val route = cockpit.route
        val routeState = NavigationRouteState(
            startLocation = planner.startLocation,
            startLabel = planner.originText.takeIf {
                planner.startLocation != null && it.isNotBlank()
            },
            destination = planner.destinationSelected,
            routeResult = route,
            calculating = planner.planning,
            error = planner.error
        )
        val phase = when {
            planner.planning -> RouteLoadStatus.Loading
            planner.error != null -> RouteLoadStatus.Error(planner.error)
            route?.routeId != null && cockpit.gpsWarning != null ->
                RouteLoadStatus.GpsLost
            route?.routeId != null -> RouteLoadStatus.Success(route.routeId)
            else -> RouteLoadStatus.Empty
        }
        NavigationUiState(cockpit, planner, voice, routeState, phase, cockpit.simulationEnabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), NavigationUiState())

    init {
        viewModelScope.launch {
            // An explicitly labelled DEBUG journey; OSRM supplies real road geometry.
            // Only one attempt per activation. A manually selected route wins.
            combine(_simulationEnabled, _foreground) { enabled, foreground ->
                BuildConfig.DEBUG && enabled && foreground
            }.collect { active ->
                if (!active) {
                    debugDemoRouteJob?.cancel()
                    debugDemoRouteJob = null
                    return@collect
                }
                if (debugDemoRouteAttempted ||
                    routes.currentRoute.value != null ||
                    _planner.value.originText.isNotBlank() ||
                    _planner.value.destinationText.isNotBlank()) return@collect

                debugDemoRouteAttempted = true
                debugDemoRouteJob = viewModelScope.launch {
                    Log.i(simulationTag, "DEBUG demo: requesting OSRM route; not a real GPS fix")
                    try {
                        val start = GeoCoordinate(37.9663, 34.6824) // Niğde, TEST only
                        val destination = RoutePlace(
                            "TEST ROUTE · Kayseri",
                            GeoCoordinate(38.7205, 35.4826),
                            source = "DEBUG UAT"
                        )
                        when (val result = routes.calculateResult(start, destination).getOrThrow()) {
                            is RouteCalculationResult.Success -> {
                                debugDemoRouteActive = true
                                _planner.update {
                                    it.copy(
                                        originText = "TEST · Niğde",
                                        originSelected = RoutePlace(
                                            "TEST · Niğde", start, source = "DEBUG UAT"
                                        ),
                                        destinationText = destination.label,
                                        destinationSelected = destination,
                                        error = null
                                    )
                                }
                                Log.i(simulationTag, "DEBUG demo: OSRM route loaded; awaiting frames")
                            }
                            is RouteCalculationResult.Failure -> {
                                Log.w(simulationTag, "DEBUG demo route unavailable: " + result.message)
                                _planner.update { it.copy(
                                    error = "Could not load the TEST route: " + result.message.take(90)
                                ) }
                            }
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        Log.w(simulationTag, "DEBUG OSRM demo failed: " + error.javaClass.simpleName)
                        _planner.update { it.copy(error =
                            "Could not load the TEST route: check the internet/OSRM connection") }
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(_simulationEnabled, _foreground, routes.currentRoute) { enabled, front, route ->
                Triple(enabled && BuildConfig.DEBUG, front, route)
            }.collectLatest { (enabled, front, route) ->
                _simulationFrame.value = null
                if (BuildConfig.DEBUG) {
                    Log.i(simulationTag, "simulated=$enabled foreground=$front " +
                        "routeReady=${route?.routeId != null} maneuvers=${route?.maneuvers?.size ?: 0}")
                }
                if (enabled && front) {
                    // The SAME frame powers MapLibre/progress and the TTS route policy.
                    // Never write simulated coordinates into the real GPS repository.
                    DebugRouteSimulation.frames(route).collect { frame ->
                        // Atomic Frame: GPS and fixture battery data from ONE route.
                        _simulationFrame.value = frame
                        if (BuildConfig.DEBUG) {
                            Log.d(simulationTag, "simFrame route=${frame.routeId ?: "none"} " +
                                "hasGps=${frame.fix != null} " +
                                "gpsFresh=${frame.fix?.usable(System.currentTimeMillis()) == true}")
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            snapshotFlow { guide.status to guide.lastError }.collect { (status, error) ->
                _voice.update { it.copy(status = status, error = error) }
            }
        }
        // TTS only with verified route + fresh effective GPS + foreground Activity.
        // Android Auto has its own owner; never speak twice simultaneously.
        viewModelScope.launch {
            var activeSpeechRouteId: String? = null
            combine(
                routes.currentRoute,
                sources,
                _foreground,
                CarNavigationOwnership.navigating,
                _voice
            ) { route, source, foreground, carNavigating, voice ->
                SpeechInputs(route, source.gps, foreground && !carNavigating, voice,
                    source.simulated)
            }.collect { current ->
                if (activeSpeechRouteId != current.route?.routeId) {
                    guide.stop()
                    speechBridge.reset()
                    activeSpeechRouteId = current.route?.routeId
                }
                if (!current.allowed || !current.voice.enabled ||
                    current.voice.status != NativeVoiceGuide.Status.READY &&
                        current.voice.status != NativeVoiceGuide.Status.AUDIO_FOCUS_DENIED) {
                    guide.stop()
                } else {
                    val now = System.currentTimeMillis()
                    val evaluation = speechBridge.evaluate(current.route, current.fix, now)
                    val route = evaluation.route
                    if (BuildConfig.DEBUG && current.simulated) {
                        Log.d(simulationTag, "speechInput: route=${route?.routeId != null} " +
                            "fixFresh=${current.fix?.usable(now) == true} " +
                            "turn=${route?.nextManeuver?.id ?: "none"} " +
                            "aheadMeters=${route?.nextTurnMeters?.toInt() ?: -1} " +
                            "voiceStatus=${current.voice.status}")
                    }
                    evaluation.cue?.let { cue ->
                        if (BuildConfig.DEBUG && current.simulated)
                            Log.i(simulationTag, "TTS cue requested for " +
                                "${route?.nextManeuver?.id ?: "unknown"} " +
                                "ahead=${route?.nextTurnMeters?.toInt() ?: -1}m")
                        val queued = guide.speak(
                            cue.message,
                            current.voice.volume,
                            onUtteranceStarted = {
                                // A successful enqueue is NOT proof of audible speech.
                                viewModelScope.launch {
                                    // TTS calls arrive on Binder threads; policy is main-thread owned.
                                    speechBridge.confirmStarted(cue.token, System.currentTimeMillis())
                                    if (BuildConfig.DEBUG && current.simulated)
                                        Log.i(simulationTag, "TTS onStart: stage acknowledged")
                                }
                            },
                            onUtteranceFailed = {
                                viewModelScope.launch {
                                    speechBridge.cancel(cue.token)
                                    if (BuildConfig.DEBUG && current.simulated)
                                        Log.w(simulationTag, "TTS synthesis failed; cue retryable")
                                }
                            }
                        )
                        if (!queued) {
                            speechBridge.cancel(cue.token)
                            if (BuildConfig.DEBUG && current.simulated)
                                Log.w(simulationTag, "TTS not queued; " +
                                    "status=${current.voice.status} error=${guide.lastError}")
                        }
                    }
                }
            }
        }
    }

    private data class SpeechInputs(
        val route: RouteSnapshot?, val fix: GpsFix?,
        val allowed: Boolean, val voice: VoiceUiState,
        val simulated: Boolean
    )

    fun setForeground(isForeground: Boolean) {
        _foreground.value = isForeground
        if (isForeground) gpsWaitSinceMs = System.currentTimeMillis()
        if (!isForeground) {
            guide.stop()
            speechBridge.cancelPending()
            locationRepository.stop()
        }
    }
    fun startGps() {
        _gpsError.value = null
        if (!_simulationEnabled.value) locationRepository.start()
    }

    fun setSimulationEnabled(enabled: Boolean) {
        val next = BuildConfig.DEBUG && enabled
        if (_simulationEnabled.value == next) return
        guide.stop()
        speechBridge.reset()
        _simulationFrame.value = null
        _simulationEnabled.value = next
        // A DEBUG demo route must not remain as a live-navigation route.
        if (!next && debugDemoRouteActive) {
            routes.clear()
            _planner.value = RoutePlannerUiState()
            debugDemoRouteActive = false
        }
        debugDemoRouteJob?.cancel()
        debugDemoRouteJob = null
        debugDemoRouteAttempted = false // Allow one new demo attempt when re-enabled.
        gpsWaitSinceMs = System.currentTimeMillis()
        if (BuildConfig.DEBUG)
            Log.i(simulationTag, "Simulation toggled: $next; guidance state reset")
        _gpsError.value = null
        if (next) locationRepository.stop()
        else if (_foreground.value) locationRepository.start()
    }
    fun stopGps() = locationRepository.stop()

    /** Last known Fused fix, delivered only after the user grants runtime permission. */
    @Suppress("DEPRECATION")
    fun acceptLastLocation(location: android.location.Location?) {
        if (_simulationEnabled.value) return
        if (location == null || location.isFromMockProvider || !location.hasAccuracy()) {
            reportGpsError("Could not read location. Check GPS and precise location settings.")
            return
        }
        val fix = GpsFix(
            coordinate = GeoCoordinate(location.latitude, location.longitude),
            accuracyMeters = location.accuracy,
            recordedAtMillis = location.time,
            bearingDegrees = location.bearing.takeIf { location.hasBearing() && it.isFinite() },
            speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() && it.isFinite() }
        )
        if (fix.usable(System.currentTimeMillis())) {
            locationRepository.acceptFix(fix)
            _gpsError.value = null
        } else _gpsError.value = "Location is not sufficiently recent or accurate."

    }
    fun reportGpsError(message: String) {
        _gpsError.value = message
        _planner.update { it.copy(locatingGps = false, error = message) }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        prefs.enabled = enabled
        _voice.update { it.copy(enabled = enabled) }
        if (!enabled) guide.stop()
    }
    fun setVoiceVolume(volume: Float) {
        val safe = volume.coerceIn(0.15f, 1f)
        prefs.volume = safe
        _voice.update { it.copy(volume = safe) }
    }
    fun testVoice() {
        val voice = _voice.value
        if (_foreground.value && voice.enabled &&
            (voice.status == NativeVoiceGuide.Status.READY ||
                 voice.status == NativeVoiceGuide.Status.AUDIO_FOCUS_DENIED)) {
            guide.speak("EV Cockpit. English speech test.", voice.volume)
        }
    }

    fun editOrigin(value: String) {
        debugDemoRouteJob?.cancel()
        debugDemoRouteAttempted = true
        debugDemoRouteActive = false
        val text = value.take(100)
        originJob?.cancel(); gpsJob?.cancel(); planningJob?.cancel()
        routes.clear() // never show an obsolete route for a newly edited origin
        _planner.update { it.copy(originText = text, originSelected = null,
            originFromGps = false, gpsOrigin = null, locatingGps = false,
            originMatches = emptyList(), error = null, planning = false) }
        originJob = search(text, true)
    }
    fun editDestination(value: String) {
        debugDemoRouteJob?.cancel()
        debugDemoRouteAttempted = true
        debugDemoRouteActive = false
        val text = value.take(100)
        destinationJob?.cancel(); planningJob?.cancel()
        routes.clear()
        _planner.update { it.copy(destinationText = text, destinationSelected = null,
            destinationMatches = emptyList(), planning = false, error = null) }
        destinationJob = search(text, false)
    }
    private fun search(value: String, origin: Boolean): Job = viewModelScope.launch {
        if (value.trim().length < 3) {
            _planner.update { if (origin) it.copy(searchingOrigin = false)
                else it.copy(searchingDestination = false) }
            return@launch
        }
        delay(550L) // protect the Photon demo server
        _planner.update { if (origin) it.copy(searchingOrigin = true)
            else it.copy(searchingDestination = true) }
        try {
            val found = routes.searchResult(value).getOrThrow()
            val error = if (found.isEmpty()) "Address not found. Enter a more specific location." else null
            _planner.update { if (origin) it.copy(originMatches = found, searchingOrigin = false, error = error)
                else it.copy(destinationMatches = found, searchingDestination = false, error = error) }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            val message = when (error) {
                is UnknownHostException -> "No internet connection"
                is SocketTimeoutException -> "Photon server timed out"
                is IOException -> error.message ?: "Could not reach the address service"
                else -> "Address search failed: ${error.message?.take(80) ?: "bilinmeyen hata"}"
            }
            _planner.update { if (origin) it.copy(searchingOrigin = false, error = message)
                else it.copy(searchingDestination = false, error = message) }
        }
    }
    fun selectOrigin(place: RoutePlace) {
        debugDemoRouteJob?.cancel()
        debugDemoRouteAttempted = true
        debugDemoRouteActive = false
        originJob?.cancel(); gpsJob?.cancel(); planningJob?.cancel(); routes.clear()
        _planner.update { it.copy(originText = place.label, originSelected = place,
            originFromGps = false, gpsOrigin = null, locatingGps = false,
            originMatches = emptyList(), error = null, planning = false) }
    }
    fun selectDestination(place: RoutePlace) {
        debugDemoRouteJob?.cancel()
        debugDemoRouteAttempted = true
        debugDemoRouteActive = false
        destinationJob?.cancel(); planningJob?.cancel(); routes.clear()
        _planner.update { it.copy(destinationText = place.label, destinationSelected = place,
            destinationMatches = emptyList(), error = null, planning = false) }
    }
    /** Activity first verifies fine/coarse runtime permission. */
    fun useCurrentGps() {
        if (_simulationEnabled.value) {
            _planner.update { it.copy(error = "Select origin and destination using address search in simulation mode.") }
            return
        }
        gpsJob?.cancel(); originJob?.cancel(); planningJob?.cancel(); routes.clear()
        gpsJob = viewModelScope.launch {
            _planner.update { it.copy(locatingGps = true, originFromGps = false,
                gpsOrigin = null, originSelected = null, originMatches = emptyList(), error = null) }
            try {
                val cached = locationRepository.gpsFix.value
                    ?.takeIf { it.usable(System.currentTimeMillis()) && it.accuracyMeters <= 35f }
                    ?.coordinate
                val result = if (cached != null)
                    Result.success(cached to locationRepository.gpsFix.value)
                else currentLocation.getCurrentMeasurement()

                // A single fresh Fused measurement updates the planner origin
                // and the same GPS StateFlow observed by MapLibre and the cockpit.
                result.getOrNull()?.second?.let(locationRepository::acceptFix)
                _gpsError.value = result.exceptionOrNull()?.message
                _planner.update { state ->
                    if (result.isSuccess) state.copy(
                        locatingGps = false, originFromGps = true,
                        gpsOrigin = result.getOrNull()?.first,
                        originText = "My current GPS location", error = null
                    ) else state.copy(locatingGps = false, originFromGps = false,
                        error = result.exceptionOrNull()?.message ?: "Could not obtain GPS location")
                }
            } catch (cancel: CancellationException) { throw cancel }
        }
    }
    fun locationPermissionDenied() {
        _gpsError.value = "Location permission was not granted."
        gpsJob?.cancel()
        _planner.update { it.copy(locatingGps = false, originFromGps = false,
            gpsOrigin = null, error = "Location permission was not granted; you can select the origin manually") }
    }
    fun plan() {
        debugDemoRouteJob?.cancel()
        debugDemoRouteAttempted = true
        debugDemoRouteActive = false
        if (!_planner.value.canCalculate) return
        planningJob?.cancel()
        planningJob = viewModelScope.launch {
            val state = _planner.value
            val start = state.startLocation
            val target = state.destinationSelected
            if (start == null || target == null) {
                _planner.update { it.copy(error = "Origin and destination have not been selected") }
                return@launch
            }
            _planner.update { it.copy(planning = true, error = null) }
            routes.clear() // Don't retain a successful stale route during failed replanning.
            try {
                when (val result = routes.calculateResult(start, target).getOrThrow()) {
                    is RouteCalculationResult.Success ->
                        _planner.update { it.copy(planning = false, error = null) }
                    is RouteCalculationResult.Failure ->
                        _planner.update { it.copy(
                            planning = false,
                            error = when {
                                result.message.contains("internet", ignoreCase = true) ->
                                    "No Internet Connection"
                                else -> "Could Not Calculate Route (OSRM Error): " + result.message.take(90)
                            }
                        ) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                _planner.update { it.copy(planning = false,
                    error = "Route error: ${error.message?.take(100) ?: "beklenmeyen hata"}") }
            }
        }
    }
    fun selectAlternative(index: Int) { routes.choose(index); speechBridge.reset() }
    fun clearRoute() {
        debugDemoRouteJob?.cancel()
        debugDemoRouteAttempted = true
        debugDemoRouteActive = false
        originJob?.cancel(); destinationJob?.cancel(); gpsJob?.cancel(); planningJob?.cancel()
        routes.clear(); speechBridge.reset(); guide.stop(); _planner.value = RoutePlannerUiState()
    }
    override fun onCleared() {
        debugDemoRouteJob?.cancel()
        guide.close()
        locationRepository.stop()
        super.onCleared()
    }
}
````

## 12. `nativecockpit/src/main/java/com/evcockpit/presentation/voice/AudioDuckingManager.kt`

````kotlin
package com.evcockpit.presentation.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * One audio focus owner for short navigation speech.
 * Android ducks cooperative media apps; no stream volume or DRM manipulation.
 */
class AudioDuckingManager(
    context: Context,
    private val onFocusLost: () -> Unit
) : AutoCloseable {
    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val manager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val listener: AudioManager.OnAudioFocusChangeListener =
        AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        ) {
            main.post {
                // Late callbacks must not resurrect/interrupt closed TTS.
                if (lease.shouldStopAfterLoss()) onFocusLost()
            }
        }
    }
    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    )
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(listener, main)
        .build()

    private val lease: AudioFocusLease = AudioFocusLease(
        requestFocus = {
            manager.requestAudioFocus(focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        },
        abandonFocus = { manager.abandonAudioFocusRequest(focusRequest) }
    )

    fun acquire(): Boolean = lease.acquire()
    fun release() = lease.release()
    override fun close() = lease.close()
}
````

## 13. `nativecockpit/src/main/AndroidManifest.xml`

````xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <!-- Android 13+: only request at runtime when posting a notification. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="androidx.car.app.NAVIGATION_TEMPLATES" />
    <uses-permission android:name="androidx.car.app.ACCESS_SURFACE" />

    <queries>
        <package android:name="me.app.fermataX.auto.evcockpitpreview.debug" />
        <package android:name="me.app.fermataX.auto.evcockpitpreview" />
        <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
        <package android:name="com.netflix.mediaclient" />
        <package android:name="com.disney.disneyplus" />
        <package android:name="com.amazon.avod.thirdpartyclient" />
    </queries>

    <application
        android:name=".EyaApplication"
        android:allowBackup="false"
        android:label="@string/app_name"
        android:icon="@drawable/ic_eya_car"
        android:usesCleartextTraffic="false"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity android:name=".MainActivity"
            android:exported="true"
            android:supportsPictureInPicture="true"
            android:configChanges="screenSize|smallestScreenSize|screenLayout|orientation"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <meta-data android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc" />
        <meta-data android:name="androidx.car.app.minCarApiLevel" android:value="1" />
        <service android:name=".car.EyaCarAppService"
            android:label="@string/app_name"
            android:icon="@drawable/ic_eya_car"
            android:exported="true">
            <intent-filter>
                <action android:name="androidx.car.app.CarAppService" />
                <category android:name="androidx.car.app.category.NAVIGATION" />
            </intent-filter>
        </service>
        <service
            android:name=".ObdForegroundService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
        <!-- Only an explicit user-initiated Bluetooth connection starts this service.
             No background auto-connect, no unrestricted background location service. -->
    </application>
</manifest>
````

## 14. `nativecockpit/proguard-rules.pro`

````proguard
# :nativecockpit only — conservative, temporary R8 stabilization.
# Other modules (:app, :evlite and MediaEngine/Fermata) are untouched.
# No device stack trace was provided: these rules do NOT prove a specific crash fix.
#
# These rules are dormant while native Release isMinifyEnabled=false.
# If shrinking is re-enabled, diagnose a retraced FATAL EXCEPTION before adding
# further keep rules. Layer ownership crashes require a source fix, not a keep rule.
-dontoptimize
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
-keepclasseswithmembers class * { native <methods>; }

# Hilt/Dagger Android entrypoints and generated injection members.
# Dagger/Hilt already package consumer ProGuard rules, so do not globally disable shrinking.
-keep class com.evcockpit.EyaApplication { *; }
-keep class com.evcockpit.MainActivity { *; }
-keep class com.evcockpit.car.EyaCarAppService { *; }
-keep class com.evcockpit.Hilt_* { *; }
-keep class com.evcockpit.car.Hilt_* { *; }
-keep class com.evcockpit.presentation.**.Hilt_* { *; }
-keep class com.evcockpit.**_Factory { *; }
-keep class com.evcockpit.**_MembersInjector { *; }
-keep class com.evcockpit.Dagger* { *; }

# Retrofit 3 (dynamic proxies, suspend and generic response signatures).
-keep class kotlin.coroutines.Continuation
-keep interface com.evcockpit.data.routing.OsrmService { *; }
-keep interface com.evcockpit.data.routing.PhotonService { *; }
-keep interface com.evcockpit.data.remote.EpdkService { *; }
-keep interface com.evcockpit.data.radio.RadioBrowserApi { *; }
-keep class com.evcockpit.data.radio.RadioStation { *; }

# Gson reflection over annotated DTO fields, plus library consumer rules.
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# MapLibre JNI, lifecycle and external surface entrypoints.
-keep class org.maplibre.android.MapLibre { *; }
-keep class org.maplibre.android.maps.NativeMapView { *; }
-keep class org.maplibre.android.maps.MapView { *; }
-keep class org.maplibre.android.maps.Style { *; }

# Media3 already ships official consumer ProGuard rules; retain our direct
# player integration entrypoints as a conservative compatibility guard.
# No blanket -dontwarn or global -keep androidx.media3.**.
-keep class androidx.media3.common.MediaItem { *; }
-keep class androidx.media3.exoplayer.ExoPlayerImpl { *; }
-keep class androidx.media3.exoplayer.hls.HlsMediaSource { *; }

# Vehicle diagnostic protocol, Bluetooth transports and Android Auto host surface.
-keep class com.evcockpit.data.obd.** { *; }
-keep class com.evcockpit.domain.usecase.Elm327Session { *; }
-keep class com.evcockpit.car.EyaCarMapSurface { *; }

# No blanket -dontwarn. Missing APIs should fail the build.
````

## 15. `gradle.properties`

````properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
# Temporary R8 compatibility mode; nativecockpit release minification is disabled.
android.enableR8.fullMode=false
````

## 16. `nativecockpit/build.gradle.kts`

````kotlin
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Local release signing credentials live in ignored local.properties.
// GitHub Actions supplies the same values as environment variables.
val localSigning = Properties().apply {
    val config = rootProject.file("local.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, environmentKey: String): String? =
    providers.environmentVariable(environmentKey).orNull
        ?.takeIf { it.isNotBlank() }
        ?: localSigning.getProperty(propertyKey)?.trim()?.takeIf { it.isNotBlank() }

val storePath = signingValue("eya.release.storeFile", "EYA_RELEASE_STORE_FILE")
val storePasswordValue = signingValue("eya.release.storePassword", "EYA_RELEASE_STORE_PASSWORD")
val keyAliasValue = signingValue("eya.release.keyAlias", "EYA_RELEASE_KEY_ALIAS")
val keyPasswordValue = signingValue("eya.release.keyPassword", "EYA_RELEASE_KEY_PASSWORD")
val releaseStore = storePath?.let { path ->
    File(path).let { if (it.isAbsolute) it else rootProject.file(path) }
}
val signedReleaseReady = releaseStore?.isFile == true &&
    !storePasswordValue.isNullOrBlank() &&
    !keyAliasValue.isNullOrBlank() &&
    !keyPasswordValue.isNullOrBlank()

android {
    namespace = "com.evcockpit"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.evcockpit"
        minSdk = 28
        targetSdk = 35
        versionCode = 22
        versionName = "2.0.5"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        // No debug-key fallback. This is populated only with real owner credentials.
        if (signedReleaseReady) {
            create("eyaProduction") {
                storeFile = releaseStore
                storePassword = storePasswordValue
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Remains the explicitly marked UAT/simulation variant.
            isMinifyEnabled = false
        }
        getByName("release") {
            isDebuggable = false
            // Diagnostic release: disable R8 code + resource shrinking until crash is reproduced.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signedReleaseReady) {
                signingConfig = signingConfigs.getByName("eyaProduction")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = false }
}

// Fail closed: a signing-less artifact must NEVER be called a production APK.
gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { task ->
        task.project.path == ":nativecockpit" &&
            task.name in setOf("assembleRelease", "packageRelease", "validateSigningRelease")
    }
    if (buildingRelease && !signedReleaseReady) {
        throw GradleException(
            "Production release signing is not configured. Set " +
                "eya.release.storeFile/storePassword/keyAlias/keyPassword " +
                "in ignored local.properties (or EYA_RELEASE_* environment variables)."
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.activity:activity-ktx:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-compiler:2.58")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("org.maplibre.gl:android-sdk-turf:6.0.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
````

## 17. `nativecockpit/src/main/res/values/strings.xml`

````xml
<?xml version='1.0' encoding='utf-8'?>
<resources>
    <string name="app_name" formatted="false">EV Cockpit</string>
    <string name="native_arrival_soc_model_estimate" formatted="false">Arrival SoC · MODEL/ESTIMATE</string>
    <string name="native_automatic_dc_candidates_model_estimate" formatted="false">Automatic DC candidates · MODEL/ESTIMATE</string>
    <string name="native_back_to_map" formatted="false">Back to map</string>
    <string name="native_battery" formatted="false">"BATTERY · "</string>
    <string name="native_battery_current_raw_response_only_221e3d" formatted="false">Battery current · raw response only · 221E3D</string>
    <string name="native_battery_state_of_charge_obd" formatted="false">BATTERY SoC · OBD</string>
    <string name="native_battery_state_of_charge_test" formatted="false">BATTERY SoC · TEST</string>
    <string name="native_battery_temperature" formatted="false">Battery temperature</string>
    <string name="native_bluetooth_classic_spp_elm327_pair_the_adapter_in_android_bluetooth_set" formatted="false">Bluetooth Classic SPP ELM327. Pair the adapter in Android Bluetooth settings first.</string>
    <string name="native_bms_soc_diagnostic_candidate_dashboard_soc_and_soh_are_different_quant" formatted="false">"BMS SoC (diagnostic candidate), dashboard SoC, and SoH are different quantities. "</string>
    <string name="native_calculate_actual_road_route" formatted="false">Calculate actual road route</string>
    <string name="native_calculating_route" formatted="false">Calculating route…</string>
    <string name="native_charging" formatted="false">Charging</string>
    <string name="native_charging_needed_model_estimate" formatted="false">Charging needed · MODEL/ESTIMATE</string>
    <string name="native_charging_required_to_preserve_the_reserve" formatted="false">Charging required to preserve the reserve</string>
    <string name="native_clear_diagnostic_result" formatted="false">Clear diagnostic result</string>
    <string name="native_clear_route" formatted="false">Clear route</string>
    <string name="native_close" formatted="false">Close</string>
    <string name="native_could_not_initialize_the_speech_engine" formatted="false">Could not initialize the speech engine</string>
    <string name="native_could_not_obtain_audio_focus" formatted="false">Could not obtain audio focus</string>
    <string name="native_dc_charging_stations_along_the_route" formatted="false">DC Charging Stations Along the Route</string>
    <string name="native_departure" formatted="false">Departure</string>
    <string name="native_destination" formatted="false">Destination</string>
    <string name="native_destination_address_or_city" formatted="false">Destination address or city</string>
    <string name="native_destination_area_current_weather_estimate" formatted="false">Destination area · Current weather estimate</string>
    <string name="native_destination_area_wind" formatted="false">Destination area · Wind</string>
    <string name="native_destination_selected" formatted="false">Destination selected</string>
    <string name="native_disconnect_obd" formatted="false">Disconnect OBD</string>
    <string name="native_download_english_voice_data_from_settings_language_and_input_text_to_s" formatted="false">"Download English voice data from Settings &gt; Language and input &gt; Text-to-speech. "</string>
    <string name="native_electric_road_companion" formatted="false">EV COCKPIT</string>
    <string name="native_english_speech_ready" formatted="false">English speech ready</string>
    <string name="native_english_speech_test" formatted="false">English speech test</string>
    <string name="native_english_tts_data_missing" formatted="false">English TTS Data Missing</string>
    <string name="native_english_tts_data_missing_90a54a" formatted="false">English TTS data missing</string>
    <string name="native_epdk_filter" formatted="false">EPDK · Filter</string>
    <string name="native_epdk_records_are_stale" formatted="false">EPDK records are stale</string>
    <string name="native_epdk_station_filters" formatted="false">EPDK station filters</string>
    <string name="native_estimated_arrival_time" formatted="false">Estimated arrival time</string>
    <string name="native_estimated_drive_time" formatted="false">Estimated drive time</string>
    <string name="native_estimated_range_model_15_reserve" formatted="false">ESTIMATED RANGE · MODEL · 15% RESERVE</string>
    <string name="native_estimates_exclude_traffic_arrival_soc_is_not_calculated" formatted="false">"Estimates exclude traffic. Arrival SoC is not calculated "</string>
    <string name="native_experimental_meb_ecu_readings" formatted="false">Experimental MEB ECU readings</string>
    <string name="native_from" formatted="false">FROM</string>
    <string name="native_getting_gps_location" formatted="false">Getting GPS location…</string>
    <string name="native_hv_voltage" formatted="false">HV voltage</string>
    <string name="native_in_app_media_browser" formatted="false">In-app media browser</string>
    <string name="native_in_app_media_on_the_phone" formatted="false">In-app media on the phone</string>
    <string name="native_instantaneous_power" formatted="false">Instantaneous power</string>
    <string name="native_live_availability_price_and_road_access_are_unknown" formatted="false">Live availability, price, and road access are unknown.</string>
    <string name="native_locations_selected_calculate_the_actual_osrm_route" formatted="false">Locations selected; calculate the actual OSRM route</string>
    <string name="native_maplibre_osrm_voice_guidance_step_7" formatted="false">MAPLIBRE · OSRM · VOICE GUIDANCE · STEP 7</string>
    <string name="native_maximum_battery_temperature_221e0e" formatted="false">Maximum battery temperature · 221E0E</string>
    <string name="native_media" formatted="false">Media</string>
    <string name="native_mute_voice_guidance" formatted="false">Mute voice guidance</string>
    <string name="native_navigation" formatted="false">NAVIGATION</string>
    <string name="native_no_paired_devices_this_screen_does_not_scan" formatted="false">No paired devices. This screen does not scan.</string>
    <string name="native_no_validated_dc_station_within_5_km_of_the_route_line" formatted="false">No validated DC station within 5 km of the route line.</string>
    <string name="native_no_validated_ecu_response_received_data_was_not_used" formatted="false">No validated ECU response received; data was not used.</string>
    <string name="native_not_actual_vehicle_data_available_in_debug_builds_only" formatted="false">Not actual vehicle data; available in DEBUG builds only.</string>
    <string name="native_not_required_according_to_the_model" formatted="false">Not required according to the model</string>
    <string name="native_obd_connection_and_meb_diagnostics" formatted="false">OBD Connection and MEB Diagnostics</string>
    <string name="native_obd_reading_no_more_than_10_seconds_old" formatted="false">OBD reading · no more than 10 seconds old</string>
    <string name="native_obd_soc_stale_disconnected" formatted="false">OBD SoC stale / disconnected</string>
    <string name="native_ok" formatted="false">OK</string>
    <string name="native_openfreemap_openstreetmap" formatted="false">© OpenFreeMap · © OpenStreetMap</string>
    <string name="native_origin" formatted="false">Origin</string>
    <string name="native_origin_address_or_city" formatted="false">Origin address or city</string>
    <string name="native_origin_and_destination_have_not_been_selected" formatted="false">Origin and destination have not been selected</string>
    <string name="native_origin_selected" formatted="false">Origin selected</string>
    <string name="native_osrm_alternatives" formatted="false">OSRM alternatives (</string>
    <string name="native_osrm_eta_is_a_model_excluding_traffic_charging_time_is_calculated_sepa" formatted="false">OSRM ETA is a model excluding traffic; charging time is calculated separately.</string>
    <string name="native_photon_osm_addresses_actual_osrm_route_not_live_traffic" formatted="false">Photon / OSM addresses · actual OSRM route · not live traffic</string>
    <string name="native_plan_an_actual_route_first" formatted="false">Plan an actual route first</string>
    <string name="native_preparing_english_speech" formatted="false">Preparing English speech</string>
    <string name="native_read_bms_state_of_charge_22028c" formatted="false">Read BMS state of charge · 22028C</string>
    <string name="native_red_green_lines_osrm_speed_profile_not_live_traffic" formatted="false">Red/green lines: OSRM speed profile · NOT LIVE TRAFFIC</string>
    <string name="native_refresh" formatted="false">Refresh</string>
    <string name="native_refresh_paired_devices_request_permission" formatted="false">Refresh paired devices / request permission</string>
    <string name="native_remaining_gps" formatted="false">Remaining · GPS</string>
    <string name="native_required_charge_model_estimate" formatted="false">Required charge · MODEL/ESTIMATE</string>
    <string name="native_route" formatted="false">Route</string>
    <string name="native_route_and_charging_plan" formatted="false">Route and charging plan</string>
    <string name="native_route_progress" formatted="false">ROUTE PROGRESS</string>
    <string name="native_settings" formatted="false">Settings</string>
    <string name="native_sign_in_to_your_account_on_the_site_playback_support_depends_on_the_se" formatted="false">Sign in to your account on the site. Playback support depends on the service and your device.</string>
    <string name="native_simulation_mode_test_data_not_actual_location_obd" formatted="false">SIMULATION MODE · TEST DATA · NOT ACTUAL LOCATION/OBD</string>
    <string name="native_simulation_test_data" formatted="false">SIMULATION · TEST DATA</string>
    <string name="native_speed_measured" formatted="false">Speed · measured</string>
    <string name="native_start_from_my_current_gps_location" formatted="false">Start from my current GPS location</string>
    <string name="native_status" formatted="false">"Status: "</string>
    <string name="native_test_only_while_parked_and_in_your_own_vehicle_queries_use_read_only_u" formatted="false">"Test ONLY while parked and in your own vehicle. Queries use read-only UDS service 22. "</string>
    <string name="native_test_waiting_for_osrm_maneuver" formatted="false">TEST · waiting for OSRM maneuver</string>
    <string name="native_to" formatted="false">TO</string>
    <string name="native_total_route_osrm" formatted="false">Total route · OSRM</string>
    <string name="native_try_again" formatted="false">Try again</string>
    <string name="native_turquoise_ring_dc_within_5_km_of_route_not_driving_detour" formatted="false">Turquoise ring: DC within 5 km of route (not driving detour)</string>
    <string name="native_unmute_voice_guidance" formatted="false">Unmute voice guidance</string>
    <string name="native_vehicle_information" formatted="false">Vehicle information</string>
    <string name="native_video_is_unavailable_for_this_connection" formatted="false">Video is unavailable for this connection.</string>
    <string name="native_video_is_unavailable_on_this_screen" formatted="false">Video is unavailable on this screen.</string>
    <string name="native_voice" formatted="false">VOICE</string>
    <string name="native_voice_guidance" formatted="false">Voice guidance</string>
    <string name="native_waiting_for_gps_signal" formatted="false">Waiting for GPS signal...</string>
    <string name="native_waiting_for_maneuver" formatted="false">Waiting for maneuver</string>
    <string name="native_waiting_for_route" formatted="false">Waiting for route</string>
    <string name="native_weather_open_meteo_current_model_estimate_not_arrival_time_weather" formatted="false">Weather: Open-Meteo · current model estimate, not arrival-time weather</string>
    <string name="cockpit_gps" formatted="false">Locate vehicle</string>
    <string name="cockpit_estimated_range" formatted="false">Estimated range · model</string>
    <string name="cockpit_measurement_unavailable" formatted="false">Vehicle measurement unavailable</string>
    <string name="cockpit_arrival" formatted="false">Arrival time</string>
    <string name="cockpit_arrival_soc" formatted="false">Arrival SoC · estimate</string>
    <string name="cockpit_plan_route" formatted="false">Plan a route</string>
    <string name="cockpit_station_name" formatted="false">Charging station</string>
    <string name="cockpit_operator_unknown" formatted="false">Operator not specified</string>
    <string name="cockpit_address_unknown" formatted="false">Address not specified</string>
    <string name="cockpit_source_date_unknown" formatted="false">Source date unknown</string>
    <string name="cockpit_filter_all" formatted="false">All</string>
    <string name="cockpit_filter_dc" formatted="false">DC</string>
    <string name="cockpit_filter_route_ccs" formatted="false">Route CCS</string>
    <string name="cockpit_filter_route_dc" formatted="false">Route DC</string>
    <string name="cockpit_unknown_value" formatted="false">—</string>
    <string name="cockpit_percent_symbol" formatted="false">%</string>
    <string name="cockpit_distance_unit" formatted="false">km</string>
    <string name="cockpit_voice_volume" formatted="false">Guidance volume</string>
    <string name="cockpit_model_estimate" formatted="false">MODEL/ESTIMATE</string>
    <string name="cockpit_socket_summary">%1$s · %2$s · %3$s</string>
    <string name="cockpit_source_verified">EPDK: %1$s</string>
    <string name="cockpit_station_count">EPDK · %1$d / %2$d</string>
    <string name="media_engine_open">Open Media Engine</string>
    <string name="media_engine_unavailable_title">Media Engine unavailable</string>
    <string name="media_engine_not_installed">Install the separate Media Engine application first. You can also choose YouTube below to use the in-app browser.</string>
    <string name="media_engine_blocked">Android blocked the application launch. Check the installed application and its profile restrictions.</string>
    <string name="media_video_tab">Video</string>
    <string name="media_radio_tab">Radio</string>
    <string name="radio_description">Popular stations in Türkiye, ranked by votes. HTTPS streams only. Playback stops when this screen closes.</string>
    <string name="radio_selected">Selected: %1$s</string>
    <string name="radio_playback_error">Unable to play this station. Retry or select another station.</string>
    <string name="radio_load_error">Unable to load stations. Check your connection and try again.</string>
    <string name="radio_empty">No compatible stations were found.</string>
    <string name="radio_play">Play</string>
    <string name="radio_pause">Pause</string>
    <string name="radio_stop">Stop</string>
    <string name="radio_refresh">Refresh</string>
    <string name="radio_search">Search Turkish stations or genres</string>
    <string name="radio_results">%1$d / %2$d stations</string>
    <string name="radio_votes">%1$d votes</string>
    <string name="radio_attribution">Station directory: Radio Browser · Stream availability may change.</string>
    <string name="media_external_not_installed">The application is not installed on this device. Install the official application to continue.</string>
    <string name="media_external_launch_blocked">The application is installed but Android could not launch it. Check app availability, disabled apps, and profile restrictions.</string>
</resources>
````

## 18. `nativecockpit/src/main/res/values-tr/strings.xml`

````xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="media_video_tab">Video</string>
    <string name="media_radio_tab">Radyo</string>
    <string name="media_engine_open">Medya motorunu aç</string>
    <string name="media_engine_unavailable_title">Medya motoru açılamadı</string>
    <string name="media_engine_not_installed">Ayrı medya motoru uygulaması yüklü değil. Video sekmesinden dahili tarayıcıyı kullanabilirsiniz.</string>
    <string name="media_engine_blocked">Medya motoru yüklü ancak Android başlatılmasına izin vermedi veya açılış etkinliği bulunamadı. Uygulama izinlerini ve iş profili kısıtlamalarını kontrol edin.</string>
    <string name="radio_description">Türkiye radyo kanalları oy sayısına göre sıralanır. Yalnızca HTTPS yayınları listelenir. Bu ekran kapatılınca yayın durur.</string>
    <string name="radio_search">Radyo veya müzik türü ara</string>
    <string name="radio_results">%1$d / %2$d kanal</string>
    <string name="radio_selected">Seçilen: %1$s</string>
    <string name="radio_playback_error">Radyo yayını oynatılamadı. Tekrar deneyin veya başka kanal seçin.</string>
    <string name="radio_load_error">Radyo kanalları yüklenemedi. İnternet bağlantısını kontrol edin.</string>
    <string name="radio_empty">Uyumlu radyo kanalı bulunamadı.</string>
    <string name="radio_play">Oynat</string>
    <string name="radio_pause">Duraklat</string>
    <string name="radio_stop">Durdur</string>
    <string name="radio_refresh">Yenile</string>
    <string name="radio_votes">%1$d oy</string>
    <string name="radio_attribution">Radyo rehberi: Radio Browser. Yayın bağlantıları zamanla değişebilir.</string>
    <string name="media_external_not_installed">Uygulama cihazda yüklü değil. Resmî uygulamayı yükledikten sonra tekrar deneyin.</string>
    <string name="media_external_launch_blocked">Uygulama cihazda yüklü ancak Android tarafından açılamadı. İş profili, izinler veya devre dışı bırakılmış uygulama ayarlarını kontrol edin.</string>
</resources>
````

