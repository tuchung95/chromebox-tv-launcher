package local.chromebox.tvlauncher

import android.content.ComponentName
import org.json.JSONObject

/**
 * SmartTube, an open-source YouTube client for TVs (github.com/yuliskov/SmartTube, MIT
 * licence). It shows no ads, runs on Intel Chromeboxes, and opens YouTube links and searches
 * sent by other apps, so the launcher plays YouTube in it when it is installed.
 */
object SmartTube {

    /** Package names of the stable and beta builds, current names first. */
    val PACKAGES = listOf(
        "org.smarttube.stable",
        "org.smarttube.beta",
        "com.teamsmart.videomanager.tv",
        "com.liskovsoft.smarttubetv.beta",
        "com.liskovsoft.smarttubetv"
    )

    /** The latest stable release; GitHub lists each APK with its SHA-256. */
    const val LATEST_RELEASE = "https://api.github.com/repos/yuliskov/SmartTube/releases/latest"

    /** Sections SmartTube opens directly; it offers them to Android TV home screens. */
    enum class Section(private val activity: String, val label: Int) {
        HOME("HomeLauncherActivity", R.string.smarttube_home),
        SUBSCRIPTIONS("SubscriptionsLauncherActivity", R.string.smarttube_subscriptions),
        HISTORY("HistoryLauncherActivity", R.string.smarttube_history),
        PLAYLISTS("PlaylistsLauncherActivity", R.string.smarttube_playlists),
        CHANNELS("ChannelsLauncherActivity", R.string.smarttube_channels),
        MUSIC("MusicLauncherActivity", R.string.smarttube_music),
        NEWS("NewsLauncherActivity", R.string.smarttube_news),
        GAMES("GamesLauncherActivity", R.string.smarttube_games);

        fun component(pkg: String) = ComponentName(pkg, "com.liskovsoft.smartyoutubetv2.tv.launchers.$activity")
    }

    /** SmartTube reads the query from YouTube's own search address. */
    fun searchUrl(encodedQuery: String) = "https://www.youtube.com/results?search_query=$encodedQuery"

    /**
     * A watch address for the video in [url], which may also be in YouTube TV's "#/watch?v="
     * form or a youtu.be link; null when the page is not a video.
     */
    fun videoUrl(url: String): String? {
        val id = VIDEO_PARAM.find(url)?.groupValues?.get(1)
            ?: SHORT_LINK.find(url)?.groupValues?.get(1)
            ?: return null
        return "https://www.youtube.com/watch?v=$id"
    }

    private val VIDEO_PARAM = Regex("[?&#/]v=([A-Za-z0-9_-]{11})")
    private val SHORT_LINK = Regex("youtu\\.be/([A-Za-z0-9_-]{11})")

    data class Asset(val name: String, val url: String, val sha256: String)

    data class Release(val version: String, val assets: List<Asset>)

    /** Reads GitHub's release JSON. Assets without a SHA-256 are left out, since they can't be checked. */
    fun parseRelease(json: String): Release {
        val o = JSONObject(json)
        val list = o.getJSONArray("assets")
        val assets = (0 until list.length()).mapNotNull { i ->
            val a = list.getJSONObject(i)
            val digest = a.optString("digest")
            if (!digest.startsWith("sha256:")) return@mapNotNull null
            Asset(a.getString("name"), a.getString("browser_download_url"), digest.removePrefix("sha256:").lowercase())
        }
        return Release(o.optString("name", o.optString("tag_name")), assets)
    }

    /**
     * The APK for a device whose processor runs [abis], in the device's order of preference.
     * SmartTube has no x86_64 build; its 32-bit x86 build runs on 64-bit Intel Chromeboxes.
     */
    fun pickAsset(assets: List<Asset>, abis: List<String>): Asset? {
        val apks = assets.filter { it.name.endsWith(".apk") }
        for (abi in abis) {
            if (abi == "x86_64") continue
            apks.firstOrNull { it.name.endsWith("_$abi.apk") }?.let { return it }
        }
        return apks.firstOrNull { it.name.endsWith("_universal.apk") }
    }
}
