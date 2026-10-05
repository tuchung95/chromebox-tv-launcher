package local.chromebox.tvlauncher

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class ImaAdpcmDecoderTest {

    /** Reference IMA ADPCM encoder, high nibble first. */
    private fun encode(pcm: ShortArray): ByteArray {
        val steps = ImaAdpcmDecoder.STEPS
        val adjust = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
        var predictor = 0
        var index = 0
        val nibbles = IntArray(pcm.size)
        for ((i, sample) in pcm.withIndex()) {
            val step = steps[index]
            var diff = sample - predictor
            var nibble = 0
            if (diff < 0) { nibble = 8; diff = -diff }
            var delta = step shr 3
            if (diff >= step) { nibble = nibble or 4; diff -= step; delta += step }
            if (diff >= step shr 1) { nibble = nibble or 2; diff -= step shr 1; delta += step shr 1 }
            if (diff >= step shr 2) { nibble = nibble or 1; delta += step shr 2 }
            predictor = (if (nibble and 8 != 0) predictor - delta else predictor + delta).coerceIn(-32768, 32767)
            index = (index + adjust[nibble and 7]).coerceIn(0, steps.size - 1)
            nibbles[i] = nibble
        }
        return ByteArray(pcm.size / 2) { ((nibbles[2 * it] shl 4) or nibbles[2 * it + 1]).toByte() }
    }

    private fun sine(count: Int) = ShortArray(count) { (12000 * sin(2 * PI * 440 * it / 16000.0)).toInt().toShort() }

    private fun snrDb(reference: ShortArray, decoded: ShortArray): Double {
        var signal = 0.0
        var noise = 0.0
        for (i in reference.indices) {
            signal += reference[i].toDouble() * reference[i]
            val e = reference[i] - decoded[i].toDouble()
            noise += e * e
        }
        return 10 * log10(signal / noise)
    }

    @Test
    fun decodesHighNibbleFirst() {
        val pcm = sine(3200)
        val encoded = encode(pcm)
        val decoded = ImaAdpcmDecoder().decode(encoded, 0, encoded.size)
        assertTrue("SNR too low", snrDb(pcm, decoded) > 20)
    }

    @Test
    fun decodesLowNibbleFirst() {
        val pcm = sine(3200)
        val swapped = encode(pcm).map { (((it.toInt() and 0x0F) shl 4) or ((it.toInt() and 0xF0) shr 4)).toByte() }.toByteArray()
        val decoder = ImaAdpcmDecoder().apply { lowNibbleFirst = true }
        val decoded = decoder.decode(swapped, 0, swapped.size)
        assertTrue("SNR too low", snrDb(pcm, decoded) > 20)
    }
}
