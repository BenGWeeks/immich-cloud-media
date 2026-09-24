package codes.dreaming.cloudmedia.picker

import kotlinx.coroutines.*
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PickerSupportTest {
    @get:Rule val temporary = TemporaryFolder()

    /** Rejects unknown types and verifies that narrow caller filters do not admit incompatible originals. */
    @Test fun unknownMimeIsNotInventedAsJpeg() {
        assertNull(imageMime(null))
        assertNull(imageMime(""))
        assertNull(imageMime("application/octet-stream"))
        assertNull(imageMime("image/*"))
        assertEquals("image/heic", imageMime("IMAGE/HEIC; charset=binary"))
        assertFalse(acceptsMime("image/heic", listOf("image/jpeg")))
        assertTrue(acceptsMime("image/heic", listOf("image/*")))
        assertTrue(acceptsMime("image/png", listOf("application/pdf", "image/png")))
    }

    /** Exercises skipped server page numbers so filtering cannot silently replace the server cursor with an increment. */
    @Test fun filteredPagesAreSkippedWithoutLosingTheServerCursor() = runBlocking {
        val requested = mutableListOf<Int>()
        val page = visiblePage(1) {
            requested.add(it)
            if (it == 1) PickerPage(emptyList(), 3) else PickerPage(listOf("png"), 4)
        }
        assertEquals(listOf(1, 3), requested)
        assertEquals(listOf("png"), page.items)
        assertEquals(4, page.nextPage)
    }

    /** Ensures an exhausted empty search does not keep offering more pages. */
    @Test fun noMatchesStopsAtEndOfLibrary() = runBlocking {
        val page = visiblePage<String>(1) { PickerPage(emptyList(), null) }
        assertTrue(page.items.isEmpty())
        assertNull(page.nextPage)
    }

    /** Limits work for narrow MIME filters while retaining a continuation for the next user request. */
    @Test fun emptyBatchIsBoundedAndRetainsContinuation() = runBlocking {
        var calls = 0
        val page = visiblePage<String>(1) { calls++; PickerPage(emptyList(), it + 1) }
        assertEquals(10, calls)
        assertEquals(11, page.nextPage)
    }

    /** Simulates a network failure and verifies that retry fetches the same page before advancing state. */
    @Test fun failedPageRetriesWithoutAdvancingCursor() = runBlocking {
        val paging = PickerPaging()
        paging.success(3)
        try {
            visiblePage<String>(paging.nextPage!!) { throw IOException("offline") }
            fail("Expected network failure")
        } catch (_: IOException) { paging.failure() }
        assertEquals(3, paging.nextPage)
        assertTrue(paging.failed)
        val result = visiblePage(paging.nextPage!!) { assertEquals(3, it); PickerPage(listOf("photo"), null) }
        paging.success(result.nextPage)
        assertNull(paging.nextPage)
        assertFalse(paging.failed)
        paging.reset()
        assertEquals(1, paging.nextPage)
    }

    /** Rejects a non-advancing server cursor before it can cause repeated requests. */
    @Test fun repeatedServerCursorFailsRatherThanLooping() = runBlocking {
        try {
            visiblePage<String>(2) { PickerPage(emptyList(), 2) }
            fail("Expected invalid cursor rejection")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    /** Uses a non-responsive server to verify cancellation releases a request awaiting headers. */
    @Test fun cancelBeforeHeadersCancelsTheNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            val job = launch { call.consume { it.body!!.string() } }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
        }
    }

    /** Uses a throttled body to verify cancellation remains connected after headers arrive. */
    @Test fun cancelWhileReadingBodyCancelsTheNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("abcdefgh").throttleBody(1, 1, TimeUnit.SECONDS))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            val reading = CountDownLatch(1)
            val job = launch { call.consume { reading.countDown(); it.body!!.string() } }
            withContext(Dispatchers.IO) { assertTrue(reading.await(5, TimeUnit.SECONDS)) }
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
        }
    }

    /** Ensures an authentication error body cannot be treated as a successful image response. */
    @Test fun failedHttpResponseIsNotReturnedAsAnImage() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))
            try {
                OkHttpClient().newCall(Request.Builder().url(server.url("/")).build()).consume { it.body!!.string() }
                fail("Expected authentication error")
            } catch (e: IOException) { assertEquals("HTTP 401", e.message) }
        }
    }

    /** Checks repeated cacheable responses still bypass storage when requesting original attachments. */
    @Test fun originalDownloadsDoNotPopulateSharedHttpCache() = runBlocking {
        MockWebServer().use { server ->
            Cache(temporary.newFolder("cache"), 1024 * 1024).use { cache ->
                val client = OkHttpClient.Builder().cache(cache).build()
                repeat(2) { server.enqueue(MockResponse().setHeader("Cache-Control", "private, max-age=3600").setBody("original")) }
                val request = Request.Builder().url(server.url("/original")).build().withoutDiskCache()
                repeat(2) {
                    val bytes = client.newCall(request).consume { response ->
                        assertNull(response.cacheResponse)
                        response.body!!.string()
                    }
                    assertEquals("original", bytes)
                }
                assertEquals(2, server.requestCount)
                assertFalse(cache.urls().hasNext())
            }
        }
    }
}
