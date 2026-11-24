package com.example.composejetpackjoyride

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SearchRateLimitException(val retryAfterMillis: Long) : Exception()
internal class SearchAccessDeniedException : Exception("Openverse отказал в доступе (403)")

private const val CANDIDATE_QUEUE_CAPACITY = 8
private const val DOWNLOAD_WORKERS = 2
private const val RECENT_CANDIDATE_LIMIT = 128

internal suspend fun <T : Any> produceFeed(
    words: List<String>,
    awaitCapacity: suspend () -> Unit,
    search: suspend (String) -> List<T>,
    candidateKey: (T) -> String,
    canDisplay: suspend (T) -> Boolean,
    append: suspend (T) -> Unit,
    onSearchError: (Exception) -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) }
): Unit = coroutineScope {
    val candidates = Channel<T>(CANDIDATE_QUEUE_CAPACITY)
    val appendMutex = Mutex()
    repeat(DOWNLOAD_WORKERS) {
        launch {
            for (candidate in candidates) {
                awaitCapacity()
                if (canDisplay(candidate)) {
                    appendMutex.withLock {
                        awaitCapacity()
                        append(candidate)
                    }
                }
            }
        }
    }

    val recentCandidates = LinkedHashSet<String>()
    var index = 0
    var errorDelayMillis = 3_000L
    var emptyDelayMillis = 250L
    try {
        while (index < words.size) {
            awaitCapacity()
            val batch = try {
                search(words[index])
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchRateLimitException) {
                onSearchError(e)
                wait(e.retryAfterMillis.coerceAtLeast(1_000L))
                continue
            } catch (e: SearchAccessDeniedException) {
                onSearchError(e)
                wait(60_000L)
                continue
            } catch (e: Exception) {
                onSearchError(e)
                // An API or connection error is not an empty search result.
                wait(errorDelayMillis)
                errorDelayMillis = (errorDelayMillis * 2).coerceAtMost(30_000L)
                continue
            }
            index++
            errorDelayMillis = 3_000L

            var queued = false
            for (candidate in batch) {
                if (!recentCandidates.add(candidateKey(candidate))) continue
                if (recentCandidates.size > RECENT_CANDIDATE_LIMIT) {
                    recentCandidates.remove(recentCandidates.first())
                }
                candidates.send(candidate)
                queued = true
            }

            if (queued) {
                emptyDelayMillis = 250L
                wait(250L)
            } else {
                wait(emptyDelayMillis)
                emptyDelayMillis = (emptyDelayMillis * 2).coerceAtMost(4_000L)
            }
        }
    } finally {
        candidates.close()
    }
}
