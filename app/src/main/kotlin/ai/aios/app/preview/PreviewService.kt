package ai.aios.app.preview

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PreviewService : LifecycleService() {
    private var runJob: Job? = null
    private var statusJob: Job? = null
    private var activeToken: String? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "AIS: работа агента и остановка", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == STOP) {
            PreviewSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("token")
        val goal = intent?.getStringExtra("goal")
        if (id == null || goal.isNullOrBlank() || !PreviewSession.accepts(id)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (runJob?.isActive == true) return START_NOT_STICKY
        activeToken = id
        try { publish("Запуск…") } catch (_: Exception) {
            PreviewSession.failedToStart(id)
            stopSelf()
            return START_NOT_STICKY
        }
        statusJob = lifecycleScope.launch {
            PreviewSession.status.collect { publish(it) }
        }
        runJob = lifecycleScope.launch {
            try { PreviewSession.run(applicationContext, goal, id) } finally {
                statusJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        statusJob?.cancel()
        runJob?.cancel()
        if (activeToken?.let { PreviewSession.accepts(it) } == true) PreviewSession.stop()
        super.onDestroy()
    }

    private fun publish(status: String) {
        val open = PendingIntent.getActivity(this, 10,
            Intent(this, PreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 11, Intent(this, PreviewService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("AIS • мобильный агент")
            .setContentText(status)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION, notification)
    }

    companion object {
        private const val CHANNEL = "ais_preview_runs"
        private const val NOTIFICATION = 2401
        private const val STOP = "ai.aios.app.PREVIEW_STOP"

        fun start(context: Context, goal: String) {
            val clean = goal.trim()
            if (clean.isEmpty() || clean.length > 4_000) {
                PreviewSession.tell("Запрос должен содержать от 1 до 4000 символов.")
                return
            }
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                PreviewSession.tell("Разрешите уведомления AIS, чтобы кнопка остановки была доступна вне приложения. Агент не запущен.")
                return
            }
            val id = PreviewSession.reserve() ?: return
            try {
                context.startForegroundService(Intent(context, PreviewService::class.java)
                    .putExtra("goal", clean).putExtra("token", id))
            } catch (_: Exception) { PreviewSession.failedToStart(id) }
        }
    }
}
