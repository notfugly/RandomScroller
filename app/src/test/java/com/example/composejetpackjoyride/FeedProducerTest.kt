package com.example.composejetpackjoyride

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                if (word == "missing") emptyList() else listOf(word)
            },
            candidateKey = { it },
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
                listOf(word)
            },
            candidateKey = { it },
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
                listOf(word)
            },
            candidateKey = { it },
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
                listOf(word)
            },
            candidateKey = { it },
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
                search = { word ->
                    searched.add(word)
                    listOf(word)
                },
                candidateKey = { it },
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

    @Test
    fun usesOtherCandidatesFromSameSearchAndSkipsDuplicates() = runBlocking {
        val attempted = mutableListOf<String>()
        val appended = mutableListOf<String>()

        produceFeed(
            words = listOf("first", "second"),
            awaitCapacity = {},
            search = { word ->
                if (word == "first") {
                    listOf("broken", "one", "one", "two")
                } else {
                    listOf("one", "two", "three")
                }
            },
            candidateKey = { it },
            canDisplay = { candidate ->
                attempted.add(candidate)
                candidate != "broken"
            },
            append = { appended.add(it) },
            onSearchError = { throw AssertionError(it) },
            wait = {}
        )

        assertEquals(listOf("broken", "one", "three", "two"), attempted.sorted())
        assertEquals(listOf("one", "three", "two"), appended.sorted())
    }

    @Test
    fun queuedCandidatesContinueDownloadingWhileSearchWaitsToRetry() = runBlocking {
        withTimeout(5_000L) {
            val backoffStarted = CompletableDeferred<Unit>()
            val endBackoff = CompletableDeferred<Unit>()
            val batchAppended = CompletableDeferred<Unit>()
            val appended = mutableListOf<String>()
            var slowSearchAttempts = 0
            val job = launch {
                produceFeed(
                    words = listOf("initial", "slow"),
                    awaitCapacity = {},
                    search = { word ->
                        if (word == "initial") {
                            listOf("one", "two", "three")
                        } else {
                            slowSearchAttempts++
                            if (slowSearchAttempts == 1) throw SearchRateLimitException(4_000L)
                            emptyList()
                        }
                    },
                    candidateKey = { it },
                    canDisplay = {
                        backoffStarted.await()
                        true
                    },
                    append = {
                        appended.add(it)
                        if (appended.size == 3) batchAppended.complete(Unit)
                    },
                    onSearchError = { assertTrue(it is SearchRateLimitException) },
                    wait = { delayMillis ->
                        if (delayMillis == 4_000L) {
                            backoffStarted.complete(Unit)
                            endBackoff.await()
                        }
                    }
                )
            }

            batchAppended.await()
            assertEquals(1, slowSearchAttempts)
            assertEquals(listOf("one", "three", "two"), appended.sorted())
            endBackoff.complete(Unit)
            job.join()
            assertEquals(2, slowSearchAttempts)
        }
    }

    @Test
    fun limitsDownloadsAndPausesSearchWhenCandidateQueueFills() = runBlocking {
        withTimeout(5_000L) {
            val allowDownloads = CompletableDeferred<Unit>()
            val bothWorkersStarted = CompletableDeferred<Unit>()
            val words = List(20) { it.toString() }
            val searched = mutableListOf<String>()
            var activeDownloads = 0
            var peakDownloads = 0
            var completed = 0
            val job = launch {
                produceFeed(
                    words = words,
                    awaitCapacity = {},
                    search = { word ->
                        searched.add(word)
                        List(4) { "$word-$it" }
                    },
                    candidateKey = { it },
                    canDisplay = {
                        activeDownloads++
                        peakDownloads = maxOf(peakDownloads, activeDownloads)
                        if (activeDownloads == 2) bothWorkersStarted.complete(Unit)
                        allowDownloads.await()
                        activeDownloads--
                        true
                    },
                    append = { completed++ },
                    onSearchError = { throw AssertionError(it) },
                    wait = {}
                )
            }

            bothWorkersStarted.await()
            yield()
            assertTrue(searched.size <= 3)
            assertEquals(0, completed)
            allowDownloads.complete(Unit)
            job.join()
            assertEquals(2, peakDownloads)
            assertEquals(words, searched)
            assertEquals(80, completed)
        }
    }
}
