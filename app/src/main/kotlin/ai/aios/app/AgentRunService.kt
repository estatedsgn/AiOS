package ai.aios.app

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ai.aios.app.device.AiosAccessibilityService
import ai.aios.app.preview.PreviewSession
import ai.aios.app.ui.MainActivity
import ai.aios.core.agent.*
import ai.aios.core.device.AgentAction
import ai.aios.core.planner.ClaudePlanner
import ai.aios.core.planner.PlannerConfig
import ai.aios.core.preview.FreshScreenDevice
import ai.aios.core.safety.ActionPolicy
import ai.aios.core.safety.AutonomyMode
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import kotlinx.coroutines.*
import java.time.Duration

/** Separate, conservative screen lab. The main assistant never silently falls back into it. */
class AgentRunService : LifecycleService() {
    private var runJob: Job? = null
    private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopping = true
            runJob?.cancel()
            if (runJob == null) finish()
            return START_NOT_STICKY
        }
        val goal = intent?.getStringExtra(EXTRA_GOAL)?.takeIf { it.isNotBlank() }
        if (goal == null || !occupied || stopping) {
            if (runJob == null) finish()
            return START_NOT_STICKY
        }
        if (runJob != null) return START_NOT_STICKY
        try { notify("Запуск экспериментального режима") } catch (_: Exception) { finish(); return START_NOT_STICKY }
        runJob = lifecycleScope.launch {
            try {
                val settings = withContext(Dispatchers.IO) { AgentSettings(this@AgentRunService) }
                val device = AiosAccessibilityService.instance
                if (device == null || !settings.hasApiKey || PreviewSession.busy.value) {
                    AgentSession.onEvent(AgentEvent.Failed("Нужны ключ, вручную включённый Accessibility и отсутствие другого запуска."))
                    return@launch
                }
                val consent = getSharedPreferences("ais_preview_ui", Context.MODE_PRIVATE).getBoolean("screen_consent", false)
                if (!consent) {
                    AgentSession.onEvent(AgentEvent.Failed("Сначала подтвердите передачу текста экрана модели в экспериментальном режиме."))
                    return@launch
                }
                val client = AnthropicOkHttpClient.builder().apiKey(settings.apiKey)
                    .timeout(Duration.ofSeconds(45)).maxRetries(0).build()
                try {
                    val human = object : HumanInterface {
                        override suspend fun requestApproval(action: AgentAction, reason: String): Approval {
                            val decision = AgentSession.requestApproval(action, reason)
                            if (decision == Approval.REJECTED) throw CancellationException("Rejected by owner")
                            currentCoroutineContext().ensureActive()
                            return decision
                        }
                        override suspend fun askQuestion(question: String): String? = AgentSession.askQuestion(question)
                    }
                    val runner = AgentRunner(
                        device = FreshScreenDevice(device),
                        planner = ClaudePlanner(client, PlannerConfig(model = settings.model)),
                        // Legacy AUTONOMOUS settings cannot bypass preview approvals.
                        policy = ActionPolicy(mode = AutonomyMode.CONFIRM_EVERYTHING),
                        human = human,
                        config = RunnerConfig(maxSteps = 8),
                    )
                    withTimeout(180_000) {
                        runner.run(goal).collect { event ->
                            AgentSession.onEvent(event)
                            when (event) {
                                is AgentEvent.AwaitingApproval -> notify("Вернитесь в AIS: требуется подтверждение")
                                is AgentEvent.QuestionAsked -> notify("Агент ждёт ваш ответ")
                                is AgentEvent.Thinking -> notify("Шаг ${event.step}: анализ экрана")
                                else -> Unit
                            }
                        }
                    }
                } finally { withContext(NonCancellable + Dispatchers.IO) { runCatching { client.close() } } }
            } catch (_: TimeoutCancellationException) {
                AgentSession.onEvent(AgentEvent.Failed("Время ожидания истекло. Проверьте экран перед повтором."))
            } catch (_: CancellationException) {
                AgentSession.onEvent(AgentEvent.Cancelled)
            } catch (_: Exception) {
                AgentSession.onEvent(AgentEvent.Failed("Не удалось выполнить запрос. Проверьте сеть, ключ и модель. Автоматического повтора нет."))
            } finally { finish() }
        }
        return START_NOT_STICKY
    }

    private fun finish() {
        occupied = false
        AgentSession.markFinished()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        runJob?.cancel()
        if (runJob == null) { occupied = false; AgentSession.markFinished() }
        super.onDestroy()
    }

    private fun notify(status: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentRunService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AIS · экспериментальный контроль экрана").setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_manage).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "aios_agent_runs"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_GOAL = "goal"
        private const val ACTION_STOP = "ai.aios.app.STOP_RUN"
        @Volatile private var occupied = false

        @Synchronized fun start(context: Context, goal: String) {
            if (occupied || PreviewSession.busy.value || goal.isBlank() || goal.length > 4_000) return
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            occupied = true
            AgentSession.startRun(goal.trim())
            try {
                context.startForegroundService(Intent(context, AgentRunService::class.java).putExtra(EXTRA_GOAL, goal.trim()))
            } catch (_: Exception) {
                occupied = false
                AgentSession.onEvent(AgentEvent.Failed("Android отказал в запуске. Откройте приложение и проверьте уведомления."))
            }
        }
        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, AgentRunService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
