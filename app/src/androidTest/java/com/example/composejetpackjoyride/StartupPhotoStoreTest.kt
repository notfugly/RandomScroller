package com.example.composejetpackjoyride

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupPhotoStoreTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "startup-photo-store-test-${UUID.randomUUID()}-feed_startup"
    private val testContext = object : ContextWrapper(targetContext) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(preferenceName, mode)
    }
    private val preferences = testContext.getSharedPreferences("feed_startup", Context.MODE_PRIVATE)
    private val store = StartupPhotoStore(testContext)

    @After
    fun removeTestPreferences() {
        targetContext.deleteSharedPreferences(preferenceName)
    }

    @Test
    fun migratesLegacyPhotoWhenRememberingTheFirstNewPhoto() {
        assertTrue(
            preferences.edit()
                .putString("url", photoUrl(1))
                .putString("title", "Legacy")
                .commit()
        )
        assertEquals(listOf(photoUrl(1) to "Legacy"), store.load().map { it.imageUrl to it.title })

        store.remember(OpenversePhoto(photoUrl(2), "New"))

        val restored = StartupPhotoStore(testContext).load()
        assertEquals(
            listOf(photoUrl(2) to "New", photoUrl(1) to "Legacy"),
            restored.map { it.imageUrl to it.title }
        )
        assertEquals(photoUrl(2), preferences.getString("url", null))
        assertEquals("New", preferences.getString("title", null))
    }

    @Test
    fun keepsOnlyTheSixNewestDistinctPhotos() {
        for (number in 1..8) {
            store.remember(OpenversePhoto(photoUrl(number), "Photo $number"))
        }
        assertEquals(listOf(8, 7, 6, 5, 4, 3).map(::photoUrl), store.load().map { it.imageUrl })

        store.remember(OpenversePhoto(photoUrl(5), "Updated"))

        val restored = StartupPhotoStore(testContext).load()
        assertEquals(listOf(5, 8, 7, 6, 4, 3).map(::photoUrl), restored.map { it.imageUrl })
        assertEquals("Updated", restored.first().title)
    }

    @Test
    fun fallsBackToLegacyPhotoWhenTheArrayCannotSupplyAnyValidEntry() {
        val invalidArrays = listOf(
            "{unfinished",
            """[null, 7, {"url":"http://example.test/photo.jpg"}]"""
        )
        for (serialized in invalidArrays) {
            assertTrue(
                preferences.edit()
                    .putString("photos", serialized)
                    .putString("url", photoUrl(1))
                    .putString("title", "Legacy")
                    .commit()
            )
            assertEquals(listOf(photoUrl(1) to "Legacy"), store.load().map { it.imageUrl to it.title })
        }
    }

    @Test
    fun readsValidEntriesAlongsideInvalidAndDuplicateEntries() {
        assertTrue(
            preferences.edit()
                .putString(
                    "photos",
                    """
                    [
                        false,
                        {"url": "${photoUrl(1)}", "title": "First"},
                        {"url": "invalid", "title": "Invalid"},
                        {"url": "${photoUrl(1)}", "title": "Duplicate"},
                        {"url": "${photoUrl(2)}", "title": null}
                    ]
                    """.trimIndent()
                )
                .commit()
        )

        assertEquals(
            listOf(photoUrl(1) to "First", photoUrl(2) to ""),
            store.load().map { it.imageUrl to it.title }
        )
    }

    private fun photoUrl(number: Int): String = "https://example.test/photos/$number.jpg"
}
