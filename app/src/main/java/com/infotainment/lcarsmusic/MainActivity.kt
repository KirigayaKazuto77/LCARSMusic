package com.infotainment.lcarsmusic

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.util.*

class MainActivity : AppCompatActivity() {

    private companion object {
        const val PREF_VISUALIZER_MODE = "PREF_VISUALIZER_MODE"
        const val PREF_EQUALIZER_SELECTED_PRESET_INDEX = "PREF_EQUALIZER_SELECTED_PRESET_INDEX"
        const val PREF_EQUALIZER_BAND_PROGRESS = "PREF_EQUALIZER_BAND_PROGRESS"
        const val CUSTOM_PRESET_INDEX = -1
        const val FLAT_PRESET_INDEX = 0
    }

    private lateinit var audioManager: AudioManager
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var playbackStateManager: PlaybackStateManager
    private lateinit var visualizerView: LcarsVisualizerView
    
    private lateinit var tvViewTitle: TextView
    
    private lateinit var mainViewContainer: FrameLayout

    private lateinit var btnVisualizerFrequencyDomain: FrameLayout
    private lateinit var btnVisualizerTimeDomain: FrameLayout
    
    // Media Browser and Controller
    private lateinit var mediaBrowser: MediaBrowserCompat
    private var mediaController: MediaControllerCompat? = null
    private var isActivityForeground = false
    private var latestVisualizerSessionId = 0
    private var isScreenReceiverRegistered = false
    private var currentVisualizerMode = VisualizerMode.OFF
    private var currentSelectedPresetIndex = FLAT_PRESET_INDEX
    private var lastEqualizerStateAppliedSessionId = 0
    @Volatile
    private var requestedEqualizerSessionId = 0
    private val equalizerBandViews = mutableListOf<EqualizerBandView>()
    private val audioHandler = Handler(HandlerThread("AudioEffectThread").apply { start() }.looper)

    private enum class VisualizerMode {
        OFF,
        FREQUENCY_DOMAIN,
        TIME_DOMAIN
    }

    private data class EqualizerBandView(
        val band: Short,
        val levelLabel: TextView,
        val seekBar: SegmentedSeekBar
    )

    private data class EqualizerPreset(
        val index: Int,
        val label: String,
        val isHardwarePreset: Boolean
    )

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(MusicPlaybackService.VISUALIZER_LOG_TAG, "SCREEN_OFF_PAUSE_VISUALIZER")
                    visualizerView.pause()
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(
                        MusicPlaybackService.VISUALIZER_LOG_TAG,
                        "SCREEN_ON_RESUME_VISUALIZER foreground=$isActivityForeground sessionId=$latestVisualizerSessionId"
                    )
                    if (isActivityForeground) {
                        resumeVisualizerIfEnabled()
                    }
                }
            }
        }
    }

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, 1000)
        }
    }

    // RecyclerViews and Adapters
    private lateinit var rvMediaList: RecyclerView
    private lateinit var rvPlaylistList: RecyclerView
    private lateinit var songAdapter: SongAdapter
    private lateinit var playlistAdapter: PlaylistAdapter

    private var allSongs: List<Song> = emptyList()
    private var currentFolder: String? = null
    
    private var isMediaLoaded = false
    private var isMediaControllerConnected = false
    private var isRehydrated = false

    // Sub-view layouts
    private lateinit var layoutNowPlaying: View
    private lateinit var layoutLibrary: View
    private lateinit var layoutPlaylist: View
    private lateinit var layoutEqualizer: View

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val hasRecordAudio = permissions[Manifest.permission.RECORD_AUDIO]
            ?: hasRecordAudioPermission()
        Log.d(
            MusicPlaybackService.VISUALIZER_LOG_TAG,
            "PERMISSION_RESULT_RECORD_AUDIO granted=$hasRecordAudio"
        )

        if (permissions.all { it.value }) {
            initializeContent()
        } else {
            Toast.makeText(this, "PERMISSIONS REQUIRED FOR FULL FUNCTIONALITY", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sharedPreferences = getSharedPreferences("LCARS_PREFS", Context.MODE_PRIVATE)
        playbackStateManager = PlaybackStateManager(this)

        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, MusicPlaybackService::class.java),
            connectionCallbacks,
            null
        )

        initUI()
        registerScreenStateReceiver()
        checkPermissions()
    }
    
    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            mediaBrowser.sessionToken.also { token ->
                val controller = MediaControllerCompat(this@MainActivity, token)
                MediaControllerCompat.setMediaController(this@MainActivity, controller)
                mediaController = controller
                controller.registerCallback(mediaControllerCallback)
                
                // Initial update
                mediaControllerCallback.onMetadataChanged(controller.metadata)
                mediaControllerCallback.onPlaybackStateChanged(controller.playbackState)
                
                isMediaControllerConnected = true
                checkAndRehydrate()
            }
        }
    }

    private val mediaControllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            val sessionId = state?.extras?.getInt(MusicPlaybackService.EXTRA_AUDIO_SESSION_ID, 0) ?: 0
            if (sessionId > 0) {
                handleVisualizerAudioSession(sessionId, "playback_state")
            }

            when (state?.state) {
                PlaybackStateCompat.STATE_PLAYING -> {
                    if (currentVisualizerMode != VisualizerMode.OFF) {
                        visualizerView.onPlaybackResumed()
                    }
                }
                PlaybackStateCompat.STATE_PAUSED -> {
                    visualizerView.onPlaybackPaused()
                }
            }

            val playPauseBtn = findViewById<ImageButton>(R.id.btn_play_pause)
            if (state?.state == PlaybackStateCompat.STATE_PLAYING) {
                playPauseBtn.setImageResource(android.R.drawable.ic_media_pause)
                startProgressUpdates()
            } else {
                playPauseBtn.setImageResource(android.R.drawable.ic_media_play)
                stopProgressUpdates()
            }
        }

        override fun onShuffleModeChanged(shuffleMode: Int) {
            val btnShuffle = findViewById<ImageButton>(R.id.btn_shuffle)
            if (shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL) {
                btnShuffle.setColorFilter(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_light))
            } else {
                btnShuffle.clearColorFilter()
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val btnLoop = findViewById<ImageButton>(R.id.btn_loop)
            when (repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_ONE -> {
                    btnLoop.setImageResource(android.R.drawable.ic_menu_rotate) // Can use a specific icon if available
                    btnLoop.setColorFilter(ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_light))
                }
                PlaybackStateCompat.REPEAT_MODE_ALL -> {
                    btnLoop.setImageResource(android.R.drawable.ic_menu_rotate)
                    btnLoop.setColorFilter(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_light))
                }
                else -> {
                    btnLoop.setImageResource(android.R.drawable.ic_menu_rotate)
                    btnLoop.clearColorFilter()
                }
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            metadata?.let {
                val mediaId = it.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID)
                val title = it.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
                val artist = it.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)
                val album = it.getString(MediaMetadataCompat.METADATA_KEY_ALBUM)
                val playlist = it.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE)
                val trackDuration = it.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)
                val sessionId = it.getLong(MusicPlaybackService.EXTRA_AUDIO_SESSION_ID).toInt()
                val INSIGNIA_ROTATION_DURATION = 5000L

                if (sessionId > 0) {
                    handleVisualizerAudioSession(
                        sessionId,
                        "metadata",
                        forceVisualizerRebuild = true
                    )
                }

                layoutNowPlaying.findViewById<TextView>(R.id.tv_song_title).text = title?.uppercase()
                layoutNowPlaying.findViewById<TextView>(R.id.tv_artist_name).text = artist?.uppercase()
                layoutNowPlaying.findViewById<TextView>(R.id.tv_album_name).text = album?.uppercase()
                layoutNowPlaying.findViewById<TextView>(R.id.tv_playlist_name).text = "PLAYLIST: ${playlist?.uppercase()}"

                val albumArt = it.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
                val ivMediaArt = layoutNowPlaying.findViewById<ImageView>(R.id.iv_media_art)
                ivMediaArt.cameraDistance = 8000f

                if (albumArt != null) {
                    ivMediaArt.setImageBitmap(albumArt)
                    ivMediaArt.rotationY = 0f // Reset the 3D rotation when real art is present
                    ivMediaArt.clearAnimation()
                } else {
                    ivMediaArt.setImageResource(R.drawable.federation)

                    // Create an animator that targets the Y-axis rotation property
                    val flipAnimator = android.animation.ObjectAnimator.ofFloat(ivMediaArt, "rotationY", 0f, 360f)

                    // We use a literal value (3000L) to avoid declaring a 'duration' variable entirely.
                    // This property is a system-required setting for the animator.
                    flipAnimator.duration = 15000L

                    flipAnimator.repeatCount = android.animation.ValueAnimator.INFINITE
                    flipAnimator.interpolator = android.view.animation.LinearInterpolator()
                    flipAnimator.start()
                }

                val seekBar = findViewById<SeekBar>(R.id.playback_seekbar)
                seekBar.max = trackDuration.toInt()
                seekBar.progress = 0
                findViewById<TextView>(R.id.tv_playback_time).text = MediaScanner.formatDuration(0L)
                findViewById<TextView>(R.id.tv_track_duration).text = MediaScanner.formatDuration(trackDuration)

                // Update Library Highlight and Scroll
                mediaId?.toLongOrNull()?.let { id ->
                    songAdapter.updateHighlight(id)
                    val pos = songAdapter.getPositionForId(id)
                    if (pos != -1) {
                        rvMediaList.scrollToPosition(pos)
                    }
                }
            }
        }
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    private fun updateProgress() {
        mediaController?.playbackState?.let { state ->
            if (state.state == PlaybackStateCompat.STATE_PLAYING) {
                val currentPosition = state.position
                val seekBar = findViewById<SeekBar>(R.id.playback_seekbar)
                seekBar.progress = currentPosition.toInt()
                findViewById<TextView>(R.id.tv_playback_time).text = MediaScanner.formatDuration(currentPosition)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mediaBrowser.connect()
    }

    override fun onStop() {
        super.onStop()
        mediaBrowser.disconnect()
    }

    private fun initUI() {
        visualizerView = findViewById(R.id.visualizer_view)
        visualizerView.setAudioHandler(audioHandler)
        tvViewTitle = findViewById(R.id.tv_view_title)
        mainViewContainer = findViewById(R.id.main_view_container)

        // Inflate sub-views
        layoutNowPlaying = layoutInflater.inflate(R.layout.layout_now_playing, mainViewContainer, false)
        layoutLibrary = layoutInflater.inflate(R.layout.layout_library, mainViewContainer, false)
        layoutPlaylist = layoutInflater.inflate(R.layout.layout_playlist, mainViewContainer, false)
        layoutEqualizer = layoutInflater.inflate(R.layout.layout_equalizer, mainViewContainer, false)

        mainViewContainer.addView(layoutNowPlaying)
        mainViewContainer.addView(layoutLibrary)
        mainViewContainer.addView(layoutPlaylist)
        mainViewContainer.addView(layoutEqualizer)

        setupRecyclerViews()
        setupNavigation()
        setupMediaControls()
        setupVolumeControl()
        
        // Power button logic
        findViewById<Button>(R.id.btn_power).setOnClickListener {
            terminateApp()
        }

        btnVisualizerFrequencyDomain = findViewById(R.id.btn_visualizer_frequency_domain)
        btnVisualizerTimeDomain = findViewById(R.id.btn_visualizer_time_domain)

        btnVisualizerFrequencyDomain.setOnClickListener {
            val nextMode = if (currentVisualizerMode == VisualizerMode.FREQUENCY_DOMAIN) {
                VisualizerMode.OFF
            } else {
                VisualizerMode.FREQUENCY_DOMAIN
            }
            setVisualizerMode(nextMode)
        }

        btnVisualizerTimeDomain.setOnClickListener {
            val nextMode = if (currentVisualizerMode == VisualizerMode.TIME_DOMAIN) {
                VisualizerMode.OFF
            } else {
                VisualizerMode.TIME_DOMAIN
            }
            setVisualizerMode(nextMode)
        }

        setVisualizerMode(loadSavedVisualizerMode())
    }

    private fun setVisualizerMode(mode: VisualizerMode) {
        currentVisualizerMode = mode
        sharedPreferences.edit().putString(PREF_VISUALIZER_MODE, mode.name).apply()

        when (mode) {
            VisualizerMode.OFF -> {
                visualizerView.onPlaybackPaused()
                visualizerView.pause()
            }
            VisualizerMode.FREQUENCY_DOMAIN -> {
                visualizerView.mode = LcarsVisualizerView.Mode.FREQUENCY_DOMAIN
                resumeVisualizerIfEnabled()
            }
            VisualizerMode.TIME_DOMAIN -> {
                visualizerView.mode = LcarsVisualizerView.Mode.TIME_DOMAIN
                resumeVisualizerIfEnabled()
            }
        }

        // Active colors for selected visualizer buttons
        val activeContainerColor = Color.parseColor("#FFCC00") // Bright Yellow

        val frequencySelected = mode == VisualizerMode.FREQUENCY_DOMAIN
        val timeSelected = mode == VisualizerMode.TIME_DOMAIN

        // Frequency Domain Button: show active color only when selected, otherwise null to show XML defaults
        btnVisualizerFrequencyDomain.backgroundTintList = if (frequencySelected) {
            ColorStateList.valueOf(activeContainerColor)
        } else {
            null
        }

        // Time Domain Button: show active color only when selected, otherwise null to show XML defaults
        btnVisualizerTimeDomain.backgroundTintList = if (timeSelected) {
            ColorStateList.valueOf(activeContainerColor)
        } else {
            null
        }
    }

    private fun loadSavedVisualizerMode(): VisualizerMode {
        val savedMode = sharedPreferences.getString(PREF_VISUALIZER_MODE, null)
        return savedMode?.let {
            runCatching { VisualizerMode.valueOf(it) }.getOrNull()
        } ?: VisualizerMode.FREQUENCY_DOMAIN
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        val hasRecordAudio = hasRecordAudioPermission()
        Log.d(
            MusicPlaybackService.VISUALIZER_LOG_TAG,
            "PERMISSION_CHECK_RECORD_AUDIO granted=$hasRecordAudio"
        )

        if (missingPermissions.isEmpty()) {
            initializeContent()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun initializeContent() {
        lifecycleScope.launch {
            val cachedSongs = MediaScanner.loadMediaCache(this@MainActivity)
            if (cachedSongs.isNotEmpty()) {
                allSongs = cachedSongs
                
                val folders = allSongs.map { it.folderName }.distinct().sorted()
                playlistAdapter.updateFolders(folders)
                
                isMediaLoaded = true
                checkAndRehydrate()
            }
            
            switchView("NOW PLAYING")
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleVisualizerAudioSession(
        sessionId: Int,
        source: String,
        forceVisualizerRebuild: Boolean = false
    ) {
        Log.d(
            MusicPlaybackService.VISUALIZER_LOG_TAG,
            "UI_CAPTURED_AUDIO_SESSION_ID source=$source sessionId=$sessionId latest=$latestVisualizerSessionId forceVisualizerRebuild=$forceVisualizerRebuild"
        )

        latestVisualizerSessionId = sessionId
        initializeEqualizerForSession(sessionId, source)

        if (!hasRecordAudioPermission()) {
            Log.d(
                MusicPlaybackService.VISUALIZER_LOG_TAG,
                "VISUALIZER_SETUP_SKIPPED_MISSING_RECORD_AUDIO source=$source sessionId=$sessionId"
            )
            return
        }

        if (currentVisualizerMode == VisualizerMode.OFF) {
            Log.d(
                MusicPlaybackService.VISUALIZER_LOG_TAG,
                "VISUALIZER_SETUP_SKIPPED_MODE_OFF source=$source sessionId=$sessionId"
            )
            return
        }

        visualizerView.setAudioSessionId(sessionId, forceRebuild = forceVisualizerRebuild)
    }

    private fun initializeEqualizerForSession(sessionId: Int, source: String) {
        if (sessionId <= 0) return

        requestedEqualizerSessionId = sessionId
        audioHandler.post {
            if (requestedEqualizerSessionId != sessionId) {
                Log.d(
                    MusicPlaybackService.VISUALIZER_LOG_TAG,
                    "EQUALIZER_SETUP_SKIPPED_STALE_BEFORE_INIT source=$source sessionId=$sessionId requested=$requestedEqualizerSessionId"
                )
                return@post
            }

            val equalizer = EqualizerEngine.initialize(sessionId)
            if (equalizer == null || requestedEqualizerSessionId != sessionId) {
                Log.d(
                    MusicPlaybackService.VISUALIZER_LOG_TAG,
                    "EQUALIZER_SETUP_SKIPPED_UNAVAILABLE_OR_STALE source=$source sessionId=$sessionId requested=$requestedEqualizerSessionId"
                )
                return@post
            }

            applySavedEqualizerStateToHardwareIfNeeded(sessionId, equalizer)

            runOnUiThread {
                if (
                    latestVisualizerSessionId == sessionId &&
                    layoutEqualizer.visibility == View.VISIBLE
                ) {
                    setupEqualizerUI()
                }
            }
        }
    }

    private fun resumeVisualizerIfEnabled() {
        if (currentVisualizerMode == VisualizerMode.OFF) return

        visualizerView.resume()

        if (
            latestVisualizerSessionId > 0 &&
            visualizerView.activeSessionId != latestVisualizerSessionId &&
            hasRecordAudioPermission()
        ) {
            visualizerView.setAudioSessionId(latestVisualizerSessionId)
        }

        if (mediaController?.playbackState?.state == PlaybackStateCompat.STATE_PLAYING) {
            visualizerView.onPlaybackResumed()
        }
    }

    private fun registerScreenStateReceiver() {
        if (isScreenReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)
        isScreenReceiverRegistered = true
    }

    private fun unregisterScreenStateReceiver() {
        if (!isScreenReceiverRegistered) return

        unregisterReceiver(screenStateReceiver)
        isScreenReceiverRegistered = false
    }

    private fun setupNavigation() {
        findViewById<LinearLayout>(R.id.btn_now_playing).setOnClickListener { switchView("NOW PLAYING") }
        findViewById<LinearLayout>(R.id.btn_library).setOnClickListener { switchView("LIBRARY") }
        findViewById<LinearLayout>(R.id.btn_playlist).setOnClickListener { switchView("PLAYLIST") }
        findViewById<LinearLayout>(R.id.btn_equalizer).setOnClickListener { switchView("EQUALIZER") }
    }

    private fun switchView(viewName: String) {
        tvViewTitle.text = viewName.uppercase()
        updateNavigationSelection(viewName)

        layoutNowPlaying.visibility = View.GONE
        layoutLibrary.visibility = View.GONE
        layoutPlaylist.visibility = View.GONE
        layoutEqualizer.visibility = View.GONE

        when (viewName) {
            "NOW PLAYING" -> layoutNowPlaying.visibility = View.VISIBLE
            "LIBRARY" -> layoutLibrary.visibility = View.VISIBLE
            "PLAYLIST" -> layoutPlaylist.visibility = View.VISIBLE
            "EQUALIZER" -> {
                layoutEqualizer.visibility = View.VISIBLE
                setupEqualizerUI()
            }
        }
        
        sharedPreferences.edit().putString("LAST_VIEW", viewName).apply()
    }

    private fun updateNavigationSelection(viewName: String) {
        val selectedColor = ContextCompat.getColor(this, android.R.color.holo_orange_light)

        val navButtons = mapOf(
            "NOW PLAYING" to Pair(findViewById<View>(R.id.tombstone_now_playing), findViewById<FrameLayout>(R.id.label_container_now_playing)),
            "LIBRARY" to Pair(findViewById<View>(R.id.tombstone_library), findViewById<FrameLayout>(R.id.label_container_library)),
            "PLAYLIST" to Pair(findViewById<View>(R.id.tombstone_playlist), findViewById<FrameLayout>(R.id.label_container_playlist)),
            "EQUALIZER" to Pair(findViewById<View>(R.id.tombstone_equalizer), findViewById<FrameLayout>(R.id.label_container_equalizer))
        )

        navButtons.forEach { (name, views) ->
            val isSelected = name == viewName
            val (tombstone, labelContainer) = views

            if (isSelected) {
                // Apply selected color (orange) to both tombstone and label
                tombstone.backgroundTintList = ColorStateList.valueOf(selectedColor)
                labelContainer.backgroundTintList = ColorStateList.valueOf(selectedColor)
            } else {
                // Clear tints to show XML default colors
                tombstone.backgroundTintList = null
                labelContainer.backgroundTintList = null
            }
        }
    }

    private fun setupRecyclerViews() {
        // Library RecyclerView
        rvMediaList = layoutLibrary.findViewById(R.id.rv_media_list)
        rvMediaList.layoutManager = LinearLayoutManager(this)
        songAdapter = SongAdapter(emptyList()) { song ->
            val currentList = (rvMediaList.adapter as SongAdapter).getSongs()
            val index = currentList.indexOf(song)
            
            MediaRepository.currentQueue = currentList
            MediaRepository.currentQueueIndex = index
            MediaRepository.playlistName = currentFolder ?: "ALL SONGS"
            
            // Explicitly call PLAY when user clicks a song from the library
            songAdapter.updateHighlight(song.id)
            mediaController?.transportControls?.playFromUri(song.uri, null)
        }
        rvMediaList.adapter = songAdapter

        // Playlist RecyclerView
        rvPlaylistList = layoutPlaylist.findViewById(R.id.rv_playlist_list)
        rvPlaylistList.layoutManager = LinearLayoutManager(this)
        playlistAdapter = PlaylistAdapter(emptyList()) { folder ->
            currentFolder = if (folder == "ALL SONGS") null else folder

            updateLibraryView()
            switchView("LIBRARY")
        }
        rvPlaylistList.adapter = playlistAdapter

        // Scan Buttons
        layoutLibrary.findViewById<Button>(R.id.btn_scan_device).setOnClickListener {
            performScan()
        }
        layoutPlaylist.findViewById<Button>(R.id.btn_scan_playlists).setOnClickListener {
            performScan()
        }
    }

    private fun performScan() {
        lifecycleScope.launch {
            allSongs = MediaScanner.scanDevice(this@MainActivity)
            val folders = allSongs.map { it.folderName }.distinct().sorted()
            playlistAdapter.updateFolders(folders)
            
            isMediaLoaded = true
            checkAndRehydrate()
            
            MediaScanner.saveMediaCache(this@MainActivity, allSongs)
            
            Toast.makeText(this@MainActivity, "SCAN COMPLETE: ${allSongs.size} SONGS FOUND", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLibraryView() {
        val filteredSongs = if (currentFolder == null) {
            allSongs
        } else {
            allSongs.filter { it.folderName == currentFolder }
        }

        songAdapter.updateSongs(filteredSongs)

        val headerTitle = layoutLibrary.findViewById<TextView>(R.id.tv_library_header_title)
        headerTitle.text = (currentFolder ?: "ALL SONGS").uppercase()
    }

    private fun checkAndRehydrate() {
        if (isMediaLoaded && isMediaControllerConnected && !isRehydrated) {
            initializePlaybackState()
            isRehydrated = true
        }
    }

    private fun initializePlaybackState() {
        // Step A: Load last playlist and populate MediaRepository
        val lastPlaylist = playbackStateManager.getLastPlaylistName()
        currentFolder = if (lastPlaylist == "ALL SONGS") null else lastPlaylist
        
        updateLibraryView()
        
        val filteredSongs = if (currentFolder == null) {
            allSongs
        } else {
            allSongs.filter { it.folderName == currentFolder }
        }
        
        MediaRepository.currentQueue = filteredSongs
        MediaRepository.playlistName = lastPlaylist
        
        // Step B: Load last song and find index (0-based)
        val lastSongId = playbackStateManager.getLastSongId()
        if (lastSongId != -1L) {
            val index = filteredSongs.indexOfFirst { it.id == lastSongId }
            if (index != -1) {
                // EXPLICITLY set to index (e.g. 0 if it's the 1st song)
                MediaRepository.currentQueueIndex = index
                
                // Highlight in UI immediately
                songAdapter.updateHighlight(lastSongId)
                val pos = songAdapter.getPositionForId(lastSongId)
                if (pos != -1) {
                    rvMediaList.scrollToPosition(pos)
                }
            }
        }
        
        // Step C: Tell service to rehydrate metadata
        mediaController?.transportControls?.sendCustomAction(MusicPlaybackService.ACTION_REHYDRATE, null)
        
        // Force UI update from repository state for Now Playing view
        updateNowPlayingUIFromRepository()
    }

    private fun updateNowPlayingUIFromRepository() {
        val song = MediaRepository.getCurrentSong()
        if (song != null) {
            layoutNowPlaying.findViewById<TextView>(R.id.tv_song_title).text = song.title.uppercase()
            layoutNowPlaying.findViewById<TextView>(R.id.tv_artist_name).text = song.artist.uppercase()
            layoutNowPlaying.findViewById<TextView>(R.id.tv_album_name).text = "UNKNOWN ALBUM"
            layoutNowPlaying.findViewById<TextView>(R.id.tv_playlist_name).text = "PLAYLIST: ${MediaRepository.playlistName.uppercase()}"

            val seekBar = findViewById<SeekBar>(R.id.playback_seekbar)
            seekBar.max = song.duration.toInt()
            findViewById<TextView>(R.id.tv_track_duration).text = MediaScanner.formatDuration(song.duration)
        }
    }

    private fun setupMediaControls() {
        findViewById<ImageButton>(R.id.btn_play_pause).setOnClickListener {
            val playbackState = mediaController?.playbackState?.state
            if (playbackState == PlaybackStateCompat.STATE_PLAYING) {
                mediaController?.transportControls?.pause()
            } else {
                playSelectedOrFirstAvailableSong()
            }
        }
        findViewById<ImageButton>(R.id.btn_previous).setOnClickListener { mediaController?.transportControls?.skipToPrevious() }
        findViewById<ImageButton>(R.id.btn_next).setOnClickListener { mediaController?.transportControls?.skipToNext() }

        findViewById<ImageButton>(R.id.btn_shuffle).setOnClickListener {
            val currentMode = mediaController?.shuffleMode ?: PlaybackStateCompat.SHUFFLE_MODE_NONE
            val nextMode = if (currentMode == PlaybackStateCompat.SHUFFLE_MODE_NONE) {
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            } else {
                PlaybackStateCompat.SHUFFLE_MODE_NONE
            }
            mediaController?.transportControls?.setShuffleMode(nextMode)
        }

        findViewById<ImageButton>(R.id.btn_loop).setOnClickListener {
            val currentMode = mediaController?.repeatMode ?: PlaybackStateCompat.REPEAT_MODE_NONE
            val nextMode = when (currentMode) {
                PlaybackStateCompat.REPEAT_MODE_NONE -> PlaybackStateCompat.REPEAT_MODE_ALL
                PlaybackStateCompat.REPEAT_MODE_ALL -> PlaybackStateCompat.REPEAT_MODE_ONE
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            }
            mediaController?.transportControls?.setRepeatMode(nextMode)
        }

        val seekBar = findViewById<SeekBar>(R.id.playback_seekbar)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    findViewById<TextView>(R.id.tv_playback_time).text = MediaScanner.formatDuration(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                stopProgressUpdates()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.let {
                    mediaController?.transportControls?.seekTo(it.progress.toLong())
                    if (mediaController?.playbackState?.state == PlaybackStateCompat.STATE_PLAYING) {
                        startProgressUpdates()
                    }
                }
            }
        })
    }

    private fun playSelectedOrFirstAvailableSong() {
        val hasSelectedSong = MediaRepository.getCurrentSong() != null
        val queue = MediaRepository.currentQueue

        when {
            hasSelectedSong -> {
                mediaController?.transportControls?.play()
            }
            queue.isNotEmpty() -> {
                MediaRepository.currentQueueIndex = 0
                updateNowPlayingUIFromRepository()
                MediaRepository.getCurrentSong()?.let { song ->
                    songAdapter.updateHighlight(song.id)
                }
                mediaController?.transportControls?.play()
            }
            allSongs.isNotEmpty() -> {
                currentFolder = null
                MediaRepository.currentQueue = allSongs
                MediaRepository.currentQueueIndex = 0
                MediaRepository.playlistName = "ALL SONGS"
                updateLibraryView()
                updateNowPlayingUIFromRepository()
                MediaRepository.getCurrentSong()?.let { song ->
                    songAdapter.updateHighlight(song.id)
                }
                mediaController?.transportControls?.play()
            }
            else -> {
                findViewById<ImageButton>(R.id.btn_play_pause)
                    .setImageResource(android.R.drawable.ic_media_play)
                Toast.makeText(this, "NO SONGS AVAILABLE", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupVolumeControl() {
        val volumeSeekBar = findViewById<SeekBar>(R.id.volume_seekbar)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeSeekBar.max = maxVolume
        volumeSeekBar.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun setupEqualizerUI() {
        val btnPreset = layoutEqualizer.findViewById<Button>(R.id.btn_preset_selector)
        val btnReset = layoutEqualizer.findViewById<Button>(R.id.btn_reset_equalizer)
        val bandsContainer = layoutEqualizer.findViewById<LinearLayout>(R.id.equalizerBandsContainer)
        val equalizer = EqualizerEngine.activeEqualizer

        if (equalizer == null) {
            bandsContainer.removeAllViews()
            equalizerBandViews.clear()
            currentSelectedPresetIndex = sharedPreferences.getInt(
                PREF_EQUALIZER_SELECTED_PRESET_INDEX,
                FLAT_PRESET_INDEX
            )
            btnPreset.text = if (currentSelectedPresetIndex == CUSTOM_PRESET_INDEX) "CUSTOM" else "FLAT"
            btnPreset.setOnClickListener { view ->
                PopupMenu(this, view).apply {
                    menu.add("FLAT")
                    show()
                }
            }
            btnReset.setOnClickListener {
                currentSelectedPresetIndex = FLAT_PRESET_INDEX
                btnPreset.text = "FLAT"
                sharedPreferences.edit()
                    .putInt(PREF_EQUALIZER_SELECTED_PRESET_INDEX, FLAT_PRESET_INDEX)
                    .remove(PREF_EQUALIZER_BAND_PROGRESS)
                    .apply()
            }
            Log.d(
                MusicPlaybackService.VISUALIZER_LOG_TAG,
                "EQUALIZER_UI_ENGINE_UNAVAILABLE sessionId=${EqualizerEngine.activeSessionId}"
            )
            return
        }

        populateEqualizerBands(equalizer, bandsContainer, btnPreset)
        updateEqualizerUiFromSavedState(equalizer, btnPreset)
        
        btnPreset.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            val presets = buildEqualizerPresets(equalizer)
            val hardwarePresets = presets.filter { it.isHardwarePreset }

            if (hardwarePresets.isNotEmpty()) {
                hardwarePresets.forEach { preset ->
                    popup.menu.add(0, preset.index, preset.index, preset.label)
                }
            } else {
                popup.menu.add(0, FLAT_PRESET_INDEX, FLAT_PRESET_INDEX, "FLAT")
            }
            
            popup.setOnMenuItemClickListener { item ->
                btnPreset.text = item.title
                currentSelectedPresetIndex = item.itemId
                if (hardwarePresets.isNotEmpty()) {
                    runCatching {
                        equalizer.usePreset(item.itemId.toShort())
                        refreshEqualizerBandViews(equalizer)
                        saveEqualizerState()
                    }.onFailure { error ->
                        Log.w(
                            MusicPlaybackService.VISUALIZER_LOG_TAG,
                            "EQUALIZER_PRESET_FAILED preset=${item.itemId}",
                            error
                        )
                    }
                } else {
                    resetEqualizerBands(equalizer)
                    saveEqualizerState()
                }
                true
            }
            popup.show()
        }
        
        btnReset.setOnClickListener {
            btnPreset.text = "FLAT"
            currentSelectedPresetIndex = FLAT_PRESET_INDEX
            resetEqualizerBands(equalizer)
            saveEqualizerState()
        }
    }

    private fun buildEqualizerPresets(equalizer: Equalizer): List<EqualizerPreset> {
        val presets = mutableListOf(EqualizerPreset(CUSTOM_PRESET_INDEX, "CUSTOM", false))
        val numberOfPresets = runCatching { equalizer.numberOfPresets.toInt() }.getOrDefault(0)

        if (numberOfPresets > 0) {
            for (presetIndex in 0 until numberOfPresets) {
                val presetName = runCatching {
                    equalizer.getPresetName(presetIndex.toShort())
                }.getOrNull() ?: "PRESET ${presetIndex + 1}"
                presets.add(EqualizerPreset(presetIndex, presetName.uppercase(), true))
            }
        } else {
            presets.add(EqualizerPreset(FLAT_PRESET_INDEX, "FLAT", false))
        }

        return presets
    }

    private fun populateEqualizerBands(
        equalizer: Equalizer,
        bandsContainer: LinearLayout,
        presetButton: Button
    ) {
        bandsContainer.removeAllViews()
        equalizerBandViews.clear()

        val numberOfBands = runCatching { equalizer.numberOfBands.toInt() }.getOrElse { error ->
            Log.w(MusicPlaybackService.VISUALIZER_LOG_TAG, "EQUALIZER_BANDS_UNAVAILABLE", error)
            return
        }
        val bandLevelRange = runCatching { equalizer.bandLevelRange }.getOrElse { error ->
            Log.w(MusicPlaybackService.VISUALIZER_LOG_TAG, "EQUALIZER_LEVEL_RANGE_UNAVAILABLE", error)
            return
        }
        if (bandLevelRange.size < 2) {
            Log.w(MusicPlaybackService.VISUALIZER_LOG_TAG, "EQUALIZER_LEVEL_RANGE_INVALID")
            return
        }

        for (bandIndex in 0 until numberOfBands) {
            val band = bandIndex.toShort()
            val bandView = createEqualizerBandView(equalizer, band, bandLevelRange, presetButton)
            bandsContainer.addView(bandView)
        }

        Log.d(
            MusicPlaybackService.VISUALIZER_LOG_TAG,
            "EQUALIZER_UI_BANDS_RENDERED sessionId=${EqualizerEngine.activeSessionId} bands=$numberOfBands"
        )
    }

    private fun createEqualizerBandView(
        equalizer: Equalizer,
        band: Short,
        bandLevelRange: ShortArray,
        presetButton: Button
    ): LinearLayout {
        val currentLevel = runCatching { equalizer.getBandLevel(band) }.getOrDefault(0.toShort())
        val centerFrequency = runCatching { equalizer.getCenterFreq(band) }.getOrDefault(0)

        val levelLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(4)
            }
            text = formatBandLevel(currentLevel)
            setTextColor(Color.WHITE)
            textSize = 12f
        }

        val seekBar = SegmentedSeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(48), 0, 1f)
            progress = millibelsToProgress(currentLevel, bandLevelRange)
            segmentCount = 12
            activeColor = Color.parseColor("#04AA6D")
            inactiveColor = Color.parseColor("#1A3D2A")
            thumbColor = Color.WHITE
            onProgressChangedListener = { newProgress, fromUser ->
                if (fromUser) {
                    val newLevel = progressToMillibels(newProgress, bandLevelRange)
                    runCatching {
                        equalizer.setBandLevel(band, newLevel)
                        levelLabel.text = formatBandLevel(newLevel)
                        currentSelectedPresetIndex = CUSTOM_PRESET_INDEX
                        presetButton.text = "CUSTOM"
                        saveEqualizerState()
                    }.onFailure { error ->
                        Log.w(
                            MusicPlaybackService.VISUALIZER_LOG_TAG,
                            "EQUALIZER_SET_BAND_FAILED band=$band level=$newLevel",
                            error
                        )
                    }
                }
            }
        }

        val frequencyLabel = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(4)
            }
            text = formatCenterFrequency(centerFrequency)
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
        }

        equalizerBandViews.add(EqualizerBandView(band, levelLabel, seekBar))

        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                marginEnd = dpToPx(24)
            }
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(levelLabel)
            addView(seekBar)
            addView(frequencyLabel)
        }
    }

    private fun resetEqualizerBands(equalizer: Equalizer) {
        val bandLevelRange = runCatching { equalizer.bandLevelRange }.getOrElse { error ->
            Log.w(MusicPlaybackService.VISUALIZER_LOG_TAG, "EQUALIZER_RESET_RANGE_UNAVAILABLE", error)
            return
        }
        if (bandLevelRange.size < 2) return
        val flatLevel = 0.coerceIn(bandLevelRange[0].toInt(), bandLevelRange[1].toInt()).toShort()

        equalizerBandViews.forEach { bandView ->
            runCatching {
                equalizer.setBandLevel(bandView.band, flatLevel)
            }.onFailure { error ->
                Log.w(
                    MusicPlaybackService.VISUALIZER_LOG_TAG,
                    "EQUALIZER_RESET_BAND_FAILED band=${bandView.band}",
                    error
                )
            }
        }

        refreshEqualizerBandViews(equalizer)
    }

    private fun refreshEqualizerBandViews(equalizer: Equalizer) {
        val bandLevelRange = runCatching { equalizer.bandLevelRange }.getOrElse { error ->
            Log.w(MusicPlaybackService.VISUALIZER_LOG_TAG, "EQUALIZER_REFRESH_RANGE_UNAVAILABLE", error)
            return
        }
        if (bandLevelRange.size < 2) return

        equalizerBandViews.forEach { bandView ->
            runCatching {
                val level = equalizer.getBandLevel(bandView.band)
                bandView.levelLabel.text = formatBandLevel(level)
                bandView.seekBar.progress = millibelsToProgress(level, bandLevelRange)
            }.onFailure { error ->
                Log.w(
                    MusicPlaybackService.VISUALIZER_LOG_TAG,
                    "EQUALIZER_REFRESH_BAND_FAILED band=${bandView.band}",
                    error
                )
            }
        }
    }

    private fun saveEqualizerState() {
        val bandProgress = equalizerBandViews
            .sortedBy { it.band.toInt() }
            .joinToString(",") { bandView ->
                (bandView.seekBar.progress + 0.5f).toInt().coerceIn(0, 100).toString()
            }

        sharedPreferences.edit()
            .putInt(PREF_EQUALIZER_SELECTED_PRESET_INDEX, currentSelectedPresetIndex)
            .putString(PREF_EQUALIZER_BAND_PROGRESS, bandProgress)
            .apply()
    }

    private fun updateEqualizerUiFromSavedState(equalizer: Equalizer, presetButton: Button) {
        val savedProgressString = sharedPreferences.getString(PREF_EQUALIZER_BAND_PROGRESS, null)
        val savedProgress = parseSavedBandProgress(savedProgressString)

        if (savedProgressString == null) {
            currentSelectedPresetIndex = FLAT_PRESET_INDEX
            presetButton.text = "FLAT"
            refreshEqualizerBandViews(equalizer)
            return
        }

        currentSelectedPresetIndex = sharedPreferences.getInt(
            PREF_EQUALIZER_SELECTED_PRESET_INDEX,
            FLAT_PRESET_INDEX
        )
        refreshEqualizerBandViews(equalizer)
        presetButton.text = resolveEqualizerPresetLabel(equalizer, currentSelectedPresetIndex, savedProgress)
    }

    private fun applySavedEqualizerStateToHardwareIfNeeded(sessionId: Int, equalizer: Equalizer) {
        if (sessionId <= 0 || lastEqualizerStateAppliedSessionId == sessionId) return
        if (requestedEqualizerSessionId != sessionId) return

        val savedProgressString = sharedPreferences.getString(PREF_EQUALIZER_BAND_PROGRESS, null)
        val savedPresetIndex = sharedPreferences.getInt(
            PREF_EQUALIZER_SELECTED_PRESET_INDEX,
            FLAT_PRESET_INDEX
        )

        if (savedProgressString == null) {
            applyFlatEqualizerState(equalizer)
        } else {
            val savedProgress = parseSavedBandProgress(savedProgressString)
            if (savedProgress.isNotEmpty()) {
                applyBandProgressToEqualizer(equalizer, savedProgress)
            } else if (savedPresetIndex >= 0) {
                runCatching {
                    equalizer.usePreset(savedPresetIndex.toShort())
                }.onFailure { error ->
                    Log.w(
                        MusicPlaybackService.VISUALIZER_LOG_TAG,
                        "EQUALIZER_RESTORE_PRESET_FAILED preset=$savedPresetIndex",
                        error
                    )
                    applyFlatEqualizerState(equalizer)
                }
            } else {
                applyFlatEqualizerState(equalizer)
            }
        }

        lastEqualizerStateAppliedSessionId = sessionId
    }

    private fun parseSavedBandProgress(savedProgressString: String?): List<Float> {
        return savedProgressString
            ?.split(",")
            ?.mapNotNull { it.trim().toFloatOrNull()?.coerceIn(0f, 100f) }
            .orEmpty()
    }

    private fun applyBandProgressToEqualizer(equalizer: Equalizer, bandProgress: List<Float>) {
        val bandLevelRange = runCatching { equalizer.bandLevelRange }.getOrElse { error ->
            Log.w(MusicPlaybackService.VISUALIZER_LOG_TAG, "EQUALIZER_RESTORE_RANGE_UNAVAILABLE", error)
            return
        }
        if (bandLevelRange.size < 2) return

        val numberOfBands = runCatching { equalizer.numberOfBands.toInt() }.getOrElse { error ->
            Log.w(MusicPlaybackService.VISUALIZER_LOG_TAG, "EQUALIZER_RESTORE_BANDS_UNAVAILABLE", error)
            return
        }

        for (bandIndex in 0 until numberOfBands) {
            val progress = bandProgress.getOrNull(bandIndex) ?: 50f
            val level = progressToMillibels(progress, bandLevelRange)
            runCatching {
                equalizer.setBandLevel(bandIndex.toShort(), level)
            }.onFailure { error ->
                Log.w(
                    MusicPlaybackService.VISUALIZER_LOG_TAG,
                    "EQUALIZER_RESTORE_BAND_FAILED band=$bandIndex level=$level",
                    error
                )
            }
        }
    }

    private fun applyFlatEqualizerState(equalizer: Equalizer) {
        val bandLevelRange = runCatching { equalizer.bandLevelRange }.getOrElse { error ->
            Log.w(MusicPlaybackService.VISUALIZER_LOG_TAG, "EQUALIZER_FLAT_RANGE_UNAVAILABLE", error)
            return
        }
        if (bandLevelRange.size < 2) return

        val numberOfBands = runCatching { equalizer.numberOfBands.toInt() }.getOrElse { error ->
            Log.w(MusicPlaybackService.VISUALIZER_LOG_TAG, "EQUALIZER_FLAT_BANDS_UNAVAILABLE", error)
            return
        }
        val flatLevel = 0.coerceIn(bandLevelRange[0].toInt(), bandLevelRange[1].toInt()).toShort()

        for (bandIndex in 0 until numberOfBands) {
            runCatching {
                equalizer.setBandLevel(bandIndex.toShort(), flatLevel)
            }.onFailure { error ->
                Log.w(
                    MusicPlaybackService.VISUALIZER_LOG_TAG,
                    "EQUALIZER_FLAT_BAND_FAILED band=$bandIndex",
                    error
                )
            }
        }
    }

    private fun resolveEqualizerPresetLabel(
        equalizer: Equalizer,
        presetIndex: Int,
        bandProgress: List<Float>
    ): String {
        if (presetIndex == CUSTOM_PRESET_INDEX) return "CUSTOM"
        if (presetIndex == FLAT_PRESET_INDEX && bandProgress.all { it.toInt() == 50 }) return "FLAT"

        return buildEqualizerPresets(equalizer)
            .firstOrNull { it.index == presetIndex && it.isHardwarePreset }
            ?.label
            ?: "FLAT"
    }

    private fun millibelsToProgress(level: Short, bandLevelRange: ShortArray): Float {
        if (bandLevelRange.size < 2) return 50f

        val minLevel = bandLevelRange[0].toFloat()
        val maxLevel = bandLevelRange[1].toFloat()
        if (maxLevel <= minLevel) return 50f

        return ((level.toFloat() - minLevel) / (maxLevel - minLevel) * 100f).coerceIn(0f, 100f)
    }

    private fun progressToMillibels(progress: Float, bandLevelRange: ShortArray): Short {
        if (bandLevelRange.size < 2) return 0.toShort()

        val minLevel = bandLevelRange[0].toFloat()
        val maxLevel = bandLevelRange[1].toFloat()
        if (maxLevel <= minLevel) return 0.toShort()

        return (minLevel + (progress.coerceIn(0f, 100f) / 100f) * (maxLevel - minLevel))
            .toInt()
            .coerceIn(bandLevelRange[0].toInt(), bandLevelRange[1].toInt())
            .toShort()
    }

    private fun formatBandLevel(level: Short): String {
        val decibels = level / 100f
        return when {
            decibels > 0f -> "+${formatCompactNumber(decibels)} dB"
            decibels < 0f -> "${formatCompactNumber(decibels)} dB"
            else -> "0 dB"
        }
    }

    private fun formatCenterFrequency(centerFrequencyMilliHz: Int): String {
        val frequencyHz = centerFrequencyMilliHz / 1000
        return if (frequencyHz >= 1000) {
            "${formatCompactNumber(frequencyHz / 1000f)}k"
        } else {
            "$frequencyHz Hz"
        }
    }

    private fun formatCompactNumber(value: Float): String {
        return if (value % 1f == 0f) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun sendActionToService(action: String) {
        val intent = Intent(this, MusicPlaybackService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    private fun terminateApp() {
        sendActionToService(MusicPlaybackService.ACTION_STOP)
        finishAndRemoveTask()
        System.exit(0)
    }

    override fun onResume() {
        super.onResume()
        isActivityForeground = true
        Log.d(
            MusicPlaybackService.VISUALIZER_LOG_TAG,
            "ACTIVITY_FOREGROUND_RESUME_VISUALIZER sessionId=$latestVisualizerSessionId"
        )
        resumeVisualizerIfEnabled()
    }

    override fun onPause() {
        super.onPause()
        isActivityForeground = false
        Log.d(
            MusicPlaybackService.VISUALIZER_LOG_TAG,
            "ACTIVITY_BACKGROUND_PAUSE_VISUALIZER sessionId=$latestVisualizerSessionId"
        )
        visualizerView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterScreenStateReceiver()
        visualizerView.release()
        audioHandler.post {
            audioHandler.looper.quitSafely()
        }
    }
}
