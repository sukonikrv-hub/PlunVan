package ua.planner

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import org.json.JSONArray
import org.json.JSONObject

object Alarms {
    private fun pi(c: Context, code: Int, title: String, pre: Boolean = false, j: String = ""): PendingIntent =
        PendingIntent.getBroadcast(
            c, code, Intent(c, AlarmReceiver::class.java).putExtra("t", title).putExtra("p", pre).putExtra("j", j),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun showIntent(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 999, Intent(c, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun schedule(c: Context, json: String) {
        val sp = c.getSharedPreferences("p", 0)
        val am = c.getSystemService(AlarmManager::class.java)
        for (i in 0 until sp.getInt("n", 0)) am.cancel(pi(c, i, ""))
        val show = showIntent(c)
        val arr = JSONArray(json)
        val now = System.currentTimeMillis()
        var n = 0
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            val w = o.getLong("w")
            if (w <= now) continue
            if (Quiet.isQuiet(c, w)) continue // тихі години / «Не турбувати»: нагадування вимкнені
            am.setAlarmClock(AlarmManager.AlarmClockInfo(w, show), pi(c, n, o.getString("t"), o.optInt("p", 0) == 1, o.toString()))
            n++
        }
        sp.edit().putString("list", json).putInt("n", n).apply()
    }

    /** Тестовий будильник через 1 хвилину (для перевірки фонових сповіщень). */
    fun test(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.setAlarmClock(
            AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 60_000L, showIntent(c)),
            pi(c, 900, "Тест сповіщення")
        )
    }
}

/** Тихі години та «Не турбувати» (налаштування приходять із сайту через Bridge.setQuiet). */
object Quiet {
    private fun hm(s: String): Int {
        val m = Regex("^(\\d{2}):(\\d{2})$").find(s) ?: return -1
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    fun isQuiet(c: Context, ms: Long): Boolean {
        val j = try { JSONObject(c.getSharedPreferences("p", 0).getString("quiet", "{}") ?: "{}") } catch (e: Exception) { return false }
        val a = j.optLong("a", 0L)
        val b = j.optLong("b", 0L)
        if (a > 0 && b > 0 && ms >= a && ms < b) return true
        if (j.optBoolean("on", false)) {
            val f = hm(j.optString("from"))
            val t = hm(j.optString("to"))
            if (f >= 0 && t >= 0 && f != t) {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
                val m = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
                return if (f < t) m in f until t else (m >= f || m < t)
            }
        }
        return false
    }
}

object Notify {
    private val verbs = mapOf(
        "add" to "додано", "edit" to "змінено", "done" to "виконано", "del" to "видалено",
        "res" to "відновлено", "snz" to "відкладено", "cm" to "коментар до", "sub" to "підзадачі в"
    )

    /** Push про зміни, які внесли інші учасники корпоративних календарів. */
    fun changes(c: Context, list: List<Chg>) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("changes", "Зміни в календарях", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            c, 2,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (list.size > 3) {
            nm.notify(
                3000,
                Notification.Builder(c, "changes").setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("🏢 Зміни в календарях")
                    .setContentText("${list.size} змін від інших учасників")
                    .setContentIntent(open).setAutoCancel(true).build()
            )
            return
        }
        for (x in list) {
            val who = x.eb.substringBefore("@")
            nm.notify(
                3001 + (x.et % 500).toInt(),
                Notification.Builder(c, "changes").setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle("🏢 " + x.cal)
                    .setContentText("$who: ${verbs[x.ev] ?: "змінено"} «${x.title}»")
                    .setContentIntent(open).setAutoCancel(true).build()
            )
        }
    }

    /** Звичайне гучне сповіщення в шторці: запасний варіант, якщо сирену запустити не вдалося. */
    fun fallback(c: Context, title: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel("alert", "Нагадування (звичайні)", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        )
        ch.enableVibration(true)
        nm.createNotificationChannel(ch)
        val open = PendingIntent.getActivity(
            c, 1,
            Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(c, "alert")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ $title")
            .setContentText("Натисніть, щоб відкласти або виконати")
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        nm.notify((title.hashCode() and 0xffff) + 10, n)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val t = i.getStringExtra("t") ?: "Нагадування"
        if (i.getBooleanExtra("p", false)) { // попередження «за N хв»: тихіше, без сирени
            Notify.fallback(c, t)
            return
        }
        try {
            c.startForegroundService(Intent(c, RingService::class.java).putExtra("t", t).putExtra("j", i.getStringExtra("j") ?: ""))
        } catch (e: Exception) {
            Notify.fallback(c, t) // сирену запустити не вдалося: хоча б сповіщення в шторці
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val j = c.getSharedPreferences("p", 0).getString("list", null)
        if (j != null) try { Alarms.schedule(c, j) } catch (e: Exception) { }
        try { Sync.start(c) } catch (e: Exception) { }
    }
}
