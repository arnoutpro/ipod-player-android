package com.arnoutpro.ipod3g

import android.net.Uri

/** One song from the device's MediaStore. */
class Track(
    val id: Long,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val composer: String,
    val trackNo: Int,
    val durationMs: Long,
    val dateAdded: Long,
)
