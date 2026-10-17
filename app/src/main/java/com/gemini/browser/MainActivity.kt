package com.gemini.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class MainActivity : Activity() {

    private lateinit var webContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var overlayView: AIOverlayView
    private lateinit var urlInput: EditText
    private lateinit var consoleText: TextView
    private lateinit var logPanel: TextView
    private lateinit var desktopBtn: Button
    private lateinit var prefs: SharedPreferences

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serverPool: ExecutorService = Executors.newCachedThreadPool()
    private var wakeLock: PowerManager.WakeLock? = null

    private val isEmergencyStopped = AtomicBoolean(false)
    private val commandLogs = LinkedList<String>()
    private val sseClients = CopyOnWriteArrayList<PrintWriter>()

    @Volatile private var desktopMode = false
    @Volatile private var isPageLoading = false
    @Volatile private var isNetworkIdle = true
    @Volatile private var currentUrl = "https://gemini.google.com"
    @Volatile private var currentTitle = ""
    @Volatile private var queuedFilePath: String? = null

    // Pure Chrome Browser User-Agents (Without 'wv' or WebView signature to allow Google OAuth login)
    private val mobileUA = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"
    private val desktopUA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Safari/537.36"

    companion object {
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

        buildUserInterface()
        configureWebView()
        ensureServerRunning()

        webView.loadUrl(currentUrl)
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

    private fun buildUserInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1F1F1F"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
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

        val consoleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        consoleText = TextView(this).apply {
            text = "🟡 Starting Gemini Browser 127.0.0.1:8080..."
            setTextColor(Color.parseColor("#FFD600"))
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnDomPreview = Button(this).apply {
            text = "DOM"
            textSize = 9.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply {
                marginEnd = dp(3)
            }
            setOnClickListener {
                serverPool.execute {
                    val arrStr = extractDomSync(true)
                    try {
                        val arr = JSONArray(arrStr)
                        setConsoleStatus("🟢 DOM Elements: ${arr.length()}", false)
                    } catch (_: Exception) {}
                }
            }
        }

        val btnOcrPreview = Button(this).apply {
            text = "OCR"
            textSize = 9.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#00838F"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply {
                marginEnd = dp(3)
            }
            setOnClickListener {
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
        }

        val btnUserScript = Button(this).apply {
            text = "JS"
            textSize = 9.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#6A1B9A"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply {
                marginEnd = dp(3)
            }
            setOnClickListener {
                showUserScriptDialog()
            }
        }

        val btnLogs = Button(this).apply {
            text = "LOGS"
            textSize = 9.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#37474F"))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply {
                marginEnd = dp(3)
            }
            setOnClickListener {
                logPanel.visibility = if (logPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        val btnStop = Button(this).apply {
            text = "STOP"
            textSize = 9.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#D50000"))
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26))
            setOnClickListener {
                isEmergencyStopped.set(true)
                webView.stopLoading()
                overlayView.clearHighlights()
                setConsoleStatus("🔴 [STOPPED] Kill Switch", true)
                appendLog("EMERGENCY STOP triggered by user")
                broadcastSse("emergency_stop", "User pressed STOP button")
            }
        }

        consoleRow.addView(consoleText)
        consoleRow.addView(btnDomPreview)
        consoleRow.addView(btnOcrPreview)
        consoleRow.addView(btnUserScript)
        consoleRow.addView(btnLogs)
        consoleRow.addView(btnStop)

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

        webView = WebView(this).apply {
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

        webContainer.addView(webView)
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
            text = "[System] Gemini Browser Ready...\n"
        }

        root.addView(navBar)
        root.addView(consoleRow)
        root.addView(webContainer)
        root.addView(logPanel)

        setContentView(root)
    }

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

        if (scripts.length() > 0) {
            val savedHeader = TextView(this).apply {
                text = "Saved Scripts (${scripts.length()}):"
                setTextColor(Color.LTGRAY)
                textSize = 12f
                setPadding(0, 0, 0, dp(4))
            }
            container.addView(savedHeader)
        }

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
                setPadding(dp(6), dp(2), dp(6), dp(2))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(28)).apply { marginEnd = dp(4) }
                setOnClickListener {
                    runSingleUserScriptOnPage(sName, sCode)
                    Toast.makeText(this@MainActivity, "Executed: $sName", Toast.LENGTH_SHORT).show()
                }
            }

            val btnEdit = Button(this).apply {
                text = "EDIT"
                textSize = 9f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#6A1B9A"))
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
            hint = "Script Name"
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
            hint = "// JavaScript code here..."
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
            text = "🗑️ Clear"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#455A64"))
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
            setOnClickListener {
                codeInput.setText("")
            }
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
                    runSingleUserScriptOnPage(nameInput.text.toString().ifEmpty { "TempScript" }, jsCode)
                    Toast.makeText(this@MainActivity, "Script Executed!", Toast.LENGTH_SHORT).show()
                    dialogRef?.dismiss()
                }
            }
        }

        val btnSaveAuto = Button(this).apply {
            text = if (editIndex >= 0) "💾 Update" else "💾 Save & Run"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E7D32"))
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            setOnClickListener {
                val jsCode = codeInput.text.toString().trim()
                if (jsCode.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Code cannot be empty!", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                var sName = nameInput.text.toString().trim()
                if (sName.isEmpty()) {
                    val m = Regex("//\\s*@name\\s+(.+)").find(jsCode)
                    sName = m?.groupValues?.get(1)?.trim() ?: "UserScript #${scripts.length() + 1}"
                }
                val entry = JSONObject()
                    .put("name", sName)
                    .put("code", jsCode)
                    .put("enabled", true)

                if (editIndex in 0 until scripts.length()) {
                    scripts.put(editIndex, entry)
                } else {
                    scripts.put(entry)
                }
                saveUserScripts(scripts)
                runSingleUserScriptOnPage(sName, jsCode)
                Toast.makeText(this@MainActivity, "Saved: $sName", Toast.LENGTH_SHORT).show()
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

    private fun runSingleUserScriptOnPage(name: String, rawCode: String) {
        val b64 = Base64.encodeToString(rawCode.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val safeName = name.replace("'", "\\'")
        val js = """
            (function() {
                try {
                    const bin = atob('$b64');
                    const bytes = new Uint8Array(bin.length);
                    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
                    const decodedCode = new TextDecoder('utf-8').decode(bytes);
                    const fn = new Function(decodedCode);
                    fn();
                    console.log('[GeminiBrowser UserScript] $safeName executed');
                } catch (e) {
                    console.error('[GeminiBrowser UserScript Error] $safeName:', e);
                }
            })();
        """.trimIndent()
        mainHandler.post {
            webView.evaluateJavascript(js, null)
            appendLog("UserScript ran: $name")
        }
    }

    private fun injectSavedUserScripts(url: String) {
        val scripts = getSavedUserScripts()
        for (i in 0 until scripts.length()) {
            val obj = scripts.optJSONObject(i) ?: continue
            if (obj.optBoolean("enabled", true)) {
                val name = obj.optString("name", "Script #$i")
                val code = obj.optString("code", "")
                if (code.isNotEmpty() && scriptMatchesUrl(code, url)) {
                    runSingleUserScriptOnPage(name, code)
                }
            }
        }
    }

    private fun updateWebViewViewportBounds() {
        val cw = webContainer.width
        val ch = webContainer.height
        if (cw <= 0 || ch <= 0) return

        if (desktopMode) {
            val density = resources.displayMetrics.density
            val targetCssWidth = 1280f
            val targetPxW = (targetCssWidth * density).toInt()
            val scale = cw.toFloat() / targetPxW.toFloat()
            val targetPxH = (ch.toFloat() / scale).toInt()

            webView.pivotX = 0f
            webView.pivotY = 0f
            webView.scaleX = scale
            webView.scaleY = scale

            val lp = webView.layoutParams as FrameLayout.LayoutParams
            if (lp.width != targetPxW || lp.height != targetPxH) {
                lp.width = targetPxW
                lp.height = targetPxH
                webView.layoutParams = lp
            }
        } else {
            webView.pivotX = 0f
            webView.pivotY = 0f
            webView.scaleX = 1f
            webView.scaleY = 1f

            val lp = webView.layoutParams as FrameLayout.LayoutParams
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                webView.layoutParams = lp
            }
        }
    }

    private fun applyDesktopMode(enable: Boolean) {
        desktopMode = enable
        runOnUiThread {
            desktopBtn.text = if (desktopMode) "PC" else "MOB"
            desktopBtn.setBackgroundColor(if (desktopMode) Color.parseColor("#1565C0") else Color.parseColor("#333333"))
            webView.settings.userAgentString = if (desktopMode) desktopUA else mobileUA
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
            updateWebViewViewportBounds()
            webView.reload()
        }
        appendLog("Desktop Mode -> $desktopMode")
    }

    private fun makeSmallButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
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

    private fun configureWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.userAgentString = mobileUA

        // Enable cookies completely for Google Accounts session
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onIdle(url: String) {
                isNetworkIdle = true
                broadcastSse("network_idle", url)
                if (serverStarted.get()) {
                    setConsoleStatus("🟢 ONLINE 127.0.0.1:8080 | Ready", false)
                }
            }

            @JavascriptInterface
            fun logAllowClicked(msg: String) {
                appendLog("⚡ Auto-Allowed: $msg")
            }
        }, "AIAgentBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isPageLoading = true
                isNetworkIdle = false
                currentUrl = url ?: ""
                urlInput.setText(currentUrl)
                injectStealthAndAutoAllowWatcher()
                if (serverStarted.get()) {
                    setConsoleStatus("🟢 127.0.0.1:8080 | Loading...", false)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isPageLoading = false
                currentUrl = url ?: ""
                currentTitle = view?.title ?: ""
                urlInput.setText(currentUrl)
                CookieManager.getInstance().flush()
                injectStealthAndAutoAllowWatcher()
                injectSavedUserScripts(currentUrl)
                broadcastSse("page_loaded", currentUrl)
                if (serverStarted.get()) {
                    setConsoleStatus("🟢 ONLINE 127.0.0.1:8080 | Ready", false)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                appendLog("Alert: $message")
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
                        appendLog("Auto-Uploaded: ${f.name}")
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

    // Google Login Bypass Stealth + Auto Click "Allow" Watcher
    private fun injectStealthAndAutoAllowWatcher() {
        val isPc = if (desktopMode) "true" else "false"
        val js = """
            (function() {
                try {
                    // 1. Google OAuth & Anti-Bot Detection Bypass
                    Object.defineProperty(navigator, 'webdriver', { get: () => false });
                    if (!window.chrome) {
                        window.chrome = {
                            runtime: {},
                            loadTimes: function() {},
                            csi: function() {},
                            app: {}
                        };
                    }
                    if ($isPc) {
                        Object.defineProperty(navigator, 'platform', { get: () => 'Linux x86_64', configurable: true });
                        Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0, configurable: true });
                    }
                } catch (e) {}

                // 2. Auto Click "Allow" Watcher for Gemini MCP Tool Confirmations
                if (!window.__aiAllowWatcherRunning) {
                    window.__aiAllowWatcherRunning = true;

                    function triggerAutoAllow() {
                        try {
                            const buttons = Array.from(document.querySelectorAll('button, div[role="button"], span[role="button"]'));
                            for (let b of buttons) {
                                const text = (b.innerText || b.textContent || '').trim();
                                if (text.toLowerCase() === 'allow') {
                                    const rect = b.getBoundingClientRect();
                                    if (rect.width > 0 && rect.height > 0 && !b.disabled) {
                                        b.click();
                                        if (window.AIAgentBridge && window.AIAgentBridge.logAllowClicked) {
                                            window.AIAgentBridge.logAllowClicked('Tool Call Allow Button Clicked');
                                        }
                                        break;
                                    }
                                }
                            }
                        } catch (e) {}
                    }

                    // Continuous observer to immediately catch popup rendering
                    const allowObserver = new MutationObserver((mutations) => {
                        triggerAutoAllow();
                    });

                    if (document.body) {
                        allowObserver.observe(document.body, { childList: true, subtree: true });
                    }
                    setInterval(triggerAutoAllow, 400);
                }

                // Smart In-DOM <select> Modal Builder
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
                if (origSend) {
                    XMLHttpRequest.prototype.send = function() {
                        window.__aiPendingReq++;
                        this.addEventListener('loadend', function() {
                            window.__aiPendingReq = Math.max(0, window.__aiPendingReq - 1);
                            checkIdle();
                        });
                        return origSend.apply(this, arguments);
                    };
                }

                checkIdle();
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun ensureServerRunning() {
        if (serverStarted.get() && serverSocket != null && !serverSocket!!.isClosed) {
            setConsoleStatus("🟢 ONLINE 127.0.0.1:8080 | Ready", false)
            return
        }

        serverPool.execute {
            try {
                serverSocket?.close()
            } catch (_: Exception) {}

            try {
                val sock = ServerSocket()
                sock.reuseAddress = true
                sock.bind(InetSocketAddress("0.0.0.0", 8080), 50)
                serverSocket = sock
                serverStarted.set(true)

                setConsoleStatus("🟢 ONLINE 127.0.0.1:8080 | Ready", false)
                appendLog("Server listening on 0.0.0.0:8080")

                while (serverStarted.get() && !sock.isClosed) {
                    val client = sock.accept()
                    serverPool.execute {
                        val inst = activeInstance ?: this@MainActivity
                        inst.handleClientSocket(client)
                    }
                }
            } catch (e: Exception) {
                serverStarted.set(false)
                setConsoleStatus("🔴 SERVER ERROR: ${e.message}", true)
                appendLog("Server error: ${e.message}")
            }
        }
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
                try { JSONObject(bodyStr) } catch (_: Exception) { null }
            } else null

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

    private fun executeCommand(method: String, path: String, body: JSONObject?): String {
        isEmergencyStopped.set(false)
        appendLog("$method $path")
        setConsoleStatus("🟢 CMD: $method $path", false)

        return when (path) {
            "/", "/status" -> {
                JSONObject()
                    .put("status", "online")
                    .put("name", "Gemini Browser")
                    .put("port", 8080)
                    .put("url", currentUrl)
                    .put("title", currentTitle)
                    .put("loading", isPageLoading)
                    .put("network_idle", isNetworkIdle)
                    .put("desktop_mode", desktopMode)
                    .put("userscripts_count", getSavedUserScripts().length())
                    .toString()
            }

            "/desktop" -> {
                val enable = body?.optBoolean("enable", !desktopMode) ?: !desktopMode
                applyDesktopMode(enable)
                JSONObject().put("status", "ok").put("desktop_mode", desktopMode).toString()
            }

            "/navigate" -> {
                val targetUrl = body?.optString("url", "https://gemini.google.com") ?: "https://gemini.google.com"
                val waitIdle = body?.optBoolean("wait_idle", true) ?: true
                if (body?.has("desktop_mode") == true) {
                    val wantPc = body.optBoolean("desktop_mode", desktopMode)
                    if (wantPc != desktopMode) applyDesktopMode(wantPc)
                }
                isPageLoading = true
                isNetworkIdle = false
                runOnMainSync {
                    webView.loadUrl(targetUrl)
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

            "/editor" -> {
                performCodeEditorActionSync(body ?: JSONObject())
            }

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

            "/userscript", "/eval" -> {
                handleUserScriptApiSync(body ?: JSONObject())
            }

            "/wait" -> {
                val selector = body?.optString("selector", "") ?: ""
                val timeoutMs = body?.optLong("timeout_ms", 10000L) ?: 10000L
                waitForSelectorSync(selector, timeoutMs)
            }

            "/upload" -> {
                queuedFilePath = body?.optString("file_path", null)
                JSONObject()
                    .put("status", "queued")
                    .put("file_path", queuedFilePath)
                    .toString()
            }

            else -> {
                JSONObject()
                    .put("status", "error")
                    .put("message", "Unknown endpoint: $path")
                    .toString()
            }
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
            "list" -> {
                JSONObject().put("status", "ok").put("scripts", getSavedUserScripts()).toString()
            }
            "add", "save" -> {
                val arr = getSavedUserScripts()
                val obj = JSONObject().put("name", name).put("code", code).put("enabled", true)
                arr.put(obj)
                saveUserScripts(arr)
                runSingleUserScriptOnPage(name, code)
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
                const selectors = 'a, button, input, select, textarea, [role="button"], [role="option"], [role="menuitem"], [role="textbox"], [contenteditable="true"], .monaco-editor, .cm-content';
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

                        let rawLabel = (el.innerText || el.value || el.placeholder || el.getAttribute('aria-label') || el.name || '').replace(/\s+/g, ' ').trim();

                        const itemObj = {
                            id: id,
                            tag: tag,
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
                        list.push(itemObj);
                    }
                });
                return JSON.stringify(list);
            })();
        """.trimIndent()

        val jsonStr = evalJsSync(js)
        if (showBoxes && jsonStr.startsWith("[")) {
            try {
                val arr = JSONArray(jsonStr)
                val boxes = mutableListOf<Pair<RectF, String>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val rect = RectF(
                        o.getDouble("left").toFloat(),
                        o.getDouble("top").toFloat(),
                        o.getDouble("right").toFloat(),
                        o.getDouble("bottom").toFloat()
                    )
                    boxes.add(Pair(rect, "[${o.getInt("id")}]"))
                }
                runOnMainSync {
                    overlayView.showDomBoxes(boxes)
                }
            } catch (_: Exception) {}
        }
        return if (jsonStr.isNotEmpty()) jsonStr else "[]"
    }

    private fun performSmartClickSync(elementId: Int, rawX: Float, rawY: Float, matchText: String = ""): String {
        val overlayW = overlayView.width.coerceAtLeast(1)
        val wvW = webView.width.coerceAtLeast(1)
        val wvScaleRatio = wvW.toFloat() / overlayW.toFloat()

        if (matchText.isNotEmpty()) {
            val safeTarget = matchText.replace("\\", "\\\\").replace("'", "\\'")
            val textClickRes = evalJsSync("""
                (function() {
                    const q = '$safeTarget'.trim().toLowerCase();
                    const nodes = document.querySelectorAll('button, a, [role="button"], span, div');
                    for (let i = 0; i < nodes.length; i++) {
                        const el = nodes[i];
                        const t = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
                        if (t === q) {
                            el.scrollIntoView({ behavior: 'instant', block: 'center' });
                            el.click();
                            return JSON.stringify({ status: 'clicked_by_text', text: el.innerText.trim() });
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
            Thread.sleep(120)
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

        val wvTouchX = screenX * wvScaleRatio
        val wvTouchY = screenY * wvScaleRatio

        val holdMs = (50..100).random().toLong()
        runOnMainSync {
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
                    if (el) el.focus();
                })();
            """.trimIndent())
        }

        Thread.sleep(holdMs)
        return JSONObject()
            .put("status", "clicked")
            .put("element_id", elementId)
            .put("x", screenX)
            .put("y", screenY)
            .toString()
    }

    private fun performSelectSync(elementId: Int, value: String, text: String, index: Int): String {
        val safeVal = value.replace("\\", "\\\\").replace("'", "\\'")
        val safeTxt = text.replace("\\", "\\\\").replace("'", "\\'")
        val res = evalJsSync("""
            (function() {
                let el = $elementId > 0 ? document.querySelector('[data-ai-id="$elementId"]') : document.querySelector('select');
                if (!el || el.tagName.toLowerCase() !== 'select') return JSON.stringify({ status: 'error', message: 'Select not found' });
                
                let chosenIdx = $index;
                if (chosenIdx < 0) {
                    for (let i = 0; i < el.options.length; i++) {
                        const ot = (el.options[i].text || '').toLowerCase();
                        if (ot.includes('$safeTxt'.toLowerCase())) { chosenIdx = i; break; }
                    }
                }
                if (chosenIdx >= 0 && chosenIdx < el.options.length) {
                    el.selectedIndex = chosenIdx;
                    el.value = el.options[chosenIdx].value;
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    return JSON.stringify({ status: 'selected', index: chosenIdx });
                }
                return JSON.stringify({ status: 'option_not_found' });
            })();
        """.trimIndent())
        return res
    }

    private fun performHumanTypingSync(elementId: Int, text: String, clear: Boolean, submit: Boolean, instant: Boolean): String {
        if (elementId > 0) {
            performSmartClickSync(elementId, -1f, -1f)
            Thread.sleep(120)
        }

        if (clear) {
            performCodeEditorActionSync(JSONObject().put("action", "clear"))
        }

        if (instant) {
            return performCodeEditorActionSync(JSONObject().put("action", "insert").put("text", text))
        }

        for (ch in text) {
            if (isEmergencyStopped.get()) break
            val safe = ch.toString().replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
            val js = """
                (function() {
                    let el = document.activeElement;
                    if (!el || el === document.body) {
                        el = document.querySelector('input:focus, textarea:focus, [contenteditable="true"]');
                    }
                    if (!el) return;
                    if (el.isContentEditable) {
                        document.execCommand('insertText', false, '$safe');
                    } else if ('value' in el) {
                        el.value = (el.value || '') + '$safe';
                        el.dispatchEvent(new InputEvent('input', { data: '$safe', bubbles: true }));
                    }
                })();
            """.trimIndent()
            evalJsSync(js)
            Thread.sleep((25..65).random().toLong())
        }

        if (submit) {
            performSpecialKeySync("Enter")
        }

        return JSONObject().put("status", "typed").put("length", text.length).toString()
    }

    private fun performCodeEditorActionSync(body: JSONObject): String {
        val action = body.optString("action", "get_code").lowercase(Locale.ROOT)
        val text = body.optString("text", "")
        val b64Text = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val js = """
            (function() {
                const action = '$action';
                const rawText = atob('$b64Text');
                let target = document.activeElement;
                if (!target || target === document.body) {
                    target = document.querySelector('[contenteditable="true"], textarea, input');
                }
                if (!target) return JSON.stringify({ status: 'no_target' });

                if (action === 'clear') {
                    if (target.isContentEditable) target.innerText = '';
                    else target.value = '';
                    target.dispatchEvent(new Event('input', { bubbles: true }));
                    return JSON.stringify({ status: 'cleared' });
                }

                if (action === 'insert' || action === 'paste' || action === 'set_code') {
                    if (target.isContentEditable) {
                        target.focus();
                        document.execCommand('insertText', false, rawText);
                    } else {
                        target.value = (action === 'set_code' ? rawText : (target.value + rawText));
                        target.dispatchEvent(new Event('input', { bubbles: true }));
                    }
                    return JSON.stringify({ status: 'ok', length: rawText.length });
                }

                return JSON.stringify({ status: 'ok', content: (target.value || target.innerText || '') });
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
                window.scrollBy({ left: $dx, top: $dy, behavior: 'smooth' });
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
            if (found == "true") {
                return JSONObject().put("status", "found").put("selector", selector).toString()
            }
            Thread.sleep(200)
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
                                    val cx = (left + right) / 2
                                    val cy = (top + bottom) / 2
                                    val id = blockId++

                                    ocrBlocksArray.put(
                                        JSONObject()
                                            .put("id", id)
                                            .put("text", txt)
                                            .put("x", cx)
                                            .put("y", cy)
                                    )
                                    overlayBoxes.add(
                                        Pair(
                                            RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()),
                                            "T$id"
                                        )
                                    )
                                }
                            }
                        }
                        latch.countDown()
                    }
                    .addOnFailureListener {
                        latch.countDown()
                    }
                latch.await(8, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }

        if (showBoxes && overlayBoxes.isNotEmpty()) {
            runOnMainSync {
                overlayView.showDomBoxes(overlayBoxes)
            }
        }

        return JSONObject()
            .put("status", "ok")
            .put("url", currentUrl)
            .put("title", currentTitle)
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
