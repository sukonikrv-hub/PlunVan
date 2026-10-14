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

object Alarms {
    private fun pi(c: Context, code: Int, title: String, pre: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(
            c, code, Intent(c, AlarmReceiver::class.java).putExtra("t", title).putExtra("p", pre),
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
            am.setAlarmClock(AlarmManager.AlarmClockInfo(w, show), pi(c, n, o.getString("t"), o.optInt("p", 0) == 1))
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

object Notify {
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
            c.startForegroundService(Intent(c, RingService::class.java).putExtra("t", t))
        } catch (e: Exception) {
            Notify.fallback(c, t) // сирену запустити не вдалося: хоча б сповіщення в шторці
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val j = c.getSharedPreferences("p", 0).getString("list", null) ?: return
        try { Alarms.schedule(c, j) } catch (e: Exception) { }
    }
}
