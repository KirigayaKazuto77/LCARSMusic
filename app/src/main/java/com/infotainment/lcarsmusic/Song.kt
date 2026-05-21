package com.infotainment.lcarsmusic

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uriString: String,
    val folderName: String,
    val folderPath: String
) : Parcelable {
    
    val uri: Uri
        get() = Uri.parse(uriString)

    constructor(parcel: Parcel) : this(
        parcel.readLong(),
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readLong(),
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(id)
        parcel.writeString(title)
        parcel.writeString(artist)
        parcel.writeLong(duration)
        parcel.writeString(uriString)
        parcel.writeString(folderName)
        parcel.writeString(folderPath)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<Song> {
        override fun createFromParcel(parcel: Parcel): Song = Song(parcel)
        override fun newArray(size: Int): Array<Song?> = arrayOfNulls(size)
    }
}
