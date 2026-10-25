package com.arnoutpro.ipod3g

import android.net.Uri

/** One song from the device's MediaStore. */
class Track(
    @JvmField val id: Long,
    @JvmField val uri: Uri,
    @JvmField val title: String,
    @JvmField val artist: String,
    @JvmField val album: String,
    @JvmField val genre: String,
    @JvmField val composer: String,
    @JvmField val trackNo: Int,
    @JvmField val durationMs: Long,
    @JvmField val dateAdded: Long,
)
