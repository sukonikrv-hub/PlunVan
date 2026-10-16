package ua.planner

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Повноекранний «будильник»: чорний фон, видно на заблокованому/вимкненому екрані. */
class AlarmActivity : Activity() {
    private var j = JSONObject()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        read(intent)
        build()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
        build()
    }

    private fun read(i: Intent?) {
        j = try { JSONObject(i?.getStringExtra("j") ?: "{}") } catch (e: Exception) { JSONObject() }
        if (!j.has("t")) j.put("t", i?.getStringExtra("t") ?: "Нагадування")
    }

    private fun text(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun build() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(40))
        }
        val w = j.optLong("w", System.currentTimeMillis())
        val d = Date(if (w > 0) w else System.currentTimeMillis())
        val loc = Locale("uk")
        root.addView(text(SimpleDateFormat("HH mm", loc).format(d), 84f).apply { typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL) })
        root.addView(text(SimpleDateFormat("EEE, d MMMM", loc).format(d), 18f, Color.parseColor("#BBBBBB")))
        root.addView(text("⏰ " + j.optString("t"), 26f, Color.WHITE, true).apply { setPadding(0, dp(36), 0, 0) })
        val oneShot = j.has("i") && j.optInt("r", 0) == 0
        val canAct = j.has("i")

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        for ((label, min) in listOf("5 хв" to 5, "15 хв" to 15, "1 год" to 60)) {
            row.addView(pill("Повторити за $label".takeIf { min == 5 } ?: label) { snooze(min) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (min == 5) 2.2f else 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val ok = TextView(this).apply {
            text = "✓"; textSize = 40f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = bg(Color.parseColor("#2E7D32"), 60)
            isClickable = true
            setOnClickListener { done() }
        }
        root.addView(ok, LinearLayout.LayoutParams(dp(110), dp(110)).apply { topMargin = dp(32) })
        root.addView(text(if (canAct && !oneShot) "Виконано (відкриє застосунок)" else "Виконано", 15f, Color.parseColor("#BBBBBB")).apply { setPadding(0, dp(10), 0, 0) })
        setContentView(root)
    }

    private fun pill(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = bg(Color.parseColor("#2B2B2B"), 30)
        setPadding(dp(10), dp(16), dp(10), dp(16))
        isClickable = true
        setOnClickListener { click() }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun stopRing() { stopService(Intent(this, RingService::class.java)) }

    private fun done() {
        val path = j.optString("i")
        if (path.isEmpty() || j.optInt("r", 0) == 1) { openApp(); return } // повторювані задачі завершує сайт (створює наступну)
        stopRing()
        QuickOps.done(applicationContext, path)
        Toast.makeText(this, "Виконано", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun snooze(min: Int) {
        val path = j.optString("i")
        if (path.isEmpty()) { openApp(); return }
        stopRing()
        QuickOps.snooze(applicationContext, path, min, j)
        Toast.makeText(this, "Відкладено на $min хв", Toast.LENGTH_SHORT).show()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* тільки кнопками */ }
}
