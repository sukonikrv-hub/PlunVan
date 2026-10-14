package ua.planner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File

class RingService : Service() {
    private var mp: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null
    private var title = "Нагадування"

    override fun onBind(intent: Intent?): IBinder? = null

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mp != null) return START_NOT_STICKY // вже дзвонить
        title = intent?.getStringExtra("t") ?: "Нагадування"
        val nm = getSystemService(NotificationManager::class.java)
        val ring = NotificationChannel("ring", "Нагадування (сирена)", NotificationManager.IMPORTANCE_HIGH)
        ring.setSound(null, null)
        nm.createNotificationChannel(ring)
        nm.createNotificationChannel(NotificationChannel("missed", "Пропущені нагадування", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, "ring")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ $title")
            .setContentText("Натисніть, щоб відкласти або виконати")
            .setContentIntent(openApp())
            .setFullScreenIntent(openApp(), true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(1, n)
        } catch (e: Exception) {
            Notify.fallback(this, title)
            stopSelf()
            return START_NOT_STICKY
        }
        val custom = File(filesDir, "alarm.bin")
        mp = try {
            player(custom.exists() && custom.length() > 0).also { it.start() }
        } catch (e: Exception) {
            try { player(false).also { it.start() } } catch (x: Exception) { null }
        }
        // Якщо ніхто не відреагував 10 хв: вимикаємо звук, але сповіщення ЛИШАЄТЬСЯ в шторці
        val r = Runnable { postMissed(); stopSelf() }
        timeout = r
        handler.postDelayed(r, 10 * 60 * 1000L)
        return START_NOT_STICKY
    }

    private fun postMissed() {
        val nm = getSystemService(NotificationManager::class.java)
        val n = Notification.Builder(this, "missed")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ Пропущене нагадування")
            .setContentText(title + " — натисніть, щоб відкласти або виконати")
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        nm.notify(2, n)
    }

    private fun player(custom: Boolean): MediaPlayer {
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        )
        if (custom) {
            p.setDataSource(File(filesDir, "alarm.bin").absolutePath)
        } else {
            val afd = resources.openRawResourceFd(R.raw.alarm)
            p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
        }
        p.isLooping = true
        p.prepare()
        return p
    }

    override fun onDestroy() {
        timeout?.let { handler.removeCallbacks(it) }
        try { mp?.stop() } catch (e: Exception) { }
        mp?.release()
        mp = null
        super.onDestroy()
    }
}
