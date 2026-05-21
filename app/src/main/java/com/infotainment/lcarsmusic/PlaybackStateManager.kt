package com.infotainment.lcarsmusic

import android.content.Context
import android.content.SharedPreferences

class PlaybackStateManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("LCARS_PLAYBACK_STATE", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_SONG_ID = "last_played_song_id"
        private const val KEY_LAST_PLAYLIST_NAME = "last_active_playlist_name"
    }

    fun saveLastSong(id: Long) {
        prefs.edit().putLong(KEY_LAST_SONG_ID, id).apply()
    }

    fun saveLastPlaylist(name: String) {
        prefs.edit().putString(KEY_LAST_PLAYLIST_NAME, name).apply()
    }

    fun getLastSongId(): Long {
        return prefs.getLong(KEY_LAST_SONG_ID, -1L)
    }

    fun getLastPlaylistName(): String {
        return prefs.getString(KEY_LAST_PLAYLIST_NAME, "ALL SONGS") ?: "ALL SONGS"
    }
}
