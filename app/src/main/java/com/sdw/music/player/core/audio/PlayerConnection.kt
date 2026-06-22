package com.sdw.music.player.core.audio

import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.sdw.music.player.MusicService
import com.sdw.music.player.Song
import com.sdw.music.player.SongRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 连接层：UI (ViewModel) ↔ MusicService (MediaSessionService)
 * 
 * 使用流程：
 * 1. UI 层创建 PlayerConnection 实例（或通过 Hilt 注入）
 * 2. connect() 建立 MediaController 连接
 * 3. UI 通过 setSongs() SettingsPlaylists，通过 playSong() 播放
 * 4. UI 通过 state flow 观察播放状态变化
 */
class PlayerConnection(private val context: Context) {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var connected = false
    private var pendingPlayIndex: Int = -1

    // 播放状态
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _currentSongIndex = MutableStateFlow(-1)
    val currentSongIndex: StateFlow<Int> = _currentSongIndex.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _songList = MutableStateFlow<List<Song>>(emptyList())
    val songList: StateFlow<List<Song>> = _songList.asStateFlow()

    // 进度更新 job
    private var positionJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (isPlaying) startPositionUpdates() else stopPositionUpdates()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                controller?.duration?.takeIf { it > 0 }?.let {
                    _durationMs.value = it
                }
            }
            if (state == Player.STATE_ENDED) {
                // 自动下一首（歌单为空则跳过）
                val size = _songList.value.size
                if (size > 0) {
                    val nextIndex = (_currentSongIndex.value + 1) % size
                    if (nextIndex > 0) playSong(nextIndex)
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            controller?.currentMediaItemIndex?.let { idx ->
                _currentSongIndex.value = idx
                if (idx in _songList.value.indices) {
                    _currentSong.value = _songList.value[idx]
                }
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleEnabled: Boolean) {
            _shuffleEnabled.value = shuffleEnabled
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
        }
    }

    /**
     * 连接 MediaController 到 MusicService
     * 幂等：已连接则跳过，防止重复创建 MediaController
     */
    fun connect() {
        if (connected && controller != null) return
        // 先断On旧连接，防止多实例残留
        disconnect()
        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            controller?.addListener(playerListener)
            connected = true
            // 如果有待播请求，执行
            if (pendingPlayIndex >= 0) {
                playSong(pendingPlayIndex)
                pendingPlayIndex = -1
            }
            // 同步初始状态
            controller?.let {
                _isPlaying.value = it.isPlaying
                _durationMs.value = it.duration.coerceAtLeast(0)
                _currentSongIndex.value = it.currentMediaItemIndex
                // 【v7.XX】重连时恢复当前歌曲（从Playlists或元数据）
                val restoredIdx = it.currentMediaItemIndex
                val songs = _songList.value
                if (restoredIdx in songs.indices) {
                    _currentSong.value = songs[restoredIdx]
                } else {
                    val mediaItem = it.currentMediaItem
                    if (mediaItem != null) {
                        _currentSong.value = Song(
                            id = mediaItem.mediaId?.toLongOrNull() ?: -1L,
                            title = mediaItem.mediaMetadata.title?.toString() ?: "",
                            artist = mediaItem.mediaMetadata.artist?.toString() ?: "",
                            album = mediaItem.mediaMetadata.albumTitle?.toString() ?: "",
                            duration = it.duration,
                            path = mediaItem.localConfiguration?.uri?.toString() ?: "",
                            albumArtUri = mediaItem.mediaMetadata.artworkUri?.toString() ?: ""
                        )
                    }
                }
                _shuffleEnabled.value = it.shuffleModeEnabled
                _repeatMode.value = it.repeatMode
                if (it.isPlaying) startPositionUpdates()
                // 【v7.XX】重连时尝试恢复Playlists
                if (_songList.value.isEmpty() && it.mediaItemCount > 0) {
                    val recoveredSongs = mutableListOf<Song>()
                    for (i in 0 until it.mediaItemCount) {
                        val mi = it.getMediaItemAt(i)
                        if (mi != null) {
                            recoveredSongs.add(Song(
                                id = mi.mediaId?.toLongOrNull() ?: i.toLong(),
                                title = mi.mediaMetadata.title?.toString() ?: "",
                                artist = mi.mediaMetadata.artist?.toString() ?: "",
                                album = mi.mediaMetadata.albumTitle?.toString() ?: "",
                                duration = it.duration,  // approximate
                                path = mi.localConfiguration?.uri?.toString() ?: "",
                                albumArtUri = mi.mediaMetadata.artworkUri?.toString() ?: ""
                            ))
                        }
                    }
                    if (recoveredSongs.isNotEmpty()) {
                        _songList.value = recoveredSongs
                    }
                }
                // 【修复】进程被杀后 Service 已重建 → controller 无 mediaItem
                // 从 SharedPreferences 恢复播放状态并重新 setMediaItems
                // 【关键修复】延迟 500ms 等 MediaController 完成初始同步
                if (it.mediaItemCount == 0 && _songList.value.isNotEmpty()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (controller?.isConnected == true && controller?.mediaItemCount == 0 && controller?.isPlaying == false && _songList.value.isNotEmpty()) {
                            restoreFromSavedState()
                        }
                    }, 500L)
                }
            }
        }, MoreExecutors.directExecutor())
    }

    /**
     * 断开连接
     * 【修复】不再调用 MediaController.releaseFuture()，保留底层连接。
     * 原因：MediaSessionService 检测到最后一个 Controller 断开会自动 stopSelf()，
     * 导致手势返回桌面时播放中断。保留连接让 Service 不会误判为「无客户端」而自毁。
     */
    fun disconnect() {
        if (!connected && controller == null) return
        stopPositionUpdates()
        try {
            controller?.removeListener(playerListener)
        } catch (e: Exception) {
            android.util.Log.w("PlayerConnection", "removeListener failed: ${e.message}")
        }
        controller = null
        controllerFuture = null
        connected = false
        pendingPlayIndex = -1
        // 不再 releaseFuture — 保持底层 MediaController 与 Service 的连接
        // 重连时 connect() 会创建新的 MediaController，旧的会随进程生命周期自然释放
    }

    /**
     * SettingsPlaylists（不立即播放）
     */
    fun setSongs(songs: List<Song>, updateGlobal: Boolean = true) {
        _songList.value = songs
        // 同步到 MusicService
        scope.launch {
            if (updateGlobal) SongRepository.setSongs(songs)
        }
    }

    /** 编辑歌曲元数据后同步 Connection 内的 Song 副本，防止 currentSong 回调覆盖新标题 */
    fun updateSongInList(songId: Long, newTitle: String, newArtist: String) {
        _songList.value = _songList.value.map {
            if (it.id == songId) it.copy(title = newTitle, artist = newArtist) else it
        }
        val cur = _currentSong.value
        if (cur != null && cur.id == songId) {
            _currentSong.value = cur.copy(title = newTitle, artist = newArtist)
        }
    }

    /**
     * 播放指定索引的歌曲
     */
    fun playSong(index: Int) {
        val songs = _songList.value
        if (index !in songs.indices) return
        if (!connected) {
            pendingPlayIndex = index
            return
        }
        // 【V7.82】Oboe模式下直接调用MusicService，绕过MediaController（ExoPlayer空闲不响应）
        if (MusicService.instance?.isOboeDirectMode() == true) {
            android.util.Log.d("PlayerConnection", "playSong: Oboe mode, delegating to MusicService")
            MusicService.instance?.playSong(index)
            _currentSongIndex.value = index
            _currentSong.value = songs[index]
            // 【V7.xx】Oboe模式ExoPlayer无状态变化，不会触发onIsPlayingChanged，手动启动进度更新
            _isPlaying.value = true
            startPositionUpdates()
            return
        }
        controller?.let { ctrl ->
            val mediaItems = songs.mapIndexed { i, song ->
                MediaItem.Builder()
                    .setUri(song.path)
                    .setMediaId(song.id.toString())
                    .setMediaMetadata(MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(song.albumArtUri?.let { android.net.Uri.parse(it) })
                        .build())
                    .build()
            }
            ctrl.setMediaItems(mediaItems, index, 0L)
            ctrl.prepare()
            ctrl.play()

            _currentSongIndex.value = index
            _currentSong.value = songs[index]
        }
    }

    /**
     * 播放/暂停切换
     */
    fun togglePlayPause() {
        // 【V7.82】Oboe模式下直接操作MusicService，绕过MediaController
        if (MusicService.instance?.isOboeDirectMode() == true) {
            val svc = MusicService.instance!!
            if (svc.isPlaying()) {
                svc.pause()
                _isPlaying.value = false
                stopPositionUpdates()
            } else {
                svc.resume()
                _isPlaying.value = true
                startPositionUpdates()
            }
            return
        }
        val ctrl = controller
        if (ctrl == null) {
            android.util.Log.w("PlayerConnection", "togglePlayPause: controller is null, ignoring")
            return
        }
        try {
            if (ctrl.isPlaying) ctrl.pause() else ctrl.play()
        } catch (e: Exception) {
            android.util.Log.e("PlayerConnection", "togglePlayPause failed", e)
        }
    }

    /**
     * Previous
     */
    fun skipToPrevious() {
        if (MusicService.instance?.isOboeDirectMode() == true) {
            MusicService.instance?.playPrevious()
            // 【V7.xx】Oboe切歌后手动启动进度更新
            // 同步更新 Connection 侧的歌曲索引（否则UI显示旧曲目信息，duration不对）
            val svc = MusicService.instance!!
            val newIdx = MusicService.currentIndex
            val songs = _songList.value
            if (newIdx in songs.indices) {
                _currentSongIndex.value = newIdx
                _currentSong.value = songs[newIdx]
                // 从 MusicService 获取真实 duration，覆盖旧缓存的 _durationMs
                val dur = svc.getDuration()
                if (dur > 0) _durationMs.value = dur
            }
            _isPlaying.value = true
            startPositionUpdates()
            return
        }
        controller?.seekToPreviousMediaItem()
    }

    /**
     * 下一曲
     */
    fun skipToNext() {
        if (MusicService.instance?.isOboeDirectMode() == true) {
            MusicService.instance?.playNext()
            // 【V7.xx】Oboe切歌后手动启动进度更新
            // 同步更新 Connection 侧的歌曲索引（否则UI显示旧曲目信息，duration不对）
            val svc = MusicService.instance!!
            val newIdx = MusicService.currentIndex
            val songs = _songList.value
            if (newIdx in songs.indices) {
                _currentSongIndex.value = newIdx
                _currentSong.value = songs[newIdx]
                // 从 MusicService 获取真实 duration，覆盖旧缓存的 _durationMs
                val dur = svc.getDuration()
                if (dur > 0) _durationMs.value = dur
            }
            _isPlaying.value = true
            startPositionUpdates()
            return
        }
        controller?.seekToNextMediaItem()
    }

    /**
     * 跳转到指定位置（毫秒）
     */
    fun seekTo(positionMs: Long) {
        if (MusicService.instance?.isOboeDirectMode() == true) {
            MusicService.instance?.seekTo(positionMs)
            _currentPositionMs.value = positionMs
            return
        }
        controller?.seekTo(positionMs)
        _currentPositionMs.value = positionMs
    }

    /**
     * 切换Shuffle播放
     */
    fun setShuffleEnabled(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
    }

    /**
     * Settings循环模式
     */
    fun setRepeatMode(mode: Int) {
        controller?.repeatMode = mode
    }

    private var lastSaveTimeMs = 0L

    private fun startPositionUpdates() {
        stopPositionUpdates()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        positionJob = scope.launch {
            while (isActive) {
                val screenOn = pm.isInteractive
                if (screenOn) {
                    // 【V7.82】Oboe模式下从MusicService直接读取位置，而非MediaController（后者来自ExoPlayer）
                    val svc = MusicService.instance
                    val isOboe = svc?.isOboeDirectMode() == true
                    if (isOboe && svc != null) {
                        val pos = svc.getCurrentPosition()
                        val dur = svc.getDuration()
                        _currentPositionMs.value = pos
                        if (dur > 0) _durationMs.value = dur
                    } else {
                        controller?.let { ctrl ->
                            val pos = ctrl.currentPosition.coerceAtLeast(0)
                            val dur = ctrl.duration.coerceAtLeast(0)
                            _currentPositionMs.value = pos
                            if (dur > 0) _durationMs.value = dur
                        }
                    }
                    // Save播放位置用于进程恢复
                    val now = System.currentTimeMillis()
                    if (now - lastSaveTimeMs > 300_000L) {
                        try { MusicService.savePlaybackState() } catch (_: Exception) {}
                        lastSaveTimeMs = now
                    }
                    delay(1000) // 亮屏：每秒更新进度
                } else {
                    // 熄屏：只做save检查，跳过进度更新和StateFlow写入，减少Compose重组
                    val now = System.currentTimeMillis()
                    if (now - lastSaveTimeMs > 300_000L) {
                        try { MusicService.savePlaybackState() } catch (_: Exception) {}
                        lastSaveTimeMs = now
                    }
                    delay(2000) // 熄屏降低检查频率
                }
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    /**
     * 【修复】从 SharedPreferences 恢复播放状态（进程被杀后重启时使用）
     * Service 重建后 MediaController 无 mediaItem → 从Save的状态重建
     */
    private fun restoreFromSavedState() {
        try {
            val prefs = context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)
            val savedSongId = prefs.getLong("last_song_id", -1L)
            if (savedSongId <= 0) return
            val savedPos = prefs.getLong("last_position_ms", 0L)
            val wasPlaying = prefs.getBoolean("was_playing", false)
            val songs = _songList.value
            val index = songs.indexOfFirst { it.id == savedSongId }
            if (index < 0) return

            val song = songs[index]
            val mediaItems = songs.mapIndexed { i, s ->
                MediaItem.Builder()
                    .setUri(s.path)
                    .setMediaId(s.id.toString())
                    .setMediaMetadata(MediaMetadata.Builder()
                        .setTitle(s.title)
                        .setArtist(s.artist)
                        .setAlbumTitle(s.album)
                        .setArtworkUri(s.albumArtUri?.let { android.net.Uri.parse(it) })
                        .build())
                    .build()
            }
            val ctrl = controller ?: return
            ctrl.setMediaItems(mediaItems, index, savedPos)
            ctrl.prepare()
            _currentSongIndex.value = index
            _currentSong.value = song
            _durationMs.value = song.duration
            android.util.Log.d("PlayerConnection",
                "restoreFromSavedState: song=${song.title}, pos=$savedPos, wasPlaying=$wasPlaying")
            // 只在确实需要恢复播放时才调用 play()
            if (wasPlaying) {
                ctrl.play()
            }
        } catch (e: Exception) {
            android.util.Log.e("PlayerConnection", "restoreFromSavedState failed", e)
        }
    }

    /**
     * 清理资源（ViewModel onCleared 时调用）
     */
    fun release() {
        disconnect()
        scope.cancel()
    }
}

