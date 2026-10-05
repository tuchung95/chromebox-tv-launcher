package local.chromebox.tvlauncher

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import local.chromebox.tvlauncher.LauncherStore.Companion.OPENER_BROWSER4K
import local.chromebox.tvlauncher.LauncherStore.Companion.OPENER_CHROME
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * A 10-foot home screen for ChromeOS: large tiles driven by arrow keys, a TV remote or the
 * mouse, voice commands through a Xiaomi Bluetooth remote, and in-app updates.
 */
class MainActivity : Activity(), AtvvRemote.Listener, SpeechEngine.Listener {

    private data class AppEntry(
        val pkg: String,
        val label: String,
        val component: ComponentName,
        val category: String,
        val info: ActivityInfo
    )

    private lateinit var store: LauncherStore
    private lateinit var rows: LinearLayout
    private lateinit var remoteButton: TextView
    private lateinit var updateButton: TextView
    private lateinit var fullscreenButton: TextView
    private lateinit var voiceOverlay: View
    private lateinit var voiceMic: View
    private lateinit var voiceStatus: TextView
    private lateinit var voiceText: TextView
    private lateinit var voiceActions: LinearLayout

    private lateinit var remote: AtvvRemote
    private lateinit var speech: SpeechEngine
    private lateinit var updates: UpdateManager

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var apps: List<AppEntry> = emptyList()
    private var lastSignature = ""
    private var lastFocusKey: String? = null
    private var firstTile: View? = null

    private var remoteState = AtvvRemote.State.CONNECTING
    private var remoteName: String? = null
    private var modelState = SpeechEngine.ModelState.LOADING
    private var transcriptHandled = true
    private var pendingVoice: Runnable? = null
    private var micPulse: ObjectAnimator? = null
    private val voiceTimeout = Runnable { remote.closeMicrophone() }
    private val hideVoiceRunnable = Runnable { hideVoice() }

    private var availableUpdates: List<AppUpdate> = emptyList()
    private var lastUpdateCheck = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = LauncherStore(this)
        updates = UpdateManager(this)

        rows = findViewById(R.id.rows)
        remoteButton = findViewById(R.id.btn_remote)
        updateButton = findViewById(R.id.btn_update)
        fullscreenButton = findViewById(R.id.btn_fullscreen)
        voiceOverlay = findViewById(R.id.voice_overlay)
        voiceMic = findViewById(R.id.voice_mic)
        voiceStatus = findViewById(R.id.voice_status)
        voiceText = findViewById(R.id.voice_text)
        voiceActions = findViewById(R.id.voice_actions)

        remoteButton.setOnClickListener { showRemoteDialog() }
        updateButton.setOnClickListener { showUpdates() }
        fullscreenButton.setOnClickListener { setFullscreen(!store.fullscreen) }
        voiceOverlay.setOnClickListener { cancelVoice() }

        speech = SpeechEngine(this, this)
        speech.load()
        remote = AtvvRemote(this, { store.remoteAddress }, this)

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { onBack() }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!hasBluetoothPermission()) requestBluetoothPermission()
        remote.start()
        if (System.currentTimeMillis() - lastUpdateCheck > UPDATE_CHECK_INTERVAL_MS) checkUpdates(quiet = true)
    }

    override fun onResume() {
        super.onResume()
        applyFullscreen(store.fullscreen)
        updateFullscreenButton()
        updateRemoteButton()
        renderIfChanged()
    }

    override fun onPause() {
        lastFocusKey = currentFocus?.tag as? String
        super.onPause()
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

    @Deprecated("Used on Android 12L and older, where ChromeOS ARC runs")
    override fun onBackPressed() {
        onBack()
    }

    /** A launcher never closes itself on Back. */
    private fun onBack() {
        when {
            voiceOverlay.visibility == View.VISIBLE -> cancelVoice()
            store.fullscreen -> setFullscreen(false)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code in VOICE_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onVoiceKey()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_F11) {
            setFullscreen(!store.fullscreen)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BLUETOOTH) remote.restart()
    }

    // --- Home screen ---

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

    private fun renderIfChanged(force: Boolean = false) {
        val loaded = loadApps()
        val signature = loaded.joinToString(",") { it.pkg + "/" + it.label } + "#" + store.signature()
        if (!force && signature == lastSignature && rows.childCount > 0) return
        lastSignature = signature
        apps = loaded
        render()
    }

    private fun refresh(focusKey: String?) {
        lastFocusKey = focusKey
        renderIfChanged(force = true)
    }

    private fun render() {
        rows.removeAllViews()
        firstTile = null
        val web = store.webShortcuts()
        addRow(getString(R.string.row_watch), web.map { webTile(it) } + addWebTile())

        val favorites = store.favorites()
        val byPackage = apps.associateBy { it.pkg }
        val favoriteApps = favorites.mapNotNull { byPackage[it] }
        addRow(getString(R.string.row_favorites), favoriteApps.map { appTile(it, inFavoritesRow = true, isFavorite = true) })
        addRow(getString(R.string.row_apps), apps.map { appTile(it, inFavoritesRow = false, isFavorite = it.pkg in favorites) })

        val target = lastFocusKey?.let { rows.findViewWithTag<View>(it) } ?: firstTile
        target?.requestFocus()
    }

    private fun addRow(title: String, tiles: List<View>) {
        if (tiles.isEmpty()) return
        rows.addView(label(title, 20f, getColor(R.color.text_dim), bold = true).apply {
            setPadding(dp(GUTTER), dp(20), dp(GUTTER), 0)
        })
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Vertical room so a focused, enlarged tile is not clipped
            setPadding(dp(GUTTER), dp(16), dp(GUTTER), dp(18))
            clipChildren = false
            clipToPadding = false
        }
        tiles.forEach { strip.addView(it) }
        if (firstTile == null) firstTile = tiles.first()
        rows.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(strip)
        })
    }

    private fun makeTile(key: String, color: Int, onClick: () -> Unit, onMenu: () -> Unit): FrameLayout =
        FrameLayout(this).apply {
            tag = key
            layoutParams = LinearLayout.LayoutParams(dp(TILE_WIDTH), dp(TILE_HEIGHT)).apply { marginEnd = dp(22) }
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(color)
            }
            foreground = getDrawable(R.drawable.tile_focus)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
            setOnLongClickListener { onMenu(); true }
            setOnContextClickListener { onMenu(); true } // right click on ChromeOS
            setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                    onMenu(); true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { v, focused -> lift(v, focused) }
            setOnHoverListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_HOVER_ENTER -> lift(v, true)
                    MotionEvent.ACTION_HOVER_EXIT -> if (!v.isFocused) lift(v, false)
                }
                false
            }
        }

    private fun lift(view: View, up: Boolean) {
        view.animate()
            .scaleX(if (up) 1.08f else 1f)
            .scaleY(if (up) 1.08f else 1f)
            .translationZ(if (up) dp(10).toFloat() else 0f)
            .setDuration(140)
            .start()
    }

    private fun webTile(shortcut: WebShortcut): View {
        val card = makeTile(
            "web:${shortcut.id}", webColor(shortcut.url),
            { openUrl(shortcut.url, shortcut.opener) }, { webMenu(shortcut) }
        )
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        column.addView(label(shortcut.title, 24f, Color.WHITE, bold = true))
        column.addView(label(hostOf(shortcut.url), 14f, 0xCCFFFFFF.toInt()))
        card.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        card.addView(badge(openerName(shortcut.opener)), cornerParams())
        card.contentDescription = shortcut.title
        return card
    }

    private fun addWebTile(): View {
        val card = makeTile(KEY_ADD, getColor(R.color.surface), { editWeb(null) }, { editWeb(null) })
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        column.addView(label("+", 40f, getColor(R.color.accent)).apply { gravity = Gravity.CENTER })
        column.addView(label(getString(R.string.add_web), 16f, getColor(R.color.text)).apply { gravity = Gravity.CENTER })
        card.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        card.contentDescription = getString(R.string.add_web)
        return card
    }

    private fun appTile(app: AppEntry, inFavoritesRow: Boolean, isFavorite: Boolean): View {
        val prefix = if (inFavoritesRow) "fav:" else "app:"
        val card = makeTile(
            prefix + app.pkg, getColor(R.color.surface),
            { launchApp(app) }, { appMenu(app, inFavoritesRow, isFavorite) }
        )
        val banner = runCatching { app.info.loadBanner(packageManager) }.getOrNull()
        if (banner != null) {
            card.addView(ImageView(this).apply {
                setImageDrawable(banner)
                scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }
            column.addView(ImageView(this).apply {
                setImageDrawable(app.info.loadIcon(packageManager))
            }, LinearLayout.LayoutParams(dp(64), dp(64)))
            column.addView(label(app.label, 16f, getColor(R.color.text)).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            card.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        if (isFavorite && !inFavoritesRow) {
            card.addView(label("★", 18f, getColor(R.color.accent)), cornerParams())
        }
        card.contentDescription = app.label
        return card
    }

    private fun badge(text: String) = label(text, 12f, Color.WHITE).apply {
        background = getDrawable(R.drawable.badge_bg)
        setPadding(dp(8), dp(3), dp(8), dp(3))
    }

    private fun cornerParams() = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
        setMargins(dp(12), dp(10), dp(12), dp(10))
    }

    private fun label(text: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    // --- Launching ---

    private fun launchApp(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(app.category)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (!start(intent)) toast(getString(R.string.app_missing))
    }

    private fun launchPackage(pkg: String) {
        apps.firstOrNull { it.pkg == pkg }?.let { launchApp(it) } ?: toast(getString(R.string.app_missing))
    }

    private fun openUrl(url: String, opener: String) {
        val uri = Uri.parse(url)
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

    private fun start(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun isInstalled(pkg: String) = updates.installedVersion(pkg) != null

    private fun openerName(opener: String) =
        getString(if (opener == OPENER_BROWSER4K) R.string.name_browser4k else R.string.name_chrome)

    // --- Tile menus and editing ---

    private fun showMenu(title: String, actions: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun appMenu(app: AppEntry, inFavoritesRow: Boolean, isFavorite: Boolean) {
        val installed = apps.map { it.pkg }.toSet()
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (isFavorite) {
            actions += getString(R.string.unpin) to {
                store.updateFavorites { it.remove(app.pkg) }
                refresh("app:${app.pkg}")
            }
        } else {
            actions += getString(R.string.pin) to {
                store.updateFavorites { it.add(app.pkg) }
                refresh("fav:${app.pkg}")
            }
        }
        if (inFavoritesRow) {
            for ((labelRes, delta) in listOf(R.string.move_left to -1, R.string.move_right to 1)) {
                actions += getString(labelRes) to {
                    store.updateFavorites {
                        it.retainAll { pkg -> pkg in installed }
                        it.move(app.pkg, delta)
                    }
                    refresh("fav:${app.pkg}")
                }
            }
        }
        actions += getString(R.string.app_info) to {
            start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null)))
            Unit
        }
        showMenu(app.label, actions)
    }

    private fun webMenu(shortcut: WebShortcut) {
        val other = if (shortcut.opener == OPENER_BROWSER4K) OPENER_CHROME else OPENER_BROWSER4K
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.edit) to { editWeb(shortcut) },
            getString(R.string.open_with, openerName(other)) to {
                store.updateWeb { list ->
                    val i = list.indexOfFirst { it.id == shortcut.id }
                    if (i >= 0) list[i] = list[i].copy(opener = other)
                }
                refresh("web:${shortcut.id}")
            }
        )
        for ((labelRes, delta) in listOf(R.string.move_left to -1, R.string.move_right to 1)) {
            actions += getString(labelRes) to {
                store.updateWeb { list ->
                    val item = list.firstOrNull { it.id == shortcut.id }
                    if (item != null) list.move(item, delta)
                }
                refresh("web:${shortcut.id}")
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
                refresh(KEY_ADD)
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
        val browser4k = RadioButton(this).apply { id = View.generateViewId(); setText(R.string.opener_browser4k) }
        val chrome = RadioButton(this).apply { id = View.generateViewId(); setText(R.string.opener_chrome) }
        val openers = RadioGroup(this).apply {
            addView(browser4k)
            addView(chrome)
            val opener = existing?.opener ?: if (isInstalled(BROWSER4K)) OPENER_BROWSER4K else OPENER_CHROME
            check(if (opener == OPENER_BROWSER4K) browser4k.id else chrome.id)
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
                    opener = if (openers.checkedRadioButtonId == browser4k.id) OPENER_BROWSER4K else OPENER_CHROME,
                    search = searchAddress
                )
                store.updateWeb { list ->
                    val i = list.indexOfFirst { it.id == id }
                    if (i >= 0) list[i] = updated else list.add(updated)
                }
                dialog.dismiss()
                refresh("web:$id")
            }
        }
        dialog.show()
    }

    // --- Fullscreen ---

    private fun setFullscreen(on: Boolean) {
        store.fullscreen = on
        applyFullscreen(on)
        updateFullscreenButton()
    }

    private fun updateFullscreenButton() {
        fullscreenButton.setText(if (store.fullscreen) R.string.fullscreen_exit else R.string.fullscreen_enter)
    }

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

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BLUETOOTH)
        }
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

    private fun updateRemoteButton() {
        remoteButton.setText(remoteStatusText())
        val ready = remoteState == AtvvRemote.State.READY && modelState == SpeechEngine.ModelState.READY
        remoteButton.setTextColor(getColor(if (ready) R.color.accent else R.color.text))
    }

    private fun showRemoteDialog() {
        if (remoteState == AtvvRemote.State.NO_PERMISSION) {
            requestBluetoothPermission()
            return
        }
        val status = getString(remoteStatusText()) + (remoteName?.let { " · $it" } ?: "")
        AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.remote_title)
            .setMessage(status + "\n\n" + getString(R.string.remote_help))
            .setPositiveButton(R.string.remote_reconnect) { _, _ -> remote.restart() }
            .setNeutralButton(R.string.remote_choose) { _, _ -> chooseRemote() }
            .setNegativeButton(R.string.close, null)
            .show()
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
            updateRemoteButton()
        }
    }

    override fun onMicRequested() {
        runOnUiThread { showVoice(R.string.voice_opening, listening = false) }
    }

    override fun onVoiceStart() {
        speech.begin()
        runOnUiThread {
            transcriptHandled = false
            val status = if (modelState == SpeechEngine.ModelState.READY) R.string.voice_listening else R.string.voice_model_loading
            showVoice(status, listening = true)
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
            if (!transcriptHandled && voiceOverlay.visibility == View.VISIBLE) {
                voiceStatus.setText(R.string.voice_recognizing)
                pulse(false)
            }
        }
    }

    // SpeechEngine.Listener: called on the speech thread

    override fun onModelState(state: SpeechEngine.ModelState) {
        runOnUiThread {
            modelState = state
            updateRemoteButton()
        }
    }

    override fun onPartial(text: String) {
        runOnUiThread {
            if (!transcriptHandled && voiceOverlay.visibility == View.VISIBLE) voiceText.text = text
        }
    }

    override fun onFinal(text: String) {
        runOnUiThread {
            remote.closeMicrophone()
            handleTranscript(text)
        }
    }

    // --- Voice panel ---

    private fun onVoiceKey() {
        // The mic button also starts a session over Bluetooth; this only helps when that link is down
        if (remoteState != AtvvRemote.State.READY) {
            showVoice(R.string.voice_remote_offline, listening = false)
            voiceText.text = ""
            main.postDelayed(hideVoiceRunnable, 3500)
        }
    }

    private fun showVoice(statusRes: Int, listening: Boolean) {
        main.removeCallbacks(hideVoiceRunnable)
        pendingVoice?.let { main.removeCallbacks(it) }
        pendingVoice = null
        voiceOverlay.visibility = View.VISIBLE
        voiceStatus.setText(statusRes)
        if (listening) {
            voiceText.text = ""
            voiceText.hint = getString(R.string.voice_hint)
        }
        voiceActions.removeAllViews()
        voiceActions.visibility = View.GONE
        pulse(listening)
    }

    private fun pulse(on: Boolean) {
        micPulse?.cancel()
        micPulse = null
        voiceMic.alpha = 1f
        if (on) {
            micPulse = ObjectAnimator.ofFloat(voiceMic, View.ALPHA, 1f, 0.35f).apply {
                duration = 650
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun cancelVoice() {
        remote.closeMicrophone()
        hideVoice()
    }

    private fun hideVoice() {
        pendingVoice?.let { main.removeCallbacks(it) }
        pendingVoice = null
        main.removeCallbacks(voiceTimeout)
        main.removeCallbacks(hideVoiceRunnable)
        transcriptHandled = true
        pulse(false)
        voiceOverlay.visibility = View.GONE
        (lastFocusKey?.let { rows.findViewWithTag<View>(it) } ?: firstTile)?.requestFocus()
    }

    private fun handleTranscript(text: String) {
        if (transcriptHandled && voiceOverlay.visibility != View.VISIBLE) return
        transcriptHandled = true
        pulse(false)
        voiceOverlay.visibility = View.VISIBLE
        if (text.isBlank()) {
            voiceText.text = ""
            voiceStatus.setText(if (speech.isReady) R.string.voice_not_heard else R.string.voice_model_not_ready)
            main.postDelayed(hideVoiceRunnable, 2500)
            return
        }
        voiceText.text = text
        val web = store.webShortcuts()
        when (val action = VoiceCommands.parse(text, apps.map { it.label to it.pkg }, web)) {
            is VoiceAction.LaunchApp -> runVoiceAction(getString(R.string.voice_opening_target, action.label)) {
                launchPackage(action.pkg)
            }
            is VoiceAction.OpenWeb -> runVoiceAction(getString(R.string.voice_opening_target, action.shortcut.title)) {
                openUrl(action.shortcut.url, action.shortcut.opener)
            }
            is VoiceAction.Search -> {
                val site = action.shortcut?.title ?: "Google"
                runVoiceAction(getString(R.string.voice_searching, action.query, site)) { search(action.shortcut, action.query) }
            }
            is VoiceAction.Choose -> offerChoices(action.query.ifEmpty { text }, web)
        }
    }

    private fun runVoiceAction(status: String, action: () -> Unit) {
        voiceStatus.text = status
        val run = Runnable {
            pendingVoice = null
            hideVoice()
            action()
        }
        pendingVoice = run
        main.postDelayed(run, 900)
    }

    private fun offerChoices(query: String, web: List<WebShortcut>) {
        voiceStatus.setText(R.string.voice_choose)
        voiceActions.removeAllViews()
        voiceActions.visibility = View.VISIBLE
        web.filter { it.search.isNotEmpty() }.forEach { shortcut ->
            addVoiceChoice(getString(R.string.search_on, shortcut.title)) { search(shortcut, query) }
        }
        addVoiceChoice(getString(R.string.search_on, "Google")) { search(null, query) }
        addVoiceChoice(getString(R.string.close)) { }
        voiceActions.getChildAt(0)?.requestFocus()
    }

    private fun addVoiceChoice(text: String, action: () -> Unit) {
        val button = TextView(this, null, 0, R.style.HeaderButton).apply {
            this.text = text
            setOnClickListener {
                hideVoice()
                action()
            }
        }
        voiceActions.addView(button, LinearLayout.LayoutParams(dp(360), dp(48)).apply { topMargin = dp(10) })
    }

    private fun search(shortcut: WebShortcut?, query: String) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        if (shortcut == null) {
            openUrl("https://www.google.com/search?q=$encoded", OPENER_CHROME)
        } else {
            openUrl(shortcut.search.replace("%s", encoded), shortcut.opener)
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
                updateUpdateButton()
                if (!quiet || onDone != null) onDone?.invoke(result.exceptionOrNull())
            }
        }
    }

    private fun updateUpdateButton() {
        val available = availableUpdates.isNotEmpty()
        updateButton.setText(if (available) R.string.update_button_available else R.string.update_button)
        updateButton.setTextColor(getColor(if (available) R.color.accent else R.color.text))
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

    private fun installUpdate(update: AppUpdate) {
        if (!updates.canInstall()) {
            AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(R.string.update_title)
                .setMessage(R.string.update_permission)
                .setPositiveButton(R.string.update_permission_open) { _, _ -> start(updates.installPermissionIntent()) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val status = TextView(this).apply {
            setTextColor(getColor(R.color.text))
            textSize = 16f
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val dialog = AlertDialog.Builder(this, DIALOG_THEME)
            .setTitle(R.string.update_title)
            .setView(status)
            .setCancelable(false)
            .show()
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun webColor(url: String): Int = WEB_COLORS[Math.floorMod(hostOf(url).hashCode(), WEB_COLORS.size)]

    companion object {
        private const val BROWSER4K = "local.chromebox.browser4k"
        private const val KEY_ADD = "add"
        private const val GUTTER = 48
        private const val TILE_WIDTH = 240
        private const val TILE_HEIGHT = 135
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
        private const val REQUEST_BLUETOOTH = 1
        private const val MAX_LISTEN_MS = 15_000L
        private const val UPDATE_CHECK_INTERVAL_MS = 30 * 60 * 1000L
        private val DIALOG_THEME = android.R.style.Theme_Material_Dialog_Alert

        private val VOICE_KEYS = setOf(
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST
        )

        private val WEB_COLORS = intArrayOf(
            0xFFB3261E.toInt(), 0xFF1E5AA8.toInt(), 0xFF2E7D5B.toInt(),
            0xFF7A3FA0.toInt(), 0xFFA8641E.toInt(), 0xFF1F7A8C.toInt()
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
