package com.infotainment.lcarsmusic

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PlaylistAdapter(
    private var folders: List<String>,
    private val onFolderClicked: (String) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.PlaylistViewHolder>() {

    class PlaylistViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tv_playlist_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return PlaylistViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        val folderName = if (position == 0) "ALL SONGS" else folders[position - 1]
        holder.tvName.text = folderName.uppercase()
        holder.itemView.setOnClickListener { onFolderClicked(folderName) }
    }

    override fun getItemCount(): Int = folders.size + 1 // +1 for "ALL SONGS"

    fun updateFolders(newFolders: List<String>) {
        folders = newFolders
        notifyDataSetChanged()
    }
}
