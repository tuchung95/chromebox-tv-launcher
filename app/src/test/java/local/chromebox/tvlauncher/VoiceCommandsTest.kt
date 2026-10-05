package local.chromebox.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {

    private val youtube = WebShortcut("1", "YouTube", "https://www.youtube.com/", "chrome", "https://www.youtube.com/results?search_query=%s")
    private val film4k = WebShortcut("2", "Film4K", "https://film4k.net/", "browser4k", "")
    private val web = listOf(youtube, film4k)
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
    fun siteWithoutSearchOffersChoices() = assertEquals(VoiceAction.Choose("phim hành động"), parse("film4k phim hành động"))

    @Test
    fun plainSearchOffersChoices() {
        assertEquals(VoiceAction.Choose("phim hành động"), parse("tìm phim hành động"))
        assertEquals(VoiceAction.Choose("phim hành động"), parse("phim hành động"))
    }
}
