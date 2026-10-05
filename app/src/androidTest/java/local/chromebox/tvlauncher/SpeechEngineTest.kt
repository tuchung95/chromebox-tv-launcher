package local.chromebox.tvlauncher

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Feeds a recorded Vietnamese phrase through the bundled Vosk model on a real device. */
@RunWith(AndroidJUnit4::class)
class SpeechEngineTest {

    @Test
    fun recognizesVietnamesePhrase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pcm = readWav(instrumentation.context.assets.open("tim-phim-hanh-dong.wav"))

        val loaded = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var state: SpeechEngine.ModelState? = null
        var text = ""
        val engine = SpeechEngine(instrumentation.targetContext, object : SpeechEngine.Listener {
            override fun onModelState(newState: SpeechEngine.ModelState) {
                state = newState
                if (newState != SpeechEngine.ModelState.LOADING) loaded.countDown()
            }
            override fun onPartial(partial: String) {}
            override fun onFinal(result: String) {
                text = result
                finished.countDown()
            }
        })

        engine.load()
        assertTrue("model did not load in time", loaded.await(180, TimeUnit.SECONDS))
        assertEquals(SpeechEngine.ModelState.READY, state)

        engine.begin()
        // 100 ms chunks, like the remote's audio stream, followed by half a second of silence
        pcm.toList().chunked(1600).forEach { engine.accept(it.toShortArray()) }
        engine.accept(ShortArray(8000))
        engine.end()
        assertTrue("no result", finished.await(60, TimeUnit.SECONDS))
        engine.release()

        assertTrue("unexpected transcript: $text", VoiceCommands.normalize(text).contains("phim"))
    }

    /** Reads 16-bit mono PCM from a WAV file. */
    private fun readWav(input: InputStream): ShortArray {
        val bytes = input.use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (id == "data") {
                val samples = ShortArray(size / 2)
                buffer.position(offset + 8)
                buffer.asShortBuffer().get(samples)
                return samples
            }
            offset += 8 + size + (size and 1)
        }
        error("No data chunk in WAV file")
    }
}
