package codes.dreaming.cloudmedia.ui

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import codes.dreaming.cloudmedia.BuildConfig
import codes.dreaming.cloudmedia.R
import codes.dreaming.cloudmedia.network.ApiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import android.util.LruCache
import android.webkit.MimeTypeMap
import codes.dreaming.cloudmedia.picker.*

/** A user-driven GET_CONTENT picker; only the selected original gets a URI grant. */
class PhotoSelectionActivity : AppCompatActivity() {
    private data class Photo(val id: String, val name: String, val mime: String)
    private data class Attachment(val file: File, val mime: String)
    private val photos = mutableListOf<Photo>()
    private val thumbnails = Semaphore(4)
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var grid: GridView
    private lateinit var more: Button
    private lateinit var adapter: PhotoAdapter
    private var queryJob: Job? = null
    private var downloading = false
    private val paging = PickerPaging()
    private var loading = false
    private var loadGeneration = 0
    private var acceptedTypes = listOf("image/*")
    private val bitmapCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val thumbnailJobs = mutableSetOf<Job>()
    private val pickerClient by lazy {
        ApiClient.getClient().newBuilder().followRedirects(false).followSslRedirects(false).build()
    }
    private var query = ""
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        acceptedTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.takeIf { it.isNotEmpty() }
            ?.toList() ?: listOf(intent.type?.takeUnless { it == "vnd.android.cursor.dir/image" } ?: "image/*")
        acceptedTypes = acceptedTypes.filter { it == "*/*" || it.startsWith("image/", ignoreCase = true) }
        if (acceptedTypes.isEmpty()) { finish(); return }
        query = savedInstanceState?.getString("picker_query").orEmpty()
        ApiClient.initialize(this)
        val lightTheme = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
        }
        setContentView(R.layout.activity_photo_selection)
        val root = findViewById<View>(R.id.picker_root)
        val horizontalPadding = (16 * resources.displayMetrics.density).toInt()
        val verticalPadding = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(horizontalPadding + bars.left, verticalPadding + bars.top,
                horizontalPadding + bars.right, verticalPadding + bars.bottom)
            insets
        }
        findViewById<Button>(R.id.picker_cancel).setOnClickListener { finish() }
        search = findViewById(R.id.picker_query)
        search.setText(query)
        search.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { startSearch(); true } else false
        }
        findViewById<Button>(R.id.picker_search).setOnClickListener { startSearch() }
        status = findViewById(R.id.picker_status)
        adapter = PhotoAdapter()
        grid = findViewById<GridView>(R.id.picker_grid).apply {
            adapter = this@PhotoSelectionActivity.adapter
            setOnItemClickListener { _, _, position, _ -> selectPhoto(photos[position]) }
        }
        more = findViewById<Button>(R.id.picker_more).apply {
            setOnClickListener { paging.nextPage?.let { loadPage(it) } }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::status.isInitialized) return
        if (!loaded) {
            if (ApiClient.isLoggedIn) {
                status.setOnClickListener(null)
                status.isClickable = false
                loaded = true
                loadPage(1)
            }
            else {
                status.text = getString(R.string.picker_sign_in)
                status.setOnClickListener { startActivity(Intent(this, LoginActivity::class.java)) }
            }
        }
    }

    private fun startSearch() {
        if (downloading || !ApiClient.isLoggedIn) return
        androidx.core.view.WindowCompat.getInsetsController(window, search)
            .hide(WindowInsetsCompat.Type.ime())
        search.clearFocus()
        query = search.text.toString().trim()
        loadPage(1)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::search.isInitialized) outState.putString("picker_query", search.text.toString())
        super.onSaveInstanceState(outState)
    }

    private fun updateMoreButton() {
        more.visibility = if (!loading && paging.nextPage != null) View.VISIBLE else View.GONE
        more.isEnabled = !downloading
        more.setText(if (paging.failed) R.string.picker_retry else R.string.picker_more)
    }

    private fun loadPage(requestedPage: Int) {
        if (downloading) return
        queryJob?.cancel()
        val generation = ++loadGeneration
        val requestedQuery = query
        loading = true
        if (requestedPage == 1) {
            paging.reset()
            thumbnailJobs.toList().forEach { it.cancel() }
            photos.clear()
            adapter.notifyDataSetChanged()
        }
        updateMoreButton()
        status.text = getString(R.string.picker_loading)
        queryJob = lifecycleScope.launch {
            try {
                val result = visiblePage(requestedPage) { fetchPhotos(requestedQuery, it) }
                ensureActive()
                paging.success(result.nextPage)
                val knownIds = photos.mapTo(mutableSetOf()) { it.id }
                photos.addAll(result.items.filter { knownIds.add(it.id) })
                adapter.notifyDataSetChanged()
                status.setText(when {
                    photos.isNotEmpty() -> R.string.picker_choose
                    paging.nextPage != null -> R.string.picker_more_matches
                    else -> R.string.picker_empty
                })
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                paging.failure()
                status.setText(R.string.picker_load_error)
            } finally {
                if (generation == loadGeneration) {
                    loading = false
                    updateMoreButton()
                }
            }
        }
    }

    private suspend fun fetchPhotos(text: String, page: Int): PickerPage<Photo> {
        val url = ApiClient.buildUrl(if (text.isBlank()) "/search/metadata" else "/search/smart")
            ?: throw IOException("Not signed in")
        val body = JSONObject().put("type", "IMAGE").put("visibility", "timeline")
            .put("page", page).put("size", 30)
        if (text.isNotBlank()) body.put("query", text)
        val request = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return pickerClient.newCall(request).consume { response ->
            val assets = JSONObject(response.body?.string() ?: throw IOException("Empty response"))
                .getJSONObject("assets")
            val items = assets.getJSONArray("items")
            val result = (0 until items.length()).mapNotNull { i ->
                val item = items.getJSONObject(i)
                if (item.optString("type") != "IMAGE") return@mapNotNull null
                val id = runCatching { UUID.fromString(item.getString("id")).toString() }
                    .getOrNull() ?: return@mapNotNull null
                val name = item.optString("originalFileName", "photo")
                val mime = imageMime(item.optString("originalMimeType")) ?: return@mapNotNull null
                if (!acceptsMime(mime, acceptedTypes)) return@mapNotNull null
                Photo(id, name, mime)
            }
            val next = if (assets.isNull("nextPage")) null else assets.getInt("nextPage")
            PickerPage(result, next)
        }
    }

    private fun selectPhoto(photo: Photo) {
        if (downloading) return
        downloading = true
        ++loadGeneration
        queryJob?.cancel()
        loading = false
        grid.isEnabled = false
        more.isEnabled = false
        status.text = getString(R.string.picker_preparing)
        lifecycleScope.launch {
            try {
                val attachment = download(photo)
                ensureActive()
                val uri = FileProvider.getUriForFile(this@PhotoSelectionActivity,
                    "${BuildConfig.APPLICATION_ID}.attachments", attachment.file, photo.name)
                setResult(RESULT_OK, Intent().apply {
                    setDataAndType(uri, attachment.mime)
                    clipData = ClipData.newUri(contentResolver, getString(R.string.picker_selected), uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                finish()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                status.text = getString(R.string.picker_download_error)
                downloading = false
                grid.isEnabled = true
                updateMoreButton()
            }
        }
    }

    private suspend fun download(photo: Photo): Attachment {
        // Keep track of the file outside dispatcher/continuation hand-offs, including cancellation.
        val pending = AtomicReference<File?>()
        try {
            withContext(Dispatchers.IO) {
                val directory = File(cacheDir, "attachments").apply { mkdirs() }
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }
                    ?.forEach { it.delete() }
                pending.set(File.createTempFile("selection-", ".part", directory))
            }
            val file = pending.get() ?: throw IOException("Cannot create attachment")
            val url = ApiClient.buildUrl("/assets/${photo.id}/original") ?: throw IOException("Not signed in")
            val call = pickerClient.newCall(Request.Builder().url(url).build().withoutDiskCache())
            val responseMime = call.consume { response ->
                val body = response.body ?: throw IOException("No image")
                if (body.contentLength() > MAX_BYTES) throw IOException("Image too large")
                try {
                body.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        if (call.isCanceled()) throw IOException("Cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_BYTES) throw IOException("Image too large")
                        output.write(buffer, 0, count)
                    }
                    if (total == 0L) throw IOException("Empty image")
                } }
                imageMime(body.contentType()?.toString())
                } finally {
                    // Cancellation can race opening the output file after outer cleanup.
                    if (call.isCanceled()) file.delete()
                }
            }
            return withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                // Prefer the decoder's content identification over server filename-derived metadata.
                val mime = imageMime(bounds.outMimeType) ?: responseMime ?: photo.mime
                if (!acceptsMime(mime, acceptedTypes)) throw IOException("Unexpected image type")
                val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                    ?: throw IOException("Unsupported image type")
                val target = File(file.parentFile, "${UUID.randomUUID()}.$extension")
                if (!file.renameTo(target)) throw IOException("Cannot prepare attachment")
                pending.set(target)
                Attachment(target, mime)
            }
        } catch (e: Exception) {
            pending.get()?.delete()
            throw e
        }
    }

    private inner class PhotoAdapter : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val image = (convertView as? ImageView) ?: ImageView(this@PhotoSelectionActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = AbsListView.LayoutParams(-1, 1)
                addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
                    val width = right - left
                    if (width > 0 && view.layoutParams.height != width) {
                        view.layoutParams = view.layoutParams.apply { height = width }
                    }
                }
            }
            (image.tag as? Job)?.cancel()
            image.setImageDrawable(null)
            val photo = photos[position]
            image.contentDescription = getString(R.string.picker_select_photo, photo.name)
            bitmapCache.get(photo.id)?.let { image.setImageBitmap(it); image.tag = null; return image }
            val job = lifecycleScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val bitmap = thumbnails.withPermit { thumbnail(photo) }
                    if (bitmap != null) bitmapCache.put(photo.id, bitmap)
                    image.setImageBitmap(bitmap)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* A failed thumbnail can be retried on the next bind. */ }
                finally { thumbnailJobs.remove(currentCoroutineContext()[Job]) }
            }
            thumbnailJobs.add(job)
            image.tag = job
            job.start()
            return image
        }
    }

    private suspend fun thumbnail(photo: Photo): Bitmap? {
        val url = ApiClient.buildUrl("/assets/${photo.id}/thumbnail")!!.newBuilder()
            .addQueryParameter("size", "thumbnail").build()
        return pickerClient.newCall(Request.Builder().url(url).build()).consume { response ->
            response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
        }
    }

    companion object { private const val MAX_BYTES = 100L * 1024 * 1024 }
}
