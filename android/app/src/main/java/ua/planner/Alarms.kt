package ua.planner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray

object Alarms {
    private fun pi(c: Context, code: Int, title: String): PendingIntent =
        PendingIntent.getBroadcast(
            c, code, Intent(c, AlarmReceiver::class.java).putExtra("t", title),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun schedule(c: Context, json: String) {
        val sp = c.getSharedPreferences("p", 0)
        val am = c.getSystemService(AlarmManager::class.java)
        for (i in 0 until sp.getInt("n", 0)) am.cancel(pi(c, i, ""))
        val show = PendingIntent.getActivity(
            c, 999, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val arr = JSONArray(json)
        val now = System.currentTimeMillis()
        var n = 0
        for (k in 0 until arr.length()) {
            val o = arr.getJSONObject(k)
            val w = o.getLong("w")
            if (w <= now) continue
            am.setAlarmClock(AlarmManager.AlarmClockInfo(w, show), pi(c, n, o.getString("t")))
            n++
        }
        sp.edit().putString("list", json).putInt("n", n).apply()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.startForegroundService(Intent(c, RingService::class.java).putExtra("t", i.getStringExtra("t") ?: "Нагадування"))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val j = c.getSharedPreferences("p", 0).getString("list", null) ?: return
        try { Alarms.schedule(c, j) } catch (e: Exception) { }
    }
}
