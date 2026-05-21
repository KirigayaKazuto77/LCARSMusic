package com.infotainment.lcarsmusic

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var audioManager: AudioManager
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var playbackStateManager: PlaybackStateManager
    private lateinit var visualizerView: LcarsVisualizerView
    
    private lateinit var tvViewTitle: TextView
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvCurrentDate: TextView
    
    private lateinit var mainViewContainer: FrameLayout
    
    // Media Browser and Controller
    private lateinit var mediaBrowser: MediaBrowserCompat
    private var mediaController: MediaControllerCompat? = null

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

    private val timeHandler = Handler(Looper.getMainLooper())
    private val timeRunnable = object : Runnable {
        override fun run() {
            updateTimeAndDate()
            timeHandler.postDelayed(this, 1000)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
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
                val duration = it.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)

                layoutNowPlaying.findViewById<TextView>(R.id.tv_song_title).text = title?.uppercase()
                layoutNowPlaying.findViewById<TextView>(R.id.tv_artist_name).text = artist?.uppercase()
                layoutNowPlaying.findViewById<TextView>(R.id.tv_album_name).text = album?.uppercase()
                layoutNowPlaying.findViewById<TextView>(R.id.tv_playlist_name).text = "PLAYLIST: ${playlist?.uppercase()}"

                val seekBar = findViewById<SeekBar>(R.id.playback_seekbar)
                seekBar.max = duration.toInt()
                seekBar.progress = 0
                findViewById<TextView>(R.id.tv_playback_time).text = MediaScanner.formatDuration(0L)
                findViewById<TextView>(R.id.tv_track_duration).text = MediaScanner.formatDuration(duration)

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
        tvViewTitle = findViewById(R.id.tv_view_title)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvCurrentDate = findViewById(R.id.tv_current_date)
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

        // Toggle visualizer mode
        findViewById<Button>(R.id.btn_toggle_visualizer_mode).setOnClickListener {
            visualizerView.mode = if (visualizerView.mode == LcarsVisualizerView.Mode.FREQUENCY_DOMAIN) {
                LcarsVisualizerView.Mode.TIME_DOMAIN
            } else {
                LcarsVisualizerView.Mode.FREQUENCY_DOMAIN
            }
        }
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
            startClock()
        }
    }

    private fun setupNavigation() {
        findViewById<Button>(R.id.btn_now_playing).setOnClickListener { switchView("NOW PLAYING") }
        findViewById<Button>(R.id.btn_library).setOnClickListener { switchView("LIBRARY") }
        findViewById<Button>(R.id.btn_playlist).setOnClickListener { switchView("PLAYLIST") }
        findViewById<Button>(R.id.btn_equalizer).setOnClickListener { switchView("EQUALIZER") }
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
        val defaultColor = ContextCompat.getColor(this, android.R.color.darker_gray)
        val selectedTextColor = ContextCompat.getColor(this, android.R.color.black)
        val defaultTextColor = ContextCompat.getColor(this, android.R.color.white)

        val navButtons = mapOf(
            "NOW PLAYING" to findViewById<Button>(R.id.btn_now_playing),
            "LIBRARY" to findViewById<Button>(R.id.btn_library),
            "PLAYLIST" to findViewById<Button>(R.id.btn_playlist),
            "EQUALIZER" to findViewById<Button>(R.id.btn_equalizer)
        )

        navButtons.forEach { (name, button) ->
            val isSelected = name == viewName
            button.isSelected = isSelected
            button.backgroundTintList = ColorStateList.valueOf(
                if (isSelected) selectedColor else defaultColor
            )
            button.setTextColor(if (isSelected) selectedTextColor else defaultTextColor)
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
            mediaController?.transportControls?.playFromUri(song.uri, null)
            switchView("NOW PLAYING")
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
        
        btnPreset.setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add("FLAT")
            popup.menu.add("POP")
            popup.menu.add("ROCK")
            popup.menu.add("JAZZ")
            
            popup.setOnMenuItemClickListener { item ->
                btnPreset.text = item.title
                // TODO: Apply actual EQ settings
                true
            }
            popup.show()
        }
        
        btnReset.setOnClickListener {
            btnPreset.text = "FLAT"
            // TODO: Reset EQ bands
        }
    }

    private fun sendActionToService(action: String) {
        val intent = Intent(this, MusicPlaybackService::class.java).apply {
            this.action = action
        }
        startService(intent)
    }

    private fun updateTimeAndDate() {
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)
        val dateFormat = SimpleDateFormat("MMM. dd, yyyy", Locale.US)
        val now = Date()
        
        tvCurrentTime.text = timeFormat.format(now).uppercase()
        tvCurrentDate.text = dateFormat.format(now).uppercase()
    }

    private fun startClock() {
        timeHandler.post(timeRunnable)
    }

    private fun terminateApp() {
        sendActionToService(MusicPlaybackService.ACTION_STOP)
        finishAndRemoveTask()
        System.exit(0)
    }

    override fun onResume() {
        super.onResume()
        visualizerView.resume()
    }

    override fun onPause() {
        super.onPause()
        visualizerView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        timeHandler.removeCallbacks(timeRunnable)
    }
}
