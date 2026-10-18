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
import android.text.TextUtils
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
    @Volatile private var activeTab: BrowserTab? = null

    private val webView: WebView
        get() = (activeTab ?: tabs.first()).webView

    private val desktopMode: Boolean
        get() = activeTab?.desktopMode ?: false

    private val isPageLoading: Boolean
        get() = activeTab?.isPageLoading ?: false

    private val isNetworkIdle: Boolean
        get() = activeTab?.isNetworkIdle ?: true

    private val currentUrl: String
        get() = activeTab?.url ?: "https://gemini.google.com"

    private val currentTitle: String
        get() = activeTab?.title ?: ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serverPool: ExecutorService = Executors.newCachedThreadPool()
    private var wakeLock: PowerManager.WakeLock? = null

    private val isEmergencyStopped = AtomicBoolean(false)
    private val commandLogs = LinkedList<String>()
    private val sseClients = CopyOnWriteArrayList<PrintWriter>()

    @Volatile private var queuedFilePath: String? = null

    // Pure Chrome User-Agent (Bypasses Google Login "unauthorized/disallowed_useragent" error)
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

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun makePanelButton(label: String, bgHex: String, widthDp: Int = 78, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor(bgHex))
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(30)).apply {
                marginEnd = dp(5)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun buildUserInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. Horizontally Scrollable Multi-Tab Bar
        val tabBarScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            setBackgroundColor(Color.parseColor("#141414"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        tabBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        tabBarScroll.addView(tabBarContainer)

        // 2. Navigation Bar
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
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    loadFromAddressBar()
                    true
                } else false
            }
        }

        val btnGo = makeSmallButton("GO") { loadFromAddressBar() }

        desktopBtn = makeSmallButton("MOB") {
            applyDesktopMode(!desktopMode)
        }

        navBar.addView(btnBack)
        navBar.addView(btnForward)
        navBar.addView(btnReload)
        navBar.addView(urlInput)
        navBar.addView(btnGo)
        navBar.addView(desktopBtn)

        // 3. Horizontally Draggable/Scrollable Action Panel
        val consoleScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val consoleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            setPadding(dp(6), dp(5), dp(12), dp(5))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnDomPreview = makePanelButton("DOM", "#2E7D32") {
            serverPool.execute {
                val arrStr = extractDomSync(true)
                try {
                    val arr = JSONArray(arrStr)
                    setConsoleStatus("🟢 DOM Elements: ${arr.length()}", false)
                } catch (_: Exception) {}
            }
        }

        val btnOcrPreview = makePanelButton("OCR", "#00838F") {
            serverPool.execute {
                setConsoleStatus("🟡 Running OCR...", false)
                val resStr = performOcrAndDeepTextSync("viewport", true)
                try {
                    val obj = JSONObject(resStr)
                    val blocks = obj.optJSONArray("ocr_blocks")?.length() ?: 0
                    val errs = obj.optJSONArray("errors_detected")?.length() ?: 0
                    setConsoleStatus("🟢 OCR: $blocks | Err: $errs", errs > 0)
                    appendLog("OCR Done: $blocks blocks, $errs errors")
                } catch (_: Exception) {}
            }
        }

        val btnVerify = makePanelButton("VERIFY", "#E65100") {
            serverPool.execute {
                setConsoleStatus("🟡 Verifying GitHub State...", false)
                val vStr = performGitHubVerificationSync(null)
                try {
                    val vObj = JSONObject(vStr)
                    val sha = vObj.optString("commit_sha", "").take(7)
                    val path = vObj.optString("file_path", "")
                    val runSt = vObj.optString("actions_run_status", "")
                    val summary = "SHA:${sha.ifEmpty { "N/A" }} | Path:${path.ifEmpty { "N/A" }} | Run:${runSt.ifEmpty { "N/A" }}"
                    setConsoleStatus("🟢 $summary", false)
                    appendLog("VERIFY -> $summary")
                } catch (_: Exception) {}
            }
        }

        val btnUserScript = makePanelButton("JS", "#6A1B9A") {
            showUserScriptDialog()
        }

        val btnLogs = makePanelButton("LOGS", "#37474F") {
            logPanel.visibility = if (logPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val btnStop = makePanelButton("STOP", "#D50000", 82) {
            isEmergencyStopped.set(true)
            webView.stopLoading()
            overlayView.clearHighlights()
            setConsoleStatus("🔴 [STOPPED] Kill Switch", true)
            appendLog("EMERGENCY STOP triggered by user")
            broadcastSse("emergency_stop", "User pressed STOP button")
            Toast.makeText(this, "🛑 Emergency Stop Triggered", Toast.LENGTH_SHORT).show()
        }

        consoleText = TextView(this).apply {
            text = "🟡 Starting 127.0.0.1:$SERVER_PORT..."
            setTextColor(Color.parseColor("#FFD600"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            setPadding(dp(8), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        consoleRow.addView(btnDomPreview)
        consoleRow.addView(btnOcrPreview)
        consoleRow.addView(btnVerify)
        consoleRow.addView(btnUserScript)
        consoleRow.addView(btnLogs)
        consoleRow.addView(btnStop)
        consoleRow.addView(consoleText)
        consoleScroll.addView(consoleRow)

        // 4. WebView Container + Multi-Tab Holder + AI Overlay
        webContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                if (r - l != or - ol || b - t != ob - ot) {
                    mainHandler.post { updateWebViewViewportBounds() }
                }
            }
        }

        tabViewsHolder = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        overlayView = AIOverlayView(this).apply {
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(110)
            )
            text = "[System] Gemini Multi-Tab Browser Ready on port $SERVER_PORT...\n"
        }

        root.addView(tabBarScroll)
        root.addView(navBar)
        root.addView(consoleScroll)
        root.addView(webContainer)
        root.addView(logPanel)

        setContentView(root)
    }

    // ==================== STEP 6: ACTUAL GITHUB COMMIT & ACTIONS VERIFIER ====================

    private fun performGitHubVerificationSync(body: JSONObject?): String {
        val expectedPath = body?.optString("expected_path", body.optString("path", "")) ?: ""
        val expectedText = body?.optString("expected_content", body.optString("contains", "")) ?: ""
        val repoParam = body?.optString("repo", "") ?: ""
        val tokenParam = body?.optString("token", "") ?: ""

        // 1. Deep DOM Verification on Current Browser Page (Commit SHA, File Path, File Content, Actions Run & Logs)
        val domVerifyJs = """
            (function() {
                const url = window.location.href || '';
                const pathName = window.location.pathname || '';

                // Detect if still stuck in uncommitted editor mode
                const isEditorOpen = /\/(edit|new)\//.test(pathName) ||
                    document.querySelector('.cm-content[contenteditable="true"], .monaco-editor textarea.inputarea') !== null;

                // Extract Commit SHA from URL, meta tags, or commit links
                let commitSha = '';
                const urlShaMatch = pathName.match(/\/(?:commit|blob|tree)\/([0-9a-f]{7,40})/i);
                if (urlShaMatch) commitSha = urlShaMatch[1];

                if (!commitSha) {
                    const shaEl = document.querySelector(
                        'a.commit-tease-sha, [data-testid="latest-commit-html"] a[href*="/commit/"], a[href*="/commit/"].Link--secondary, span.sha.user-select-contain, clipboard-copy[aria-label*="commit" i], meta[name="octolytics-dimension-repository_network_root_sha"]'
                    );
                    if (shaEl) {
                        const href = shaEl.getAttribute('href') || '';
                        const m = href.match(/\/commit\/([0-9a-f]{7,40})/i);
                        commitSha = m ? m[1] : (shaEl.getAttribute('value') || shaEl.getAttribute('content') || shaEl.innerText || '').trim();
                    }
                }

                // Extract Committed File Path from URL or Breadcrumbs
                let filePath = '';
                const blobMatch = pathName.match(/^\/[^\/]+\/[^\/]+\/(?:blob|edit|raw)\/[^\/]+\/(.+)$/);
                if (blobMatch) {
                    filePath = decodeURIComponent(blobMatch[1]);
                } else {
                    const crumbs = document.querySelectorAll('[data-testid="breadcrumbs"] a, .final-path, .js-path-segment a');
                    if (crumbs.length > 0) {
                        const parts = [];
                        crumbs.forEach(c => {
                            const t = (c.innerText || '').trim();
                            if (t) parts.push(t);
                        });
                        filePath = parts.join('/');
                    }
                }

                // Extract Committed File Content (Read-Only Blob View)
                let committedLines = [];
                const reactLines = document.querySelectorAll('.react-code-text, .blob-code-inner, [data-testid="read-only-cursor-text-area"]');
                if (reactLines.length === 1 && reactLines[0].tagName === 'TEXTAREA') {
                    committedLines = (reactLines[0].value || '').split('\n');
                } else if (reactLines.length > 0) {
                    reactLines.forEach(l => committedLines.push(l.innerText || l.textContent || ''));
                }
                const committedContent = committedLines.join('\n');

                // Extract GitHub Actions Workflow Run Status, Steps & Error Logs
                let actionsRunStatus = '';
                let actionsConclusion = '';
                const statusBadge = document.querySelector('[data-testid="status-badge"], .State, [aria-label*="workflow run" i]');
                if (statusBadge) {
                    actionsRunStatus = (statusBadge.innerText || statusBadge.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim();
                }

                const stepSummaries = [];
                const failedSteps = [];
                document.querySelectorAll('.CheckStep, [data-testid="check-step"], details[data-conclusion]').forEach(step => {
                    const conclusion = step.getAttribute('data-conclusion') || '';
                    const nameEl = step.querySelector('.CheckStep-header-title, summary, span');
                    const stepName = nameEl ? (nameEl.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 100) : 'step';
                    if (conclusion || stepName) {
                        stepSummaries.push({ name: stepName, conclusion: conclusion });
                        if (conclusion === 'failure' || /fail|error/i.test(conclusion)) {
                            failedSteps.push(stepName);
                        }
                    }
                });

                const actionLogs = [];
                const errRegex = /(error:|exception|failed|failure|fatal|incompatible types|cannot find symbol|unresolved reference|execution failed for task|exit code [1-9])/i;
                document.querySelectorAll('.CheckStep-line, .CheckAnnotation, .js-check-step-line, [data-testid="log-line"], pre code').forEach(lineEl => {
                    const txt = (lineEl.innerText || '').replace(/\s+/g, ' ').trim();
                    if (txt && (errRegex.test(txt) || actionLogs.length < 40)) {
                        actionLogs.push(txt.slice(0, 350));
                    }
                });

                // Flash banner or commit error alert check
                let githubErrorBanner = '';
                const flashErr = document.querySelector('.flash-error, [role="alert"].flash-error, .js-flash-alert');
                if (flashErr) {
                    githubErrorBanner = (flashErr.innerText || '').replace(/\s+/g, ' ').trim();
                }

                return JSON.stringify({
                    url: url,
                    is_still_in_editor: isEditorOpen,
                    commit_sha: commitSha,
                    file_path: filePath,
                    committed_lines_count: committedLines.length,
                    committed_content_length: committedContent.length,
                    committed_content_preview: committedContent.slice(0, 500),
                    committed_content_full: committedContent.slice(0, 15000),
                    github_error_banner: githubErrorBanner,
                    actions_run_status: actionsRunStatus,
                    actions_steps: stepSummaries.slice(0, 30),
                    actions_failed_steps: failedSteps,
                    actions_logs: actionLogs.slice(0, 60)
                });
            })();
        """.trimIndent()

        val rawDomJson = evalJsSync(domVerifyJs)
        val resultObj = try {
            if (rawDomJson.startsWith("{")) JSONObject(rawDomJson) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }

        val domPath = resultObj.optString("file_path", "")
        val domContent = resultObj.optString("committed_content_full", "")
        val isEditor = resultObj.optBoolean("is_still_in_editor", false)
        val domSha = resultObj.optString("commit_sha", "")

        var verified = !isEditor && (domSha.isNotEmpty() || domContent.isNotEmpty())
        if (expectedPath.isNotEmpty() && !domPath.endsWith(expectedPath, ignoreCase = true)) {
            verified = false
        }
        if (expectedText.isNotEmpty() && !domContent.contains(expectedText)) {
            verified = false
        }

        resultObj.remove("committed_content_full")
        resultObj.put("status", if (verified) "verified_success" else "unverified_or_pending")
        resultObj.put("commit_verified", verified)

        // 2. Optional Direct GitHub REST API Verification if "repo" is passed in /verify request
        if (repoParam.isNotEmpty() && repoParam.contains("/")) {
            try {
                val apiVerification = verifyViaGitHubRestApi(repoParam, expectedPath, expectedText, tokenParam)
                resultObj.put("github_api_verification", apiVerification)
            } catch (e: Exception) {
                resultObj.put("github_api_error", e.message ?: "API error")
            }
        }

        return resultObj.toString()
    }

    private fun verifyViaGitHubRestApi(repo: String, path: String, expectedSubstring: String, token: String): JSONObject {
        val out = JSONObject()
        fun getJson(apiUrl: String): String {
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Gemini-Browser-Verifier")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (token.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        // Latest Commit SHA
        val commitsRaw = getJson("https://api.github.com/repos/$repo/commits?per_page=1")
        val commitsArr = JSONArray(commitsRaw)
        if (commitsArr.length() > 0) {
            val latest = commitsArr.getJSONObject(0)
            val sha = latest.optString("sha", "")
            val msg = latest.optJSONObject("commit")?.optString("message", "") ?: ""
            out.put("latest_commit_sha", sha)
            out.put("latest_commit_message", msg)

            if (path.isNotEmpty()) {
                val fileRaw = getJson("https://api.github.com/repos/$repo/contents/$path?ref=$sha")
                val fileObj = JSONObject(fileRaw)
                val b64Content = fileObj.optString("content", "").replace("\n", "").replace("\r", "")
                val decoded = String(Base64.decode(b64Content, Base64.DEFAULT), Charsets.UTF_8)
                val sha256 = MessageDigest.getInstance("SHA-256").digest(decoded.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }

                out.put("verified_file_path", fileObj.optString("path", path))
                out.put("verified_file_size", decoded.length)
                out.put("verified_file_sha256", sha256)
                if (expectedSubstring.isNotEmpty()) {
                    out.put("content_matches_expected", decoded.contains(expectedSubstring))
                }
            }
        }

        // Latest Workflow Run & Jobs
        val runsRaw = getJson("https://api.github.com/repos/$repo/actions/runs?per_page=1")
        val runsArr = JSONObject(runsRaw).optJSONArray("workflow_runs")
        if (runsArr != null && runsArr.length() > 0) {
            val run = runsArr.getJSONObject(0)
            val runId = run.optLong("id", 0L)
            out.put("workflow_run_id", runId)
            out.put("workflow_head_sha", run.optString("head_sha", ""))
            out.put("workflow_status", run.optString("status", ""))
            out.put("workflow_conclusion", run.optString("conclusion", ""))

            if (runId > 0L) {
                val jobsRaw = getJson("https://api.github.com/repos/$repo/actions/runs/$runId/jobs")
                val jobsArr = JSONObject(jobsRaw).optJSONArray("jobs") ?: JSONArray()
                val stepsReport = JSONArray()
                for (j in 0 until jobsArr.length()) {
                    val job = jobsArr.getJSONObject(j)
                    val steps = job.optJSONArray("steps") ?: JSONArray()
                    for (s in 0 until steps.length()) {
                        val st = steps.getJSONObject(s)
                        stepsReport.put(
                            JSONObject()
                                .put("name", st.optString("name", ""))
                                .put("status", st.optString("status", ""))
                                .put("conclusion", st.optString("conclusion", ""))
                        )
                    }
                }
                out.put("job_steps", stepsReport)
            }
        }
        return out
    }

    // ==================== MULTI-TAB ENGINE (UI + AI CAPABILITY) ====================

    private fun createNewTab(initialUrl: String = "https://gemini.google.com", switchToIt: Boolean = true): BrowserTab {
        val id = nextTabId.getAndIncrement()
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val newTab = BrowserTab(id = id, webView = wv, url = initialUrl, title = "Tab $id")
        configureWebViewForTab(newTab)
        tabs.add(newTab)
        tabViewsHolder.addView(wv)

        if (switchToIt || activeTab == null) {
            switchToTab(newTab)
        } else {
            refreshTabBarUi()
        }

        if (initialUrl.isNotBlank()) {
            wv.loadUrl(initialUrl)
        }
        appendLog("➕ Opened Tab #$id -> $initialUrl")
        broadcastSse("tab_created", JSONObject().put("tab_id", id).put("url", initialUrl).toString())
        return newTab
    }

    private fun switchToTab(targetTab: BrowserTab) {
        if (!tabs.contains(targetTab)) return
        activeTab = targetTab
        for (t in tabs) {
            t.webView.visibility = if (t.id == targetTab.id) View.VISIBLE else View.GONE
        }
        targetTab.webView.requestFocus()
        urlInput.setText(targetTab.url)
        desktopBtn.text = if (targetTab.desktopMode) "PC" else "MOB"
        desktopBtn.setBackgroundColor(if (targetTab.desktopMode) Color.parseColor("#1565C0") else Color.parseColor("#333333"))
        updateWebViewViewportBounds(targetTab)
        overlayView.clearHighlights()
        refreshTabBarUi()
        setConsoleStatus("🟢 127.0.0.1:$SERVER_PORT | Active Tab #${targetTab.id}", false)
        appendLog("🔄 Switched to Tab #${targetTab.id} (${targetTab.title.take(20)})")
        broadcastSse("tab_switched", JSONObject().put("tab_id", targetTab.id).put("url", targetTab.url).toString())
    }

    private fun closeTab(targetTab: BrowserTab) {
        if (tabs.size <= 1) {
            targetTab.webView.loadUrl("https://gemini.google.com")
            Toast.makeText(this, "Reset Tab #${targetTab.id}", Toast.LENGTH_SHORT).show()
            return
        }

        val idx = tabs.indexOf(targetTab)
        val wasActive = (activeTab?.id == targetTab.id)
        tabs.remove(targetTab)
        tabViewsHolder.removeView(targetTab.webView)
        try {
            targetTab.webView.stopLoading()
            targetTab.webView.destroy()
        } catch (_: Exception) {}

        appendLog("✕ Closed Tab #${targetTab.id}")
        broadcastSse("tab_closed", targetTab.id.toString())

        if (wasActive && tabs.isNotEmpty()) {
            val nextIdx = idx.coerceIn(0, tabs.size - 1)
            switchToTab(tabs[nextIdx])
        } else {
            refreshTabBarUi()
        }
    }

    private fun findTabByQuery(tabId: Int = -1, index: Int = -1, query: String = ""): BrowserTab? {
        if (tabId > 0) {
            tabs.find { it.id == tabId }?.let { return it }
        }
        if (index in 0 until tabs.size) {
            return tabs[index]
        }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase(Locale.ROOT)
            val numMatch = Regex("(?:tab\\s*#?|#)(\\d+)").find(q)
            if (numMatch != null) {
                val parsedId = numMatch.groupValues[1].toIntOrNull() ?: -1
                tabs.find { it.id == parsedId }?.let { return it }
                if (parsedId in 1..tabs.size) return tabs[parsedId - 1]
            }
            tabs.find {
                it.title.lowercase(Locale.ROOT).contains(q) || it.url.lowercase(Locale.ROOT).contains(q)
            }?.let { return it }
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
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(28)
                    ).apply { marginEnd = dp(5) }
                    setOnClickListener { switchToTab(t) }
                }

                val cleanTitle = t.title.ifEmpty { t.url.removePrefix("https://").removePrefix("http://") }.take(16)
                val titleTv = TextView(this).apply {
                    text = "${t.id}: $cleanTitle"
                    setTextColor(Color.WHITE)
                    textSize = 10.5f
                    typeface = if (isCurrent) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setSingleLine(true)
                    setPadding(0, 0, dp(6), 0)
                }

                val closeBtn = TextView(this).apply {
                    text = "✕"
                    setTextColor(Color.parseColor(if (isCurrent) "#FFCDD2" else "#AAAAAA"))
                    textSize = 10.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(5), dp(1), dp(5), dp(1))
                    setOnClickListener { closeTab(t) }
                }

                tabBox.addView(titleTv)
                tabBox.addView(closeBtn)
                tabBarContainer.addView(tabBox)
            }

            val addTabBtn = Button(this).apply {
                text = "+ TAB"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#00897B"))
                minWidth = 0; minimumWidth = 0
                minHeight = 0; minimumHeight = 0
                setPadding(dp(10), dp(2), dp(10), dp(2))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28))
                setOnClickListener {
                    createNewTab("https://gemini.google.com", switchToIt = true)
                }
            }
            tabBarContainer.addView(addTabBtn)
        }
    }

    // ==================== DOWNLOAD ENGINE ====================

    private fun startBrowserDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            if (url.startsWith("blob:")) {
                val safeBlobUrl = url.replace("'", "\\'")
                val js = """
                    (function() {
                        fetch('$safeBlobUrl')
                            .then(r => r.blob())
                            .then(blob => {
                                const reader = new FileReader();
                                reader.onloadend = function() {
                                    const b64 = (reader.result || '').split(',')[1] || '';
                                    const ext = (blob.type && blob.type.split('/')[1]) ? '.' + blob.type.split('/')[1].split(';')[0] : '.bin';
                                    const fname = 'gemini_download_' + Date.now() + ext;
                                    if (window.AIAgentBridge && window.AIAgentBridge.saveBase64Download) {
                                        window.AIAgentBridge.saveBase64Download(b64, fname, blob.type || 'application/octet-stream');
                                    }
                                };
                                reader.readAsDataURL(blob);
                            })
                            .catch(e => console.error('Blob download error:', e));
                    })();
                """.trimIndent()
                mainHandler.post { webView.evaluateJavascript(js, null) }
                return
            }

            if (url.startsWith("data:")) {
                val commaIdx = url.indexOf(',')
                if (commaIdx != -1) {
                    val meta = url.substring(5, commaIdx)
                    val dataPart = url.substring(commaIdx + 1)
                    val mime = meta.substringBefore(";").ifEmpty { "application/octet-stream" }
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { ".$it" } ?: ".bin"
                    val fileName = "gemini_data_${System.currentTimeMillis()}$ext"
                    val bytes = if (meta.contains("base64", ignoreCase = true)) {
                        Base64.decode(dataPart, Base64.DEFAULT)
                    } else {
                        Uri.decode(dataPart).toByteArray(Charsets.UTF_8)
                    }
                    saveBytesToDownloads(bytes, fileName)
                }
                return
            }

            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    addRequestHeader("Cookie", cookies)
                }
                addRequestHeader("User-Agent", userAgent ?: mobileUA)
                setDescription("Downloading via Gemini Browser...")
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            appendLog("📥 Downloading: $fileName")
            setConsoleStatus("🟢 Downloading: $fileName", false)
            Toast.makeText(this, "📥 Downloading: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            appendLog("Download Error: ${e.message}")
            Toast.makeText(this, "Download Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBytesToDownloads(bytes: ByteArray, fileName: String) {
        serverPool.execute {
            try {
                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dlDir.exists()) dlDir.mkdirs()
                val outFile = File(dlDir, fileName)
                FileOutputStream(outFile).use { it.write(bytes) }
                appendLog("✅ Saved to Downloads: ${outFile.name} (${bytes.size} bytes)")
                setConsoleStatus("🟢 Saved: ${outFile.name}", false)
                mainHandler.post {
                    Toast.makeText(this@MainActivity, "✅ Downloaded: ${outFile.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                appendLog("Save Download Error: ${e.message}")
            }
        }
    }

    // ==================== USERSCRIPT MANAGER ====================

    private fun getSavedUserScripts(): JSONArray {
        val raw = prefs.getString("userscripts_list", "[]") ?: "[]"
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun saveUserScripts(arr: JSONArray) {
        prefs.edit().putString("userscripts_list", arr.toString()).apply()
    }

    private fun showUserScriptDialog(editIndex: Int = -1) {
        val scripts = getSavedUserScripts()
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.parseColor("#181818"))
        }

        val titleTv = TextView(this).apply {
            text = "⚡ UserScript Manager (Gemini Browser)"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        container.addView(titleTv)

        var dialogRef: AlertDialog? = null

        for (i in 0 until scripts.length()) {
            val obj = scripts.getJSONObject(i)
            val sName = obj.optString("name", "Script #${i + 1}")
            val sEnabled = obj.optBoolean("enabled", true)
            val sCode = obj.optString("code", "")

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#262626"))
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }

            val nameLabel = TextView(this).apply {
                text = (if (sEnabled) "🟢 " else "⚪ ") + sName
                setTextColor(Color.WHITE)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnToggle = Button(this).apply {
                text = if (sEnabled) "ON" else "OFF"
                textSize = 9f
                setTextColor(Color.WHITE)
                setBackgroundColor(if (sEnabled) Color.parseColor("#2E7D32") else Color.parseColor("#555555"))
                minWidth = 0; minimumWidth = 0
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(28)).apply { marginEnd = dp(4) }
                setOnClickListener {
                    obj.put("enabled", !sEnabled)
                    saveUserScripts(scripts)
                    dialogRef?.dismiss()
                    showUserScriptDialog()
                }
            }

            val btnRun = Button(this).apply {
                text = "RUN"
                textSize = 9f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1565C0"))
                minWidth = 0; minimumWidth = 0
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(28)).apply { marginEnd = dp(4) }
                setOnClickListener {
                    runSingleUserScriptOnPage(webView, sName, sCode)
                    Toast.makeText(this@MainActivity, "Executed: $sName", Toast.LENGTH_SHORT).show()
                }
            }

            val btnEdit = Button(this).apply {
                text = "EDIT"
                textSize = 9f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#6A1B9A"))
                minWidth = 0; minimumWidth = 0
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(28)).apply { marginEnd = dp(4) }
                setOnClickListener {
                    dialogRef?.dismiss()
                    showUserScriptDialog(i)
                }
            }

            val btnDel = Button(this).apply {
                text = "✕"
                textSize = 9f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#C62828"))
                minWidth = 0; minimumWidth = 0
                setPadding(dp(4), dp(2), dp(4), dp(2))
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(28))
                setOnClickListener {
                    val nextArr = JSONArray()
                    for (idx in 0 until scripts.length()) {
                        if (idx != i) nextArr.put(scripts.getJSONObject(idx))
                    }
                    saveUserScripts(nextArr)
                    dialogRef?.dismiss()
                    showUserScriptDialog()
                }
            }

            row.addView(nameLabel)
            row.addView(btnToggle)
            row.addView(btnRun)
            row.addView(btnEdit)
            row.addView(btnDel)
            container.addView(row)
        }

        val editingObj = if (editIndex in 0 until scripts.length()) scripts.getJSONObject(editIndex) else null

        val nameInput = EditText(this).apply {
            hint = "Script Name (e.g. Auto Fill / Custom UI)"
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            textSize = 12f
            setSingleLine(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setText(editingObj?.optString("name", "") ?: "")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8); bottomMargin = dp(6) }
        }

        val codeInput = EditText(this).apply {
            hint = "// Paste JavaScript or Tampermonkey UserScript here..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.parseColor("#80CBC4"))
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            minLines = 7
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setText(editingObj?.optString("code", "") ?: "")
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }

        val helperRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val btnPasteClip = Button(this).apply {
            text = "📋 Paste Clipboard"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#37474F"))
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(4) }
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipTxt = cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
                if (clipTxt.isNotEmpty()) {
                    codeInput.setText(clipTxt)
                    if (nameInput.text.toString().trim().isEmpty()) {
                        val nameMatch = Regex("//\\s*@name\\s+(.+)").find(clipTxt)
                        if (nameMatch != null) {
                            nameInput.setText(nameMatch.groupValues[1].trim())
                        }
                    }
                    Toast.makeText(this@MainActivity, "Pasted (${clipTxt.length} chars)", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val btnClearCode = Button(this).apply {
            text = "🗑️ Clear Box"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#455A64"))
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
            setOnClickListener { codeInput.setText("") }
        }

        helperRow.addView(btnPasteClip)
        helperRow.addView(btnClearCode)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val btnRunNow = Button(this).apply {
            text = "▶ Run Once Now"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0277BD"))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            setOnClickListener {
                val jsCode = codeInput.text.toString()
                if (jsCode.trim().isNotEmpty()) {
                    runSingleUserScriptOnPage(webView, nameInput.text.toString().ifEmpty { "TempScript" }, jsCode)
                    Toast.makeText(this@MainActivity, "Script Executed!", Toast.LENGTH_SHORT).show()
                    dialogRef?.dismiss()
                }
            }
        }

        val btnSaveAuto = Button(this).apply {
            text = if (editIndex >= 0) "💾 Update Script" else "💾 Save & Auto-Run"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E7D32"))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            setOnClickListener {
                val jsCode = codeInput.text.toString().trim()
                if (jsCode.isEmpty()) return@setOnClickListener
                var sName = nameInput.text.toString().trim()
                if (sName.isEmpty()) {
                    val m = Regex("//\\s*@name\\s+(.+)").find(jsCode)
                    sName = m?.groupValues?.get(1)?.trim() ?: "UserScript #${scripts.length() + 1}"
                }
                val entry = JSONObject().put("name", sName).put("code", jsCode).put("enabled", true)
                if (editIndex in 0 until scripts.length()) scripts.put(editIndex, entry) else scripts.put(entry)
                saveUserScripts(scripts)
                runSingleUserScriptOnPage(webView, sName, jsCode)
                Toast.makeText(this@MainActivity, "Saved & Executed: $sName", Toast.LENGTH_SHORT).show()
                dialogRef?.dismiss()
            }
        }

        actionRow.addView(btnRunNow)
        actionRow.addView(btnSaveAuto)

        container.addView(nameInput)
        container.addView(codeInput)
        container.addView(helperRow)
        container.addView(actionRow)
        scroll.addView(container)

        dialogRef = AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()
        dialogRef.show()
    }

    private fun scriptMatchesUrl(code: String, url: String): Boolean {
        val matches = Regex("//\\s*@(?:match|include)\\s+(\\S+)").findAll(code).toList()
        if (matches.isEmpty()) return true
        for (m in matches) {
            val pattern = m.groupValues[1].trim()
            if (pattern == "*" || pattern == "*://*/*") return true
            val regexPattern = "^" + Regex.escape(pattern).replace("\\*", ".*") + "$"
            if (Regex(regexPattern, RegexOption.IGNORE_CASE).matches(url)) return true
        }
        return false
    }

    private fun runSingleUserScriptOnPage(targetWv: WebView, name: String, rawCode: String) {
        val b64 = Base64.encodeToString(rawCode.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val safeName = name.replace("'", "\\'")
        val js = """
            (function() {
                try {
                    if (typeof window.GM_addStyle === 'undefined') {
                        window.GM_addStyle = function(css) {
                            const s = document.createElement('style');
                            s.textContent = css;
                            (document.head || document.documentElement).appendChild(s);
                            return s;
                        };
                    }
                    const bin = atob('$b64');
                    const bytes = new Uint8Array(bin.length);
                    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                    const decodedCode = new TextDecoder('utf-8').decode(bytes);
                    new Function(decodedCode)();
                } catch (e) {
                    console.error('[UserScript Error] $safeName:', e);
                }
            })();
        """.trimIndent()
        mainHandler.post {
            targetWv.evaluateJavascript(js, null)
            appendLog("UserScript ran: $name")
        }
    }

    private fun injectSavedUserScripts(targetWv: WebView, url: String) {
        val scripts = getSavedUserScripts()
        for (i in 0 until scripts.length()) {
            val obj = scripts.optJSONObject(i) ?: continue
            if (obj.optBoolean("enabled", true)) {
                val name = obj.optString("name", "Script #$i")
                val code = obj.optString("code", "")
                if (code.isNotEmpty() && scriptMatchesUrl(code, url)) {
                    runSingleUserScriptOnPage(targetWv, name, code)
                }
            }
        }
    }

    // ==================== VIEWPORT & WEBVIEW CONFIG ====================

    private fun updateWebViewViewportBounds(targetTab: BrowserTab? = activeTab) {
        val tab = targetTab ?: return
        val wv = tab.webView
        val cw = webContainer.width
        val ch = webContainer.height
        if (cw <= 0 || ch <= 0) return

        if (tab.desktopMode) {
            val density = resources.displayMetrics.density
            val targetCssWidth = 1280f
            val targetPxW = (targetCssWidth * density).toInt()
            val scale = cw.toFloat() / targetPxW.toFloat()
            val targetPxH = (ch.toFloat() / scale).toInt()

            wv.pivotX = 0f
            wv.pivotY = 0f
            wv.scaleX = scale
            wv.scaleY = scale

            val lp = wv.layoutParams as FrameLayout.LayoutParams
            if (lp.width != targetPxW || lp.height != targetPxH) {
                lp.width = targetPxW
                lp.height = targetPxH
                wv.layoutParams = lp
            }
        } else {
            wv.pivotX = 0f
            wv.pivotY = 0f
            wv.scaleX = 1f
            wv.scaleY = 1f

            val lp = wv.layoutParams as FrameLayout.LayoutParams
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                wv.layoutParams = lp
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
            tab.webView.settings.useWideViewPort = true
            tab.webView.settings.loadWithOverviewMode = true
            updateWebViewViewportBounds(tab)
            tab.webView.reload()
        }
        appendLog("Tab #${tab.id} Desktop Mode -> $enable")
    }

    private fun makeSmallButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            minWidth = 0; minimumWidth = 0
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36)).apply {
                marginEnd = dp(3)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun loadFromAddressBar() {
        var raw = urlInput.text.toString().trim()
        if (raw.isEmpty()) return
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = if (raw.contains(".") && !raw.contains(" ")) "https://$raw"
            else "https://www.google.com/search?q=${Uri.encode(raw)}"
        }
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(urlInput.windowToken, 0)
        } catch (_: Exception) {}
        webView.loadUrl(raw)
    }

    private fun configureWebViewForTab(tab: BrowserTab) {
        val wv = tab.webView
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.userAgentString = if (tab.desktopMode) desktopUA else mobileUA

        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            startBrowserDownload(url, userAgent, contentDisposition, mimetype)
        }

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onIdle(url: String) {
                tab.isNetworkIdle = true
                broadcastSse("network_idle", url)
                if (serverStarted.get() && activeTab?.id == tab.id) {
                    setConsoleStatus("🟢 ONLINE 127.0.0.1:$SERVER_PORT | Tab #${tab.id} Idle", false)
                }
            }

            @JavascriptInterface
            fun onAutoAllowed(toolInfo: String) {
                appendLog("⚡ [Tab #${tab.id}] Auto-Clicked Latest Allow: $toolInfo")
                setConsoleStatus("🟢 [Tab #${tab.id}] Auto-Allowed MCP Tool!", false)
                broadcastSse("auto_allow_clicked", toolInfo)
            }

            @JavascriptInterface
            fun saveBase64Download(base64Data: String, fileName: String, mimeType: String) {
                try {
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    saveBytesToDownloads(bytes, fileName)
                } catch (e: Exception) {
                    appendLog("Blob decode error: ${e.message}")
                }
            }
        }, "AIAgentBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                tab.isPageLoading = true
                tab.isNetworkIdle = false
                tab.url = url ?: ""
                if (activeTab?.id == tab.id) {
                    urlInput.setText(tab.url)
                    if (serverStarted.get()) {
                        setConsoleStatus("🟢 127.0.0.1:$SERVER_PORT | Tab #${tab.id} Loading...", false)
                    }
                }
                injectStealthViewportAndTracker(tab)
                refreshTabBarUi()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tab.isPageLoading = false
                tab.url = url ?: ""
                tab.title = view?.title ?: "Tab ${tab.id}"
                if (activeTab?.id == tab.id) {
                    urlInput.setText(tab.url)
                    if (serverStarted.get()) {
                        setConsoleStatus("🟢 ONLINE 127.0.0.1:$SERVER_PORT | Tab #${tab.id} Ready", false)
                    }
                }
                CookieManager.getInstance().flush()
                injectStealthViewportAndTracker(tab)
                injectSavedUserScripts(wv, tab.url)
                refreshTabBarUi()
                broadcastSse("page_loaded", tab.url)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val createdTab = createNewTab("", switchToIt = true)
                transport.webView = createdTab.webView
                resultMsg.sendToTarget()
                return true
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank()) {
                    tab.title = title
                    refreshTabBarUi()
                }
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                appendLog("Auto-Accepted Alert: $message")
                result?.confirm()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                appendLog("Auto-Confirmed Dialog: $message")
                result?.confirm()
                return true
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                appendLog("Auto-Prompt Dialog: $message")
                result?.confirm(defaultValue ?: "")
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val path = queuedFilePath
                if (!path.isNullOrEmpty()) {
                    val f = File(path)
                    if (f.exists()) {
                        appendLog("Auto-Uploaded File: ${f.name}")
                        filePathCallback?.onReceiveValue(arrayOf(Uri.fromFile(f)))
                        queuedFilePath = null
                        return true
                    }
                }
                filePathCallback?.onReceiveValue(null)
                return true
            }
        }
    }

    private fun injectStealthViewportAndTracker(tab: BrowserTab) {
        val isPc = if (tab.desktopMode) "true" else "false"
        val js = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'webdriver', { get: () => false, configurable: true });
                    window.chrome = window.chrome || { runtime: {}, app: {}, csi: function(){}, loadTimes: function(){} };
                    Object.defineProperty(navigator, 'vendor', { get: () => 'Google Inc.', configurable: true });

                    const brandsList = [
                        { brand: 'Not/A)Brand', version: '8' },
                        { brand: 'Chromium', version: '126' },
                        { brand: 'Google Chrome', version: '126' }
                    ];
                    Object.defineProperty(navigator, 'userAgentData', {
                        get: () => ({
                            brands: brandsList,
                            mobile: !$isPc,
                            platform: $isPc ? 'Linux' : 'Android',
                            getHighEntropyValues: async () => ({
                                architecture: $isPc ? 'x86' : 'arm',
                                bitness: '64',
                                brands: brandsList,
                                mobile: !$isPc,
                                model: $isPc ? '' : 'Pixel 8',
                                platform: $isPc ? 'Linux' : 'Android',
                                platformVersion: '14.0.0',
                                uaFullVersion: '126.0.6478.122'
                            })
                        }),
                        configurable: true
                    });
                } catch (e) {}

                // React / Vue / Angular / GitHub State-Safe Value Setter
                window.__aiSetReactSafeValue = function(el, newValue, insertedDelta, isAppend) {
                    if (!el) return 'no_element';
                    try { el.focus(); } catch (e) {}

                    const isEditable = el.isContentEditable || el.getAttribute('contenteditable') === 'true' || el.classList.contains('inputarea');
                    if (isEditable) {
                        if (!isAppend) {
                            const sel = window.getSelection();
                            const range = document.createRange();
                            range.selectNodeContents(el);
                            sel.removeAllRanges();
                            sel.addRange(range);
                            document.execCommand('delete', false, null);
                            if ((el.innerText || '').trim().length > 0) el.innerText = '';
                        }
                        if (insertedDelta && insertedDelta.length > 0) {
                            if (!document.execCommand('insertText', false, insertedDelta)) {
                                el.innerText = newValue;
                            }
                        }
                        el.dispatchEvent(new InputEvent('input', { data: insertedDelta || newValue, inputType: 'insertText', bubbles: true, cancelable: false, composed: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                        return 'contenteditable';
                    }

                    if ('value' in el) {
                        const prevVal = el.value || '';
                        const targetVal = isAppend ? (prevVal + (insertedDelta || '')) : newValue;

                        const proto = el.tagName === 'TEXTAREA'
                            ? window.HTMLTextAreaElement.prototype
                            : window.HTMLInputElement.prototype;
                        const desc = Object.getOwnPropertyDescriptor(proto, 'value');

                        if (desc && desc.set) {
                            desc.set.call(el, targetVal);
                        } else {
                            el.value = targetVal;
                        }

                        if (el._valueTracker) {
                            try { el._valueTracker.setValue(prevVal); } catch (e) {}
                        }

                        try {
                            if (typeof el.setSelectionRange === 'function') {
                                el.setSelectionRange(targetVal.length, targetVal.length);
                            }
                        } catch (e) {}

                        el.dispatchEvent(new InputEvent('beforeinput', { data: insertedDelta || targetVal, inputType: 'insertText', bubbles: true, cancelable: true, composed: true }));
                        el.dispatchEvent(new InputEvent('input', { data: insertedDelta || targetVal, inputType: 'insertText', bubbles: true, cancelable: false, composed: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                        return 'react_safe_input';
                    }
                    return 'unknown';
                };

                window.__aiCommitActiveInput = function() {
                    try {
                        const active = document.activeElement;
                        if (active && ('value' in active || active.isContentEditable)) {
                            active.dispatchEvent(new Event('input', { bubbles: true }));
                            active.dispatchEvent(new Event('change', { bubbles: true }));
                        }
                    } catch (e) {}
                };

                window.__aiOpenSelectModal = function(selectEl) {
                    if (!selectEl || selectEl.tagName !== 'SELECT') return false;
                    const existing = document.getElementById('__ai_select_modal');
                    if (existing) existing.remove();

                    const modal = document.createElement('div');
                    modal.id = '__ai_select_modal';
                    modal.style.cssText = 'position:fixed;top:0;left:0;width:100vw;height:100vh;background:rgba(0,0,0,0.65);z-index:2147483647;display:flex;align-items:center;justify-content:center;padding:16px;box-sizing:border-box;font-family:sans-serif;';

                    const card = document.createElement('div');
                    card.style.cssText = 'background:#2b2b2b;color:#fff;width:100%;max-width:420px;max-height:82vh;border-radius:12px;overflow-y:auto;box-shadow:0 10px 30px rgba(0,0,0,0.8);border:1px solid #444;';

                    for (let i = 0; i < selectEl.options.length; i++) {
                        const opt = selectEl.options[i];
                        const isSelected = (selectEl.selectedIndex === i);
                        const item = document.createElement('div');
                        item.setAttribute('role', 'option');
                        item.setAttribute('data-ai-select-index', i);
                        item.setAttribute('data-ai-option-value', opt.value || '');
                        item.style.cssText = 'padding:14px 18px;border-bottom:1px solid #3d3d3d;display:flex;align-items:center;justify-content:space-between;cursor:pointer;font-size:15px;' + (isSelected ? 'background:#1e3a2f;color:#00e676;font-weight:bold;' : '');

                        const spanTxt = document.createElement('span');
                        spanTxt.textContent = (opt.text || opt.label || opt.value || '').trim();

                        const radio = document.createElement('span');
                        radio.textContent = isSelected ? '◉' : '○';
                        radio.style.cssText = 'font-size:18px;margin-left:12px;color:' + (isSelected ? '#4dd0e1' : '#aaa') + ';';

                        item.appendChild(spanTxt);
                        item.appendChild(radio);

                        item.addEventListener('click', function(ev) {
                            ev.stopPropagation();
                            selectEl.selectedIndex = i;
                            selectEl.value = opt.value;
                            selectEl.dispatchEvent(new Event('focus', { bubbles: true }));
                            selectEl.dispatchEvent(new Event('input', { bubbles: true }));
                            selectEl.dispatchEvent(new Event('change', { bubbles: true }));
                            if (window.jQuery) { try { window.jQuery(selectEl).trigger('change'); } catch (e) {} }
                            modal.remove();
                        });
                        card.appendChild(item);
                    }

                    modal.addEventListener('click', function(ev) {
                        if (ev.target === modal) modal.remove();
                    });
                    modal.appendChild(card);
                    document.body.appendChild(modal);
                    return true;
                };

                if (!window.__aiSelectHooked) {
                    window.__aiSelectHooked = true;
                    document.addEventListener('mousedown', function(e) {
                        const sel = e.target && e.target.closest ? e.target.closest('select') : null;
                        if (sel && !sel.multiple) {
                            e.preventDefault();
                            e.stopPropagation();
                            window.__aiOpenSelectModal(sel);
                        }
                    }, true);
                }

                if (!window.__aiDownloadHooked) {
                    window.__aiDownloadHooked = true;
                    document.addEventListener('click', function(e) {
                        const a = e.target && e.target.closest ? e.target.closest('a[download], a[href^="blob:"], a[href^="data:"]') : null;
                        if (a && a.href && (a.href.startsWith('blob:') || a.href.startsWith('data:'))) {
                            const fname = a.getAttribute('download') || ('gemini_file_' + Date.now());
                            if (a.href.startsWith('blob:')) {
                                e.preventDefault();
                                fetch(a.href).then(r => r.blob()).then(blob => {
                                    const reader = new FileReader();
                                    reader.onloadend = function() {
                                        const b64 = (reader.result || '').split(',')[1] || '';
                                        if (window.AIAgentBridge && window.AIAgentBridge.saveBase64Download) {
                                            window.AIAgentBridge.saveBase64Download(b64, fname, blob.type || 'application/octet-stream');
                                        }
                                    };
                                    reader.readAsDataURL(blob);
                                });
                            }
                        }
                    }, true);
                }

                if (!window.__geminiAllowClickerInstalled) {
                    window.__geminiAllowClickerInstalled = true;

                    function findAndClickLatestAllow() {
                        try {
                            const candidates = [];
                            function collectButtons(root) {
                                if (!root) return;
                                const els = root.querySelectorAll('button, [role="button"]');
                                for (let i = 0; i < els.length; i++) {
                                    const el = els[i];
                                    const txt = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
                                    const aria = (el.getAttribute('aria-label') || '').trim();
                                    if (txt.toLowerCase() === 'allow' || aria.toLowerCase() === 'allow') {
                                        const r = el.getBoundingClientRect();
                                        const s = window.getComputedStyle(el);
                                        if (r.width > 10 && r.height > 10 &&
                                            s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0' &&
                                            !el.disabled && el.getAttribute('aria-disabled') !== 'true') {
                                            candidates.push(el);
                                        }
                                    }
                                }
                                const allNodes = root.querySelectorAll('*');
                                for (let i = 0; i < allNodes.length; i++) {
                                    if (allNodes[i].shadowRoot) collectButtons(allNodes[i].shadowRoot);
                                }
                            }

                            collectButtons(document);

                            if (candidates.length > 0) {
                                const latestBtn = candidates[candidates.length - 1];
                                const now = Date.now();
                                const lastClickedTime = parseInt(latestBtn.getAttribute('data-ai-allow-ts') || '0', 10);

                                if (now - lastClickedTime > 1200) {
                                    latestBtn.setAttribute('data-ai-allow-ts', String(now));
                                    const evOpts = { bubbles: true, cancelable: true, composed: true, view: window };
                                    latestBtn.dispatchEvent(new PointerEvent('pointerdown', evOpts));
                                    latestBtn.dispatchEvent(new MouseEvent('mousedown', evOpts));
                                    latestBtn.dispatchEvent(new PointerEvent('pointerup', evOpts));
                                    latestBtn.dispatchEvent(new MouseEvent('mouseup', evOpts));
                                    latestBtn.click();

                                    if (window.AIAgentBridge && window.AIAgentBridge.onAutoAllowed) {
                                        window.AIAgentBridge.onAutoAllowed('Allow button #' + candidates.length);
                                    }
                                }
                            }
                        } catch (err) {}
                    }

                    const allowObs = new MutationObserver(function() { findAndClickLatestAllow(); });
                    if (document.documentElement) {
                        allowObs.observe(document.documentElement, { childList: true, subtree: true });
                    }
                    setInterval(findAndClickLatestAllow, 350);
                    findAndClickLatestAllow();
                }

                if (window.__aiTrackerInstalled) return;
                window.__aiTrackerInstalled = true;
                window.__aiPendingReq = 0;
                let timer = null;

                function checkIdle() {
                    clearTimeout(timer);
                    if (window.__aiPendingReq <= 0) {
                        timer = setTimeout(function() {
                            if (window.__aiPendingReq <= 0 && window.AIAgentBridge) {
                                window.AIAgentBridge.onIdle(window.location.href);
                            }
                        }, 500);
                    }
                }

                const origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        window.__aiPendingReq++;
                        return origFetch.apply(this, arguments).finally(function() {
                            window.__aiPendingReq = Math.max(0, window.__aiPendingReq - 1);
                            checkIdle();
                        });
                    };
                }

                const origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function() {
                    window.__aiPendingReq++;
                    this.addEventListener('loadend', function() {
                        window.__aiPendingReq = Math.max(0, window.__aiPendingReq - 1);
                        checkIdle();
                    });
                    return origSend.apply(this, arguments);
                };

                if (document.body) {
                    new MutationObserver(function() { checkIdle(); })
                        .observe(document.body, { childList: true, subtree: true });
                }
                checkIdle();
            })();
        """.trimIndent()
        tab.webView.evaluateJavascript(js, null)
    }

    private fun ensureServerRunning() {
        if (serverStarted.get() && serverSocket != null && !serverSocket!!.isClosed) {
            setConsoleStatus("🟢 ONLINE 127.0.0.1:$SERVER_PORT | AI: Ready", false)
            return
        }

        serverPool.execute {
            try { serverSocket?.close() } catch (_: Exception) {}

            try {
                val sock = ServerSocket()
                sock.reuseAddress = true
                sock.bind(InetSocketAddress("0.0.0.0", SERVER_PORT), 50)
                serverSocket = sock
                serverStarted.set(true)

                setConsoleStatus("🟢 ONLINE 127.0.0.1:$SERVER_PORT | AI: Ready", false)
                appendLog("Server listening on 0.0.0.0:$SERVER_PORT (Termux Ready)")

                while (serverStarted.get() && !sock.isClosed) {
                    val client = sock.accept()
                    serverPool.execute {
                        val inst = activeInstance ?: this@MainActivity
                        inst.handleClientSocket(client)
                    }
                }
            } catch (e: Exception) {
                serverStarted.set(false)
                setConsoleStatus("🔴 SERVER ERROR $SERVER_PORT: ${e.message}", true)
                appendLog("Server error: ${e.message}")
            }
        }
    }

    private fun parseQueryParams(rawPath: String): JSONObject {
        val obj = JSONObject()
        val qStr = rawPath.substringAfter("?", "")
        if (qStr.isEmpty()) return obj
        for (pair in qStr.split("&")) {
            val parts = pair.split("=", limit = 2)
            if (parts.isNotEmpty() && parts[0].isNotEmpty()) {
                val k = URLDecoder.decode(parts[0], "UTF-8")
                val v = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                val intVal = v.toIntOrNull()
                if (intVal != null) obj.put(k, intVal) else obj.put(k, v)
            }
        }
        return obj
    }

    private fun handleClientSocket(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val inp = socket.getInputStream()
            val out = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(inp, Charsets.UTF_8))

            val reqLine = reader.readLine() ?: return
            val parts = reqLine.split(" ")
            val method = if (parts.isNotEmpty()) parts[0].uppercase(Locale.ROOT) else "GET"
            val rawPath = if (parts.size > 1) parts[1] else "/"
            val path = rawPath.substringBefore("?")

            var contentLength = 0
            var headerLine: String?
            while (reader.readLine().also { headerLine = it } != null && headerLine!!.isNotEmpty()) {
                val low = headerLine!!.lowercase(Locale.ROOT)
                if (low.startsWith("content-length:")) {
                    contentLength = low.substringAfter(":").trim().toIntOrNull() ?: 0
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

            val jsonBody = if (bodyStr.trim().isNotEmpty()) {
                try { JSONObject(bodyStr) } catch (_: Exception) { parseQueryParams(rawPath) }
            } else {
                parseQueryParams(rawPath)
            }

            if (path == "/events") {
                val pw = PrintWriter(OutputStreamWriter(out, Charsets.UTF_8), true)
                pw.print("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n")
                pw.flush()
                sseClients.add(pw)
                try {
                    while (!socket.isClosed && !pw.checkError()) {
                        Thread.sleep(5000)
                        pw.print("data: {\"event\":\"ping\"}\n\n")
                        pw.flush()
                    }
                } finally {
                    sseClients.remove(pw)
                }
                return
            }

            val responseJson = executeCommand(method, path, jsonBody)
            val bytes = responseJson.toByteArray(Charsets.UTF_8)
            val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(headers.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        } catch (e: Exception) {
            appendLog("Client error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun buildTabsJsonArray(): JSONArray {
        val arr = JSONArray()
        val curId = activeTab?.id ?: -1
        for (i in 0 until tabs.size) {
            val t = tabs[i]
            arr.put(
                JSONObject()
                    .put("tab_id", t.id)
                    .put("index", i)
                    .put("virtual_dom_id", 9000 + t.id)
                    .put("active", t.id == curId)
                    .put("url", t.url)
                    .put("title", t.title)
                    .put("loading", t.isPageLoading)
                    .put("desktop_mode", t.desktopMode)
            )
        }
        return arr
    }

    private fun handleTabsApiSync(defaultAction: String, body: JSONObject?): String {
        val action = body?.optString("action", defaultAction)?.lowercase(Locale.ROOT) ?: defaultAction
        return when (action) {
            "list" -> {
                JSONObject()
                    .put("status", "ok")
                    .put("active_tab_id", activeTab?.id ?: -1)
                    .put("tabs_count", tabs.size)
                    .put("tabs", buildTabsJsonArray())
                    .toString()
            }
            "new", "open", "create" -> {
                val url = body?.optString("url", "https://gemini.google.com") ?: "https://gemini.google.com"
                val switchToIt = body?.optBoolean("switch", true) ?: true
                var createdTab: BrowserTab? = null
                runOnMainSync {
                    createdTab = createNewTab(url, switchToIt)
                }
                JSONObject()
                    .put("status", "tab_created")
                    .put("tab_id", createdTab?.id ?: -1)
                    .put("active_tab_id", activeTab?.id ?: -1)
                    .put("url", createdTab?.url ?: url)
                    .put("tabs_count", tabs.size)
                    .put("tabs", buildTabsJsonArray())
                    .toString()
            }
            "switch", "select", "activate" -> {
                val targetId = body?.optInt("tab_id", body.optInt("id", -1)) ?: -1
                val idx = body?.optInt("index", -1) ?: -1
                val query = body?.optString("title", body.optString("query", body.optString("url", ""))) ?: ""
                val targetTab = findTabByQuery(targetId, idx, query)
                if (targetTab != null) {
                    runOnMainSync { switchToTab(targetTab) }
                    JSONObject()
                        .put("status", "switched")
                        .put("active_tab_id", targetTab.id)
                        .put("title", targetTab.title)
                        .put("url", targetTab.url)
                        .put("tabs", buildTabsJsonArray())
                        .toString()
                } else {
                    JSONObject()
                        .put("status", "error")
                        .put("message", "Tab not found")
                        .put("available_tabs", buildTabsJsonArray())
                        .toString()
                }
            }
            "next" -> {
                if (tabs.size > 1 && activeTab != null) {
                    val curIdx = tabs.indexOf(activeTab)
                    val nextTab = tabs[(curIdx + 1) % tabs.size]
                    runOnMainSync { switchToTab(nextTab) }
                }
                JSONObject().put("status", "switched").put("active_tab_id", activeTab?.id ?: -1).put("url", currentUrl).toString()
            }
            "prev", "previous" -> {
                if (tabs.size > 1 && activeTab != null) {
                    val curIdx = tabs.indexOf(activeTab)
                    val prevTab = tabs[(curIdx - 1 + tabs.size) % tabs.size]
                    runOnMainSync { switchToTab(prevTab) }
                }
                JSONObject().put("status", "switched").put("active_tab_id", activeTab?.id ?: -1).put("url", currentUrl).toString()
            }
            "close" -> {
                val targetId = body?.optInt("tab_id", body.optInt("id", activeTab?.id ?: -1)) ?: (activeTab?.id ?: -1)
                val targetTab = findTabByQuery(targetId)
                if (targetTab != null) {
                    runOnMainSync { closeTab(targetTab) }
                    JSONObject()
                        .put("status", "closed")
                        .put("closed_tab_id", targetId)
                        .put("active_tab_id", activeTab?.id ?: -1)
                        .put("tabs", buildTabsJsonArray())
                        .toString()
                } else {
                    JSONObject().put("status", "error").put("message", "Tab not found").toString()
                }
            }
            else -> JSONObject().put("status", "error").put("message", "Unknown tab action: $action").toString()
        }
    }

    private fun executeCommand(method: String, path: String, body: JSONObject?): String {
        isEmergencyStopped.set(false)
        appendLog("$method $path")
        setConsoleStatus("🟢 127.0.0.1:$SERVER_PORT | CMD: $method $path", false)

        if (body != null && body.has("tab_id") && !path.startsWith("/tab") && path != "/close_tab" && path != "/switch_tab") {
            val reqTabId = body.optInt("tab_id", -1)
            if (reqTabId > 0 && activeTab?.id != reqTabId) {
                findTabByQuery(tabId = reqTabId)?.let { t ->
                    runOnMainSync { switchToTab(t) }
                    Thread.sleep(100)
                }
            }
        }

        return when (path) {
            "/", "/status" -> {
                JSONObject()
                    .put("status", "online")
                    .put("browser", "Gemini Browser")
                    .put("port", SERVER_PORT)
                    .put("active_tab_id", activeTab?.id ?: -1)
                    .put("tabs_count", tabs.size)
                    .put("tabs", buildTabsJsonArray())
                    .put("url", currentUrl)
                    .put("title", currentTitle)
                    .put("loading", isPageLoading)
                    .put("network_idle", isNetworkIdle)
                    .put("desktop_mode", desktopMode)
                    .put("userscripts_count", getSavedUserScripts().length())
                    .toString()
            }

            "/verify", "/github_verify" -> performGitHubVerificationSync(body)

            "/tabs", "/tab" -> {
                val defAct = if (body != null && (body.has("tab_id") || body.has("index")) && !body.has("action")) "switch" else "list"
                handleTabsApiSync(defAct, body)
            }
            "/switch_tab" -> handleTabsApiSync("switch", body)
            "/new_tab", "/open_tab" -> handleTabsApiSync("new", body)
            "/close_tab" -> handleTabsApiSync("close", body)

            "/desktop" -> {
                val enable = body?.optBoolean("enable", !desktopMode) ?: !desktopMode
                applyDesktopMode(enable)
                JSONObject().put("status", "ok").put("desktop_mode", desktopMode).toString()
            }

            "/download" -> {
                val dlUrl = body?.optString("url", currentUrl) ?: currentUrl
                runOnMainSync {
                    startBrowserDownload(dlUrl, if (desktopMode) desktopUA else mobileUA, null, null)
                }
                JSONObject().put("status", "download_started").put("url", dlUrl).toString()
            }

            "/navigate" -> {
                val targetUrl = body?.optString("url", "https://gemini.google.com") ?: "https://gemini.google.com"
                val waitIdle = body?.optBoolean("wait_idle", true) ?: true
                val openInNewTab = body?.optBoolean("new_tab", false) ?: false
                if (openInNewTab) {
                    runOnMainSync { createNewTab(targetUrl, switchToIt = true) }
                } else {
                    if (body?.has("desktop_mode") == true) {
                        val wantPc = body.optBoolean("desktop_mode", desktopMode)
                        if (wantPc != desktopMode) applyDesktopMode(wantPc)
                    }
                    activeTab?.isPageLoading = true
                    activeTab?.isNetworkIdle = false
                    runOnMainSync { webView.loadUrl(targetUrl) }
                }
                if (waitIdle) {
                    val start = System.currentTimeMillis()
                    while (System.currentTimeMillis() - start < 8000) {
                        if (!isPageLoading && isNetworkIdle) break
                        Thread.sleep(200)
                    }
                }
                JSONObject()
                    .put("status", "navigating")
                    .put("tab_id", activeTab?.id ?: -1)
                    .put("url", currentUrl)
                    .put("title", currentTitle)
                    .put("desktop_mode", desktopMode)
                    .toString()
            }

            "/dom" -> extractDomSync(true)

            "/reader" -> {
                val text = evalJsSync("""
                    (function() {
                        if (!document.body) return '';
                        const clone = document.body.cloneNode(true);
                        clone.querySelectorAll('script, style, noscript, nav, footer, iframe').forEach(e => e.remove());
                        return (clone.innerText || '').replace(/\n{3,}/g, '\n\n').trim();
                    })();
                """.trimIndent())
                JSONObject()
                    .put("status", "ok")
                    .put("tab_id", activeTab?.id ?: -1)
                    .put("url", currentUrl)
                    .put("title", currentTitle)
                    .put("content", text)
                    .toString()
            }

            "/ocr" -> {
                val mode = body?.optString("mode", "full_page") ?: "full_page"
                val showBoxes = body?.optBoolean("show_boxes", true) ?: true
                performOcrAndDeepTextSync(mode, showBoxes)
            }

            "/screenshot" -> {
                val b64 = captureViewportBase64Sync()
                JSONObject()
                    .put("status", "ok")
                    .put("tab_id", activeTab?.id ?: -1)
                    .put("url", currentUrl)
                    .put("image_base64", b64)
                    .toString()
            }

            "/click" -> {
                val elId = if (body != null && body.has("element_id")) body.optInt("element_id", -1) else -1
                val rawX = body?.optDouble("x", -1.0)?.toFloat() ?: -1f
                val rawY = body?.optDouble("y", -1.0)?.toFloat() ?: -1f
                val matchText = body?.optString("text", "") ?: ""
                performSmartClickSync(elId, rawX, rawY, matchText)
            }

            "/type" -> {
                val elId = if (body != null && body.has("element_id")) body.optInt("element_id", -1) else -1
                val b64 = body?.optString("text_b64", "") ?: ""
                val text = if (b64.isNotEmpty()) {
                    try { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { "" }
                } else {
                    body?.optString("text", "") ?: ""
                }
                val clear = body?.optBoolean("clear", false) ?: false
                val submit = body?.optBoolean("submit", false) ?: false
                val instant = body?.optBoolean("instant", false) ?: false
                performHumanTypingSync(elId, text, clear, submit, instant)
            }

            "/editor" -> performCodeEditorActionSync(body ?: JSONObject())

            "/key" -> {
                val keyName = body?.optString("key", "Enter") ?: "Enter"
                performSpecialKeySync(keyName)
            }

            "/scroll" -> {
                val dy = body?.optDouble("dy", 550.0)?.toFloat() ?: 550f
                val dx = body?.optDouble("dx", 0.0)?.toFloat() ?: 0f
                val elId = if (body != null && body.has("element_id")) body.optInt("element_id", -1) else -1
                performSmoothScrollSync(dx, dy, elId)
            }

            "/select" -> {
                val elId = body?.optInt("element_id", -1) ?: -1
                val value = body?.optString("value", "") ?: ""
                val text = body?.optString("text", value) ?: value
                val index = if (body != null && body.has("index")) body.optInt("index", -1) else -1
                performSelectSync(elId, value, text, index)
            }

            "/userscript", "/eval" -> handleUserScriptApiSync(body ?: JSONObject())

            "/wait" -> {
                val selector = body?.optString("selector", "") ?: ""
                val timeoutMs = body?.optLong("timeout_ms", 10000L) ?: 10000L
                waitForSelectorSync(selector, timeoutMs)
            }

            "/upload" -> {
                queuedFilePath = body?.optString("file_path", null)
                JSONObject().put("status", "queued").put("file_path", queuedFilePath).toString()
            }

            else -> JSONObject().put("status", "error").put("message", "Unknown endpoint: $path").toString()
        }
    }

    private fun handleUserScriptApiSync(body: JSONObject): String {
        val action = body.optString("action", "list").lowercase(Locale.ROOT)
        val b64 = body.optString("code_b64", body.optString("text_b64", ""))
        val code = if (b64.isNotEmpty()) {
            try { String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { "" }
        } else {
            body.optString("code", body.optString("script", ""))
        }
        val name = body.optString("name", "API_UserScript")

        return when (action) {
            "list" -> JSONObject().put("status", "ok").put("scripts", getSavedUserScripts()).toString()
            "add", "save" -> {
                val arr = getSavedUserScripts()
                arr.put(JSONObject().put("name", name).put("code", code).put("enabled", true))
                saveUserScripts(arr)
                runSingleUserScriptOnPage(webView, name, code)
                JSONObject().put("status", "saved_and_executed").put("name", name).put("total_scripts", arr.length()).toString()
            }
            "run", "eval" -> {
                val res = evalJsSync(code)
                JSONObject().put("status", "executed").put("result", res).toString()
            }
            "clear" -> {
                saveUserScripts(JSONArray())
                JSONObject().put("status", "cleared_all_scripts").toString()
            }
            else -> JSONObject().put("status", "error").put("message", "Unknown userscript action: $action").toString()
        }
    }

    private fun extractDomSync(showBoxes: Boolean): String {
        val overlayW = overlayView.width.coerceAtLeast(1)
        val js = """
            (function() {
                const selectors = 'a, button, input, select, textarea, [role="button"], [role="option"], [role="menuitem"], [role="textbox"], [role="code"], [contenteditable="true"], .monaco-editor, .cm-content, .ace_editor, [onclick], summary';
                const nodes = document.querySelectorAll(selectors);
                const scale = $overlayW / (window.innerWidth || 1);
                const list = [];
                let idCounter = 1;

                nodes.forEach(el => {
                    const r = el.getBoundingClientRect();
                    const s = window.getComputedStyle(el);
                    if (r.width > 5 && r.height > 5 &&
                        r.bottom > 0 && r.right > 0 &&
                        r.top < window.innerHeight && r.left < window.innerWidth &&
                        s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0') {

                        const id = idCounter++;
                        el.setAttribute('data-ai-id', id);
                        const tag = el.tagName.toLowerCase();
                        const isEditor = el.classList.contains('monaco-editor') || el.classList.contains('cm-content') || el.getAttribute('contenteditable') === 'true';

                        let rawLabel = '';
                        let optionsPreview = null;

                        if (tag === 'select') {
                            const selOpt = el.options && el.selectedIndex >= 0 ? el.options[el.selectedIndex] : null;
                            rawLabel = selOpt ? (selOpt.text || selOpt.value || '') : '';
                            const opts = [];
                            if (el.options) {
                                for (let i = 0; i < Math.min(el.options.length, 25); i++) {
                                    opts.push((el.options[i].text || el.options[i].value || '').trim());
                                }
                            }
                            optionsPreview = opts;
                        } else {
                            rawLabel = (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || el.name || (isEditor ? 'Code Editor' : '')).replace(/\s+/g, ' ').trim();
                            if (rawLabel.length <= 4 && el.parentElement) {
                                const rowText = (el.parentElement.innerText || '').replace(/\s+/g, ' ').trim();
                                if (rowText.length > rawLabel.length) rawLabel = rowText;
                            }
                        }

                        const itemObj = {
                            id: id,
                            tag: isEditor ? 'code-editor' : (el.getAttribute('role') === 'option' ? 'dropdown-option' : tag),
                            type: el.type || (tag === 'select' ? 'dropdown' : (isEditor ? 'monaco/codemirror' : '')),
                            text: rawLabel.slice(0, 140),
                            x: Math.round((r.left + r.width / 2) * scale),
                            y: Math.round((r.top + r.height / 2) * scale),
                            left: Math.round(r.left * scale),
                            top: Math.round(r.top * scale),
                            right: Math.round(r.right * scale),
                            bottom: Math.round(r.bottom * scale),
                            width: Math.round(r.width * scale),
                            height: Math.round(r.height * scale)
                        };
                        if (optionsPreview) itemObj.options = optionsPreview;
                        list.push(itemObj);
                    }
                });
                return JSON.stringify(list);
            })();
        """.trimIndent()

        val jsonStr = evalJsSync(js)
        val finalArr = try {
            if (jsonStr.startsWith("[")) JSONArray(jsonStr) else JSONArray()
        } catch (_: Exception) {
            JSONArray()
        }

        if (showBoxes && finalArr.length() > 0) {
            try {
                val boxes = mutableListOf<Pair<RectF, String>>()
                for (i in 0 until finalArr.length()) {
                    val o = finalArr.getJSONObject(i)
                    val rect = RectF(
                        o.getDouble("left").toFloat(),
                        o.getDouble("top").toFloat(),
                        o.getDouble("right").toFloat(),
                        o.getDouble("bottom").toFloat()
                    )
                    boxes.add(Pair(rect, "[${o.getInt("id")}]"))
                }
                runOnMainSync { overlayView.showDomBoxes(boxes) }
            } catch (_: Exception) {}
        }

        val curId = activeTab?.id ?: -1
        for (t in tabs) {
            val isCur = (t.id == curId)
            finalArr.put(
                JSONObject()
                    .put("id", 9000 + t.id)
                    .put("tag", "browser-tab")
                    .put("type", if (isCur) "active_tab" else "switch_tab")
                    .put("tab_id", t.id)
                    .put("text", (if (isCur) "[ACTIVE TAB #${t.id}] " else "[SWITCH TO TAB #${t.id}] ") + t.title.take(40) + " (" + t.url.take(50) + ")")
                    .put("x", 0).put("y", 0).put("left", 0).put("top", 0).put("right", 0).put("bottom", 0).put("width", 0).put("height", 0)
            )
        }
        finalArr.put(
            JSONObject()
                .put("id", 9099)
                .put("tag", "browser-tab-new")
                .put("type", "new_tab_button")
                .put("text", "[+ NEW TAB] Open a new browser tab")
                .put("x", 0).put("y", 0).put("left", 0).put("top", 0).put("right", 0).put("bottom", 0).put("width", 0).put("height", 0)
        )

        return finalArr.toString()
    }

    private fun performSmartClickSync(elementId: Int, rawX: Float, rawY: Float, matchText: String = ""): String {
        evalJsSync("(function(){ if (window.__aiCommitActiveInput) window.__aiCommitActiveInput(); })()")

        if (elementId == 9099) {
            var created: BrowserTab? = null
            runOnMainSync { created = createNewTab("https://gemini.google.com", switchToIt = true) }
            return JSONObject()
                .put("status", "new_tab_opened")
                .put("tab_id", created?.id ?: -1)
                .put("tabs", buildTabsJsonArray())
                .toString()
        }
        if (elementId in 9001..9098) {
            val targetTabId = elementId - 9000
            val targetTab = findTabByQuery(tabId = targetTabId)
            if (targetTab != null) {
                runOnMainSync { switchToTab(targetTab) }
                return JSONObject()
                    .put("status", "switched_tab")
                    .put("active_tab_id", targetTab.id)
                    .put("title", targetTab.title)
                    .put("url", targetTab.url)
                    .toString()
            }
        }

        val overlayW = overlayView.width.coerceAtLeast(1)
        val wvW = webView.width.coerceAtLeast(1)
        val wvScaleRatio = wvW.toFloat() / overlayW.toFloat()

        if (matchText.isNotEmpty()) {
            val lowerMatch = matchText.trim().lowercase(Locale.ROOT)
            if (lowerMatch.startsWith("switch to tab") || lowerMatch.startsWith("[switch to tab") || Regex("^tab\\s*#?\\d+$").matches(lowerMatch)) {
                val tabTarget = findTabByQuery(query = lowerMatch)
                if (tabTarget != null) {
                    runOnMainSync { switchToTab(tabTarget) }
                    return JSONObject()
                        .put("status", "switched_tab")
                        .put("active_tab_id", tabTarget.id)
                        .put("url", tabTarget.url)
                        .toString()
                }
            }

            val safeTarget = matchText.replace("\\", "\\\\").replace("'", "\\'")
            val textClickRes = evalJsSync("""
                (function() {
                    if (window.__aiCommitActiveInput) window.__aiCommitActiveInput();
                    const q = '$safeTarget'.trim().toLowerCase();
                    const selects = document.querySelectorAll('select');
                    for (let s = 0; s < selects.length; s++) {
                        const sel = selects[s];
                        for (let i = 0; i < sel.options.length; i++) {
                            const opt = sel.options[i];
                            const txt = (opt.text || opt.label || opt.value || '').trim().toLowerCase();
                            if (txt === q || txt.includes(q)) {
                                sel.selectedIndex = i;
                                sel.value = opt.value;
                                sel.dispatchEvent(new Event('focus', { bubbles: true }));
                                sel.dispatchEvent(new Event('input', { bubbles: true }));
                                sel.dispatchEvent(new Event('change', { bubbles: true }));
                                if (window.jQuery) { try { window.jQuery(sel).trigger('change'); } catch(e){} }
                                const modal = document.getElementById('__ai_select_modal');
                                if (modal) modal.remove();
                                return JSON.stringify({ status: 'selected_option', option_text: opt.text, index: i, value: opt.value });
                            }
                        }
                    }
                    const nodes = document.querySelectorAll('[role="option"], [role="menuitem"], button, a, input[type="submit"], input[type="button"], [role="button"], span, div');
                    for (let i = nodes.length - 1; i >= 0; i--) {
                        const el = nodes[i];
                        const t = (el.innerText || el.value || el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                        if (t === q) {
                            el.scrollIntoView({ behavior: 'instant', block: 'center' });
                            el.click();
                            return JSON.stringify({ status: 'clicked_by_text', text: t });
                        }
                    }
                    return '';
                })();
            """.trimIndent())
            if (textClickRes.startsWith("{")) return textClickRes
        }

        var screenX = rawX
        var screenY = rawY
        var targetRect: RectF? = null

        if (elementId > 0) {
            val specialCheck = evalJsSync("""
                (function() {
                    const el = document.querySelector('[data-ai-id="$elementId"]');
                    if (!el) return '';
                    if (el.getAttribute('role') === 'option' && el.hasAttribute('data-ai-select-index')) {
                        const txt = (el.innerText || '').trim();
                        el.click();
                        return JSON.stringify({ status: 'selected_dropdown_option', element_id: $elementId, selected_text: txt });
                    }
                    if (el.tagName.toLowerCase() === 'select') {
                        if (window.__aiOpenSelectModal) window.__aiOpenSelectModal(el);
                        const opts = [];
                        for (let i = 0; i < el.options.length; i++) {
                            opts.push({ index: i, text: (el.options[i].text || '').trim(), value: el.options[i].value });
                        }
                        return JSON.stringify({
                            status: 'select_dropdown_opened',
                            element_id: $elementId,
                            options: opts
                        });
                    }
                    return '';
                })();
            """.trimIndent())

            if (specialCheck.startsWith("{")) {
                extractDomSync(true)
                return specialCheck
            }

            val js = """
                (function() {
                    const el = document.querySelector('[data-ai-id="$elementId"]');
                    if (!el) return '';
                    el.scrollIntoView({ behavior: 'instant', block: 'center', inline: 'center' });
                    const r = el.getBoundingClientRect();
                    const scale = $overlayW / (window.innerWidth || 1);
                    return JSON.stringify({
                        x: (r.left + r.width / 2) * scale,
                        y: (r.top + r.height / 2) * scale,
                        l: r.left * scale,
                        t: r.top * scale,
                        r: r.right * scale,
                        b: r.bottom * scale
                    });
                })();
            """.trimIndent()
            val posStr = evalJsSync(js)
            Thread.sleep(150)
            if (posStr.isNotEmpty() && posStr != "null") {
                val obj = JSONObject(posStr)
                screenX = obj.getDouble("x").toFloat()
                screenY = obj.getDouble("y").toFloat()
                targetRect = RectF(
                    obj.getDouble("l").toFloat(),
                    obj.getDouble("t").toFloat(),
                    obj.getDouble("r").toFloat(),
                    obj.getDouble("b").toFloat()
                )
            } else {
                return JSONObject().put("status", "error").put("message", "Element [$elementId] not found").toString()
            }
        }

        val urlBeforeClick = currentUrl
        val wvTouchX = screenX * wvScaleRatio
        val wvTouchY = screenY * wvScaleRatio
        val holdMs = (55..110).random().toLong()

        runOnMainSync {
            webView.requestFocus()
            overlayView.showActionHighlight(screenX, screenY, targetRect, if (elementId > 0) "CLICK [$elementId]" else "TAP")
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, wvTouchX, wvTouchY, 0)
            val up = MotionEvent.obtain(downTime, downTime + holdMs, MotionEvent.ACTION_UP, wvTouchX, wvTouchY, 0)
            webView.dispatchTouchEvent(down)
            webView.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }

        if (elementId > 0) {
            evalJsSync("""
                (function() {
                    const el = document.querySelector('[data-ai-id="$elementId"]');
                    if (!el) return;
                    if (el.classList.contains('monaco-editor')) {
                        const ta = el.querySelector('textarea.inputarea') || el.querySelector('textarea');
                        if (ta) ta.focus(); else el.focus();
                    } else {
                        el.focus();
                    }
                })();
            """.trimIndent())
        }

        Thread.sleep(holdMs + 120)
        return JSONObject()
            .put("status", "clicked")
            .put("note", "Click dispatched. For GitHub commits or Actions, call /verify to confirm actual commit_sha, file_path, content & run logs.")
            .put("tab_id", activeTab?.id ?: -1)
            .put("element_id", elementId)
            .put("url_before", urlBeforeClick)
            .put("url_after", currentUrl)
            .put("x", screenX)
            .put("y", screenY)
            .toString()
    }

    private fun performSelectSync(elementId: Int, value: String, text: String, index: Int): String {
        val safeVal = value.replace("\\", "\\\\").replace("'", "\\'")
        val safeTxt = text.replace("\\", "\\\\").replace("'", "\\'")
        return evalJsSync("""
            (function() {
                let el = $elementId > 0 ? document.querySelector('[data-ai-id="$elementId"]') : document.querySelector('select');
                if (!el || el.tagName.toLowerCase() !== 'select') return JSON.stringify({ status: 'error', message: 'Select element not found' });
                let chosenIdx = $index;
                if (chosenIdx < 0) {
                    const q = ('$safeTxt' || '$safeVal').trim().toLowerCase();
                    for (let i = 0; i < el.options.length; i++) {
                        const ot = (el.options[i].text || el.options[i].value || '').trim().toLowerCase();
                        if (ot === q || ot.includes(q)) { chosenIdx = i; break; }
                    }
                }
                if (chosenIdx >= 0 && chosenIdx < el.options.length) {
                    el.selectedIndex = chosenIdx;
                    el.value = el.options[chosenIdx].value;
                    el.dispatchEvent(new Event('focus', { bubbles: true }));
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    const modal = document.getElementById('__ai_select_modal');
                    if (modal) modal.remove();
                    return JSON.stringify({ status: 'selected', selected_index: chosenIdx, selected_text: el.options[chosenIdx].text });
                }
                return JSON.stringify({ status: 'option_not_found' });
            })();
        """.trimIndent())
    }

    private fun performHumanTypingSync(elementId: Int, text: String, clear: Boolean, submit: Boolean, instant: Boolean): String {
        if (elementId > 0) {
            performSmartClickSync(elementId, -1f, -1f)
            Thread.sleep(150)
        } else {
            runOnMainSync { webView.requestFocus() }
        }

        if (clear) {
            if (elementId > 0) {
                evalJsSync("""
                    (function() {
                        const el = document.querySelector('[data-ai-id="$elementId"]');
                        if (el && window.__aiSetReactSafeValue) window.__aiSetReactSafeValue(el, '', '', false);
                    })();
                """.trimIndent())
            } else {
                performCodeEditorActionSync(JSONObject().put("action", "clear"))
            }
        }

        if (instant) {
            if (elementId > 0) {
                val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val res = evalJsSync("""
                    (function() {
                        const bin = atob('$b64');
                        const bytes = new Uint8Array(bin.length);
                        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                        const decoded = new TextDecoder('utf-8').decode(bytes);
                        const el = document.querySelector('[data-ai-id="$elementId"]') || document.activeElement;
                        if (el && window.__aiSetReactSafeValue) {
                            window.__aiSetReactSafeValue(el, decoded, decoded, false);
                            return JSON.stringify({ status: 'typed_instant', length: decoded.length, value: el.value || el.innerText || '' });
                        }
                        return '';
                    })();
                """.trimIndent())
                if (res.startsWith("{")) return res
            }
            return performCodeEditorActionSync(JSONObject().put("action", "insert").put("text", text))
        }

        for (i in 0 until text.length) {
            if (isEmergencyStopped.get()) return JSONObject().put("status", "stopped").toString()
            val ch = text[i]
            val safe = ch.toString()
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "")

            val js = """
                (function() {
                    if (window.monaco && window.monaco.editor) {
                        const editors = window.monaco.editor.getEditors ? window.monaco.editor.getEditors() : [];
                        if (editors.length > 0 && editors[0].hasTextFocus && editors[0].hasTextFocus()) {
                            editors[0].trigger('keyboard', 'type', { text: '$safe' });
                            return 'monaco';
                        }
                    }

                    let el = $elementId > 0 ? document.querySelector('[data-ai-id="$elementId"]') : document.activeElement;
                    if (!el || el === document.body) {
                        el = document.querySelector('.cm-content[contenteditable="true"], .monaco-editor textarea.inputarea, [contenteditable="true"], input:focus, textarea:focus, input:not([type="hidden"]), textarea');
                    }
                    if (!el) return 'no_element';

                    el.focus();
                    el.dispatchEvent(new KeyboardEvent('keydown', { key: '$safe', bubbles: true, cancelable: true }));

                    if (window.__aiSetReactSafeValue) {
                        window.__aiSetReactSafeValue(el, null, '$safe', true);
                    } else if ('value' in el) {
                        const prev = el.value || '';
                        const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                        const desc = Object.getOwnPropertyDescriptor(proto, 'value');
                        const nextVal = prev + '$safe';
                        if (desc && desc.set) desc.set.call(el, nextVal); else el.value = nextVal;
                        if (el._valueTracker) { try { el._valueTracker.setValue(prev); } catch (e) {} }
                        el.dispatchEvent(new InputEvent('input', { data: '$safe', inputType: 'insertText', bubbles: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                    }

                    el.dispatchEvent(new KeyboardEvent('keyup', { key: '$safe', bubbles: true }));
                    return 'ok';
                })();
            """.trimIndent()
            evalJsSync(js)
            Thread.sleep((28..72).random().toLong())
        }

        val finalRead = evalJsSync("""
            (function() {
                let el = $elementId > 0 ? document.querySelector('[data-ai-id="$elementId"]') : document.activeElement;
                if (!el) return '';
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return (el.value !== undefined ? el.value : (el.innerText || '')).slice(0, 140);
            })();
        """.trimIndent())

        if (submit) {
            performSpecialKeySync("Enter")
            evalJsSync("(function(){ const el = document.activeElement; if (el && el.form) el.form.submit(); })()")
        }

        return JSONObject()
            .put("status", "typed")
            .put("tab_id", activeTab?.id ?: -1)
            .put("element_id", elementId)
            .put("length", text.length)
            .put("verified_input_value", finalRead)
            .put("submitted", submit)
            .toString()
    }

    private fun performCodeEditorActionSync(body: JSONObject): String {
        val action = body.optString("action", "get_code").lowercase(Locale.ROOT)
        val inputB64 = body.optString("text_b64", "")
        val text = if (inputB64.isNotEmpty()) {
            try { String(Base64.decode(inputB64, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { "" }
        } else {
            body.optString("text", "")
        }
        val line = body.optInt("line", 1)
        val b64Text = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        if ((action == "paste" || action == "set_code" || action == "insert") && text.isNotEmpty()) {
            runOnMainSync {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ai_paste", text))
            }
        }

        val js = """
            (function() {
                const action = '$action';
                function decodeB64Utf8(b64) {
                    if (!b64) return '';
                    try {
                        const bin = atob(b64);
                        const bytes = new Uint8Array(bin.length);
                        for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                        return new TextDecoder('utf-8').decode(bytes);
                    } catch (e) { return ''; }
                }
                const rawText = decodeB64Utf8('$b64Text');

                function findCmView() {
                    const nodes = document.querySelectorAll('.cm-editor, .cm-content, .cm-scroller, .cm-line');
                    for (let i = 0; i < nodes.length; i++) {
                        const cv = nodes[i].cmView;
                        if (!cv) continue;
                        if (cv.view && typeof cv.view.dispatch === 'function' && cv.view.state) return cv.view;
                        if (cv.rootView && cv.rootView.view && typeof cv.rootView.view.dispatch === 'function') return cv.rootView.view;
                        const keys1 = Object.keys(cv);
                        for (let j = 0; j < keys1.length; j++) {
                            const v1 = cv[keys1[j]];
                            if (v1 && typeof v1 === 'object') {
                                if (typeof v1.dispatch === 'function' && v1.state && v1.state.doc) return v1;
                                const keys2 = Object.keys(v1);
                                for (let k = 0; k < keys2.length; k++) {
                                    const v2 = v1[keys2[k]];
                                    if (v2 && typeof v2 === 'object' && typeof v2.dispatch === 'function' && v2.state && v2.state.doc) {
                                        return v2;
                                    }
                                }
                            }
                        }
                    }
                    return null;
                }

                const cmView = findCmView();
                const cmContent = document.querySelector('.cm-content[contenteditable="true"]');
                const monacoModels = (window.monaco && window.monaco.editor && window.monaco.editor.getModels)
                    ? window.monaco.editor.getModels() : [];
                const monacoEditors = (window.monaco && window.monaco.editor && window.monaco.editor.getEditors)
                    ? window.monaco.editor.getEditors() : [];

                let target = document.activeElement;
                if (!target || target === document.body) {
                    target = cmContent || document.querySelector('.monaco-editor textarea.inputarea, [contenteditable="true"], textarea, input:not([type="hidden"])');
                    if (target) target.focus();
                }

                function readCurrentCode() {
                    if (monacoModels.length > 0) return monacoModels[0].getValue();
                    if (cmView) return cmView.state.doc.toString();
                    if (cmContent) return cmContent.innerText || '';
                    if (target && 'value' in target) return target.value || '';
                    if (target && target.isContentEditable) return target.innerText || '';
                    return '';
                }

                function writeFullCode(newCode) {
                    if (monacoModels.length > 0) {
                        monacoModels[0].setValue(newCode);
                        return 'monaco_model';
                    }
                    if (cmView) {
                        cmView.dispatch({
                            changes: { from: 0, to: cmView.state.doc.length, insert: newCode },
                            selection: { anchor: newCode.length }
                        });
                        return 'codemirror6_dispatch';
                    }
                    if (target && window.__aiSetReactSafeValue) {
                        return window.__aiSetReactSafeValue(target, newCode, newCode, false);
                    }
                    return 'none';
                }

                if (action === 'get_code' || action === 'copy') {
                    const code = readCurrentCode();
                    return JSON.stringify({ status: 'ok', action: action, length: code.length, content: code });
                }

                if (action === 'set_code') {
                    const usedEngine = writeFullCode(rawText);
                    const actual = readCurrentCode();
                    return JSON.stringify({
                        status: 'ok',
                        engine: usedEngine,
                        expected_length: rawText.length,
                        actual_length: actual.length,
                        preview: actual.slice(0, 120)
                    });
                }

                if (action === 'clear') {
                    const usedEngine = writeFullCode('');
                    const actual = readCurrentCode();
                    return JSON.stringify({
                        status: 'cleared',
                        engine: usedEngine,
                        actual_length: actual.length
                    });
                }

                if (action === 'insert' || action === 'paste') {
                    let usedEngine = 'react_safe_insert';
                    if (monacoEditors.length > 0 && monacoEditors[0].hasTextFocus && monacoEditors[0].hasTextFocus()) {
                        monacoEditors[0].trigger('keyboard', 'type', { text: rawText });
                        usedEngine = 'monaco_trigger';
                    } else if (cmView) {
                        const mainSel = cmView.state.selection.main;
                        cmView.dispatch({
                            changes: { from: mainSel.from, to: mainSel.to, insert: rawText },
                            selection: { anchor: mainSel.from + rawText.length }
                        });
                        usedEngine = 'codemirror6_insert';
                    } else if (target && window.__aiSetReactSafeValue) {
                        usedEngine = window.__aiSetReactSafeValue(target, rawText, rawText, true);
                    }
                    const actual = readCurrentCode();
                    return JSON.stringify({
                        status: 'ok',
                        engine: usedEngine,
                        inserted_length: rawText.length,
                        actual_length: actual.length,
                        preview: actual.slice(0, 120)
                    });
                }

                if (action === 'goto_line') {
                    if (monacoEditors.length > 0) {
                        monacoEditors[0].revealLineInCenter($line);
                        monacoEditors[0].setPosition({ lineNumber: $line, column: 1 });
                        return JSON.stringify({ status: 'moved', line: $line });
                    }
                    if (cmView) {
                        const maxLines = cmView.state.doc.lines;
                        const targetLine = Math.min(Math.max(1, $line), maxLines);
                        const lineInfo = cmView.state.doc.line(targetLine);
                        cmView.dispatch({ selection: { anchor: lineInfo.from }, scrollIntoView: true });
                        return JSON.stringify({ status: 'moved', line: targetLine });
                    }
                }

                return JSON.stringify({ status: 'ok', content: readCurrentCode() });
            })();
        """.trimIndent()
        return evalJsSync(js)
    }

    private fun performSpecialKeySync(keyName: String): String {
        val keyCode = when (keyName.lowercase(Locale.ROOT)) {
            "backspace" -> KeyEvent.KEYCODE_DEL
            "delete" -> KeyEvent.KEYCODE_FORWARD_DEL
            "enter" -> KeyEvent.KEYCODE_ENTER
            "tab" -> KeyEvent.KEYCODE_TAB
            "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
            "up", "arrowup" -> KeyEvent.KEYCODE_DPAD_UP
            "down", "arrowdown" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left", "arrowleft" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right", "arrowright" -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> KeyEvent.KEYCODE_ENTER
        }
        runOnMainSync {
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
        return JSONObject().put("status", "key_sent").put("key", keyName).toString()
    }

    private fun performSmoothScrollSync(dx: Float, dy: Float, elementId: Int): String {
        evalJsSync("""
            (function() {
                let el = $elementId > 0 ? document.querySelector('[data-ai-id="$elementId"]') : null;
                if (!el) {
                    const modalCard = document.querySelector('#__ai_select_modal > div');
                    el = modalCard || document.querySelector('.cm-scroller, .monaco-scrollable-element, .ace_scrollbar');
                }
                if (el) el.scrollBy({ left: $dx, top: $dy, behavior: 'smooth' });
                else window.scrollBy({ left: $dx, top: $dy, behavior: 'smooth' });
            })();
        """.trimIndent())
        return JSONObject().put("status", "scrolled").put("dx", dx).put("dy", dy).toString()
    }

    private fun waitForSelectorSync(selector: String, timeoutMs: Long): String {
        val start = System.currentTimeMillis()
        val safeSel = selector.replace("'", "\\'")
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isEmergencyStopped.get()) break
            val found = evalJsSync("(function(){ return document.querySelector('$safeSel') !== null ? 'true' : 'false'; })()")
            if (found == "true") return JSONObject().put("status", "found").put("selector", selector).toString()
            Thread.sleep(250)
        }
        return JSONObject().put("status", "timeout").put("selector", selector).toString()
    }

    private fun captureViewportBase64Sync(): String {
        var b64 = ""
        runOnMainSync {
            val w = overlayView.width.coerceAtLeast(1)
            val h = overlayView.height.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
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
        val ocrScale = 2.0f
        var capturedBitmap: Bitmap? = null

        runOnMainSync {
            try {
                val w = (overlayView.width.coerceAtLeast(1) * ocrScale).toInt()
                val h = (overlayView.height.coerceAtLeast(1) * ocrScale).toInt()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.BLACK)
                canvas.scale(webView.scaleX * ocrScale, webView.scaleY * ocrScale)
                canvas.translate(-webView.scrollX.toFloat(), -webView.scrollY.toFloat())
                webView.draw(canvas)
                capturedBitmap = bmp
            } catch (e: Exception) {
                appendLog("OCR Bitmap Error: ${e.message}")
            }
        }

        val ocrBlocksArray = JSONArray()
        val overlayBoxes = mutableListOf<Pair<RectF, String>>()
        var ocrFullText = ""

        capturedBitmap?.let { bmp ->
            val latch = CountDownLatch(1)
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val image = InputImage.fromBitmap(bmp, 0)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        ocrFullText = visionText.text ?: ""
                        var blockId = 1
                        for (block in visionText.textBlocks) {
                            for (line in block.lines) {
                                val r = line.boundingBox
                                val txt = line.text.trim()
                                if (r != null && txt.isNotEmpty()) {
                                    val left = (r.left / ocrScale).toInt()
                                    val top = (r.top / ocrScale).toInt()
                                    val right = (r.right / ocrScale).toInt()
                                    val bottom = (r.bottom / ocrScale).toInt()
                                    val id = blockId++
                                    ocrBlocksArray.put(
                                        JSONObject()
                                            .put("id", id)
                                            .put("text", txt)
                                            .put("x", (left + right) / 2)
                                            .put("y", (top + bottom) / 2)
                                            .put("left", left)
                                            .put("top", top)
                                            .put("right", right)
                                            .put("bottom", bottom)
                                    )
                                    overlayBoxes.add(Pair(RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()), "T$id"))
                                }
                            }
                        }
                        latch.countDown()
                    }
                    .addOnFailureListener { latch.countDown() }
                latch.await(8, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }

        if (showBoxes && overlayBoxes.isNotEmpty()) {
            runOnMainSync { overlayView.showDomBoxes(overlayBoxes) }
        }

        return JSONObject()
            .put("status", "ok")
            .put("tab_id", activeTab?.id ?: -1)
            .put("url", currentUrl)
            .put("title", currentTitle)
            .put("mode", mode)
            .put("ocr_screen_text", ocrFullText)
            .put("ocr_blocks_count", ocrBlocksArray.length())
            .put("ocr_blocks", ocrBlocksArray)
            .toString()
    }

    private fun evalJsSync(script: String): String {
        var output = ""
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                webView.evaluateJavascript(script) { res ->
                    output = if (res != null && res.startsWith("\"") && res.endsWith("\"")) {
                        res.substring(1, res.length - 1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                            .replace("\\n", "\n")
                    } else {
                        res ?: ""
                    }
                    latch.countDown()
                }
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return output
    }

    private fun runOnMainSync(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            val latch = CountDownLatch(1)
            mainHandler.post {
                try { action() } finally { latch.countDown() }
            }
            latch.await(5, TimeUnit.SECONDS)
        }
    }

    private fun setConsoleStatus(msg: String, isError: Boolean) {
        mainHandler.post {
            consoleText.text = msg
            consoleText.setTextColor(if (isError) Color.parseColor("#FF5252") else Color.parseColor("#00E676"))
        }
    }

    private fun appendLog(entry: String) {
        mainHandler.post {
            if (commandLogs.size >= 20) commandLogs.removeFirst()
            val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            commandLogs.addLast("[$t] $entry")
            logPanel.text = commandLogs.joinToString("\n")
        }
    }

    private fun broadcastSse(event: String, detail: String) {
        val payload = JSONObject().put("event", event).put("detail", detail).toString()
        for (pw in sseClients) {
            try {
                pw.print("data: $payload\n\n")
                pw.flush()
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }

    class AIOverlayView(context: Context) : View(context) {
        private var touchPoint: PointF? = null
        private var highlightBox: RectF? = null
        private var actionTag: String = ""
        private val domBoxes = CopyOnWriteArrayList<Pair<RectF, String>>()

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E676")
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }

        private val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DD00C853")
            style = Paint.Style.FILL
        }

        private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        }

        private val touchCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#88FF1744")
            style = Paint.Style.FILL
        }

        fun showActionHighlight(x: Float, y: Float, box: RectF?, label: String) {
            domBoxes.clear()
            touchPoint = PointF(x, y)
            highlightBox = box
            actionTag = label
            invalidate()
            postDelayed({
                touchPoint = null
                highlightBox = null
                invalidate()
            }, 900)
        }

        fun showDomBoxes(items: List<Pair<RectF, String>>) {
            domBoxes.clear()
            domBoxes.addAll(items)
            invalidate()
            postDelayed({
                domBoxes.clear()
                invalidate()
            }, 2200)
        }

        fun clearHighlights() {
            domBoxes.clear()
            touchPoint = null
            highlightBox = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (pair in domBoxes) {
                val r = pair.first
                val lbl = pair.second
                canvas.drawRoundRect(r, 8f, 8f, boxPaint)
                val tw = tagTextPaint.measureText(lbl) + 12f
                val topY = (r.top - 30f).coerceAtLeast(4f)
                canvas.drawRect(r.left, topY, r.left + tw, topY + 30f, tagBgPaint)
                canvas.drawText(lbl, r.left + 6f, topY + 23f, tagTextPaint)
            }

            highlightBox?.let { r ->
                canvas.drawRoundRect(r, 10f, 10f, boxPaint)
                val tw = tagTextPaint.measureText(actionTag) + 16f
                val topY = (r.top - 34f).coerceAtLeast(4f)
                canvas.drawRect(r.left, topY, r.left + tw, topY + 32f, tagBgPaint)
                canvas.drawText(actionTag, r.left + 8f, topY + 24f, tagTextPaint)
            }

            touchPoint?.let { p ->
                canvas.drawCircle(p.x, p.y, 44f, touchCirclePaint)
            }
        }
    }
}
