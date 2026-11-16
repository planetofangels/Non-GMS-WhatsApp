package lolz.planetofangels.whatspush.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ConsoleMessage
import android.graphics.Bitmap
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import lolz.planetofangels.whatspush.log.AppLog
import org.json.JSONObject
import java.security.SecureRandom

class WebHost(
    private val app: Context,
    private val listener: Listener,
    private val catchUpTimers: () -> Boolean,
) {
    interface Listener {
        fun onPageMessage(msg: JSONObject)
        fun onPageStarted()
        fun onPageFinished()
        fun onLoadError(reason: String)
        fun onRendererGone(crashed: Boolean)
    }

    private val contextHolder = MutableContextWrapper(app)
    private var reply: JavaScriptReplyProxy? = null
    private val bridgeName = "_" + randomName()

    var webView: WebView? = null
        private set
    var shown = false
        private set

    private var park: FrameLayout? = null

    val supported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    @SuppressLint("SetJavaScriptEnabled")
    fun create() {
        if (webView != null) return
        val wv = WebView(contextHolder)
        webView = wv
        reply = null

        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            blockNetworkImage = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = desktopUserAgent()
        }
        applyUserAgentMetadata(wv)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, false)
        }

        WebViewCompat.addWebMessageListener(wv, bridgeName, setOf(ORIGIN)) { _, message, _, isMainFrame, replyProxy ->
            if (!isMainFrame) return@addWebMessageListener
            reply = replyProxy
            val data = message.data ?: return@addWebMessageListener
            val json = runCatching { JSONObject(data) }.getOrNull() ?: return@addWebMessageListener
            listener.onPageMessage(json)
        }
        WebViewCompat.addDocumentStartJavaScript(wv, pageScript(), setOf(ORIGIN))

        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        layoutOffscreen(wv)
        parkInWindow(wv)
        wv.loadUrl(URL)
        AppLog.info("WebView created (${WebViewCompat.getCurrentWebViewPackage(app)?.versionName ?: "unknown"})")
    }

    fun reload() {
        val wv = webView ?: return
        AppLog.info("Reloading WhatsApp Web")
        reply = null
        wv.loadUrl(URL)
    }

    fun probe() = post(JSONObject().put("t", "probe"))

    fun destroy() {
        val wv = webView ?: return
        webView = null
        reply = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        removePark()
        runCatching {
            wv.stopLoading()
            wv.destroy()
        }
    }

    fun show(activity: Context, container: ViewGroup) {
        val wv = webView ?: return
        contextHolder.baseContext = activity
        (wv.parent as? ViewGroup)?.removeView(wv)
        removePark()
        container.addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wv.settings.blockNetworkImage = false
        shown = true
        post(JSONObject().put("t", "visible").put("v", true))
    }

    fun hide() {
        val wv = webView ?: return
        shown = false
        post(JSONObject().put("t", "visible").put("v", false))
        (wv.parent as? ViewGroup)?.removeView(wv)
        contextHolder.baseContext = app
        wv.settings.blockNetworkImage = true
        layoutOffscreen(wv)
        parkInWindow(wv)
    }

    // outside a window chromium redraws every vsync, and onPause would freeze the page's timers
    private fun parkInWindow(wv: WebView) {
        if (park != null || !Settings.canDrawOverlays(app)) return
        runCatching {
            val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val windowContext = app.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            val holder = ParkLayout(windowContext)
            holder.addView(wv)
            val params = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                alpha = 0f
                title = "WA Push"
            }
            windowContext.getSystemService(WindowManager::class.java).addView(holder, params)
            park = holder
        }.onFailure {
            AppLog.warn("Can't park the page in a window: ${it::class.java.simpleName}")
            (wv.parent as? ViewGroup)?.removeView(wv)
            layoutOffscreen(wv)
        }
    }

    fun repark() {
        val wv = webView ?: return
        if (shown || park != null || !Settings.canDrawOverlays(app)) return
        (wv.parent as? ViewGroup)?.removeView(wv)
        parkInWindow(wv)
        if (park != null) AppLog.info("Page moved to its window")
    }

    private fun removePark() {
        val holder = park ?: return
        park = null
        holder.removeAllViews()
        runCatching { holder.context.getSystemService(WindowManager::class.java).removeView(holder) }
    }

    private class ParkLayout(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            for (i in 0 until childCount) {
                getChildAt(i).measure(
                    View.MeasureSpec.makeMeasureSpec(OFFSCREEN_W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(OFFSCREEN_H, View.MeasureSpec.EXACTLY),
                )
            }
            setMeasuredDimension(1, 1)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for (i in 0 until childCount) getChildAt(i).layout(0, 0, OFFSCREEN_W, OFFSCREEN_H)
        }
    }

    private fun post(msg: JSONObject) {
        runCatching { reply?.postMessage(msg.toString()) }
            .onFailure { AppLog.warn("Posting to page failed: ${it::class.java.simpleName}") }
    }

    private fun pageScript(): String =
        app.assets.open("page.js").bufferedReader().use { it.readText() }.replace("__BRIDGE_NAME__", bridgeName)
            .replace("__CATCH_UP__", catchUpTimers().toString())

    private fun layoutOffscreen(wv: WebView) {
        val w = View.MeasureSpec.makeMeasureSpec(OFFSCREEN_W, View.MeasureSpec.EXACTLY)
        val h = View.MeasureSpec.makeMeasureSpec(OFFSCREEN_H, View.MeasureSpec.EXACTLY)
        wv.measure(w, h)
        wv.layout(0, 0, OFFSCREEN_W, OFFSCREEN_H)
    }

    private fun chromeMajor(): String =
        Regex("Chrome/(\\d+)").find(WebSettings.getDefaultUserAgent(app))?.groupValues?.get(1) ?: "140"

    private fun desktopUserAgent(): String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/${chromeMajor()}.0.0.0 Safari/537.36"

    private fun applyUserAgentMetadata(wv: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            AppLog.warn("WebView can't set client hints; it may look like a phone to WhatsApp")
            return
        }
        val major = chromeMajor()
        val full = "$major.0.0.0"
        fun brand(name: String, version: String, fullVersion: String) =
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand(name).setMajorVersion(version).setFullVersion(fullVersion).build()
        val metadata = UserAgentMetadata.Builder()
            .setBrandVersionList(
                listOf(
                    brand("Google Chrome", major, full),
                    brand("Chromium", major, full),
                    brand("Not_A Brand", "24", "24.0.0.0"),
                ),
            )
            .setFullVersion(full)
            .setPlatform("Linux")
            .setPlatformVersion("6.8.0")
            .setArchitecture("x86")
            .setBitness(64)
            .setModel("")
            .setMobile(false)
            .setWow64(false)
            .build()
        WebSettingsCompat.setUserAgentMetadata(wv.settings, metadata)
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.scheme == "https" && uri.host == HOST) return false

            if (shown && request.hasGesture()) {
                runCatching {
                    app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (Uri.parse(url).host == HOST) listener.onPageStarted()
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (Uri.parse(url).host == HOST) listener.onPageFinished()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) listener.onLoadError("error ${error.errorCode}")
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (request.isForMainFrame && response.statusCode >= 400) listener.onLoadError("HTTP ${response.statusCode}")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view == webView) {
                destroy()
                listener.onRendererGone(detail.didCrash())
            } else {
                runCatching { view.destroy() }
            }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) =
            callback.invoke(origin, false, false)

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = false

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean = true
    }

    companion object {
        const val HOST = "web.whatsapp.com"
        const val ORIGIN = "https://web.whatsapp.com"
        const val URL = "https://web.whatsapp.com/"
        private const val OFFSCREEN_W = 1280
        private const val OFFSCREEN_H = 800

        private fun randomName(): String {
            val chars = "abcdefghijklmnopqrstuvwxyz"
            val rnd = SecureRandom()
            return (1..12).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }
    }
}
