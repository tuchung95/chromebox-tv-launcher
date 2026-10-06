package local.chromebox.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeFeedTest {

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
         <title>VTV24</title>
         <author><name>VTV24</name></author>
         <entry>
          <yt:videoId>aaaaaaaaaaa</yt:videoId>
          <yt:channelId>UCabsTV34JwALXKGMqHpvUiA</yt:channelId>
          <title>Bản tin sáng</title>
          <link rel="alternate" href="https://www.youtube.com/watch?v=aaaaaaaaaaa"/>
          <author><name>VTV24</name></author>
          <published>2026-10-05T14:53:25+00:00</published>
          <media:group><media:title>Bản tin sáng</media:title></media:group>
         </entry>
         <entry>
          <yt:videoId>bbbbbbbbbbb</yt:videoId>
          <yt:channelId>UCabsTV34JwALXKGMqHpvUiA</yt:channelId>
          <title>Một short</title>
          <link rel="alternate" href="https://www.youtube.com/shorts/bbbbbbbbbbb"/>
          <author><name>VTV24</name></author>
          <published>2026-10-05T15:00:00+00:00</published>
         </entry>
        </feed>
    """.trimIndent()

    @Test
    fun readsVideosAndSkipsShorts() {
        val parsed = YouTubeFeed.parseFeed(feed)
        assertEquals("VTV24", parsed.title)
        assertEquals(1, parsed.videos.size)
        val video = parsed.videos[0]
        assertEquals("aaaaaaaaaaa", video.id)
        assertEquals("Bản tin sáng", video.title)
        assertEquals("VTV24", video.channel)
        assertEquals("UCabsTV34JwALXKGMqHpvUiA", video.channelId)
        assertEquals(1791212005000L, video.published)
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/mqdefault.jpg", video.thumbnail)
    }

    @Test
    fun newestVideosComeFirstAcrossChannels() {
        val old = FeedVideo("old", "", "A", "UCa", 1)
        val new = FeedVideo("new", "", "B", "UCb", 3)
        val middle = FeedVideo("mid", "", "A", "UCa", 2)
        assertEquals(listOf(new, middle), YouTubeFeed.latest(listOf(listOf(old, middle), listOf(new, middle)), limit = 2))
    }

    @Test
    fun channelLinksNeedNoLookup() {
        assertEquals("UCabsTV34JwALXKGMqHpvUiA", YouTubeFeed.channelIdIn("https://www.youtube.com/channel/UCabsTV34JwALXKGMqHpvUiA/videos"))
        assertEquals("UCabsTV34JwALXKGMqHpvUiA", YouTubeFeed.channelIdIn("UCabsTV34JwALXKGMqHpvUiA"))
        assertNull(YouTubeFeed.channelIdIn("@VTV24"))
    }

    @Test
    fun otherInputsAreLookedUp() {
        assertEquals("https://www.youtube.com/@VTV24", YouTubeFeed.lookupUrl("https://youtube.com/@VTV24?si=x"))
        assertEquals("https://www.youtube.com/@VTV24", YouTubeFeed.lookupUrl("@VTV24"))
        assertEquals("https://youtu.be/aaaaaaaaaaa", YouTubeFeed.lookupUrl("youtu.be/aaaaaaaaaaa"))
        assertTrue(YouTubeFeed.lookupUrl("thời sự vtv").startsWith("https://www.youtube.com/results?search_query=th%E1%BB%9Di+s%E1%BB%B1+vtv"))
    }

    @Test
    fun findsTheChannelInAPage() {
        val channelPage = """<link rel="canonical" href="https://www.youtube.com/channel/UCabsTV34JwALXKGMqHpvUiA">"""
        assertEquals("UCabsTV34JwALXKGMqHpvUiA", YouTubeFeed.channelIdInPage(channelPage))
        val searchPage = """..."channelId":"UCEDufSH13rFeV_MaHNc3z5A"..."channelId":"UCHaOPqe_8CJAuLMVr4eT9iw""""
        assertEquals("UCEDufSH13rFeV_MaHNc3z5A", YouTubeFeed.channelIdInPage(searchPage))
        assertNull(YouTubeFeed.channelIdInPage("<html></html>"))
    }
}
