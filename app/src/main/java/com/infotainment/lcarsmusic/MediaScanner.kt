package com.infotainment.lcarsmusic

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

object MediaScanner {

    private const val PREFS_NAME = "MediaCachePrefs"
    private const val KEY_SONGS = "cached_songs"
    private const val TAG = "MediaScanner"

    suspend fun scanDevice(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val existingPaths = mutableSetOf<String>()

        // 1. Scan via MediaStore with filter
        scanMediaStore(context, songs, existingPaths, filterMusicOnly = true)

        // 2. If MediaStore query with IS_MUSIC filter produced 0 songs, retry without filter
        if (songs.isEmpty()) {
            scanMediaStore(context, songs, existingPaths, filterMusicOnly = false)
        }

        // 3. Direct File System Fallback Scan for files on storage (pushed via ADB or not indexed yet)
        val storageDir = Environment.getExternalStorageDirectory()
        val searchDirs = listOfNotNull(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            storageDir,
            File(storageDir, "Music"),
            File(storageDir, "Download"),
            File(storageDir, "Audio")
        ).distinct()

        for (dir in searchDirs) {
            scanDirectoryForAudioFiles(dir, songs, existingPaths, context)
        }

        Log.d(TAG, "Device scan completed. Found ${songs.size} total songs.")
        return@withContext songs
    }

    private fun scanMediaStore(
        context: Context,
        songs: MutableList<Song>,
        existingPaths: MutableSet<String>,
        filterMusicOnly: Boolean
    ) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA
        )
        val selection = if (filterMusicOnly) {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} > 0"
        } else {
            null
        }

        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val durationColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = if (idColumn != -1) cursor.getLong(idColumn) else -1L
                    if (id == -1L) continue

                    val title = if (titleColumn != -1) cursor.getString(titleColumn) ?: "UNKNOWN TITLE" else "UNKNOWN TITLE"
                    val artist = if (artistColumn != -1) cursor.getString(artistColumn) ?: "UNKNOWN ARTIST" else "UNKNOWN ARTIST"
                    val duration = if (durationColumn != -1) cursor.getLong(durationColumn) else 0L
                    val data = if (dataColumn != -1) cursor.getString(dataColumn) else null

                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                    val folderName: String
                    val folderPath: String
                    if (!data.isNullOrEmpty()) {
                        existingPaths.add(data)
                        val file = File(data)
                        folderName = file.parentFile?.name ?: "ROOT"
                        folderPath = file.parentFile?.absolutePath ?: ""
                    } else {
                        folderName = "ROOT"
                        folderPath = ""
                    }

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
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore", e)
        }
    }

    private fun scanDirectoryForAudioFiles(
        dir: File,
        songs: MutableList<Song>,
        existingPaths: MutableSet<String>,
        context: Context
    ) {
        if (!dir.exists() || !dir.isDirectory) return

        val audioExtensions = setOf("mp3", "m4a", "flac", "wav", "aac", "ogg", "opus", "wma", "3gp")

        try {
            dir.walkTopDown()
                .maxDepth(4)
                .filter { file -> file.isFile && audioExtensions.contains(file.extension.lowercase()) }
                .forEach { file ->
                    val absolutePath = file.absolutePath
                    if (!existingPaths.contains(absolutePath)) {
                        existingPaths.add(absolutePath)
                        val title = file.nameWithoutExtension
                        val artist = file.parentFile?.name ?: "UNKNOWN ARTIST"
                        val folderName = file.parentFile?.name ?: "ROOT"
                        val folderPath = file.parentFile?.absolutePath ?: ""
                        val uri = Uri.fromFile(file)

                        var duration = 0L
                        var finalTitle = title
                        var finalArtist = artist

                        try {
                            val retriever = MediaMetadataRetriever()
                            retriever.setDataSource(file.absolutePath)
                            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            duration = durationStr?.toLongOrNull() ?: 0L
                            val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                            val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            retriever.release()

                            if (!metaTitle.isNullOrBlank()) finalTitle = metaTitle
                            if (!metaArtist.isNullOrBlank()) finalArtist = metaArtist
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed extracting metadata for ${file.name}", e)
                        }

                        songs.add(
                            Song(
                                id = absolutePath.hashCode().toLong(),
                                title = finalTitle,
                                artist = finalArtist,
                                duration = duration,
                                uriString = uri.toString(),
                                folderName = folderName,
                                folderPath = folderPath
                            )
                        )

                        runCatching {
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(file.absolutePath),
                                null,
                                null
                            )
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning directory ${dir.absolutePath}", e)
        }
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
