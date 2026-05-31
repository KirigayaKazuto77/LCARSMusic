package com.infotainment.lcarsmusic

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MusicPlaybackService : MediaBrowserServiceCompat() {

    private fun promoteToForegroundService() {
        val startIntent = Intent(this, MusicPlaybackService::class.java)
        ContextCompat.startForegroundService(this, startIntent)
    }

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var playbackStateManager: PlaybackStateManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    
    private lateinit var mediaSession: MediaSessionCompat
    private var currentPlayingUri: Uri? = null
    @Volatile
    private var latestAudioSessionId: Int = 0
    private val audioEffectHandler = Handler(
        HandlerThread("PlaybackAudioEffectThread").apply { start() }.looper
    )

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                restoreVolume()
                if (!isPlaying()) play()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                duckVolume()
            }
        }
    }

    private val CHANNEL_ID = "LCARS_MUSIC_PLAYBACK_CHANNEL"
    private val NOTIFICATION_ID = 1

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            var song = MediaRepository.getCurrentSong()
            if (song == null && MediaRepository.currentQueue.isNotEmpty()) {
                MediaRepository.currentQueueIndex = 0
                song = MediaRepository.getCurrentSong()
            }

            if (song != null) {
                if (song.uri == currentPlayingUri && mediaPlayer != null) {
                    // Resume existing track
                    promoteToForegroundService()
                    play()
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    updateNotification()
                } else {
                    // Start new track
                    prepareSong(song, PlaybackStateCompat.STATE_PAUSED)
                    startPreparedSong()
                }
            } else {
                handleEmptyPlayRequest()
            }
        }

        override fun onPlayFromUri(uri: Uri?, extras: Bundle?) {
            uri?.let { requestedUri ->
                val song = findSongByUri(requestedUri) ?: MediaRepository.getCurrentSong()?.takeIf {
                    it.uri == requestedUri
                }
                if (song != null) {
                    selectSong(song)
                    prepareSong(song, PlaybackStateCompat.STATE_PAUSED)
                    startPreparedSong()
                }
            }
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val songId = mediaId?.toLongOrNull() ?: return
            val song = findSongById(songId) ?: return
            selectSong(song)
            prepareSong(song, PlaybackStateCompat.STATE_PAUSED)
            startPreparedSong()
        }

        private fun startPreparedSong() {
            promoteToForegroundService()
            play() // Force play when explicitly requested by transport controls.
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            updateNotification()
        }

        private fun selectSong(song: Song) {
            val index = MediaRepository.currentQueue.indexOfFirst { it.id == song.id }
            if (index != -1) {
                MediaRepository.currentQueueIndex = index
            }
        }

        override fun onPause() {
            handlePauseAction()
        }

        override fun onSkipToNext() {
            if (MediaRepository.repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) {
                // Loop is enabled: Restart the current track
                mediaPlayer?.seekTo(0)
                play()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            } else {
                // Loop is disabled: Advance queue normally
                MediaRepository.skipToNext()
                onPlay()
            }
        }

        override fun onSkipToPrevious() {
            if (MediaRepository.repeatMode == PlaybackStateCompat.REPEAT_MODE_ONE) {
                // Loop is enabled: Restart the current track
                mediaPlayer?.seekTo(0)
                play()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            } else {
                // Loop is disabled: Reverse queue normally
                MediaRepository.skipToPrevious()
                onPlay()
            }
        }

        override fun onStop() {
            handleStopAction()
        }

        override fun onSetShuffleMode(shuffleMode: Int) {
            MediaRepository.shuffleMode = shuffleMode
            playbackStateManager.saveShuffleMode(shuffleMode)
            updatePlaybackState(if (isPlaying()) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
        }

        override fun onSetRepeatMode(repeatMode: Int) {
            MediaRepository.repeatMode = repeatMode
            playbackStateManager.saveRepeatMode(repeatMode)
            updatePlaybackState(if (isPlaying()) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
        }

        override fun onSeekTo(pos: Long) {
            mediaPlayer?.seekTo(pos.toInt())
            updatePlaybackState(if (isPlaying()) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            if (action == ACTION_REHYDRATE) {
                rehydrateMetadata()
            }
        }
    }

    private fun prepareSong(song: Song, targetState: Int, persist: Boolean = true) {
        if (requestAudioFocus()) {
            try {
                mediaPlayer?.reset()
                mediaPlayer?.setDataSource(this, song.uri)
                mediaPlayer?.prepare()
                currentPlayingUri = song.uri
                latestAudioSessionId = mediaPlayer?.audioSessionId ?: 0
                initializeEqualizerForSession(latestAudioSessionId)
                Log.d(
                    VISUALIZER_LOG_TAG,
                    "SERVICE_CAPTURED_AUDIO_SESSION_ID sessionId=$latestAudioSessionId title=${song.title}"
                )
                updateMetadata(song, MediaRepository.playlistName)
                updatePlaybackState(targetState)
                if (persist) {
                    persistPlaybackSelection(song)
                }
                updateNotification()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun initializeEqualizerForSession(sessionId: Int) {
        if (sessionId <= 0) return

        audioEffectHandler.post {
            if (latestAudioSessionId != sessionId) {
                Log.d(
                    VISUALIZER_LOG_TAG,
                    "SERVICE_EQUALIZER_INIT_SKIPPED_STALE sessionId=$sessionId latest=$latestAudioSessionId"
                )
                return@post
            }

            EqualizerEngine.initialize(sessionId)
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        playbackStateManager = PlaybackStateManager(this)
        initializeMediaPlayer()
        
        mediaSession = MediaSessionCompat(this, "MusicPlaybackService").apply {
            setCallback(mediaSessionCallback)
            isActive = true
        }
        sessionToken = mediaSession.sessionToken
        
        createNotificationChannel()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.sendResult(mutableListOf())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Still handle standard actions for buttons in notification
        val action = intent?.action
        when (action) {
            ACTION_PLAY -> mediaSessionCallback.onPlay()
            ACTION_PAUSE -> mediaSessionCallback.onPause()
            ACTION_PREVIOUS -> mediaSessionCallback.onSkipToPrevious()
            ACTION_NEXT -> mediaSessionCallback.onSkipToNext()
            ACTION_STOP -> mediaSessionCallback.onStop()
        }
        return START_STICKY
    }

    private fun handlePlayAction() {
        if (MediaRepository.getCurrentSong() == null) {
            handleEmptyPlayRequest()
            return
        }

        if (requestAudioFocus()) {
            play()
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            updateNotification()
        }
    }

    private fun handleEmptyPlayRequest() {
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        if (mediaSession.controller.metadata != null) {
            updateNotification()
        }
    }

    private fun handlePauseAction() {
        pause()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        updateNotification()
        stopForeground(false)
    }

    private fun handleStopAction() {
        stopForeground(true)
        stopSelf()
    }

    private fun rehydrateMetadata() {
        val song = MediaRepository.getCurrentSong()
        if (song != null) {
            prepareSong(song, PlaybackStateCompat.STATE_PAUSED, persist = false)
        } else {
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        }
    }

    private fun initializeMediaPlayer() {
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            setOnCompletionListener {
                mediaSessionCallback.onSkipToNext()
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun play() {
        try {
            mediaPlayer?.start()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        }
    }

    private fun pause() {
        try {
            mediaPlayer?.pause()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        }
    }

    private fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false

    private fun duckVolume() {
        mediaPlayer?.setVolume(0.2f, 0.2f)
    }

    private fun restoreVolume() {
        mediaPlayer?.setVolume(1.0f, 1.0f)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "LCARS Music Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(songTitle: String): Notification {
        val playPauseAction = if (isPlaying()) {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "PAUSE", getPendingIntent(ACTION_PAUSE))
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_play, "PLAY", getPendingIntent(ACTION_PLAY))
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(songTitle.uppercase())
            .setContentText("LCARS MUSIC PLAYER")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(android.R.drawable.ic_media_previous, "PREVIOUS", getPendingIntent(ACTION_PREVIOUS))
            .addAction(playPauseAction)
            .addAction(android.R.drawable.ic_media_next, "NEXT", getPendingIntent(ACTION_NEXT))
            .setStyle(MediaNotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateMetadata(song: Song, playlist: String) {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, song.id.toString())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "UNKNOWN ALBUM")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, playlist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
                .putLong(EXTRA_AUDIO_SESSION_ID, latestAudioSessionId.toLong())
                .build()
        )
        Log.d(
            VISUALIZER_LOG_TAG,
            "SERVICE_PUBLISHED_METADATA_AUDIO_SESSION_ID sessionId=$latestAudioSessionId"
        )
    }

    private fun findSongByUri(uri: Uri): Song? {
        return MediaRepository.currentQueue.firstOrNull { it.uri == uri }
    }

    private fun findSongById(songId: Long): Song? {
        return MediaRepository.currentQueue.firstOrNull { it.id == songId }
    }

    private fun persistPlaybackSelection(song: Song) {
        val playlistName = MediaRepository.playlistName
        if (playlistName.isBlank()) return

        serviceScope.launch(Dispatchers.IO) {
            playbackStateManager.saveLastSong(song.id)
            playbackStateManager.saveLastPlaylist(playlistName)
        }
    }

    private fun updatePlaybackState(state: Int) {
        val extras = Bundle().apply {
            putInt(EXTRA_AUDIO_SESSION_ID, latestAudioSessionId)
        }
        val builder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
                        PlaybackStateCompat.ACTION_SET_REPEAT_MODE
            )
            .setState(state, mediaPlayer?.currentPosition?.toLong() ?: 0L, 1.0f)
            .setExtras(extras)
        mediaSession.setPlaybackState(builder.build())
        mediaSession.setShuffleMode(MediaRepository.shuffleMode)
        mediaSession.setRepeatMode(MediaRepository.repeatMode)
        Log.d(
            VISUALIZER_LOG_TAG,
            "SERVICE_PUBLISHED_PLAYBACK_STATE_AUDIO_SESSION_ID sessionId=$latestAudioSessionId state=$state"
        )
    }

    private fun updateNotification() {
        val metadata = mediaSession.controller.metadata
        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: "SONG TITLE"
        
        val notification = createNotification(title)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mediaSession.release()
        audioEffectHandler.post {
            EqualizerEngine.release()
            audioEffectHandler.looper.quitSafely()
        }
        mediaPlayer?.release()
        mediaPlayer = null
        abandonAudioFocus()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(VISUALIZER_LOG_TAG, "APP_SWIPED_FROM_RECENTS: Terminating playback and service.")

        // This triggers your existing stop routine, drops the foreground notification,
        // and completely kills the background service.
        handleStopAction()
    }

    companion object {
        const val ACTION_PLAY = "com.infotainment.lcarsmusic.ACTION_PLAY"
        const val ACTION_PAUSE = "com.infotainment.lcarsmusic.ACTION_PAUSE"
        const val ACTION_PREVIOUS = "com.infotainment.lcarsmusic.ACTION_PREVIOUS"
        const val ACTION_NEXT = "com.infotainment.lcarsmusic.ACTION_NEXT"
        const val ACTION_STOP = "com.infotainment.lcarsmusic.ACTION_STOP"
        const val ACTION_REHYDRATE = "com.infotainment.lcarsmusic.ACTION_REHYDRATE"
        const val EXTRA_AUDIO_SESSION_ID = "com.infotainment.lcarsmusic.EXTRA_AUDIO_SESSION_ID"
        const val VISUALIZER_LOG_TAG = "LCARS_VISUALIZER"
    }
}
