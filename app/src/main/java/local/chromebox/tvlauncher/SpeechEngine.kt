package local.chromebox.tvlauncher

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Offline Vietnamese speech recognition with Vosk. The model ships zipped in the APK and is
 * unpacked once to app storage. All work happens on one background thread; [Listener]
 * callbacks arrive on that thread.
 */
class SpeechEngine(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onModelState(state: ModelState)
        fun onPartial(text: String)
        /** Called once per voice session. Empty text means nothing was understood. */
        fun onFinal(text: String)
    }

    enum class ModelState { LOADING, READY, FAILED }

    private val executor = Executors.newSingleThreadExecutor()
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var delivered = true
    private var lastPartial = ""

    @Volatile
    var isReady = false
        private set

    fun load() = executor.execute {
        listener.onModelState(ModelState.LOADING)
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(installModel().absolutePath)
            isReady = true
            listener.onModelState(ModelState.READY)
        } catch (t: Throwable) {
            Log.e(TAG, "speech model failed", t)
            listener.onModelState(ModelState.FAILED)
        }
    }

    fun begin() = executor.execute {
        recognizer?.close()
        recognizer = model?.let { Recognizer(it, SAMPLE_RATE) }
        delivered = false
        lastPartial = ""
    }

    fun accept(pcm: ShortArray) = executor.execute {
        val r = recognizer ?: return@execute
        if (delivered) return@execute
        if (r.acceptWaveForm(pcm, pcm.size)) {
            // Vosk detected the end of an utterance
            val text = textOf(r.result, "text")
            if (text.isNotEmpty()) deliver(text)
        } else {
            val partial = textOf(r.partialResult, "partial")
            if (partial != lastPartial) {
                lastPartial = partial
                listener.onPartial(partial)
            }
        }
    }

    fun end() = executor.execute {
        val r = recognizer
        recognizer = null
        if (!delivered) deliver(r?.let { textOf(it.finalResult, "text") }.orEmpty())
        r?.close()
    }

    fun release() {
        executor.execute {
            recognizer?.close()
            recognizer = null
            model?.close()
            model = null
        }
        executor.shutdown()
    }

    private fun deliver(text: String) {
        delivered = true
        listener.onFinal(text)
    }

    private fun textOf(json: String, key: String): String =
        runCatching { JSONObject(json).optString(key) }.getOrDefault("").trim()

    /** Unpacks the bundled model the first time, then reuses it. */
    private fun installModel(): File {
        val dir = File(context.filesDir, "vosk-vn")
        val marker = File(dir, ".installed-$MODEL_VERSION")
        if (marker.exists()) return dir
        dir.deleteRecursively()
        dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        context.assets.open(MODEL_ASSET).use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    // Strip the archive's top-level folder
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isNotEmpty()) {
                        val target = File(dir, relative)
                        if (!target.canonicalPath.startsWith(root)) throw IOException("Bad entry ${entry.name}")
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        marker.createNewFile()
        return dir
    }

    companion object {
        private const val TAG = "SpeechEngine"
        private const val MODEL_ASSET = "vosk-model-small-vn-0.4.zip"
        private const val MODEL_VERSION = "small-vn-0.4"
        private const val SAMPLE_RATE = 16000f
    }
}
