package com.example.composejetpackjoyride

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.ReportDrawnWhen
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject
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
        val startupPhotos = restoreStartupPhotos(applicationContext)
            .shareIn(lifecycleScope, SharingStarted.Eagerly, replay = StartupPhotoStore.MAX_PHOTOS)
        setContent { MainScroller(startupPhotos) }
    }
}

data class OpenversePhoto(
    val imageUrl: String,
    val title: String,
    val id: String = UUID.randomUUID().toString(),
    val cacheOnly: Boolean = false
)

private const val PREFETCH_COUNT = 8
private const val SEARCH_BATCH_SIZE = 5
private val FEED_BACKGROUND = Color(0xFF101929)

private fun previewRequest(context: Context, photo: OpenversePhoto) =
    ImageRequest.Builder(context)
        .data(photo.imageUrl)
        .size(480, 854)
        .scale(Scale.FIT)
        .precision(Precision.INEXACT)
        .apply { if (photo.cacheOnly) networkCachePolicy(CachePolicy.DISABLED) }
        .build()

private fun restoreStartupPhotos(context: Context): Flow<OpenversePhoto> = flow {
    val saved = StartupPhotoStore(context).load()
    val imageLoader = SingletonImageLoader.get(context)
    for (photo in saved) {
        val cached = photo.copy(cacheOnly = true)
        if (imageLoader.execute(previewRequest(context, cached)) is SuccessResult) {
            emit(cached)
        }
    }
}.flowOn(Dispatchers.IO)

@Composable
@Preview(showBackground = true, showSystemUi = true)
fun MainScroller() {
    MainScroller(startupPhotos = null)
}

@Composable
private fun MainScroller(startupPhotos: Flow<OpenversePhoto>?) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val photos = remember { mutableStateListOf<OpenversePhoto>() }
    val displayedUrls = remember { mutableSetOf<String>() }
    val pagerState = rememberPagerState(pageCount = { photos.size })
    val imageLoader = remember(context) { SingletonImageLoader.get(context) }

    var firstPhotoReady by remember { mutableStateOf(false) }
    if (!isPreview) {
        ReportDrawnWhen { firstPhotoReady }
    }

    LaunchedEffect(context, isPreview, startupPhotos) {
        if (isPreview) return@LaunchedEffect
        val appContext = context.applicationContext
        val startup = StartupPhotoStore(appContext)

        fun hasRoom(): Boolean {
            val current = pagerState.settledPage
                .coerceAtMost((photos.size - 1).coerceAtLeast(0))
            return photos.size < current + 1 + PREFETCH_COUNT
        }

        fun showPhoto(photo: OpenversePhoto): Boolean {
            if (!displayedUrls.add(photo.imageUrl)) return false
            photos.add(photo)
            return true
        }

        launch {
            val cached = startupPhotos ?: restoreStartupPhotos(appContext)
            cached.collect { photo ->
                if (hasRoom()) showPhoto(photo)
            }
        }

        val availableWords = withContext(Dispatchers.IO) { loadIndexedWords(context.assets) }
        produceFeed(
            words = availableWords,
            awaitCapacity = {
                snapshotFlow { hasRoom() }.first { it }
            },
            search = ::searchForPhotos,
            candidateKey = OpenversePhoto::imageUrl,
            canDisplay = { photo ->
                if (photo.imageUrl in displayedUrls) return@produceFeed false
                try {
                    val result = imageLoader.execute(previewRequest(context, photo))
                    if (result is ErrorResult) {
                        Log.w("Openverse", "Не удалось загрузить превью", result.throwable)
                    }
                    result is SuccessResult
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Openverse", "Не удалось загрузить изображение", e)
                    false
                }
            },
            append = { photo ->
                if (showPhoto(photo)) {
                    withContext(Dispatchers.IO) { startup.remember(photo) }
                }
            },
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
                        model = remember(context, photo.imageUrl, photo.cacheOnly) {
                            previewRequest(context, photo)
                        },
                        imageLoader = imageLoader,
                        contentDescription = photo.title,
                        contentScale = ContentScale.Fit,
                        onSuccess = {
                            if (page == 0) firstPhotoReady = true
                        },
                        onError = {
                            val index = photos.indexOfFirst { it === photo }
                            if (index >= 0) {
                                photos.removeAt(index)
                                displayedUrls.remove(photo.imageUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

private suspend fun searchForPhotos(word: String): List<OpenversePhoto> = withContext(Dispatchers.IO) {
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
            val item = results.optJSONObject(index) ?: continue
            val imageUrl = item.optString("thumbnail").toHttpUrlOrNull()?.takeIf { it.isHttps }
                ?: item.optString("url").toHttpUrlOrNull()?.takeIf { it.isHttps }
                ?: continue
            val title = item.optString("title", "")
            suitable.add(
                OpenversePhoto(
                    imageUrl = imageUrl.toString(),
                    title = title.takeUnless { it.isBlank() || it == "null" } ?: word
                )
            )
        }
        suitable.distinctBy(OpenversePhoto::imageUrl).shuffled().take(SEARCH_BATCH_SIZE)
    }
}
