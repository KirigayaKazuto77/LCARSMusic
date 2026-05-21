package com.infotainment.lcarsmusic

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object MediaScanner {

    private const val PREFS_NAME = "MediaCachePrefs"
    private const val KEY_SONGS = "cached_songs"

    fun scanDevice(context: Context): List<Song> {
        val songs = mutableListOf<Song>()
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )

        val query = context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )

        query?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "UNKNOWN TITLE"
                val artist = cursor.getString(artistColumn) ?: "UNKNOWN ARTIST"
                val duration = cursor.getLong(durationColumn)
                val data = cursor.getString(dataColumn)
                
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                
                val file = File(data)
                val folderName = file.parentFile?.name ?: "ROOT"
                val folderPath = file.parentFile?.absolutePath ?: ""

                songs.add(
                    Song(
                        id = id,
                        title = title,
                        artist = artist,
                        duration = duration,
                        uriString = uri.toString(),
                        folderName = folderName,
                        folderPath = folderPath
                    )
                )
            }
        }
        return songs
    }

    fun formatDuration(durationMs: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    suspend fun saveMediaCache(context: Context, songs: List<Song>) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = Gson().toJson(songs)
        prefs.edit().putString(KEY_SONGS, json).apply()
    }

    suspend fun loadMediaCache(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SONGS, null) ?: return@withContext emptyList<Song>()
        
        val type = object : TypeToken<List<Song>>() {}.type
        return@withContext Gson().fromJson<List<Song>>(json, type)
    }
}
