package org.darelihsan.parents

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject

/**
 * Standalone container for the parents' space. It only ever shows BuildConfig.PARENT_URL
 * (see UrlPolicy), keeps the Supabase session in the Keystore and wires FCM.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var offline: LinearLayout
    private lateinit var store: SessionStore
    private lateinit var prefs: Prefs
    private var scriptHandler: ScriptHandler? = null
    private var pendingRoute: String? = null
    private val startUrl = BuildConfig.PARENT_URL
    private val origin = UrlPolicy.origin(BuildConfig.PARENT_URL)

    private val askNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        prefs = Prefs(this)
        Notify.ensureChannel(this)
        onTokenChanged = { runOnUiThread { reinstallScript() } }

        web = WebView(this)
        offline = buildOfflineView()
        setContentView(FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(-1, -1))
            addView(offline, FrameLayout.LayoutParams(-1, -1))
        })
        offline.visibility = View.GONE

        if (origin == null || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            // Fail closed: without these the secure session bridge cannot be restricted to our origin.
            showMessage("يلزم تحديث «Android System WebView» أو «Chrome» من متجر Google Play ثم إعادة فتح التطبيق.")
            return
        }

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        WebViewCompat.addWebMessageListener(web, "DEBridge", setOf(origin)) { _, message, sourceOrigin, isMainFrame, _ ->
            if (isMainFrame && sourceOrigin.toString() == origin) handleBridge(message.data)
        }
        reinstallScript()

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                route(request.url.toString())

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                // Last line of defence for navigations that did not pass through shouldOverrideUrlLoading.
                if (UrlPolicy.decide(startUrl, url) == UrlPolicy.Decision.BLOCK) { view.stopLoading(); view.loadUrl(startUrl) }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                offline.visibility = View.GONE
                pendingRoute?.let { r -> openTab(r); pendingRoute = null }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) offline.visibility = View.VISIBLE
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (offline.visibility == View.VISIBLE) { moveTaskToBack(true); return }
                if (web.canGoBack()) web.goBack() else moveTaskToBack(true)   // never leaves the parents' space
            }
        })

        handleIntent(intent)
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(startUrl)
        fetchFcmToken()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        pendingRoute?.let { openTab(it); pendingRoute = null }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onDestroy() {
        if (onTokenChanged != null) onTokenChanged = null
        super.onDestroy()
    }

    // ---- navigation policy ----
    private fun route(url: String): Boolean {
        return when (UrlPolicy.decide(startUrl, url)) {
            UrlPolicy.Decision.ALLOW -> false
            UrlPolicy.Decision.EXTERNAL -> { openExternal(url); true }
            UrlPolicy.Decision.BLOCK -> {
                // Stay inside the parents' space (never show the public site or the admin panel).
                if (web.url == null || UrlPolicy.decide(startUrl, web.url) == UrlPolicy.Decision.BLOCK) web.loadUrl(startUrl)
                true
            }
        }
    }

    private fun openExternal(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)) } catch (_: Exception) {}
    }

    // ---- session bridge ----
    private fun handleBridge(data: String?) {
        if (data == null || data.length > 20_000) return
        val o = try { JSONObject(data) } catch (_: Exception) { return }
        when (o.optString("t")) {
            "session" -> {
                if (store.save(o.optString("v"))) { requestNotifPermissionOnce() }
                reinstallScript()
            }
            "cleared" -> { store.clear(); reinstallScript() }
        }
    }

    /** (Re)registers the document-start script with the current stored session / FCM token. */
    private fun reinstallScript() {
        val o = origin ?: return
        scriptHandler?.remove()
        val seed = store.load()
        val pre = "var __DE=" + JSONObject()
            .put("seed", seed ?: JSONObject.NULL)
            .put("installId", prefs.installId)
            .put("fcm", prefs.fcmToken ?: JSONObject.NULL)
            .put("ver", BuildConfig.VERSION_NAME)
            .toString().replace("</", "<\\/") + ";\n"
        val js = assets.open("inject.js").bufferedReader().use { it.readText() }
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(web, pre + js, setOf(o))
    }

    // ---- notifications ----
    private fun fetchFcmToken() {
        if (!BuildConfig.FIREBASE_ENABLED) return
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
                if (t != null && t != prefs.fcmToken) { prefs.fcmToken = t; reinstallScript(); web.evaluateJavascript("location.reload()", null) }
                else if (t != null) prefs.fcmToken = t
            }
        } catch (_: Exception) { /* Firebase not initialised: notifications stay off */ }
    }

    private fun requestNotifPermissionOnce() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !Notify.canPost(this) && !prefs.askedNotifPermission) {
            prefs.askedNotifPermission = true
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleIntent(i: Intent?) {
        val r = i?.getStringExtra(Notify.EXTRA_ROUTE)
        if (r != null && r in Notify.ROUTES) pendingRoute = r
    }

    private fun openTab(route: String) {
        // route is validated against a fixed allowlist, so it is safe to embed in the script.
        if (route in Notify.ROUTES) web.evaluateJavascript("window.__deOpenTab&&window.__deOpenTab('$route')", null)
    }

    // ---- small native screens ----
    private fun buildOfflineView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER
        setBackgroundColor(0xFFF9F5F1.toInt())
        setPadding(64, 64, 64, 64)
        addView(TextView(context).apply { text = getString(R.string.offline_title); textSize = 20f; gravity = android.view.Gravity.CENTER })
        addView(TextView(context).apply { text = getString(R.string.offline_body); gravity = android.view.Gravity.CENTER; setPadding(0, 24, 0, 24) })
        addView(Button(context).apply { text = getString(R.string.retry); setOnClickListener { offline.visibility = View.GONE; web.loadUrl(startUrl) } })
    }

    private fun showMessage(msg: String) {
        offline.removeAllViews()
        offline.addView(TextView(this).apply { text = msg; gravity = android.view.Gravity.CENTER; textSize = 18f })
        offline.visibility = View.VISIBLE
    }

    companion object {
        @Volatile var onTokenChanged: (() -> Unit)? = null
    }
}
