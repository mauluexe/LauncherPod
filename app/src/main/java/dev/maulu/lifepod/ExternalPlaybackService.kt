package dev.maulu.launcherpod

import android.app.Notification
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ExternalPlayback(
    val title: String,
    val artist: String,
    val album: String,
    val artwork: Bitmap?,
    val positionMs: Int,
    val durationMs: Int,
    val isPlaying: Boolean,
    val packageName: String
)

object ExternalPlaybackBridge {
    private val mutable = MutableStateFlow<ExternalPlayback?>(null)
    val state = mutable.asStateFlow()
    @Volatile var controller: MediaController? = null

    fun publish(value: ExternalPlayback?, activeController: MediaController?) {
        controller = activeController
        mutable.value = value
    }

    fun playPause() {
        controller?.let { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) it.transportControls.pause() else it.transportControls.play() }
    }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
}

class ExternalPlaybackService : NotificationListenerService() {
    private lateinit var sessions: MediaSessionManager
    private val component by lazy { android.content.ComponentName(this, ExternalPlaybackService::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            publishActiveSession()
            handler.postDelayed(this, 750)
        }
    }

    override fun onListenerConnected() {
        sessions = getSystemService(MediaSessionManager::class.java)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onListenerDisconnected() {
        handler.removeCallbacks(ticker)
        ExternalPlaybackBridge.publish(null, null)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        ExternalPlaybackBridge.publish(null, null)
        super.onDestroy()
    }

    private fun publishActiveSession() {
        val controllers = runCatching { sessions.getActiveSessions(component) }.getOrDefault(emptyList())
            .filter { it.packageName != packageName && it.metadata != null }
        val controller = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull()
        val metadata = controller?.metadata
        val playback = controller?.playbackState
        if (controller == null || metadata == null || playback == null) {
            ExternalPlaybackBridge.publish(null, null)
            return
        }
        var position = playback.position
        if (playback.state == PlaybackState.STATE_PLAYING && playback.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime) * playback.playbackSpeed).toLong()
        }
        val artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
        ExternalPlaybackBridge.publish(
            ExternalPlayback(
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                artwork = artwork,
                positionMs = position.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                isPlaying = playback.state == PlaybackState.STATE_PLAYING,
                packageName = controller.packageName
            ),
            controller
        )
    }
}
