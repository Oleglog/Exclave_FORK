package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.view.setPadding
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs

class VKTurnCaptchaActivity : ThemedActivity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    private var webView: WebView? = null
    private var captchaUrl: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.vkturn_captcha_title)

        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!setCaptchaUrl(initialUrl)) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * resources.displayMetrics.density).toInt())
        }
        toolbar.addView(Button(this).apply {
            setText(R.string.vkturn_captcha_open_browser)
            setOnClickListener { openExternalBrowser() }
        })
        toolbar.addView(Button(this).apply {
            setText(R.string.vkturn_captcha_copy_link)
            setOnClickListener {
                SagerNet.clipboard.setPrimaryClip(
                    ClipData.newPlainText("VK TURN captcha", captchaUrl),
                )
            }
        })

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    return false
                }
            }
        }

        root.addView(toolbar)
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        setContentView(root)
        webView?.loadUrl(captchaUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (setCaptchaUrl(url)) {
            webView?.loadUrl(captchaUrl)
        }
    }

    private fun setCaptchaUrl(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Logs.w("[vkturn] invalid captcha url: $url")
            return false
        }
        captchaUrl = url
        return true
    }

    private fun openExternalBrowser() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(captchaUrl)))
        }.onFailure {
            Logs.w("[vkturn] failed to open external captcha browser: ${it.message}")
        }
    }

    override fun onDestroy() {
        webView?.let {
            it.stopLoading()
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
