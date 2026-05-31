package com.infotainment.lcarsmusic

import android.content.Context
import android.content.SharedPreferences
import android.support.v4.media.session.PlaybackStateCompat

class PlaybackStateManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("LCARS_PLAYBACK_STATE", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_SONG_ID = "last_played_song_id"
        private const val KEY_LAST_PLAYLIST_NAME = "last_active_playlist_name"
        private const val KEY_SHUFFLE_MODE = "shuffle_mode"
        private const val KEY_REPEAT_MODE = "repeat_mode"
    }

    fun saveLastSong(id: Long) {
        prefs.edit().putLong(KEY_LAST_SONG_ID, id).apply()
    }

    fun saveLastPlaylist(name: String) {
        prefs.edit().putString(KEY_LAST_PLAYLIST_NAME, name).apply()
    }

    fun saveShuffleMode(mode: Int) {
        prefs.edit().putInt(KEY_SHUFFLE_MODE, mode).apply()
    }

    fun saveRepeatMode(mode: Int) {
        prefs.edit().putInt(KEY_REPEAT_MODE, mode).apply()
    }

    fun getLastSongId(): Long {
        return prefs.getLong(KEY_LAST_SONG_ID, -1L)
    }

    fun getLastPlaylistName(): String {
        return prefs.getString(KEY_LAST_PLAYLIST_NAME, "ALL SONGS") ?: "ALL SONGS"
    }

    fun getShuffleMode(): Int {
        return prefs.getInt(KEY_SHUFFLE_MODE, PlaybackStateCompat.SHUFFLE_MODE_NONE)
    }

    fun getRepeatMode(): Int {
        return prefs.getInt(KEY_REPEAT_MODE, PlaybackStateCompat.REPEAT_MODE_ALL)
    }
}
