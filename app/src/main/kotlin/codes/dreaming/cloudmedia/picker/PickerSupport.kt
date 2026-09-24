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
        /** Propagates transport failures only while the caller is still awaiting a result. */
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        /** Closes every response and consumes its body before completing the cancellable continuation. */
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

/** Normalizes a concrete image MIME type; rejects absent, non-image and wildcard values. */
internal fun imageMime(value: String?): String? = value?.substringBefore(';')?.trim()?.lowercase()
    ?.takeIf { it.startsWith("image/") && it.substringAfter('/').matches(Regex("[a-z0-9.+-]+")) }

/** Matches a concrete MIME type against the calling app’s exact or wildcard filters. */
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
    /** Starts a new search at page one and clears the previous retry state. */
    fun reset() { nextPage = 1; failed = false }
    /** Commits the server’s next cursor after a successful fetch; null means the search is exhausted. */
    fun success(next: Int?) { nextPage = next; failed = false }
    /** Enables retry without advancing the cursor of the failed request. */
    fun failure() { failed = true }
}

/** Prevents original attachments from being duplicated in the shared HTTP disk cache. */
internal fun Request.withoutDiskCache(): Request = newBuilder()
    .cacheControl(CacheControl.Builder().noStore().build()).build()
