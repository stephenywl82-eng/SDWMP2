package com.sdw.music.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.sdw.music.player.OboeAudioSink
import com.sdw.music.player.widget.MusicWidgetProvider
import com.sdw.music.player.widget.MusicWidgetProvider3x2
import androidx.media3.exoplayer.audio.SonicAudioProcessor
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sdw.music.player.core.audio.helpers.VolumeGuard
import com.sdw.music.player.core.audio.helpers.VisualizerManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class MusicService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    // ??v3.36??FFT ????????? ?? ????? VisualizerManager
    private val visualizerManager by lazy {
        VisualizerManager(
            getPlayer = { mediaSession?.player },
            getFftCallback = { fftCallback },
            tag = TAG
        )
    }
    // ProcessLifecycleOwner tracks app foreground/background reliably
    // (ActivityLifecycleCallbacks can fire too late — Service starts after Activity resumes)
    // Delayed release to avoid flicker during activity transitions or brief screen-off
    private var isAppForeground = false
    private val visualizerReleaseTask = Runnable {
        if (!isAppForeground) {
            Log.d(TAG, "Delayed Visualizer release (background confirmed)")
            visualizerManager.release()
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val TAG = "MusicService"
    private var stopDelayRunnable: Runnable? = null
    private var isDestroyed = false

    // ??v4.79?????????????????????? + ??????????
    private val volumeGuard by lazy {
        VolumeGuard(this, this::pause, this::resume, this::isPlaying)
    }

    // ??v6.22??Oboe ?????:NDK MediaCodec ?????? + Oboe ??????? ExoPlayer
    var oboeDirectPlayer: OboeDirectPlayer? = null
    private var useOboeDirect: Boolean = false

    // ??v6.29??DSP EQ ??????
    private var dspEqEnabled: Boolean = false

    // [V8.x] AudioDeviceCallback: detect USB DAC hotplug -> restart Oboe Exclusive
    private val usbDacCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            val hasUsbDac = addedDevices.any { d ->
                d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
            if (!hasUsbDac) return
            if (!isOboeDirectMode() || oboeDirectPlayer?.isPlaying != true) return
            Log.i(TAG, "USB DAC detected, restarting Oboe stream for Exclusive attempt")
            val savedPos = oboeDirectPlayer?.getCurrentPositionMs() ?: 0L
            val idx = currentIndex
            handler.postDelayed({
                playSong(idx)
                if (savedPos > 1000) {
                    handler.postDelayed({
                        oboeDirectPlayer?.seekTo(savedPos)
                    }, 400)
                }
            }, 800)
        }
    }

    internal fun isOboeDirectMode(): Boolean {
        val mode = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("audio_output", "Oboe Exclusive") ?: "Oboe Exclusive"
        val loaded = OboeDirectPlayer.nativeLibLoaded
        val result = (mode == "Oboe Exclusive" || mode == "Oboe???") && loaded
        // ??v6.23???????????????,??????
        // ??V7.16????????y???,???????
        // if (oboeFailureCount >= OBOE_MAX_FAILURES) {
        //     Log.w(TAG, "Oboe mode disabled due to $oboeFailureCount consecutive failures")
        //     return false
        // }
        return result
    }

    /** ??V7.XX??????Equalizer??????????onAudioSessionIdChanged????? */
    private fun tryInitEqualizerFallback() {
        if (isOboeDirectMode()) {
            Log.d(TAG, "tryInitEqualizerFallback: Oboe mode, skip")
            return
        }
        if (EqualizerManager.isInitialized()) {
            Log.d(TAG, "tryInitEqualizerFallback: already initialized")
            return
        }
        val sessionId = exoPlayer?.audioSessionId ?: 0
        if (sessionId == 0) {
            Log.d(TAG, "tryInitEqualizerFallback: sessionId still 0, retrying in 500ms")
            handler.postDelayed({ tryInitEqualizerFallback() }, 500)
            return
        }
        Log.d(TAG, "tryInitEqualizerFallback: init Equalizer with sessionId=$sessionId")
        EqualizerManager.init(sessionId)
        EqualizerManager.restoreSettings(this)
    }

    // ??Steven ??????? player ???????????,?????????
    private var exoPlayer: ExoPlayer? = null

    /** ??V7.XX????????? audioSessionId??Equalizer???????????*/
    fun getAudioSessionId(): Int = exoPlayer?.audioSessionId ?: 0

    // ??Steven??Service ?????Playlists(ExoPlayer ??),????? SongRepository ????��?
    private var servicePlaylist: List<Song> = emptyList()

    // ??v6.23??Oboe ??????????,???? 3 ?????????
    private var oboeFailureCount = 0
    private val OBOE_MAX_FAILURES = 3

    // ???? settings SharedPreferences ????????Output Mode?��???
    private var settingsPrefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** ??v4.77??????????????��??????(?��??????? BottomSheet)*/
    fun getServicePlaylist(): List<Song> = servicePlaylist

    // FFT ??????
    private var fftCallback: ((ByteArray) -> Unit)? = null

    // [v7.122] Auto-map system standby bucket to idle_level
    private var standbyBucketReceiver: BroadcastReceiver? = null

    // ??v4.94????? Player.Listener ???????,?? reconfigureAudioOutput ????
    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            try {
                Log.e(TAG, "Player error: code=${error.errorCode} msg=${error.message} cause=${error.cause?.message}")
                // IO ?????? 2000-2999 ??��:????????/????????/?????��??
                // ??��??????ExoPlayer ??????��??,???????????????
                val errorCode = error.errorCode
                val isRecoverable = errorCode < 2000 || errorCode >= 3000
                if (isRecoverable) {
                    Log.w(TAG, "Recoverable error, pausing for retry")
                    exoPlayer?.pause()
                } else {
                    Log.w(TAG, "Fatal IO error, skipping to next")
                    exoPlayer?.seekToNext()
                }
            } catch (e: Exception) {
                Log.e(TAG, "onPlayerError: ${e.message}")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            try {
                // ??V7.50??Oboe????OboeDirectPlayer.onCompletion??????���k
                // ???ExoPlayer STATE_ENDED??��???playNext()???????playNext???
                // ??manifest?"Repeating / No Sound"??
                if (playbackState == Player.STATE_ENDED && !isOboeDirectMode()) {
                    Log.d(TAG, "Playback ended, auto-playing next song")
                    exoPlayer?.seekToNext()
                }
            } catch (e: Exception) {
                Log.e(TAG, "onPlaybackStateChanged error (service may be dying): ${e.message}")
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            try {
                // [V8.x] In Oboe mode, ExoPlayer is not the actual playback engine.
                // Ignore isPlaying=false from ExoPlayer when Oboe is still running.
                if (!isPlaying && isOboeDirectMode() && oboeDirectPlayer?.isPlaying == true) {
                    Log.d(TAG, "isPlaying changed: $isPlaying (ignored, Oboe active)")
                    return
                }
                Log.d(TAG, "isPlaying changed: $isPlaying")
                notifyPlayStateChanged(isPlaying)
            } catch (e: Exception) {
                Log.e(TAG, "onIsPlayingChanged error: ${e.message}")
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            try {
                Log.d(TAG, "Media item transition: reason=$reason, title=${mediaItem?.mediaMetadata?.title}")
                Log.d(TAG, "servicePlaylist size=${servicePlaylist.size}, source=$playlistSource")

                val songs = servicePlaylist.ifEmpty { SongRepository.getSongs() }

                // ??v5.16 ?????shuffle On??? ExoPlayer ??????????,
                // currentMediaItemIndex ??????��??,??? songs[newIndex] ?????????????
                // ???? MEDIA_ID_CUSTOM extras ????????????
                val newSong = if (mediaItem != null) {
                    val mediaId = mediaItem.mediaMetadata.extras?.getString("MEDIA_ID_CUSTOM")
                    if (mediaId != null) {
                        songs.find { it.id.toString() == mediaId }
                    } else null
                } else null

                if (newSong != null) {
                    currentSong = newSong
                    val idxInList = songs.indexOfFirst { it.id == newSong.id }
                    if (idxInList >= 0) MusicService.currentIndex = idxInList
                    Log.d(TAG, "Updated current song (by ID): ${currentSong?.title}, idx=$idxInList")
                    notifySongChanged(currentSong)
                } else {
                    // ????:?? currentMediaItemIndex(shuffle Close???��)
                    // ??Widget????????? ?: 0????????��????? currentMediaItemIndex ? null??
                    // ???? 0 ????????currentSong ??????��??????
                    val newIndex = exoPlayer?.currentMediaItemIndex
                    if (newIndex != null && newIndex in songs.indices) {
                        currentSong = songs[newIndex]
                        MusicService.currentIndex = newIndex
                        Log.d(TAG, "Updated current song (by index fallback): ${currentSong?.title}")
                        notifySongChanged(currentSong)
                    }
                }

                mediaItem?.let { item ->
                    val newMetadata = MediaMetadata.Builder()
                        .setTitle(item.mediaMetadata.title)
                        .setArtist(item.mediaMetadata.artist)
                        .setAlbumTitle(item.mediaMetadata.albumTitle)
                        .setArtworkUri(item.mediaMetadata.artworkUri)
                        .build()
                    exoPlayer?.playlistMetadata = newMetadata
                }

                updateNotification()

                if (fftCallback != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                    handler.postDelayed({
                        try { visualizerManager.setup() } catch (e: Exception) {
                            Log.e(TAG, "setupVisualizer in transition: ${e.message}")
                        }
                    }, 300)
                }
            } catch (e: Exception) {
                Log.e(TAG, "onMediaItemTransition error (service may be dying): ${e.message}")
            }
        }

        // ??v5.56??????? ID ?????????????? Equalizer
        // Oboe ??????? prepare() ? sessionId=0???????? Equ
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            if (audioSessionId == 0) {
                Log.d(TAG, "Audio session ID is 0, Equalizer not available")
                return
            }
            // ??V7.xx??Oboe ??????? DSP??????? Android Equalizer
            if (isOboeDirectMode()) {
                Log.d(TAG, "Oboe mode: skipping Android Equalizer (DSP mode active)")
                return
            }
            // ??V7.xx???? Oboe ??:?? Android Equalizer
            // ?? onAudioSessionIdChanged ???????sessionId ????????
            if (EqualizerManager.isInitialized()) {
                Log.d(TAG, "Equalizer already initialized, skip")
                return
            }
            Log.d(TAG, "Audio session ready: $audioSessionId, initializing Equalizer")
            EqualizerManager.init(audioSessionId)
            // ?????Save?????
            if (EqualizerManager.isInitialized()) {
                EqualizerManager.restoreSettings(this@MusicService)
            }
        }
    }

    /**
     * ??Steven v1.6????????????��???????
     * ???????????????? Fragment/Adapter ???????,
     * ?��????????,?????????????????
     */
    interface OnCurrentSongChangedListener {
        fun onCurrentSongChanged(song: Song?)
    }

    interface OnPlayStateChangedListener {
        fun onPlayStateChanged(isPlaying: Boolean)
    }

    /**
     * ??v5.58?????? format Back MIME type
     * ExoPlayer ??? MIME type ???????????????
     */
    private fun getMimeType(format: String): String {
        return when (format.uppercase()) {
            "FLAC" -> "audio/flac"
            "OPUS" -> "audio/ogg"    // Opus ?? Ogg ??????
            "OGG" -> "audio/ogg"
            "WAV" -> "audio/wav"
            "AAC" -> "audio/aac"
            "M4A" -> "audio/mp4"
            "MP3" -> "audio/mpeg"
            else -> "audio/*"        // ???????
        }
    }

    companion object {
        // ??V7.16???????????????
        var oboeFlowTrace: String = "��On?"
            private set

        // ??Compose??StateFlow ?? ViewModel ???
        private val _songChangedFlow = kotlinx.coroutines.flow.MutableStateFlow<Song?>(null)
        val songChangedFlow: kotlinx.coroutines.flow.StateFlow<Song?> = _songChangedFlow

        private val _themeColorFlow = kotlinx.coroutines.flow.MutableStateFlow(0)
        val themeColorFlow: kotlinx.coroutines.flow.StateFlow<Int> = _themeColorFlow

        /** ?????????��? - ??????? handler.post ??? */
        private val songChangedListeners = mutableListOf<OnCurrentSongChangedListener>()
    private val playStateChangedListeners = mutableListOf<OnPlayStateChangedListener>()

        fun addSongChangedListener(listener: OnCurrentSongChangedListener) {
            synchronized(songChangedListeners) {
                if (!songChangedListeners.contains(listener)) {
                    songChangedListeners.add(listener)
                }
            }
        }

        fun removeSongChangedListener(listener: OnCurrentSongChangedListener) {
            synchronized(songChangedListeners) {
                songChangedListeners.remove(listener)
            }
        }

        /** ?????��???????????????��?(??????????) */
        private fun notifySongChanged(song: Song?) {
            _songChangedFlow.value = song  // ??Compose?????? Flow
            // ???????Save???????? SharedPreferences???????????????
            if (song != null) {
                savePlaybackState()
                // ??????????????"Recent"
                SongRepository.recordPlayed(song.id)
            }
            synchronized(songChangedListeners) {
                songChangedListeners.forEach { listener ->
                    try { listener.onCurrentSongChanged(song) } catch (_: Exception) {}
                }
            }
            // ??Widget?????????/???��????��????
            try { MusicWidgetProvider.updateAllWidgets(instance ?: return) } catch (_: Exception) {}
            try { MusicWidgetProvider3x2.updateAllWidgets(instance ?: return) } catch (_: Exception) {}
        }

        fun addPlayStateChangedListener(listener: OnPlayStateChangedListener) {
            synchronized(playStateChangedListeners) {
                if (!playStateChangedListeners.contains(listener)) {
                    playStateChangedListeners.add(listener)
                }
            }
        }

        fun removePlayStateChangedListener(listener: OnPlayStateChangedListener) {
            synchronized(playStateChangedListeners) {
                playStateChangedListeners.remove(listener)
            }
        }

        /** ?????��?????????????��(???/????) */
        @Volatile private var stoppedByIdlePolicy = false

        private fun notifyPlayStateChanged(isPlaying: Boolean) {
            // [v7.113] update last known state for widget query (handles Oboe JNI lag)
            lastKnownPlayingState = isPlaying
            // save before notifying so listeners see consistent state
            savePlaybackState()
            synchronized(playStateChangedListeners) {
                playStateChangedListeners.forEach { listener ->
                    try { listener.onPlayStateChanged(isPlaying) } catch (_: Exception) {}
                }
            }
            // update widgets
            try { MusicWidgetProvider.updateAllWidgets(instance ?: return) } catch (_: Exception) {}
            try { MusicWidgetProvider3x2.updateAllWidgets(instance ?: return) } catch (_: Exception) {}
            // [v7.121] delay stop foreground when paused; cancel if resumed
            val inst = instance ?: return
            inst.stopDelayRunnable?.let { inst.handler.removeCallbacks(it) }
            if (!isPlaying) {
                val r = Runnable {
                    val i = instance ?: return@Runnable
                    if (!i.isPlaying()) {
                        Log.d(inst.TAG, "Idle timeout reached, stopping foreground service")
                        i.stopForeground(STOP_FOREGROUND_REMOVE)
                        i.stopSelf()
                        stoppedByIdlePolicy = true
                    }
                }
                inst.stopDelayRunnable = r
                val idleMs = when (inst.getSharedPreferences("sdw_music_prefs", MODE_PRIVATE).getString("idle_level", "Rare")) {
                    "Working Set" -> 1_800_000L  // 30 min
                    "Frequent" -> 300_000L       // 5 min
                    "Rare" -> 3_000L             // 3 sec
                    "Restricted" -> 0L            // immediate
                    else -> 3_000L
                }
                if (idleMs == 0L) {
                    Log.d(inst.TAG, "Idle level Restricted, stopping immediately")
                    inst.stopForeground(STOP_FOREGROUND_REMOVE)
                    stoppedByIdlePolicy = true
                    inst.stopSelf()
                    Log.d(inst.TAG, "stopSelf() called after Restricted idle policy")
                } else {
                    inst.handler.postDelayed(r, idleMs)
                }
            } else {
                stoppedByIdlePolicy = false
            }
        }
        const val CHANNEL_ID = "music_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CLOSE = "ACTION_CLOSE"
        const val ACTION_SHUFFLE = "com.sdw.music.player.ACTION_SHUFFLE"
        private const val PREFS_PLAYBACK = "playback_state"
        private const val KEY_SONG_ID = "last_song_id"
        private const val KEY_SONG_PATH = "last_song_path"
        private const val KEY_SONG_TITLE = "last_song_title"
        private const val KEY_SONG_ARTIST = "last_song_artist"
        private const val KEY_ALBUM_ART_URI = "last_album_art_uri"
        private const val KEY_POSITION = "last_position_ms"
        private const val KEY_WAS_PLAYING = "was_playing"

        /** Save??????????? SharedPreferences???????????????*/
        fun savePlaybackState() {
            val ctx = instance ?: return
            val song = currentSong ?: return
            val prefs = ctx.getSharedPreferences(PREFS_PLAYBACK, MODE_PRIVATE)
            val pos = try {
                if (instance?.isOboeDirectMode() == true) {
                    instance?.oboeDirectPlayer?.getCurrentPositionMs() ?: 0L
                } else {
                    instance?.exoPlayer?.currentPosition ?: 0L
                }
            } catch (_: Exception) { 0L }
            val isPlaying = try {
                when {
                    instance?.isOboeDirectMode() == true -> instance?.oboeDirectPlayer?.isPlaying ?: false
                    else -> instance?.exoPlayer?.isPlaying ?: false
                }
            } catch (_: Exception) { false }
            prefs.edit()
                .putLong(KEY_SONG_ID, song.id)
                .putString(KEY_SONG_PATH, song.path)
                .putString(KEY_SONG_TITLE, song.title)
                .putString(KEY_SONG_ARTIST, song.artist)
                .putString(KEY_ALBUM_ART_URI, song.albumArtUri.takeIf { it.isNotEmpty() } ?: "")
                .putLong(KEY_POSITION, pos)
                .putBoolean(KEY_WAS_PLAYING, isPlaying)
                .apply()
            android.util.Log.d("MusicService", "Playback state saved: id=${song.id}, pos=$pos, playing=$isPlaying")
        }
        var currentSong: Song? = null
            private set
        var currentIndex: Int = 0
            private set
        var isShuffleMode: Boolean = false
            private set

        // ??Steven??Playlists???:???? UI ??????????��?
        var playlistSource: String = "All Songs"
            private set

        // ???????
        var instance: MusicService? = null
            private set

        // ??Steven v1.5?????????? - Palette ????????,????/?��?????
        var themeColor: Int = 0
            set(value) {
                field = value
                _themeColorFlow.value = value  // ??Compose?????? Flow
            }

        // ??Steven????? Player ?? Fragment ????
        val player: Player?
            get() = instance?.exoPlayer

        // [v7.113] ��Ƶ�������
        private var audioFocusListener: AudioManager.OnAudioFocusChangeListener? = null
        private var hasAudioFocus = false

        fun requestAudioFocusIfNeeded(ctx: android.content.Context) {
            // Oboe Exclusive mode doesn't need AudioFocus — AAudio manages it independently
            if (instance?.isOboeDirectMode() == true) return
            if (hasAudioFocus) return
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (audioFocusListener == null) {
                audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            hasAudioFocus = false
                            val svc = instance
                            if (svc != null && !svc.isOboeDirectMode()) {
                                svc.pause()
                                android.util.Log.d("MusicService", "Audio focus lost, pausing (ExoPlayer mode)")
                            } else {
                                // Oboe mode: don't pause — Oboe streams bypass AudioFocus
                                // requesting Exclusive AAudio triggers spurious focus loss
                                svc?.wasPlayingBeforeFocusLoss = true
                                android.util.Log.d("MusicService", "Audio focus lost, ignoring (Oboe mode)")
                            }
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            hasAudioFocus = true
                            val svc = instance
                            if (svc?.isPlaying() != true && svc?.wasPlayingBeforeFocusLoss == true) {
                                svc?.resume()
                                svc?.wasPlayingBeforeFocusLoss = false
                            }
                            android.util.Log.d("MusicService", "Audio focus regained")
                        }
                    }
                }
            }
            val result = am.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            android.util.Log.d("MusicService", "requestAudioFocus: ${if (hasAudioFocus) "granted" else "denied"}")
        }

        fun abandonAudioFocus(ctx: android.content.Context) {
            if (instance?.isOboeDirectMode() == true) return  // Oboe mode: never requested focus
            if (!hasAudioFocus) return
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioFocusListener?.let { am.abandonAudioFocus(it) }
            hasAudioFocus = false
            android.util.Log.d("MusicService", "Audio focus abandoned")
        }

        // [v7.113] ��¼���㶪ʧǰ�Ĳ���״̬
        var wasPlayingBeforeFocusLoss = false
            private set

        // [v7.113] ���µĲ���״̬����Widget��ѯ������Oboe JNI�ӳ٣�
        var lastKnownPlayingState: Boolean = false
            private set
    }

    // ??Steven v1.9.1????�� Media3 ??????????,????????????
    // Media3 ???????????????????????��?,??????????????? updateNotification()
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // ??v7.112??Service ???????????????stopSelf ?? skip ???��??
        if (isDestroyed) return
        Log.d(TAG, "onUpdateNotification called, currentSong=${currentSong?.title}, startInForeground=$startInForegroundRequired")
        // ????��??????????????????????
        if (currentSong != null) {
            updateNotification()
            Log.d(TAG, "Custom notification updated via onUpdateNotification")
        }
        // ?????? super,??? Media3 ??????????????????��?
    }

    // [v7.113] ���㶪ʧǰ�Ƿ��ڲ���
    private var wasPlayingBeforeFocusLoss = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate - Service starting")

        // [v7.xxx] Use ProcessLifecycleOwner to track app foreground/background
        // It initializes during Application.onCreate (before any Activity/Service),
        // unlike ActivityLifecycleCallbacks which fire too late if Service starts after Activity resumes
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                handler.removeCallbacks(visualizerReleaseTask)
                isAppForeground = true
                Log.d(TAG, "App foreground (ProcessLifecycle), re-setup Visualizer")
                if (fftCallback != null && !visualizerManager.isReady()) {
                    handler.postDelayed({ visualizerManager.setup() }, 300)
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                isAppForeground = false
                Log.d(TAG, "App background (ProcessLifecycle), scheduling Visualizer release in 2s")
                handler.postDelayed(visualizerReleaseTask, 2000)
            }
        })

        createNotificationChannel()

        // ??Steven v1.6 ????????Shuffle??????,????? ExoPlayer
        val prefs = getSharedPreferences("MusicPlayer", MODE_PRIVATE)
        isShuffleMode = prefs.getBoolean("shuffle_mode", false)
        Log.d(TAG, "Restored shuffle mode: $isShuffleMode")

        // ???????:??????????????,???????????
        // ??Steven v1.51 ??????????????????? + ????? + ?????
        val audioAttributesBuilder = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            audioAttributesBuilder.setFlags(android.media.AudioAttributes.FLAG_LOW_LATENCY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioAttributesBuilder.setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
        }
        val audioAttributes = audioAttributesBuilder.build()

        // ??v6.20????Settings??????Output Mode,??? Oboe ???
        val audioOutputMode = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("audio_output", "Oboe Exclusive") ?: "Oboe Exclusive"

        // ??v6.20?????????Output ModeSettings??????
        val loadControl = when (audioOutputMode) {
            "AAudio", "Oboe\u72ec\u5360", "OpenSL ES" -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 8000, 1000, 2000)
                .build()
            else -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(2000, 5000, 500, 1000)
                .build()
        }

        val renderersFactory = buildRenderersFactory(audioOutputMode)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)   // true = ??????????
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)           // ?��?????????
            .build()

        // ??????????????????????????
        player.setSkipSilenceEnabled(false)
        // ??Steven v1.6?????Playlists,???? STATE_ENDED ?? seekToNext ????��
        player.repeatMode = Player.REPEAT_MODE_ALL

        // volume ???? 1.0 ??????

        exoPlayer = player  // ??Steven??Save????

        // ??V7.31?????? ForwardingPlayer ?????
        // Oboe ???????,???? MediaSession ????????????(play/pause/next/prev)
        // ????? OboeDirectPlayer,?????????? ExoPlayer(?????)
        val wrappedPlayer = object : androidx.media3.common.ForwardingPlayer(player) {
            override fun play() {
                try {
                    if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                        oboeDirectPlayer?.resume()
                        notifyPlayStateChanged(true)
                        updateNotification()
                        Log.d(TAG, "ForwardingPlayer.play → OboeDirectPlayer.resume()")
                    } else {
                        super.play()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ForwardingPlayer.play crash: ${e.message}", e)
                }
            }
            override fun pause() {
                try {
                    if (isOboeDirectMode() && oboeDirectPlayer?.isPlaying == true) {
                        oboeDirectPlayer?.pause()
                        notifyPlayStateChanged(false)
                        updateNotification()
                        Log.d(TAG, "ForwardingPlayer.pause → OboeDirectPlayer.pause()")
                    } else {
                        super.pause()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ForwardingPlayer.pause crash: ${e.message}", e)
                }
            }
            override fun seekToNext() {
                if (isOboeDirectMode()) {
                    playNext()
                    Log.d(TAG, "ForwardingPlayer.seekToNext ?? playNext() (Oboe)")
                } else {
                    super.seekToNext()
                }
            }
            override fun seekToPrevious() {
                if (isOboeDirectMode()) {
                    playPrevious()
                    Log.d(TAG, "ForwardingPlayer.seekToPrev ?? playPrevious() (Oboe)")
                } else {
                    super.seekToPrevious()
                }
            }
            override fun isPlaying(): Boolean {
                if (isOboeDirectMode() && oboeDirectPlayer != null) {
                    return oboeDirectPlayer!!.isPlaying
                }
                return super.isPlaying()
            }
            override fun getCurrentPosition(): Long {
                if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                    return oboeDirectPlayer!!.getCurrentPositionMs()
                }
                return super.getCurrentPosition()
            }
            override fun getDuration(): Long {
                if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                    return oboeDirectPlayer!!.getDurationMs()
                }
                return super.getDuration()
            }
            override fun seekTo(positionMs: Long) {
                try {
                    if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                        oboeDirectPlayer?.seekTo(positionMs)
                        super.seekTo(positionMs)
                        Log.d(TAG, "ForwardingPlayer.seekTo ?? OboeDirectPlayer.seekTo($positionMs)")
                    } else {
                        super.seekTo(positionMs)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ForwardingPlayer.seekTo crash: ${e.message}", e)
                }
            }
            override fun getPlaybackState(): Int {
                if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                    return Player.STATE_READY
                }
                return super.getPlaybackState()
            }
            override fun getPlayWhenReady(): Boolean {
                if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                    return oboeDirectPlayer?.isPlaying ?: false
                }
                return super.getPlayWhenReady()
            }
            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                    if (playWhenReady) oboeDirectPlayer?.resume() else oboeDirectPlayer?.pause()
                    return
                }
                super.setPlayWhenReady(playWhenReady)
            }
        }

        // ??Steven v1.6?????Shuffle???? ExoPlayer(????Shuffle,seekToNext ?????Shuffle)
        player.shuffleModeEnabled = isShuffleMode

        // ?????????????????????????????????? + ?��???????????
        player.addListener(playerListener)

        // ??v5.39??????? Equilizer??????? Oboe ??????��
        if (!isOboeDirectMode()) {
            EqualizerManager.init(player.audioSessionId)
            EqualizerManager.restoreSettings(this)
        }

        // ??Steven v1.6???? MediaSession(????????? onConnect ??)
        // ??V7.31????? wrappedPlayer ???? player,? MediaSession ????????? Oboe
        mediaSession = MediaSession.Builder(this, wrappedPlayer)
            .setCallback(object : MediaSession.Callback {
                // ??Steven v1.6???????????"Previous"????,??Shuffle???????
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
                        .build()

                    // ??Steven v1.9.2?????Previous????
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .build()

                    // ??Steven v1.9.4??CustomLayout ??????y?��?(Previous+????+?????)
                    val prevButton = CommandButton.Builder()
                        .setDisplayName("Previous")
                        .setIconResId(android.R.drawable.ic_media_previous)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .setEnabled(true)
                        .build()

                    val playPauseButton = CommandButton.Builder()
                        .setDisplayName(if (player.isPlaying) "Pause" else "Play")
                        .setIconResId(if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                        .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                        .setEnabled(true)
                        .build()

                    val nextButton = CommandButton.Builder()
                        .setDisplayName("Next")
                        .setIconResId(android.R.drawable.ic_media_next)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .setEnabled(true)
                        .build()

                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setAvailablePlayerCommands(playerCommands)
                        // ???3???:Previous??????/??????????
                        .setCustomLayout(ImmutableList.of(prevButton, playPauseButton, nextButton))
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        ACTION_CLOSE -> {
                            Log.d(TAG, "Close button pressed, performing hard exit")
                            performHardExit()
                        }
                        ACTION_SHUFFLE -> {
                            toggleShuffle()
                            // ???????????? session(???????????? mediaSession,???Refresh customLayout
                            val shuffleButton = CommandButton.Builder()
                                .setDisplayName(if (isShuffleMode) "Shuffle ON" else "Shuffle")
                                .setIconResId(if (isShuffleMode) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
                                .setSessionCommand(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                                .setEnabled(true)
                                .build()
                            val closeButton = CommandButton.Builder()
                                .setDisplayName("Close")
                                .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
                                .setSessionCommand(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
                                .setEnabled(true)
                                .build()
                            session.setCustomLayout(ImmutableList.of(shuffleButton, closeButton))
                            // ???Refresh???? shuffle ???
                            updateNotification()
                            Log.d(TAG, "Shuffle toggled from media session: isShuffleMode=$isShuffleMode")
                        }
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .setSessionActivity(createPendingIntent())
            .build()

        instance = this
        Log.d(TAG, "MediaSession created")

        // [V8.x] Register USB DAC hotplug callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.registerAudioDeviceCallback(usbDacCallback, handler)
            Log.d(TAG, "AudioDeviceCallback registered for USB DAC detection")
        }

        // [v7.113] �״�������Ƶ���㣨����ʱ����������
        requestAudioFocusIfNeeded(this)

        // ??v4.79???????????? + ???????????????(???,???????? Fragment)
        volumeGuard.register()

        // ???????Output Mode?��???apply ????????
        val settingsPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        settingsPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPrefs, key ->
            if (key == "audio_output") {
                val newMode = sharedPrefs.getString("audio_output", "Oboe\u72ec\u5360") ?: "Oboe\u72ec\u5360"
                handler.post { reconfigureAudioOutput(newMode) }
            }
        }
        settingsPrefs.registerOnSharedPreferenceChangeListener(settingsPrefsListener)
    }

    // ??Steven ?????????????????
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // [v7.122] If service was stopped by idle policy (Restricted), don't resurface
        // unless user explicitly tries to play (e.g. from notification PLAY button)
        if (stoppedByIdlePolicy) {
            if (intent?.action == "com.sdw.music.player.PLAY") {
                stoppedByIdlePolicy = false
                Log.d(TAG, "onStartCommand: user wants to play, clearing idle stop flag")
            } else {
                Log.d(TAG, "onStartCommand: was stopped by idle policy, ignoring restart")
                return START_NOT_STICKY
            }
        }

        // ??v2.0 ??????????? 5 ??????? startForeground,????????
        // ???????��???????????????????
        if (currentSong == null) {
            val emptyNotification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Moto Music")
                .setContentText("Preparing...")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(NOTIFICATION_ID, emptyNotification)
            Log.d(TAG, "Started foreground with empty notification")
        }

        when (intent?.action) {
            "com.sdw.music.player.ACTION_SHUFFLE" -> {
                toggleShuffle()
                updateNotification()  // Refresh?????
                Log.d(TAG, "Shuffle toggled from notification: isShuffleMode=$isShuffleMode")
            }
            "com.sdw.music.player.PREV" -> playPrevious()
            "com.sdw.music.player.NEXT" -> playNext()
            "com.sdw.music.player.PLAY" -> resume()
            "com.sdw.music.player.PAUSE" -> pause()
        }
        return START_STICKY
    }

    // ??????????????????????
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * ???y?��???????On MainActivity (SINGLE_TOP ?????)
     * ???? TaskStackBuilder ?? ?? Service ?????????????????????,
     * ???? Activity ??? ?? ViewModel ??? ?? connect() ??? ?? Playing?? + ????
     */
    private fun createPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_player", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ??Steven v1.51 ?????setSongs ??? Service ?????Playlists,
    // ??????? SongRepository ?????????��?(????Back?Folders?????)
    /**
     * ??Steven v1.51?????? Service Playlists + ??? ExoPlayer
     * keepCurrentPosition=true ???????????????????(???? onResume ?��??��?)
     */
    /**
     * SettingsPlaylists??
     * @param songs ??????????��?
     * @param updateGlobal ?????????? SongRepository ????��?(???��?=true,Folders=false)
     */
    fun setSongs(songs: List<Song>, updateGlobal: Boolean = true, source: String = "All Songs") {
        Log.d(TAG, "setSongs: ${songs.size} songs ?? servicePlaylist, source=$source, updateGlobal=$updateGlobal")
        servicePlaylist = songs
        playlistSource = source
        if (updateGlobal) {
            SongRepository.setSongs(songs)
        }
    }

    // [V8.1] sync servicePlaylist with SongRepository after delete
    fun refreshServicePlaylist() {
        servicePlaylist = SongRepository.getSongs()
        playlistSource = "All Songs"
    }

    fun playSong(index: Int) {
        // [v7.113] ��ʼ����ʱ������Ƶ����
        requestAudioFocusIfNeeded(this)

        val songs = servicePlaylist.ifEmpty { SongRepository.getSongs() }
        Log.d(TAG, "playSong: index=$index, songsCount=${songs.size}, playlistSource=$playlistSource")

        if (index < 0 || index >= songs.size) {
            Log.e(TAG, "Invalid index: $index")
            return
        }

        // ??v6.22??Oboe ?????:?? NDK MediaCodec ???? + Oboe ????
        val oboeMode = isOboeDirectMode()
        Log.d(TAG, "playSong: checking Oboe mode, isOboeDirectMode=$oboeMode, oboeFailureCount=$oboeFailureCount")
        oboeFlowTrace = if (oboeMode) "0?? Oboe?? (failures=$oboeFailureCount)" else "0?? ExoPlayer?? (audio_output=${getSharedPreferences("settings", MODE_PRIVATE).getString("audio_output", "?")}, libLoaded=${OboeDirectPlayer.nativeLibLoaded}, failures=$oboeFailureCount)"
        if (oboeMode) {
            playSongOboeDirect(index, songs)
            // ??V7.41+V7.46??Oboe????Refresh????
            updateNotification()
            // ??V7.44+V7.46??Oboe?????????UI?????????????????��??Playing"??
            notifySongChanged(songs[index])
            return  // ??V7.46 ?????????return???????????????????ExoPlayer?????
                    // replaceMediaItem????currentSong??????????????
        }

        val song = songs[index]
        Log.d(TAG, "playSong: switching to ${song.title} (id=${song.id})")

        // ??Steven v1.6 ?????? + Bug ?????
        // ????1:???????????????Playlists ?? ??? seekTo ????,???????1????��?
        // ????2:Playlists????(?��?Folders/???��?)?? ????��?
        mediaSession?.player?.let { player ->
            val existingPlaylistSize = player.mediaItemCount
            val isSamePlaylist = existingPlaylistSize == songs.size

            if (isSamePlaylist && player.playbackState != Player.STATE_IDLE) {
                // ??????1????��????��?:??? seekTo,?????? MediaItem
                // ???? clearMediaItems() + setMediaItems ???��? ExoPlayer ??????
                Log.d(TAG, "playSong: same playlist, seeking to index=$index")
                player.seekTo(index, 0)
                player.playWhenReady = true  // ??V7.XX??STATE_ENDED??playWhenReady=false?????????????��?????????
                currentSong = song
                currentIndex = index
                notifySongChanged(song)
                updateNotification()

                // ??V7.XX??????Equalizer?????
                handler.postDelayed({
                    tryInitEqualizerFallback()
                }, 300)
            } else {
                // ??????2??Playlists?��:??? MediaItem ?��?
                val mediaItems = songs.map { s ->
                    val artworkUri = if (s.albumArtUri.isNotEmpty()) {
                        android.net.Uri.parse(s.albumArtUri)
                    } else null
                    val displayArtist = if (s.artist.isNullOrBlank() || s.artist == "Unknown Artist") {
                        "Moto Music"
                    } else { s.artist }
                    val displayAlbum = if (s.album.isNullOrBlank() || s.album == "Unknown Album") {
                        playlistSource
                    } else { s.album }

                    MediaItem.Builder()
                        .setUri(android.net.Uri.parse(s.path))
                        .setMimeType(getMimeType(s.format))  // ??v5.58????? MIME type,????Opus ????????????
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(s.title)
                                .setArtist(displayArtist)
                                .setAlbumTitle(displayAlbum)
                                .setArtworkUri(artworkUri)
                                .setExtras(android.os.Bundle().apply {
                                    putString("MEDIA_ID_CUSTOM", s.id.toString())
                                })
                                .build()
                        )
                        .build()
                }
                player.clearMediaItems()
                player.setMediaItems(mediaItems, index, 0L)
                player.prepare()
                player.playWhenReady = true
                currentSong = song
                currentIndex = index
                notifySongChanged(song)
                updateNotification()
                Log.d(TAG, "playSong: new playlist set (${songs.size} songs), playing ${song.title}")

                // ??V7.XX??????Equalizer??????????onAudioSessionIdChanged?????
                handler.postDelayed({
                    tryInitEqualizerFallback()
                }, 300)
            }

            // Reset mute state when starting new song
            volumeGuard.resetMuteState()

            // ????? Visualizer FFT
            handler.postDelayed({
                if (fftCallback != null && !visualizerManager.isReady()) { visualizerManager.setup() }
            }, 500)
        }
    }

    /**
     * ??Steven ???????????? song.id ????,???????????????
     *
     * ???��???????????????????? servicePlaylist ???? ID ???,
     * ????????��???????��??????
     */
    fun playSongById(songId: Long, allSongs: List<Song>) {
        val songs = servicePlaylist.ifEmpty { allSongs }

        // ??????Playlists????
        var index = songs.indexOfFirst { it.id == songId }

        if (index != -1) {
            // ????????Playlists??,????
            Log.d(TAG, "playSongById: found in current playlist at $index (source=$playlistSource)")
            playSong(index)
        } else {
            // ??????????Playlists(?????Folders?��????��?),?��???????��?
            Log.d(TAG, "playSongById: not in current playlist, switching to full list")
            setSongs(allSongs, updateGlobal = true, source = "All Songs")
            index = allSongs.indexOfFirst { it.id == songId }
            if (index != -1) {
                playSong(index)
            } else {
                Log.e(TAG, "playSongById: song not found in any list! id=$songId")
            }
        }
    }

    // ??v6.22??Oboe ????????????
    // ==Splitted Oboe blocking ops to background thread=============================
    // V7.123: moved stop/open/play off UI thread so UI never freezes
    // ====================================================================

    private fun playSongOboeDirect(index: Int, songs: List<Song>) {
        val song = songs[index]
        currentIndex = index
        currentSong = song
        oboeFlowTrace = "1W9 Oboe starting: ${song.title}"
        Log.i(TAG, "playSongOboeDirect: ${song.title}")

        // Notify UI that we're loading
        handler.post {
            updateNotification()
        }

        oboeFlowTrace = "1F5 launching bg thread..."

        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

            // Stop previous (background thread - nativeStop() internally syncs via join+close+reset)
            oboeDirectPlayer?.stop()

            // Create new player
            val newPlayer = OboeDirectPlayer(this@MusicService)
            oboeDirectPlayer = newPlayer

            Log.i(TAG, "Oboe: Exclusive mode (bg thread)")
            oboeFlowTrace = "2F0E initializing (libLoaded=${OboeDirectPlayer.nativeLibLoaded})"

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val nativeSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
            newPlayer.setSampleRateNative(nativeSampleRate)
            newPlayer.resetClipStats()
            newPlayer.onCompletion = {
                Log.i(TAG, "OboeDirect: song completed, playing next")
                handler.post { playNext() }
            }
            newPlayer.onError = { msg ->
                oboeFlowTrace = "274C error: $msg, fallback to ExoPlayer"
                Log.e(TAG, "OboeDirect error: $msg")
                oboeFailureCount++
                handler.post { playSongFallbackExo(index, songs) }
            }

            val filePath = song.filePath.ifEmpty { song.path }
            val actualPath = if (filePath.startsWith("content://")) {
                resolveContentUriToPath(filePath)
            } else {
                filePath
            }

            if (actualPath == null) {
                oboeFlowTrace = "274C cannot resolve path, fallback to ExoPlayer"
                Log.e(TAG, "Cannot resolve path for: $filePath")
                handler.post { playSongFallbackExo(index, songs) }
                return@Thread
            }

            oboeFlowTrace = "3F50C opening..."
            val opened = newPlayer.open(actualPath)
            Log.i(TAG, "oboeDirectPlayer.open() = $opened, actualPath=$actualPath")
            if (!opened) {
                oboeFailureCount++
                oboeFlowTrace = "274C open failed, fallback to ExoPlayer (failures=$oboeFailureCount)"
                Log.e(TAG, "OboeDirect failed to open: $actualPath")
                handler.post { playSongFallbackExo(index, songs) }
                return@Thread
            }

            oboeFlowTrace = "4F3B5 playing..."
            var played = newPlayer.play()
            Log.i(TAG, "oboeDirectPlayer.play() = $played")
            if (!played) {
                Thread.sleep(150)
                played = newPlayer.play()
                Log.i(TAG, "oboeDirectPlayer retry play() = $played")
            }
            if (!played) {
                oboeFailureCount++
                oboeFlowTrace = "274C play failed, fallback to ExoPlayer (failures=$oboeFailureCount)"
                Log.e(TAG, "OboeDirect failed to play: $actualPath")
                handler.post { playSongFallbackExo(index, songs) }
                return@Thread
            }

            // Oboe succeeded, post UI updates back to main thread
            oboeFailureCount = 0
            oboeFlowTrace = "2705 Oboe OK (mode=${newPlayer.getDspMode()?.displayName}, exclusive=${newPlayer.isExclusiveMode()})"

            handler.post {
                if (isOboeDirectMode()) {
                    exoPlayer?.stop()  // Release MediaCodec + buffer resources to save CPU
                    exoPlayer?.volume = 0f
                    mediaSession?.player?.volume = 0f
                    Log.d(TAG, "Oboe Exclusive mode: ExoPlayer stopped (saves CPU)")
                }

                currentSong = song
                currentIndex = index
                volumeGuard.resetMuteState()
                oboeFailureCount = 0

                val dspModeSp = getSharedPreferences("dsp_mode", MODE_PRIVATE)
                val savedDspMode = dspModeSp.getInt("mode", -1)
                setDspMode(savedDspMode)
                Log.i(TAG, "DSP mode restored: ${when (savedDspMode) { -1 -> "OFF"; 1 -> "CAT_MODE"; else -> "STEVEN_SPECIAL" }}")
                EqualizerManager.restoreSettings(this@MusicService)

                // Only sync ExoPlayer playlist when NOT in Oboe mode.
                // Oboe plays directly via native AAudio — keeping ExoPlayer prepared
                // wastes CPU on parallel MediaCodec decoding in the background.
                if (!isOboeDirectMode()) {
                exoPlayer?.let { player ->
                    val existingPlaylistSize = player.mediaItemCount
                    val isSamePlaylist = existingPlaylistSize == songs.size

                    if (isSamePlaylist) {
                        val artworkUri = if (song.albumArtUri.isNotEmpty()) android.net.Uri.parse(song.albumArtUri) else null
                        val displayArtist = if (song.artist.isNullOrBlank() || song.artist == "Unknown Artist") "Moto Music" else song.artist
                        val displayAlbum = if (song.album.isNullOrBlank() || song.album == "Unknown Album") playlistSource else song.album
                        val newMediaItem = MediaItem.Builder()
                            .setUri(song.path)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(song.title)
                                    .setArtist(displayArtist)
                                    .setAlbumTitle(displayAlbum)
                                    .setArtworkUri(artworkUri)
                                    .setExtras(Bundle().apply { putString("MEDIA_ID_CUSTOM", song.id.toString()) })
                                    .build()
                            )
                            .build()
                        player.replaceMediaItem(index, newMediaItem)
                        player.seekTo(index, 0L)
                    } else {
                        val mediaItems = songs.map { s ->
                            val artworkUri = if (s.albumArtUri.isNotEmpty()) android.net.Uri.parse(s.albumArtUri) else null
                            val displayArtist = if (s.artist.isNullOrBlank() || s.artist == "Unknown Artist") "Moto Music" else s.artist
                            val displayAlbum = if (s.album.isNullOrBlank() || s.album == "Unknown Album") playlistSource else s.album
                            MediaItem.Builder()
                                .setUri(android.net.Uri.parse(s.path))
                                .setMimeType(getMimeType(s.format))
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(s.title)
                                        .setArtist(displayArtist)
                                        .setAlbumTitle(displayAlbum)
                                        .setArtworkUri(artworkUri)
                                        .setExtras(android.os.Bundle().apply { putString("MEDIA_ID_CUSTOM", s.id.toString()) })
                                        .build()
                                )
                                .build()
                        }
                        player.setMediaItems(mediaItems, index, 0L)
                    }
                    player.prepare()
                    // [V8.x] ForwardingPlayer now overrides playbackState+playWhenReady
                    // based on OboeDirectPlayer state, so ExoPlayer stays idle.
                }
                } // if (!isOboeDirectMode())

                notifyPlayStateChanged(true)
                notifySongChanged(song)
                updateNotification()

                handler.postDelayed({
                    if (fftCallback != null && !visualizerManager.isReady()) { visualizerManager.setup() }
                }, 500)

                val sampleRate = newPlayer.getSampleRate() ?: 0
                val nativeRate = newPlayer.getSampleRateNative() ?: 0
                val bitPerfect = sampleRate == nativeRate
                val clipInfo = newPlayer.getClipDebugInfo() ?: ""
                Log.i(TAG, "OboeDirect playing: ${song.title}, rate=${sampleRate}Hz, native=${nativeRate}Hz, bitPerfect=$bitPerfect, exclusive=${newPlayer.isExclusiveMode()}, dspMode=${newPlayer.getDspMode()?.displayName}, $clipInfo")
            }
        }.start()
    }


    /** ??V7.17??Oboe ?????????? ExoPlayer ?????????(??????????) */
    private fun playSongFallbackExo(index: Int, songs: List<Song>) {
        val song = songs[index]
        oboeFlowTrace = "\u2139\uFE0F ????ExoPlayer: ${song.title} (failures=$oboeFailureCount)"
        Log.w(TAG, "Falling back to ExoPlayer for: ${song.title}")
        // ??????????Oboe
        useOboeDirect = false
        // ??? ExoPlayer ????
        exoPlayer?.volume = 1f
        mediaSession?.player?.volume = 1f
        val mediaItems = songs.map { s ->
            MediaItem.Builder()
                .setUri(s.path)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(s.title).build())
                .build()
        }
        exoPlayer?.setMediaItems(mediaItems, index, 0L)
        exoPlayer?.playWhenReady = true
        currentSong = song
        currentIndex = index
        notifySongChanged(song)
        updateNotification()
        volumeGuard.resetMuteState()
    }

    /** ??v6.25??Apply DSP Biquad EQ in Oboe callback if "Steven Special" preset is active */
    private fun applyDspEqIfNeeded() {
        val eqPresetId = EqualizerManager.getCurrentPresetId(this)
        if (eqPresetId == "steven_special" && oboeDirectPlayer != null) {
            oboeDirectPlayer?.setDspEq(
                enabled = true,
                highShelfFreq = 8000f, highShelfDb = 2.0f, highShelfQ = 0.707f,
                peakingFreq = 12000f, peakingDb = 2.0f, peakingQ = 2.0f,
                preGainDb = 0.0f
            )
            Log.i(TAG, "DSP EQ enabled: Steven Special (High-Shelf 8kHz/+2dB + Air 12kHz/+2dB)")
        } else {
            oboeDirectPlayer?.setDspEq(enabled = false)
        }
    }

    /** ??v6.29??DSP EQ ????On??(?????? EQ preset) */
    fun setDspEqEnabled(enabled: Boolean) {
        dspEqEnabled = enabled
        // ??V7.02???SettingsOn??,??Settings?????????????? setDspMode ?? setCustomDspEq ????)
        if (oboeDirectPlayer != null) {
            oboeDirectPlayer?.setDspEnabled(enabled)
            Log.i(TAG, "DSP EQ ${if (enabled) "enabled" else "disabled"} via toggle")
        }
    }

    /** ??v6.29??DSP EQ ?????????????��???,?? SharedPreferences ???? */
    fun setCustomDspEq(
        enabled: Boolean,
        highShelfFreq: Float, highShelfDb: Float, highShelfQ: Float,
        peakingFreq: Float, peakingDb: Float, peakingQ: Float,
        preGainDb: Float
    ) {
        dspEqEnabled = enabled
        if (oboeDirectPlayer != null) {
            oboeDirectPlayer?.setDspEq(enabled, highShelfFreq, highShelfDb, highShelfQ,
                peakingFreq, peakingDb, peakingQ, preGainDb)
        }
        // ??V7.04???????? SharedPreferences
        getSharedPreferences("dsp_eq", MODE_PRIVATE).edit().apply {
            putBoolean("enabled", enabled)
            putFloat("hs_freq", highShelfFreq)
            putFloat("hs_db", highShelfDb)
            putFloat("hs_q", highShelfQ)
            putFloat("pk_freq", peakingFreq)
            putFloat("pk_db", peakingDb)
            putFloat("pk_q", peakingQ)
            putFloat("pre_gain_db", preGainDb)
            apply()
        }
    }

    /** ??V7.04???? SharedPreferences ??? DSP EQ ?????????*/
    fun restoreCustomDspEq() {
        val sp = getSharedPreferences("dsp_eq", MODE_PRIVATE)
        val enabled = sp.getBoolean("enabled", false)
        if (!enabled) return
        val hsFreq = sp.getFloat("hs_freq", 8000f)
        val hsDb = sp.getFloat("hs_db", 0f)
        val hsQ = sp.getFloat("hs_q", 0.707f)
        val pkFreq = sp.getFloat("pk_freq", 12000f)
        val pkDb = sp.getFloat("pk_db", 0f)
        val pkQ = sp.getFloat("pk_q", 2.0f)
        val preGainDb = sp.getFloat("pre_gain_db", 0f)
        dspEqEnabled = true
        if (oboeDirectPlayer != null) {
            oboeDirectPlayer?.setDspEq(true, hsFreq, hsDb, hsQ, pkFreq, pkDb, pkQ, preGainDb)
            Log.i(TAG, "DSP EQ restored from prefs: HS=${hsFreq}Hz/${hsDb}dB + Peak=${pkFreq}Hz/${pkDb}dB + preGain=${preGainDb}dB")
        }
    }

    /** ??V7.04???????Save?? DSP EQ ????(?? UI ???????) */
    fun getSavedDspEqParams(): android.os.Bundle? {
        val sp = getSharedPreferences("dsp_eq", MODE_PRIVATE)
        if (!sp.getBoolean("enabled", false)) return null
        return android.os.Bundle().apply {
            putFloat("hs_freq", sp.getFloat("hs_freq", 8000f))
            putFloat("hs_db", sp.getFloat("hs_db", 0f))
            putFloat("pk_freq", sp.getFloat("pk_freq", 12000f))
            putFloat("pk_db", sp.getFloat("pk_db", 0f))
            putFloat("pre_gain_db", sp.getFloat("pre_gain_db", 0f))
        }
    }

    fun isDspEqEnabled(): Boolean = dspEqEnabled

    /** ??V7.0????? OboeDirectPlayer ????(?? UI ??????????? */
    fun getOboePlayer(): OboeDirectPlayer? = oboeDirectPlayer

    /** ??V7.0??Settings DSP Mode:-1 = OFF,0 = Steven Special,1 = Cat Mode */
    fun setDspMode(mode: Int) {
        // ??V7.10?????? DSP Mode
        getSharedPreferences("dsp_mode", MODE_PRIVATE).edit().putInt("mode", mode).apply()
        oboeDirectPlayer?.setDspMode(
            when (mode) {
                -1 -> OboeDirectPlayer.DspMode.OFF
                1 -> OboeDirectPlayer.DspMode.CAT_MODE
                else -> OboeDirectPlayer.DspMode.STEVEN_SPECIAL
            }
        )
        Log.i(TAG, "DSP mode set to: ${when (mode) { -1 -> "OFF"; 1 -> "CAT_MODE"; else -> "STEVEN_SPECIAL" }}")
    }

    /** ??V7.0???????? DSP Mode:-1 = OFF,0 = Steven Special,1 = Cat Mode */
    fun getDspMode(): Int {
        return try {
            val mode = oboeDirectPlayer?.getDspMode()
            when (mode) {
                OboeDirectPlayer.DspMode.OFF -> -1
                OboeDirectPlayer.DspMode.CAT_MODE -> 1
                else -> 0
            }
        } catch (_: Exception) { -1 }
    }

    // ??V7.34??Brand Presets????? ?? ??? DSP Mode???????Steven Special / ���� / Close??

    /** ??V7.05??????? - softClip ???????,???????????*/
    fun setNightMode(enabled: Boolean) {
        oboeDirectPlayer?.setNightMode(enabled)
    }

    fun isNightMode(): Boolean {
        return oboeDirectPlayer?.isNightMode() ?: false
    }

    fun toggleNightMode() {
        oboeDirectPlayer?.toggleNightMode()
    }

    private fun resolveContentUriToPath(uri: String): String? {
        // Try to get file path from content URI
        // For local files managed by MediaStore, the DATA column still contains the file path
        try {
            val contentUri = android.net.Uri.parse(uri)
            if (contentUri.scheme == "file") {
                return contentUri.path
            }
            // For content:// URIs, query MediaStore
            val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA)
            val cursor = contentResolver.query(contentUri, projection, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val path = it.getString(0)
                    if (!path.isNullOrEmpty()) return path
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveContentUriToPath failed: ${e.message}")
        }
        return null
    }

    fun pause() {
        try {
            if (isOboeDirectMode() && oboeDirectPlayer?.isPlaying == true) {
                oboeDirectPlayer?.pause()
                notifyPlayStateChanged(false)
            } else {
                mediaSession?.player?.pause()
                // ExoPlayer ?? onIsPlayingChanged ??????? UI
            }
            // [v7.113] ��ͣʱ�ͷ���Ƶ���㣬��ϵͳ��������
            abandonAudioFocus(this)
            Log.d(TAG, "Paused")
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "pause crash: ${e.message}", e)
        }
    }

    fun resume() {
        try {
            requestAudioFocusIfNeeded(this)
            if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true && oboeDirectPlayer?.isPlaying == false) {
                val ok = oboeDirectPlayer?.resume() ?: false
                if (ok) {
                    onOboeResumeSuccess()
                } else {
                    // [vX] nativeResume may return false during rapid state transitions (e.g. volume 0->restore)
                    // retry once after 150ms - same window as nativePlay retry on fast track switches
                    Log.w(TAG, "Oboe resume returned false, retrying in 150ms")
                    handler.postDelayed({
                        try {
                            if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true && oboeDirectPlayer?.isPlaying == false) {
                                val retry = oboeDirectPlayer?.resume() ?: false
                                if (retry) {
                                    onOboeResumeSuccess()
                                } else {
                                    Log.e(TAG, "Oboe resume retry also failed, falling back to ExoPlayer")
                                    resumeExoPlayer()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Oboe resume retry crash: ${e.message}", e)
                        }
                    }, 150)
                    return  // defer state update until retry completes
                }
            } else {
                resumeExoPlayer()
            }
            // [V8.x] ForwardingPlayer overrides handle state; ExoPlayer stays idle
            if (isOboeDirectMode()) {
                exoPlayer?.volume = 0f
                mediaSession?.player?.volume = 0f
            }
            Log.d(TAG, "Resumed")
            updateNotification()
        } catch (e: Exception) {
            Log.e(TAG, "resume crash: ${e.message}", e)
        }
    }

    private fun onOboeResumeSuccess() {
        notifyPlayStateChanged(true)
        updateNotification()
        Log.d(TAG, "Oboe resumed OK")
    }

    private fun resumeExoPlayer() {
        val player = exoPlayer ?: mediaSession?.player
        if (player != null) {
            if (player.mediaItemCount == 0) {
                Log.e(TAG, "resumeExoPlayer: no media items, aborting")
                return
            }
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                player.prepare()
            }
            player.play()
        }
        exoPlayer?.volume = 1f
        mediaSession?.player?.volume = 1f
    }

    fun isPlaying(): Boolean {
        if (oboeDirectPlayer?.isPlaying == true) return true
        return mediaSession?.player?.isPlaying == true
    }

    fun getCurrentPosition(): Long {
        // ??V7.34????????????isOboeDirectMode(),??????Oboe ?��?????????
        // oboeDirectPlayer.isPrepared ??? true ?? Back??????Oboe ��?? ?? ?????????
        val isOboe = isOboeDirectMode()
        if (isOboe && oboeDirectPlayer?.isPrepared == true) return oboeDirectPlayer?.getCurrentPositionMs() ?: 0
        val pos = mediaSession?.player?.currentPosition ?: 0
        // ??V7.35???????? Oboe ????????????????
        if (pos <= 0 && mediaSession?.player?.playbackState == Player.STATE_IDLE) {
            Log.w(TAG, "getCurrentPosition: ExoPlayer STATE_IDLE! isPrepared=${oboeDirectPlayer?.isPrepared}")
        }
        return pos
    }

    fun getDuration(): Long {
        val isOboe = isOboeDirectMode()
        if (isOboe && oboeDirectPlayer?.isPrepared == true) return oboeDirectPlayer?.getDurationMs() ?: 0
        return mediaSession?.player?.duration ?: 0
    }

    fun seekTo(position: Long) {
        if (oboeDirectPlayer?.isPrepared == true) {
            oboeDirectPlayer?.seekTo(position)
            oboeDirectPlayer?.resetDspEq()  // Reset filter state on seek
            oboeDirectPlayer?.resetClipStats()  // ??V7.0??Reset peak stats on seek
        } else {
            mediaSession?.player?.seekTo(position)
        }
    }

    fun playNext() {
        val songs = servicePlaylist.ifEmpty { SongRepository.getSongs() }
        if (songs.isEmpty()) return

        // ??v6.22??Oboe ???????????? playSong ????
        val nextIndex = if (isShuffleMode || exoPlayer?.shuffleModeEnabled == true) {
            (0 until songs.size).filter { it != currentIndex }.random().let { if (it < 0) 0 else it }
        } else {
            (currentIndex + 1) % songs.size
        }
        playSong(nextIndex)
    }

    fun playPrevious() {
        val songs = servicePlaylist.ifEmpty { SongRepository.getSongs() }
        if (songs.isEmpty()) return

        val prevIndex = if (currentIndex > 0) currentIndex - 1 else songs.size - 1
        playSong(prevIndex)
    }

    /**
     * ??Steven v1.6??Shuffle?????��? - ??? ExoPlayer ???? shuffleModeEnabled
     * Settings?? seekToNext() / seekToPrevious() ?????Shuffle???
     */
    fun toggleShuffle(): Boolean {
        isShuffleMode = !isShuffleMode
        exoPlayer?.shuffleModeEnabled = isShuffleMode
        Log.d(TAG, "Shuffle mode: $isShuffleMode (ExoPlayer.shuffleModeEnabled=$isShuffleMode)")

        // ??Steven v1.6 ????????Refresh MediaSession customLayout(y?��???????)
        mediaSession?.let { session ->
            val shuffleButton = CommandButton.Builder()
                .setDisplayName(if (isShuffleMode) "Shuffle ON" else "Shuffle")
                .setIconResId(if (isShuffleMode) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
                .setSessionCommand(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                .setEnabled(true)
                .build()
            val closeButton = CommandButton.Builder()
                .setDisplayName("Close")
                .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
                .setSessionCommand(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
                .setEnabled(true)
                .build()
            session.setCustomLayout(ImmutableList.of(shuffleButton, closeButton))
        }

        // ???Refresh???? shuffle ???
        updateNotification()

        // ????
        getSharedPreferences("MusicPlayer", MODE_PRIVATE).edit()
            .putBoolean("shuffle_mode", isShuffleMode).apply()

        return isShuffleMode
    }

    fun setShuffleMode(enabled: Boolean) {
        isShuffleMode = enabled
        exoPlayer?.shuffleModeEnabled = enabled
        Log.d(TAG, "Shuffle mode set to: $enabled")

        // ??Steven v1.6?????Refresh MediaSession customLayout
        mediaSession?.let { session ->
            val shuffleButton = CommandButton.Builder()
                .setDisplayName(if (isShuffleMode) "Shuffle ON" else "Shuffle")
                .setIconResId(if (isShuffleMode) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
                .setSessionCommand(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                .setEnabled(true)
                .build()
            val closeButton = CommandButton.Builder()
                .setDisplayName("Close")
                .setIconResId(android.R.drawable.ic_menu_close_clear_cancel)
                .setSessionCommand(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
                .setEnabled(true)
                .build()
            session.setCustomLayout(ImmutableList.of(shuffleButton, closeButton))
        }

        // ???Refresh????
        updateNotification()

        // ????
        getSharedPreferences("MusicPlayer", MODE_PRIVATE).edit()
            .putBoolean("shuffle_mode", enabled).apply()
    }

    // ??Steven????????? updateShuffleButton,y?��????Close???

    /**
     * ??Steven ???????????????????????????,????LifecycleRegistry ????
     * ??????:???��???,?????????
     */
    private fun performHardExit() {
        try {
            Log.d(TAG, "=== performHardExit: Starting hard exit ===")

            // 1. ??????:???????????????
            // release() ???????????��?????
            exoPlayer?.stop()
            exoPlayer?.release()
            exoPlayer = null

            // 2. ??? Visualizer
            visualizerManager.release()

            // 3. ???y??????????????????
            mediaSession?.release()
            mediaSession = null

            // 4. ???????
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }

            // 5. ???????????
            instance = null
            currentSong = null

            // 6. ??????
            stopSelf()

            Log.d(TAG, "=== performHardExit: Clean exit completed ===")

            // 7. ????????��:????????????,??????
            android.os.Process.killProcess(android.os.Process.myPid())

        } catch (e: Exception) {
            Log.e(TAG, "performHardExit error: ${e.message}")
            // ??????????????,???????��??
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW  // LOW = ??????,????????
            ).apply {
                description = "Music playback controls"
                setSound(null, null)  // ??V7.0????????????��
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created with IMPORTANCE_DEFAULT")
        }
    }

    private fun updateNotification() {
        try {
            val song = currentSong ?: return
            val player = mediaSession?.player ?: return
            val session = mediaSession ?: return

        // ??Steven ????????????? PendingIntent.getActivity?????? TaskStackBuilder
        // TaskStackBuilder ?? Service ???????????????? ?? Activity ??? ?? ???????
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("open_player", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // [V8.x] Use lastKnownPlayingState instead of player.isPlaying
        // Oboe JNI isPlaying has lag; lastKnownPlayingState is set synchronously
        // in notifyPlayStateChanged → reflects caller intent immediately
        val isPlaying = if (isOboeDirectMode()) lastKnownPlayingState else player.isPlaying

        // ??V7.33????? getService ???? getBroadcast
        // getBroadcast ??? BroadcastReceiver,????��??? ?? ??????????????
        // getService ???? Intent ????? MusicService.onStartCommand()
        val prevIntent = PendingIntent.getService(
            this, 0, Intent(this, MusicService::class.java).apply { action = "com.sdw.music.player.PREV" }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playPauseIntent = PendingIntent.getService(
            this, 2, Intent(this, MusicService::class.java).apply { action = if (isPlaying) "com.sdw.music.player.PAUSE" else "com.sdw.music.player.PLAY" }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextIntent = PendingIntent.getService(
            this, 3, Intent(this, MusicService::class.java).apply { action = "com.sdw.music.player.NEXT" }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // ??????????????Cover??
        var largeIcon: Bitmap? = null
        if (song.albumArtUri.isNotEmpty()) {
            try {
                val uri = android.net.Uri.parse(song.albumArtUri)
                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    // ??��??????????Steven??>10MB??????)
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
                    inputStream.close()

                    // ???????????,??? 512x512
                    val scale = maxOf(options.outWidth, options.outHeight) / 512
                    val sampleSize = if (scale > 1) scale else 1

                    val options2 = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    val inputStream2 = contentResolver.openInputStream(uri)
                    largeIcon = android.graphics.BitmapFactory.decodeStream(inputStream2, null, options2)
                    inputStream2?.close()
                    Log.d(TAG, "Loaded album art: ${options.outWidth}x${options.outHeight} -> ${largeIcon?.width}x${largeIcon?.height}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load album art: ${e.message}")
            }
        }

        // ??Steven ????????????,??? <unknown>
        val displayArtist = if (song.artist.isNullOrBlank() || song.artist == "Unknown Artist") {
            "Moto Music"
        } else {
            song.artist
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText(displayArtist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            // ??Steven v1.9.4?????Previous????,Shuffle???????????????
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "Pause" else "Play",
                playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)

        @Suppress("DEPRECATION")
        notificationBuilder.setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionCompatToken)
                    // compactView ???:prev(0) + playPause(1) + next(2)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        // ?????????????????Cover,???????
        if (largeIcon != null) {
            notificationBuilder.setLargeIcon(largeIcon)
        }

        val notification = notificationBuilder.build()

        startForeground(NOTIFICATION_ID, notification)
        // ??V7.34?????Refresh?? UI,?????????????????
        // startForeground ??????????????????????????
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Notification updated: title=${song.title}, hasArt=${largeIcon != null}, isPlaying=$isPlaying, shuffleMode=$isShuffleMode, actions=${notification.actions?.size}, playerCommands=${player.availableCommands}")
        } catch (e: Exception) {
            Log.e(TAG, "updateNotification error (service may be dying): ${e.message}")
        }
    }

    // ??v4.94?????????Output Mode????????? RenderersFactory
    private fun buildRenderersFactory(audioOutputMode: String): DefaultRenderersFactory {
        return object : DefaultRenderersFactory(this) {
            init {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            }
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                // ??v7.07??Oboe ?????:ExoPlayer ?????��??(?????????0),OboeDirectPlayer ???????????DSP ??��)
                val builder = DefaultAudioSink.Builder(context)
                when (audioOutputMode) {
                    "AAudio" -> {
                        // AAudio ??:????????? Float ????
                        // Android 8.1+ ??????????????????????? AAudio ��??
                        // ??v7.112????????? enableAudioTrackPlaybackParams=true,
                        // ?????�� AudioTrack ????? playback params ?????????
                        builder.setEnableFloatOutput(enableFloatOutput)
                    }
                    "OpenSL ES" -> {
                        // OpenSL ES ??????:16-bit PCM + ???????
                        // ??? OpenSL ES ?????????��??,?????????????
                        builder.setEnableFloatOutput(false)
                        builder.setEnableAudioTrackPlaybackParams(false)
                    }
                    else -> {
                        // AudioTrack ?????:Float ???????????
                        builder.setEnableFloatOutput(enableFloatOutput)
                    }
                }
                return builder.build()
            }
        }
    }

    // ??v4.94???��????Output Mode - ??? ExoPlayer
    fun reconfigureAudioOutput(mode: String) {
        // ??V7.35????????????OboeDirectPlayer ??????
        // ?? Oboe ???? ExoPlayer ??��???(�� prepare),
        // ?? OboeDirectPlayer ????? ?? wasPlaying ?? true
        val wasPlaying = exoPlayer?.isPlaying == true || oboeDirectPlayer?.isPlaying == true
        // ??V7.35??Oboe ???? ExoPlayer �� prepare,currentPosition=0
        // ????? OboeDirectPlayer ??????????��??
        val currentPosition = if (oboeDirectPlayer?.isPrepared == true) {
            oboeDirectPlayer?.getCurrentPositionMs() ?: exoPlayer?.currentPosition ?: 0
        } else {
            exoPlayer?.currentPosition ?: 0
        }
        val currentMediaIndex = currentIndex
        val playlistSize = exoPlayer?.mediaItemCount ?: 0

        Log.d(TAG, "reconfigureAudioOutput: switching to $mode, wasPlaying=$wasPlaying, pos=$currentPosition, playlistSize=$playlistSize")

        // Save???Playlists
        val savedPlaylist = servicePlaylist.toList()
        Log.d(TAG, "reconfigureAudioOutput: savedPlaylist size=${savedPlaylist.size}")

        // ???? Visualizer(???? audioSessionId,?????????)
        visualizerManager.release()

        // ?????????
        try {
            exoPlayer?.stop()
        } catch (_: Exception) {}

        // ??? AudioAttributes(AAudio ???????????
        val audioAttributesBuilder = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        if (mode == "AAudio" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            audioAttributesBuilder.setFlags(android.media.AudioAttributes.FLAG_LOW_LATENCY)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioAttributesBuilder.setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
        }
        val audioAttributes = audioAttributesBuilder.build()

        // ??? LoadControl(v6.20 Oboe ???????????)
        val loadControl = when (mode) {
            "AAudio", "Oboe Exclusive", "Oboe???", "OpenSL ES" -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(3000, 8000, 1000, 2000)
                .build()
            else -> DefaultLoadControl.Builder()
                .setBufferDurationsMs(2000, 5000, 500, 1000)
                .build()
        }

        val renderersFactory = buildRenderersFactory(mode)

        val newPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .build()

        newPlayer.setSkipSilenceEnabled(false)
        newPlayer.repeatMode = Player.REPEAT_MODE_ALL
        newPlayer.shuffleModeEnabled = isShuffleMode

        // ?????????????
        newPlayer.addListener(playerListener)

        // ???? mediaSession
        mediaSession?.player?.removeListener(playerListener)
        mediaSession?.run {
            player.release()
            release()
        }
        // ??V7.108????? wrappedPlayer??? Oboe ???��?y?��??????????????? playNext()/playPrevious()
        val newWrappedPlayer = object : androidx.media3.common.ForwardingPlayer(newPlayer) {
            override fun play() {
                try {
                    if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                        oboeDirectPlayer?.resume()
                        updateNotification()
                    } else {
                        super.play()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ForwardingPlayer.play crash: ${e.message}", e)
                }
            }
            override fun pause() {
                try {
                    if (isOboeDirectMode() && oboeDirectPlayer?.isPlaying == true) {
                        oboeDirectPlayer?.pause()
                        updateNotification()
                    } else {
                        super.pause()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ForwardingPlayer.pause crash: ${e.message}", e)
                }
            }
            override fun seekToNext() {
                if (isOboeDirectMode()) {
                    playNext()
                    Log.d(TAG, "ForwardingPlayer(new).seekToNext ?? playNext()")
                } else {
                    super.seekToNext()
                }
            }
            override fun seekToPrevious() {
                if (isOboeDirectMode()) {
                    playPrevious()
                    Log.d(TAG, "ForwardingPlayer(new).seekToPrev ?? playPrevious()")
                } else {
                    super.seekToPrevious()
                }
            }
            override fun isPlaying(): Boolean {
                if (isOboeDirectMode() && oboeDirectPlayer != null) {
                    return oboeDirectPlayer!!.isPlaying
                }
                return super.isPlaying()
            }
            override fun getCurrentPosition(): Long {
                if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                    return oboeDirectPlayer!!.getCurrentPositionMs()
                }
                return super.getCurrentPosition()
            }
            override fun getDuration(): Long {
                if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                    return oboeDirectPlayer!!.getDurationMs()
                }
                return super.getDuration()
            }
            override fun seekTo(positionMs: Long) {
                try {
                    if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                        oboeDirectPlayer?.seekTo(positionMs)
                        super.seekTo(positionMs)
                        Log.d(TAG, "ForwardingPlayer(new).seekTo ?? OboeDirectPlayer.seekTo($positionMs)")
                    } else {
                        super.seekTo(positionMs)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ForwardingPlayer.seekTo crash: ${e.message}", e)
                }
            }
            override fun getPlaybackState(): Int {
                if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                    return Player.STATE_READY
                }
                return super.getPlaybackState()
            }
            override fun getPlayWhenReady(): Boolean {
                if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                    return oboeDirectPlayer?.isPlaying ?: false
                }
                return super.getPlayWhenReady()
            }
            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (isOboeDirectMode() && oboeDirectPlayer?.isPrepared == true) {
                    if (playWhenReady) oboeDirectPlayer?.resume() else oboeDirectPlayer?.pause()
                    return
                }
                super.setPlayWhenReady(playWhenReady)
            }
        }
        mediaSession = MediaSession.Builder(this, newWrappedPlayer)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: androidx.media3.session.MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_CLOSE, Bundle.EMPTY))
                        .build()
                    val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .build()
                    val prevButton = CommandButton.Builder()
                        .setDisplayName("Previous")
                        .setIconResId(android.R.drawable.ic_media_previous)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .setEnabled(true)
                        .build()
                    val playPauseButton = CommandButton.Builder()
                        .setDisplayName(if (newPlayer.isPlaying) "Pause" else "Play")
                        .setIconResId(if (newPlayer.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                        .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                        .setEnabled(true)
                        .build()
                    val nextButton = CommandButton.Builder()
                        .setDisplayName("Next")
                        .setIconResId(android.R.drawable.ic_media_next)
                        .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .setEnabled(true)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(sessionCommands)
                        .setAvailablePlayerCommands(playerCommands)
                        .setCustomLayout(ImmutableList.of(prevButton, playPauseButton, nextButton))
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        ACTION_SHUFFLE -> {
                            toggleShuffle()
                            updateNotification()
                        }
                        ACTION_CLOSE -> stopSelf()
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()

        exoPlayer = newPlayer

        // ??v5.55????????????????3???????????
        // Oboe ???????DSP ?? OboeDirectPlayer ???????????Android Equalizer
        // ?? Oboe ???????????? init??????AudioTrack ?????????sessionId=0????
        //            ?? onAudioSessionIdChanged ??????? init
        val newOboeMode = mode == "Oboe Exclusive" || mode == "Oboe???"
        EqualizerManager.release()  // ???? Android Equalizer
        if (!newOboeMode) {
            Log.d(TAG, "reconfigureAudioOutput: non-Oboe mode ($mode), trying init with sessionId=${newPlayer.audioSessionId}")
            EqualizerManager.init(newPlayer.audioSessionId)
            // ????sessionId=0??init() ??????????onAudioSessionIdChanged ?? init
            EqualizerManager.restoreSettings(this)
        } else {
            Log.d(TAG, "reconfigureAudioOutput: Oboe mode, DSP only (no Android Equalizer)")
        }

        // ???Playlists??��??
        if (savedPlaylist.isNotEmpty()) {
            servicePlaylist = savedPlaylist
            val mediaItems = savedPlaylist.map { song ->
                val artworkUri = if (song.albumArtUri.isNotEmpty()) {
                    android.net.Uri.parse(song.albumArtUri)
                } else null
                MediaItem.Builder()
                    .setUri(song.path)
                    .setMimeType(getMimeType(song.format))  // ??v5.58??
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(song.title)
                            .setArtist(song.artist)
                            .setAlbumTitle(song.album)
                            .setArtworkUri(artworkUri)
                            .setExtras(android.os.Bundle().apply {
                                putString("MEDIA_ID_CUSTOM", song.id.toString())
                            })
                            .build()
                    )
                    .build()
            }
                        // Oboe route: do NOT set media items on ExoPlayer.
            // setMediaItems internally calls prepare() which starts MediaCodec;
            // in Oboe Direct mode we skip it to avoid parallel decoding CPU burn.
            val targetOboe = mode == "Oboe Exclusive" || mode == "Oboe???"
            if (targetOboe && wasPlaying && currentMediaIndex in savedPlaylist.indices) {
                // Oboe mode: skip setMediaItems, route directly to OboeDirect
                newPlayer.volume = 0f
                newWrappedPlayer.volume = 0f
                Log.d(TAG, "reconfigureAudioOutput: Oboe mode, routing to playSongOboeDirect")
                handler.post {
                    playSongOboeDirect(currentMediaIndex, savedPlaylist)
                }
            } else {
                newPlayer.setMediaItems(mediaItems, currentMediaIndex, currentPosition.toLong())
                newPlayer.volume = 1f
                if (wasPlaying) {
                    newPlayer.playWhenReady = true
                }
                Log.d(TAG, "reconfigureAudioOutput: non-Oboe path, prepare+playWhenReady=$wasPlaying")
                newPlayer.prepare()
            }

        }

        // ??? Visualizer(?? Player ???? audioSessionId)
        if (fftCallback != null) {
            handler.postDelayed({
                try { visualizerManager.setup() } catch (e: Exception) {
                    Log.e(TAG, "setupVisualizer after reconfigure: ${e.message}")
                }
            }, 500)
        }

        // ??V7.34???��??? Oboe ???,??????? oboeDirectPlayer
        // ???? isPrepared ????? true ???? getCurrentPosition/getDuration ???��??
        if (mode != "Oboe Exclusive" && mode != "Oboe???" && oboeDirectPlayer != null) {
            oboeDirectPlayer?.stop()
            oboeDirectPlayer = null
            Log.d(TAG, "reconfigureAudioOutput: oboeDirectPlayer stopped & released (switching to $mode)")
        }

        Log.d(TAG, "reconfigureAudioOutput: done, mode=$mode, resumed=$wasPlaying")

        // ??Widget??????????????Widget?????????????????????onMediaItemTransition
        if (currentSong != null) {
            try { MusicWidgetProvider.updateAllWidgets(this) } catch (_: Exception) {}
            try { MusicWidgetProvider3x2.updateAllWidgets(this) } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        Log.d(TAG, "onDestroy - Service being destroyed")

        // ???????Save????????????????????????
        savePlaybackState()
        // ?????????????????
        SongRepository.persistNow()

        // ??Steven ?????????????,?????????????��??????
        try {
            oboeDirectPlayer?.stop()
            oboeDirectPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "oboeDirect stop error: ${e.message}")
        }
        try {
            exoPlayer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop error: ${e.message}")
        }

        // [v7.113] �ͷ���Ƶ����
        abandonAudioFocus(this)

        instance = null
        visualizerManager.release()
        volumeGuard.unregister()

        // Cancel SharedPreferences ????
        settingsPrefsListener?.let {
            getSharedPreferences("settings", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it)
        }

        // [V8.x] Unregister USB DAC hotplug callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.unregisterAudioDeviceCallback(usbDacCallback)
            } catch (_: Exception) {}
        }

        // [v7.122] Unregister standby bucket listener
        try { unregisterStandbyBucketReceiver() } catch (_: Exception) {}

        // Cancel any pending Visualizer release
        handler.removeCallbacks(visualizerReleaseTask)

        // ??v5.39????? Equalizer
        EqualizerManager.release()

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved: task removed, isPlaying=${isPlaying()}")
        if (!isPlaying() || currentSong == null) {
            // ?????????????
            Log.d(TAG, "onTaskRemoved: not playing, stopping service")
            stopSelf()
        } else {
            // ???????????? Service ????????????? stopSelf()
            Log.d(TAG, "onTaskRemoved: still playing, keeping service alive")
        }
    }

    /**
     * Settings FFT ??????
     */
    fun setFftCallback(callback: ((ByteArray) -> Unit)?) {
        fftCallback = callback
        if (callback != null) visualizerManager.retry()
    }
    /**
     * ??v4.76??????Visualizer ????????????
     */
    fun isVisualizerReady(): Boolean = visualizerManager.isReady()

    // [v7.122] Register receiver to auto-map system standby bucket to idle_level
    private fun registerStandbyBucketReceiver() {
        if (standbyBucketReceiver != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                applyStandbyBucketToIdleLevel()
            }
        }
        try {
            // ACTION_APPLICATION_STANDBY_BUCKET_CHANGED exposed as SDK constant from API 31
            @Suppress("InlinedApi")
            val filter = IntentFilter("android.os.action.APPLICATION_STANDBY_BUCKET_CHANGED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                @Suppress("UnsafeRegisteredReceiver")
                registerReceiver(receiver, filter)
            }
            standbyBucketReceiver = receiver
            Log.d(TAG, "Standby bucket receiver registered")
            // Also apply immediately on registration
            applyStandbyBucketToIdleLevel()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register standby bucket receiver: ${e.message}")
        }
    }

    private fun unregisterStandbyBucketReceiver() {
        try {
            standbyBucketReceiver?.let {
                unregisterReceiver(it)
                standbyBucketReceiver = null
                Log.d(TAG, "Standby bucket receiver unregistered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister standby bucket receiver: ${e.message}")
        }
    }

    // [v7.122] Read system standby bucket and write corresponding idle_level
    private fun applyStandbyBucketToIdleLevel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
            val bucket = usm.appStandbyBucket
            val mappedLevel = when (bucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> null  // don't override, user may have set a preference
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "Working Set"
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "Frequent"
                UsageStatsManager.STANDBY_BUCKET_RARE -> "Rare"
                UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "Restricted"
                else -> "Rare"
            }
            if (mappedLevel != null) {
                val prefs = getSharedPreferences("sdw_music_prefs", MODE_PRIVATE)
                val current = prefs.getString("idle_level", "Rare") ?: "Rare"
                if (current != mappedLevel) {
                    prefs.edit().putString("idle_level", mappedLevel).apply()
                    Log.d(TAG, "Auto-set idle_level to $mappedLevel (system bucket=$bucket)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply standby bucket: ${e.message}")
        }
    }
}



