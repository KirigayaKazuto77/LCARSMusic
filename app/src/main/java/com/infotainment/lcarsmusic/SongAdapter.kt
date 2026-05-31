package com.infotainment.lcarsmusic

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class SongAdapter(
    private var songs: List<Song>,
    private val onSongClicked: (Song) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    private var currentlyPlayingSongId: Long = -1

    class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvId: TextView = view.findViewById(R.id.tv_item_id)
        val tvTitle: TextView = view.findViewById(R.id.tv_item_title)
        val tvArtist: TextView = view.findViewById(R.id.tv_item_artist)
        val tvDuration: TextView = view.findViewById(R.id.tv_item_duration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        holder.tvId.text = (position + 1).toString()
        holder.tvTitle.text = song.title.uppercase()
        holder.tvArtist.text = song.artist.uppercase()
        holder.tvDuration.text = MediaScanner.formatDuration(song.duration)

        // Fetch the context and define our two color states
        val context = holder.itemView.context
        val defaultTextColor = ContextCompat.getColor(context, R.color.lcars_nemesis_evening)
        val activeTextColor = Color.WHITE

        if (song.id == currentlyPlayingSongId) {
            // Highlighted State: Dimmed Green Background + White Text
            holder.itemView.setBackgroundColor(0x3304AA6D)
            holder.tvId.setTextColor(activeTextColor)
            holder.tvTitle.setTextColor(activeTextColor)
            holder.tvArtist.setTextColor(activeTextColor)
            holder.tvDuration.setTextColor(activeTextColor)
        } else {
            // Default State: Transparent Background + LCARS Nemesis Evening Text
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.tvId.setTextColor(defaultTextColor)
            holder.tvTitle.setTextColor(defaultTextColor)
            holder.tvArtist.setTextColor(defaultTextColor)
            holder.tvDuration.setTextColor(defaultTextColor)
        }

        holder.itemView.setOnClickListener { onSongClicked(song) }
    }

    override fun getItemCount(): Int = songs.size

    fun getSongs(): List<Song> = songs

    fun getPositionForId(songId: Long): Int {
        return songs.indexOfFirst { it.id == songId }
    }

    fun updateHighlight(songId: Long) {
        currentlyPlayingSongId = songId
        notifyDataSetChanged()
    }

    fun updateSongs(newSongs: List<Song>) {
        songs = newSongs
        notifyDataSetChanged()
    }
}
