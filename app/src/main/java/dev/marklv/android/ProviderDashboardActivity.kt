package dev.marklv.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONObject

class ProviderDashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showStatus("Starting Jarvis's built-in FreeLLMAPI router…")
        Thread({
            try {
                val token = EmbeddedFreeLLMRuntime.ensureSession(this)
                runOnUiThread { showDashboard(token) }
            } catch (e: Throwable) {
                runOnUiThread {
                    showStatus("FreeLLMAPI could not start.\n\n${e.message ?: EmbeddedFreeLLMRuntime.startupError ?: "Unknown startup error"}")
                }
            }
        }, "Jarvis-provider-dashboard").start()
    }

    private fun showStatus(message: String) {
        setContentView(TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.rgb(16, 17, 20))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
    }

    private fun showDashboard(token: String) {
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val quotedToken = JSONObject.quote(token)
                val script = "(function(){var k='freellmapi_dashboard_token';if(localStorage.getItem(k)!==$quotedToken){localStorage.setItem(k,$quotedToken);location.reload();}})();"
                view.evaluateJavascript(script, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.host == "127.0.0.1" || uri.host == "localhost") return false
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                }.getOrDefault(true)
            }
        }
        setContentView(webView)
        webView.loadUrl("http://127.0.0.1:3001")
    }

    override fun onDestroy() {
        (window.decorView as? ViewGroup)?.let { root ->
            for (i in 0 until root.childCount) {
                (root.getChildAt(i) as? WebView)?.destroy()
            }
        }
        super.onDestroy()
    }
}
