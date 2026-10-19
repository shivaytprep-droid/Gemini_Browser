package com.gemini.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
import android.os.*
import android.text.method.ScrollingMovementMethod
import android.util.Base64
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class MainActivity : Activity() {

    class BrowserTab(
        val id: Int,
        val webView: WebView,
        @Volatile var url: String = "https://gemini.google.com",
        @Volatile var title: String = "New Tab",
        @Volatile var desktopMode: Boolean = false,
        @Volatile var isPageLoading: Boolean = false,
        @Volatile var isNetworkIdle: Boolean = true
    )

    private lateinit var tabBarContainer: LinearLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var tabViewsHolder: FrameLayout
    private lateinit var overlayView: AIOverlayView
    private lateinit var urlInput: EditText
    private lateinit var consoleText: TextView
    private lateinit var logPanel: TextView
    private lateinit var desktopBtn: Button
    private lateinit var prefs: SharedPreferences

    private val tabs = CopyOnWriteArrayList<BrowserTab>()
    private val nextTabId = AtomicInteger(1)
    private val domGeneration = AtomicInteger(1)
    @Volatile private var activeTab: BrowserTab? = null

    @Volatile private var activeGoalDescription: String = "Autonomous Coding & Build Workflow"
    @Volatile private var expectedTargetFilePath: String = ""
    @Volatile private var expectedTargetContentLength: Int = 0
    @Volatile private var expectedTargetContentSha256: String = ""

    @Volatile private var activeBuildRepo: String = "shivaytprep-droid/Gemini_Browser"
    @Volatile private var activeBuildRunId: Long = 0L
    @Volatile private var activeBuildHeadSha: String = ""
    @Volatile private var activeBuildToken: String = ""
    private val originalFileSnapshots = ConcurrentHashMap<String, String>()
    private val chunkBuffer = StringBuilder()
    @Volatile private var loopStepReadDone = false
    @Volatile private var loopStepEditVerified = false
    @Volatile private var loopStepDiffVerified = false
    @Volatile private var loopStepCommitVerified = false
    @Volatile private var loopStepBuildVerified = false
    @Volatile private var lastVerifiedCommitSha = ""


    private val webView: WebView get() = (activeTab ?: tabs.first()).webView
    private val desktopMode: Boolean get() = activeTab?.desktopMode ?: false
    private val isPageLoading: Boolean get() = activeTab?.isPageLoading ?: false
    private val isNetworkIdle: Boolean get() = activeTab?.isNetworkIdle ?: true
    private val currentUrl: String get() = activeTab?.url ?: "https://gemini.google.com"
    private val currentTitle: String get() = activeTab?.title ?: ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serverPool: ExecutorService = Executors.newCachedThreadPool()
    private var wakeLock: PowerManager.WakeLock? = null

    private val isEmergencyStopped = AtomicBoolean(false)
    private val commandLogs = LinkedList<String>()
    private val sseClients = CopyOnWriteArrayList<PrintWriter>()
    @Volatile private var queuedFilePath: String? = null

    private val mobileUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private val desktopUA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    companion object {
        private const val SERVER_PORT = 8795
        private val serverStarted = AtomicBoolean(false)
        @Volatile private var serverSocket: ServerSocket? = null
        @Volatile private var activeInstance: MainActivity? = null
    }

    @SuppressLint("WakelockTimeout")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this
        prefs = getSharedPreferences("gemini_browser_prefs", Context.MODE_PRIVATE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { WebView.setWebContentsDebuggingEnabled(true) } catch (_: Exception) {}
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GeminiBrowser::ServerLock")
            wakeLock?.acquire()
        } catch (_: Exception) {}

        CookieManager.getInstance().setAcceptCookie(true)
        buildUserInterface()
        createNewTab("https://gemini.google.com", switchToIt = true)
        ensureServerRunning()
    }

    override fun onResume() {
        super.onResume()
        activeInstance = this
        ensureServerRunning()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun makePanelButton(label: String, bgHex: String, widthDp: Int = 76, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(bgHex))
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(30)).apply { marginEnd = dp(5) }
            setOnClickListener { onClick() }
        }
    }

    private fun makeSmallButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            minWidth = 0; minimumWidth = 0
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36)).apply { marginEnd = dp(3) }
            setOnClickListener { onClick() }
        }
    }

    private fun buildUserInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val tabBarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#141414"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        tabBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        tabBarScroll.addView(tabBarContainer)

        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1F1F1F"))
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }
        val btnBack = makeSmallButton("◀") { if (webView.canGoBack()) webView.goBack() }
        val btnForward = makeSmallButton("▶") { if (webView.canGoForward()) webView.goForward() }
        val btnReload = makeSmallButton("↻") { webView.reload() }

        urlInput = EditText(this).apply {
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#888888"))
            hint = "https://..."
            textSize = 13f
            setBackgroundColor(Color.parseColor("#2C2C2C"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            imeOptions = EditorInfo.IME_ACTION_GO
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(4); marginEnd = dp(4) }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    loadFromAddressBar(); true
                } else false
            }
        }
        val btnGo = makeSmallButton("GO") { loadFromAddressBar() }
        desktopBtn = makeSmallButton("MOB") { applyDesktopMode(!desktopMode) }

        navBar.addView(btnBack); navBar.addView(btnForward); navBar.addView(btnReload)
        navBar.addView(urlInput); navBar.addView(btnGo); navBar.addView(desktopBtn)

        val consoleScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val consoleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(12), dp(5))
        }

        val btnDom = makePanelButton("DOM", "#2E7D32") {
            serverPool.execute {
                val arr = JSONArray(extractDomSync(true))
                setConsoleStatus("🟢 DOM #${domGeneration.get()}: ${arr.length()} items", false)
            }
        }
        val btnOcr = makePanelButton("OCR", "#00838F") {
            serverPool.execute {
                setConsoleStatus("🟡 Running OCR...", false)
                val obj = JSONObject(performOcrAndDeepTextSync("viewport", true))
                setConsoleStatus("🟢 OCR Blocks: ${obj.optInt("ocr_blocks_count", 0)}", false)
            }
        }
        val btnVerify = makePanelButton("VERIFY", "#E65100") {
            serverPool.execute {
                val vObj = JSONObject(performGitHubVerificationSync(null))
                setConsoleStatus("🟢 SHA:${vObj.optString("commit_sha").take(7)} | Path:${vObj.optString("file_path")}", false)
            }
        }
        val btnJs = makePanelButton("JS", "#6A1B9A") { showUserScriptDialog() }
        val btnLogs = makePanelButton("LOGS", "#37474F") {
            logPanel.visibility = if (logPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val btnStop = makePanelButton("STOP", "#D50000", 80) {
            isEmergencyStopped.set(true)
            webView.stopLoading()
            overlayView.clearHighlights()
            setConsoleStatus("🔴 [STOPPED] Kill Switch", true)
            appendLog("EMERGENCY STOP triggered")
        }

        consoleText = TextView(this).apply {
            text = "🟡 Starting 127.0.0.1:$SERVER_PORT..."
            setTextColor(Color.parseColor("#FFD600"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            setPadding(dp(8), 0, dp(10), 0)
        }

        consoleRow.addView(btnDom); consoleRow.addView(btnOcr); consoleRow.addView(btnVerify)
        consoleRow.addView(btnJs); consoleRow.addView(btnLogs); consoleRow.addView(btnStop); consoleRow.addView(consoleText)
        consoleScroll.addView(consoleRow)

        webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) mainHandler.post { updateWebViewViewportBounds() }
            }
        }
        tabViewsHolder = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        overlayView = AIOverlayView(this).apply {
            isClickable = false; isFocusable = false
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        webContainer.addView(tabViewsHolder)
        webContainer.addView(overlayView)

        logPanel = TextView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#050505"))
            setTextColor(Color.parseColor("#00E676"))
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            movementMethod = ScrollingMovementMethod()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110))
            text = "[System] Gemini Autonomous Browser Ready on port $SERVER_PORT...\n"
        }

        root.addView(tabBarScroll); root.addView(navBar); root.addView(consoleScroll)
        root.addView(webContainer); root.addView(logPanel)
        setContentView(root)
    }

    private fun createNewTab(initialUrl: String = "https://gemini.google.com", switchToIt: Boolean = true): BrowserTab {
        val id = nextTabId.getAndIncrement()
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            visibility = View.GONE; isFocusable = true; isFocusableInTouchMode = true
        }
        val newTab = BrowserTab(id = id, webView = wv, url = initialUrl, title = "Tab $id")
        configureWebViewForTab(newTab)
        tabs.add(newTab)
        tabViewsHolder.addView(wv)
        if (switchToIt || activeTab == null) switchToTab(newTab) else refreshTabBarUi()
        if (initialUrl.isNotBlank()) wv.loadUrl(initialUrl)
        return newTab
    }

    private fun switchToTab(targetTab: BrowserTab) {
        if (!tabs.contains(targetTab)) return
        activeTab = targetTab
        for (t in tabs) t.webView.visibility = if (t.id == targetTab.id) View.VISIBLE else View.GONE
        targetTab.webView.requestFocus()
        urlInput.setText(targetTab.url)
        desktopBtn.text = if (targetTab.desktopMode) "PC" else "MOB"
        desktopBtn.setBackgroundColor(if (targetTab.desktopMode) Color.parseColor("#1565C0") else Color.parseColor("#333333"))
        updateWebViewViewportBounds(targetTab)
        overlayView.clearHighlights()
        refreshTabBarUi()
    }

    private fun closeTab(targetTab: BrowserTab) {
        if (tabs.size <= 1) { targetTab.webView.loadUrl("https://gemini.google.com"); return }
        val idx = tabs.indexOf(targetTab)
        val wasActive = (activeTab?.id == targetTab.id)
        tabs.remove(targetTab)
        tabViewsHolder.removeView(targetTab.webView)
        try { targetTab.webView.stopLoading(); targetTab.webView.destroy() } catch (_: Exception) {}
        if (wasActive && tabs.isNotEmpty()) switchToTab(tabs[idx.coerceIn(0, tabs.size - 1)]) else refreshTabBarUi()
    }

    private fun findTabByQuery(tabId: Int = -1, index: Int = -1, query: String = ""): BrowserTab? {
        if (tabId > 0) tabs.find { it.id == tabId }?.let { return it }
        if (index in 0 until tabs.size) return tabs[index]
        if (query.isNotBlank()) {
            val q = query.trim().lowercase(Locale.ROOT)
            tabs.find { it.title.lowercase(Locale.ROOT).contains(q) || it.url.lowercase(Locale.ROOT).contains(q) }?.let { return it }
        }
        return null
    }

    private fun refreshTabBarUi() {
        mainHandler.post {
            tabBarContainer.removeAllViews()
            val curId = activeTab?.id ?: -1
            for (t in tabs) {
                val isCurrent = (t.id == curId)
                val tabBox = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Color.parseColor(if (isCurrent) "#1565C0" else "#252525"))
                    setPadding(dp(8), dp(3), dp(4), dp(3))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28)).apply { marginEnd = dp(5) }
                    setOnClickListener { switchToTab(t) }
                }
                val titleTv = TextView(this).apply {
                    text = "${t.id}: ${t.title.ifEmpty { t.url }.take(15)}"
                    setTextColor(Color.WHITE); textSize = 10.5f; setSingleLine(true)
                    setPadding(0, 0, dp(6), 0)
                }
                val closeBtn = TextView(this).apply {
                    text = "✕"; setTextColor(Color.LTGRAY); textSize = 10.5f
                    setPadding(dp(5), 0, dp(5), 0)
                    setOnClickListener { closeTab(t) }
                }
                tabBox.addView(titleTv); tabBox.addView(closeBtn)
                tabBarContainer.addView(tabBox)
            }
            val addBtn = makePanelButton("+ TAB", "#00897B", 60) { createNewTab("https://gemini.google.com", true) }
            tabBarContainer.addView(addBtn)
        }
    }

    private fun startBrowserDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            if (url.startsWith("blob:")) {
                val safeBlob = url.replace("'", "\\'")
                val js = """
                    fetch('$safeBlob').then(r => r.blob()).then(b => {
                        const rd = new FileReader();
                        rd.onloadend = () => window.AIAgentBridge.saveBase64Download((rd.result||'').split(',')[1]||'', 'download_' + Date.now(), b.type||'');
                        rd.readAsDataURL(b);
                    });
                """.trimIndent()
                mainHandler.post { webView.evaluateJavascript(js, null) }
                return
            }
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                addRequestHeader("User-Agent", userAgent ?: mobileUA)
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            appendLog("📥 Downloading: $fileName")
        } catch (_: Exception) {}
    }

    private fun saveBytesToDownloads(bytes: ByteArray, fileName: String) {
        serverPool.execute {
            try {
                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dlDir.exists()) dlDir.mkdirs()
                File(dlDir, fileName).writeBytes(bytes)
                appendLog("✅ Saved: $fileName")
            } catch (_: Exception) {}
        }
    }

    private fun getSavedUserScripts(): JSONArray =
        try { JSONArray(prefs.getString("userscripts_list", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }

    private fun saveUserScripts(arr: JSONArray) = prefs.edit().putString("userscripts_list", arr.toString()).apply()

    private fun showUserScriptDialog() {
        val scripts = getSavedUserScripts()
        val codeInput = EditText(this).apply {
            hint = "// Paste UserScript JS here..."
            setTextColor(Color.parseColor("#80CBC4")); setBackgroundColor(Color.parseColor("#0D0D0D"))
            minLines = 6
        }
        AlertDialog.Builder(this)
            .setTitle("⚡ UserScript Manager (${scripts.length()} saved)")
            .setView(codeInput)
            .setPositiveButton("Save & Run") { _, _ ->
                val js = codeInput.text.toString().trim()
                if (js.isNotEmpty()) {
                    scripts.put(JSONObject().put("name", "Script #${scripts.length() + 1}").put("code", js).put("enabled", true))
                    saveUserScripts(scripts)
                    runSingleUserScriptOnPage(webView, "UserScript", js)
                }
            }
            .setNeutralButton("Clear All") { _, _ -> saveUserScripts(JSONArray()) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun runSingleUserScriptOnPage(targetWv: WebView, name: String, rawCode: String) {
        val b64 = Base64.encodeToString(rawCode.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val js = "(function(){ try { const b=atob('$b64'); const u=new Uint8Array(b.length); for(let i=0;i<b.length;i++) u[i]=b.charCodeAt(i); new Function(new TextDecoder('utf-8').decode(u))(); } catch(e){} })();"
        mainHandler.post { targetWv.evaluateJavascript(js, null) }
    }

    private fun injectSavedUserScripts(targetWv: WebView, url: String) {
        val scripts = getSavedUserScripts()
        for (i in 0 until scripts.length()) {
            val obj = scripts.optJSONObject(i) ?: continue
            if (obj.optBoolean("enabled", true)) {
                runSingleUserScriptOnPage(targetWv, obj.optString("name", "Script"), obj.optString("code", ""))
            }
        }
    }

    private fun updateWebViewViewportBounds(targetTab: BrowserTab? = activeTab) {
        val tab = targetTab ?: return
        val wv = tab.webView
        val cw = webContainer.width; val ch = webContainer.height
        if (cw <= 0 || ch <= 0) return

        if (tab.desktopMode) {
            val targetPxW = (1280f * resources.displayMetrics.density).toInt()
            val scale = cw.toFloat() / targetPxW.toFloat()
            val targetPxH = (ch.toFloat() / scale).toInt()
            wv.pivotX = 0f; wv.pivotY = 0f; wv.scaleX = scale; wv.scaleY = scale
            val lp = wv.layoutParams as FrameLayout.LayoutParams
            if (lp.width != targetPxW || lp.height != targetPxH) { lp.width = targetPxW; lp.height = targetPxH; wv.layoutParams = lp }
        } else {
            wv.pivotX = 0f; wv.pivotY = 0f; wv.scaleX = 1f; wv.scaleY = 1f
            val lp = wv.layoutParams as FrameLayout.LayoutParams
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.height = ViewGroup.LayoutParams.MATCH_PARENT; wv.layoutParams = lp
            }
        }
    }

    private fun applyDesktopMode(enable: Boolean) {
        val tab = activeTab ?: return
        tab.desktopMode = enable
        runOnUiThread {
            desktopBtn.text = if (enable) "PC" else "MOB"
            desktopBtn.setBackgroundColor(if (enable) Color.parseColor("#1565C0") else Color.parseColor("#333333"))
            tab.webView.settings.userAgentString = if (enable) desktopUA else mobileUA
            updateWebViewViewportBounds(tab)
            tab.webView.reload()
        }
    }

    private fun loadFromAddressBar() {
        var raw = urlInput.text.toString().trim()
        if (raw.isEmpty()) return
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = if (raw.contains(".") && !raw.contains(" ")) "https://$raw" else "https://www.google.com/search?q=${Uri.encode(raw)}"
        }
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlInput.windowToken, 0) } catch (_: Exception) {}
        webView.loadUrl(raw)
    }

    private fun configureWebViewForTab(tab: BrowserTab) {
        val wv = tab.webView
        wv.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            javaScriptCanOpenWindowsAutomatically = true; setSupportMultipleWindows(true)
            allowFileAccess = true; allowContentAccess = true; loadWithOverviewMode = true; useWideViewPort = true
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = if (tab.desktopMode) desktopUA else mobileUA
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.setDownloadListener { url, ua, cd, mime, _ -> startBrowserDownload(url, ua, cd, mime) }

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onIdle(url: String) {
                tab.isNetworkIdle = true
                broadcastSse("network_idle", url)
            }
            @JavascriptInterface
            fun onAutoAllowed(info: String) {
                appendLog("⚡ [Tab #${tab.id}] Auto-Allowed: $info")
                setConsoleStatus("🟢 [Tab #${tab.id}] Auto-Allowed MCP!", false)
            }
            @JavascriptInterface
            fun saveBase64Download(b64: String, name: String, mime: String) {
                try { saveBytesToDownloads(Base64.decode(b64, Base64.DEFAULT), name) } catch (_: Exception) {}
            }
        }, "AIAgentBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                tab.isPageLoading = true; tab.isNetworkIdle = false; tab.url = url ?: ""
                domGeneration.incrementAndGet()
                if (activeTab?.id == tab.id) urlInput.setText(tab.url)
                injectStealthViewportAndTracker(tab)
                refreshTabBarUi()
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tab.isPageLoading = false; tab.url = url ?: ""; tab.title = view?.title ?: "Tab ${tab.id}"
                domGeneration.incrementAndGet()
                if (activeTab?.id == tab.id) urlInput.setText(tab.url)
                CookieManager.getInstance().flush()
                injectStealthViewportAndTracker(tab)
                injectSavedUserScripts(wv, tab.url)
                refreshTabBarUi()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = createNewTab("", true).webView
                resultMsg.sendToTarget()
                return true
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) { tab.title = title; refreshTabBarUi() }
            }
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.confirm(); return true }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean { result?.confirm(); return true }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, def: String?, result: JsPromptResult?): Boolean { result?.confirm(def ?: ""); return true }
            override fun onShowFileChooser(wv: WebView?, cb: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
                val path = queuedFilePath
                if (!path.isNullOrEmpty() && File(path).exists()) {
                    cb?.onReceiveValue(arrayOf(Uri.fromFile(File(path))))
                    queuedFilePath = null
                    return true
                }
                cb?.onReceiveValue(null)
                return true
            }
        }
    }

    private fun injectStealthViewportAndTracker(tab: BrowserTab) {
        val js = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'webdriver', { get: () => false, configurable: true });
                    window.chrome = window.chrome || { runtime: {}, app: {} };
                } catch (e) {}

                window.__aiSetReactSafeValue = function(el, newValue, insertedDelta, isAppend) {
                    if (!el) return 'no_element';
                    try { el.focus(); } catch (e) {}
                    const isEditable = el.isContentEditable || el.getAttribute('contenteditable') === 'true' || el.classList.contains('inputarea');
                    if (isEditable) {
                        if (!isAppend) {
                            const sel = window.getSelection();
                            const r = document.createRange();
                            r.selectNodeContents(el);
                            sel.removeAllRanges(); sel.addRange(r);
                            document.execCommand('delete', false, null);
                        }
                        if (insertedDelta && insertedDelta.length > 0) {
                            try {
                                const dt = new DataTransfer();
                                dt.setData('text/plain', insertedDelta);
                                const pe = new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true, composed: true });
                                el.dispatchEvent(pe);
                                if (!pe.defaultPrevented) document.execCommand('insertText', false, insertedDelta);
                            } catch (err) { document.execCommand('insertText', false, insertedDelta); }
                        }
                        el.dispatchEvent(new InputEvent('input', { data: insertedDelta || newValue, inputType: 'insertFromPaste', bubbles: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                        return 'contenteditable_paste';
                    }
                    if ('value' in el) {
                        const prevVal = el.value || '';
                        const targetVal = isAppend ? (prevVal + (insertedDelta || '')) : newValue;
                        const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                        const desc = Object.getOwnPropertyDescriptor(proto, 'value');
                        if (desc && desc.set) desc.set.call(el, targetVal); else el.value = targetVal;
                        if (el._valueTracker) { try { el._valueTracker.setValue(prevVal); } catch (e) {} }
                        el.dispatchEvent(new InputEvent('input', { data: insertedDelta || targetVal, inputType: 'insertText', bubbles: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                        return 'react_safe_input';
                    }
                    return 'unknown';
                };

                window.__aiCommitActiveInput = function() {
                    try {
                        const a = document.activeElement;
                        if (a && ('value' in a || a.isContentEditable)) {
                            a.dispatchEvent(new Event('input', { bubbles: true }));
                            a.dispatchEvent(new Event('change', { bubbles: true }));
                        }
                    } catch (e) {}
                };

                if (!window.__geminiAllowClickerInstalled) {
                    window.__geminiAllowClickerInstalled = true;
                    setInterval(function() {
                        try {
                            const els = document.querySelectorAll('button, [role="button"]');
                            const list = [];
                            for (let i = 0; i < els.length; i++) {
                                const el = els[i];
                                const t = (el.innerText || el.textContent || '').trim().toLowerCase();
                                if (t === 'allow' && !el.disabled) {
                                    const r = el.getBoundingClientRect();
                                    if (r.width > 10 && r.height > 10) list.push(el);
                                }
                            }
                            if (list.length > 0) {
                                const btn = list[list.length - 1];
                                const now = Date.now();
                                if (now - parseInt(btn.getAttribute('data-ai-ts') || '0', 10) > 1200) {
                                    btn.setAttribute('data-ai-ts', String(now));
                                    btn.click();
                                    if (window.AIAgentBridge) window.AIAgentBridge.onAutoAllowed('Allow clicked');
                                }
                            }
                        } catch (e) {}
                    }, 400);
                }
            })();
        """.trimIndent()
        tab.webView.evaluateJavascript(js, null)
    }

    private fun ensureServerRunning() {
        if (serverStarted.get() && serverSocket != null && !serverSocket!!.isClosed) return
        serverPool.execute {
            try { serverSocket?.close() } catch (_: Exception) {}
            try {
                val sock = ServerSocket()
                sock.reuseAddress = true
                sock.bind(InetSocketAddress("0.0.0.0", SERVER_PORT), 50)
                serverSocket = sock
                serverStarted.set(true)
                setConsoleStatus("🟢 ONLINE 127.0.0.1:$SERVER_PORT | Ready", false)
                while (serverStarted.get() && !sock.isClosed) {
                    val client = sock.accept()
                    serverPool.execute { (activeInstance ?: this@MainActivity).handleClientSocket(client) }
                }
            } catch (e: Exception) {
                serverStarted.set(false)
                setConsoleStatus("🔴 SERVER ERR: ${e.message}", true)
            }
        }
    }

    private fun parseQueryParams(rawPath: String): JSONObject {
        val obj = JSONObject()
        val qStr = rawPath.substringAfter("?", "")
        if (qStr.isEmpty()) return obj
        for (pair in qStr.split("&")) {
            val p = pair.split("=", limit = 2)
            if (p.isNotEmpty() && p[0].isNotEmpty()) {
                val k = URLDecoder.decode(p[0], "UTF-8")
                val v = if (p.size > 1) URLDecoder.decode(p[1], "UTF-8") else ""
                val iv = v.toIntOrNull()
                if (iv != null) obj.put(k, iv)
                else if (v == "true" || v == "false") obj.put(k, v.toBoolean())
                else obj.put(k, v)
            }
        }
        return obj
    }

    private fun handleClientSocket(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val out = socket.getOutputStream()
            val reqLine = reader.readLine() ?: return
            val parts = reqLine.split(" ")
            val method = if (parts.isNotEmpty()) parts[0].uppercase(Locale.ROOT) else "GET"
            val rawPath = if (parts.size > 1) parts[1] else "/"
            val path = rawPath.substringBefore("?")

            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                if (line!!.lowercase(Locale.ROOT).startsWith("content-length:")) {
                    contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val bodyStr = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var total = 0
                while (total < contentLength) {
                    val r = reader.read(buf, total, contentLength - total)
                    if (r == -1) break
                    total += r
                }
                String(buf, 0, total)
            } else ""

            val queryJson = parseQueryParams(rawPath)
            val jsonBody = if (bodyStr.trim().isNotEmpty()) {
                try {
                    val parsed = JSONObject(bodyStr)
                    for (k in queryJson.keys()) if (!parsed.has(k)) parsed.put(k, queryJson.get(k))
                    parsed
                } catch (_: Exception) { queryJson }
            } else queryJson

            val responseJson = executeCommand(method, path, jsonBody)
            val bytes = responseJson.toByteArray(Charsets.UTF_8)
            val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
            out.write(headers.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun buildTabsJsonArray(): JSONArray {
        val arr = JSONArray()
        val curId = activeTab?.id ?: -1
        for (i in 0 until tabs.size) {
            val t = tabs[i]
            arr.put(JSONObject().put("tab_id", t.id).put("index", i).put("virtual_dom_id", 9000 + t.id).put("active", t.id == curId).put("url", t.url).put("title", t.title))
        }
        return arr
    }

    private fun inspectGitHubPageStateSync(): JSONObject {
        val urlMeta = parseGitHubUrlMeta(currentUrl)
        val js = """
            (function() {
                const url = window.location.href || '';
                const pathName = window.location.pathname || '';
                const isNewOrEdit = /\/(new|edit)\//.test(pathName);

                const crumbParts = [];
                document.querySelectorAll('[data-testid="breadcrumbs"] a, [data-testid="breadcrumbs-filename"], .js-path-segment a').forEach(el => {
                    const t = (el.innerText || el.textContent || '').replace(/\//g, '').trim();
                    if (t) crumbParts.push(t);
                });
                const repoParts = pathName.split('/').filter(Boolean);
                if (crumbParts.length > 0 && repoParts.length >= 2 && crumbParts[0].toLowerCase() === repoParts[1].toLowerCase()) {
                    crumbParts.shift();
                }

                const fnInput = document.querySelector('input[aria-label*="file name" i], input[placeholder*="Name your file" i], input.js-blob-filename, input[name="filename"]');
                const fnVal = fnInput ? (fnInput.value || '').trim() : '';

                let effectivePath = '';
                if (isNewOrEdit) {
                    const combined = [...crumbParts];
                    if (fnVal) combined.push(fnVal);
                    effectivePath = combined.join('/').replace(/\/+/g, '/');
                } else {
                    const m = pathName.match(/^\/[^\/]+\/[^\/]+\/(?:blob|raw|tree)\/[^\/]+\/(.+)$/);
                    if (m) effectivePath = decodeURIComponent(m[1]);
                }

                let editorContent = '';
                let editorEngine = 'none';
                function findCmView() {
                    const nodes = document.querySelectorAll('.cm-editor, .cm-content, .cm-scroller, .cm-line');
                    for (let i = 0; i < nodes.length; i++) {
                        const cv = nodes[i].cmView || nodes[i].cmTile;
                        if (!cv) continue;
                        if (cv.view && cv.view.state && cv.view.state.doc) return cv.view;
                        if (cv.rootView && cv.rootView.view && cv.rootView.view.state) return cv.rootView.view;
                        for (const k of Object.keys(cv)) {
                            const v = cv[k];
                            if (v && typeof v === 'object' && v.state && v.state.doc && typeof v.dispatch === 'function') return v;
                        }
                    }
                    return null;
                }
                const cmView = findCmView();
                const cmContent = document.querySelector('.cm-content[contenteditable="true"]');
                if (cmView) {
                    editorContent = cmView.state.doc.toString();
                    editorEngine = 'codemirror6_state';
                } else if (cmContent) {
                    const lines = cmContent.querySelectorAll('.cm-line');
                    if (lines.length > 0) {
                        const arr = [];
                        lines.forEach(l => arr.push((l.innerText || l.textContent || '').replace(/\u200B/g, '').replace(/\r?\n/g, '')));
                        editorContent = arr.join('\n');
                    } else editorContent = cmContent.innerText || '';
                    editorEngine = 'codemirror_dom_lines';
                }

                const openDialogs = Array.from(document.querySelectorAll('[role="dialog"], dialog[open], .Overlay')).filter(d => {
                    const r = d.getBoundingClientRect();
                    const s = window.getComputedStyle(d);
                    return r.width > 50 && r.height > 50 && s.display !== 'none' && s.visibility !== 'hidden';
                });
                const hasOpenDialog = openDialogs.length > 0;
                let dialogCommitBtnId = -1;
                if (hasOpenDialog) {
                    const d = openDialogs[openDialogs.length - 1];
                    d.querySelectorAll('button, [role="button"]').forEach(b => {
                        const t = (b.innerText || b.textContent || '').trim().toLowerCase();
                        if ((t === 'commit changes' || t.includes('commit')) && !b.disabled) {
                            const aid = parseInt(b.getAttribute('data-ai-id') || '-1', 10);
                            if (aid > 0) dialogCommitBtnId = aid;
                        }
                    });
                }

                let topCommitBtnId = -1;
                let topCommitBtnEnabled = false;
                document.querySelectorAll('button, [role="button"]').forEach(b => {
                    const t = (b.innerText || b.textContent || '').trim().toLowerCase();
                    if ((t === 'commit changes...' || t === 'commit changes') && !b.closest('[role="dialog"], dialog[open]')) {
                        topCommitBtnId = parseInt(b.getAttribute('data-ai-id') || '-1', 10);
                        topCommitBtnEnabled = !b.disabled && b.getAttribute('aria-disabled') !== 'true';
                    }
                });

                let commitSha = '';
                const shaEl = document.querySelector('a[href*="/commit/"], span.sha, meta[name="octolytics-dimension-repository_network_root_sha"]');
                if (shaEl) {
                    const href = shaEl.getAttribute('href') || '';
                    const m = href.match(/\/commit\/([0-9a-f]{7,40})/i);
                    commitSha = m ? m[1] : (shaEl.getAttribute('content') || shaEl.innerText || '').trim();
                }

                return JSON.stringify({
                    url: url,
                    is_in_editor: isNewOrEdit,
                    breadcrumb_segments: crumbParts,
                    filename: fnVal || effectivePath.split('/').pop() || '',
                    filename_input_id: fnInput ? parseInt(fnInput.getAttribute('data-ai-id') || '-1', 10) : -1,
                    effective_full_path: effectivePath,
                    editor_engine: editorEngine,
                    editor_length: editorContent.length,
                    editor_lines_count: editorContent ? editorContent.split('\n').length : 0,
                    has_open_dialog: hasOpenDialog,
                    dialog_commit_button_id: dialogCommitBtnId,
                    page_commit_button_id: topCommitBtnId,
                    page_commit_button_enabled: topCommitBtnEnabled,
                    commit_sha: commitSha
                });
            })();
        """.trimIndent()
        val raw = evalJsSync(js)
        val obj = try { if (raw.startsWith("{")) JSONObject(raw) else JSONObject() } catch (_: Exception) { JSONObject() }
        obj.put("repo", urlMeta.optString("repo", activeBuildRepo))
        obj.put("branch", urlMeta.optString("branch", "main"))
        obj.put("mode", urlMeta.optString("mode", "unknown"))
        if (obj.optString("effective_full_path", "").isEmpty()) {
            obj.put("effective_full_path", urlMeta.optString("url_path", ""))
        }
        return obj
    }

    private fun attachFreshStateAndDirective(baseResponse: JSONObject, actionName: String): String {
        val currentGen = domGeneration.incrementAndGet()
        val domStr = extractDomSync(false)
        val domArr = try { if (domStr.startsWith("[")) JSONArray(domStr) else JSONArray() } catch (_: Exception) { JSONArray() }
        val ghState = inspectGitHubPageStateSync()
        val hasDialog = ghState.optBoolean("has_open_dialog", false)
        val isEditor = ghState.optBoolean("is_in_editor", false)
        val effPath = ghState.optString("effective_full_path", "")
        val edLen = ghState.optInt("editor_length", 0)
        val pageCommitId = ghState.optInt("page_commit_button_id", -1)
        val dlgCommitId = ghState.optInt("dialog_commit_button_id", -1)
        val repo = ghState.optString("repo", activeBuildRepo)

        // Update state machine flags based on action & verified results
        if (actionName.startsWith("editor_get") || actionName == "navigate" || actionName == "github_state") {
            if (isEditor || effPath.isNotEmpty()) loopStepReadDone = true
        }
        if (actionName == "editor_set_code" && baseResponse.optBoolean("verified", false)) {
            loopStepReadDone = true
            loopStepEditVerified = true
            loopStepDiffVerified = false
            loopStepCommitVerified = false
            loopStepBuildVerified = false
        }
        if (actionName == "diff" && baseResponse.optBoolean("safe_to_commit", false)) {
            loopStepDiffVerified = true
        }
        if ((actionName == "github_commit" && baseResponse.optBoolean("success", false)) ||
            (actionName == "verify" && baseResponse.optBoolean("commit_verified", false))) {
            loopStepCommitVerified = true
            lastVerifiedCommitSha = baseResponse.optString("commit_sha", ghState.optString("commit_sha", ""))
        }
        if (actionName == "build_status" && baseResponse.optString("run_status") == "completed" && baseResponse.optString("conclusion") == "success") {
            loopStepBuildVerified = true
        }

        // Determine current Stage & Mandatory Next Action to lock AI in continuous execution loop
        val currentStage: String
        val nextEndpoint: String
        val nextPayload = JSONObject()
        val instructionMsg: String
        var isTaskComplete = false

        if (isEditor && !loopStepEditVerified) {
            currentStage = "STAGE_2_WRITE_AND_VERIFY_CODE"
            nextEndpoint = "PUT /editor"
            nextPayload.put("action", "set_code").put("text_b64", "<BASE64_FULL_CODE>")
            instructionMsg = "🚨 [LOOP LOCK ACTIVE - STEP 2/6]: Editor is open for '$effPath' (current length:$edLen). DO NOT STOP or reply to user! Send full code via PUT /editor and verify exact_match==true."
        } else if (loopStepEditVerified && !loopStepDiffVerified) {
            currentStage = "STAGE_3_VERIFY_GIT_DIFF"
            nextEndpoint = "GET /diff"
            instructionMsg = "🚨 [LOOP LOCK ACTIVE - STEP 3/6]: Code written & hash matched ($edLen chars). DO NOT STOP! Immediately call GET /diff to verify unified_diff and file identity before committing."
        } else if (loopStepDiffVerified && !loopStepCommitVerified) {
            if (getStoredGitHubToken().isNotEmpty()) {
                currentStage = "STAGE_4_COMMIT_CHANGES_API"
                nextEndpoint = "POST /github/commit"
                nextPayload.put("repo", repo).put("path", effPath).put("message", "Update $effPath")
                instructionMsg = "🚨 [LOOP LOCK ACTIVE - STEP 4/6]: Diff verified for '$effPath'. DO NOT STOP! Immediately call POST /github/commit (or click Commit button) to commit the file."
            } else if (hasDialog) {
                currentStage = "STAGE_4B_CONFIRM_DIALOG_COMMIT"
                nextEndpoint = "POST /click"
                nextPayload.put("element_id", dlgCommitId).put("text", "Commit changes").put("context", "dialog")
                instructionMsg = "🚨 [LOOP LOCK ACTIVE - STEP 4/6]: Commit dialog is OPEN. DO NOT STOP! Click the dialog 'Commit changes' button (element_id=$dlgCommitId) now, then call POST /verify."
            } else {
                currentStage = "STAGE_4A_OPEN_COMMIT_DIALOG"
                nextEndpoint = "POST /click"
                nextPayload.put("element_id", pageCommitId).put("text", "Commit changes...")
                instructionMsg = "🚨 [LOOP LOCK ACTIVE - STEP 4/6]: Diff is verified! DO NOT STOP! Click 'Commit changes...' (element_id=$pageCommitId) now."
            }
        } else if (isEditor && loopStepDiffVerified && !hasDialog) {
            currentStage = "STAGE_4C_VERIFY_COMMIT_LANDED"
            nextEndpoint = "GET /page_text"
            instructionMsg = "🚨 [LOOP LOCK ACTIVE - STEP 4/6]: Still in editor after commit attempt! Call GET /page_text immediately to check 'errors_detected' or call POST /verify."
        } else if (loopStepCommitVerified && !loopStepBuildVerified) {
            currentStage = "STAGE_5_BUILD_AND_APK_VERIFY"
            nextEndpoint = "GET /build/status"
            nextPayload.put("repo", repo).put("commit_sha", lastVerifiedCommitSha).put("download_apk", true)
            instructionMsg = "🚨 [LOOP LOCK ACTIVE - STEP 5/6]: Commit ($lastVerifiedCommitSha) is verified! DO NOT STOP! Poll GET /build/status until run_status=='completed' and conclusion=='success'."
        } else if (loopStepCommitVerified && loopStepBuildVerified) {
            currentStage = "STAGE_6_ALL_VERIFIED_COMPLETE"
            nextEndpoint = "NONE"
            isTaskComplete = true
            instructionMsg = "✅ [LOOP LOCK RELEASED - 6/6 COMPLETE]: Code edited, diff verified, commit SHA ($lastVerifiedCommitSha) verified, and build succeeded! You may now report final success to the user."
        } else {
            currentStage = "STAGE_1_INSPECT_AND_ACT"
            nextEndpoint = "GET /dom"
            instructionMsg = "🚨 [LOOP LOCK ACTIVE]: Action '$actionName' finished on DOM Gen #$currentGen. DO NOT pause to ask the user—immediately execute the next tool call toward the goal!"
        }

        val priorityElements = JSONArray()
        for (i in 0 until domArr.length()) {
            val item = domArr.getJSONObject(i)
            if (hasDialog && item.optBoolean("in_open_dialog", false)) priorityElements.put(item)
            else if (!hasDialog && priorityElements.length() < 30) priorityElements.put(item)
        }

        val checklist = JSONObject()
            .put("1_file_read", loopStepReadDone)
            .put("2_code_edited_hash_matched", loopStepEditVerified)
            .put("3_diff_verified", loopStepDiffVerified)
            .put("4_commit_sha_verified", loopStepCommitVerified)
            .put("5_build_verified", loopStepBuildVerified)

        val directive = JSONObject()
            .put("mode", "TINYFISH_HARD_LOOP_LOCK")
            .put("loop_lock", if (isTaskComplete) "RELEASED_TASK_COMPLETE" else "ACTIVE_DO_NOT_STOP")
            .put("task_complete", isTaskComplete)
            .put("current_stage", currentStage)
            .put("dom_generation", currentGen)
            .put("checklist", checklist)
            .put("ai_loop_prompt", instructionMsg)
            .put("mandatory_next_action", JSONObject().put("endpoint", nextEndpoint).put("payload", nextPayload))

        // Put loop control fields right at the top of the response so LLM reads them first
        baseResponse.put("task_complete", isTaskComplete)
        baseResponse.put("loop_lock", if (isTaskComplete) "RELEASED_TASK_COMPLETE" else "ACTIVE_DO_NOT_STOP")
        baseResponse.put("ai_loop_prompt", instructionMsg)
        baseResponse.put("dom_generation", currentGen)
        baseResponse.put("github_state", ghState)
        baseResponse.put("fresh_page_state", JSONObject().put("url", currentUrl).put("title", currentTitle).put("has_open_dialog", hasDialog).put("priority_elements", priorityElements))
        baseResponse.put("agent_directive", directive)
        return baseResponse.toString()
    }

    // Atomic Pipeline Endpoint: Edit -> Verify Hash -> Verify Diff -> Commit -> Trigger Build in ONE call!
    private fun performAtomicPipelineSync(body: JSONObject?): String {
        if (body == null) return JSONObject().put("success", false).put("error_code", "EMPTY_BODY").toString()
        val logSteps = JSONArray()

        // 1. Set code in editor & verify read-back hash
        val editRes = JSONObject(performCodeEditorActionSync(body.put("action", "set_code")))
        logSteps.put(JSONObject().put("step", "1_set_code").put("success", editRes.optBoolean("success", false)).put("editor_length", editRes.optInt("editor_length", 0)).put("content_hash", editRes.optString("content_hash", "")))
        if (!editRes.optBoolean("success", false)) {
            return JSONObject().put("success", false).put("failed_at", "1_set_code").put("pipeline_steps", logSteps).put("details", editRes).toString()
        }

        // 2. Verify Diff
        val diffRes = JSONObject(performGetDiffSync(body))
        logSteps.put(JSONObject().put("step", "2_verify_diff").put("safe_to_commit", diffRes.optBoolean("safe_to_commit", false)).put("added_lines", diffRes.optInt("added_lines_count", 0)))
        if (!diffRes.optBoolean("safe_to_commit", false)) {
            return JSONObject().put("success", false).put("failed_at", "2_verify_diff").put("pipeline_steps", logSteps).put("details", diffRes).toString()
        }

        // 3. Commit via GitHub API if token is configured
        if (getStoredGitHubToken().isNotEmpty() && body.optBoolean("auto_commit", true)) {
            val commitRes = JSONObject(performGitHubDirectCommitSync(body))
            val cOk = commitRes.optBoolean("success", false)
            logSteps.put(JSONObject().put("step", "3_github_commit").put("success", cOk).put("commit_sha", commitRes.optString("commit_sha", "")))
            if (!cOk) {
                return JSONObject().put("success", false).put("failed_at", "3_github_commit").put("pipeline_steps", logSteps).put("details", commitRes).toString()
            }
        }

        val out = JSONObject()
            .put("success", true)
            .put("status", "pipeline_completed")
            .put("pipeline_steps", logSteps)
            .put("commit_sha", lastVerifiedCommitSha)
        return attachFreshStateAndDirective(out, "pipeline")
    }

    private fun getStoredGitHubToken(): String {
        if (activeBuildToken.trim().isNotEmpty()) return activeBuildToken.trim()
        return (prefs.getString("gh_pat_token", "") ?: "").trim()
    }

    private fun saveStoredGitHubToken(token: String) {
        val clean = token.trim()
        if (clean.isNotEmpty()) {
            activeBuildToken = clean
            prefs.edit().putString("gh_pat_token", clean).apply()
        }
    }

    private fun parseGitHubUrlMeta(url: String): JSONObject {
        val meta = JSONObject()
        val m = Regex("github\\.com/([^/]+/[^/?#]+)(?:/(blob|edit|new|raw|tree)/([^/?#]+)(?:/(.*))?)?").find(url)
        if (m != null) {
            meta.put("repo", m.groupValues[1].removeSuffix(".git"))
            meta.put("mode", m.groupValues[2].ifEmpty { "repo" })
            meta.put("branch", m.groupValues[3].ifEmpty { "main" })
            meta.put("url_path", URLDecoder.decode(m.groupValues[4].substringBefore("?"), "UTF-8"))
        } else {
            meta.put("repo", activeBuildRepo); meta.put("mode", "unknown"); meta.put("branch", "main"); meta.put("url_path", "")
        }
        return meta
    }

    private fun performSetGitHubPathSync(targetPath: String, allowRenameOnEdit: Boolean = false): String {
        val cleanPath = targetPath.trim().removePrefix("/")
        expectedTargetFilePath = cleanPath
        val ghState = inspectGitHubPageStateSync()
        val currentEff = ghState.optString("effective_full_path", "")

        if (currentEff.equals(cleanPath, ignoreCase = true)) {
            return attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "path_already_verified").put("effective_full_path", currentEff).put("verified", true), "set_github_path")
        }
        if (currentUrl.contains("/edit/") && !allowRenameOnEdit && currentEff.isNotEmpty()) {
            return attachFreshStateAndDirective(JSONObject().put("success", false).put("status", "path_change_blocked_on_edit_page").put("error_code", "EDIT_PAGE_PATH_PROTECTED").put("current_file_path", currentEff), "set_github_path")
        }

        val prep = evalJsSync("""
            (function() {
                const fn = document.querySelector('input[aria-label*="file name" i], input[placeholder*="Name your file" i], input.js-blob-filename, input[name="filename"]');
                if (!fn) return 'no_input';
                fn.focus();
                if (window.__aiSetReactSafeValue) window.__aiSetReactSafeValue(fn, '', '', false);
                return 'ok';
            })();
        """.trimIndent())
        if (prep == "no_input") return attachFreshStateAndDirective(JSONObject().put("success", false).put("status", "error").put("error_code", "NO_FILENAME_INPUT"), "set_github_path")

        for (ch in cleanPath) {
            val s = ch.toString().replace("\\", "\\\\").replace("'", "\\'")
            evalJsSync("""
                (function() {
                    const fn = document.querySelector('input[aria-label*="file name" i], input[placeholder*="Name your file" i], input.js-blob-filename, input[name="filename"]');
                    if (fn && window.__aiSetReactSafeValue) window.__aiSetReactSafeValue(fn, null, '$s', true);
                })();
            """.trimIndent())
            Thread.sleep(if (ch == '/') 100L else 20L)
        }
        Thread.sleep(180)
        val after = inspectGitHubPageStateSync().optString("effective_full_path", "")
        val ok = after.equals(cleanPath, ignoreCase = true)
        return attachFreshStateAndDirective(JSONObject().put("success", ok).put("status", if (ok) "path_verified" else "path_mismatch").put("effective_full_path", after).put("verified", ok), "set_github_path")
    }

    // Supports: get_code, get_chunk (offset/limit), set_code, append_chunk (for chunked writing!), clear
    private fun performCodeEditorActionSync(body: JSONObject): String {
        val startMs = System.currentTimeMillis()
        val action = body.optString("action", "get_code").lowercase(Locale.ROOT)
        val filePathParam = body.optString("file_path", body.optString("path", ""))
        val inputB64 = body.optString("text_b64", "")
        val rawInput = if (inputB64.isNotEmpty()) {
            try { String(Base64.decode(inputB64, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { "" }
        } else body.optString("text", "")

        if (filePathParam.isNotEmpty() && !currentUrl.contains("/edit/")) {
            performSetGitHubPathSync(filePathParam, false)
        }

        // Chunked buffer support so large files never get cut off!
        if (action == "start_chunk" || action == "clear_chunks") {
            chunkBuffer.setLength(0)
            if (rawInput.isNotEmpty()) chunkBuffer.append(rawInput.replace("\r\n", "\n"))
            return JSONObject().put("success", true).put("status", "chunk_buffer_initialized").put("buffered_length", chunkBuffer.length).toString()
        }
        if (action == "append_chunk") {
            chunkBuffer.append(rawInput.replace("\r\n", "\n"))
            val commitChunks = body.optBoolean("commit_chunks", body.optBoolean("flush", false))
            if (!commitChunks) {
                return JSONObject().put("success", true).put("status", "chunk_appended").put("buffered_length", chunkBuffer.length).put("buffered_hash", sha256Hex(chunkBuffer.toString())).toString()
            }
        }

        val text = if (action == "append_chunk" || action == "flush_chunks") {
            val full = chunkBuffer.toString()
            chunkBuffer.setLength(0)
            full
        } else rawInput.replace("\r\n", "\n")

        val effectiveAction = if (action == "append_chunk" || action == "flush_chunks") "set_code" else action
        val b64Text = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        if (effectiveAction == "set_code" && text.isNotEmpty()) {
            expectedTargetContentLength = text.length
            expectedTargetContentSha256 = sha256Hex(text)
        }

        val js = """
            (function() {
                const action = '$effectiveAction';
                function decodeB64(b64) {
                    if (!b64) return '';
                    try {
                        const bin = atob(b64);
                        const u = new Uint8Array(bin.length);
                        for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
                        return new TextDecoder('utf-8').decode(u);
                    } catch (e) { return ''; }
                }
                const rawText = decodeB64('$b64Text');

                function findCmView() {
                    const nodes = document.querySelectorAll('.cm-editor, .cm-content, .cm-scroller, .cm-line');
                    for (let i = 0; i < nodes.length; i++) {
                        const n = nodes[i];
                        const hooks = [n.cmView, n.cmTile, n.__cmView];
                        for (const cv of hooks) {
                            if (!cv) continue;
                            if (cv.view && typeof cv.view.dispatch === 'function' && cv.view.state) return cv.view;
                            if (cv.rootView && cv.rootView.view && typeof cv.rootView.view.dispatch === 'function') return cv.rootView.view;
                            for (const k of Object.keys(cv)) {
                                const v = cv[k];
                                if (v && typeof v === 'object' && typeof v.dispatch === 'function' && v.state && v.state.doc) return v;
                                if (v && typeof v === 'object' && v.view && typeof v.view.dispatch === 'function') return v.view;
                            }
                        }
                    }
                    return null;
                }

                const cmView = findCmView();
                const cmContent = document.querySelector('.cm-content[contenteditable="true"]');
                const monacoModels = (window.monaco && window.monaco.editor && window.monaco.editor.getModels) ? window.monaco.editor.getModels() : [];
                let target = document.activeElement;
                if (!target || target === document.body || target.tagName === 'INPUT') {
                    target = cmContent || document.querySelector('.monaco-editor textarea.inputarea, [contenteditable="true"], textarea');
                }

                function readCurrentCode() {
                    if (monacoModels.length > 0) return (monacoModels[0].getValue() || '').replace(/\r\n/g, '\n');
                    if (cmView) return (cmView.state.doc.toString() || '').replace(/\r\n/g, '\n');
                    const roTa = document.querySelector('textarea[data-testid="read-only-cursor-text-area"], textarea.file-editor-textarea');
                    if (roTa && roTa.value) return roTa.value.replace(/\r\n/g, '\n');
                    if (cmContent) {
                        const lines = cmContent.querySelectorAll('.cm-line');
                        if (lines.length > 0) {
                            const arr = [];
                            lines.forEach(l => arr.push((l.innerText || l.textContent || '').replace(/\u200B/g, '').replace(/\r?\n/g, '')));
                            return arr.join('\n');
                        }
                        return (cmContent.innerText || '').replace(/\r\n/g, '\n');
                    }
                    if (target && 'value' in target) return (target.value || '').replace(/\r\n/g, '\n');
                    return '';
                }

                function writeFullCode(newCode) {
                    if (cmView) {
                        try {
                            cmView.dispatch({ changes: { from: 0, to: cmView.state.doc.length, insert: newCode }, selection: { anchor: newCode.length } });
                            if (readCurrentCode().trim() === newCode.trim()) return 'codemirror6_dispatch';
                        } catch (e) {}
                    }
                    if (monacoModels.length > 0) {
                        try { monacoModels[0].setValue(newCode); return 'monaco_model'; } catch (e) {}
                    }
                    const editNode = cmContent || target;
                    if (editNode) {
                        editNode.focus();
                        const sel = window.getSelection();
                        const range = document.createRange();
                        range.selectNodeContents(editNode);
                        sel.removeAllRanges(); sel.addRange(range);
                        document.execCommand('delete', false, null);
                        if (newCode.length > 0) {
                            const dt = new DataTransfer();
                            dt.setData('text/plain', newCode);
                            editNode.dispatchEvent(new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true, composed: true }));
                        }
                        return 'clipboard_paste_event';
                    }
                    return 'none';
                }

                if (action === 'get_code' || action === 'get_chunk' || action === 'copy') {
                    const code = readCurrentCode();
                    return JSON.stringify({ status: 'ok', action: action, actual_length: code.length, lines_count: code ? code.split('\n').length : 0, content: code });
                }

                if (action === 'set_code' || action === 'insert' || action === 'paste') {
                    const eng = writeFullCode(rawText);
                    const actual = readCurrentCode();
                    const exact = (actual === rawText);
                    const norm = (actual.trim() === rawText.trim());
                    return JSON.stringify({
                        status: (exact || norm) ? 'verified_exact_match' : 'mismatch_detected',
                        verified: (exact || norm),
                        exact_match: exact,
                        normalized_match: norm,
                        engine: eng,
                        expected_length: rawText.length,
                        actual_length: actual.length,
                        readback_full: actual
                    });
                }

                if (action === 'clear') {
                    writeFullCode('');
                    const actual = readCurrentCode();
                    return JSON.stringify({ status: 'cleared', verified: actual.length === 0, actual_length: actual.length });
                }
                return JSON.stringify({ status: 'ok', content: readCurrentCode() });
            })();
        """.trimIndent()

        val rawRes = evalJsSync(js)
        val resObj = try { if (rawRes.startsWith("{")) JSONObject(rawRes) else JSONObject() } catch (_: Exception) { JSONObject() }
        val ghState = inspectGitHubPageStateSync()
        val urlMeta = parseGitHubUrlMeta(currentUrl)
        val effPath = ghState.optString("effective_full_path", "").ifEmpty { urlMeta.optString("url_path", "") }
        val branch = urlMeta.optString("branch", "main")
        val repo = urlMeta.optString("repo", activeBuildRepo)

        resObj.put("repo", repo).put("branch", branch).put("filename", effPath.substringAfterLast("/")).put("file_path", effPath)

        if (effectiveAction == "get_code" || effectiveAction == "get_chunk") {
            var fullCode = resObj.optString("content", "")
            if (fullCode.isEmpty() && effPath.isNotEmpty() && repo.contains("/")) {
                try {
                    val apiRes = JSONObject(performGitHubFileApiSync("GET", JSONObject().put("repo", repo).put("path", effPath).put("branch", branch)))
                    if (apiRes.optBoolean("success", false)) {
                        fullCode = apiRes.optString("content", "")
                        resObj.put("engine", "github_api_fallback")
                    }
                } catch (_: Exception) {}
            }
            val fullHash = sha256Hex(fullCode)
            if (!body.optBoolean("_internal_no_snapshot", false) && effPath.isNotEmpty() && !originalFileSnapshots.containsKey(effPath)) {
                originalFileSnapshots[effPath] = fullCode
            }
            val offset = body.optInt("offset", 0).coerceAtLeast(0)
            val limit = body.optInt("limit", 0)
            val sliced = if (limit > 0 && offset < fullCode.length) fullCode.substring(offset, (offset + limit).coerceAtMost(fullCode.length)) else fullCode

            resObj.put("success", true).put("editor_length", fullCode.length).put("content_hash", fullHash).put("offset", offset).put("chunk_length", sliced.length).put("has_more", limit > 0 && (offset + limit) < fullCode.length).put("content", sliced)
        } else if (resObj.has("readback_full")) {
            val rb = resObj.optString("readback_full", "")
            val ok = resObj.optBoolean("verified", false)
            resObj.put("success", ok).put("editor_length", rb.length).put("content_hash", sha256Hex(rb)).put("sha256_expected", sha256Hex(text)).put("sha256_actual", sha256Hex(rb))
            if (rb.length > 3500) resObj.put("readback_full", rb.take(3500) + "...[truncated]")
        }

        resObj.put("elapsed_ms", System.currentTimeMillis() - startMs)
        resObj.put("error_code", if (resObj.optBoolean("success", true)) JSONObject.NULL else "EDITOR_MISMATCH")
        if (body.optBoolean("_internal_no_snapshot", false)) return resObj.toString()
        return attachFreshStateAndDirective(resObj, "editor_$effectiveAction")
    }

    private fun performGetDiffSync(body: JSONObject?): String {
        val startMs = System.currentTimeMillis()
        val ghState = inspectGitHubPageStateSync()
        val urlMeta = parseGitHubUrlMeta(currentUrl)
        val effPath = ghState.optString("effective_full_path", "").ifEmpty { urlMeta.optString("url_path", "") }
        val branch = urlMeta.optString("branch", "main")
        val repo = urlMeta.optString("repo", activeBuildRepo)

        val edObj = JSONObject(performCodeEditorActionSync(JSONObject().put("action", "get_code").put("_internal_no_snapshot", true)))
        val currentCode = edObj.optString("content", "")
        val baseline = originalFileSnapshots[effPath] ?: ""

        val oldLines = if (baseline.isEmpty()) emptyList() else baseline.split("\n")
        val newLines = if (currentCode.isEmpty()) emptyList() else currentCode.split("\n")
        val sb = StringBuilder("--- a/$effPath\n+++ b/$effPath\n")
        var added = 0; var removed = 0
        for (i in 0 until maxOf(oldLines.size, newLines.size)) {
            val o = oldLines.getOrNull(i); val n = newLines.getOrNull(i)
            if (o != n) {
                if (o != null) { removed++; if (sb.length < 4000) sb.append("-L${i + 1}: $o\n") }
                if (n != null) { added++; if (sb.length < 4000) sb.append("+L${i + 1}: $n\n") }
            }
        }
        val pathMatches = expectedTargetFilePath.isEmpty() || effPath.equals(expectedTargetFilePath, ignoreCase = true)
        val out = JSONObject()
            .put("success", true).put("status", "ok").put("repo", repo).put("branch", branch).put("filename", effPath.substringAfterLast("/")).put("file_path", effPath)
            .put("path_matches_expected", pathMatches).put("original_length", baseline.length).put("current_length", currentCode.length)
            .put("original_hash", sha256Hex(baseline)).put("current_hash", sha256Hex(currentCode))
            .put("is_dirty", baseline != currentCode).put("added_lines_count", added).put("removed_lines_count", removed)
            .put("safe_to_commit", effPath.isNotEmpty() && currentCode.isNotEmpty() && pathMatches)
            .put("unified_diff", sb.toString()).put("elapsed_ms", System.currentTimeMillis() - startMs)
        return attachFreshStateAndDirective(out, "diff")
    }

    private fun performGitHubFileApiSync(method: String, body: JSONObject?): String {
        val urlMeta = parseGitHubUrlMeta(currentUrl)
        val repo = body?.optString("repo", "")?.ifEmpty { urlMeta.optString("repo", activeBuildRepo) } ?: activeBuildRepo
        val branch = body?.optString("branch", "")?.ifEmpty { urlMeta.optString("branch", "main") } ?: "main"
        val path = (body?.optString("path", body.optString("file_path", ""))?.ifEmpty { urlMeta.optString("url_path", "") } ?: "").removePrefix("/")
        val token = getStoredGitHubToken()
        if (path.isEmpty()) return JSONObject().put("success", false).put("error_code", "MISSING_PATH").toString()

        return try {
            val conn = URL("https://api.github.com/repos/$repo/contents/$path?ref=$branch").openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Gemini-Browser"); conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (token.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $token")
            val obj = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val decoded = String(Base64.decode(obj.optString("content", "").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8).replace("\r\n", "\n")
            originalFileSnapshots[path] = decoded
            JSONObject().put("success", true).put("repo", repo).put("branch", branch).put("file_path", path).put("blob_sha", obj.optString("sha", "")).put("editor_length", decoded.length).put("content_hash", sha256Hex(decoded)).put("content", decoded).toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("error_code", "GITHUB_READ_ERR").put("message", e.message).toString()
        }
    }

    private fun performGitHubDirectCommitSync(body: JSONObject?): String {
        val urlMeta = parseGitHubUrlMeta(currentUrl)
        val ghState = inspectGitHubPageStateSync()
        val repo = body?.optString("repo", "")?.ifEmpty { urlMeta.optString("repo", activeBuildRepo) } ?: activeBuildRepo
        val branch = body?.optString("branch", "")?.ifEmpty { urlMeta.optString("branch", "main") } ?: "main"
        val path = (body?.optString("path", body.optString("file_path", ""))?.ifEmpty { ghState.optString("effective_full_path", "").ifEmpty { urlMeta.optString("url_path", "") } } ?: "").removePrefix("/")
        val msg = body?.optString("message", "Update $path") ?: "Update $path"
        if (body != null && body.has("token")) saveStoredGitHubToken(body.optString("token", ""))
        val token = getStoredGitHubToken()
        if (token.isEmpty()) return JSONObject().put("success", false).put("error_code", "MISSING_TOKEN").put("message", "POST /github/token first").toString()

        val b64In = body?.optString("text_b64", "") ?: ""
        val content = when {
            b64In.isNotEmpty() -> String(Base64.decode(b64In, Base64.DEFAULT), Charsets.UTF_8).replace("\r\n", "\n")
            body?.has("text") == true -> body.optString("text", "").replace("\r\n", "\n")
            else -> JSONObject(performCodeEditorActionSync(JSONObject().put("action", "get_code").put("_internal_no_snapshot", true))).optString("content", "")
        }
        if (content.isEmpty()) return JSONObject().put("success", false).put("error_code", "EMPTY_CONTENT").toString()

        return try {
            var blobSha = body?.optString("blob_sha", "") ?: ""
            if (blobSha.isEmpty()) {
                try {
                    val gc = URL("https://api.github.com/repos/$repo/contents/$path?ref=$branch").openConnection() as HttpURLConnection
                    gc.setRequestProperty("User-Agent", "Gemini-Browser"); gc.setRequestProperty("Authorization", "Bearer $token")
                    if (gc.responseCode == 200) blobSha = JSONObject(gc.inputStream.bufferedReader().use { it.readText() }).optString("sha", "")
                } catch (_: Exception) {}
            }
            val pc = URL("https://api.github.com/repos/$repo/contents/$path").openConnection() as HttpURLConnection
            pc.requestMethod = "PUT"; pc.doOutput = true
            pc.setRequestProperty("User-Agent", "Gemini-Browser"); pc.setRequestProperty("Authorization", "Bearer $token"); pc.setRequestProperty("Content-Type", "application/json")
            val payload = JSONObject().put("message", msg).put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)).put("branch", branch)
            if (blobSha.isNotEmpty()) payload.put("sha", blobSha)
            pc.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val resp = JSONObject((if (pc.responseCode in 200..299) pc.inputStream else pc.errorStream)!!.bufferedReader().use { it.readText() })
            val newSha = resp.optJSONObject("commit")?.optString("sha", "") ?: ""
            activeBuildHeadSha = newSha; originalFileSnapshots[path] = content
            JSONObject().put("success", newSha.isNotEmpty()).put("status", "committed_via_github_api").put("repo", repo).put("branch", branch).put("file_path", path).put("commit_sha", newSha).put("content_hash", sha256Hex(content)).put("editor_length", content.length).toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("error_code", "COMMIT_ERR").put("message", e.message).toString()
        }
    }

    private fun performTriggerOrBindBuildSync(body: JSONObject?): String {
        val repo = body?.optString("repo", "")?.ifEmpty { activeBuildRepo } ?: activeBuildRepo
        val branch = body?.optString("branch", "main") ?: "main"
        val workflow = body?.optString("workflow", "") ?: ""
        val token = getStoredGitHubToken()
        if (body?.has("commit_sha") == true) activeBuildHeadSha = body.optString("commit_sha", "")

        var mode = "bound_to_latest_run"
        if (workflow.isNotEmpty() && token.isNotEmpty()) {
            try {
                val conn = URL("https://api.github.com/repos/$repo/actions/workflows/$workflow/dispatches").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"; conn.doOutput = true
                conn.setRequestProperty("User-Agent", "Gemini-Browser"); conn.setRequestProperty("Authorization", "Bearer $token")
                conn.outputStream.use { it.write(JSONObject().put("ref", branch).toString().toByteArray()) }
                mode = "workflow_dispatch_http_${conn.responseCode}"
                Thread.sleep(1200)
            } catch (_: Exception) {}
        }
        val st = JSONObject(performGetBuildStatusSync(JSONObject().put("repo", repo).put("commit_sha", activeBuildHeadSha)))
        st.put("build_trigger_mode", mode)
        return st.toString()
    }

    private fun performGetBuildStatusSync(body: JSONObject?): String {
        val repo = body?.optString("repo", "")?.ifEmpty { activeBuildRepo } ?: activeBuildRepo
        val filterSha = body?.optString("commit_sha", "")?.ifEmpty { activeBuildHeadSha } ?: activeBuildHeadSha
        val downloadApk = body?.optBoolean("download_apk", false) ?: false
        val token = getStoredGitHubToken()

        fun apiGet(u: String): String {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true; c.setRequestProperty("User-Agent", "Gemini-Browser")
            if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        return try {
            val runs = JSONObject(apiGet("https://api.github.com/repos/$repo/actions/runs?per_page=8")).optJSONArray("workflow_runs") ?: JSONArray()
            var run: JSONObject? = null
            for (i in 0 until runs.length()) {
                val r = runs.getJSONObject(i)
                if (filterSha.isNotEmpty() && r.optString("head_sha", "").startsWith(filterSha)) { run = r; break }
            }
            if (run == null && runs.length() > 0) run = runs.getJSONObject(0)
            if (run == null) return JSONObject().put("status", "no_runs").toString()

            val runId = run.optLong("id", 0L)
            val conclusion = run.optString("conclusion", "")
            val stepsArr = JSONArray(); val failedArr = JSONArray(); val errLogs = JSONArray()

            val jobs = JSONObject(apiGet("https://api.github.com/repos/$repo/actions/runs/$runId/jobs")).optJSONArray("jobs") ?: JSONArray()
            for (j in 0 until jobs.length()) {
                val job = jobs.getJSONObject(j)
                val steps = job.optJSONArray("steps") ?: JSONArray()
                for (s in 0 until steps.length()) {
                    val st = steps.getJSONObject(s)
                    stepsArr.put(JSONObject().put("name", st.optString("name")).put("status", st.optString("status")).put("conclusion", st.optString("conclusion")))
                    if (st.optString("conclusion") == "failure") failedArr.put(st.optString("name"))
                }
                if (job.optString("conclusion") == "failure") {
                    try {
                        val rawLog = apiGet("https://api.github.com/repos/$repo/actions/jobs/${job.optLong("id")}/logs")
                        rawLog.lines().filter { l -> listOf("error:", "exception", "failed", "unresolved").any { l.lowercase().contains(it) } }.takeLast(30).forEach { errLogs.put(it.trim()) }
                    } catch (_: Exception) {}
                }
            }

            val apkObj = JSONObject().put("available", false)
            try {
                val rel = JSONObject(apiGet("https://api.github.com/repos/$repo/releases/latest"))
                val assets = rel.optJSONArray("assets") ?: JSONArray()
                if (assets.length() > 0) {
                    val a0 = assets.getJSONObject(0)
                    val dlUrl = a0.optString("browser_download_url", "")
                    val apkName = a0.optString("name", "app.apk")
                    val apkSize = a0.optLong("size", 0L)
                    apkObj.put("available", true).put("apk_name", apkName).put("apk_size", apkSize).put("download_url", dlUrl)
                    if (downloadApk && conclusion == "success" && dlUrl.isNotEmpty()) {
                        val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), apkName)
                        URL(dlUrl).openStream().use { inp -> FileOutputStream(dest).use { inp.copyTo(it) } }
                        apkObj.put("downloaded_to_device", true).put("local_path", dest.absolutePath).put("local_size_verified", dest.length() == apkSize)
                    }
                }
            } catch (_: Exception) {}

            JSONObject().put("success", true).put("status", "ok").put("repo", repo).put("build_id", runId).put("head_sha", run.optString("head_sha")).put("run_status", run.optString("status")).put("conclusion", conclusion).put("steps", stepsArr).put("failed_steps", failedArr).put("error_logs", errLogs).put("apk_artifact", apkObj).toString()
        } catch (e: Exception) {
            JSONObject().put("success", false).put("status", "api_error").put("message", e.message).toString()
        }
    }

    private fun executeCommand(method: String, path: String, body: JSONObject?): String {
        val startMs = System.currentTimeMillis()
        isEmergencyStopped.set(false)
        appendLog("$method $path")
        setConsoleStatus("🟢 127.0.0.1:$SERVER_PORT | $method $path", false)

        if (body != null && body.has("tab_id") && !path.startsWith("/tab") && path != "/switch_tab") {
            val tid = body.optInt("tab_id", -1)
            if (tid > 0 && activeTab?.id != tid) findTabByQuery(tabId = tid)?.let { runOnMainSync { switchToTab(it) }; Thread.sleep(80) }
        }

        return when (path) {
            "/", "/status" -> {
                val res = JSONObject()
                    .put("status", "online").put("browser", "Gemini Autonomous Browser").put("port", SERVER_PORT)
                    .put("cdp_enabled", true).put("github_token_configured", getStoredGitHubToken().isNotEmpty())
                    .put("active_tab_id", activeTab?.id ?: -1).put("tabs", buildTabsJsonArray())
                    .put("elapsed_ms", System.currentTimeMillis() - startMs).put("error_code", JSONObject.NULL)
                attachFreshStateAndDirective(res, "status")
            }
            "/page_text", "/text", "/reader" -> extractCleanPageTextSync(body)
            "/pipeline", "/auto_pipeline" -> performAtomicPipelineSync(body)
            "/reset_loop" -> {
                loopStepReadDone = false; loopStepEditVerified = false; loopStepDiffVerified = false
                loopStepCommitVerified = false; loopStepBuildVerified = false; lastVerifiedCommitSha = ""
                attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "loop_state_reset"), "reset_loop")
            }

            "/eval", "/userscript" -> {
                val b64 = body?.optString("code_b64", body.optString("text_b64", "")) ?: ""
                val code = if (b64.isNotEmpty()) String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) else (body?.optString("code", body.optString("script", "")) ?: "")
                val res = evalJsSync(code)
                attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "executed").put("result", res), "eval")
            }
            "/goal" -> {
                activeGoalDescription = body?.optString("goal", activeGoalDescription) ?: activeGoalDescription
                expectedTargetFilePath = body?.optString("expected_path", expectedTargetFilePath) ?: expectedTargetFilePath
                expectedTargetContentLength = body?.optInt("expected_length", expectedTargetContentLength) ?: expectedTargetContentLength
                attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "goal_registered"), "goal")
            }
            "/github_state" -> attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "ok"), "github_state")
            "/diff", "/git_diff" -> performGetDiffSync(body)
            "/github/token", "/config/token" -> {
                saveStoredGitHubToken(body?.optString("token", "") ?: "")
                JSONObject().put("success", getStoredGitHubToken().isNotEmpty()).put("github_token_configured", getStoredGitHubToken().isNotEmpty()).toString()
            }
            "/github/file" -> performGitHubFileApiSync(method, body)
            "/github/commit", "/commit" -> performGitHubDirectCommitSync(body)
            "/set_github_path", "/github_path" -> performSetGitHubPathSync(body?.optString("path", body.optString("file_path", "")) ?: "", body?.optBoolean("allow_rename", false) ?: false)
            "/editor" -> {
                val edBody = body ?: JSONObject()
                if (!edBody.has("action")) {
                    when (method) {
                        "GET" -> edBody.put("action", if (edBody.has("limit")) "get_chunk" else "get_code")
                        "PUT" -> edBody.put("action", "set_code")
                        else -> if (edBody.has("text") || edBody.has("text_b64")) edBody.put("action", "set_code") else edBody.put("action", "get_code")
                    }
                }
                performCodeEditorActionSync(edBody)
            }
            "/build" -> performTriggerOrBindBuildSync(body)
            "/build/status", "/build_status" -> performGetBuildStatusSync(body)
            "/verify", "/github_verify" -> performGitHubVerificationSync(body)
            "/locator" -> performPlaywrightLocatorSync(body ?: JSONObject())
            "/tabs", "/tab", "/switch_tab", "/new_tab", "/close_tab" -> {
                val act = when (path) {
                    "/switch_tab" -> "switch"; "/new_tab" -> "new"; "/close_tab" -> "close"
                    else -> body?.optString("action", "list") ?: "list"
                }
                when (act) {
                    "new", "open" -> {
                        var t: BrowserTab? = null
                        runOnMainSync { t = createNewTab(body?.optString("url", "https://gemini.google.com") ?: "https://gemini.google.com", true) }
                        attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "tab_created").put("tab_id", t?.id ?: -1), "new_tab")
                    }
                    "switch" -> {
                        val t = findTabByQuery(body?.optInt("tab_id", -1) ?: -1, body?.optInt("index", -1) ?: -1, body?.optString("query", "") ?: "")
                        if (t != null) { runOnMainSync { switchToTab(t) }; attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "switched").put("active_tab_id", t.id), "switch_tab") }
                        else JSONObject().put("success", false).put("error_code", "TAB_NOT_FOUND").toString()
                    }
                    "close" -> {
                        val t = findTabByQuery(body?.optInt("tab_id", activeTab?.id ?: -1) ?: -1)
                        if (t != null) { runOnMainSync { closeTab(t) }; attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "closed"), "close_tab") }
                        else JSONObject().put("success", false).put("error_code", "TAB_NOT_FOUND").toString()
                    }
                    else -> attachFreshStateAndDirective(JSONObject().put("success", true).put("tabs", buildTabsJsonArray()), "tabs")
                }
            }
            "/desktop" -> {
                applyDesktopMode(body?.optBoolean("enable", !desktopMode) ?: !desktopMode)
                Thread.sleep(300)
                attachFreshStateAndDirective(JSONObject().put("success", true).put("desktop_mode", desktopMode), "desktop")
            }
            "/navigate" -> {
                val targetUrl = body?.optString("url", "https://gemini.google.com") ?: "https://gemini.google.com"
                if (body?.has("desktop_mode") == true) {
                    val wantPc = body.optBoolean("desktop_mode", desktopMode)
                    if (wantPc != desktopMode) applyDesktopMode(wantPc)
                }
                activeTab?.isPageLoading = true; activeTab?.isNetworkIdle = false
                runOnMainSync { webView.loadUrl(targetUrl) }
                val timeout = body?.optLong("timeout_ms", 8000L) ?: 8000L
                val st = System.currentTimeMillis()
                while (System.currentTimeMillis() - st < timeout) {
                    if (!isPageLoading && isNetworkIdle) break
                    Thread.sleep(200)
                }
                attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "navigated").put("url", currentUrl).put("elapsed_ms", System.currentTimeMillis() - startMs), "navigate")
            }
            "/dom" -> extractDomSync(true)
            "/click" -> performSmartClickSync(body?.optInt("element_id", -1) ?: -1, body?.optDouble("x", -1.0)?.toFloat() ?: -1f, body?.optDouble("y", -1.0)?.toFloat() ?: -1f, body?.optString("text", "") ?: "", body?.optString("context", "") ?: "")
            "/type" -> {
                val b64 = body?.optString("text_b64", "") ?: ""
                val txt = if (b64.isNotEmpty()) String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) else (body?.optString("text", "") ?: "")
                performHumanTypingSync(body?.optInt("element_id", -1) ?: -1, txt, body?.optBoolean("clear", false) ?: false, body?.optBoolean("submit", false) ?: false, body?.optBoolean("instant", true) ?: true)
            }
            "/scroll" -> performSmoothScrollSync(body?.optDouble("dx", 0.0)?.toFloat() ?: 0f, body?.optDouble("dy", 550.0)?.toFloat() ?: 550f, body?.optInt("element_id", -1) ?: -1)
            "/key" -> performSpecialKeySync(body?.optString("key", "Enter") ?: "Enter")
            "/wait" -> waitForSelectorSync(body?.optString("selector", "") ?: "", body?.optLong("timeout_ms", 8000L) ?: 8000L)
            "/ocr" -> performOcrAndDeepTextSync(body?.optString("mode", "viewport") ?: "viewport", body?.optBoolean("show_boxes", true) ?: true)
            "/screenshot" -> JSONObject().put("success", true).put("url", currentUrl).put("image_base64", captureViewportBase64Sync()).toString()
            else -> JSONObject().put("success", false).put("error_code", "UNKNOWN_ENDPOINT").put("message", "Unknown: $path").toString()
        }
    }

    private fun extractCleanPageTextSync(body: JSONObject?): String {
        val startMs = System.currentTimeMillis()
        val offset = body?.optInt("offset", 0)?.coerceAtLeast(0) ?: 0
        val limit = body?.optInt("limit", 0)?.coerceAtLeast(0) ?: 0

        val js = """
            (function() {
                const errSelectors = '.flash-error, .flash-warn, [role="alert"], .js-flash-alert, .form-control.is-error, .error, .hx_flash, .Toast-item--error, .banner-error';
                const errorNodes = document.querySelectorAll(errSelectors);
                const errors = [];
                errorNodes.forEach(el => {
                    const t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
                    if (t && !errors.includes(t)) errors.push(t);
                });

                const openDialog = document.querySelector('[role="dialog"], dialog[open], .Overlay, .Box-overlay');
                let dialogText = '';
                if (openDialog) {
                    dialogText = (openDialog.innerText || '').replace(/\n{3,}/g, '\n\n').trim();
                    if (/failed|error|conflict|cannot|protected|invalid|empty/i.test(dialogText)) {
                        const firstLine = dialogText.split('\n')[0];
                        if (!errors.includes(firstLine)) errors.push('[DIALOG] ' + firstLine);
                    }
                }

                if (!document.body) {
                    return JSON.stringify({ errors: errors, dialog_text: dialogText, full_text: '' });
                }

                const clone = document.body.cloneNode(true);
                clone.querySelectorAll('script, style, noscript, svg, canvas, iframe').forEach(e => e.remove());
                const cleanText = (clone.innerText || '').replace(/\n{3,}/g, '\n\n').trim();

                return JSON.stringify({
                    errors: errors,
                    dialog_text: dialogText,
                    full_text: cleanText
                });
            })();
        """.trimIndent()

        val raw = evalJsSync(js)
        val data = try { if (raw.startsWith("{")) JSONObject(raw) else JSONObject() } catch (_: Exception) { JSONObject() }

        val errorsArr = data.optJSONArray("errors") ?: JSONArray()
        val dlgText = data.optString("dialog_text", "")
        val fullText = data.optString("full_text", "")

        val totalLen = fullText.length
        val slicedText = if (limit > 0 && offset < totalLen) {
            fullText.substring(offset, (offset + limit).coerceAtMost(totalLen))
        } else {
            fullText
        }

        val out = JSONObject()
            .put("success", true)
            .put("status", "ok")
            .put("url", currentUrl)
            .put("title", currentTitle)
            .put("has_commit_errors", errorsArr.length() > 0)
            .put("errors_detected", errorsArr)
            .put("modal_dialog_text", dlgText)
            .put("total_text_length", totalLen)
            .put("offset", offset)
            .put("chunk_length", slicedText.length)
            .put("has_more", limit > 0 && (offset + limit) < totalLen)
            .put("text", slicedText)
            .put("elapsed_ms", System.currentTimeMillis() - startMs)

        return attachFreshStateAndDirective(out, "page_text")
    }

    private fun performGitHubVerificationSync(body: JSONObject?): String {
        val gh = inspectGitHubPageStateSync()
        val isEditor = gh.optBoolean("is_in_editor", false)
        val sha = gh.optString("commit_sha", "")
        val path = gh.optString("effective_full_path", "")
        val verified = !isEditor && sha.isNotEmpty()
        val out = JSONObject().put("success", verified).put("status", if (verified) "verified_success" else "unverified_or_in_editor").put("commit_verified", verified).put("is_still_in_editor", isEditor).put("commit_sha", sha).put("file_path", path)
        return attachFreshStateAndDirective(out, "verify")
    }

    private fun performPlaywrightLocatorSync(body: JSONObject): String {
        val action = body.optString("action", "click").lowercase(Locale.ROOT)
        val text = body.optString("text", body.optString("name", ""))
        val ctx = body.optString("context", "")
        if (action == "click") return performSmartClickSync(-1, -1f, -1f, text, ctx)
        val valStr = body.optString("value", "")
        return performHumanTypingSync(-1, valStr, true, false, true)
    }

    private fun extractDomSync(showBoxes: Boolean): String {
        val overlayW = overlayView.width.coerceAtLeast(1)
        val js = """
            (function() {
                const scale = $overlayW / (window.innerWidth || 1);
                const list = [];
                let idCounter = 1;
                const seen = new Set();

                function collectInteractiveNodes(rootDoc, frameOffsetX, frameOffsetY) {
                    if (!rootDoc) return;
                    const baseSel = 'a, button, input, select, textarea, summary, [role="button"], [role="link"], [role="option"], [role="menuitem"], [role="tab"], [role="checkbox"], [role="textbox"], [contenteditable="true"], .cm-content, [onclick], [tabindex]';
                    const nodes = Array.from(rootDoc.querySelectorAll(baseSel));

                    // Also include custom clickable divs/spans with cursor:pointer (like browser-use)
                    const allDivs = rootDoc.querySelectorAll('div, span, li');
                    for (let i = 0; i < allDivs.length; i++) {
                        const d = allDivs[i];
                        if (d.children.length <= 2) {
                            const cs = window.getComputedStyle(d);
                            if (cs.cursor === 'pointer' && (d.innerText || '').trim().length > 0 && (d.innerText || '').trim().length < 60) {
                                nodes.push(d);
                            }
                        }
                        if (d.shadowRoot) {
                            collectInteractiveNodes(d.shadowRoot, frameOffsetX, frameOffsetY);
                        }
                    }

                    for (const el of nodes) {
                        if (seen.has(el)) continue;
                        seen.add(el);
                        const r = el.getBoundingClientRect();
                        const s = window.getComputedStyle(el);
                        if (r.width > 5 && r.height > 5 && s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity || '1') > 0.05) {
                            const absLeft = r.left + frameOffsetX;
                            const absTop = r.top + frameOffsetY;
                            const absRight = r.right + frameOffsetX;
                            const absBottom = r.bottom + frameOffsetY;

                            if (absBottom > 0 && absRight > 0 && absTop < window.innerHeight && absLeft < window.innerWidth) {
                                const id = idCounter++;
                                el.setAttribute('data-ai-id', id);
                                const inDlg = el.closest('[role="dialog"], dialog[open], .Overlay, .Box-overlay') !== null;
                                const tag = el.tagName.toLowerCase();
                                const rawTxt = (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || el.getAttribute('title') || '').replace(/\s+/g, ' ').trim();
                                list.push({
                                    id: id,
                                    tag: tag,
                                    role: el.getAttribute('role') || tag,
                                    context: inDlg ? 'dialog' : 'main',
                                    in_open_dialog: inDlg,
                                    disabled: Boolean(el.disabled || el.getAttribute('aria-disabled') === 'true'),
                                    text: rawTxt.slice(0, 120),
                                    x: Math.round((absLeft + r.width / 2) * scale),
                                    y: Math.round((absTop + r.height / 2) * scale),
                                    left: Math.round(absLeft * scale),
                                    top: Math.round(absTop * scale),
                                    right: Math.round(absRight * scale),
                                    bottom: Math.round(absBottom * scale)
                                });
                            }
                        }
                    }

                    // Traverse same-origin iframes
                    const iframes = rootDoc.querySelectorAll ? rootDoc.querySelectorAll('iframe') : [];
                    for (let i = 0; i < iframes.length; i++) {
                        try {
                            const fr = iframes[i];
                            const frRect = fr.getBoundingClientRect();
                            if (fr.contentDocument) {
                                collectInteractiveNodes(fr.contentDocument, frameOffsetX + frRect.left, frameOffsetY + frRect.top);
                            }
                        } catch (e) {}
                    }
                }

                collectInteractiveNodes(document, 0, 0);
                return JSON.stringify(list);
            })();
        """.trimIndent()
        val jsonStr = evalJsSync(js)
        val arr = try { if (jsonStr.startsWith("[")) JSONArray(jsonStr) else JSONArray() } catch (_: Exception) { JSONArray() }
        if (showBoxes && arr.length() > 0) {
            val boxes = mutableListOf<Pair<RectF, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                boxes.add(Pair(RectF(o.getDouble("left").toFloat(), o.getDouble("top").toFloat(), o.getDouble("right").toFloat(), o.getDouble("bottom").toFloat()), "[${o.getInt("id")}]"))
            }
            runOnMainSync { overlayView.showDomBoxes(boxes) }
        }
        return arr.toString()
    }

    private fun performSmartClickSync(elementId: Int, rawX: Float, rawY: Float, matchText: String = "", contextFilter: String = ""): String {
        val startMs = System.currentTimeMillis()
        evalJsSync("(function(){ if (window.__aiCommitActiveInput) window.__aiCommitActiveInput(); })()")

        if (elementId in 9001..9098) {
            findTabByQuery(tabId = elementId - 9000)?.let { runOnMainSync { switchToTab(it) } }
            return attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "switched_tab"), "click")
        }

        if (matchText.isNotEmpty() && elementId <= 0) {
            val safeT = matchText.replace("'", "\\'").lowercase(Locale.ROOT)
            val safeC = contextFilter.replace("'", "\\'").lowercase(Locale.ROOT)
            val res = evalJsSync("""
                (function() {
                    const nodes = Array.from(document.querySelectorAll('button, a, input[type="submit"], [role="button"], [role="menuitem"], summary'));
                    const scored = [];
                    for (const el of nodes) {
                        const r = el.getBoundingClientRect();
                        if (r.width <= 4 || r.height <= 4 || el.disabled || el.getAttribute('aria-disabled') === 'true') continue;
                        const t = (el.innerText || el.value || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().toLowerCase();
                        const inDlg = el.closest('[role="dialog"], dialog[open], .Overlay') !== null;
                        if ('$safeC' === 'dialog' && !inDlg) continue;
                        if (t === '$safeT' || t.includes('$safeT')) {
                            scored.push({ el: el, score: (t === '$safeT' ? 100 : 40) + (inDlg ? 200 : 0), text: t, in_dialog: inDlg });
                        }
                    }
                    scored.sort((a, b) => b.score - a.score);
                    if (scored.length > 0) {
                        scored[0].el.scrollIntoView({ behavior: 'instant', block: 'center' });
                        scored[0].el.click();
                        return JSON.stringify({ success: true, status: 'clicked_by_semantic_text', clicked_text: scored[0].text, in_open_dialog: scored[0].in_dialog });
                    }
                    return '';
                })();
            """.trimIndent())
            if (res.startsWith("{")) {
                Thread.sleep(300)
                return attachFreshStateAndDirective(JSONObject(res).put("elapsed_ms", System.currentTimeMillis() - startMs), "click")
            }
        }

        val overlayW = overlayView.width.coerceAtLeast(1)
        val wvScaleRatio = webView.width.coerceAtLeast(1).toFloat() / overlayW.toFloat()
        var sx = rawX; var sy = rawY

        if (elementId > 0) {
            val posStr = evalJsSync("""
                (function() {
                    const el = document.querySelector('[data-ai-id="$elementId"]');
                    if (!el) return '';
                    el.scrollIntoView({ behavior: 'instant', block: 'center' });
                    const r = el.getBoundingClientRect();
                    const sc = $overlayW / (window.innerWidth || 1);
                    return JSON.stringify({ x: (r.left + r.width / 2) * sc, y: (r.top + r.height / 2) * sc, disabled: Boolean(el.disabled) });
                })();
            """.trimIndent())
            if (!posStr.startsWith("{")) return attachFreshStateAndDirective(JSONObject().put("success", false).put("error_code", "ELEMENT_NOT_FOUND"), "click")
            val o = JSONObject(posStr)
            sx = o.getDouble("x").toFloat(); sy = o.getDouble("y").toFloat()
        }

        runOnMainSync {
            webView.requestFocus()
            overlayView.showActionHighlight(sx, sy, null, "CLICK")
            val dt = SystemClock.uptimeMillis()
            val d = MotionEvent.obtain(dt, dt, MotionEvent.ACTION_DOWN, sx * wvScaleRatio, sy * wvScaleRatio, 0)
            val u = MotionEvent.obtain(dt, dt + 65, MotionEvent.ACTION_UP, sx * wvScaleRatio, sy * wvScaleRatio, 0)
            webView.dispatchTouchEvent(d); webView.dispatchTouchEvent(u)
            d.recycle(); u.recycle()
        }
        if (elementId > 0) evalJsSync("(function(){ const el = document.querySelector('[data-ai-id=\"$elementId\"]'); if (el) { el.focus(); if (el.tagName==='BUTTON') el.click(); } })()")
        Thread.sleep(320)
        return attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "clicked").put("element_id", elementId).put("elapsed_ms", System.currentTimeMillis() - startMs), "click")
    }

    private fun performHumanTypingSync(elementId: Int, text: String, clear: Boolean, submit: Boolean, instant: Boolean): String {
        val startMs = System.currentTimeMillis()
        if (elementId > 0) { performSmartClickSync(elementId, -1f, -1f); Thread.sleep(100) }
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val res = evalJsSync("""
            (function() {
                const bin = atob('$b64');
                const u = new Uint8Array(bin.length);
                for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
                const decoded = new TextDecoder('utf-8').decode(u);
                const el = $elementId > 0 ? document.querySelector('[data-ai-id="$elementId"]') : document.activeElement;
                if (!el || !window.__aiSetReactSafeValue) return '';
                window.__aiSetReactSafeValue(el, decoded, decoded, false);
                const rb = el.value !== undefined ? el.value : (el.innerText || '');
                return JSON.stringify({ success: rb === decoded, status: 'typed', verified_input_value: rb, exact_match: rb === decoded });
            })();
        """.trimIndent())
        if (submit) performSpecialKeySync("Enter")
        val obj = if (res.startsWith("{")) JSONObject(res) else JSONObject().put("success", false).put("error_code", "TYPE_FAILED")
        obj.put("elapsed_ms", System.currentTimeMillis() - startMs)
        return attachFreshStateAndDirective(obj, "type")
    }

    private fun performSpecialKeySync(keyName: String): String {
        val code = when (keyName.lowercase(Locale.ROOT)) {
            "backspace" -> KeyEvent.KEYCODE_DEL; "enter" -> KeyEvent.KEYCODE_ENTER; "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE; else -> KeyEvent.KEYCODE_ENTER
        }
        runOnMainSync { webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }
        return attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "key_sent"), "key")
    }

    private fun performSmoothScrollSync(dx: Float, dy: Float, elementId: Int): String {
        evalJsSync("(function(){ window.scrollBy({ left: $dx, top: $dy, behavior: 'smooth' }); })()")
        Thread.sleep(200)
        return attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "scrolled"), "scroll")
    }

    private fun waitForSelectorSync(selector: String, timeoutMs: Long): String {
        val st = System.currentTimeMillis()
        val safe = selector.replace("'", "\\'")
        while (System.currentTimeMillis() - st < timeoutMs) {
            if (evalJsSync("(function(){ return document.querySelector('$safe') !== null ? 'true' : 'false'; })()") == "true") {
                return attachFreshStateAndDirective(JSONObject().put("success", true).put("status", "found"), "wait")
            }
            Thread.sleep(200)
        }
        return attachFreshStateAndDirective(JSONObject().put("success", false).put("status", "timeout").put("error_code", "WAIT_TIMEOUT"), "wait")
    }

    private fun captureViewportBase64Sync(): String {
        var b64 = ""
        runOnMainSync {
            val bmp = Bitmap.createBitmap(overlayView.width.coerceAtLeast(1), overlayView.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(webView.scaleX, webView.scaleY)
            canvas.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
            webView.draw(canvas)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
            b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
        return b64
    }

    private fun performOcrAndDeepTextSync(mode: String, showBoxes: Boolean): String {
        var bmp: Bitmap? = null
        runOnMainSync {
            try {
                val b = Bitmap.createBitmap(overlayView.width.coerceAtLeast(1), overlayView.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                c.scale(webView.scaleX, webView.scaleY); webView.draw(c)
                bmp = b
            } catch (_: Exception) {}
        }
        val blocksArr = JSONArray()
        var fullTxt = ""
        bmp?.let { b ->
            val latch = CountDownLatch(1)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(b, 0))
                .addOnSuccessListener { vt ->
                    fullTxt = vt.text ?: ""
                    for (blk in vt.textBlocks) for (ln in blk.lines) blocksArr.put(JSONObject().put("text", ln.text))
                    latch.countDown()
                }
                .addOnFailureListener { latch.countDown() }
            latch.await(6, TimeUnit.SECONDS)
        }
        return attachFreshStateAndDirective(JSONObject().put("success", true).put("ocr_screen_text", fullTxt).put("ocr_blocks_count", blocksArr.length()).put("ocr_blocks", blocksArr), "ocr")
    }

    private fun evalJsSync(script: String): String {
        var output = ""
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                webView.evaluateJavascript(script) { res ->
                    output = if (res != null && res.startsWith("\"") && res.endsWith("\"")) {
                        res.substring(1, res.length - 1).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")
                    } else res ?: ""
                    latch.countDown()
                }
            } catch (_: Exception) { latch.countDown() }
        }
        latch.await(5, TimeUnit.SECONDS)
        return output
    }

    private fun runOnMainSync(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else { val l = CountDownLatch(1); mainHandler.post { try { action() } finally { l.countDown() } }; l.await(5, TimeUnit.SECONDS) }
    }

    private fun setConsoleStatus(msg: String, isErr: Boolean) {
        mainHandler.post {
            consoleText.text = msg
            consoleText.setTextColor(if (isErr) Color.parseColor("#FF5252") else Color.parseColor("#00E676"))
        }
    }

    private fun appendLog(entry: String) {
        mainHandler.post {
            if (commandLogs.size >= 20) commandLogs.removeFirst()
            commandLogs.addLast("[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $entry")
            logPanel.text = commandLogs.joinToString("\n")
        }
    }

    private fun broadcastSse(ev: String, detail: String) {
        val p = JSONObject().put("event", ev).put("detail", detail).toString()
        for (pw in sseClients) try { pw.print("data: $p\n\n"); pw.flush() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    class AIOverlayView(context: Context) : View(context) {
        private var touchPoint: PointF? = null
        private val domBoxes = CopyOnWriteArrayList<Pair<RectF, String>>()
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#00E676"); style = Paint.Style.STROKE; strokeWidth = 4f }
        private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#88FF1744"); style = Paint.Style.FILL }

        fun showActionHighlight(x: Float, y: Float, box: RectF?, label: String) {
            domBoxes.clear(); touchPoint = PointF(x, y); invalidate()
            postDelayed({ touchPoint = null; invalidate() }, 800)
        }
        fun showDomBoxes(items: List<Pair<RectF, String>>) {
            domBoxes.clear(); domBoxes.addAll(items); invalidate()
            postDelayed({ domBoxes.clear(); invalidate() }, 2000)
        }
        fun clearHighlights() { domBoxes.clear(); touchPoint = null; invalidate() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (p in domBoxes) canvas.drawRoundRect(p.first, 6f, 6f, boxPaint)
            touchPoint?.let { canvas.drawCircle(it.x, it.y, 40f, touchPaint) }
        }
    }
}
