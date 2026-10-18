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

    // TinyFish Autonomous Goal State
    @Volatile private var activeGoalDescription: String = "Autonomous Browser Task Execution"
    @Volatile private var expectedTargetFilePath: String = ""
    @Volatile private var expectedTargetContentLength: Int = 0
    @Volatile private var expectedTargetContentSha256: String = ""

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

        // Enable Chromium DevTools Protocol (CDP) for Playwright / remote inspection
        try {
            WebView.setWebContentsDebuggingEnabled(true)
        } catch (_: Exception) {}

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

    private fun sha256Hex(text: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
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
                    setConsoleStatus("🟢 DOM Gen #${domGeneration.get()}: ${arr.length()} items", false)
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
                    setConsoleStatus("🟢 OCR Blocks: $blocks", false)
                    appendLog("OCR Done: $blocks blocks")
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
                    val len = vObj.optInt("committed_content_length", vObj.optInt("editor_length", 0))
                    val summary = "SHA:${sha.ifEmpty { "N/A" }} | Path:${path.ifEmpty { "N/A" }} | Len:$len"
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
            text = "[System] Gemini Autonomous Browser Ready on port $SERVER_PORT (CDP Enabled)...\n"
        }

        root.addView(tabBarScroll)
        root.addView(navBar)
        root.addView(consoleScroll)
        root.addView(webContainer)
        root.addView(logPanel)

        setContentView(root)
    }

    // ==================== IMPROVEMENT 3 & 7: GITHUB STATE INSPECTOR & TINYFISH DIRECTIVE ENGINE ====================

    private fun inspectGitHubPageStateSync(): JSONObject {
        val js = """
            (function() {
                const url = window.location.href || '';
                const pathName = window.location.pathname || '';
                const isNewOrEdit = /\/(new|edit)\//.test(pathName);

                // 1. Reconstruct Effective File Path (Breadcrumb directories + File name input)
                const crumbParts = [];
                document.querySelectorAll(
                    '[data-testid="breadcrumbs"] a, [data-testid="breadcrumbs-filename"], .js-path-segment a, .breadcrumb a'
                ).forEach(el => {
                    const t = (el.innerText || el.textContent || '').replace(/\//g, '').trim();
                    if (t) crumbParts.push(t);
                });
                // Remove repository name if it is the first breadcrumb
                const repoParts = pathName.split('/').filter(Boolean);
                if (crumbParts.length > 0 && repoParts.length >= 2 && crumbParts[0].toLowerCase() === repoParts[1].toLowerCase()) {
                    crumbParts.shift();
                }

                const fnInput = document.querySelector(
                    'input[aria-label*="file name" i], input[placeholder*="Name your file" i], input.js-blob-filename, input[name="filename"]'
                );
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

                // 2. Read Editor Content (CodeMirror 6 / Monaco / Textarea)
                let editorContent = '';
                let editorEngine = 'none';

                function findCmView() {
                    const nodes = document.querySelectorAll('.cm-editor, .cm-content, .cm-scroller, .cm-line');
                    for (let i = 0; i < nodes.length; i++) {
                        const n = nodes[i];
                        const cv = n.cmView || n.cmTile;
                        if (cv) {
                            if (cv.view && cv.view.state && cv.view.state.doc) return cv.view;
                            if (cv.rootView && cv.rootView.view && cv.rootView.view.state) return cv.rootView.view;
                            if (cv.root && cv.root.view && cv.root.view.state) return cv.root.view;
                            for (const k of Object.keys(cv)) {
                                const v = cv[k];
                                if (v && typeof v === 'object' && v.state && v.state.doc && typeof v.dispatch === 'function') return v;
                                if (v && typeof v === 'object' && v.view && v.view.state && v.view.state.doc) return v.view;
                            }
                        }
                    }
                    return null;
                }

                const cmView = findCmView();
                const cmContent = document.querySelector('.cm-content[contenteditable="true"]');
                if (cmView) {
                    editorContent = cmView.state.doc.toString();
                    editorEngine = 'codemirror6_state';
                } else if (window.monaco && window.monaco.editor && window.monaco.editor.getModels && window.monaco.editor.getModels().length > 0) {
                    editorContent = window.monaco.editor.getModels()[0].getValue();
                    editorEngine = 'monaco_model';
                } else if (cmContent) {
                    const lines = cmContent.querySelectorAll('.cm-line');
                    if (lines.length > 0) {
                        const arr = [];
                        lines.forEach(l => arr.push((l.innerText || l.textContent || '').replace(/\u200B/g, '').replace(/\r?\n/g, '')));
                        editorContent = arr.join('\n');
                    } else {
                        editorContent = cmContent.innerText || '';
                    }
                    editorEngine = 'codemirror_dom_lines';
                }

                // 3. Detect Open Modal Dialog (e.g. "Commit changes" modal)
                const openDialogs = Array.from(document.querySelectorAll('[role="dialog"], dialog[open], .Overlay, .Box-overlay'))
                    .filter(d => {
                        const r = d.getBoundingClientRect();
                        const s = window.getComputedStyle(d);
                        return r.width > 50 && r.height > 50 && s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0';
                    });
                const hasOpenDialog = openDialogs.length > 0;
                let dialogTitle = '';
                let dialogCommitBtnId = -1;
                if (hasOpenDialog) {
                    const d = openDialogs[openDialogs.length - 1];
                    const h = d.querySelector('h1, h2, h3, [id*="dialog-title"], .Box-title');
                    dialogTitle = h ? (h.innerText || '').trim() : 'Modal Dialog';
                    const btns = d.querySelectorAll('button, [role="button"]');
                    btns.forEach(b => {
                        const t = (b.innerText || b.textContent || '').trim().toLowerCase();
                        if ((t === 'commit changes' || t.includes('commit')) && !b.disabled && b.getAttribute('aria-disabled') !== 'true') {
                            const aid = parseInt(b.getAttribute('data-ai-id') || '-1', 10);
                            if (aid > 0) dialogCommitBtnId = aid;
                        }
                    });
                }

                // 4. Check Main Page "Commit changes..." Trigger Button
                let topCommitBtnId = -1;
                let topCommitBtnEnabled = false;
                document.querySelectorAll('button, [role="button"]').forEach(b => {
                    const t = (b.innerText || b.textContent || '').trim().toLowerCase();
                    if (t === 'commit changes...' || t === 'commit changes') {
                        const inDlg = b.closest('[role="dialog"], dialog[open], .Overlay') !== null;
                        if (!inDlg) {
                            topCommitBtnId = parseInt(b.getAttribute('data-ai-id') || '-1', 10);
                            topCommitBtnEnabled = !b.disabled && b.getAttribute('aria-disabled') !== 'true';
                        }
                    }
                });

                // 5. Latest Commit SHA if on committed blob/repo page
                let commitSha = '';
                const urlShaMatch = pathName.match(/\/(?:commit|blob|tree)\/([0-9a-f]{7,40})/i);
                if (urlShaMatch) commitSha = urlShaMatch[1];
                if (!commitSha) {
                    const shaEl = document.querySelector('a[href*="/commit/"], span.sha, meta[name="octolytics-dimension-repository_network_root_sha"]');
                    if (shaEl) {
                        const href = shaEl.getAttribute('href') || '';
                        const m = href.match(/\/commit\/([0-9a-f]{7,40})/i);
                        commitSha = m ? m[1] : (shaEl.getAttribute('content') || shaEl.innerText || '').trim();
                    }
                }

                return JSON.stringify({
                    url: url,
                    is_in_editor: isNewOrEdit,
                    breadcrumb_segments: crumbParts,
                    filename_input_value: fnVal,
                    filename_input_id: fnInput ? parseInt(fnInput.getAttribute('data-ai-id') || '-1', 10) : -1,
                    effective_full_path: effectivePath,
                    editor_engine: editorEngine,
                    editor_length: editorContent.length,
                    editor_lines_count: editorContent ? editorContent.split('\n').length : 0,
                    editor_preview: editorContent.slice(0, 200),
                    has_open_dialog: hasOpenDialog,
                    dialog_title: dialogTitle,
                    dialog_commit_button_id: dialogCommitBtnId,
                    page_commit_button_id: topCommitBtnId,
                    page_commit_button_enabled: topCommitBtnEnabled,
                    commit_sha: commitSha
                });
            })();
        """.trimIndent()

        val raw = evalJsSync(js)
        return try {
            if (raw.startsWith("{")) JSONObject(raw) else JSONObject()
        } catch (_: Exception) {
            JSONObject()
        }
    }

    // Builds the TinyFish-style Autonomous Supervisor Directive + Fresh Page State after EVERY action
    private fun attachFreshStateAndDirective(baseResponse: JSONObject, actionName: String): String {
        val currentGen = domGeneration.incrementAndGet()
        val domStr = extractDomSync(false)
        val domArr = try { if (domStr.startsWith("[")) JSONArray(domStr) else JSONArray() } catch (_: Exception) { JSONArray() }
        val ghState = inspectGitHubPageStateSync()

        // Filter priority elements (if a dialog is open, surface dialog elements at the top!)
        val hasDialog = ghState.optBoolean("has_open_dialog", false)
        val priorityElements = JSONArray()
        for (i in 0 until domArr.length()) {
            val item = domArr.getJSONObject(i)
            if (hasDialog && item.optBoolean("in_open_dialog", false)) {
                priorityElements.put(item)
            } else if (!hasDialog && priorityElements.length() < 35) {
                priorityElements.put(item)
            }
        }

        // Construct TinyFish Autonomous Directive
        val directive = JSONObject()
        directive.put("mode", "TINYFISH_AUTONOMOUS_SUPERVISOR")
        directive.put("active_goal", activeGoalDescription)
        directive.put("dom_generation", currentGen)

        val isEditor = ghState.optBoolean("is_in_editor", false)
        val effPath = ghState.optString("effective_full_path", "")
        val edLen = ghState.optInt("editor_length", 0)
        val dlgCommitId = ghState.optInt("dialog_commit_button_id", -1)
        val pageCommitId = ghState.optInt("page_commit_button_id", -1)
        val pageCommitEnabled = ghState.optBoolean("page_commit_button_enabled", false)
        val fnInputId = ghState.optInt("filename_input_id", -1)

        if (isEditor) {
            val pathMatches = expectedTargetFilePath.isEmpty() || effPath.equals(expectedTargetFilePath, ignoreCase = true)
            val contentMatches = if (expectedTargetContentLength > 0) {
                kotlin.math.abs(edLen - expectedTargetContentLength) <= 5
            } else {
                edLen > 40
            }

            if (effPath.isEmpty() || !pathMatches) {
                directive.put("directive_status", "CONTINUE_IMMEDIATELY")
                directive.put("detected_stage", "STEP_1_SET_FILE_PATH")
                directive.put(
                    "supervisor_instruction",
                    "⚡ [BROWSER DIRECTIVE]: File path is currently '$effPath' (expected: '${expectedTargetFilePath.ifEmpty { "target path" }}'). Call POST /set_github_path or POST /type on element_id=$fnInputId immediately. Do NOT stop!"
                )
                directive.put(
                    "suggested_next_call",
                    JSONObject()
                        .put("endpoint", "/set_github_path")
                        .put("body", JSONObject().put("path", expectedTargetFilePath.ifEmpty { ".github/workflows/url-audit.yml" }))
                )
            } else if (!contentMatches) {
                directive.put("directive_status", "FIX_REQUIRED_CONTINUE")
                directive.put("detected_stage", "STEP_2_SET_AND_VERIFY_EDITOR_CODE")
                directive.put(
                    "supervisor_instruction",
                    "⚠️ [BROWSER DIRECTIVE]: Path '$effPath' is set, BUT editor content length is $edLen chars (expected: ${if (expectedTargetContentLength > 0) expectedTargetContentLength else ">40"}). Do NOT click Commit yet! Call POST /editor with action='set_code' and text_b64, and verify exact_match==true."
                )
                directive.put(
                    "suggested_next_call",
                    JSONObject()
                        .put("endpoint", "/editor")
                        .put("body", JSONObject().put("action", "set_code").put("text_b64", "<BASE64_CONTENT>"))
                )
            } else if (hasDialog) {
                directive.put("directive_status", "CONTINUE_IMMEDIATELY")
                directive.put("detected_stage", "STEP_4_CONFIRM_COMMIT_IN_DIALOG")
                directive.put(
                    "supervisor_instruction",
                    "⚡ [BROWSER DIRECTIVE]: Commit dialog '${ghState.optString("dialog_title")}' is OPEN! Path '$effPath' and Editor ($edLen chars) are verified. Now click the dialog's primary 'Commit changes' button (element_id=$dlgCommitId, context='dialog') and then call POST /verify!"
                )
                directive.put(
                    "suggested_next_call",
                    JSONObject()
                        .put("endpoint", "/click")
                        .put("body", JSONObject().put("element_id", dlgCommitId).put("text", "Commit changes").put("context", "dialog"))
                )
            } else {
                directive.put("directive_status", "CONTINUE_IMMEDIATELY")
                directive.put("detected_stage", "STEP_3_OPEN_COMMIT_DIALOG")
                directive.put(
                    "supervisor_instruction",
                    "⚡ [BROWSER DIRECTIVE]: Both Path ('$effPath') and Editor Content ($edLen chars) are verified! Commit button enabled=$pageCommitEnabled. Click 'Commit changes...' (element_id=$pageCommitId) now!"
                )
                directive.put(
                    "suggested_next_call",
                    JSONObject()
                        .put("endpoint", "/click")
                        .put("body", JSONObject().put("element_id", pageCommitId).put("text", "Commit changes..."))
                )
            }
        } else if (currentUrl.contains("github.com") && (currentUrl.contains("/blob/") || currentUrl.contains("/tree/"))) {
            directive.put("directive_status", "CONTINUE_IMMEDIATELY")
            directive.put("detected_stage", "STEP_5_VERIFY_COMMIT_AND_ACTIONS")
            directive.put(
                "supervisor_instruction",
                "⚡ [BROWSER DIRECTIVE]: Editor is closed and page is at '$currentUrl' (SHA: ${ghState.optString("commit_sha").take(7)}). Call POST /verify now to confirm actual committed file path, content, and Actions run status!"
            )
            directive.put(
                "suggested_next_call",
                JSONObject().put("endpoint", "/verify").put("body", JSONObject().put("expected_path", expectedTargetFilePath))
            )
        } else {
            directive.put("directive_status", "CONTINUE_IMMEDIATELY")
            directive.put("detected_stage", "ACTIVE_NAVIGATION_OR_INTERACTION")
            directive.put(
                "supervisor_instruction",
                "⚡ [BROWSER DIRECTIVE]: Action '$actionName' completed on DOM Gen #$currentGen. Inspect 'fresh_page_state' below and execute the next step toward your goal immediately without pausing!"
            )
        }

        baseResponse.put("dom_generation", currentGen)
        baseResponse.put("github_state", ghState)
        baseResponse.put("fresh_page_state", JSONObject()
            .put("url", currentUrl)
            .put("title", currentTitle)
            .put("has_open_dialog", hasDialog)
            .put("interactive_elements_count", domArr.length())
            .put("priority_elements", priorityElements)
        )
        baseResponse.put("agent_directive", directive)
        return baseResponse.toString()
    }

    // ==================== IMPROVEMENT 3B: DEDICATED GITHUB PATH SETTER ====================

    private fun performSetGitHubPathSync(targetPath: String): String {
        val cleanPath = targetPath.trim().removePrefix("/")
        expectedTargetFilePath = cleanPath

        // 1. Focus the filename input and clear any existing breadcrumb segments with Backspace if needed
        val prepJs = """
            (function() {
                const fnInput = document.querySelector(
                    'input[aria-label*="file name" i], input[placeholder*="Name your file" i], input.js-blob-filename, input[name="filename"]'
                );
                if (!fnInput) return 'no_input';
                fnInput.scrollIntoView({ behavior: 'instant', block: 'center' });
                fnInput.focus();
                if (window.__aiSetReactSafeValue) {
                    window.__aiSetReactSafeValue(fnInput, '', '', false);
                } else {
                    fnInput.value = '';
                    fnInput.dispatchEvent(new Event('input', { bubbles: true }));
                }
                return 'ready';
            })();
        """.trimIndent()

        val status = evalJsSync(prepJs)
        if (status == "no_input") {
            return attachFreshStateAndDirective(
                JSONObject().put("status", "error").put("message", "GitHub filename input not found on current page"),
                "set_github_path"
            )
        }

        // Send a few Backspaces in case old breadcrumb segments exist
        for (i in 0..5) {
            val gh = inspectGitHubPageStateSync()
            val crumbs = gh.optJSONArray("breadcrumb_segments")?.length() ?: 0
            if (crumbs == 0) break
            performSpecialKeySync("Backspace")
            Thread.sleep(60)
        }

        // Type path segment by segment so GitHub's `/` breadcrumb splitter processes each directory cleanly
        for (ch in cleanPath) {
            val s = ch.toString().replace("\\", "\\\\").replace("'", "\\'")
            evalJsSync("""
                (function() {
                    const fnInput = document.querySelector(
                        'input[aria-label*="file name" i], input[placeholder*="Name your file" i], input.js-blob-filename, input[name="filename"]'
                    );
                    if (!fnInput) return;
                    fnInput.focus();
                    fnInput.dispatchEvent(new KeyboardEvent('keydown', { key: '$s', bubbles: true, cancelable: true }));
                    if (window.__aiSetReactSafeValue) {
                        window.__aiSetReactSafeValue(fnInput, null, '$s', true);
                    }
                    fnInput.dispatchEvent(new KeyboardEvent('keyup', { key: '$s', bubbles: true }));
                })();
            """.trimIndent())
            Thread.sleep(if (ch == '/') 120L else 25L)
        }

        Thread.sleep(200)
        val afterState = inspectGitHubPageStateSync()
        val actualFullPath = afterState.optString("effective_full_path", "")
        val verified = actualFullPath.equals(cleanPath, ignoreCase = true)

        val res = JSONObject()
            .put("status", if (verified) "path_verified" else "path_mismatch")
            .put("expected_path", cleanPath)
            .put("effective_full_path", actualFullPath)
            .put("verified", verified)
        return attachFreshStateAndDirective(res, "set_github_path")
    }

    // ==================== IMPROVEMENT 6: PLAYWRIGHT-STYLE SEMANTIC LOCATOR ENGINE ====================

    private fun performPlaywrightLocatorSync(body: JSONObject): String {
        val action = body.optString("action", "click").lowercase(Locale.ROOT)
        val role = body.optString("role", "")
        val text = body.optString("text", body.optString("name", ""))
        val label = body.optString("label", "")
        val placeholder = body.optString("placeholder", "")
        val selector = body.optString("selector", "")
        val contextFilter = body.optString("context", "") // e.g. "dialog"
        val exact = body.optBoolean("exact", false)
        val timeoutMs = body.optLong("timeout_ms", 5000L)

        val b64Val = body.optString("text_b64", "")
        val fillValue = if (b64Val.isNotEmpty()) {
            try { String(Base64.decode(b64Val, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { "" }
        } else {
            body.optString("value", "")
        }

        val start = System.currentTimeMillis()
        var matchedId = -1
        var matchedInfo = JSONObject()

        while (System.currentTimeMillis() - start < timeoutMs) {
            extractDomSync(false)
            val safeRole = role.replace("'", "\\'").lowercase(Locale.ROOT)
            val safeText = text.replace("'", "\\'").lowercase(Locale.ROOT)
            val safeLabel = label.replace("'", "\\'").lowercase(Locale.ROOT)
            val safePh = placeholder.replace("'", "\\'").lowercase(Locale.ROOT)
            val safeSel = selector.replace("'", "\\'")
            val safeCtx = contextFilter.replace("'", "\\'").lowercase(Locale.ROOT)

            val locateJs = """
                (function() {
                    const candidates = Array.from(document.querySelectorAll('[data-ai-id]'));
                    const scored = [];
                    for (const el of candidates) {
                        const r = el.getBoundingClientRect();
                        const s = window.getComputedStyle(el);
                        if (r.width <= 4 || r.height <= 4 || s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') continue;
                        if (el.disabled || el.getAttribute('aria-disabled') === 'true') continue;

                        if ('$safeSel' && !el.matches('$safeSel')) continue;

                        const elRole = (el.getAttribute('role') || el.tagName).toLowerCase();
                        const elText = (el.innerText || el.value || el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                        const elAria = (el.getAttribute('aria-label') || '').trim().toLowerCase();
                        const elPh = (el.getAttribute('placeholder') || '').trim().toLowerCase();
                        const inDialog = el.closest('[role="dialog"], dialog[open], .Overlay, .Box-overlay') !== null;
                        const elCtx = inDialog ? 'dialog' : 'main';

                        if ('$safeCtx' && elCtx !== '$safeCtx') continue;
                        if ('$safeRole' && !elRole.includes('$safeRole')) continue;
                        if ('$safeLabel' && !elAria.includes('$safeLabel') && !elText.includes('$safeLabel')) continue;
                        if ('$safePh' && !elPh.includes('$safePh')) continue;

                        if ('$safeText') {
                            const exactMatch = (elText === '$safeText' || elAria === '$safeText');
                            const partialMatch = (elText.includes('$safeText') || elAria.includes('$safeText'));
                            if ($exact && !exactMatch) continue;
                            if (!partialMatch) continue;
                        }

                        // Priority score: elements inside open modal dialogs & exact text matches win!
                        let score = 10;
                        if (inDialog) score += 100;
                        if ('$safeText' && (elText === '$safeText' || elAria === '$safeText')) score += 50;
                        if (el.tagName === 'BUTTON' || el.getAttribute('role') === 'button') score += 20;

                        scored.push({
                            id: parseInt(el.getAttribute('data-ai-id'), 10),
                            score: score,
                            tag: el.tagName.toLowerCase(),
                            text: elText.slice(0, 100),
                            in_dialog: inDialog
                        });
                    }
                    scored.sort((a, b) => b.score - a.score);
                    return scored.length > 0 ? JSON.stringify(scored[0]) : '';
                })();
            """.trimIndent()

            val foundStr = evalJsSync(locateJs)
            if (foundStr.startsWith("{")) {
                matchedInfo = JSONObject(foundStr)
                matchedId = matchedInfo.optInt("id", -1)
                if (matchedId > 0) break
            }
            Thread.sleep(200)
        }

        if (matchedId <= 0) {
            return attachFreshStateAndDirective(
                JSONObject()
                    .put("status", "locator_timeout")
                    .put("role", role)
                    .put("text", text)
                    .put("label", label),
                "locator"
            )
        }

        return when (action) {
            "click" -> performSmartClickSync(matchedId, -1f, -1f, "")
            "fill", "type" -> performHumanTypingSync(matchedId, fillValue, clear = true, submit = false, instant = true)
            else -> attachFreshStateAndDirective(
                JSONObject().put("status", "located").put("matched", matchedInfo),
                "locator"
            )
        }
    }

    // ==================== IMPROVEMENT 5: REAL COMMIT & ACTIONS VERIFIER ====================

    private fun performGitHubVerificationSync(body: JSONObject?): String {
        val expectedPath = body?.optString("expected_path", body.optString("path", expectedTargetFilePath)) ?: expectedTargetFilePath
        val expectedText = body?.optString("expected_content", body.optString("contains", "")) ?: ""
        val expectedLen = body?.optInt("expected_length", expectedTargetContentLength) ?: expectedTargetContentLength
        val repoParam = body?.optString("repo", "") ?: ""
        val tokenParam = body?.optString("token", "") ?: ""

        val domVerifyJs = """
            (function() {
                const url = window.location.href || '';
                const pathName = window.location.pathname || '';

                const isEditorOpen = /\/(edit|new)\//.test(pathName) ||
                    document.querySelector('.cm-content[contenteditable="true"], .monaco-editor textarea.inputarea') !== null;

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

                let committedLines = [];
                const roTextarea = document.querySelector('[data-testid="read-only-cursor-text-area"]');
                if (roTextarea && roTextarea.value) {
                    committedLines = roTextarea.value.split('\n');
                } else {
                    const reactLines = document.querySelectorAll('.react-code-text, .blob-code-inner');
                    reactLines.forEach(l => committedLines.push(l.innerText || l.textContent || ''));
                }
                const committedContent = committedLines.join('\n');

                let actionsRunStatus = '';
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

                return JSON.stringify({
                    url: url,
                    is_still_in_editor: isEditorOpen,
                    commit_sha: commitSha,
                    file_path: filePath,
                    committed_lines_count: committedLines.length,
                    committed_content_length: committedContent.length,
                    committed_content_preview: committedContent.slice(0, 500),
                    committed_content_full: committedContent.slice(0, 20000),
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
        if (expectedLen > 0 && domContent.isNotEmpty() && kotlin.math.abs(domContent.length - expectedLen) > 20) {
            verified = false
        }

        resultObj.remove("committed_content_full")
        resultObj.put("status", if (verified) "verified_success" else "unverified_or_pending")
        resultObj.put("commit_verified", verified)

        if (repoParam.isNotEmpty() && repoParam.contains("/")) {
            try {
                val apiVerification = verifyViaGitHubRestApi(repoParam, expectedPath, expectedText, tokenParam)
                resultObj.put("github_api_verification", apiVerification)
            } catch (e: Exception) {
                resultObj.put("github_api_error", e.message ?: "API error")
            }
        }

        return attachFreshStateAndDirective(resultObj, "verify")
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
                out.put("verified_file_path", fileObj.optString("path", path))
                out.put("verified_file_size", decoded.length)
                out.put("verified_file_sha256", sha256Hex(decoded))
                if (expectedSubstring.isNotEmpty()) {
                    out.put("content_matches_expected", decoded.contains(expectedSubstring))
                }
            }
        }

        val runsRaw = getJson("https://api.github.com/repos/$repo/actions/runs?per_page=1")
        val runsArr = JSONObject(runsRaw).optJSONArray("workflow_runs")
        if (runsArr != null && runsArr.length() > 0) {
            val run = runsArr.getJSONObject(0)
            val runId = run.optLong("id", 0L)
            out.put("workflow_run_id", runId)
            out.put("workflow_head_sha", run.optString("head_sha", ""))
            out.put("workflow_status", run.optString("status", ""))
            out.put("workflow_conclusion", run.optString("conclusion", ""))
        }
        return out
    }

    // ==================== MULTI-TAB ENGINE ====================

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
        appendLog("🔄 Switched to Tab #${targetTab.id}")
        broadcastSse("tab_switched", JSONObject().put("tab_id", targetTab.id).put("url", targetTab.url).toString())
    }

    private fun closeTab(targetTab: BrowserTab) {
        if (tabs.size <= 1) {
            targetTab.webView.loadUrl("https://gemini.google.com")
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

        if (wasActive && tabs.isNotEmpty()) {
            switchToTab(tabs[idx.coerceIn(0, tabs.size - 1)])
        } else {
            refreshTabBarUi()
        }
    }

    private fun findTabByQuery(tabId: Int = -1, index: Int = -1, query: String = ""): BrowserTab? {
        if (tabId > 0) tabs.find { it.id == tabId }?.let { return it }
        if (index in 0 until tabs.size) return tabs[index]
        if (query.isNotBlank()) {
            val q = query.trim().lowercase(Locale.ROOT)
            val numMatch = Regex("(?:tab\\s*#?|#)(\\d+)").find(q)
            if (numMatch != null) {
                val parsedId = numMatch.groupValues[1].toIntOrNull() ?: -1
                tabs.find { it.id == parsedId }?.let { return it }
                if (parsedId in 1..tabs.size) return tabs[parsedId - 1]
            }
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
                val cleanTitle = t.title.ifEmpty { t.url.removePrefix("https://") }.take(16)
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
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setPadding(dp(10), dp(2), dp(10), dp(2))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(28))
                setOnClickListener { createNewTab("https://gemini.google.com", switchToIt = true) }
            }
            tabBarContainer.addView(addTabBtn)
        }
    }

    // ==================== DOWNLOAD ENGINE ====================

    private fun startBrowserDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            if (url.startsWith("blob:")) {
                val safeBlobUrl = url.replace("'", "\\'")
                val js = """
                    (function() {
                        fetch('$safeBlobUrl').then(r => r.blob()).then(blob => {
                            const reader = new FileReader();
                            reader.onloadend = function() {
                                const b64 = (reader.result || '').split(',')[1] || '';
                                const ext = (blob.type && blob.type.split('/')[1]) ? '.' + blob.type.split('/')[1].split(';')[0] : '.bin';
                                if (window.AIAgentBridge && window.AIAgentBridge.saveBase64Download) {
                                    window.AIAgentBridge.saveBase64Download(b64, 'gemini_download_' + Date.now() + ext, blob.type || 'application/octet-stream');
                                }
                            };
                            reader.readAsDataURL(blob);
                        });
                    })();
                """.trimIndent()
                mainHandler.post { webView.evaluateJavascript(js, null) }
                return
            }

            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) addRequestHeader("Cookie", cookies)
                addRequestHeader("User-Agent", userAgent ?: mobileUA)
                setDescription("Downloading via Gemini Browser...")
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            appendLog("📥 Downloading: $fileName")
        } catch (e: Exception) {
            appendLog("Download Error: ${e.message}")
        }
    }

    private fun saveBytesToDownloads(bytes: ByteArray, fileName: String) {
        serverPool.execute {
            try {
                val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dlDir.exists()) dlDir.mkdirs()
                val outFile = File(dlDir, fileName)
                FileOutputStream(outFile).use { it.write(bytes) }
                appendLog("✅ Saved: ${outFile.name}")
            } catch (_: Exception) {}
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

        val editingObj = if (editIndex in 0 until scripts.length()) scripts.getJSONObject(editIndex) else null
        val nameInput = EditText(this).apply {
            hint = "Script Name"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setText(editingObj?.optString("name", "") ?: "")
        }
        val codeInput = EditText(this).apply {
            hint = "// JS Code..."
            setTextColor(Color.parseColor("#80CBC4"))
            setBackgroundColor(Color.parseColor("#0D0D0D"))
            typeface = Typeface.MONOSPACE
            minLines = 6
            setText(editingObj?.optString("code", "") ?: "")
        }
        container.addView(nameInput)
        container.addView(codeInput)
        scroll.addView(container)

        AlertDialog.Builder(this)
            .setTitle("⚡ UserScript Manager")
            .setView(scroll)
            .setPositiveButton("Save & Run") { _, _ ->
                val jsCode = codeInput.text.toString().trim()
                if (jsCode.isNotEmpty()) {
                    val sName = nameInput.text.toString().ifEmpty { "Script #${scripts.length() + 1}" }
                    scripts.put(JSONObject().put("name", sName).put("code", jsCode).put("enabled", true))
                    saveUserScripts(scripts)
                    runSingleUserScriptOnPage(webView, sName, jsCode)
                }
            }
            .setNegativeButton("Close", null)
            .show()
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
        val js = """
            (function() {
                try {
                    const bin = atob('$b64');
                    const bytes = new Uint8Array(bin.length);
                    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                    new Function(new TextDecoder('utf-8').decode(bytes))();
                } catch (e) {}
            })();
        """.trimIndent()
        mainHandler.post { targetWv.evaluateJavascript(js, null) }
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
            val targetPxW = (1280f * density).toInt()
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
                } catch (_: Exception) {}
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
                domGeneration.incrementAndGet()
                if (activeTab?.id == tab.id) {
                    urlInput.setText(tab.url)
                }
                injectStealthViewportAndTracker(tab)
                refreshTabBarUi()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tab.isPageLoading = false
                tab.url = url ?: ""
                tab.title = view?.title ?: "Tab ${tab.id}"
                domGeneration.incrementAndGet()
                if (activeTab?.id == tab.id) {
                    urlInput.setText(tab.url)
                }
                CookieManager.getInstance().flush()
                injectStealthViewportAndTracker(tab)
                injectSavedUserScripts(wv, tab.url)
                refreshTabBarUi()
                broadcastSse("page_loaded", tab.url)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
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
                result?.confirm()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.confirm()
                return true
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
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
                            try {
                                const dt = new DataTransfer();
                                dt.setData('text/plain', insertedDelta);
                                const pasteEv = new ClipboardEvent('paste', { clipboardData: dt, bubbles: true, cancelable: true, composed: true });
                                el.dispatchEvent(pasteEv);
                                if (!pasteEv.defaultPrevented) {
                                    document.execCommand('insertText', false, insertedDelta);
                                }
                            } catch (err) {
                                document.execCommand('insertText', false, insertedDelta);
                            }
                        }
                        el.dispatchEvent(new InputEvent('input', { data: insertedDelta || newValue, inputType: 'insertFromPaste', bubbles: true, composed: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                        return 'contenteditable_paste';
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

                // Auto-Click Newly Appeared Latest "Allow" Button (Gemini MCP Tool Confirmation)
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
                                        if (r.width > 10 && r.height > 10 && s.display !== 'none' && s.visibility !== 'hidden' && !el.disabled) {
                                            candidates.push(el);
                                        }
                                    }
                                }
                            }
                            collectButtons(document);
                            if (candidates.length > 0) {
                                const latestBtn = candidates[candidates.length - 1];
                                const now = Date.now();
                                const lastClickedTime = parseInt(latestBtn.getAttribute('data-ai-allow-ts') || '0', 10);
                                if (now - lastClickedTime > 1200) {
                                    latestBtn.setAttribute('data-ai-allow-ts', String(now));
                                    latestBtn.click();
                                    if (window.AIAgentBridge && window.AIAgentBridge.onAutoAllowed) {
                                        window.AIAgentBridge.onAutoAllowed('Allow button #' + candidates.length);
                                    }
                                }
                            }
                        } catch (err) {}
                    }
                    setInterval(findAndClickLatestAllow, 350);
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
                setConsoleStatus("🟢 ONLINE 127.0.0.1:$SERVER_PORT | TinyFish Ready", false)

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
            )
        }
        return arr
    }

    private fun handleTabsApiSync(defaultAction: String, body: JSONObject?): String {
        val action = body?.optString("action", defaultAction)?.lowercase(Locale.ROOT) ?: defaultAction
        return when (action) {
            "new", "open", "create" -> {
                val url = body?.optString("url", "https://gemini.google.com") ?: "https://gemini.google.com"
                var createdTab: BrowserTab? = null
                runOnMainSync { createdTab = createNewTab(url, true) }
                Thread.sleep(300)
                attachFreshStateAndDirective(JSONObject().put("status", "tab_created").put("tab_id", createdTab?.id ?: -1), "new_tab")
            }
            "switch", "select" -> {
                val targetId = body?.optInt("tab_id", body.optInt("id", -1)) ?: -1
                val idx = body?.optInt("index", -1) ?: -1
                val query = body?.optString("query", body.optString("title", "")) ?: ""
                val targetTab = findTabByQuery(targetId, idx, query)
                if (targetTab != null) {
                    runOnMainSync { switchToTab(targetTab) }
                    Thread.sleep(200)
                    attachFreshStateAndDirective(JSONObject().put("status", "switched").put("active_tab_id", targetTab.id), "switch_tab")
                } else {
                    JSONObject().put("status", "error").put("message", "Tab not found").toString()
                }
            }
            "close" -> {
                val targetId = body?.optInt("tab_id", activeTab?.id ?: -1) ?: -1
                val targetTab = findTabByQuery(targetId)
                if (targetTab != null) {
                    runOnMainSync { closeTab(targetTab) }
                    attachFreshStateAndDirective(JSONObject().put("status", "closed").put("closed_tab_id", targetId), "close_tab")
                } else {
                    JSONObject().put("status", "error").put("message", "Tab not found").toString()
                }
            }
            else -> attachFreshStateAndDirective(JSONObject().put("status", "ok").put("tabs", buildTabsJsonArray()), "list_tabs")
        }
    }

    private fun executeCommand(method: String, path: String, body: JSONObject?): String {
        isEmergencyStopped.set(false)
        appendLog("$method $path")
        setConsoleStatus("🟢 127.0.0.1:$SERVER_PORT | CMD: $method $path", false)

        if (body != null && body.has("tab_id") && !path.startsWith("/tab") && path != "/switch_tab") {
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
                val res = JSONObject()
                    .put("status", "online")
                    .put("browser", "Gemini Autonomous Browser")
                    .put("port", SERVER_PORT)
                    .put("cdp_enabled", true)
                    .put("active_tab_id", activeTab?.id ?: -1)
                    .put("tabs", buildTabsJsonArray())
                attachFreshStateAndDirective(res, "status")
            }

            "/goal" -> {
                activeGoalDescription = body?.optString("goal", activeGoalDescription) ?: activeGoalDescription
                expectedTargetFilePath = body?.optString("expected_path", expectedTargetFilePath) ?: expectedTargetFilePath
                expectedTargetContentLength = body?.optInt("expected_length", expectedTargetContentLength) ?: expectedTargetContentLength
                attachFreshStateAndDirective(
                    JSONObject()
                        .put("status", "goal_registered")
                        .put("goal", activeGoalDescription)
                        .put("expected_path", expectedTargetFilePath)
                        .put("expected_length", expectedTargetContentLength),
                    "goal"
                )
            }

            "/github_state" -> {
                attachFreshStateAndDirective(JSONObject().put("status", "ok"), "github_state")
            }

            "/set_github_path", "/github_path" -> {
                val targetPath = body?.optString("path", body.optString("file_path", "")) ?: ""
                performSetGitHubPathSync(targetPath)
            }

            "/locator" -> performPlaywrightLocatorSync(body ?: JSONObject())

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
                Thread.sleep(400)
                attachFreshStateAndDirective(JSONObject().put("status", "ok").put("desktop_mode", desktopMode), "desktop")
            }

            "/navigate" -> {
                val targetUrl = body?.optString("url", "https://gemini.google.com") ?: "https://gemini.google.com"
                val waitIdle = body?.optBoolean("wait_idle", true) ?: true
                if (body?.has("expected_path") == true) {
                    expectedTargetFilePath = body.optString("expected_path", "")
                }
                if (body?.has("desktop_mode") == true) {
                    val wantPc = body.optBoolean("desktop_mode", desktopMode)
                    if (wantPc != desktopMode) applyDesktopMode(wantPc)
                }
                activeTab?.isPageLoading = true
                activeTab?.isNetworkIdle = false
                runOnMainSync { webView.loadUrl(targetUrl) }
                if (waitIdle) {
                    val start = System.currentTimeMillis()
                    while (System.currentTimeMillis() - start < 8000) {
                        if (!isPageLoading && isNetworkIdle) break
                        Thread.sleep(200)
                    }
                }
                Thread.sleep(250)
                attachFreshStateAndDirective(
                    JSONObject().put("status", "navigated").put("url", currentUrl).put("title", currentTitle),
                    "navigate"
                )
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
                attachFreshStateAndDirective(JSONObject().put("status", "ok").put("content", text), "reader")
            }

            "/ocr" -> {
                val mode = body?.optString("mode", "full_page") ?: "full_page"
                val showBoxes = body?.optBoolean("show_boxes", true) ?: true
                performOcrAndDeepTextSync(mode, showBoxes)
            }

            "/screenshot" -> {
                val b64 = captureViewportBase64Sync()
                JSONObject().put("status", "ok").put("url", currentUrl).put("image_base64", b64).toString()
            }

            "/click" -> {
                val elId = if (body != null && body.has("element_id")) body.optInt("element_id", -1) else -1
                val rawX = body?.optDouble("x", -1.0)?.toFloat() ?: -1f
                val rawY = body?.optDouble("y", -1.0)?.toFloat() ?: -1f
                val matchText = body?.optString("text", "") ?: ""
                val contextFilter = body?.optString("context", "") ?: ""
                performSmartClickSync(elId, rawX, rawY, matchText, contextFilter)
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

            "/wait" -> {
                val selector = body?.optString("selector", "") ?: ""
                val timeoutMs = body?.optLong("timeout_ms", 10000L) ?: 10000L
                waitForSelectorSync(selector, timeoutMs)
            }

            else -> JSONObject().put("status", "error").put("message", "Unknown endpoint: $path").toString()
        }
    }

    // ==================== IMPROVEMENT 2: SEMANTIC DOM EXTRACTION WITH DIALOG CONTEXT ====================

    private fun extractDomSync(showBoxes: Boolean): String {
        val overlayW = overlayView.width.coerceAtLeast(1)
        val js = """
            (function() {
                const selectors = 'a, button, input, select, textarea, [role="button"], [role="option"], [role="menuitem"], [role="textbox"], [role="code"], [contenteditable="true"], .monaco-editor, .cm-content, [onclick], summary';
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

                        const dlg = el.closest('[role="dialog"], dialog[open], .Overlay, .Box-overlay');
                        const inOpenDialog = dlg !== null;
                        const ctx = inOpenDialog ? 'dialog' : (el.closest('header, nav') ? 'nav' : 'main');
                        const isDisabled = Boolean(el.disabled || el.getAttribute('aria-disabled') === 'true');

                        let rawLabel = (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || el.name || (isEditor ? 'Code Editor' : '')).replace(/\s+/g, ' ').trim();

                        list.push({
                            id: id,
                            tag: isEditor ? 'code-editor' : tag,
                            role: el.getAttribute('role') || tag,
                            type: el.type || (isEditor ? 'monaco/codemirror' : ''),
                            context: ctx,
                            in_open_dialog: inOpenDialog,
                            disabled: isDisabled,
                            aria_label: (el.getAttribute('aria-label') || '').slice(0, 80),
                            placeholder: (el.getAttribute('placeholder') || '').slice(0, 80),
                            text: rawLabel.slice(0, 140),
                            x: Math.round((r.left + r.width / 2) * scale),
                            y: Math.round((r.top + r.height / 2) * scale),
                            left: Math.round(r.left * scale),
                            top: Math.round(r.top * scale),
                            right: Math.round(r.right * scale),
                            bottom: Math.round(r.bottom * scale),
                            width: Math.round(r.width * scale),
                            height: Math.round(r.height * scale)
                        });
                    }
                });
                return JSON.stringify(list);
            })();
        """.trimIndent()

        val jsonStr = evalJsSync(js)
        val finalArr = try { if (jsonStr.startsWith("[")) JSONArray(jsonStr) else JSONArray() } catch (_: Exception) { JSONArray() }

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
                    val prefix = if (o.optBoolean("in_open_dialog", false)) "D" else ""
                    boxes.add(Pair(rect, "[$prefix${o.getInt("id")}]"))
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
                    .put("context", "browser")
                    .put("in_open_dialog", false)
                    .put("disabled", false)
                    .put("tab_id", t.id)
                    .put("text", (if (isCur) "[ACTIVE TAB #${t.id}] " else "[SWITCH TO TAB #${t.id}] ") + t.title.take(40))
                    .put("x", 0).put("y", 0).put("left", 0).put("top", 0).put("right", 0).put("bottom", 0).put("width", 0).put("height", 0)
            )
        }
        return finalArr.toString()
    }

    // ==================== IMPROVEMENT 2B: SEMANTIC & DIALOG-AWARE SMART CLICK ====================

    private fun performSmartClickSync(
        elementId: Int,
        rawX: Float,
        rawY: Float,
        matchText: String = "",
        contextFilter: String = ""
    ): String {
        evalJsSync("(function(){ if (window.__aiCommitActiveInput) window.__aiCommitActiveInput(); })()")

        if (elementId == 9099) {
            var created: BrowserTab? = null
            runOnMainSync { created = createNewTab("https://gemini.google.com", switchToIt = true) }
            return attachFreshStateAndDirective(JSONObject().put("status", "new_tab_opened").put("tab_id", created?.id ?: -1), "click")
        }
        if (elementId in 9001..9098) {
            val targetTab = findTabByQuery(tabId = elementId - 9000)
            if (targetTab != null) {
                runOnMainSync { switchToTab(targetTab) }
                return attachFreshStateAndDirective(JSONObject().put("status", "switched_tab").put("active_tab_id", targetTab.id), "click")
            }
        }

        val overlayW = overlayView.width.coerceAtLeast(1)
        val wvW = webView.width.coerceAtLeast(1)
        val wvScaleRatio = wvW.toFloat() / overlayW.toFloat()

        // Semantic Text Click with Automatic Open-Dialog Prioritization!
        if (matchText.isNotEmpty() && elementId <= 0) {
            val safeTarget = matchText.replace("\\", "\\\\").replace("'", "\\'")
            val safeCtx = contextFilter.replace("'", "\\'").lowercase(Locale.ROOT)
            val textClickRes = evalJsSync("""
                (function() {
                    if (window.__aiCommitActiveInput) window.__aiCommitActiveInput();
                    const q = '$safeTarget'.trim().toLowerCase();
                    const nodes = Array.from(document.querySelectorAll('button, a, input[type="submit"], input[type="button"], [role="button"], [role="menuitem"], [role="option"], summary, span'));
                    const matches = [];
                    for (const el of nodes) {
                        const r = el.getBoundingClientRect();
                        const s = window.getComputedStyle(el);
                        if (r.width <= 4 || r.height <= 4 || s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') continue;
                        if (el.disabled || el.getAttribute('aria-disabled') === 'true') continue;

                        const t = (el.innerText || el.value || el.getAttribute('aria-label') || el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                        const inDialog = el.closest('[role="dialog"], dialog[open], .Overlay, .Box-overlay') !== null;
                        if ('$safeCtx' === 'dialog' && !inDialog) continue;

                        if (t === q || t.includes(q)) {
                            let score = (t === q) ? 100 : 40;
                            // Crucial: If a modal dialog is open, strongly prefer buttons inside the dialog!
                            if (inDialog) score += 200;
                            if (el.tagName === 'BUTTON' || el.getAttribute('role') === 'button') score += 30;
                            matches.push({ el: el, score: score, text: t, in_dialog: inDialog });
                        }
                    }
                    matches.sort((a, b) => b.score - a.score);
                    if (matches.length > 0) {
                        const best = matches[0];
                        best.el.scrollIntoView({ behavior: 'instant', block: 'center' });
                        best.el.click();
                        return JSON.stringify({
                            status: 'clicked_by_semantic_text',
                            clicked_text: best.text,
                            in_open_dialog: best.in_dialog,
                            candidates_found: matches.length
                        });
                    }
                    return '';
                })();
            """.trimIndent())

            if (textClickRes.startsWith("{")) {
                Thread.sleep(350)
                return attachFreshStateAndDirective(JSONObject(textClickRes), "click")
            }
        }

        var screenX = rawX
        var screenY = rawY
        var targetRect: RectF? = null
        var clickedMeta = JSONObject()

        if (elementId > 0) {
            val js = """
                (function() {
                    const el = document.querySelector('[data-ai-id="$elementId"]');
                    if (!el) return '';
                    el.scrollIntoView({ behavior: 'instant', block: 'center', inline: 'center' });
                    const r = el.getBoundingClientRect();
                    const scale = $overlayW / (window.innerWidth || 1);
                    const inDialog = el.closest('[role="dialog"], dialog[open], .Overlay, .Box-overlay') !== null;
                    const disabled = Boolean(el.disabled || el.getAttribute('aria-disabled') === 'true');
                    return JSON.stringify({
                        x: (r.left + r.width / 2) * scale,
                        y: (r.top + r.height / 2) * scale,
                        l: r.left * scale,
                        t: r.top * scale,
                        r: r.right * scale,
                        b: r.bottom * scale,
                        tag: el.tagName.toLowerCase(),
                        text: (el.innerText || el.value || el.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim().slice(0, 80),
                        in_open_dialog: inDialog,
                        disabled: disabled
                    });
                })();
            """.trimIndent()
            val posStr = evalJsSync(js)
            Thread.sleep(120)
            if (posStr.isNotEmpty() && posStr.startsWith("{")) {
                clickedMeta = JSONObject(posStr)
                if (clickedMeta.optBoolean("disabled", false)) {
                    return attachFreshStateAndDirective(
                        JSONObject()
                            .put("status", "click_blocked_element_disabled")
                            .put("element_id", elementId)
                            .put("element_info", clickedMeta),
                        "click"
                    )
                }
                screenX = clickedMeta.getDouble("x").toFloat()
                screenY = clickedMeta.getDouble("y").toFloat()
                targetRect = RectF(
                    clickedMeta.getDouble("l").toFloat(),
                    clickedMeta.getDouble("t").toFloat(),
                    clickedMeta.getDouble("r").toFloat(),
                    clickedMeta.getDouble("b").toFloat()
                )
            } else {
                return attachFreshStateAndDirective(
                    JSONObject().put("status", "error").put("message", "Element [$elementId] not found in current DOM"),
                    "click"
                )
            }
        }

        val urlBeforeClick = currentUrl
        val wvTouchX = screenX * wvScaleRatio
        val wvTouchY = screenY * wvScaleRatio
        val holdMs = (55..95).random().toLong()

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
                    el.focus();
                    // Also trigger synthetic click if it's a button inside a modal dialog to guarantee activation
                    if (el.tagName === 'BUTTON' || el.getAttribute('role') === 'button') {
                        el.click();
                    }
                })();
            """.trimIndent())
        }

        // Wait 350ms for modal dialog or navigation transition before capturing fresh DOM state
        Thread.sleep(holdMs + 300)

        val res = JSONObject()
            .put("status", "clicked")
            .put("element_id", elementId)
            .put("clicked_meta", clickedMeta)
            .put("url_before", urlBeforeClick)
            .put("url_after", currentUrl)
        return attachFreshStateAndDirective(res, "click")
    }

    // ==================== IMPROVEMENT 1 (P0): BULLETPROOF 4-STAGE EDITOR SET_CODE + READ-BACK VERIFICATION ====================

    private fun performCodeEditorActionSync(body: JSONObject): String {
        val action = body.optString("action", "get_code").lowercase(Locale.ROOT)
        val filePathParam = body.optString("file_path", body.optString("path", ""))
        val inputB64 = body.optString("text_b64", "")
        val rawInputText = if (inputB64.isNotEmpty()) {
            try { String(Base64.decode(inputB64, Base64.DEFAULT), Charsets.UTF_8) } catch (_: Exception) { "" }
        } else {
            body.optString("text", "")
        }

        // Normalize CRLF -> LF for clean comparison
        val text = rawInputText.replace("\r\n", "\n")
        val line = body.optInt("line", 1)
        val b64Text = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        if (filePathParam.isNotEmpty()) {
            performSetGitHubPathSync(filePathParam)
        }

        if ((action == "paste" || action == "set_code" || action == "insert") && text.isNotEmpty()) {
            expectedTargetContentLength = text.length
            expectedTargetContentSha256 = sha256Hex(text)
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

                // Deep scan for CodeMirror 6 EditorView instance across all DOM nodes
                function findCmView() {
                    const nodes = document.querySelectorAll('.cm-editor, .cm-content, .cm-scroller, .cm-line');
                    for (let i = 0; i < nodes.length; i++) {
                        const n = nodes[i];
                        const hooks = [n.cmView, n.cmTile, n.__cmView];
                        for (const cv of hooks) {
                            if (!cv) continue;
                            if (cv.view && typeof cv.view.dispatch === 'function' && cv.view.state) return cv.view;
                            if (cv.rootView && cv.rootView.view && typeof cv.rootView.view.dispatch === 'function') return cv.rootView.view;
                            if (cv.root && cv.root.view && typeof cv.root.view.dispatch === 'function') return cv.root.view;
                            const keys1 = Object.keys(cv);
                            for (let j = 0; j < keys1.length; j++) {
                                const v1 = cv[keys1[j]];
                                if (v1 && typeof v1 === 'object') {
                                    if (typeof v1.dispatch === 'function' && v1.state && v1.state.doc) return v1;
                                    if (v1.view && typeof v1.view.dispatch === 'function' && v1.view.state) return v1.view;
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
                    }
                    return null;
                }

                const cmView = findCmView();
                const cmContent = document.querySelector('.cm-content[contenteditable="true"]');
                const monacoModels = (window.monaco && window.monaco.editor && window.monaco.editor.getModels)
                    ? window.monaco.editor.getModels() : [];

                let target = document.activeElement;
                if (!target || target === document.body || target.tagName === 'INPUT') {
                    target = cmContent || document.querySelector('.monaco-editor textarea.inputarea, [contenteditable="true"], textarea');
                    if (target) target.focus();
                }

                function readCurrentCode() {
                    if (monacoModels.length > 0) return (monacoModels[0].getValue() || '').replace(/\r\n/g, '\n');
                    if (cmView) return (cmView.state.doc.toString() || '').replace(/\r\n/g, '\n');
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
                    if (target && target.isContentEditable) return (target.innerText || '').replace(/\r\n/g, '\n');
                    return '';
                }

                // Multi-Strategy Writer with Automatic Read-Back Verification Loop
                function writeFullCodeAndVerify(newCode) {
                    const attemptsLog = [];

                    // Strategy 1: CodeMirror 6 Direct State Transaction (Atomic, handles 10,000+ lines)
                    if (cmView) {
                        try {
                            cmView.dispatch({
                                changes: { from: 0, to: cmView.state.doc.length, insert: newCode },
                                selection: { anchor: newCode.length }
                            });
                            const rb1 = readCurrentCode();
                            attemptsLog.push({ strategy: 'codemirror6_dispatch', length: rb1.length });
                            if (rb1.trim() === newCode.trim()) return { engine: 'codemirror6_dispatch', attempts: attemptsLog };
                        } catch (e) {
                            attemptsLog.push({ strategy: 'codemirror6_dispatch_error', msg: String(e) });
                        }
                    }

                    // Strategy 2: Monaco Model setValue
                    if (monacoModels.length > 0) {
                        try {
                            monacoModels[0].setValue(newCode);
                            const rb2 = readCurrentCode();
                            attemptsLog.push({ strategy: 'monaco_model', length: rb2.length });
                            if (rb2.trim() === newCode.trim()) return { engine: 'monaco_model', attempts: attemptsLog };
                        } catch (e) {}
                    }

                    // Strategy 3: Synthetic DataTransfer ClipboardEvent('paste') on .cm-content
                    // (Avoids execCommand('insertText') 25-char first-line truncation on multiline YAML!)
                    const editNode = cmContent || target;
                    if (editNode) {
                        try {
                            editNode.focus();
                            const sel = window.getSelection();
                            const range = document.createRange();
                            range.selectNodeContents(editNode);
                            sel.removeAllRanges();
                            sel.addRange(range);
                            document.execCommand('delete', false, null);

                            if (newCode.length > 0) {
                                const dt = new DataTransfer();
                                dt.setData('text/plain', newCode);
                                const pasteEvent = new ClipboardEvent('paste', {
                                    clipboardData: dt,
                                    bubbles: true,
                                    cancelable: true,
                                    composed: true
                                });
                                editNode.dispatchEvent(pasteEvent);

                                const rb3 = readCurrentCode();
                                attemptsLog.push({ strategy: 'clipboard_paste_event', length: rb3.length });
                                if (rb3.trim() === newCode.trim() || Math.abs(rb3.length - newCode.length) <= 5) {
                                    return { engine: 'clipboard_paste_event', attempts: attemptsLog };
                                }

                                // Strategy 3B: InputEvent('beforeinput', { inputType: 'insertFromPaste', dataTransfer })
                                const beforeInp = new InputEvent('beforeinput', {
                                    inputType: 'insertFromPaste',
                                    data: newCode,
                                    dataTransfer: dt,
                                    bubbles: true,
                                    cancelable: true,
                                    composed: true
                                });
                                editNode.dispatchEvent(beforeInp);
                                const rb3b = readCurrentCode();
                                attemptsLog.push({ strategy: 'beforeinput_insertFromPaste', length: rb3b.length });
                                if (rb3b.trim() === newCode.trim() || Math.abs(rb3b.length - newCode.length) <= 5) {
                                    return { engine: 'beforeinput_insertFromPaste', attempts: attemptsLog };
                                }
                            } else {
                                return { engine: 'cleared_dom', attempts: attemptsLog };
                            }
                        } catch (e) {
                            attemptsLog.push({ strategy: 'paste_error', msg: String(e) });
                        }
                    }

                    // Strategy 4: Direct .cm-line DOM reconstruction for CodeMirror 6 if paste was blocked
                    if (cmContent && newCode.length > 0) {
                        try {
                            cmContent.focus();
                            cmContent.innerHTML = '';
                            const lines = newCode.split('\n');
                            for (let i = 0; i < lines.length; i++) {
                                const div = document.createElement('div');
                                div.className = 'cm-line';
                                if (lines[i].length === 0) {
                                    div.appendChild(document.createElement('br'));
                                } else {
                                    div.textContent = lines[i];
                                }
                                cmContent.appendChild(div);
                            }
                            cmContent.dispatchEvent(new InputEvent('input', { inputType: 'insertFromPaste', data: newCode, bubbles: true }));
                            cmContent.dispatchEvent(new Event('change', { bubbles: true }));
                            const rb4 = readCurrentCode();
                            attemptsLog.push({ strategy: 'cm_line_dom_rebuild', length: rb4.length });
                            return { engine: 'cm_line_dom_rebuild', attempts: attemptsLog };
                        } catch (e) {}
                    }

                    // Strategy 5: Standard Textarea / React Safe Value
                    if (target && 'value' in target && window.__aiSetReactSafeValue) {
                        window.__aiSetReactSafeValue(target, newCode, newCode, false);
                        return { engine: 'react_safe_textarea', attempts: attemptsLog };
                    }

                    return { engine: 'fallback_failed', attempts: attemptsLog };
                }

                if (action === 'get_code' || action === 'copy') {
                    const code = readCurrentCode();
                    return JSON.stringify({
                        status: 'ok',
                        action: action,
                        actual_length: code.length,
                        lines_count: code ? code.split('\n').length : 0,
                        content: code
                    });
                }

                if (action === 'set_code' || action === 'insert' || action === 'paste') {
                    const writeRes = writeFullCodeAndVerify(rawText);
                    const actual = readCurrentCode();
                    const exactMatch = (actual === rawText);
                    const normalizedMatch = (actual.trim() === rawText.trim());
                    const lengthDiff = Math.abs(actual.length - rawText.length);

                    // Find first mismatch index for debugging if not exact
                    let firstDiffAt = -1;
                    if (!exactMatch) {
                        const minL = Math.min(actual.length, rawText.length);
                        for (let i = 0; i < minL; i++) {
                            if (actual[i] !== rawText[i]) { firstDiffAt = i; break; }
                        }
                        if (firstDiffAt === -1 && actual.length !== rawText.length) firstDiffAt = minL;
                    }

                    return JSON.stringify({
                        status: (exactMatch || normalizedMatch) ? 'verified_exact_match' : 'mismatch_detected',
                        verified: (exactMatch || normalizedMatch),
                        exact_match: exactMatch,
                        normalized_match: normalizedMatch,
                        engine: writeRes.engine,
                        attempts: writeRes.attempts,
                        expected_length: rawText.length,
                        actual_length: actual.length,
                        length_diff: lengthDiff,
                        expected_lines: rawText ? rawText.split('\n').length : 0,
                        actual_lines: actual ? actual.split('\n').length : 0,
                        first_mismatch_index: firstDiffAt,
                        readback_preview_start: actual.slice(0, 200),
                        readback_preview_end: actual.slice(Math.max(0, actual.length - 200)),
                        readback_full: actual
                    });
                }

                if (action === 'clear') {
                    const writeRes = writeFullCodeAndVerify('');
                    const actual = readCurrentCode();
                    return JSON.stringify({
                        status: actual.trim().length === 0 ? 'cleared' : 'unverified',
                        verified: actual.trim().length === 0,
                        actual_length: actual.length
                    });
                }

                return JSON.stringify({ status: 'ok', content: readCurrentCode() });
            })();
        """.trimIndent()

        val rawRes = evalJsSync(js)
        val resObj = try { if (rawRes.startsWith("{")) JSONObject(rawRes) else JSONObject().put("status", "error") } catch (_: Exception) { JSONObject() }
        if (resObj.has("readback_full")) {
            val rbFull = resObj.optString("readback_full", "")
            resObj.put("sha256_expected", sha256Hex(text))
            resObj.put("sha256_actual", sha256Hex(rbFull))
            // Keep response compact if content is huge, but keep full readback up to 4000 chars
            if (rbFull.length > 4000) {
                resObj.put("readback_full", rbFull.take(4000) + "...[truncated]")
            }
        }
        return attachFreshStateAndDirective(resObj, "editor_$action")
    }

    private fun performSelectSync(elementId: Int, value: String, text: String, index: Int): String {
        val safeVal = value.replace("\\", "\\\\").replace("'", "\\'")
        val safeTxt = text.replace("\\", "\\\\").replace("'", "\\'")
        val raw = evalJsSync("""
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
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return JSON.stringify({ status: 'selected', selected_index: chosenIdx, selected_text: el.options[chosenIdx].text });
                }
                return JSON.stringify({ status: 'option_not_found' });
            })();
        """.trimIndent())
        val obj = try { JSONObject(raw) } catch (_: Exception) { JSONObject().put("status", raw) }
        return attachFreshStateAndDirective(obj, "select")
    }

    private fun performHumanTypingSync(elementId: Int, text: String, clear: Boolean, submit: Boolean, instant: Boolean): String {
        // Check if target element is actually GitHub's filename input and contains '/'
        if (text.contains("/") && currentUrl.contains("github.com") && (currentUrl.contains("/new/") || currentUrl.contains("/edit/"))) {
            val isFilenameBox = evalJsSync("""
                (function() {
                    const el = $elementId > 0 ? document.querySelector('[data-ai-id="$elementId"]') : document.activeElement;
                    if (!el) return 'false';
                    const aria = (el.getAttribute('aria-label') || el.getAttribute('placeholder') || el.name || '').toLowerCase();
                    return (aria.includes('file name') || aria.includes('name your file') || aria.includes('filename')) ? 'true' : 'false';
                })();
            """.trimIndent())
            if (isFilenameBox == "true") {
                return performSetGitHubPathSync(text)
            }
        }

        if (elementId > 0) {
            performSmartClickSync(elementId, -1f, -1f)
            Thread.sleep(120)
        } else {
            runOnMainSync { webView.requestFocus() }
        }

        if (clear) {
            evalJsSync("""
                (function() {
                    const el = $elementId > 0 ? document.querySelector('[data-ai-id="$elementId"]') : document.activeElement;
                    if (el && window.__aiSetReactSafeValue) window.__aiSetReactSafeValue(el, '', '', false);
                })();
            """.trimIndent())
        }

        if (instant) {
            val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val resStr = evalJsSync("""
                (function() {
                    const bin = atob('$b64');
                    const bytes = new Uint8Array(bin.length);
                    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                    const decoded = new TextDecoder('utf-8').decode(bytes);
                    const el = $elementId > 0 ? document.querySelector('[data-ai-id="$elementId"]') : document.activeElement;
                    if (el && window.__aiSetReactSafeValue) {
                        window.__aiSetReactSafeValue(el, decoded, decoded, false);
                        const rb = (el.value !== undefined ? el.value : (el.innerText || ''));
                        return JSON.stringify({
                            status: 'typed_instant',
                            expected_length: decoded.length,
                            actual_length: rb.length,
                            verified_input_value: rb,
                            exact_match: rb === decoded
                        });
                    }
                    return '';
                })();
            """.trimIndent())
            if (resStr.startsWith("{")) {
                return attachFreshStateAndDirective(JSONObject(resStr), "type")
            }
        }

        for (i in 0 until text.length) {
            if (isEmergencyStopped.get()) break
            val ch = text[i]
            val safe = ch.toString()
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "")

            evalJsSync("""
                (function() {
                    let el = $elementId > 0 ? document.querySelector('[data-ai-id="$elementId"]') : document.activeElement;
                    if (!el || el === document.body) {
                        el = document.querySelector('[contenteditable="true"], input:focus, textarea:focus, input:not([type="hidden"]), textarea');
                    }
                    if (!el) return;
                    el.focus();
                    el.dispatchEvent(new KeyboardEvent('keydown', { key: '$safe', bubbles: true, cancelable: true }));
                    if (window.__aiSetReactSafeValue) {
                        window.__aiSetReactSafeValue(el, null, '$safe', true);
                    }
                    el.dispatchEvent(new KeyboardEvent('keyup', { key: '$safe', bubbles: true }));
                })();
            """.trimIndent())
            Thread.sleep((20..50).random().toLong())
        }

        val finalRead = evalJsSync("""
            (function() {
                let el = $elementId > 0 ? document.querySelector('[data-ai-id="$elementId"]') : document.activeElement;
                if (!el) return '';
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return (el.value !== undefined ? el.value : (el.innerText || ''));
            })();
        """.trimIndent())

        if (submit) {
            performSpecialKeySync("Enter")
            evalJsSync("(function(){ const el = document.activeElement; if (el && el.form) el.form.submit(); })()")
        }

        val res = JSONObject()
            .put("status", "typed")
            .put("element_id", elementId)
            .put("expected_length", text.length)
            .put("actual_length", finalRead.length)
            .put("verified_input_value", finalRead.take(300))
            .put("exact_match", finalRead == text)
            .put("submitted", submit)
        return attachFreshStateAndDirective(res, "type")
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
        return attachFreshStateAndDirective(JSONObject().put("status", "key_sent").put("key", keyName), "key")
    }

    private fun performSmoothScrollSync(dx: Float, dy: Float, elementId: Int): String {
        evalJsSync("""
            (function() {
                let el = $elementId > 0 ? document.querySelector('[data-ai-id="$elementId"]') : null;
                if (!el) el = document.querySelector('[role="dialog"] .Overlay-body, .cm-scroller, .monaco-scrollable-element');
                if (el) el.scrollBy({ left: $dx, top: $dy, behavior: 'smooth' });
                else window.scrollBy({ left: $dx, top: $dy, behavior: 'smooth' });
            })();
        """.trimIndent())
        Thread.sleep(250)
        return attachFreshStateAndDirective(JSONObject().put("status", "scrolled").put("dx", dx).put("dy", dy), "scroll")
    }

    private fun waitForSelectorSync(selector: String, timeoutMs: Long): String {
        val start = System.currentTimeMillis()
        val safeSel = selector.replace("'", "\\'")
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isEmergencyStopped.get()) break
            val found = evalJsSync("(function(){ return document.querySelector('$safeSel') !== null ? 'true' : 'false'; })()")
            if (found == "true") {
                return attachFreshStateAndDirective(JSONObject().put("status", "found").put("selector", selector), "wait")
            }
            Thread.sleep(250)
        }
        return attachFreshStateAndDirective(JSONObject().put("status", "timeout").put("selector", selector), "wait")
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
            } catch (_: Exception) {}
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

        val res = JSONObject()
            .put("status", "ok")
            .put("ocr_screen_text", ocrFullText)
            .put("ocr_blocks_count", ocrBlocksArray.length())
            .put("ocr_blocks", ocrBlocksArray)
        return attachFreshStateAndDirective(res, "ocr")
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
