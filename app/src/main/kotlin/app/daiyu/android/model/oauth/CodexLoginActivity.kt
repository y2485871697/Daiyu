package app.daiyu.android.model.oauth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Original Eta's embedded login + loopback callback, with a one-shot strict PKCE transaction. */
class CodexLoginActivity : ComponentActivity() {
    private val verifier = CodexOAuthProtocol.nonce(64)
    private val state = CodexOAuthProtocol.nonce()
    private var consumed = false
    private var returned = false
    private var web: WebView? = null
    private var server: CodexCallbackServer? = null
    private val timeout = android.os.Handler(android.os.Looper.getMainLooper())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val attempt = intent.getStringExtra("attempt")
        if (savedInstanceState != null || runCatching { UUID.fromString(attempt).toString() == attempt }.getOrDefault(false).not()) { finish(); return }
        CodexOAuthRuntime.initialize(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val close = Button(this).apply { text = "取消登录"; setOnClickListener { finish() } }
        val status = TextView(this).apply { text = "使用 OpenAI Codex 登录"; setPadding(24, 12, 24, 12) }
        val browser = WebView(this)
        web = browser
        layout.addView(close); layout.addView(status); layout.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(layout)
        browser.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            allowFileAccess = false; allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        fun callback(url: String) {
            if (consumed || isFinishing || isDestroyed) return
            val code = try { CodexOAuthProtocol.code(url, state) }
            catch (_: Exception) { status.text = "登录回调无效，请取消后重试"; return }
            consumed = true; server?.close(); browser.stopLoading(); status.text = "正在完成登录"
            lifecycleScope.launch {
                var auth: CodexAuth? = null
                try {
                    withContext(Dispatchers.IO + NonCancellable) { auth = CodexOAuthRuntime.exchange(code, verifier) }
                    if (!isFinishing && !isDestroyed) {
                        setResult(Activity.RESULT_OK, Intent().putExtra("attempt", attempt).putExtra("oauth-reference", checkNotNull(auth).id))
                        returned = true; finish()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { status.text = "登录失败，请取消后重试" }
                finally {
                    if (!returned) auth?.let { unused -> withContext(Dispatchers.IO + NonCancellable) { runCatching { CodexOAuthRuntime.clear(unused) } } }
                }
            }
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (CodexOAuthProtocol.isCallback(url)) { callback(url); return true }
                return request.url.scheme != "https"
            }
            @Deprecated("Legacy WebView callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (CodexOAuthProtocol.isCallback(url)) { callback(url); return true }
                return runCatching { java.net.URI(url).scheme != "https" }.getOrDefault(true)
            }
        }
        server = CodexCallbackServer { url -> runOnUiThread { callback(url) } }.also { it.start() }
        timeout.postDelayed({ if (!returned && !isFinishing) finish() }, 300_000)
        browser.loadUrl(CodexOAuthProtocol.authorizationUrl(verifier, state))
    }
    override fun onDestroy() {
        timeout.removeCallbacksAndMessages(null); server?.close()
        web?.apply { stopLoading(); webViewClient = WebViewClient(); destroy() }; web = null
        super.onDestroy()
    }
}
