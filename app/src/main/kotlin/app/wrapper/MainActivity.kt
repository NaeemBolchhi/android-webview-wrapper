package app.wrapper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var bar: ProgressBar
    private lateinit var root: FrameLayout
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    @Volatile private var pageHost: String? = null
    private val homeHost: String? = Uri.parse(BuildConfig.HOME_URL).host

    private val handler = Handler(Looper.getMainLooper())
    private var barColor = 0
    private val poll = object : Runnable {
        override fun run() {
            web.evaluateJavascript(COLOR_JS) { r ->
                val n = (r ?: "").trim('"').split(',').mapNotNull { it.trim().toIntOrNull() }
                if (n.size == 3) applyBarColor(Color.rgb(n[0], n[1], n[2]))
            }
            handler.postDelayed(this, 700)
        }
    }

    /** Paints the area behind the (visible) system bars and picks light/dark icons for it. */
    private fun applyBarColor(c: Int) {
        if (c == barColor) return
        barColor = c
        root.setBackgroundColor(c)
        val light = ColorUtils.calculateLuminance(c) > 0.5
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            bar.progress = newProgress
            bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
        }

        override fun onShowFileChooser(
            w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams
        ): Boolean {
            fileCb?.onReceiveValue(null)
            fileCb = cb
            return try {
                chooser.launch(p.createIntent()); true
            } catch (e: Exception) {
                fileCb = null; cb.onReceiveValue(null); false
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            customView = view; customCb = callback
            web.visibility = View.GONE
            root.addView(view, MATCH_PARENT, MATCH_PARENT)
            WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.systemBars())
        }

        override fun onHideCustomView() {
            customView?.let { root.removeView(it) }
            customView = null
            customCb?.onCustomViewHidden(); customCb = null
            web.visibility = View.VISIBLE
            WindowCompat.getInsetsController(window, root).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        web = WebView(this)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        root = FrameLayout(this).apply {
            addView(web, MATCH_PARENT, MATCH_PARENT)
            addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, (3 * resources.displayMetrics.density).toInt(), Gravity.TOP))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(CosmeticBridge(), "__ubo")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, Blocker.SCRIPT, setOf("*"))
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageHost = Uri.parse(url).host
            }

            // Fallback in case the document-start script ran before the bridge existed
            override fun onPageCommitVisible(view: WebView, url: String) {
                view.evaluateJavascript(Blocker.SCRIPT, null)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.isForMainFrame && Blocker.shouldBlock(request.url.host.orEmpty(), pageHost)) {
                    return WebResourceResponse(
                        "text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                when (u.scheme) {
                    "http", "https" -> {
                        if (Blocker.sameSite(u.host, homeHost) || request.isRedirect || !request.hasGesture()) return false
                    }
                    "about", "blob", "data", "javascript" -> return false
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (_: Exception) {
                }
                return true
            }
        }
        web.webChromeClient = chrome

        web.setDownloadListener { url, ua, cd, _, _ ->
            if (url.startsWith("http://") || url.startsWith("https://")) {
                DownloadService.enqueue(this, url, ua, cd, web.url)
            } else {
                Toast.makeText(this, "This download type is not supported", Toast.LENGTH_SHORT).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> chrome.onHideCustomView()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            Thread {
                Blocker.load(applicationContext)
                runOnUiThread { if (!isDestroyed) web.loadUrl(BuildConfig.HOME_URL) }
            }.start()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() { super.onResume(); web.onResume(); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); web.onPause(); super.onPause() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}

/** Returns "r,g,b" of whatever is painted at the top edge of the page. */
private const val COLOR_JS = """(function(){try{
function rgb(e){var c=getComputedStyle(e).backgroundColor,m=c&&c.match(/[\d.]+/g);
if(!m||m.length<3)return null;var a=m.length>3?parseFloat(m[3]):1;
return a>0.5?m[0]+","+m[1]+","+m[2]:null}
var e=document.elementFromPoint(innerWidth/2,2),r=null;
while(e&&!r){r=rgb(e);e=e.parentElement}
if(!r&&document.documentElement)r=rgb(document.documentElement);
if(!r&&document.body){var t=document.querySelector('meta[name=theme-color]');
if(t){var d=document.createElement('div');d.style.color=t.content;document.body.appendChild(d);
var cc=getComputedStyle(d).color;d.remove();var m=cc.match(/[\d.]+/g);if(m)r=m[0]+","+m[1]+","+m[2]}}
return r||"255,255,255"}catch(x){return ""}})()"""
