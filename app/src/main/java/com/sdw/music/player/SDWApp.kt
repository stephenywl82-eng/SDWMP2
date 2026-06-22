package com.sdw.music.player

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SDWApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize SongRepository with application context
        SongRepository.init(this)
        // Load persisted palette cache from disk
        MemoryManager.loadPaletteFromDisk(this)
    }
}