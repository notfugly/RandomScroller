package com.example.composejetpackjoyride

import android.content.res.AssetManager
import java.nio.channels.FileChannel
import kotlin.random.Random

internal fun loadIndexedWords(assets: AssetManager): List<String> {
    val data = assets.openFd("random.words").use { asset ->
        asset.createInputStream().use { input ->
            input.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.length)
        }
    }

    return object : AbstractList<String>() {
        override val size = data.getInt(0)
        private val order = IntArray(size) { it }
        private var shuffledCount = 0

        override fun get(index: Int): String {
            if (index !in indices) throw IndexOutOfBoundsException("Word index: $index, size: $size")
            val wordIndex = synchronized(order) {
                // Shuffle only the requested prefix and preserve earlier choices for retries.
                while (shuffledCount <= index) {
                    val chosen = Random.nextInt(shuffledCount, size)
                    val word = order[chosen]
                    order[chosen] = order[shuffledCount]
                    order[shuffledCount] = word
                    shuffledCount++
                }
                order[index]
            }
            val offsetEntry = (wordIndex + 1) * Int.SIZE_BYTES
            val start = data.getInt(offsetEntry)
            val end = data.getInt(offsetEntry + Int.SIZE_BYTES)
            val bytes = ByteArray(end - start) { data.get(start + it) }
            return bytes.toString(Charsets.UTF_8)
        }
    }
}
