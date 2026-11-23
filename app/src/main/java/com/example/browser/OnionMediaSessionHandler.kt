package com.example.browser

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MediaPlaybackSnapshot(
    val isPlaying: Boolean = false,
    val hasActiveSession: Boolean = false,
    val title: String = "",
    val artist: String = "OnionShield Media",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * Manages the Android [MediaSession], [PlaybackState], [MediaMetadata], [AudioManager] audio focus,
 * and `ACTION_AUDIO_BECOMING_NOISY` headset unplug handling so WebView audio/video streams stay
 * controllable and uninterrupted in the background and while the screen is turned off.
 */
class OnionMediaSessionHandler(
    private val context: Context,
    private val onPlayCommand: () -> Unit,
    private val onPauseCommand: () -> Unit,
    private val onStopCommand: () -> Unit,
    private val onSeekCommand: (Long) -> Unit,
    private val onSkipNextCommand: () -> Unit,
    private val onSkipPrevCommand: () -> Unit,
    private val onStateChanged: (MediaPlaybackSnapshot) -> Unit = {},
    private val manageAudioFocus: Boolean = false,
) {
    private val _snapshot = MutableStateFlow(MediaPlaybackSnapshot())
    val snapshot: StateFlow<MediaPlaybackSnapshot> = _snapshot.asStateFlow()

    private var mediaSession: MediaSession? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus: Boolean = false
    private var resumeOnFocusGain: Boolean = false
    private var noisyReceiverRegistered: Boolean = false
    internal var platformAudioFocusRequests: Int = 0
        private set

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                handlePauseFromSession(abandonFocus = false)
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        handleAudioFocusChange(focusChange)
    }

    val sessionToken: MediaSession.Token?
        get() = ensureSession()?.sessionToken

    val isSessionActive: Boolean
        get() = runCatching { mediaSession?.isActive == true }.getOrDefault(_snapshot.value.hasActiveSession)

    fun ensureSession(): MediaSession? {
        mediaSession?.let { return it }
        return runCatching {
            MediaSession(context.applicationContext, SESSION_TAG).apply {
                val launchIntent = Intent(context.applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val sessionPendingIntent = PendingIntent.getActivity(
                    context.applicationContext,
                    REQUEST_CODE_SESSION_UI,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                setSessionActivity(sessionPendingIntent)
                setCallback(sessionCallback)
                setPlaybackState(buildPlaybackState(PlaybackState.STATE_NONE, 0L, 0f))
            }.also { mediaSession = it }
        }.onFailure { err ->
            Log.w(TAG, "Unable to create platform MediaSession", err)
        }.getOrNull()
    }

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            handlePlayFromSession()
        }

        override fun onPause() {
            handlePauseFromSession(abandonFocus = false)
        }

        override fun onStop() {
            handleStopFromSession()
        }

        override fun onSeekTo(pos: Long) {
            val clamped = pos.coerceAtLeast(0L)
            val current = _snapshot.value
            val updated = current.copy(positionMs = clamped)
            _snapshot.value = updated
            applyMetadataAndState(updated)
            onSeekCommand(clamped)
            onStateChanged(updated)
        }

        override fun onSkipToNext() {
            onSkipNextCommand()
        }

        override fun onSkipToPrevious() {
            onSkipPrevCommand()
        }
    }

    fun updatePlayback(
        title: String,
        artist: String = "OnionShield Media",
        isPlaying: Boolean,
        positionMs: Long = _snapshot.value.positionMs,
        durationMs: Long = _snapshot.value.durationMs,
    ) {
        val cleanTitle = title.trim().ifBlank { "Background Media Audio" }.take(100)
        val cleanArtist = artist.trim().ifBlank { "OnionShield Media" }.take(80)
        val next = MediaPlaybackSnapshot(
            isPlaying = isPlaying,
            hasActiveSession = true,
            title = cleanTitle,
            artist = cleanArtist,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
        )
        _snapshot.value = next

        val session = ensureSession()
        if (isPlaying) {
            requestAudioFocusInternal()
            registerNoisyReceiver()
            runCatching { session?.isActive = true }
        } else {
            unregisterNoisyReceiver()
            runCatching { session?.isActive = true }
        }
        applyMetadataAndState(next)
        onStateChanged(next)
    }

    fun handlePlayFromSession() {
        val granted = requestAudioFocusInternal()
        if (!granted) {
            Log.w(TAG, "Audio focus not granted when starting MediaSession playback")
        }
        resumeOnFocusGain = false
        val current = _snapshot.value
        val next = current.copy(
            isPlaying = true,
            hasActiveSession = true,
            title = current.title.ifBlank { "Background Media Audio" },
        )
        _snapshot.value = next
        ensureSession()?.let { runCatching { it.isActive = true } }
        registerNoisyReceiver()
        applyMetadataAndState(next)
        onPlayCommand()
        onStateChanged(next)
    }

    fun handlePauseFromSession(abandonFocus: Boolean = false) {
        unregisterNoisyReceiver()
        if (abandonFocus) {
            abandonAudioFocusInternal()
        }
        val current = _snapshot.value
        val next = current.copy(
            isPlaying = false,
            hasActiveSession = current.hasActiveSession || current.title.isNotBlank(),
        )
        _snapshot.value = next
        applyMetadataAndState(next)
        onPauseCommand()
        onStateChanged(next)
    }

    fun handleStopFromSession() {
        resumeOnFocusGain = false
        unregisterNoisyReceiver()
        abandonAudioFocusInternal()
        val next = _snapshot.value.copy(
            isPlaying = false,
            hasActiveSession = false,
        )
        _snapshot.value = next
        mediaSession?.let { session ->
            runCatching {
                session.setPlaybackState(buildPlaybackState(PlaybackState.STATE_STOPPED, next.positionMs, 0f))
                session.isActive = false
            }
        }
        onStopCommand()
        onStateChanged(next)
    }

    internal fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                handlePauseFromSession(abandonFocus = true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (_snapshot.value.isPlaying) {
                    resumeOnFocusGain = true
                    handlePauseFromSession(abandonFocus = false)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    handlePlayFromSession()
                }
            }
        }
    }

    fun release() {
        unregisterNoisyReceiver()
        abandonAudioFocusInternal()
        resumeOnFocusGain = false
        runCatching {
            mediaSession?.isActive = false
            mediaSession?.setCallback(null)
            mediaSession?.release()
        }
        mediaSession = null
        _snapshot.value = MediaPlaybackSnapshot()
    }

    private fun applyMetadataAndState(snapshot: MediaPlaybackSnapshot) {
        val session = ensureSession() ?: return
        runCatching {
            val metadataBuilder = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, snapshot.title.ifBlank { "Background Media Audio" })
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, snapshot.title.ifBlank { "Background Media Audio" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, snapshot.artist)
            if (snapshot.durationMs > 0L) {
                metadataBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, snapshot.durationMs)
            }
            session.setMetadata(metadataBuilder.build())

            val stateCode = when {
                snapshot.isPlaying -> PlaybackState.STATE_PLAYING
                snapshot.hasActiveSession -> PlaybackState.STATE_PAUSED
                else -> PlaybackState.STATE_STOPPED
            }
            val speed = if (snapshot.isPlaying) 1.0f else 0.0f
            session.setPlaybackState(buildPlaybackState(stateCode, snapshot.positionMs, speed))
        }
    }

    private fun buildPlaybackState(stateCode: Int, positionMs: Long, speed: Float): PlaybackState {
        return PlaybackState.Builder()
            .setActions(SUPPORTED_MEDIA_ACTIONS)
            .setState(stateCode, positionMs.coerceAtLeast(0L), speed)
            .build()
    }

    private fun requestAudioFocusInternal(): Boolean {
        if (!manageAudioFocus) {
            hasAudioFocus = true
            return true
        }
        if (hasAudioFocus) return true
        return runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return@runCatching true
            platformAudioFocusRequests++
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusListener)
                    .build()
                    .also { audioFocusRequest = it }
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                )
            }
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ||
                result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED
            hasAudioFocus
        }.getOrDefault(false)
    }

    private fun abandonAudioFocusInternal() {
        hasAudioFocus = false
        if (!manageAudioFocus) return
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusListener)
            }
        }
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        runCatching {
            context.applicationContext.unregisterReceiver(becomingNoisyReceiver)
        }
        noisyReceiverRegistered = false
    }

    companion object {
        private const val TAG = "OnionMediaSession"
        private const val SESSION_TAG = "OnionShieldMediaSession"
        private const val REQUEST_CODE_SESSION_UI = 4201
        const val SUPPORTED_MEDIA_ACTIONS: Long =
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
    }
}
