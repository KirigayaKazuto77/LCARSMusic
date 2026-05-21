package com.infotainment.lcarsmusic

import android.support.v4.media.session.PlaybackStateCompat
import java.util.*

/**
 * Singleton repository to hold the current playback queue and state.
 * This avoids passing large lists through Intent extras, preventing TransactionTooLargeException.
 * Methods are thread-safe to handle concurrent access from UI and Service threads.
 */
object MediaRepository {
    private var queue: List<Song> = emptyList()
    private var index: Int = -1
    private var name: String = "ALL SONGS"

    private var shuffleModeState: Int = PlaybackStateCompat.SHUFFLE_MODE_NONE
    private var repeatModeState: Int = PlaybackStateCompat.REPEAT_MODE_NONE
    private var shuffledIndices: List<Int> = emptyList()

    var currentQueue: List<Song>
        @Synchronized get() = queue
        @Synchronized set(value) {
            queue = value
            if (shuffleModeState != PlaybackStateCompat.SHUFFLE_MODE_NONE) {
                generateShuffleOrder()
            }
        }

    var currentQueueIndex: Int
        @Synchronized get() = index
        @Synchronized set(value) {
            index = value
        }

    var playlistName: String
        @Synchronized get() = name
        @Synchronized set(value) {
            name = value
        }

    var shuffleMode: Int
        @Synchronized get() = shuffleModeState
        @Synchronized set(value) {
            shuffleModeState = value
            if (value != PlaybackStateCompat.SHUFFLE_MODE_NONE) {
                generateShuffleOrder()
            }
        }

    var repeatMode: Int
        @Synchronized get() = repeatModeState
        @Synchronized set(value) {
            repeatModeState = value
        }

    @Synchronized
    private fun generateShuffleOrder() {
        if (queue.isEmpty()) {
            shuffledIndices = emptyList()
            return
        }
        val indices = queue.indices.toList()
        shuffledIndices = indices.shuffled()
        // If there's a current song, we might want it to be at the current position in shuffled list,
        // but for simplicity, we'll just start the sequence.
    }

    @Synchronized
    fun getCurrentSong(): Song? {
        if (index >= 0 && index < queue.size) {
            return queue[index]
        }
        return null
    }

    @Synchronized
    fun skipToNext() {
        if (queue.isEmpty()) return

        if (repeatModeState == PlaybackStateCompat.REPEAT_MODE_ONE) {
            // Stay on current index
            return
        }

        if (shuffleModeState != PlaybackStateCompat.SHUFFLE_MODE_NONE && shuffledIndices.isNotEmpty()) {
            val currentPosInShuffle = shuffledIndices.indexOf(index)
            index = if (currentPosInShuffle == -1 || currentPosInShuffle == shuffledIndices.size - 1) {
                shuffledIndices[0]
            } else {
                shuffledIndices[currentPosInShuffle + 1]
            }
        } else {
            index = (index + 1) % queue.size
        }
    }

    @Synchronized
    fun skipToPrevious() {
        if (queue.isEmpty()) return

        if (repeatModeState == PlaybackStateCompat.REPEAT_MODE_ONE) {
            // Stay on current index
            return
        }

        if (shuffleModeState != PlaybackStateCompat.SHUFFLE_MODE_NONE && shuffledIndices.isNotEmpty()) {
            val currentPosInShuffle = shuffledIndices.indexOf(index)
            index = if (currentPosInShuffle == -1 || currentPosInShuffle == 0) {
                shuffledIndices.last()
            } else {
                shuffledIndices[currentPosInShuffle - 1]
            }
        } else {
            index = if (index <= 0) {
                queue.size - 1
            } else {
                index - 1
            }
        }
    }
}
