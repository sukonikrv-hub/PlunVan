package ua.planner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        web.addJavascriptInterface(Bridge(applicationContext), "Android")
        web.loadUrl(BuildConfig.SITE_URL)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}

class Bridge(private val ctx: Context) {
    @JavascriptInterface
    fun setAlarms(json: String) {
        try { Alarms.schedule(ctx, json) } catch (e: Exception) { }
    }

    @JavascriptInterface
    fun setRinging(on: Boolean) {
        val i = Intent(ctx, RingService::class.java)
        try {
            if (on) ctx.startForegroundService(i.putExtra("t", "Нагадування")) else ctx.stopService(i)
        } catch (e: Exception) { }
    }
}
