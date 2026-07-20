package com.sdw.music.player.core.audio

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private val buffer = mutableListOf<String>()
    private val maxLines = 200
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(tag: String, msg: String) {
        if (buffer.size >= maxLines) buffer.removeAt(0)
        buffer.add("${formatter.format(Date())} $tag  $msg")
    }

    fun addWithLogCat(tag: String, msg: String) {
        add(tag, msg)
        android.util.Log.d("SDW_$tag", msg)
    }

    fun get(): String = buffer.joinToString("\n")

    fun clear() { buffer.clear() }
}
