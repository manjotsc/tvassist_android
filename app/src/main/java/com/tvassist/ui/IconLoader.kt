package com.tvassist.ui

import android.content.Context
import coil.ImageLoader
import coil.decode.SvgDecoder

/**
 * A shared Coil [ImageLoader] with the SVG decoder registered, used to render Iconify/MDI icons
 * (and SVG/PNG/JPG icon URLs) faithfully — unlike the old regex+PathParser parser which
 * mis-tessellated complex icons (e.g. mdi:vector-square's corner nodes).
 */
object IconLoader {
    @Volatile private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader =
        loader ?: synchronized(this) {
            loader ?: ImageLoader.Builder(context.applicationContext)
                .components { add(SvgDecoder.Factory()) }
                .build()
                .also { loader = it }
        }

    /** Build the Iconify SVG URL for an "mdi:home"-style name, or pass through an http(s) URL. */
    fun iconUrl(name: String): String =
        if (name.startsWith("http")) name
        else "https://api.iconify.design/${name.replaceFirst(':', '/')}.svg"
}
