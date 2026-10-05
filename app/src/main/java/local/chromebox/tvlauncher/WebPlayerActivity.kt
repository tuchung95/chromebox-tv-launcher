package local.chromebox.tvlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Plays a web page full screen inside the launcher's window. Back walks the page history,
 * then returns to the home screen.
 *
 * Tuned for movie sites: the video keeps playing while the ChromeOS window is visible but
 * not focused, a crashed renderer is replaced, and ad popups and app-link redirects are dropped.
 *
 * In TV mode ([EXTRA_TV_MODE]) the page is YouTube's TV interface: it gets a smart-TV user
 * agent, Back is passed to the page, holding Back returns to the launcher, and AV1 is hidden
 * so YouTube streams VP9 or H.264, which the Chromebox GPU decodes.
 */
class WebPlayerActivity : Activity() {

    private lateinit var webContainer: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var progress: ProgressBar

    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var lastUrl = ""
    private var tvMode = false
    private var backLongPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tvMode = intent.getBooleanExtra(EXTRA_TV_MODE, false)
        setContentView(R.layout.activity_web_player)
        webContainer = findViewById(R.id.web_container)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        progress = findViewById(R.id.progress)

        val wv = createWebView()
        webContainer.addView(wv)
        webView = wv

        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { onBack() }
        }

        if (tvMode) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val restored = savedInstanceState != null && wv.restoreState(savedInstanceState) != null
        if (!restored) {
            intent?.dataString?.let { load(it) }
            val hint = if (tvMode) R.string.web_tv_back_hint else R.string.web_back_hint
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { load(it) }
    }

    override fun onResume() {
        super.onResume()
        applyImmersive(true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive(true)
    }

    // Pause only when the window is hidden: an unfocused ChromeOS window stays visible
    override fun onStart() {
        super.onStart()
        webView?.onResume()
    }

    override fun onStop() {
        webView?.onPause()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        webView?.let {
            webContainer.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    @Deprecated("Used on Android 12L and older, where ChromeOS ARC runs")
    override fun onBackPressed() {
        onBack()
    }

    private fun onBack() {
        val wv = webView
        when {
            customView != null -> hideCustomView()
            tvMode -> sendBackToPage()
            wv != null && wv.canGoBack() -> wv.goBack()
            else -> finish()
        }
    }

    /** YouTube's TV interface treats Escape as the remote's Back button. */
    private fun sendBackToPage() {
        val wv = webView ?: return
        wv.requestFocus()
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        wv.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
    }

    // In TV mode a short Back goes to the page and a long Back returns to the launcher

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (tvMode && keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (tvMode && keyCode == KeyEvent.KEYCODE_BACK) {
            backLongPressed = true
            finish()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (tvMode && keyCode == KeyEvent.KEYCODE_BACK) {
            if (!backLongPressed && event.isTracking && !event.isCanceled) onBack()
            backLongPressed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when {
                event.keyCode == KeyEvent.KEYCODE_ESCAPE && customView != null -> {
                    hideCustomView(); return true
                }
                event.keyCode == KeyEvent.KEYCODE_F5 || (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_R) -> {
                    webView?.reload(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun load(url: String) {
        lastUrl = url
        webView?.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(this)
        wv.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        wv.setBackgroundColor(Color.BLACK)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            userAgentString = if (tvMode) TV_USER_AGENT else desktopUserAgent()
        }
        if (tvMode && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, HIDE_AV1_SCRIPT, YOUTUBE_ORIGINS)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Embedded video players live in third-party iframes and need their cookies
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.webViewClient = PageClient()
        wv.webChromeClient = ChromeClient()
        return wv
    }

    /** A ChromeOS desktop user agent, so sites serve their big-screen layout and players. */
    private fun desktopUserAgent(): String {
        val default = WebSettings.getDefaultUserAgent(this)
        val chrome = Regex("Chrome/([\\d.]+)").find(default)?.groupValues?.get(1) ?: "130.0.0.0"
        return "Mozilla/5.0 (X11; CrOS x86_64 14541.0.0) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    private fun applyImmersive(on: Boolean) {
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

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreenContainer.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        fullscreenContainer.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hideCustomView() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun recoverFromRendererCrash(view: WebView) {
        if (view != webView) {
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            return
        }
        hideCustomView()
        webContainer.removeView(view)
        view.destroy()
        val fresh = createWebView()
        webContainer.addView(fresh)
        webView = fresh
        Toast.makeText(this, R.string.web_renderer_restarted, Toast.LENGTH_SHORT).show()
        fresh.loadUrl(lastUrl)
    }

    private inner class PageClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme?.lowercase()
            // Web pages load in place; intent://, market:// and similar links used by ads are dropped
            return scheme != "http" && scheme != "https"
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (view == webView && url.startsWith("http")) lastUrl = url
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            recoverFromRendererCrash(view)
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view != webView) return
            progress.progress = newProgress
            progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = showCustomView(view, callback)

        override fun onHideCustomView() = hideCustomView()

        // Hides the grey play icon WebView draws before a video starts
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            // Windows opened without a click are always ads
            if (!isUserGesture) return false
            val popup = WebView(this@WebPlayerActivity)
            val client = PopupClient(siteOf(view.url))
            popup.webViewClient = client
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            main.postDelayed({ client.discard(popup) }, 10_000)
            return true
        }

        // YouTube's TV interface closes the window when the viewer chooses to exit
        override fun onCloseWindow(window: WebView) {
            if (window == webView) finish()
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                Log.d(TAG, "${message.sourceId()}:${message.lineNumber()} ${message.message()}")
            }
            return false
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Allow DRM-protected playback (Widevine) only; never camera or microphone
            if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
            } else {
                request.deny()
            }
        }
    }

    /** Follows a popup only when it stays on the current site; ad windows are discarded. */
    private inner class PopupClient(private val site: String?) : WebViewClient() {
        private var handled = false

        fun discard(popup: WebView) {
            if (handled) return
            handled = true
            popup.stopLoading()
            popup.destroy()
        }

        private fun route(popup: WebView, url: String) {
            if (handled || url == "about:blank") return
            if (site != null && siteOf(url) == site) load(url)
            main.post { discard(popup) }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            route(view, request.url.toString())
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = route(view, url)
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TV_MODE = "tv_mode"
        private const val TAG = "WebPlayer"

        /** A Samsung smart TV; YouTube serves its TV interface only to TV browsers. */
        private const val TV_USER_AGENT = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) 85.0.4183.93/6.0 TV Safari/537.36"

        private val YOUTUBE_ORIGINS = setOf("https://www.youtube.com", "https://youtube.com", "https://m.youtube.com")

        /**
         * The Chromebox's Celeron decodes AV1 on the CPU only, which stutters. Reporting AV1 as
         * unsupported makes YouTube choose VP9 or H.264, which the GPU decodes up to 4K.
         */
        private val HIDE_AV1_SCRIPT = """
            (function () {
              if (window.__chromeboxHideAv1) return;
              window.__chromeboxHideAv1 = true;
              var blocked = /av01|\bav1\b/i;
              var source = window.MediaSource;
              if (source && source.isTypeSupported) {
                var isTypeSupported = source.isTypeSupported.bind(source);
                source.isTypeSupported = function (type) { return blocked.test(type) ? false : isTypeSupported(type); };
              }
              var capabilities = navigator.mediaCapabilities;
              if (capabilities && capabilities.decodingInfo) {
                var decodingInfo = capabilities.decodingInfo.bind(capabilities);
                capabilities.decodingInfo = function (config) {
                  var type = (config && config.video && config.video.contentType) || '';
                  if (blocked.test(type)) return Promise.resolve({ supported: false, smooth: false, powerEfficient: false });
                  return decodingInfo(config);
                };
              }
              var canPlayType = HTMLMediaElement.prototype.canPlayType;
              HTMLMediaElement.prototype.canPlayType = function (type) { return blocked.test(type) ? '' : canPlayType.call(this, type); };
              console.log('chromebox: AV1 hidden');
            })();
        """.trimIndent()
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT

        /** Registrable part of a host, e.g. "film4k.net" for "cdn.film4k.net". */
        private fun siteOf(url: String?): String? {
            val host = url?.let { Uri.parse(it).host }?.lowercase() ?: return null
            val parts = host.split('.')
            return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host
        }
    }
}
