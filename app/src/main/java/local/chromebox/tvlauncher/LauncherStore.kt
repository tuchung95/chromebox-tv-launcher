package local.chromebox.tvlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** A web page pinned to the "Xem ngay" row. [search] is a URL template with %s, or empty. */
data class WebShortcut(
    val id: String,
    val title: String,
    val url: String,
    val opener: String,
    val search: String
)

/** Launcher settings kept in SharedPreferences. */
class LauncherStore(context: Context) {

    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var fullscreen: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN, true)
        set(value) = prefs.edit().putBoolean(KEY_FULLSCREEN, value).apply()

    /** Bluetooth address of the chosen remote, or null to pick a Xiaomi remote automatically. */
    var remoteAddress: String?
        get() = prefs.getString(KEY_REMOTE, null)
        set(value) = prefs.edit().putString(KEY_REMOTE, value).apply()

    /** Background of the home screen: a built-in style or the viewer's own photo. */
    var wallpaper: String
        get() = prefs.getString(KEY_WALLPAPER, null) ?: WALLPAPER_AURORA
        set(value) = prefs.edit().putString(KEY_WALLPAPER, value).apply()

    /** Address of updates.json on the update server. */
    var updateUrl: String
        get() = prefs.getString(KEY_UPDATE_URL, null) ?: DEFAULT_UPDATE_URL
        set(value) = prefs.edit().putString(KEY_UPDATE_URL, value).apply()

    fun webShortcuts(): MutableList<WebShortcut> {
        migrateYouTubeToTv()
        removeRetiredDefaults()
        // Save the defaults on first use so their ids stay stable for editing
        val raw = prefs.getString(KEY_WEB, null) ?: return defaultWeb().also { saveWeb(it) }
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                WebShortcut(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    url = o.getString("url"),
                    opener = o.optString("opener", OPENER_CHROME),
                    search = o.optString("search", "")
                )
            }
        } catch (e: JSONException) {
            defaultWeb()
        }
    }

    /**
     * Version 1.0.6 moved the YouTube page to YouTube's TV interface. Saved YouTube pages that
     * still open the regular site inside the launcher switch over once.
     */
    private fun migrateYouTubeToTv() {
        if (prefs.getBoolean(KEY_YOUTUBE_TV_MIGRATED, false)) return
        prefs.edit().putBoolean(KEY_YOUTUBE_TV_MIGRATED, true).apply()
        if (prefs.getString(KEY_WEB, null) == null) return
        updateWeb { list ->
            for (i in list.indices) {
                val shortcut = list[i]
                val host = android.net.Uri.parse(shortcut.url).host.orEmpty()
                if (host.endsWith("youtube.com") && shortcut.opener == OPENER_LAUNCHER) {
                    list[i] = shortcut.copy(url = YOUTUBE_TV_URL, opener = OPENER_YOUTUBE_TV, search = YOUTUBE_TV_SEARCH)
                }
            }
        }
    }

    /**
     * Once SmartTube is installed, the YouTube pages that open YouTube's TV interface switch to
     * it, a single time; the card menu can switch a page back.
     */
    fun preferSmartTube() {
        if (prefs.getBoolean(KEY_SMARTTUBE_SWITCHED, false)) return
        prefs.edit().putBoolean(KEY_SMARTTUBE_SWITCHED, true).apply()
        updateWeb { list ->
            for (i in list.indices) {
                if (list[i].opener == OPENER_YOUTUBE_TV) list[i] = list[i].copy(opener = OPENER_SMARTTUBE)
            }
        }
    }

    /** Version 1.0.8 dropped a page that earlier versions pinned by default. */
    private fun removeRetiredDefaults() {
        if (prefs.getBoolean(KEY_RETIRED_REMOVED, false)) return
        prefs.edit().putBoolean(KEY_RETIRED_REMOVED, true).apply()
        if (prefs.getString(KEY_WEB, null) == null) return
        updateWeb { list ->
            list.removeAll { android.net.Uri.parse(it.url).host.orEmpty().endsWith(RETIRED_DEFAULT_HOST) }
        }
    }

    fun updateWeb(block: (MutableList<WebShortcut>) -> Unit) {
        val list = webShortcuts()
        block(list)
        saveWeb(list)
    }

    private fun saveWeb(list: List<WebShortcut>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("url", it.url)
                    .put("opener", it.opener)
                    .put("search", it.search)
            )
        }
        prefs.edit().putString(KEY_WEB, array.toString()).apply()
    }

    /** Package names in the "Yêu thích" row, in display order. */
    fun favorites(): MutableList<String> {
        val raw = prefs.getString(KEY_FAVORITES, null) ?: return DEFAULT_FAVORITES.toMutableList()
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { array.getString(it) }
        } catch (e: JSONException) {
            DEFAULT_FAVORITES.toMutableList()
        }
    }

    fun updateFavorites(block: (MutableList<String>) -> Unit) {
        val list = favorites()
        block(list)
        prefs.edit().putString(KEY_FAVORITES, JSONArray(list.distinct()).toString()).apply()
    }

    /** Packages the user chose to open in their own window instead of inside the launcher. */
    fun separateWindowApps(): Set<String> {
        val raw = prefs.getString(KEY_SEPARATE_WINDOW, null) ?: return emptySet()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }.toSet()
        } catch (e: JSONException) {
            emptySet()
        }
    }

    fun setSeparateWindow(pkg: String, separate: Boolean) {
        val set = separateWindowApps().toMutableSet()
        if (separate) set.add(pkg) else set.remove(pkg)
        prefs.edit().putString(KEY_SEPARATE_WINDOW, JSONArray(set.toList()).toString()).apply()
    }

    /**
     * Pins apps installed since the last scan to the front of the favorites, as Android TV
     * does. The first scan only records what is installed, so existing apps stay unpinned.
     */
    fun pinNewlyInstalled(installed: Set<String>) {
        val raw = prefs.getString(KEY_KNOWN_APPS, null)
        if (raw == null) {
            prefs.edit().putString(KEY_KNOWN_APPS, JSONArray(installed.toList()).toString()).apply()
            return
        }
        val known = try {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }.toSet()
        } catch (e: JSONException) {
            emptySet()
        }
        val fresh = installed - known
        if (fresh.isEmpty()) return
        updateFavorites { favorites -> fresh.forEach { if (it !in favorites) favorites.add(0, it) } }
        prefs.edit().putString(KEY_KNOWN_APPS, JSONArray((known + installed).toList()).toString()).apply()
    }

    /** YouTube channels whose new videos appear on the home screen, in the order added. */
    fun youTubeChannels(): List<FeedChannel> {
        val raw = prefs.getString(KEY_CHANNELS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                FeedChannel(o.getString("id"), o.optString("title", o.getString("id")))
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun setYouTubeChannels(channels: List<FeedChannel>) {
        val array = JSONArray()
        channels.distinctBy { it.id }.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title)) }
        prefs.edit().putString(KEY_CHANNELS, array.toString()).apply()
    }

    /** The videos shown last time, so the row appears at once while the feeds reload. */
    fun cachedVideos(): List<FeedVideo> {
        val raw = prefs.getString(KEY_VIDEOS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                FeedVideo(o.getString("id"), o.optString("title"), o.optString("channel"), o.optString("channelId"), o.optLong("published"))
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun cacheVideos(videos: List<FeedVideo>) {
        val array = JSONArray()
        videos.forEach {
            array.put(
                JSONObject().put("id", it.id).put("title", it.title).put("channel", it.channel)
                    .put("channelId", it.channelId).put("published", it.published)
            )
        }
        prefs.edit().putString(KEY_VIDEOS, array.toString()).apply()
    }

    /** Remote buttons the viewer assigned, by Android key code. */
    fun buttonActions(): Map<Int, ButtonAction> {
        val raw = prefs.getString(KEY_BUTTONS, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().mapNotNull { key ->
                val code = key.toIntOrNull() ?: return@mapNotNull null
                ButtonAction.parse(o.optString(key))?.let { code to it }
            }.toMap()
        } catch (e: JSONException) {
            emptyMap()
        }
    }

    /** Assigns [action] to the button, or clears the button when [action] is null. */
    fun setButtonAction(keyCode: Int, action: ButtonAction?) {
        val map = buttonActions().toMutableMap()
        if (action == null) map.remove(keyCode) else map[keyCode] = action
        val o = JSONObject()
        map.forEach { (code, value) -> o.put(code.toString(), value.value) }
        prefs.edit().putString(KEY_BUTTONS, o.toString()).apply()
    }

    /** Changes whenever the shortcuts or favorites change; used to skip needless redraws. */
    fun signature(): String =
        prefs.getString(KEY_WEB, "") + "|" + prefs.getString(KEY_FAVORITES, "")

    private fun defaultWeb() = mutableListOf(
        WebShortcut(newId(), "YouTube", YOUTUBE_TV_URL, OPENER_YOUTUBE_TV, YOUTUBE_TV_SEARCH)
    )

    companion object {
        /** Plays the page in the launcher's own web player. */
        const val OPENER_LAUNCHER = "launcher"
        /** YouTube's TV interface, driven by the remote, in the launcher's web player. */
        const val OPENER_YOUTUBE_TV = "youtube_tv"
        /** The SmartTube app, falling back to YouTube's TV interface when it is not installed. */
        const val OPENER_SMARTTUBE = "smarttube"
        const val OPENER_BROWSER4K = "browser4k"
        const val OPENER_CHROME = "chrome"
        val OPENERS = listOf(OPENER_LAUNCHER, OPENER_YOUTUBE_TV, OPENER_SMARTTUBE, OPENER_BROWSER4K, OPENER_CHROME)

        const val YOUTUBE_TV_URL = "https://www.youtube.com/tv"
        const val YOUTUBE_TV_SEARCH = "https://www.youtube.com/tv#/search?q=%s"

        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_REMOTE = "remote_address"
        private const val KEY_WEB = "web_shortcuts"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_UPDATE_URL = "update_url"
        private const val KEY_SEPARATE_WINDOW = "separate_window_apps"
        private const val KEY_YOUTUBE_TV_MIGRATED = "youtube_tv_migrated"
        private const val KEY_KNOWN_APPS = "known_apps"
        private const val KEY_WALLPAPER = "wallpaper"
        private const val KEY_BUTTONS = "remote_buttons"
        private const val KEY_CHANNELS = "youtube_channels"
        private const val KEY_VIDEOS = "youtube_videos"
        private const val KEY_SMARTTUBE_SWITCHED = "smarttube_switched"

        const val WALLPAPER_NONE = "none"
        const val WALLPAPER_AURORA = "aurora"
        const val WALLPAPER_SUNSET = "sunset"
        const val WALLPAPER_OCEAN = "ocean"
        const val WALLPAPER_PHOTO = "photo"
        private const val KEY_RETIRED_REMOVED = "retired_defaults_removed"
        private const val RETIRED_DEFAULT_HOST = "film4k.net"

        /** updates.json attached to the latest GitHub release (see scripts/release.sh). */
        const val DEFAULT_UPDATE_URL =
            "https://github.com/tuchung95/chromebox-tv-launcher/releases/latest/download/updates.json"

        private val DEFAULT_FAVORITES = listOf(
            "local.chromebox.browser4k",
            "org.videolan.vlc.chromebox",
            "org.videolan.vlc",
            "org.videolan.vlc.debug",
            "com.google.android.youtube"
        )

        fun newId(): String = UUID.randomUUID().toString()
    }
}
