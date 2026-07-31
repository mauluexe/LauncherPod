package dev.maulu.launcherpod

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun LifePodApp(
    viewModel: LauncherViewModel,
    homeEvent: Int = 0,
    lockEvent: Int = 0,
    musicPermissionEvent: Int = 0,
    homeRoleEvent: Int = 0,
    onRequestHomeRole: () -> Unit = {},
    onRequestAudioPermission: () -> Unit = {}
) {
    val state by viewModel.state.collectAsState()
    val externalPlayback by ExternalPlaybackBridge.state.collectAsState()
    var interactionTick by remember { mutableIntStateOf(0) }
    var showIdleNowPlaying by remember { mutableStateOf(false) }
    var manualNowPlaying by remember { mutableStateOf(false) }

    fun interacted() {
        interactionTick++
        showIdleNowPlaying = false
    }

    LaunchedEffect(interactionTick, state.screen, state.isPlaying, state.currentTrackIndex, externalPlayback?.isPlaying, state.playbackMode) {
        showIdleNowPlaying = false
        val active = state.isPlaying || externalPlayback?.isPlaying == true
        if (active && state.screen == Screen.HOME) {
            delay(5_000)
            showIdleNowPlaying = true
        }
    }

    LaunchedEffect(homeEvent) {
        viewModel.resetHome()
    }
    LaunchedEffect(lockEvent) {
        if (lockEvent > 0) viewModel.lock()
    }
    LaunchedEffect(musicPermissionEvent) {
        if (musicPermissionEvent > 0) viewModel.completeAudioSetup()
    }
    LaunchedEffect(homeRoleEvent) {
        if (homeRoleEvent > 0) viewModel.completeHomeSetup()
    }

    val handleSelect: () -> Unit = {
        interacted()
        when (state.screen) {
            Screen.ONBOARDING_HOME -> onRequestHomeRole()
            Screen.ONBOARDING_AUDIO -> onRequestAudioPermission()
            else -> viewModel.select()
        }
    }
    val handleItemClick: (Int) -> Unit = { index ->
        interacted()
        when (state.screen) {
            Screen.ONBOARDING_HOME -> onRequestHomeRole()
            Screen.ONBOARDING_AUDIO -> onRequestAudioPermission()
            else -> viewModel.selectIndex(index)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bodyColor(state.config.bodyTheme)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LifePodDisplay(
                state = state,
                items = viewModel.currentItems(),
                onItemClick = handleItemClick,
                showIdleNowPlaying = showIdleNowPlaying || (manualNowPlaying && state.screen == Screen.HOME),
                externalPlayback = externalPlayback,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            Spacer(Modifier.height(22.dp))
            ClickWheel(
                config = state.config,
                state = state,
                onPatternSubmit = { interacted(); viewModel.submitPattern(it) },
                onPrevious = { interacted(); if (manualNowPlaying && state.screen == Screen.HOME) viewModel.mediaPrevious() else viewModel.previous() },
                onNext = { interacted(); if (manualNowPlaying && state.screen == Screen.HOME) viewModel.mediaNext() else viewModel.next() },
                onSelect = handleSelect,
                onBack = {
                    interacted()
                    if (manualNowPlaying && state.screen == Screen.HOME) manualNowPlaying = false else viewModel.back()
                },
                onPlayPause = { interacted(); if (manualNowPlaying && state.screen == Screen.HOME) viewModel.mediaPlayPause() else viewModel.playPause() },
                onShuffle = { interacted(); viewModel.toggleShuffle() },
                onRepeat = { interacted(); viewModel.toggleRepeat() },
                onMode = {
                    interacted()
                    if (state.screen == Screen.NOW_PLAYING) {
                        manualNowPlaying = false
                        viewModel.resetHome()
                    } else {
                        manualNowPlaying = !manualNowPlaying
                    }
                },
                nowPlayingControls = state.screen == Screen.NOW_PLAYING || (state.screen == Screen.HOME && manualNowPlaying)
            )
        }
    }
}

@Composable
private fun LifePodDisplay(
    state: LauncherState,
    items: List<String>,
    onItemClick: (Int) -> Unit,
    showIdleNowPlaying: Boolean,
    externalPlayback: ExternalPlayback?,
    modifier: Modifier = Modifier
) {
    val hasCurrentMusic = state.currentTrackIndex in state.tracks.indices || externalPlayback != null
    val showLockNowPlaying = state.screen == Screen.LOCK && hasCurrentMusic
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        color = displayColor(state.config.displayTheme)
    ) {
        AnimatedContent(state.screen, label = "screen") { screen ->
            Column(Modifier.padding(22.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (showIdleNowPlaying) "Now Playing" else screen.title(state.config),
                        color = displayTextColor(state.config.displayTheme),
                        fontSize = (26 * state.config.textScale).sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.config.showCornerClock && !showLockNowPlaying) CornerClock(state.config)
                }
                Spacer(Modifier.height(22.dp))

                if (showLockNowPlaying) {
                    LockNowPlaying(state, externalPlayback)
                } else if (showIdleNowPlaying) {
                    NowPlaying(state, externalPlayback)
                } else when (screen) {
                    Screen.ONBOARDING -> WelcomeScreen(state.config) { onItemClick(0) }
                    Screen.ONBOARDING_THEME -> ThemePicker(state.selectedIndex, state.config, onItemClick)
                    Screen.ONBOARDING_CREDENTIAL -> CredentialSetup(state)
                    Screen.NOW_PLAYING -> NowPlaying(state, externalPlayback)
                    Screen.LOCK -> PatternLock(state)
                    Screen.SET_CREDENTIAL -> CredentialSetup(state)
                    else -> MenuList(items, state.selectedIndex, state.config, onItemClick)
                }
            }
        }
    }
}

@Composable
private fun LockNowPlaying(state: LauncherState, external: ExternalPlayback?) {
    val track = state.tracks.getOrNull(state.currentTrackIndex)
    val useExternal = external != null && (external.isPlaying || !state.isPlaying)
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            now.format(DateTimeFormatter.ofPattern("HH:mm")),
            color = displayTextColor(state.config.displayTheme),
            fontSize = 44.sp,
            fontWeight = FontWeight.Light
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (useExternal) ExternalArtwork(external?.artwork, 88) else AlbumArtwork(track, 88)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (useExternal) external?.title.orEmpty() else track?.title ?: "Nothing Playing",
                    color = displayTextColor(state.config.displayTheme),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                Text(
                    if (useExternal) external?.artist.orEmpty() else track?.artist.orEmpty(),
                    color = displayTextColor(state.config.displayTheme).copy(alpha = .62f),
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.LightGray, CircleShape)) {
            Box(
                Modifier
                    .fillMaxWidth(playbackFraction(state, external))
                    .height(4.dp)
                    .background(accentColor(state.config.accentTheme), CircleShape)
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlaybackTime(if (useExternal) external?.positionMs ?: 0 else state.playbackPositionMs), color = Color.Gray, fontSize = 13.sp)
            Text(formatPlaybackTime(if (useExternal) external?.durationMs ?: 0 else state.playbackDurationMs), color = Color.Gray, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ThemePicker(selectedIndex: Int, config: PodConfig, onItemClick: (Int) -> Unit) {
    val choices = listOf(
        PodConfig(bodyTheme = 3, displayTheme = 4, wheelTheme = 4, accentTheme = 1),
        PodConfig(bodyTheme = 0, displayTheme = 0, wheelTheme = 0, accentTheme = 0),
        PodConfig(bodyTheme = 1, displayTheme = 0, wheelTheme = 1, accentTheme = 1),
        PodConfig(bodyTheme = 2, displayTheme = 0, wheelTheme = 2, accentTheme = 2)
    )
    val names = listOf("Original", "Classic Black", "Silver", "Mono")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(choices) { index, preview ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = if (index == selectedIndex) 3.dp else 1.dp,
                        color = if (index == selectedIndex) accentColor(config.accentTheme) else Color.Gray.copy(alpha = .35f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable { onItemClick(index) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 112.dp, height = 72.dp)
                        .background(bodyColor(preview.bodyTheme), RoundedCornerShape(12.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp, top = 8.dp)
                            .size(width = 66.dp, height = 34.dp)
                            .background(displayColor(preview.displayTheme), RoundedCornerShape(6.dp))
                    ) {
                        Box(
                            Modifier
                                .padding(6.dp)
                                .fillMaxWidth()
                                .height(7.dp)
                                .background(accentColor(preview.accentTheme), RoundedCornerShape(4.dp))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(7.dp)
                            .size(43.dp)
                            .background(wheelColor(preview.wheelTheme), CircleShape)
                            .border(1.dp, Color.Black.copy(alpha = .15f), CircleShape)
                    )
                }
                Column {
                    Text(names[index], color = displayTextColor(config.displayTheme), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Body · Screen · Wheel", color = displayTextColor(config.displayTheme).copy(alpha = .55f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun WelcomeScreen(config: PodConfig, onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Your music. Your apps.", color = displayTextColor(config.displayTheme).copy(alpha = .65f), fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        Text("LauncherPod", color = displayTextColor(config.displayTheme), fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            "A modern iPod-style home\nfor your Android phone.",
            color = displayTextColor(config.displayTheme).copy(alpha = .72f),
            fontSize = 17.sp
        )
        Spacer(Modifier.height(30.dp))
        Text(
            "GET STARTED",
            modifier = Modifier
                .background(accentColor(config.accentTheme), RoundedCornerShape(50))
                .clickable(onClick = onStart)
                .padding(horizontal = 26.dp, vertical = 12.dp),
            color = accentTextColor(config.accentTheme),
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text("or press the center button", color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
private fun CornerClock(config: PodConfig) {
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000)
        }
    }
    Text(
        now.format(DateTimeFormatter.ofPattern("HH:mm")),
        color = displayTextColor(config.displayTheme).copy(alpha = .55f)
    )
}

@Composable
private fun MenuList(items: List<String>, selectedIndex: Int, config: PodConfig, onItemClick: (Int) -> Unit) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedIndex, items.size) {
        if (items.isNotEmpty() && selectedIndex in items.indices) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        itemsIndexed(items) { index, label ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onItemClick(index) }
                    .background(
                        if (index == selectedIndex) accentColor(config.accentTheme)
                        else Color.Transparent,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    label,
                    color = if (index == selectedIndex) accentTextColor(config.accentTheme)
                    else displayTextColor(config.displayTheme),
                    fontSize = (17 * config.textScale).sp
                )
                Text(
                    "›",
                    color = if (index == selectedIndex) accentTextColor(config.accentTheme)
                    else Color.Gray
                )
            }
        }
    }
}

@Composable
private fun NowPlaying(state: LauncherState, external: ExternalPlayback?) {
    val track = state.tracks.getOrNull(state.currentTrackIndex)
    val useExternal = external != null && (external.isPlaying || !state.isPlaying)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (useExternal) ExternalArtwork(external?.artwork) else AlbumArtwork(track)
        Spacer(Modifier.height(10.dp))
        Text(
            if (useExternal) external?.title.orEmpty() else track?.title ?: "Nothing Playing",
            color = displayTextColor(state.config.displayTheme),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            if (useExternal) "${external?.artist.orEmpty()}  ·  ${external?.album.orEmpty()}" else if (track == null) "Choose a song from Music" else "${track.artist}  ·  ${track.album}",
            color = displayTextColor(state.config.displayTheme).copy(alpha = .6f),
            fontSize = 13.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(5.dp))
        Text(
            (if (useExternal) "EXTERNAL" else "LAUNCHERPOD") + "  ·  " + (if (if (useExternal) external?.isPlaying == true else state.isPlaying) "PLAYING" else "PAUSED") +
                (if (!useExternal && state.shuffleEnabled) "  ·  SHUFFLE" else "") +
                (if (!useExternal && state.repeatMode != RepeatMode.OFF) "  ·  LOOP ${state.repeatMode.name}" else ""),
            color = accentColor(state.config.accentTheme),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Color.LightGray, CircleShape)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(playbackFraction(state, external))
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.secondary, CircleShape)
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlaybackTime(if (useExternal) external?.positionMs ?: 0 else state.playbackPositionMs), color = Color.Gray, fontSize = 13.sp)
            Text(formatPlaybackTime(if (useExternal) external?.durationMs ?: 0 else state.playbackDurationMs), color = Color.Gray, fontSize = 13.sp)
        }
    }
}

private fun playbackFraction(state: LauncherState, external: ExternalPlayback?): Float {
    val useExternal = external != null && (external.isPlaying || !state.isPlaying)
    val position = if (useExternal) external?.positionMs ?: 0 else state.playbackPositionMs
    val duration = if (useExternal) external?.durationMs ?: 0 else state.playbackDurationMs
    return if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

private fun formatPlaybackTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

@Composable
private fun AlbumArtwork(track: MusicTrack?, artworkSize: Int = 130) {
    val context = LocalContext.current
    val artwork by produceState<Bitmap?>(initialValue = null, track?.uri) {
        value = withContext(Dispatchers.IO) {
            track?.let {
                runCatching {
                    MediaMetadataRetriever().run {
                        try {
                            setDataSource(context, it.uri)
                            embeddedPicture?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                        } finally {
                            release()
                        }
                    }
                }.getOrNull()
            }
        }
    }

    if (artwork != null) {
        Image(
            bitmap = artwork!!.asImageBitmap(),
            contentDescription = track?.album,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(artworkSize.dp)
        )
    } else {
        Box(
            Modifier
                .size(artworkSize.dp)
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.primary)
                    ),
                    RoundedCornerShape(28.dp)
                )
        )
    }
}

@Composable
private fun ExternalArtwork(artwork: Bitmap?, artworkSize: Int = 130) {
    if (artwork != null) {
        Image(
            bitmap = artwork.asImageBitmap(),
            contentDescription = "External album artwork",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(artworkSize.dp)
        )
    } else {
        Box(
            Modifier
                .size(artworkSize.dp)
                .background(Brush.linearGradient(listOf(Color(0xFF315CF4), Color(0xFF7A44E8))), RoundedCornerShape(28.dp))
        )
    }
}

@Composable
private fun PatternLock(state: LauncherState) {
    if (state.config.lockMode == LockMode.PATTERN) {
        PatternGridLock(state)
        return
    }
    if (state.config.lockMode == LockMode.WHEEL) {
        WheelGestureLock(state)
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (state.config.lockMode) {
                LockMode.PASSCODE -> "Enter Passcode"
                LockMode.PATTERN -> "Enter Pattern"
                LockMode.WHEEL -> "Turn: Right, Right, Left, Right"
                LockMode.OFF -> "Unlocked"
            },
            color = Color.Gray
        )
        Spacer(Modifier.height(30.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { index ->
                Box(
                    Modifier
                        .size(18.dp)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        .background(
                            if (index < state.lockInput.size) MaterialTheme.colorScheme.primary else Color.Transparent,
                            CircleShape
                        )
                )
            }
        }
        Spacer(Modifier.height(42.dp))
        Text(
            when (state.config.lockMode) {
                LockMode.PASSCODE -> state.selectedIndex.toString()
                LockMode.PATTERN -> (state.selectedIndex + 1).toString()
                LockMode.WHEEL -> "◌"
                LockMode.OFF -> "✓"
            },
            fontSize = 72.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(12.dp))
        Text("Turn wheel • Press center", color = Color.Gray)
    }
}

@Composable
private fun PatternGridLock(state: LauncherState) {
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1_000)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 58.sp, fontWeight = FontWeight.Light, color = displayTextColor(state.config.displayTheme))
        Text(now.format(DateTimeFormatter.ofPattern("yyyy.MM.dd  EEE")), color = Color.Gray)
        Spacer(Modifier.height(24.dp))
        Text(
            "Draw pattern below to unlock",
            fontSize = 16.sp,
            color = displayTextColor(state.config.displayTheme)
        )
    }
}

@Composable
private fun WheelGestureLock(state: LauncherState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Wheel Gesture", color = Color.Gray)
        Spacer(Modifier.height(42.dp))
        Text("↻  ↻  ↺  ↻", fontSize = 42.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(28.dp))
        Text("${state.lockInput.size} / 4", fontSize = 24.sp)
        Text("Turn the wheel", color = Color.Gray)
    }
}

@Composable
private fun CredentialSetup(state: LauncherState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (state.config.lockMode) {
                LockMode.PASSCODE -> "Set New Passcode"
                LockMode.PATTERN -> "Draw New Pattern"
                LockMode.WHEEL -> "Record Wheel Gesture"
                LockMode.OFF -> "Lock Is Off"
            },
            fontSize = 24.sp,
            color = displayTextColor(state.config.displayTheme)
        )
        if (state.config.lockMode != LockMode.PATTERN) {
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(4) { index ->
                    Box(
                        Modifier
                            .size(18.dp)
                            .border(2.dp, accentColor(state.config.accentTheme), CircleShape)
                            .background(if (index < state.lockInput.size) accentColor(state.config.accentTheme) else Color.Transparent, CircleShape)
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            when (state.config.lockMode) {
                LockMode.PASSCODE -> "Turn wheel · Press center"
                LockMode.PATTERN -> "Draw directly on the pad below"
                LockMode.WHEEL -> "Turn four steps in any direction"
                LockMode.OFF -> "Choose a lock method first"
            },
            color = Color.Gray
        )
    }
}

@Composable
private fun ClickWheel(
    config: PodConfig,
    state: LauncherState,
    onPatternSubmit: (List<Int>) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onMode: () -> Unit,
    nowPlayingControls: Boolean
) {
    var accumulatedAngle by remember { mutableFloatStateOf(0f) }
    var previousAngle by remember { mutableFloatStateOf(Float.NaN) }
    val rotation by animateFloatAsState(accumulatedAngle, label = "wheelRotation")

    val patternMode = state.screen in listOf(Screen.LOCK, Screen.SET_CREDENTIAL, Screen.ONBOARDING_CREDENTIAL) && state.config.lockMode == LockMode.PATTERN
    val wheelContent = wheelContentColor(config.wheelTheme)
    val baseWheelModifier = Modifier
            .size(config.wheelSize.dp)
            .background(wheelColor(config.wheelTheme), CircleShape)
            .border(1.dp, wheelContent.copy(alpha = .18f), CircleShape)
    val interactiveWheelModifier = if (patternMode) baseWheelModifier else baseWheelModifier.pointerInput(Unit) {
                val center = Offset(size.width / 2f, size.height / 2f)
                detectDragGestures(
                    onDragStart = { point ->
                        previousAngle = angle(point, center)
                    },
                    onDragEnd = { previousAngle = Float.NaN }
                ) { change, _ ->
                    val next = angle(change.position, center)
                    if (!previousAngle.isNaN()) {
                        var delta = next - previousAngle
                        if (delta > 180f) delta -= 360f
                        if (delta < -180f) delta += 360f
                        accumulatedAngle += delta
                        if (accumulatedAngle >= 24f) {
                            onNext()
                            accumulatedAngle = 0f
                        } else if (accumulatedAngle <= -24f) {
                            onPrevious()
                            accumulatedAngle = 0f
                        }
                    }
                    previousAngle = next
                    change.consume()
                }
            }

    Box(
        modifier = interactiveWheelModifier,
        contentAlignment = Alignment.Center
    ) {
        if (!patternMode) {
        Text(
            "MENU",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp)
                .clickable(onClick = onBack),
            color = wheelContent,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "◀◀",
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 22.dp)
                .clickable(onClick = onPrevious),
            color = wheelContent.copy(alpha = .62f)
        )
        Text(
            "▶▶",
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 22.dp)
                .clickable(onClick = onNext),
            color = wheelContent.copy(alpha = .62f)
        )
        Text(
            "▶ Ⅱ",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
                .clickable(onClick = onPlayPause),
            color = wheelContent.copy(alpha = .62f)
        )
        }
        if (nowPlayingControls) {
            Text(
                "SHUF",
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = (-70).dp, y = (-58).dp)
                    .clickable(onClick = onShuffle)
                    .padding(8.dp),
                color = if (state.shuffleEnabled) accentColor(config.accentTheme) else wheelContent.copy(alpha = .62f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "LOOP\n${state.repeatMode.name}",
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = 70.dp, y = (-58).dp)
                    .clickable(onClick = onRepeat)
                    .padding(8.dp),
                color = if (state.repeatMode != RepeatMode.OFF) accentColor(config.accentTheme) else wheelContent.copy(alpha = .62f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 11.sp
            )
        }
        if (state.screen == Screen.HOME && !nowPlayingControls) {
            Text(
                "MODE",
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = 70.dp, y = (-58).dp)
                    .clickable(onClick = onMode)
                    .padding(8.dp),
                color = wheelContent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (patternMode) {
            TouchPatternPad(state = state, onSubmit = onPatternSubmit)
        } else {
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .background(Color(0xFF17171A), CircleShape)
                    .border(1.dp, Color(0xFF313136), CircleShape)
                    .clickable(onClick = onSelect)
            )
        }
    }
}

@Composable
private fun TouchPatternPad(state: LauncherState, onSubmit: (List<Int>) -> Unit) {
    val selected = remember { mutableStateListOf<Int>() }
    val padColor = accentColor(state.config.accentTheme)
    Canvas(
        modifier = Modifier
            .size(210.dp)
            .background(Color(0xFF17171A), CircleShape)
            .border(1.dp, Color(0xFF313136), CircleShape)
            .pointerInput(Unit) {
                fun pointAt(position: Offset): Int? {
                    val gap = size.width / 4f
                    var best: Int? = null
                    var bestDistance = Float.MAX_VALUE
                    repeat(9) { index ->
                        val row = index / 3
                        val column = index % 3
                        val center = Offset(gap * (column + 1), gap * (row + 1))
                        val distance = (position - center).getDistance()
                        if (distance < bestDistance && distance < gap * .55f) {
                            best = index
                            bestDistance = distance
                        }
                    }
                    return best
                }
                detectDragGestures(
                    onDragStart = { position -> pointAt(position)?.let { if (it !in selected) selected.add(it) } },
                    onDragEnd = { val result = selected.toList(); selected.clear(); onSubmit(result) },
                    onDragCancel = { selected.clear() }
                ) { change, _ ->
                    pointAt(change.position)?.let { if (it !in selected) selected.add(it) }
                    change.consume()
                }
            }
    ) {
        val gap = size.width / 4f
        selected.zipWithNext().forEach { (from, to) ->
            val a = Offset(gap * (from % 3 + 1), gap * (from / 3 + 1))
            val b = Offset(gap * (to % 3 + 1), gap * (to / 3 + 1))
            drawLine(padColor, a, b, strokeWidth = 10f)
        }
        repeat(9) { index ->
            val center = Offset(gap * (index % 3 + 1), gap * (index / 3 + 1))
            drawCircle(if (index in selected) padColor else Color(0xFFB8B8BC), radius = if (index in selected) 16f else 11f, center = center)
            if (index in selected) {
                drawCircle(Color.White, radius = 5f, center = center)
            }
        }
    }
}

private fun angle(point: Offset, center: Offset): Float =
    Math.toDegrees(
        atan2(
            (point.y - center.y).toDouble(),
            (point.x - center.x).toDouble()
        )
    ).toFloat()

private fun Screen.title(config: PodConfig): String {
    fun localized(japanese: String, english: String) = if (config.language == 0) japanese else english
    return when (this) {
    Screen.ONBOARDING -> "Welcome"
    Screen.ONBOARDING_HOME -> localized("ホームアプリ", "Home App")
    Screen.ONBOARDING_AUDIO -> localized("音楽へのアクセス", "Music Access")
    Screen.ONBOARDING_THEME -> localized("テーマを選択", "Choose a Look")
    Screen.ONBOARDING_LOCK -> localized("ロックを選択", "Choose a Lock")
    Screen.ONBOARDING_CREDENTIAL -> localized("解除方法を登録", "Create Unlock")
    Screen.ONBOARDING_APPS -> localized("ホームアプリ", "Home Apps")
    Screen.ONBOARDING_FINISH -> localized("準備完了", "Ready")
    Screen.HOME -> "LauncherPod"
    Screen.MUSIC -> localized("音楽", "Music")
    Screen.LOCAL_MUSIC -> localized("端末内の音楽", "On-Device Music")
    Screen.PLAYLISTS -> localized("プレイリスト", "Playlists")
    Screen.PLAYLIST_BUILDER -> "On-The-Go"
    Screen.ARTISTS -> localized("アーティスト", "Artists")
    Screen.ALBUMS -> localized("アルバム", "Albums")
    Screen.SONGS -> localized("曲", "Songs")
    Screen.NOW_PLAYING -> "Now Playing"
    Screen.STREAMING -> "Streaming"
    Screen.MORE -> localized("その他", "More")
    Screen.APPS -> localized("アプリ", "Apps")
    Screen.CUSTOMIZE -> localized("ホームを編集", "Customize Home")
    Screen.SETTINGS -> localized("設定", "Settings")
    Screen.APPEARANCE -> localized("外観", "Appearance")
    Screen.LOCK_SETTINGS -> localized("ロック画面", "Lock Screen")
    Screen.SET_CREDENTIAL -> localized("解除方法を登録", "Unlock Setup")
    Screen.LOCK -> localized("ロック中", "Locked")
    }
}

private fun bodyColor(theme: Int): Color = when (theme) {
    1 -> Color(0xFFB8B8BC)
    2 -> Color(0xFFF2F2F0)
    3 -> Color(0xFFF1EFE7)
    4 -> Color(0xFF0A1930)
    else -> Color(0xFF050506)
}

private fun wheelColor(theme: Int): Color = when (theme) {
    1 -> Color(0xFFF4F4F1)
    2 -> Color(0xFF151517)
    3 -> Color(0xFFFF6A18)
    4 -> Color(0xFFF6F4EC)
    5 -> Color(0xFF315CF4)
    6 -> Color(0xFF7A44E8)
    7 -> Color(0xFF198754)
    else -> Color(0xFFFF2D20)
}

private fun wheelContentColor(theme: Int): Color = when (theme) {
    1, 3, 4 -> Color(0xFF17171A)
    else -> Color.White
}

private fun displayColor(theme: Int): Color = when (theme) {
    1 -> Color(0xFF071B33)
    2 -> Color(0xFF25113D)
    3 -> Color(0xFF082A22)
    4 -> Color(0xFFF6F4EC)
    5 -> Color(0xFFF7F7F7)
    else -> Color(0xFF17171A)
}

private fun displayTextColor(theme: Int): Color = if (theme >= 4) Color(0xFF151517) else Color(0xFFF7F7F7)

private fun accentColor(theme: Int): Color = when (theme) {
    1 -> Color(0xFF315CF4)
    2 -> Color(0xFFFF6A18)
    3 -> Color(0xFF7A44E8)
    4 -> Color(0xFF198754)
    5 -> Color(0xFFFF4F9A)
    6 -> Color(0xFFFFC928)
    7 -> Color(0xFF17B8C4)
    else -> Color(0xFFFF2D20)
}

private fun accentTextColor(theme: Int): Color = if (theme in listOf(2, 6, 7)) Color.Black else Color.White
