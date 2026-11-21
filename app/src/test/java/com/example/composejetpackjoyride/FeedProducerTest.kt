package com.example.composejetpackjoyride

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedProducerTest {
    @Test
    fun skipsMissingAndUndisplayableImages() = runBlocking {
        val searched = mutableListOf<String>()
        val appended = mutableListOf<String>()

        produceFeed(
            words = listOf("missing", "broken", "working"),
            awaitCapacity = {},
            search = { word ->
                searched.add(word)
                if (word == "missing") null else word
            },
            canDisplay = { it != "broken" },
            append = { appended.add(it) },
            onSearchError = { throw AssertionError(it) },
            wait = {}
        )

        assertEquals(listOf("missing", "broken", "working"), searched)
        assertEquals(listOf("working"), appended)
    }

    @Test
    fun retriesSameWordAfterRateLimit() = runBlocking {
        val searched = mutableListOf<String>()
        val appended = mutableListOf<String>()
        val waits = mutableListOf<Long>()

        produceFeed(
            words = listOf("cat"),
            awaitCapacity = {},
            search = { word ->
                searched.add(word)
                if (searched.size == 1) throw SearchRateLimitException(4_000L)
                word
            },
            canDisplay = { true },
            append = { appended.add(it) },
            onSearchError = {},
            wait = { waits.add(it) }
        )

        assertEquals(listOf("cat", "cat"), searched)
        assertEquals(listOf("cat"), appended)
        assertEquals(listOf(4_000L, 250L), waits)
    }

    @Test
    fun retriesSameWordAfterSearchFailure() = runBlocking {
        val searched = mutableListOf<String>()
        val appended = mutableListOf<String>()
        val waits = mutableListOf<Long>()

        produceFeed(
            words = listOf("cat"),
            awaitCapacity = {},
            search = { word ->
                searched.add(word)
                if (searched.size == 1) throw IllegalStateException("500")
                word
            },
            canDisplay = { true },
            append = { appended.add(it) },
            onSearchError = {},
            wait = { waits.add(it) }
        )

        assertEquals(listOf("cat", "cat"), searched)
        assertEquals(listOf("cat"), appended)
        assertEquals(listOf(3_000L, 250L), waits)
    }

    @Test
    fun waitsLongerWhenAccessIsDenied() = runBlocking {
        val waits = mutableListOf<Long>()
        var attempts = 0

        produceFeed(
            words = listOf("cat"),
            awaitCapacity = {},
            search = { word ->
                attempts++
                if (attempts == 1) throw SearchAccessDeniedException()
                word
            },
            canDisplay = { true },
            append = {},
            onSearchError = {},
            wait = { waits.add(it) }
        )

        assertEquals(2, attempts)
        assertEquals(listOf(60_000L, 250L), waits)
    }

    @Test
    fun waitsForCapacityBeforeSearching() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val searched = mutableListOf<String>()
        val job = launch {
            produceFeed(
                words = listOf("cat"),
                awaitCapacity = { gate.await() },
                search = { searched.add(it); it },
                canDisplay = { true },
                append = {},
                onSearchError = { throw AssertionError(it) },
                wait = {}
            )
        }

        yield()
        assertEquals(emptyList<String>(), searched)
        gate.complete(Unit)
        job.join()
        assertEquals(listOf("cat"), searched)
    }
}
