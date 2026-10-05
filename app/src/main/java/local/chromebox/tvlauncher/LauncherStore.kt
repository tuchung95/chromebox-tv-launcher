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

    /** Address of updates.json on the update server. */
    var updateUrl: String
        get() = prefs.getString(KEY_UPDATE_URL, null) ?: DEFAULT_UPDATE_URL
        set(value) = prefs.edit().putString(KEY_UPDATE_URL, value).apply()

    fun webShortcuts(): MutableList<WebShortcut> {
        val raw = prefs.getString(KEY_WEB, null) ?: return defaultWeb()
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

    fun updateWeb(block: (MutableList<WebShortcut>) -> Unit) {
        val list = webShortcuts()
        block(list)
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

    /** Changes whenever the shortcuts or favorites change; used to skip needless redraws. */
    fun signature(): String =
        prefs.getString(KEY_WEB, "") + "|" + prefs.getString(KEY_FAVORITES, "")

    private fun defaultWeb() = mutableListOf(
        WebShortcut(newId(), "Film4K", "https://film4k.net/", OPENER_LAUNCHER, ""),
        WebShortcut(
            newId(), "YouTube", "https://www.youtube.com/", OPENER_LAUNCHER,
            "https://www.youtube.com/results?search_query=%s"
        )
    )

    companion object {
        /** Plays the page in the launcher's own web player. */
        const val OPENER_LAUNCHER = "launcher"
        const val OPENER_BROWSER4K = "browser4k"
        const val OPENER_CHROME = "chrome"
        val OPENERS = listOf(OPENER_LAUNCHER, OPENER_BROWSER4K, OPENER_CHROME)

        private const val KEY_FULLSCREEN = "fullscreen"
        private const val KEY_REMOTE = "remote_address"
        private const val KEY_WEB = "web_shortcuts"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_UPDATE_URL = "update_url"
        private const val KEY_SEPARATE_WINDOW = "separate_window_apps"

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
