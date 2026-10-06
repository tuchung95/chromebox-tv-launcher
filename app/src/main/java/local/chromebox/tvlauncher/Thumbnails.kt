package local.chromebox.tvlauncher

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Video thumbnails, downloaded once and kept in memory and in the app's cache folder. */
object Thumbnails {

    private val memory = LruCache<String, ImageBitmap>(MAX_IN_MEMORY)

    fun cached(url: String): ImageBitmap? = memory.get(url)

    /** Blocks; null when the image can't be fetched or decoded. */
    fun load(context: Context, url: String): ImageBitmap? {
        memory.get(url)?.let { return it }
        val file = File(folder(context), fileName(url))
        val bytes = if (file.exists()) {
            runCatching { file.readBytes() }.getOrNull()
        } else {
            runCatching { download(url) }.getOrNull()?.also { runCatching { file.writeBytes(it) } }
        } ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return bitmap.asImageBitmap().also { memory.put(url, it) }
    }

    /** Deletes cached thumbnails of videos no longer shown. */
    fun prune(context: Context, keep: Collection<String>) {
        val names = keep.map { fileName(it) }.toSet()
        folder(context).listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    private fun folder(context: Context) = File(context.cacheDir, "thumbnails").apply { mkdirs() }

    /** "https://i.ytimg.com/vi/ID/mqdefault.jpg" becomes "ID_mqdefault.jpg". */
    private fun fileName(url: String) = url.substringAfter("/vi/").replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun download(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 10000
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private const val MAX_IN_MEMORY = 120
}

/** The thumbnail at [url], or null until it has loaded. */
@Composable
fun rememberThumbnail(url: String): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val image by produceState(Thumbnails.cached(url), url) {
        if (value == null) value = withContext(Dispatchers.IO) { Thumbnails.load(context, url) }
    }
    return image
}
