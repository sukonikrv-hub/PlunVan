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

class RingService : Service() {
    private var mp: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mp != null) return START_NOT_STICKY // вже дзвонить
        val title = intent?.getStringExtra("t") ?: "Нагадування"
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel("ring", "Нагадування", NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(null, null)
        nm.createNotificationChannel(ch)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "ring")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏰ $title")
            .setContentText("Натисніть, щоб відкласти або виконати")
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, n)
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            val afd = resources.openRawResourceFd(R.raw.alarm)
            p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            p.isLooping = true
            p.prepare()
            p.start()
            mp = p
        } catch (e: Exception) { }
        Handler(mainLooper).postDelayed({ stopSelf() }, 10 * 60 * 1000L) // страховка: не довше 10 хв
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try { mp?.stop() } catch (e: Exception) { }
        mp?.release()
        mp = null
        super.onDestroy()
    }
}
