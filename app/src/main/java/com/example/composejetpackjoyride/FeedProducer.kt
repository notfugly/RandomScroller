package com.example.composejetpackjoyride

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal class SearchRateLimitException(val retryAfterMillis: Long) : Exception()
internal class SearchAccessDeniedException : Exception("Openverse отказал в доступе (403)")

internal suspend fun <T : Any> produceFeed(
    words: List<String>,
    awaitCapacity: suspend () -> Unit,
    search: suspend (String) -> T?,
    canDisplay: suspend (T) -> Boolean,
    append: (T) -> Unit,
    onSearchError: (Exception) -> Unit,
    wait: suspend (Long) -> Unit = { delay(it) }
) {
    var index = 0
    var errorDelayMillis = 3_000L
    var emptyDelayMillis = 250L
    while (index < words.size) {
        awaitCapacity()
        try {
            val image = search(words[index])
            val loaded = image != null && canDisplay(image)
            if (loaded) append(requireNotNull(image))
            index++
            errorDelayMillis = 3_000L
            if (loaded) {
                emptyDelayMillis = 250L
                wait(250L)
            } else {
                wait(emptyDelayMillis)
                emptyDelayMillis = (emptyDelayMillis * 2).coerceAtMost(4_000L)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SearchRateLimitException) {
            onSearchError(e)
            wait(e.retryAfterMillis.coerceAtLeast(1_000L))
        } catch (e: SearchAccessDeniedException) {
            onSearchError(e)
            wait(60_000L)
        } catch (e: Exception) {
            onSearchError(e)
            // An API or connection error is not an empty search result.
            wait(errorDelayMillis)
            errorDelayMillis = (errorDelayMillis * 2).coerceAtMost(30_000L)
        }
    }
}
