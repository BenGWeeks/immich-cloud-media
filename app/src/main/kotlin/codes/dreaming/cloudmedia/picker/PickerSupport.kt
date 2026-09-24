package codes.dreaming.cloudmedia.picker

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Request
import okhttp3.CacheControl
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Keep cancellation connected while consuming the body, not just while waiting for headers. */
internal suspend fun <T> Call.consume(block: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if (!continuation.isActive) return
                    if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                    val result = block(it)
                    continuation.resume(result)
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}

internal fun imageMime(value: String?): String? = value?.substringBefore(';')?.trim()?.lowercase()
    ?.takeIf { it.startsWith("image/") && it.substringAfter('/').matches(Regex("[a-z0-9.+-]+")) }

internal fun acceptsMime(mime: String, filters: List<String>): Boolean = filters.any { filter ->
    val normalized = filter.lowercase()
    normalized == "*/*" || normalized == mime || normalized == "${mime.substringBefore('/')}/*"
}

internal data class PickerPage<T>(val items: List<T>, val nextPage: Int?)

/** Advance past filtered-out pages; cap each batch so a narrow filter cannot scan forever. */
internal suspend fun <T> visiblePage(
    firstPage: Int,
    fetch: suspend (Int) -> PickerPage<T>,
): PickerPage<T> {
    var cursor = firstPage
    repeat(10) {
        val result = fetch(cursor)
        require(result.nextPage == null || result.nextPage > cursor) { "Invalid pagination cursor" }
        if (result.items.isNotEmpty() || result.nextPage == null || it == 9) return result
        cursor = result.nextPage
    }
    error("Unreachable")
}

/** A failed request never advances the cursor, so retry requests exactly the same page. */
internal class PickerPaging {
    var nextPage: Int? = 1
        private set
    var failed = false
        private set
    fun reset() { nextPage = 1; failed = false }
    fun success(next: Int?) { nextPage = next; failed = false }
    fun failure() { failed = true }
}

internal fun Request.withoutDiskCache(): Request = newBuilder()
    .cacheControl(CacheControl.Builder().noStore().build()).build()
