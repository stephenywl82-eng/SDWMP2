package com.sdw.music.player.core.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object CoverFetcher {
    private const val TAG = "CoverFetcher"

    // Prevent duplicate downloads for the same artist+album
    private val pendingSet = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    sealed class Progress {
        object Searching : Progress()
        data class Downloading(val percent: Int) : Progress()
        data class Saved(val file: File) : Progress()
        data class Failed(val reason: String) : Progress()
    }

    fun getCacheDir(context: Context): File {
        val dir = File(context.externalCacheDir ?: context.cacheDir, "cover_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getCachedCover(context: Context, artist: String, album: String): File? {
        val file = File(getCacheDir(context), cachedName(artist, album))
        return file.takeIf { it.exists() && it.length() > 0 }
    }

    suspend fun fetchCover(
        context: Context,
        artist: String,
        album: String,
        onProgress: ((Progress) -> Unit)? = null
    ): File? {
        val key = "${artist}_${album}"

        val cached = getCachedCover(context, artist, album)
        if (cached != null) {
            withContext(Dispatchers.Main) { onProgress?.invoke(Progress.Saved(cached)) }
            return cached
        }

        // Prevent concurrent downloads for same artist+album
        if (!pendingSet.add(key)) {
            Log.d(TAG, "Already downloading: $artist - $album, skipping")
            return null
        }

        try {
            withContext(Dispatchers.Main) { onProgress?.invoke(Progress.Searching) }

            // 1. iTunes Search API (no API key required)
            val imageUrl = searchItunes(artist, album)
                ?: searchMusicBrainz(artist, album) // 2. MusicBrainz CAA fallback

            if (imageUrl == null) {
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(Progress.Failed("No cover found"))
                }
                return null
            }

            val file = downloadImage(context, imageUrl, artist, album) { pct ->
                onProgress?.invoke(Progress.Downloading(pct))
            }
            if (file != null) {
                withContext(Dispatchers.Main) { onProgress?.invoke(Progress.Saved(file)) }
            } else {
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(Progress.Failed("Download failed"))
                }
            }
            return file
        } catch (e: Exception) {
            Log.w(TAG, "fetchCover failed: ${e.message}")
            withContext(Dispatchers.Main) {
                onProgress?.invoke(Progress.Failed("${e.message}"))
            }
            return null
        } finally {
            pendingSet.remove(key)
        }
    }

    fun fetchInBackground(
        context: Context,
        artist: String,
        album: String,
        onProgress: ((Progress) -> Unit)? = null,
        onResult: ((File?) -> Unit)? = null
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            val file = fetchCover(context, artist, album, onProgress)
            onResult?.invoke(file)
        }
    }

    // --- iTunes Search API (free, no key) ---
    private suspend fun searchItunes(artist: String, album: String): String? = withContext(Dispatchers.IO) {
        try {
            val term = URLEncoder.encode("$artist $album", "UTF-8")
            val url = "https://itunes.apple.com/search?term=$term&media=music&entity=album&limit=5"
            val json = httpGet(url) ?: return@withContext null
            val root = JSONObject(json)
            val results = root.optJSONArray("results") ?: return@withContext null
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val artworkUrl = item.optString("artworkUrl100", "")
                if (artworkUrl.isNotBlank()) {
                    // Request 600x600 instead of 100x100
                    return@withContext artworkUrl.replace("100x100bb", "600x600bb")
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "iTunes search failed: ${e.message}")
        }
        null
    }

    // --- MusicBrainz Cover Art Archive (free, no key) ---
    private suspend fun searchMusicBrainz(artist: String, album: String): String? = withContext(Dispatchers.IO) {
        try {
            // Search release on MusicBrainz
            val query = URLEncoder.encode("artist:\"$artist\" AND release:\"$album\"", "UTF-8")
            val searchUrl = "https://musicbrainz.org/ws/2/release/?query=$query&limit=3&fmt=json"
            val json = httpGet(searchUrl) ?: return@withContext null
            val root = JSONObject(json)
            val releases = root.optJSONArray("releases") ?: return@withContext null
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                val mbid = release.optString("id", "")
                if (mbid.isBlank()) continue
                // Get cover art from CAA
                val caaUrl = "https://coverartarchive.org/release/$mbid"
                val caaJson = httpGet(caaUrl) ?: continue
                val caaRoot = JSONObject(caaJson)
                val images = caaRoot.optJSONArray("images") ?: continue
                for (j in 0 until images.length()) {
                    val img = images.getJSONObject(j)
                    if (img.optBoolean("front", false)) {
                        val imageUrl = img.optString("image", "")
                        if (imageUrl.isNotBlank()) return@withContext imageUrl
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "MusicBrainz search failed: ${e.message}")
        }
        null
    }

    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "MotoMusicPro/7.0")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode != 200) {
                Log.d(TAG, "HTTP $url -> ${conn.responseCode}")
                return@withContext null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.d(TAG, "HTTP GET failed: ${e.message}")
            null
        }
    }

    private fun downloadImage(
        context: Context,
        url: String,
        artist: String,
        album: String,
        onProgress: ((Int) -> Unit)? = null
    ): File? {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "MotoMusicPro/7.0")
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            if (conn.responseCode != 200) return null

            val contentLength = conn.contentLength
            val file = File(getCacheDir(context), cachedName(artist, album))

            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    if (contentLength > 0) {
                        copyWithProgress(input, output, contentLength, onProgress)
                    } else {
                        input.copyTo(output)
                        onProgress?.invoke(100)
                    }
                }
            }
            Log.i(TAG, "Downloaded cover: $artist - $album -> ${file.length()} bytes")
            return file
        } catch (e: Exception) {
            Log.w(TAG, "Download failed: ${e.message}")
            return null
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: java.io.OutputStream,
        totalBytes: Int,
        onProgress: ((Int) -> Unit)?
    ) {
        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalRead = 0
        var lastPct = -1
        while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            totalRead += bytesRead
            val pct = (totalRead * 100 / totalBytes).coerceIn(0, 100)
            if (pct != lastPct) {
                lastPct = pct
                onProgress?.invoke(pct)
            }
        }
    }

    private fun cachedName(artist: String, album: String): String {
        val key = "${artist}_${album}"
        val hash = key.toByteArray(Charsets.UTF_8)
            .fold(0L) { acc, b -> acc * 31 + b }
        return "cover_${hash.toString(16)}.jpg"
    }
}
