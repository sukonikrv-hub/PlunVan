package ua.planner

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var chooser: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.webViewClient = WebViewClient()
        // WebChromeClient потрібен для вікон confirm/alert/prompt і вибору файлу (власний сигнал)
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?
            ): Boolean {
                chooser?.onReceiveValue(null)
                chooser = callback
                val i = Intent(Intent.ACTION_GET_CONTENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "audio/*"
                startActivityForResult(i, 7)
                return true
            }
        }
        web.addJavascriptInterface(Bridge(applicationContext), "Android")
        web.loadUrl(BuildConfig.SITE_URL)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 7) {
            chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            chooser = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
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

    // Дані входу для фонової синхронізації (задачі з інших пристроїв підтягуються й при закритому застосунку)
    @JavascriptInterface
    fun setAuth(json: String) {
        try {
            val sp = ctx.getSharedPreferences("p", 0)
            if (json.isBlank()) {
                sp.edit().remove("auth").apply()
                Sync.cancelJob(ctx)
                Alarms.schedule(ctx, "[]")
            } else {
                sp.edit().putString("auth", json).apply()
                Sync.ensureJob(ctx)
            }
        } catch (e: Exception) { }
    }

    // Обраний на сайті сигнал (data-URL) зберігаємо у файл, його грає сирена
    @JavascriptInterface
    fun setSound(dataUrl: String) {
        try {
            val sp = ctx.getSharedPreferences("p", 0)
            val h = dataUrl.hashCode().toString() + "_" + dataUrl.length
            val f = File(ctx.filesDir, "alarm.bin")
            if (sp.getString("sh", "") == h && f.exists()) return
            f.writeBytes(Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT))
            sp.edit().putString("sh", h).apply()
        } catch (e: Exception) { }
    }
}
