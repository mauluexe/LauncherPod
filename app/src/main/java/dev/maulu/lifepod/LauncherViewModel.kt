package dev.maulu.launcherpod

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Process
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.util.Locale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Screen {
    ONBOARDING, ONBOARDING_HOME, ONBOARDING_AUDIO, ONBOARDING_THEME, ONBOARDING_LOCK,
    ONBOARDING_CREDENTIAL, ONBOARDING_APPS, ONBOARDING_FINISH,
    HOME, MUSIC, LOCAL_MUSIC, PLAYLISTS, PLAYLIST_BUILDER, ARTISTS, ALBUMS, SONGS, NOW_PLAYING, STREAMING,
    MORE, APPS, CUSTOMIZE, SETTINGS, APPEARANCE, LOCK_SETTINGS, SET_CREDENTIAL, LOCK
}

enum class LockMode(val label: String) {
    PASSCODE("4-Digit Passcode"), PATTERN("iPod Pattern"), WHEEL("Wheel Gesture"), OFF("Off")
}

enum class RepeatMode { OFF, ALL, ONE }
enum class PlaybackMode { POD, EXT }

data class PodConfig(
    val language: Int = 0,
    val preset: Int = 0,
    val bodyTheme: Int = 3,
    val displayTheme: Int = 4,
    val wheelTheme: Int = 4,
    val accentTheme: Int = 1,
    val wheelSize: Int = 250,
    val textScale: Float = 1f,
    val showCornerClock: Boolean = true,
    val lockMode: LockMode = LockMode.PATTERN
)

data class LaunchableApp(val label: String, val componentName: ComponentName)

data class MusicTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val playlist: String,
    val uri: Uri
)

data class LauncherState(
    val screen: Screen = Screen.HOME,
    val selectedIndex: Int = 0,
    val apps: List<LaunchableApp> = emptyList(),
    val lockInput: List<Int> = emptyList(),
    val pinnedPackages: List<String> = emptyList(),
    val tracks: List<MusicTrack> = emptyList(),
    val currentTrackIndex: Int = -1,
    val isPlaying: Boolean = false,
    val playbackPositionMs: Int = 0,
    val playbackDurationMs: Int = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val playbackMode: PlaybackMode = PlaybackMode.POD,
    val playbackQueueIds: List<Long> = emptyList(),
    val onTheGoIds: Set<Long> = emptySet(),
    val songFilter: String? = null,
    val songFilterType: Screen? = null,
    val config: PodConfig = PodConfig()
)

class LauncherViewModel(private val context: Context) : ViewModel() {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val preferences = context.getSharedPreferences("launcherpod", Context.MODE_PRIVATE)
    private var player: MediaPlayer? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                player?.takeIf { it.isPlaying }?.pause()
                _state.value = _state.value.copy(isPlaying = false)
            }
        }
        .build()
    private var savedPasscode = loadSequence("passcode", listOf(1, 2, 3, 4))
    private var savedPattern = loadSequence("pattern", listOf(0, 1, 2, 5))
    private var savedWheelGesture = loadSequence("wheel_gesture", listOf(1, 1, -1, 1))
    private val playbackHandler = Handler(Looper.getMainLooper())
    private val playbackTicker = object : Runnable {
        override fun run() {
            player?.let { activePlayer ->
                runCatching {
                    _state.value = _state.value.copy(
                        playbackPositionMs = activePlayer.currentPosition,
                        playbackDurationMs = activePlayer.duration.coerceAtLeast(0),
                        isPlaying = activePlayer.isPlaying
                    )
                }
            }
            playbackHandler.postDelayed(this, 500)
        }
    }
    private val _state = MutableStateFlow(
        LauncherState(
            screen = when {
                preferences.getInt("setup_version", 0) < 2 -> Screen.ONBOARDING
                preferences.getString("lock_mode", LockMode.PATTERN.name) != LockMode.PATTERN.name -> Screen.SET_CREDENTIAL
                else -> Screen.HOME
            },
            apps = loadApps(),
            pinnedPackages = loadPinnedPackages(),
            config = loadConfig()
            ,playbackMode = runCatching { PlaybackMode.valueOf(preferences.getString("playback_mode", PlaybackMode.POD.name)!!) }.getOrDefault(PlaybackMode.POD)
            ,onTheGoIds = loadOnTheGoIds()
        )
    )
    val state: StateFlow<LauncherState> = _state.asStateFlow()

    fun move(delta: Int) {
        val state = _state.value
        if (state.screen == Screen.LOCK && state.config.lockMode == LockMode.WHEEL) {
            val next = (state.lockInput + if (delta > 0) 1 else -1).takeLast(4)
            if (next == savedWheelGesture) unlock() else _state.value = state.copy(lockInput = next)
            return
        }
        if (state.screen in listOf(Screen.SET_CREDENTIAL, Screen.ONBOARDING_CREDENTIAL) && state.config.lockMode == LockMode.WHEEL) {
            val next = state.lockInput + if (delta > 0) 1 else -1
            if (next.size >= 4) {
                savedWheelGesture = next.take(4)
                saveSequence("wheel_gesture", savedWheelGesture)
                finishCredentialSetup()
            } else _state.value = state.copy(lockInput = next)
            return
        }
        val count = currentItems().size.coerceAtLeast(1)
        _state.value = state.copy(selectedIndex = (state.selectedIndex + delta + count) % count)
    }

    fun previous() = if (_state.value.screen == Screen.NOW_PLAYING) mediaPrevious() else move(-1)
    fun next() = if (_state.value.screen == Screen.NOW_PLAYING) mediaNext() else move(1)

    fun mediaPrevious() { if (externalIsActive()) ExternalPlaybackBridge.previous() else skip(-1) }
    fun mediaNext() { if (externalIsActive()) ExternalPlaybackBridge.next() else skip(1) }
    fun mediaPlayPause() { if (externalIsActive()) ExternalPlaybackBridge.playPause() else playPause() }
    private fun externalIsActive() =
        _state.value.playbackMode == PlaybackMode.EXT && ExternalPlaybackBridge.state.value != null

    fun selectIndex(index: Int) {
        if (index !in currentItems().indices || _state.value.screen == Screen.LOCK) return
        _state.value = _state.value.copy(selectedIndex = index)
        select()
    }

    fun submitPattern(points: List<Int>) {
        if (_state.value.config.lockMode != LockMode.PATTERN) return
        when (_state.value.screen) {
            Screen.LOCK -> if (points == savedPattern) unlock() else _state.value = _state.value.copy(lockInput = emptyList(), selectedIndex = 0)
            Screen.SET_CREDENTIAL, Screen.ONBOARDING_CREDENTIAL -> if (points.size >= 4) {
                savedPattern = points
                saveSequence("pattern", points)
                finishCredentialSetup()
            }
            else -> Unit
        }
    }

    fun select() {
        val state = _state.value
        when (state.screen) {
            Screen.ONBOARDING -> open(Screen.ONBOARDING_HOME)
            Screen.ONBOARDING_HOME, Screen.ONBOARDING_AUDIO -> Unit
            Screen.ONBOARDING_THEME -> setOnboardingTheme(state.selectedIndex)
            Screen.ONBOARDING_LOCK -> setOnboardingLock(state.selectedIndex)
            Screen.ONBOARDING_CREDENTIAL -> if (state.config.lockMode == LockMode.PASSCODE) handlePasscodeSetup(state.selectedIndex)
            Screen.ONBOARDING_APPS -> if (state.selectedIndex == 0) open(Screen.ONBOARDING_FINISH)
                else state.apps.getOrNull(state.selectedIndex - 1)?.let(::togglePinned)
            Screen.ONBOARDING_FINISH -> completeOnboarding()
            Screen.HOME -> when (state.selectedIndex) {
                0 -> open(Screen.MUSIC)
                1 -> launchIntent("android.media.action.IMAGE_CAPTURE")
                pinnedApps().size + 2 -> open(Screen.MORE)
                else -> pinnedApps().getOrNull(state.selectedIndex - 2)?.let(::launchApp)
            }
            Screen.MUSIC -> when (state.selectedIndex) {
                0 -> open(Screen.LOCAL_MUSIC)
                1 -> open(Screen.STREAMING)
            }
            Screen.LOCAL_MUSIC -> when (state.selectedIndex) {
                0 -> open(Screen.PLAYLISTS)
                1 -> open(Screen.ARTISTS)
                2 -> open(Screen.ALBUMS)
                3 -> openSongs()
            }
            Screen.PLAYLISTS -> playlists().getOrNull(state.selectedIndex)?.let {
                if (it == "Build On-The-Go") open(Screen.PLAYLIST_BUILDER) else openSongs(Screen.PLAYLISTS, it)
            }
            Screen.ARTISTS -> openSongs(Screen.ARTISTS, artists().getOrNull(state.selectedIndex))
            Screen.ALBUMS -> openSongs(Screen.ALBUMS, albums().getOrNull(state.selectedIndex))
            Screen.SONGS -> filteredTracks().getOrNull(state.selectedIndex)?.let {
                _state.value = _state.value.copy(
                    shuffleEnabled = false,
                    playbackQueueIds = filteredTracks().map(MusicTrack::id)
                )
                playTrack(state.tracks.indexOf(it))
            }
            Screen.PLAYLIST_BUILDER -> state.tracks.getOrNull(state.selectedIndex)?.let(::toggleOnTheGo)
            Screen.STREAMING -> streamingApps().getOrNull(state.selectedIndex)?.let(::launchApp)
            Screen.MORE -> when (state.selectedIndex) {
                0 -> open(Screen.APPS)
                1 -> open(Screen.CUSTOMIZE)
                2 -> open(Screen.SETTINGS)
            }
            Screen.SETTINGS -> when (state.selectedIndex) {
                0 -> open(Screen.APPEARANCE)
                1 -> open(Screen.LOCK_SETTINGS)
                2 -> context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                3 -> context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Screen.APPEARANCE -> updateAppearance(state.selectedIndex)
            Screen.LOCK_SETTINGS -> open(Screen.SET_CREDENTIAL)
            Screen.SET_CREDENTIAL -> if (state.config.lockMode == LockMode.PASSCODE) handlePasscodeSetup(state.selectedIndex)
            Screen.APPS -> state.apps.getOrNull(state.selectedIndex)?.let(::launchApp)
            Screen.CUSTOMIZE -> state.apps.getOrNull(state.selectedIndex)?.let(::togglePinned)
            Screen.LOCK -> handleLockSelect(state.selectedIndex)
            Screen.NOW_PLAYING -> Unit
        }
    }

    fun back() {
        when (_state.value.screen) {
            Screen.ONBOARDING -> Unit
            Screen.ONBOARDING_HOME -> open(Screen.ONBOARDING)
            Screen.ONBOARDING_AUDIO -> open(Screen.ONBOARDING_HOME)
            Screen.ONBOARDING_THEME -> open(Screen.ONBOARDING_AUDIO)
            Screen.ONBOARDING_LOCK -> open(Screen.ONBOARDING_THEME)
            Screen.ONBOARDING_CREDENTIAL -> open(Screen.ONBOARDING_LOCK)
            Screen.ONBOARDING_APPS -> open(Screen.ONBOARDING_LOCK)
            Screen.ONBOARDING_FINISH -> open(Screen.ONBOARDING_APPS)
            Screen.HOME -> Unit
            Screen.LOCK -> _state.value = _state.value.copy(lockInput = emptyList(), selectedIndex = 0)
            Screen.MUSIC, Screen.MORE -> open(Screen.HOME)
            Screen.LOCAL_MUSIC, Screen.STREAMING -> open(Screen.MUSIC)
            Screen.PLAYLISTS, Screen.PLAYLIST_BUILDER, Screen.ARTISTS, Screen.ALBUMS, Screen.SONGS, Screen.NOW_PLAYING -> open(Screen.LOCAL_MUSIC)
            Screen.SETTINGS -> open(Screen.MORE)
            Screen.APPEARANCE, Screen.LOCK_SETTINGS -> open(Screen.SETTINGS)
            Screen.SET_CREDENTIAL -> open(Screen.LOCK_SETTINGS)
            Screen.APPS, Screen.CUSTOMIZE -> open(Screen.MORE)
        }
    }

    fun playPause() {
        if (_state.value.screen == Screen.LOCK || _state.value.screen == Screen.SET_CREDENTIAL || _state.value.screen.name.startsWith("ONBOARDING")) return
        if (externalIsActive()) {
            ExternalPlaybackBridge.playPause()
            if (_state.value.screen != Screen.NOW_PLAYING) open(Screen.NOW_PLAYING)
            return
        }
        val current = player
        when {
            current == null && _state.value.tracks.isNotEmpty() -> {
                _state.value = _state.value.copy(
                    shuffleEnabled = true,
                    playbackQueueIds = _state.value.tracks.map(MusicTrack::id)
                )
                playTrack(_state.value.tracks.indices.random())
            }
            _state.value.screen != Screen.NOW_PLAYING && current != null -> {
                _state.value = _state.value.copy(screen = Screen.NOW_PLAYING)
            }
            current?.isPlaying == true -> { current.pause(); _state.value = _state.value.copy(isPlaying = false) }
            current != null -> { audioManager.requestAudioFocus(audioFocusRequest); current.start(); _state.value = _state.value.copy(isPlaying = true, screen = Screen.NOW_PLAYING) }
        }
    }

    fun togglePlaybackMode() {
        val next = if (_state.value.playbackMode == PlaybackMode.POD) PlaybackMode.EXT else PlaybackMode.POD
        preferences.edit().putString("playback_mode", next.name).apply()
        _state.value = _state.value.copy(playbackMode = next)
        if (next == PlaybackMode.EXT && ExternalPlaybackBridge.state.value == null) {
            runCatching {
                context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun toggleShuffle() {
        if (_state.value.screen == Screen.NOW_PLAYING) {
            _state.value = _state.value.copy(shuffleEnabled = !_state.value.shuffleEnabled)
        }
    }

    fun toggleRepeat() {
        if (_state.value.screen == Screen.NOW_PLAYING) {
            _state.value = _state.value.copy(
                repeatMode = when (_state.value.repeatMode) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                }
            )
        }
    }

    fun refreshMusic() { _state.value = _state.value.copy(tracks = loadTracks()) }
    fun resetHome() { if (_state.value.screen != Screen.LOCK && !_state.value.screen.name.startsWith("ONBOARDING")) open(Screen.HOME) }
    fun lock() {
        if (_state.value.screen.name.startsWith("ONBOARDING")) return
        if (_state.value.config.lockMode == LockMode.OFF) open(Screen.HOME)
        else _state.value = _state.value.copy(screen = Screen.LOCK, selectedIndex = 0, lockInput = emptyList())
    }

    fun currentItems(): List<String> = when (_state.value.screen) {
        Screen.ONBOARDING -> listOf("Get Started")
        Screen.ONBOARDING_HOME -> listOf(if (_state.value.config.language == 0) "デフォルトホームに設定" else "Set as Default Home")
        Screen.ONBOARDING_AUDIO -> listOf(if (_state.value.config.language == 0) "音楽へのアクセスを許可" else "Allow Music Access")
        Screen.ONBOARDING_THEME -> listOf("Original", "Classic Black", "Silver", "Mono")
        Screen.ONBOARDING_LOCK -> listOf(label("パターン", "Pattern"))
        Screen.ONBOARDING_CREDENTIAL -> List(9) { (it + 1).toString() }
        Screen.ONBOARDING_APPS -> listOf(
            if (_state.value.config.language == 0) "完了  ·  ${_state.value.pinnedPackages.size}個を選択"
            else "Done  ·  ${_state.value.pinnedPackages.size} selected"
        ) + _state.value.apps.map {
            if (it.componentName.packageName in _state.value.pinnedPackages) "✓ ${it.label}" else "+ ${it.label}"
        }
        Screen.ONBOARDING_FINISH -> listOf(if (_state.value.config.language == 0) "セットアップ完了" else "Finish Setup")
        Screen.HOME -> listOf(label("音楽", "Music"), label("カメラ", "Camera")) + pinnedApps().map { it.label } + label("その他", "More")
        Screen.MUSIC -> listOf(label("端末内の音楽", "On-Device Music"), label("ストリーミングアプリ", "Streaming Apps"))
        Screen.LOCAL_MUSIC -> listOf(label("プレイリスト", "Playlists"), label("アーティスト", "Artists"), label("アルバム", "Albums"), label("曲", "Songs"))
        Screen.PLAYLISTS -> playlists()
        Screen.PLAYLIST_BUILDER -> _state.value.tracks.map { if (it.id in _state.value.onTheGoIds) "✓ ${it.title}" else "+ ${it.title}" }
        Screen.ARTISTS -> artists()
        Screen.ALBUMS -> albums()
        Screen.SONGS -> filteredTracks().map { it.title }
        Screen.STREAMING -> streamingApps().map { it.label }
        Screen.MORE -> listOf(label("アプリ", "Apps"), label("ホームを編集", "Customize Home"), label("設定", "Settings"))
        Screen.SETTINGS -> listOf(label("外観", "Appearance"), label("ロック画面", "Lock Screen"), label("Android設定", "Android Settings"), label("外部音楽アクセス", "External Music Access"))
        Screen.APPEARANCE -> appearanceLabels()
        Screen.LOCK_SETTINGS -> listOf(label("パターンを変更", "Change Pattern"))
        Screen.SET_CREDENTIAL -> List(9) { (it + 1).toString() }
        Screen.APPS -> _state.value.apps.map { it.label }
        Screen.CUSTOMIZE -> _state.value.apps.map { if (it.componentName.packageName in _state.value.pinnedPackages) "✓ ${it.label}" else "+ ${it.label}" }
        Screen.LOCK -> List(9) { (it + 1).toString() }
        Screen.NOW_PLAYING -> emptyList()
    }

    private fun label(japanese: String, english: String): String = if (_state.value.config.language == 0) japanese else english

    private fun completeOnboarding() {
        preferences.edit().putInt("setup_version", 2).apply()
        open(Screen.HOME)
    }

    fun completeHomeSetup() {
        if (_state.value.screen == Screen.ONBOARDING_HOME) open(Screen.ONBOARDING_AUDIO)
    }

    fun completeAudioSetup() {
        refreshMusic()
        if (_state.value.screen == Screen.ONBOARDING_AUDIO) open(Screen.ONBOARDING_THEME)
    }

    private fun setOnboardingTheme(index: Int) {
        val next = preset(index.coerceIn(0, 3)).copy(language = _state.value.config.language, lockMode = LockMode.PATTERN)
        saveConfig(next)
        _state.value = _state.value.copy(config = next, screen = Screen.ONBOARDING_CREDENTIAL, selectedIndex = 0)
    }

    private fun setOnboardingLock(index: Int) {
        val mode = LockMode.PATTERN
        val next = _state.value.config.copy(lockMode = mode)
        saveConfig(next)
        _state.value = _state.value.copy(
            config = next,
            screen = Screen.ONBOARDING_CREDENTIAL,
            selectedIndex = 0,
            lockInput = emptyList()
        )
    }

    private fun appearanceLabels(): List<String> {
        val c = _state.value.config
        return listOf(
            "Preset  ·  ${if (c.preset < 0) "Custom" else listOf("Original", "Classic Black", "Silver", "Mono")[c.preset]}",
            "Body Color  ·  ${listOf("Black", "Silver", "White", "Cream", "Navy")[c.bodyTheme]}",
            "Display Color  ·  ${listOf("Black", "Navy", "Purple", "Forest", "Cream", "White")[c.displayTheme]}",
            "Wheel Color  ·  ${listOf("Red", "White", "Black", "Orange", "Cream", "Blue", "Purple", "Green")[c.wheelTheme]}",
            "Accent Color  ·  ${listOf("Red", "Blue", "Orange", "Purple", "Green", "Pink", "Yellow", "Cyan")[c.accentTheme]}",
            "Wheel Size  ·  ${c.wheelSize}",
            "Text Size  ·  ${listOf("Small", "Medium", "Large")[when { c.textScale < 1f -> 0; c.textScale > 1f -> 2; else -> 1 }]}",
            "Corner Clock  ·  ${if (c.showCornerClock) "On" else "Off"}"
        )
    }

    private fun updateAppearance(item: Int) {
        val c = _state.value.config
        val next = when (item) {
            0 -> preset(if (c.preset < 0) 0 else (c.preset + 1) % 4)
            1 -> c.copy(preset = -1, bodyTheme = (c.bodyTheme + 1) % 5)
            2 -> c.copy(preset = -1, displayTheme = (c.displayTheme + 1) % 6)
            3 -> c.copy(preset = -1, wheelTheme = (c.wheelTheme + 1) % 8)
            4 -> c.copy(preset = -1, accentTheme = (c.accentTheme + 1) % 8)
            5 -> c.copy(preset = -1, wheelSize = if (c.wheelSize >= 290) 230 else c.wheelSize + 20)
            6 -> c.copy(preset = -1, textScale = when (c.textScale) { .9f -> 1f; 1f -> 1.15f; else -> .9f })
            else -> c.copy(preset = -1, showCornerClock = !c.showCornerClock)
        }
        saveConfig(next); _state.value = _state.value.copy(config = next)
    }

    private fun preset(index: Int): PodConfig = when (index) {
        0 -> _state.value.config.copy(preset = 0, bodyTheme = 3, displayTheme = 4, wheelTheme = 4, accentTheme = 1, wheelSize = 250)
        2 -> _state.value.config.copy(preset = 2, bodyTheme = 1, displayTheme = 0, wheelTheme = 1, accentTheme = 1, wheelSize = 270)
        3 -> _state.value.config.copy(preset = 3, bodyTheme = 2, displayTheme = 0, wheelTheme = 2, accentTheme = 2, wheelSize = 270)
        else -> _state.value.config.copy(preset = 1, bodyTheme = 0, displayTheme = 0, wheelTheme = 0, accentTheme = 0, wheelSize = 270)
    }

    private fun updateLockMode(index: Int) {
        val next = _state.value.config.copy(lockMode = LockMode.entries[index])
        saveConfig(next); _state.value = _state.value.copy(config = next)
    }

    private fun handleLockSelect(value: Int) {
        when (_state.value.config.lockMode) {
            LockMode.PASSCODE -> {
                val next = _state.value.lockInput + value
                when { next.size < 4 -> _state.value = _state.value.copy(lockInput = next)
                    next == savedPasscode -> unlock()
                    else -> _state.value = _state.value.copy(lockInput = emptyList(), selectedIndex = 0) }
            }
            LockMode.PATTERN -> {
                if (value in _state.value.lockInput) return
                val next = _state.value.lockInput + value
                when { next.size < 4 -> _state.value = _state.value.copy(lockInput = next)
                    next == savedPattern -> unlock()
                    else -> _state.value = _state.value.copy(lockInput = emptyList(), selectedIndex = 0) }
            }
            LockMode.WHEEL -> _state.value = _state.value.copy(lockInput = emptyList())
            LockMode.OFF -> unlock()
        }
    }

    private fun handlePasscodeSetup(digit: Int) {
        val next = _state.value.lockInput + digit
        if (next.size >= 4) {
            savedPasscode = next.take(4)
            saveSequence("passcode", savedPasscode)
            finishCredentialSetup()
        } else _state.value = _state.value.copy(lockInput = next)
    }

    private fun finishCredentialSetup() {
        val patternConfig = _state.value.config.copy(lockMode = LockMode.PATTERN)
        saveConfig(patternConfig)
        _state.value = _state.value.copy(config = patternConfig)
        open(if (_state.value.screen == Screen.ONBOARDING_CREDENTIAL) Screen.ONBOARDING_APPS else Screen.LOCK_SETTINGS)
    }

    private fun unlock() { _state.value = _state.value.copy(screen = Screen.HOME, lockInput = emptyList(), selectedIndex = 0) }
    private fun open(screen: Screen) { _state.value = _state.value.copy(screen = screen, selectedIndex = 0) }
    private fun openSongs(type: Screen? = null, value: String? = null) { _state.value = _state.value.copy(screen = Screen.SONGS, selectedIndex = 0, songFilterType = type, songFilter = value) }
    private fun playlists() = listOf("Build On-The-Go") + (if (_state.value.onTheGoIds.isNotEmpty()) listOf("On-The-Go") else emptyList()) + _state.value.tracks.map { it.playlist }.distinct().sorted()
    private fun artists() = _state.value.tracks.map { it.artist }.distinct().sorted()
    private fun albums() = _state.value.tracks.map { it.album }.distinct().sorted()
    private fun filteredTracks() = _state.value.tracks.filter { track -> when (_state.value.songFilterType) { Screen.PLAYLISTS -> if (_state.value.songFilter == "On-The-Go") track.id in _state.value.onTheGoIds else track.playlist == _state.value.songFilter; Screen.ARTISTS -> track.artist == _state.value.songFilter; Screen.ALBUMS -> track.album == _state.value.songFilter; else -> true } }

    private fun playTrack(index: Int) {
        val track = _state.value.tracks.getOrNull(index) ?: return
        context.startForegroundService(Intent(context, PlaybackService::class.java))
        audioManager.requestAudioFocus(audioFocusRequest)
        if (ExternalPlaybackBridge.state.value?.isPlaying == true) ExternalPlaybackBridge.playPause()
        player?.release()
        player = MediaPlayer.create(context, track.uri)?.apply {
            setOnCompletionListener { completed ->
                if (_state.value.repeatMode == RepeatMode.ONE) {
                    completed.seekTo(0)
                    completed.start()
                } else advanceQueue(1)
            }
            start()
        }
        _state.value = _state.value.copy(
            screen = Screen.NOW_PLAYING,
            currentTrackIndex = index,
            selectedIndex = 0,
            isPlaying = player != null,
            playbackPositionMs = 0,
            playbackDurationMs = player?.duration?.coerceAtLeast(0) ?: 0
        )
        playbackHandler.removeCallbacks(playbackTicker)
        playbackHandler.post(playbackTicker)
    }
    private fun skip(delta: Int) = advanceQueue(delta)
    private fun advanceQueue(delta: Int) {
        val state = _state.value
        val queue = state.playbackQueueIds.ifEmpty { state.tracks.map(MusicTrack::id) }
        if (queue.isEmpty()) return
        val currentId = state.tracks.getOrNull(state.currentTrackIndex)?.id
        val currentQueueIndex = queue.indexOf(currentId).coerceAtLeast(0)
        val nextQueueIndex = if (state.shuffleEnabled) {
            queue.indices.random()
        } else {
            val candidate = currentQueueIndex + delta
            when {
                candidate in queue.indices -> candidate
                state.repeatMode == RepeatMode.ALL -> (candidate + queue.size) % queue.size
                else -> {
                    runCatching { player?.pause() }
                    _state.value = state.copy(isPlaying = false)
                    return
                }
            }
        }
        val globalIndex = state.tracks.indexOfFirst { it.id == queue[nextQueueIndex] }
        if (globalIndex >= 0) playTrack(globalIndex)
    }
    private fun toggleOnTheGo(track: MusicTrack) { val next = if (track.id in _state.value.onTheGoIds) _state.value.onTheGoIds - track.id else _state.value.onTheGoIds + track.id; preferences.edit().putString("on_the_go", next.joinToString(",")).apply(); _state.value = _state.value.copy(onTheGoIds = next) }
    private fun loadOnTheGoIds(): Set<Long> = preferences.getString("on_the_go", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()

    private fun pinnedApps() = _state.value.pinnedPackages.mapNotNull { p -> _state.value.apps.firstOrNull { it.componentName.packageName == p } }
    private fun streamingApps() = listOf("com.spotify.music", "com.google.android.apps.youtube.music", "com.apple.android.music", "com.amazon.mp3").mapNotNull { p -> _state.value.apps.firstOrNull { it.componentName.packageName == p } }
    private fun launchApp(app: LaunchableApp) { launcherApps.startMainActivity(app.componentName, Process.myUserHandle(), null, null) }
    private fun togglePinned(app: LaunchableApp) { val p = app.componentName.packageName; val next = if (p in _state.value.pinnedPackages) _state.value.pinnedPackages - p else _state.value.pinnedPackages + p; preferences.edit().putString("pinned_packages", next.joinToString(",")).apply(); _state.value = _state.value.copy(pinnedPackages = next) }
    private fun launchIntent(action: String) { runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    private fun loadPinnedPackages() = preferences.getString("pinned_packages", "").orEmpty().split(',').filter { it.isNotBlank() }.distinct()
    private fun loadConfig() = PodConfig(
        language = if (Locale.getDefault().language == "ja") 0 else 1,
        preset = preferences.getInt("preset",0),
        bodyTheme = preferences.getInt("body",3),
        displayTheme = preferences.getInt("display",4),
        wheelTheme = preferences.getInt("wheel",4),
        accentTheme = preferences.getInt("accent",1),
        wheelSize = preferences.getInt("wheel_size",250),
        textScale = preferences.getFloat("text_scale",1f),
        showCornerClock = preferences.getBoolean("corner_clock", true),
        lockMode = LockMode.PATTERN
    )
    private fun saveConfig(c: PodConfig) { preferences.edit().putInt("preset",c.preset).putInt("body",c.bodyTheme).putInt("display",c.displayTheme).putInt("wheel",c.wheelTheme).putInt("accent",c.accentTheme).putInt("wheel_size",c.wheelSize).putFloat("text_scale",c.textScale).putBoolean("corner_clock",c.showCornerClock).putString("lock_mode",c.lockMode.name).apply() }
    private fun loadSequence(key: String, fallback: List<Int>): List<Int> = preferences.getString(key, null)?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.isNotEmpty() } ?: fallback
    private fun saveSequence(key: String, value: List<Int>) { preferences.edit().putString(key, value.joinToString(",")).apply() }
    private fun loadApps() = launcherApps.getActivityList(null, Process.myUserHandle()).filterNot { it.componentName.packageName == context.packageName }.map { LaunchableApp(it.label.toString(), it.componentName) }.sortedBy { it.label.lowercase() }
    private fun loadTracks(): List<MusicTrack> { val out = mutableListOf<MusicTrack>(); val p = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.RELATIVE_PATH); runCatching { context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,p,"${MediaStore.Audio.Media.IS_MUSIC} != 0",null,"${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c -> val id=c.getColumnIndexOrThrow(p[0]); val title=c.getColumnIndexOrThrow(p[1]); val artist=c.getColumnIndexOrThrow(p[2]); val album=c.getColumnIndexOrThrow(p[3]); val path=c.getColumnIndexOrThrow(p[4]); while(c.moveToNext()){ val n=c.getLong(id); val folder=(c.getString(path)?:"Music").trimEnd('/').substringAfterLast('/').ifBlank{"Music"}; out += MusicTrack(n,c.getString(title)?:"Unknown title",c.getString(artist)?:"Unknown artist",c.getString(album)?:"Unknown album",folder,ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,n)) } } }; return out }
    override fun onCleared() {
        playbackHandler.removeCallbacks(playbackTicker)
        if (player?.isPlaying != true) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
            player?.release()
            context.stopService(Intent(context, PlaybackService::class.java))
        }
        super.onCleared()
    }

    companion object { fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T = LauncherViewModel(context.applicationContext) as T } }
}
