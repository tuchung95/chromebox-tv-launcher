package local.chromebox.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartTubeTest {

    private fun asset(suffix: String) = SmartTube.Asset("SmartTube_stable_32.56_$suffix.apk", "https://example.com/$suffix", "00")

    private val assets = listOf(asset("arm64-v8a"), asset("armeabi-v7a"), asset("universal"), asset("x86"))

    @Test
    fun intelChromeboxGetsTheX86Build() =
        assertEquals(asset("x86"), SmartTube.pickAsset(assets, listOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a")))

    @Test
    fun armBoxGetsTheArm64Build() =
        assertEquals(asset("arm64-v8a"), SmartTube.pickAsset(assets, listOf("arm64-v8a", "armeabi-v7a", "armeabi")))

    @Test
    fun otherProcessorsGetTheUniversalBuild() =
        assertEquals(asset("universal"), SmartTube.pickAsset(assets, listOf("riscv64")))

    @Test
    fun findsTheVideoInEveryLinkForm() {
        val watch = "https://www.youtube.com/watch?v=LXb3EKWsInQ"
        assertEquals(watch, SmartTube.videoUrl("https://www.youtube.com/tv#/watch?v=LXb3EKWsInQ"))
        assertEquals(watch, SmartTube.videoUrl("https://www.youtube.com/watch?v=LXb3EKWsInQ&t=30"))
        assertEquals(watch, SmartTube.videoUrl("https://youtu.be/LXb3EKWsInQ"))
    }

    @Test
    fun pagesWithoutAVideoOpenSmartTubeHome() {
        assertNull(SmartTube.videoUrl("https://www.youtube.com/tv"))
        assertNull(SmartTube.videoUrl("https://www.youtube.com/"))
    }
}
