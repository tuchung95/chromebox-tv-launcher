package local.chromebox.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {

    private val youtube = WebShortcut("1", "YouTube", "https://www.youtube.com/", "chrome", "https://www.youtube.com/results?search_query=%s")
    private val movies = WebShortcut("2", "Kho Phim", "https://example.com/", "launcher", "")
    private val web = listOf(youtube, movies)
    private val apps = listOf("VLC" to "org.videolan.vlc.chromebox", "4K Browser" to "local.chromebox.browser4k", "Cài đặt" to "com.android.settings")

    private fun parse(text: String) = VoiceCommands.parse(text, apps, web)

    @Test
    fun opensPinnedPage() = assertEquals(VoiceAction.OpenWeb(youtube), parse("mở youtube"))

    @Test
    fun opensAppIgnoringDiacritics() {
        assertEquals(VoiceAction.LaunchApp("org.videolan.vlc.chromebox", "VLC"), parse("mở vlc"))
        assertEquals(VoiceAction.LaunchApp("com.android.settings", "Cài đặt"), parse("mở cai dat"))
    }

    @Test
    fun searchesNamedSite() {
        assertEquals(VoiceAction.Search(youtube, "nhạc trịnh"), parse("tìm trên youtube nhạc trịnh"))
        assertEquals(VoiceAction.Search(youtube, "nhạc trịnh"), parse("youtube nhạc trịnh"))
        assertEquals(VoiceAction.Search(youtube, "nhạc trịnh"), parse("nhạc trịnh trên youtube"))
    }

    @Test
    fun searchesGoogle() = assertEquals(VoiceAction.Search(null, "thời tiết hà nội"), parse("tìm trên google thời tiết hà nội"))

    @Test
    fun siteWithoutSearchOffersChoices() = assertEquals(VoiceAction.Choose("phim hành động"), parse("kho phim phim hành động"))

    @Test
    fun plainSearchOffersChoices() {
        assertEquals(VoiceAction.Choose("phim hành động"), parse("tìm phim hành động"))
        assertEquals(VoiceAction.Choose("phim hành động"), parse("phim hành động"))
    }
}
