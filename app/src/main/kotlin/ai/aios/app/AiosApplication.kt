package ai.aios.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AiosApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            AgentRunService.CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows the agent's current step while a run is in progress."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
