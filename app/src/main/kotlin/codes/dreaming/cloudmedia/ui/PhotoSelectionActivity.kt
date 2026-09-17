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

/** A user-driven GET_CONTENT picker; only the selected original gets a URI grant. */
class PhotoSelectionActivity : AppCompatActivity() {
    private data class Photo(val id: String, val name: String, val mime: String)
    private val photos = mutableListOf<Photo>()
    private val thumbnails = Semaphore(4)
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var grid: GridView
    private lateinit var more: Button
    private lateinit var adapter: PhotoAdapter
    private var queryJob: Job? = null
    private var downloading = false
    private var page = 1
    private var query = ""
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        ApiClient.initialize(this)
        val lightTheme = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightTheme
            isAppearanceLightNavigationBars = lightTheme
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 12)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(24 + bars.left, 12 + bars.top, 24 + bars.right, 12 + bars.bottom)
            insets
        }
        root.addView(TextView(this).apply { text = getString(R.string.picker_title); textSize = 22f })
        root.addView(Button(this).apply { text = getString(R.string.picker_cancel); setOnClickListener { finish() } })
        search = EditText(this).apply {
            hint = getString(R.string.picker_search_hint)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { startSearch(); true } else false
            }
        }
        root.addView(search)
        root.addView(Button(this).apply { text = getString(R.string.picker_search); setOnClickListener { startSearch() } })
        status = TextView(this).apply { textSize = 16f }
        root.addView(status)
        adapter = PhotoAdapter()
        grid = GridView(this).apply {
            numColumns = 3
            verticalSpacing = 8
            horizontalSpacing = 8
            adapter = this@PhotoSelectionActivity.adapter
            setOnItemClickListener { _, _, position, _ -> selectPhoto(photos[position]) }
        }
        root.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        more = Button(this).apply {
            text = getString(R.string.picker_more)
            visibility = View.GONE
            setOnClickListener { loadPage(page + 1) }
        }
        root.addView(more)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (!loaded) {
            if (ApiClient.isLoggedIn) { loaded = true; loadPage(1) }
            else {
                status.text = getString(R.string.picker_sign_in)
                status.setOnClickListener { startActivity(Intent(this, LoginActivity::class.java)) }
            }
        }
    }

    private fun startSearch() {
        if (downloading || !ApiClient.isLoggedIn) return
        query = search.text.toString().trim()
        loadPage(1)
    }

    private fun loadPage(requestedPage: Int) {
        if (downloading) return
        queryJob?.cancel()
        val requestedQuery = query
        more.visibility = View.GONE
        if (requestedPage == 1) { photos.clear(); adapter.notifyDataSetChanged() }
        status.text = getString(R.string.picker_loading)
        queryJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { fetchPhotos(requestedQuery, requestedPage) }
                ensureActive()
                page = requestedPage
                photos.addAll(result.first)
                adapter.notifyDataSetChanged()
                status.text = if (photos.isEmpty()) getString(R.string.picker_empty) else getString(R.string.picker_choose)
                more.visibility = if (result.second) View.VISIBLE else View.GONE
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                status.text = getString(R.string.picker_load_error)
            }
        }
    }

    private fun fetchPhotos(text: String, page: Int): Pair<List<Photo>, Boolean> {
        val url = ApiClient.buildUrl(if (text.isBlank()) "/search/metadata" else "/search/smart")
            ?: throw IOException("Not signed in")
        val body = JSONObject().put("type", "IMAGE").put("page", page).put("size", 30)
        if (text.isNotBlank()) body.put("query", text)
        val request = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return ApiClient.getClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val assets = JSONObject(response.body?.string() ?: throw IOException("Empty response"))
                .getJSONObject("assets")
            val items = assets.getJSONArray("items")
            val result = (0 until items.length()).mapNotNull { i ->
                val item = items.getJSONObject(i)
                if (item.optString("type") != "IMAGE") return@mapNotNull null
                val id = UUID.fromString(item.getString("id")).toString()
                val name = item.optString("originalFileName", "photo.jpg")
                val ext = name.substringAfterLast('.', "jpg").lowercase()
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                val accepted = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                    ?.takeIf { it.isNotEmpty() }?.toList() ?: listOf(intent.type?.takeUnless { it == "vnd.android.cursor.dir/image" } ?: "image/*")
                if (accepted.none { android.content.ClipDescription.compareMimeTypes(mime, it) })
                    return@mapNotNull null
                Photo(id, name, mime)
            }
            result to !assets.isNull("nextPage")
        }
    }

    private fun selectPhoto(photo: Photo) {
        if (downloading) return
        downloading = true
        queryJob?.cancel()
        grid.isEnabled = false
        more.isEnabled = false
        status.text = getString(R.string.picker_preparing)
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { download(photo) }
                ensureActive()
                val uri = FileProvider.getUriForFile(this@PhotoSelectionActivity,
                    "${BuildConfig.APPLICATION_ID}.attachments", file)
                setResult(RESULT_OK, Intent().apply {
                    setDataAndType(uri, photo.mime)
                    clipData = ClipData.newUri(contentResolver, getString(R.string.picker_selected), uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
                finish()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                status.text = getString(R.string.picker_download_error)
                downloading = false
                grid.isEnabled = true
                more.isEnabled = true
            }
        }
    }

    private suspend fun download(photo: Photo): File {
        val directory = File(cacheDir, "attachments").apply { mkdirs() }
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }
            ?.forEach { it.delete() }
        val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(photo.mime) ?: "jpg"
        val file = File(directory, "${UUID.randomUUID()}.$extension")
        try {
            val url = ApiClient.buildUrl("/assets/${photo.id}/original") ?: throw IOException("Not signed in")
            ApiClient.getClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("No image")
                if (body.contentLength() > MAX_BYTES) throw IOException("Image too large")
                body.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_BYTES) throw IOException("Image too large")
                        output.write(buffer, 0, count)
                    }
                    if (total == 0L) throw IOException("Empty image")
                } }
            }
            return file
        } catch (e: Exception) { file.delete(); throw e }
    }

    private inner class PhotoAdapter : BaseAdapter() {
        override fun getCount() = photos.size
        override fun getItem(position: Int) = photos[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val image = (convertView as? ImageView) ?: ImageView(this@PhotoSelectionActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = AbsListView.LayoutParams(-1, resources.displayMetrics.widthPixels / 3)
            }
            (image.tag as? Job)?.cancel()
            image.setImageDrawable(null)
            val photo = photos[position]
            image.contentDescription = getString(R.string.picker_select_photo, photo.name)
            image.tag = lifecycleScope.launch {
                val bitmap = thumbnails.withPermit { withContext(Dispatchers.IO) { thumbnail(photo) } }
                image.setImageBitmap(bitmap)
            }
            return image
        }
    }

    private fun thumbnail(photo: Photo): Bitmap? = try {
        val url = ApiClient.buildUrl("/assets/${photo.id}/thumbnail")!!.newBuilder()
            .addQueryParameter("size", "thumbnail").build()
        ApiClient.getClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
        }
    } catch (_: Exception) { null }

    companion object { private const val MAX_BYTES = 100L * 1024 * 1024 }
}
