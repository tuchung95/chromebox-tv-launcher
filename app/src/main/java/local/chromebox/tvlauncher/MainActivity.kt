package local.chromebox.tvlauncher

import android.Manifest
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import local.chromebox.tvlauncher.LauncherStore.Companion.OPENERS
import local.chromebox.tvlauncher.LauncherStore.Companion.OPENER_BROWSER4K
import local.chromebox.tvlauncher.LauncherStore.Companion.OPENER_LAUNCHER
import local.chromebox.tvlauncher.LauncherStore.Companion.OPENER_SMARTTUBE
import local.chromebox.tvlauncher.LauncherStore.Companion.OPENER_YOUTUBE_TV
import java.io.IOException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A 10-foot home screen for ChromeOS built with Google's Compose for TV components: large
 * cards driven by the remote, arrow keys or the mouse, apps and web pages that open inside
 * the launcher, voice commands through a Xiaomi Bluetooth remote, and in-app updates.
 */
class MainActivity : ComponentActivity(), AtvvRemote.Listener, SpeechEngine.Listener, HomeActions {

    private data class AppEntry(
        val pkg: String,
        val label: String,
        val component: ComponentName,
        val category: String,
        val info: ActivityInfo
    )

    private lateinit var store: LauncherStore
    private lateinit var remote: AtvvRemote
    private lateinit var speech: SpeechEngine
    private lateinit var updates: UpdateManager

    private val home = HomeState()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var apps: List<AppEntry> = emptyList()
    private var lastSignature = ""

    private var remoteState = AtvvRemote.State.CONNECTING
    private var remoteName: String? = null
    private var modelState = SpeechEngine.ModelState.LOADING
    private var transcriptHandled = true
    private var pendingVoice: Runnable? = null
    private val voiceTimeout = Runnable { remote.closeMicrophone() }
    private val hideVoiceRunnable = Runnable { hideVoice() }
    private val micTimeout = Runnable { onMicSilent() }
    private val voiceKeyFallback = Runnable { if (home.voice == null) startVoice() }

    /** Remote buttons the viewer assigned, by key code. */
    private var buttons: Map<Int, ButtonAction> = emptyMap()
    /** A button pressed in the web player, run once the home screen is back. */
    private var pendingButton: ButtonAction? = null

    private var availableUpdates: List<AppUpdate> = emptyList()
    private var lastUpdateCheck = 0L

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // Lay the screen out at Android TV's 960 dp width, whatever density ChromeOS reports
        TvDensity.overrideFor(newBase)?.let { applyOverrideConfiguration(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LauncherStore(this)
        updates = UpdateManager(this)
        speech = SpeechEngine(this, this)
        speech.load()
        remote = AtvvRemote(this, { store.remoteAddress }, this)

        buttons = store.buttonActions()
        updateButtonsLabel()
        takeButtonAction(intent)
        home.fullscreen = store.fullscreen
        home.wallpaper = store.wallpaper
        if (store.wallpaper == LauncherStore.WALLPAPER_PHOTO) loadWallpaperPhoto()
        updateVersionLabel()
        setContent { ChromeboxTvTheme { HomeScreen(home, this) } }

        // A launcher never closes itself on Back; it returns to the navigation bar and Home tab
        onBackPressedDispatcher.addCallback(this) { home.backRequest++ }
    }

    override fun onStart() {
        super.onStart()
        if (!hasBluetoothPermission()) requestBluetoothPermission()
        remote.start()
        if (System.currentTimeMillis() - lastUpdateCheck > UPDATE_CHECK_INTERVAL_MS) checkUpdates(quiet = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeButtonAction(intent)
    }

    override fun onResume() {
        super.onResume()
        applyFullscreen(store.fullscreen)
        updateRemoteLabel()
        refreshHome()
        pendingButton?.let {
            pendingButton = null
            runButtonAction(it)
        }
    }

    override fun onStop() {
        remote.stop()
        hideVoice()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        remote.release()
        speech.release()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && store.fullscreen) applyFullscreen(true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        buttons[code]?.let { action ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) runButtonAction(action)
            return true
        }
        if (code in VOICE_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onVoiceKey()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_F11) {
            toggleFullscreen()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // --- Home screen content ---

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val found = LinkedHashMap<String, AppEntry>()
        for (category in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (resolve in pm.queryIntentActivities(query, 0)) {
                val info = resolve.activityInfo
                if (info.packageName == packageName || found.containsKey(info.packageName)) continue
                found[info.packageName] = AppEntry(
                    pkg = info.packageName,
                    label = resolve.loadLabel(pm).toString(),
                    component = ComponentName(info.packageName, info.name),
                    category = category,
                    info = info
                )
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** Reloads apps, pages and favorites; skips the work when nothing changed. */
    private fun refreshHome(force: Boolean = false) {
        val loaded = loadApps()
        store.pinNewlyInstalled(loaded.map { it.pkg }.toSet())
        val smartTube = SmartTube.PACKAGES.firstOrNull { pkg -> loaded.any { it.pkg == pkg } }
        if (smartTube != null) store.preferSmartTube()
        home.smartTube = smartTube
        val signature = loaded.joinToString(",") { it.pkg + "/" + it.label } + "#" + store.signature()
        if (!force && signature == lastSignature) return
        lastSignature = signature
        apps = loaded

        val favorites = store.favorites()
        val iconPx = (96 * resources.displayMetrics.density).toInt()
        val tiles = loaded.associate { app ->
            val banner = runCatching { app.info.loadBanner(packageManager) }.getOrNull()
            app.pkg to AppTile(
                pkg = app.pkg,
                label = app.label,
                icon = app.info.loadIcon(packageManager).toImageBitmap(iconPx, iconPx),
                banner = banner?.toImageBitmap(banner.intrinsicWidth.takeIf { it > 0 } ?: 640, banner.intrinsicHeight.takeIf { it > 0 } ?: 360),
                isFavorite = app.pkg in favorites
            )
        }
        home.web = store.webShortcuts()
        home.favorites = favorites.mapNotNull { tiles[it] }
        home.apps = loaded.mapNotNull { tiles[it.pkg] }
    }

    private fun Drawable.toImageBitmap(width: Int, height: Int) =
        Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            setBounds(0, 0, canvas.width, canvas.height)
            draw(canvas)
        }.asImageBitmap()

    // --- HomeActions ---

    override fun openWeb(shortcut: WebShortcut) = openUrl(shortcut.url, shortcut.opener)

    override fun launchApp(pkg: String) {
        val app = apps.firstOrNull { it.pkg == pkg } ?: return toast(getString(R.string.app_missing))
        val intent = Intent(Intent.ACTION_MAIN).addCategory(app.category).setComponent(app.component)
        val started = if (app.pkg in store.separateWindowApps()) {
            start(intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } else {
            startInLauncher(intent)
        }
        if (!started) toast(getString(R.string.app_missing))
    }

    override fun addWeb() = editWeb(null)

    override fun remoteClicked() = showRemoteDialog()

    override fun updateClicked() = showUpdates()

    override fun toggleFullscreen() {
        store.fullscreen = !store.fullscreen
        home.fullscreen = store.fullscreen
        applyFullscreen(store.fullscreen)
    }

    override fun searchFor(query: String, shortcut: WebShortcut?) = search(shortcut, query)

    override fun wallpaperClicked() {
        val styles = listOf(
            LauncherStore.WALLPAPER_NONE, LauncherStore.WALLPAPER_AURORA,
            LauncherStore.WALLPAPER_SUNSET, LauncherStore.WALLPAPER_OCEAN, LauncherStore.WALLPAPER_PHOTO
        )
        val labels = styles.map { style ->
            getString(if (style == LauncherStore.WALLPAPER_PHOTO) R.string.wallpaper_pick else wallpaperName(style))
        }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.settings_wallpaper)
            .setSingleChoiceItems(labels.toTypedArray(), styles.indexOf(store.wallpaper)) { dialog, which ->
                dialog.dismiss()
                val style = styles[which]
                if (style == LauncherStore.WALLPAPER_PHOTO) {
                    // The ChromeOS file picker, which also reaches Downloads and Google Drive.
                    // Some TV boxes have no file picker at all.
                    try {
                        pickWallpaper.launch(arrayOf("image/*"))
                    } catch (e: ActivityNotFoundException) {
                        toast(getString(R.string.wallpaper_no_picker))
                    }
                } else {
                    store.wallpaper = style
                    home.wallpaper = style
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private val pickWallpaper =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importWallpaper(it) } }

    private fun wallpaperFile() = java.io.File(filesDir, "wallpaper.jpg")

    /** Copies the chosen image, scaled down to the screen, into app storage. */
    private fun importWallpaper(uri: Uri) {
        val target = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).coerceAtLeast(1280)
        io.execute {
            val bitmap = runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                    ?: error("unreadable image")
                wallpaperFile().outputStream().use { decoded.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                decoded
            }.getOrNull()
            runOnUiThread {
                if (bitmap == null) {
                    toast(getString(R.string.wallpaper_failed))
                } else {
                    store.wallpaper = LauncherStore.WALLPAPER_PHOTO
                    home.wallpaper = LauncherStore.WALLPAPER_PHOTO
                    home.wallpaperPhoto = bitmap.asImageBitmap()
                }
            }
        }
    }

    private fun loadWallpaperPhoto() {
        io.execute {
            val bitmap = runCatching { android.graphics.BitmapFactory.decodeFile(wallpaperFile().path) }.getOrNull()
            runOnUiThread { home.wallpaperPhoto = bitmap?.asImageBitmap() }
        }
    }

    override fun dismissVoice() {
        remote.closeMicrophone()
        hideVoice()
    }

    // --- Launching ---

    private fun openUrl(url: String, opener: String, query: String? = null) {
        val uri = Uri.parse(url)
        if (opener == OPENER_SMARTTUBE) {
            if (openInSmartTube(url, query)) return
            toast(getString(R.string.smarttube_missing))
            return openUrl(url, OPENER_YOUTUBE_TV, query)
        }
        if (opener == OPENER_YOUTUBE_TV) {
            if (openYouTubeTvApp(query)) return
            val player = Intent(this, WebPlayerActivity::class.java)
                .setData(uri)
                .putExtra(WebPlayerActivity.EXTRA_TV_MODE, true)
            if (startInLauncher(player)) return
        }
        if (opener == OPENER_LAUNCHER) {
            if (startInLauncher(Intent(this, WebPlayerActivity::class.java).setData(uri))) return
        }
        if (opener == OPENER_BROWSER4K) {
            if (isInstalled(BROWSER4K)) {
                val intent = Intent(Intent.ACTION_VIEW, uri).setClassName(BROWSER4K, "$BROWSER4K.MainActivity")
                if (start(intent)) return
            } else {
                toast(getString(R.string.browser4k_missing))
            }
        }
        // On ChromeOS a web link from an Android app opens in the ChromeOS Chrome browser
        if (!start(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))) {
            toast(getString(R.string.no_browser))
        }
    }

    /**
     * Uses Google's YouTube for Android TV app when it is installed, as on an Android TV box.
     * ChromeOS has no such app, so the launcher falls back to YouTube's TV web interface.
     */
    private fun openYouTubeTvApp(query: String?): Boolean {
        if (!isInstalled(YOUTUBE_TV_APP)) return false
        if (query != null) {
            val search = Intent(Intent.ACTION_SEARCH)
                .setPackage(YOUTUBE_TV_APP)
                .putExtra(SearchManager.QUERY, query)
            if (startInLauncher(search)) return true
        }
        val launch = packageManager.getLeanbackLaunchIntentForPackage(YOUTUBE_TV_APP)
            ?: packageManager.getLaunchIntentForPackage(YOUTUBE_TV_APP)
            ?: return false
        return startInLauncher(launch)
    }

    private fun smartTubePackage() = SmartTube.PACKAGES.firstOrNull { isInstalled(it) }

    private fun launchIntentFor(pkg: String) =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)

    /** Opens SmartTube with the search for [query], the video in [url], or its home screen. */
    private fun openInSmartTube(url: String, query: String?): Boolean {
        val pkg = smartTubePackage() ?: return false
        val target = if (query != null) SmartTube.searchUrl(encode(query)) else SmartTube.videoUrl(url)
        val intent = target?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(pkg) }
            ?: launchIntentFor(pkg)
            ?: return false
        return startInLauncher(intent)
    }

    override fun openSmartTube(section: SmartTube.Section) {
        val pkg = smartTubePackage() ?: return toast(getString(R.string.smarttube_not_installed))
        if (startInLauncher(Intent().setComponent(section.component(pkg)))) return
        // An older SmartTube may lack the section; open the app instead
        val launch = launchIntentFor(pkg)
        if (launch == null || !startInLauncher(launch)) toast(getString(R.string.app_missing))
    }

    override fun smartTubeClicked() {
        if (smartTubePackage() != null) {
            openSmartTube(SmartTube.Section.HOME)
            return
        }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.smarttube_install_title)
            .setMessage(R.string.smarttube_install_message)
            .setPositiveButton(R.string.smarttube_install) { _, _ -> installSmartTube() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Downloads SmartTube's latest stable APK from its GitHub releases and installs it. */
    private fun installSmartTube() {
        if (!updates.canInstall()) return askInstallPermission()
        val (dialog, status) = progressDialog(R.string.smarttube_install_title)
        status.text = getString(R.string.smarttube_finding)
        io.execute {
            val failure = try {
                val release = SmartTube.parseRelease(updates.fetchText(SmartTube.LATEST_RELEASE))
                val asset = SmartTube.pickAsset(release.assets, Build.SUPPORTED_ABIS.toList())
                    ?: throw IOException(getString(R.string.smarttube_no_apk))
                val apk = updates.fetchApk(asset.url, asset.sha256, asset.name) { percent ->
                    runOnUiThread { status.text = getString(R.string.update_downloading, release.version, percent) }
                }
                val pkg = updates.packageOf(apk)
                if (pkg == null || pkg !in SmartTube.PACKAGES) {
                    apk.delete()
                    throw IOException(getString(R.string.update_bad_package))
                }
                runOnUiThread { status.text = getString(R.string.update_installing) }
                updates.install(apk, pkg)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                dialog.dismiss()
                failure?.let { toast(getString(R.string.smarttube_failed, it)) }
            }
        }
    }

    /** Opens in a separate task, which ChromeOS shows as its own window. */
    private fun start(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    /**
     * Opens in the launcher's own task, so ChromeOS shows it inside the launcher window and Back
     * returns home. Apps that insist on their own task still get a window covering the screen.
     */
    private fun startInLauncher(intent: Intent): Boolean = try {
        startActivity(intent, fullScreenOptions())
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun fullScreenOptions(): Bundle {
        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            windowManager.maximumWindowMetrics.bounds
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            Rect(0, 0, size.x, size.y)
        }
        // Launch bounds only apply to ChromeOS free-form windows; other devices ignore them
        return ActivityOptions.makeBasic().setLaunchBounds(bounds).toBundle()
    }

    private fun isInstalled(pkg: String) = updates.installedVersion(pkg) != null

    /** Menu and form label for an opener, e.g. "Mở bằng Chrome". */
    private fun openerChoice(opener: String) = getString(
        when (opener) {
            OPENER_LAUNCHER -> R.string.opener_launcher
            OPENER_YOUTUBE_TV -> R.string.opener_youtube_tv
            OPENER_SMARTTUBE -> R.string.opener_smarttube
            OPENER_BROWSER4K -> R.string.opener_browser4k
            else -> R.string.opener_chrome
        }
    )

    // --- Card menus and editing ---

    private fun showMenu(title: String, actions: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    override fun appMenu(pkg: String, inFavorites: Boolean) {
        val app = apps.firstOrNull { it.pkg == pkg } ?: return
        val installed = apps.map { it.pkg }.toSet()
        val isFavorite = pkg in store.favorites()
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (isFavorite) {
            actions += getString(R.string.unpin) to {
                store.updateFavorites { it.remove(pkg) }
                refreshHome(force = true)
            }
        } else {
            actions += getString(R.string.pin) to {
                store.updateFavorites { it.add(pkg) }
                refreshHome(force = true)
            }
        }
        if (inFavorites) {
            for ((labelRes, delta) in listOf(R.string.move_left to -1, R.string.move_right to 1)) {
                actions += getString(labelRes) to {
                    store.updateFavorites {
                        it.retainAll { favorite -> favorite in installed }
                        it.move(pkg, delta)
                    }
                    refreshHome(force = true)
                }
            }
        }
        val separate = pkg in store.separateWindowApps()
        actions += getString(if (separate) R.string.open_in_launcher else R.string.open_in_window) to {
            store.setSeparateWindow(pkg, !separate)
        }
        actions += getString(R.string.app_info) to {
            start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)))
            Unit
        }
        showMenu(app.label, actions)
    }

    override fun webMenu(shortcut: WebShortcut) {
        val actions = mutableListOf<Pair<String, () -> Unit>>(getString(R.string.edit) to { editWeb(shortcut) })
        for (other in OPENERS.filter { it != shortcut.opener }) {
            actions += openerChoice(other) to {
                store.updateWeb { list ->
                    val i = list.indexOfFirst { it.id == shortcut.id }
                    if (i >= 0) list[i] = list[i].copy(opener = other)
                }
                refreshHome(force = true)
            }
        }
        for ((labelRes, delta) in listOf(R.string.move_left to -1, R.string.move_right to 1)) {
            actions += getString(labelRes) to {
                store.updateWeb { list ->
                    val item = list.firstOrNull { it.id == shortcut.id }
                    if (item != null) list.move(item, delta)
                }
                refreshHome(force = true)
            }
        }
        actions += getString(R.string.delete) to { confirmDelete(shortcut) }
        showMenu(shortcut.title, actions)
    }

    private fun confirmDelete(shortcut: WebShortcut) {
        AlertDialog.Builder(this, DIALOG_THEME)
            .setMessage(getString(R.string.delete_confirm, shortcut.title))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.updateWeb { list -> list.removeAll { it.id == shortcut.id } }
                refreshHome(force = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editWeb(existing: WebShortcut?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val title = EditText(this).apply {
            hint = getString(R.string.field_title)
            setText(existing?.title.orEmpty())
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val url = EditText(this).apply {
            hint = getString(R.string.field_url)
            setText(existing?.url.orEmpty())
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val search = EditText(this).apply {
            hint = getString(R.string.field_search)
            setText(existing?.search.orEmpty())
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val openerButtons = OPENERS.associateWith { opener ->
            RadioButton(this).apply {
                id = View.generateViewId()
                text = openerChoice(opener)
            }
        }
        val openers = RadioGroup(this).apply {
            openerButtons.values.forEach { addView(it) }
            check(openerButtons.getValue(existing?.opener ?: OPENER_LAUNCHER).id)
        }
        listOf(title, url, search, openers).forEach { form.addView(it) }

        val dialog = AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(if (existing == null) R.string.add_web else R.string.edit_web)
            .setView(form)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = normalizeUrl(url.text.toString())
                if (address == null) {
                    url.error = getString(R.string.error_url)
                    return@setOnClickListener
                }
                val searchText = search.text.toString().trim()
                val searchAddress = if (searchText.isEmpty()) "" else normalizeUrl(searchText)?.takeIf { it.contains("%s") }
                if (searchAddress == null) {
                    search.error = getString(R.string.error_search)
                    return@setOnClickListener
                }
                val id = existing?.id ?: LauncherStore.newId()
                val updated = WebShortcut(
                    id = id,
                    title = title.text.toString().trim().ifEmpty { hostOf(address) },
                    url = address,
                    opener = openerButtons.entries.firstOrNull { it.value.id == openers.checkedRadioButtonId }?.key
                        ?: OPENER_LAUNCHER,
                    search = searchAddress
                )
                store.updateWeb { list ->
                    val i = list.indexOfFirst { it.id == id }
                    if (i >= 0) list[i] = updated else list.add(updated)
                }
                dialog.dismiss()
                refreshHome(force = true)
            }
        }
        dialog.show()
    }

    // --- Fullscreen ---

    private fun applyFullscreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                if (on) {
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else {
                0
            }
        }
    }

    // --- Remote ---

    private fun hasBluetoothPermission() = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private val bluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { remote.restart() }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun remoteStatusText(): Int = when (remoteState) {
        AtvvRemote.State.READY -> when (modelState) {
            SpeechEngine.ModelState.LOADING -> R.string.remote_loading_voice
            SpeechEngine.ModelState.FAILED -> R.string.remote_voice_failed
            SpeechEngine.ModelState.READY -> R.string.remote_ready
        }
        AtvvRemote.State.CONNECTING -> R.string.remote_connecting
        AtvvRemote.State.NOT_FOUND -> R.string.remote_not_found
        AtvvRemote.State.NO_BLUETOOTH -> R.string.remote_no_bluetooth
        AtvvRemote.State.NO_PERMISSION -> R.string.remote_no_permission
        AtvvRemote.State.NO_VOICE_SERVICE -> R.string.remote_no_voice
    }

    private fun updateRemoteLabel() {
        home.remoteLabel = getString(remoteStatusText())
        home.remoteReady = remoteState == AtvvRemote.State.READY && modelState == SpeechEngine.ModelState.READY
    }

    private fun showRemoteDialog() {
        if (remoteState == AtvvRemote.State.NO_PERMISSION) {
            requestBluetoothPermission()
            return
        }
        val status = getString(remoteStatusText()) + (remoteName?.let { " · $it" } ?: "")
        // What the Bluetooth link went through, so a screenshot shows where it stops
        val clock = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        val details = buildString {
            append(status).append("\n\n").append(getString(R.string.remote_help))
            append("\n\n").append(getString(R.string.remote_details, Build.VERSION.RELEASE, remote.knownDevices().size))
            remote.recentEvents().forEach { entry ->
                append('\n').append(clock.format(Date(entry.time))).append("  ").append(eventText(entry))
            }
        }
        val ready = remoteState == AtvvRemote.State.READY
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.remote_title)
            .setMessage(details)
            .setPositiveButton(if (ready) R.string.remote_test_mic else R.string.remote_reconnect) { _, _ ->
                if (ready) startVoice() else remote.restart()
            }
            .setNeutralButton(R.string.remote_choose) { _, _ -> chooseRemote() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun eventText(entry: AtvvRemote.LogEntry): String {
        val detail = entry.detail.ifEmpty { "—" }
        return when (entry.event) {
            AtvvRemote.Event.NOT_FOUND -> getString(R.string.remote_event_not_found, detail)
            AtvvRemote.Event.FOUND -> getString(R.string.remote_event_found, detail)
            AtvvRemote.Event.CONNECTED -> getString(R.string.remote_event_connected)
            AtvvRemote.Event.DISCONNECTED -> getString(R.string.remote_event_disconnected, detail)
            AtvvRemote.Event.NO_VOICE_SERVICE -> getString(R.string.remote_event_no_voice, detail)
            AtvvRemote.Event.READY -> getString(R.string.remote_event_ready, detail)
            AtvvRemote.Event.MIC_BUTTON -> getString(R.string.remote_event_mic_button)
            AtvvRemote.Event.MIC_OPEN -> getString(R.string.remote_event_mic_open)
            AtvvRemote.Event.AUDIO_START -> getString(R.string.remote_event_audio_start)
            AtvvRemote.Event.AUDIO_STOP -> getString(R.string.remote_event_audio_stop)
            AtvvRemote.Event.MIC_ERROR -> getString(R.string.remote_event_mic_error, detail)
        }
    }

    private fun chooseRemote() {
        val devices = remote.knownDevices()
        val labels = listOf(getString(R.string.remote_auto)) + devices.map { "${it.first} · ${it.second}" }
        val current = store.remoteAddress
        val checked = if (current == null) 0 else devices.indexOfFirst { it.second == current } + 1
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.remote_choose)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                store.remoteAddress = if (which == 0) null else devices[which - 1].second
                dialog.dismiss()
                remote.restart()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // AtvvRemote.Listener: called on the Bluetooth thread

    override fun onRemoteState(state: AtvvRemote.State, name: String?) {
        runOnUiThread {
            remoteState = state
            remoteName = name
            updateRemoteLabel()
        }
    }

    override fun onMicRequested() {
        runOnUiThread {
            main.removeCallbacks(voiceKeyFallback)
            showVoice(VoicePanel(getString(R.string.voice_opening)))
            main.removeCallbacks(micTimeout)
            main.postDelayed(micTimeout, MIC_OPEN_TIMEOUT_MS)
        }
    }

    override fun onMicError(code: Int) {
        runOnUiThread {
            main.removeCallbacks(micTimeout)
            showVoice(VoicePanel(getString(R.string.voice_mic_error)))
            main.postDelayed(hideVoiceRunnable, 4000)
        }
    }

    override fun onVoiceStart() {
        speech.begin()
        runOnUiThread {
            transcriptHandled = false
            val status = if (modelState == SpeechEngine.ModelState.READY) R.string.voice_listening else R.string.voice_model_loading
            showVoice(VoicePanel(getString(status), listening = true))
            main.removeCallbacks(micTimeout)
            main.removeCallbacks(voiceTimeout)
            main.postDelayed(voiceTimeout, MAX_LISTEN_MS)
        }
    }

    override fun onVoiceAudio(pcm: ShortArray) {
        speech.accept(pcm)
    }

    override fun onVoiceStop() {
        speech.end()
        runOnUiThread {
            main.removeCallbacks(voiceTimeout)
            val panel = home.voice
            if (!transcriptHandled && panel != null) {
                home.voice = panel.copy(status = getString(R.string.voice_recognizing), listening = false)
            }
        }
    }

    // SpeechEngine.Listener: called on the speech thread

    override fun onModelState(state: SpeechEngine.ModelState) {
        runOnUiThread {
            modelState = state
            updateRemoteLabel()
        }
    }

    override fun onPartial(text: String) {
        runOnUiThread {
            val panel = home.voice
            if (!transcriptHandled && panel != null) home.voice = panel.copy(transcript = text)
        }
    }

    override fun onFinal(text: String) {
        runOnUiThread {
            remote.closeMicrophone()
            handleTranscript(text)
        }
    }

    // --- Remote buttons ---

    /** A button pressed in the web player arrives here; it runs once the home screen resumes. */
    private fun takeButtonAction(intent: Intent?) {
        if (intent == null) return
        val value = intent.getStringExtra(EXTRA_BUTTON_ACTION) ?: return
        intent.removeExtra(EXTRA_BUTTON_ACTION)
        pendingButton = ButtonAction.parse(value)
    }

    private fun runButtonAction(action: ButtonAction) {
        when (action) {
            ButtonAction.Voice -> startVoice()
            ButtonAction.Home -> home.showTab(HomeTab.HOME)
            ButtonAction.AllApps -> home.showTab(HomeTab.APPS)
            ButtonAction.Fullscreen -> toggleFullscreen()
            ButtonAction.Ignore -> Unit
            is ButtonAction.OpenApp -> launchApp(action.pkg)
            is ButtonAction.OpenWeb -> {
                val shortcut = store.webShortcuts().firstOrNull { it.id == action.id }
                if (shortcut != null) openWeb(shortcut) else toast(getString(R.string.button_web_missing))
            }
        }
    }

    private fun updateButtonsLabel() {
        home.buttonsLabel = if (buttons.isEmpty()) getString(R.string.buttons_none) else getString(R.string.buttons_count, buttons.size)
    }

    override fun buttonsClicked() {
        val assigned = buttons.entries.sortedBy { buttonName(it.key) }
        val labels = listOf(getString(R.string.button_add)) +
            assigned.map { getString(R.string.button_binding, buttonName(it.key), actionLabel(it.value)) }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.buttons_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) learnButton() else chooseAction(assigned[which - 1].key)
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /**
     * Waits for a button press. Buttons ChromeOS keeps for itself never arrive, so nothing
     * happens for them; the message says so.
     */
    private fun learnButton() {
        val dialog = AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.button_learn_title)
            .setMessage(R.string.button_learn_message)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) return@setOnKeyListener false
            if (!RemoteButtons.canAssign(keyCode, event.isPrintingKey)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    dialog.setMessage(getString(R.string.button_learn_reserved, buttonName(keyCode)) + "\n\n" + getString(R.string.button_learn_message))
                }
                // The arrows and OK still reach the Cancel button
                return@setOnKeyListener false
            }
            // Act on release, so the release doesn't land in the next dialog
            if (event.action == KeyEvent.ACTION_UP) {
                dialog.dismiss()
                chooseAction(keyCode)
            }
            true
        }
        dialog.show()
    }

    private fun chooseAction(keyCode: Int) {
        val choices = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.action_voice) to { assign(keyCode, ButtonAction.Voice) },
            getString(R.string.action_home) to { assign(keyCode, ButtonAction.Home) },
            getString(R.string.action_apps) to { assign(keyCode, ButtonAction.AllApps) },
            getString(R.string.action_open_app) to { pickApp(keyCode) },
            getString(R.string.action_open_web) to { pickWeb(keyCode) },
            getString(R.string.action_fullscreen) to { assign(keyCode, ButtonAction.Fullscreen) },
            getString(R.string.action_ignore) to { assign(keyCode, ButtonAction.Ignore) }
        )
        if (keyCode in buttons) choices += getString(R.string.button_clear) to { assign(keyCode, null) }
        showMenu(getString(R.string.button_choose_action, buttonName(keyCode)), choices)
    }

    private fun pickApp(keyCode: Int) {
        if (apps.isEmpty()) return toast(getString(R.string.app_missing))
        showMenu(getString(R.string.action_open_app), apps.map { app -> app.label to { assign(keyCode, ButtonAction.OpenApp(app.pkg)) } })
    }

    private fun pickWeb(keyCode: Int) {
        val web = store.webShortcuts()
        if (web.isEmpty()) return toast(getString(R.string.button_no_web))
        showMenu(getString(R.string.action_open_web), web.map { shortcut -> shortcut.title to { assign(keyCode, ButtonAction.OpenWeb(shortcut.id)) } })
    }

    /** Saves the button; a null [action] clears it. */
    private fun assign(keyCode: Int, action: ButtonAction?) {
        store.setButtonAction(keyCode, action)
        buttons = store.buttonActions()
        updateButtonsLabel()
        toast(
            if (action == null) getString(R.string.button_cleared, buttonName(keyCode))
            else getString(R.string.button_saved, buttonName(keyCode), actionLabel(action))
        )
    }

    private fun actionLabel(action: ButtonAction): String = when (action) {
        ButtonAction.Voice -> getString(R.string.action_voice)
        ButtonAction.Home -> getString(R.string.action_home)
        ButtonAction.AllApps -> getString(R.string.action_apps)
        ButtonAction.Fullscreen -> getString(R.string.action_fullscreen)
        ButtonAction.Ignore -> getString(R.string.action_ignore)
        is ButtonAction.OpenApp ->
            getString(R.string.action_open_target, apps.firstOrNull { it.pkg == action.pkg }?.label ?: action.pkg)
        is ButtonAction.OpenWeb ->
            getString(R.string.action_open_target, store.webShortcuts().firstOrNull { it.id == action.id }?.title ?: getString(R.string.action_web_deleted))
    }

    /** "Menu", "Phát/Tạm dừng", or Android's own name such as "F5" for other keys. */
    private fun buttonName(keyCode: Int): String {
        RemoteButtons.NAMES[keyCode]?.let { return getString(it) }
        val name = KeyEvent.keyCodeToString(keyCode)
        if (!name.startsWith("KEYCODE_")) return getString(R.string.key_code, keyCode)
        return name.removePrefix("KEYCODE_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }

    // --- Voice panel ---

    private fun onVoiceKey() {
        // The mic button normally starts a session over Bluetooth by itself. When only the key
        // arrives, the launcher opens the microphone
        if (remoteState == AtvvRemote.State.READY || remoteState == AtvvRemote.State.CONNECTING) {
            main.removeCallbacks(voiceKeyFallback)
            main.postDelayed(voiceKeyFallback, VOICE_KEY_GRACE_MS)
        } else {
            showVoice(VoicePanel(getString(R.string.voice_remote_offline)))
            main.postDelayed(hideVoiceRunnable, 3500)
        }
    }

    /** Opens the remote's microphone from the launcher, for an assigned button or a test. */
    private fun startVoice() {
        if (remoteState == AtvvRemote.State.READY || remoteState == AtvvRemote.State.CONNECTING) {
            showVoice(VoicePanel(getString(R.string.voice_opening)))
            main.removeCallbacks(micTimeout)
            main.postDelayed(micTimeout, MIC_OPEN_TIMEOUT_MS)
            remote.openMicrophone()
        } else {
            showVoice(VoicePanel(getString(R.string.voice_remote_offline)))
            main.postDelayed(hideVoiceRunnable, 3500)
        }
    }

    /** The microphone was asked to open but no audio came. */
    private fun onMicSilent() {
        remote.closeMicrophone()
        showVoice(VoicePanel(getString(R.string.voice_no_audio)))
        main.postDelayed(hideVoiceRunnable, 5000)
    }

    private fun showVoice(panel: VoicePanel) {
        main.removeCallbacks(hideVoiceRunnable)
        pendingVoice?.let { main.removeCallbacks(it) }
        pendingVoice = null
        home.voice = panel
    }

    private fun hideVoice() {
        pendingVoice?.let { main.removeCallbacks(it) }
        pendingVoice = null
        main.removeCallbacks(voiceTimeout)
        main.removeCallbacks(micTimeout)
        main.removeCallbacks(voiceKeyFallback)
        main.removeCallbacks(hideVoiceRunnable)
        transcriptHandled = true
        home.voice = null
    }

    private fun handleTranscript(text: String) {
        if (transcriptHandled && home.voice == null) return
        transcriptHandled = true
        if (text.isBlank()) {
            home.voice = VoicePanel(getString(if (speech.isReady) R.string.voice_not_heard else R.string.voice_model_not_ready))
            main.postDelayed(hideVoiceRunnable, 2500)
            return
        }
        val web = store.webShortcuts()
        when (val action = VoiceCommands.parse(text, apps.map { it.label to it.pkg }, web)) {
            is VoiceAction.LaunchApp -> runVoiceAction(text, getString(R.string.voice_opening_target, action.label)) {
                launchApp(action.pkg)
            }
            is VoiceAction.OpenWeb -> runVoiceAction(text, getString(R.string.voice_opening_target, action.shortcut.title)) {
                openUrl(action.shortcut.url, action.shortcut.opener)
            }
            is VoiceAction.Search -> {
                val site = action.shortcut?.title ?: "Google"
                runVoiceAction(text, getString(R.string.voice_searching, action.query, site)) {
                    search(action.shortcut, action.query)
                }
            }
            is VoiceAction.Choose -> offerChoices(text, action.query.ifEmpty { text }, web)
        }
    }

    private fun runVoiceAction(transcript: String, status: String, action: () -> Unit) {
        home.voice = VoicePanel(status, transcript)
        val run = Runnable {
            pendingVoice = null
            hideVoice()
            action()
        }
        pendingVoice = run
        main.postDelayed(run, 900)
    }

    private fun offerChoices(transcript: String, query: String, web: List<WebShortcut>) {
        val choices = web.filter { it.search.isNotEmpty() }.map { shortcut ->
            VoiceChoice(getString(R.string.search_on, shortcut.title)) { hideVoice(); search(shortcut, query) }
        } + VoiceChoice(getString(R.string.search_on, "Google")) { hideVoice(); search(null, query) } +
            VoiceChoice(getString(R.string.close)) { hideVoice() }
        home.voice = VoicePanel(getString(R.string.voice_choose), transcript, choices = choices)
    }

    private fun search(shortcut: WebShortcut?, query: String) {
        val encoded = encode(query)
        if (shortcut == null) {
            openUrl("https://www.google.com/search?q=$encoded", OPENER_LAUNCHER)
        } else {
            openUrl(shortcut.search.replace("%s", encoded), shortcut.opener, query)
        }
    }

    // --- Updates ---

    private fun checkUpdates(quiet: Boolean, onDone: ((Throwable?) -> Unit)? = null) {
        val url = store.updateUrl
        io.execute {
            val result = runCatching { updates.check(url) }
            runOnUiThread {
                lastUpdateCheck = System.currentTimeMillis()
                result.onSuccess { list ->
                    // Only the launcher updates itself
                    availableUpdates = list.filter { it.pkg == packageName && it.hasUpdate }
                }
                home.updateAvailable = availableUpdates.isNotEmpty()
                updateVersionLabel()
                if (!quiet || onDone != null) onDone?.invoke(result.exceptionOrNull())
            }
        }
    }

    private fun updateVersionLabel() {
        val update = availableUpdates.firstOrNull()
        home.updateLabel = if (update != null) {
            getString(R.string.update_version_new, update.versionName)
        } else {
            getString(R.string.update_version_current, currentVersionName())
        }
    }

    private fun currentVersionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

    private fun showUpdates() {
        val progress = AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.update_title)
            .setMessage(R.string.update_checking)
            .setNegativeButton(R.string.close, null)
            .show()
        checkUpdates(quiet = false) { error ->
            if (!progress.isShowing) return@checkUpdates
            progress.dismiss()
            if (error != null) showUpdateError(error) else showUpdateList()
        }
    }

    private fun showUpdateError(error: Throwable) {
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.update_title)
            .setMessage(getString(R.string.update_error, store.updateUrl, error.message ?: error.javaClass.simpleName))
            .setPositiveButton(R.string.update_retry) { _, _ -> showUpdates() }
            .setNeutralButton(R.string.update_server) { _, _ -> editUpdateServer() }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun showUpdateList() {
        val update = availableUpdates.firstOrNull()
        val builder = AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.update_title)
            .setNeutralButton(R.string.update_server) { _, _ -> editUpdateServer() }
            .setNegativeButton(R.string.close, null)
        if (update == null) {
            builder.setMessage(getString(R.string.update_none, currentVersionName()))
        } else {
            builder.setMessage(getString(R.string.update_available, currentVersionName(), update.versionName))
            builder.setPositiveButton(R.string.update_now) { _, _ -> installUpdate(update) }
        }
        builder.show()
    }

    private fun editUpdateServer() {
        val field = EditText(this).apply {
            hint = getString(R.string.update_server_hint)
            setText(store.updateUrl)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val frame = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(field)
        }
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.update_server)
            .setView(frame)
            .setPositiveButton(R.string.save) { _, _ ->
                val value = field.text.toString().trim()
                store.updateUrl = if (value.isEmpty()) LauncherStore.DEFAULT_UPDATE_URL
                else if (value.startsWith("http://") || value.startsWith("https://")) value else "http://$value"
                showUpdates()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun askInstallPermission() {
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.update_title)
            .setMessage(R.string.update_permission)
            .setPositiveButton(R.string.update_permission_open) { _, _ -> start(updates.installPermissionIntent()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** A dialog showing one line of progress text, which the caller updates. */
    private fun progressDialog(title: Int): Pair<AlertDialog, TextView> {
        val status = TextView(this).apply {
            setTextColor(getColor(R.color.text))
            textSize = 16f
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val dialog = AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(title)
            .setView(status)
            .setCancelable(false)
            .show()
        return dialog to status
    }

    private fun installUpdate(update: AppUpdate) {
        if (!updates.canInstall()) return askInstallPermission()
        val (dialog, status) = progressDialog(R.string.update_title)
        io.execute {
            val failure = try {
                val apk = updates.download(update) { percent ->
                    runOnUiThread { status.text = getString(R.string.update_downloading, update.versionName, percent) }
                }
                runOnUiThread { status.text = getString(R.string.update_installing) }
                // The system asks for confirmation; UpdatedReceiver reopens the new version
                updates.install(apk, update.pkg)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                dialog.dismiss()
                failure?.let { toast(getString(R.string.update_failed, it)) }
            }
        }
    }

    // --- Helpers ---

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** %20 rather than "+", which YouTube's TV interface would show literally. */
    private fun encode(query: String) = URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** A [ButtonAction] value; the web player sends its assigned buttons here to run. */
        const val EXTRA_BUTTON_ACTION = "button_action"

        private const val BROWSER4K = "local.chromebox.browser4k"
        private const val YOUTUBE_TV_APP = "com.google.android.youtube.tv"
        private const val MAX_LISTEN_MS = 15_000L
        /** Long enough for the Bluetooth link to come back after the web player closes. */
        private const val MIC_OPEN_TIMEOUT_MS = 10_000L
        /** How long a mic key waits for the remote's own Bluetooth request before acting. */
        private const val VOICE_KEY_GRACE_MS = 700L
        private const val UPDATE_CHECK_INTERVAL_MS = 30 * 60 * 1000L
        private val DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert

        private val VOICE_KEYS = setOf(
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST
        )

        fun hostOf(url: String): String = Uri.parse(url).host?.removePrefix("www.") ?: url

        fun normalizeUrl(text: String): String? {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed.contains(' ')) return null
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
            val host = Uri.parse(withScheme).host ?: return null
            return if (host.contains('.')) withScheme else null
        }
    }
}

/** Moves [item] by [delta] places, staying inside the list. */
private fun <T> MutableList<T>.move(item: T, delta: Int) {
    val from = indexOf(item)
    if (from < 0) return
    val to = (from + delta).coerceIn(0, size - 1)
    removeAt(from)
    add(to, item)
}
