package com.example.composejetpackjoyride

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class StartupPhotoStore(context: Context) {
    private val preferences by lazy {
        context.applicationContext.getSharedPreferences("feed_startup", Context.MODE_PRIVATE)
    }

    @WorkerThread
    fun load(): List<OpenversePhoto> = synchronized(lock) {
        readPhotos()
    }

    @WorkerThread
    fun remember(photo: OpenversePhoto) {
        val url = normalizedUrl(photo.imageUrl) ?: return
        synchronized(lock) {
            val photos = listOf(photo.copy(imageUrl = url)) + readPhotos().filter { it.imageUrl != url }
            val stored = JSONArray()
            for (entry in photos.take(MAX_PHOTOS)) {
                stored.put(JSONObject().put(URL, entry.imageUrl).put(TITLE, entry.title))
            }
            val persisted = preferences.edit()
                .putString(PHOTOS, stored.toString())
                .putString(URL, url)
                .putString(TITLE, photo.title)
                .commit()
            if (!persisted) {
                Log.w("StartupPhotoStore", "Could not persist startup photos")
            }
        }
    }

    private fun readPhotos(): List<OpenversePhoto> {
        val settings = preferences.all
        val serialized = settings[PHOTOS] as? String
        val entries = try {
            if (serialized == null) JSONArray() else JSONArray(serialized)
        } catch (_: JSONException) {
            JSONArray()
        }
        val photos = mutableListOf<OpenversePhoto>()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val url = normalizedUrl(entry.opt(URL) as? String) ?: continue
            if (photos.none { it.imageUrl == url }) {
                photos.add(OpenversePhoto(url, (entry.opt(TITLE) as? String).orEmpty()))
            }
            if (photos.size == MAX_PHOTOS) break
        }
        if (photos.isEmpty()) {
            val legacyUrl = normalizedUrl(settings[URL] as? String)
            if (legacyUrl != null) {
                photos.add(OpenversePhoto(legacyUrl, (settings[TITLE] as? String).orEmpty()))
            }
        }
        return photos
    }

    private fun normalizedUrl(url: String?): String? =
        url?.toHttpUrlOrNull()?.takeIf { it.isHttps }?.toString()

    companion object {
        const val MAX_PHOTOS = 6
        private const val PHOTOS = "photos"
        private const val URL = "url"
        private const val TITLE = "title"
        private val lock = Any()
    }
}
