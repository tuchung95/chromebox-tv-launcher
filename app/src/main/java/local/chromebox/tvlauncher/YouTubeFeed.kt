package local.chromebox.tvlauncher

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** A YouTube channel whose new videos appear on the home screen. */
data class FeedChannel(val id: String, val title: String)

data class FeedVideo(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String,
    /** Milliseconds since the epoch. */
    val published: Long
) {
    /** 320 × 180, the 16:9 size that fits a card. */
    val thumbnail get() = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
}

/**
 * New videos from YouTube channels, read from the public feed every channel has
 * (youtube.com/feeds/videos.xml), which needs no account or API key. YouTube offers no
 * recommendations or subscriptions without signing in, so the viewer picks the channels.
 */
object YouTubeFeed {

    class Feed(val title: String, val videos: List<FeedVideo>)

    private const val ATOM = "http://www.w3.org/2005/Atom"
    private const val YT = "http://www.youtube.com/xml/schemas/2015"
    private val CHANNEL_ID = Regex("UC[0-9A-Za-z_-]{22}")

    fun feedUrl(channelId: String) = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"

    /** The video in YouTube's TV interface, which the launcher's openers all understand. */
    fun tvWatchUrl(videoId: String) = "https://www.youtube.com/tv#/watch?v=$videoId"

    /** Reads a channel feed. Shorts are left out: they are vertical clips made for phones. */
    fun parseFeed(xml: String): Feed {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        val entries = root.getElementsByTagNameNS(ATOM, "entry")
        val videos = (0 until entries.length).mapNotNull { i ->
            val entry = entries.item(i) as Element
            val link = entry.child(ATOM, "link")?.getAttribute("href").orEmpty()
            if ("/shorts/" in link) return@mapNotNull null
            FeedVideo(
                id = entry.child(YT, "videoId")?.textContent?.trim() ?: return@mapNotNull null,
                title = entry.child(ATOM, "title")?.textContent?.trim().orEmpty(),
                channel = entry.child(ATOM, "author")?.child(ATOM, "name")?.textContent?.trim().orEmpty(),
                channelId = entry.child(YT, "channelId")?.textContent?.trim().orEmpty(),
                published = parseDate(entry.child(ATOM, "published")?.textContent)
            )
        }
        return Feed(root.child(ATOM, "title")?.textContent?.trim().orEmpty(), videos)
    }

    /** The newest videos across channels. */
    fun latest(feeds: List<List<FeedVideo>>, limit: Int = 30): List<FeedVideo> =
        feeds.flatten().distinctBy { it.id }.sortedByDescending { it.published }.take(limit)

    /** The channel id in a /channel/ link or a bare id; null when the input needs a lookup. */
    fun channelIdIn(input: String): String? {
        val text = input.trim()
        if (text.matches(CHANNEL_ID)) return text
        return Regex("/channel/(${CHANNEL_ID.pattern})").find(text)?.groupValues?.get(1)
    }

    /**
     * The page that names the channel for [input]: the @handle page, the YouTube link itself
     * (a video link names its channel too), or a search for channels with that name.
     */
    fun lookupUrl(input: String): String {
        val text = input.trim()
        val handle = Regex("@([A-Za-z0-9._-]+)").find(text)?.groupValues?.get(1)
        return when {
            handle != null -> "https://www.youtube.com/@$handle"
            "youtube.com/" in text || "youtu.be/" in text ->
                if (text.startsWith("http")) text else "https://$text"
            // sp=EgIQAg== limits the results to channels
            else -> "https://www.youtube.com/results?search_query=" + URLEncoder.encode(text, "UTF-8") + "&sp=EgIQAg%253D%253D"
        }
    }

    /** The channel a YouTube page is about: a channel page's own id, else the first one named. */
    fun channelIdInPage(html: String): String? =
        Regex("<link rel=\"canonical\" href=\"https://www.youtube.com/channel/(${CHANNEL_ID.pattern})\"").find(html)?.groupValues?.get(1)
            ?: Regex("\"(?:externalId|channelId)\":\"(${CHANNEL_ID.pattern})\"").find(html)?.groupValues?.get(1)

    /** Blocks. A desktop browser's user agent gets YouTube's regular pages. */
    fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 15000
        connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT)
        connection.setRequestProperty("Accept-Language", "vi,en;q=0.8")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    private fun Element.child(namespace: String, name: String): Element? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.namespaceURI == namespace && node.localName == name) return node
        }
        return null
    }

    private fun parseDate(text: String?): Long =
        text?.let { runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).parse(it.trim())?.time }.getOrNull() } ?: 0L
}
