package ua.planner

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.content.ContentValues
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
                val acc = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
                // .json у WebView часто має MIME-тип application/json або текстовий: беремо будь-який, а тип перевіряє сайт
                i.type = if (acc.any { it.startsWith("audio") }) "audio/*" else "*/*"
                startActivityForResult(i, 7)
                return true
            }
        }
        web.addJavascriptInterface(Bridge(applicationContext), "Android")
        // Онлайн: завжди свіжа версія сайту (скидаємо кеш сторінки). Офлайн: відкриваємо збережену копію.
        val cm = getSystemService(ConnectivityManager::class.java)
        val online = try {
            cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true } ?: false
        } catch (e: Exception) { true }
        if (online) {
            web.clearCache(true)
            web.settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        } else {
            web.settings.cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
        }
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
                Sync.start(ctx)
            }
        } catch (e: Exception) { }
    }

    // Діагностика фонових сповіщень
    @JavascriptInterface
    fun testAlarm() {
        try { Alarms.test(ctx) } catch (e: Exception) { }
    }

    @JavascriptInterface
    fun status(): String {
        return try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val pm = ctx.getSystemService(PowerManager::class.java)
            val sp = ctx.getSharedPreferences("p", 0)
            JSONObject()
                .put("notif", nm.areNotificationsEnabled())
                .put("full", if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true)
                .put("battery", pm.isIgnoringBatteryOptimizations(ctx.packageName))
                .put("alarms", sp.getInt("n", 0))
                .put("auth", sp.contains("auth"))
                .toString()
        } catch (e: Exception) { "{}" }
    }

    @JavascriptInterface
    fun openBattery() {
        try {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { }
    }

    // Тихі години / «Не турбувати»: зберігаємо й перепланувуємо будильники
    @JavascriptInterface
    fun setQuiet(json: String) {
        try {
            val sp = ctx.getSharedPreferences("p", 0)
            sp.edit().putString("quiet", json).apply()
            Alarms.schedule(ctx, sp.getString("list", "[]") ?: "[]")
        } catch (e: Exception) { }
    }

    @JavascriptInterface
    fun openFullScreen() {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else openAppSettings()
        } catch (e: Exception) { openAppSettings() }
    }

    @JavascriptInterface
    fun openAppSettings() {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { }
    }

    // Резервна копія: зберігаємо файл у «Завантаження»
    @JavascriptInterface
    fun saveFile(name: String, content: String): String {
        return try {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return ""
                ctx.contentResolver.openOutputStream(u)?.use { it.write(content.toByteArray()) }
                "Завантаження/$safe"
            } else {
                val dir = ctx.getExternalFilesDir(null) ?: return ""
                File(dir, safe).writeText(content)
                File(dir, safe).absolutePath
            }
        } catch (e: Exception) { "" }
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
