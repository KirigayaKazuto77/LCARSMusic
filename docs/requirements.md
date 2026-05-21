Plan the following. Do not implement without my go-ahead signal:

Plan to create a music player for a tablet permanently fixed in landscape.
Make the touch targets large. All the characters in this app should be in UPPERCASE. This music app is going to be used in a car android head unit.
The app should be able to play in background.
For the UI development, separate each layout, major parts, sub-parts, and components in their own layout or drawable file.
There are to major parts - left panel and right panel.
The left panel will take up 100% of the height and 25% of the width of the screen. 
The right panel will take up 100% of the height and 75% of the width of the screen.

## The Left Panel

### The left panel consist 2 sub-parts - the "Audio Visualizer" panel and the "Navigation Menu".

#### 1. Audio Visualizer
- It will have its own layout/drawable file.
- It will take the whole width of its parent.
- It will take 30% height of its parent.
- It will be at the top of "Navigational Menu" panel.
- It will display the audio visualization of the current media being played by the app. 
- The audio visualization have two modes. It will only show one mode at a time. The user should be able to toggle between the two modes.
  1. Frequency Domain Mode: Uses FFT data to render a standard spectrum analyzer (unidirectional bars extending upward along the positive y-axis). 
  2. Time Domain Mode: Uses Waveform data to render a bidirectional waveform (bars extending symmetrically along both the positive and negative y-axes).
- **Performance Constraints:**
  - The `visualizer capture rate must be capped at a maximum of 30 frames per second to reduce CPU load.
  - The visualizer MUST pause processing entirely when the app is placed in the background or when the screen is turned off. It should only consume CPU cycles when actively visible on the screen.
  - Set the `capture size to a moderate power of 2 (e.g., 256 or 512) to balance visual fidelity with performance.

#### 2. Navigation Menu
- It will have its own layout/drawable file.
- It will take the whole width of its parent.
- It will take 70% height of its parent.
- It will be at the bottom of "Audio Visualizer" panel.
- It will consist of 4 menu items - Now Playing, Library, Playlist, and Equalizer.


## The Right Panel

### The Right panel consist 3 sub-parts - the "Title Bar", "Main View" panel, and the "Media Controls".

#### 1. Title Bar
- It will have its own layout/drawable file.
- It will take the whole width of its parent.
- It will take 17.5% height of its parent.
- This component will be at the top row of its parent.
- It will display the Title of the view displayed in the "Main View" panel based on the menu items in "Navigation Menu" ("Now Playing", "Library", "Playlist", and "Equalizer").
- It will also display the Time (i.e. 04:30 PM) and the Date (i.e. Dec. 04, 2026).
- The title, time, and date will only be displayed in a single row.

#### 2. Main View Panel
- It will have its own layout/drawable file.
- It will take the whole width of its parent.
- It will take 52.5% height of its parent.
- This component will be at the middle row of its parent.
- It will display the main views of the music player - Now Playing, Library, Playlist, and Equalizer

#### 3. Media Controls
- It will have its own layout/drawable file.
- It will take the whole width of its parent.
- It will take 30% height of its parent.
- This component will be at the bottom row of its parent.
- It will display the seek bar to display the progress of the current playback of the music file. It should also display the current playback time and the total track duration.
- It will also display the playback control buttons (shuffle, previous, play/pause, next, and loop).
- It will also display the volume control. This volume control should be able to change the device media volume.
- It will also display a power button. When the user clicks this, it should completely terminate the application (will be removed in the "Recent" list, dismiss the foreground notification for the background playback service).


## The Main Views

### The Main Views will consist of different view layouts on each of the menu items

#### 1. Now Playing
- It will have its own layout/drawable file.
- It will have 2 sub-parts - Media Art and the Media Details
- Media Art - This is located on the left side of its parent. 
  - It will have its own layout/drawable file.
- Media Details - This is located on the right side of its parent.
  - It may or may not have it own layout/drawable file.
  - It will display the song title, artist, library, etc.
  - Each details will be displayed in their own corresponding rows.

#### 2. Library
- It will have its own layout/drawable file.
- It will have 2 sub-parts - Header, and Media List
  - Header - It will contain the playlist title ("All Songs" or the playlist name) and a "Scan Device" button.
    - This will be fixed at the top part of its parent
    - When the user press the "Scan Device" button, it will scan the device for audio files and display all the songs in the "Media List"
  - Media List - It will display all the songs (sorted alphabetically) stored in the device internal storage and connected external storage (USB drives).
    - It will have its own layout/drawable file.
    - When the user clicks a song, it will play it. It will redirect the user to the "Now Playing" view.
    - The list is like a table without borders.
    - The column header of this table is Song ID (row number), Title, Artist, and Length (i.e. 00:05:30).
    - The column header of the table should be fixed and the list (data row) of the song should be scrollable.
    - The currently selected or playing song should be highlighted.

#### 3. Playlist
- It will have its own layout/drawable file.
- It will have 2 sub-parts - Header, and Playlist Body
  - Header - It will contain the title "Available Playlist" and a "Scan Device" button.
    - This will be fixed at the top part of its parent
    - When the user press the "Scan Device" button, it will scan the device for directories with audio files and display all those directories in the "Playlist" body.
  - Playlist - It will display all the folders (sorted alphabetically) that contains audio files stored in the device internal storage and connected external storage (USB drives).
    - It will have its own layout/drawable file.
    - When the user clicks a folder/directory, the user will be redirected to the "Library" view. The "Library" view will display the folder name at the "Header" and will display the songs located in that folder.
    - At the very first item of the list of directories/folders, there is a permanent item named "All Songs".
    - When the user clicks the "All Songs" item in the playlist body, the user will be redirected to the "Library" view. The "Library" view will display "All Songs" at the "Header" and will display all the songs in the device internal and external storage (USB drives).

#### 4. Equalizer
- It will have its own layout/drawable file.
- It will have 2 sub-parts - Header and the Equalizer Controls
  - Header - It will contain the title "Equalizer", a preset selector button, and a "Reset" button.
    - The preset selector button will display the name of the equalizer preset or "Custom" as its button label if the user manually adjusted the equalizer controls.
    - When the user pressed the preset selector button, there will be a popup menu that will display the list of options for equalizer presets. It will auto-dismiss once the user selects an option or press outside the popup.
    - When the user pressed the "Reset" button, the equalizer will be "flat" and the preset selector will auto-select "Flat".
  - Equalizer Controls - It will contain the equalizer controls using a customized vertical seek bar.
    - Use the custom seek bar implemented in `com/infotainment/lcarsmusic/Segmentedseekbar.kt` for the equalizer controls.
    - The sample usage of the custom vertical seek bar:
    ```
    <com.infotainment.lcarsmusic.SegmentedSeekBar
        android:id="@+id/seekBar"
        android:layout_width="48dp"
        android:layout_height="300dp"
        app:ssb_progress="50"
        app:ssb_segmentCount="12"
        app:ssb_activeColor="#04AA6D"
        app:ssb_inactiveColor="#1A3D2A"
        app:ssb_thumbColor="#FFFFFF"/>
    ```
    - Display the frequency at the bottom of corresponding custom seek bar and the value set by the user at the top of the custom seek bar.


## Other Behaviors
- When the user swipes out this app from the "Recent" list, it should completely terminate the app.
- Always save the playback state and configurations when the user reopens the app. 
- When the user reopens the app, the last played song and playlist should automatically be selected.
- The equalizer values should be saved when the user closes and reopens the app.


## OS Integration & Permissions

### 1. Audio Focus
- The app must request and handle Android Audio Focus.
- When a transient audio focus loss occurs (e.g., a GPS navigation announcement), the playback should not pause. Instead, it must "duck" (reduce the media volume temporarily) and restore full volume when the announcement ends.

### 2. Foreground Service & Lifecycle
- To prevent the Android OS from killing the app when the user switches to navigation or the home screen, background playback must be handled by a Foreground Service.
- The service must display a persistent notification containing basic playback controls (Previous, Play/Pause, Next) and the current song title.

### 3. Permissions
- The app must request the necessary runtime permissions gracefully upon startup:
     - `READ_EXTERNAL_STORAGE` (or `READ_MEDIA_AUDIO` for newer Android versions) to scan USB drives and internal storage.
     - `RECORD_AUDIO` permission, which is strictly required by the Android OS to access the audio session for the Visualizer.