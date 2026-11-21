package com.example.composejetpackjoyride

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components {
                    add(OkHttpNetworkFetcherFactory(callFactory = { AppNetwork.client }))
                }
                .build()
        }
        setContent { MainScroller() }
    }
}

data class OpenversePhoto(
    val imageUrl: String,
    val title: String,
    val id: String = UUID.randomUUID().toString()
)

private const val PREFETCH_COUNT = 8
private val FEED_BACKGROUND = Color(0xFF101929)

@Composable
@Preview(showBackground = true, showSystemUi = true)
fun MainScroller() {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val words by produceState<List<String>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            context.resources.openRawResource(R.raw.random_words)
                .bufferedReader()
                .use { it.readLines() }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .shuffled()
        }
    }
    val photos = remember { mutableStateListOf<OpenversePhoto>() }
    val pagerState = rememberPagerState(pageCount = { photos.size })
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }

    LaunchedEffect(words, isPreview) {
        val availableWords = words ?: return@LaunchedEffect
        if (isPreview) return@LaunchedEffect

        produceFeed(
            words = availableWords,
            awaitCapacity = {
                snapshotFlow {
                    val current = pagerState.settledPage
                        .coerceAtMost((photos.size - 1).coerceAtLeast(0))
                    photos.size < current + 1 + PREFETCH_COUNT
                }.first { it }
            },
            search = ::searchForPhoto,
            canDisplay = { photo ->
                try {
                    val request = ImageRequest.Builder(context)
                        .data(photo.imageUrl)
                        .size(720, 1280)
                        .build()
                    imageLoader.execute(request) is SuccessResult
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Openverse", "Не удалось загрузить изображение", e)
                    false
                }
            },
            append = { photos.add(it) },
            onSearchError = { Log.w("Openverse", "Ошибка поиска; повторим запрос позже", it) }
        )
    }

    Box(
        modifier = Modifier.fillMaxSize().background(FEED_BACKGROUND),
        contentAlignment = Alignment.Center
    ) {
        if (photos.isEmpty()) {
            if (!isPreview) CircularProgressIndicator()
        } else {
            VerticalPager(
                state = pagerState,
                key = { index -> photos[index].id },
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val photo = photos.getOrNull(page)
                if (photo != null) {
                    AsyncImage(
                        model = photo.imageUrl,
                        contentDescription = photo.title,
                        contentScale = ContentScale.Fit,
                        onError = {
                            val index = photos.indexOfFirst { it === photo }
                            if (index >= 0) photos.removeAt(index)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

suspend fun searchForPhoto(word: String): OpenversePhoto? = withContext(Dispatchers.IO) {
    val query = URLEncoder.encode(word, "UTF-8")
    val request = Request.Builder()
        .url("https://api.openverse.org/v1/images/?q=$query&page_size=20")
        .header("Accept", "application/json")
        .build()
    AppNetwork.client.newCall(request).execute().use { response ->
        when (val code = response.code) {
            200 -> Unit
            403 -> throw SearchAccessDeniedException()
            429 -> {
                val value = response.header("Retry-After")
                val seconds = value?.toLongOrNull()?.coerceAtLeast(1)
                val millis = if (seconds != null) {
                    seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
                } else {
                    val date = runCatching {
                        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                            .parse(value ?: "")?.time
                    }.getOrNull()
                    date?.let { (it - System.currentTimeMillis()).coerceAtLeast(1000) } ?: 60_000L
                }
                throw SearchRateLimitException(millis)
            }
            else -> throw IllegalStateException("Ошибка Openverse: $code")
        }
        val body = response.body?.string() ?: throw IllegalStateException("Пустой ответ Openverse")
        val results = JSONObject(body).getJSONArray("results")
        val suitable = mutableListOf<OpenversePhoto>()
        for (index in 0 until results.length()) {
            val item = results.getJSONObject(index)
            val imageUrl = item.optString("url", "")
            val title = item.optString("title", "")
            if (imageUrl.startsWith("https://")) {
                suitable.add(
                    OpenversePhoto(
                        imageUrl = imageUrl,
                        title = title.takeUnless { it.isBlank() || it == "null" } ?: word
                    )
                )
            }
        }
        suitable.randomOrNull()
    }
}
