package ai.aios.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import ai.aios.app.device.AiosAccessibilityService
import ai.aios.app.ui.MainActivity
import ai.aios.core.agent.AgentEvent
import ai.aios.core.agent.AgentRunner
import ai.aios.core.agent.RunnerConfig
import ai.aios.core.planner.ClaudePlanner
import ai.aios.core.planner.PlannerConfig
import ai.aios.core.safety.ActionPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * Executes one agent run.
 *
 * A foreground service rather than a background coroutine for two reasons: a
 * run drives the screen for minutes at a time and must survive the app being
 * backgrounded, and - more importantly - the ongoing notification means the
 * phone can never be driven without a visible, one-tap way to stop it.
 */
class AgentRunService : LifecycleService() {

    private var runJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopRun()
            return START_NOT_STICKY
        }

        val goal = intent?.getStringExtra(EXTRA_GOAL)?.takeIf { it.isNotBlank() }
        if (goal == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification("Starting…", goal)
        startRun(goal)
        return START_NOT_STICKY
    }

    private fun startRun(goal: String) {
        // A second Start while a run is live would give two agents one screen.
        if (runJob?.isActive == true) return

        val settings = AgentSettings(this)
        val device = AiosAccessibilityService.instance

        if (device == null) {
            AgentSession.onEvent(
                AgentEvent.Failed(
                    "The AiOS accessibility service is off, so the agent has no way to act. " +
                        "Turn it on in Settings > Accessibility."
                )
            )
            stopSelf()
            return
        }
        if (!settings.hasApiKey) {
            AgentSession.onEvent(AgentEvent.Failed("No API key set. Add one in AiOS settings."))
            stopSelf()
            return
        }

        val runner = AgentRunner(
            device = device,
            planner = ClaudePlanner(
                apiKey = settings.apiKey,
                config = PlannerConfig(model = settings.model),
            ),
            policy = ActionPolicy(mode = settings.autonomyMode),
            human = AgentSession,
            config = RunnerConfig(maxSteps = settings.maxSteps),
        )

        runJob = lifecycleScope.launch {
            runner.run(goal)
                .onCompletion {
                    // Covers cancellation too: without this a stopped run would
                    // leave the UI showing "running" for ever.
                    AgentSession.markFinished()
                    stopSelf()
                }
                .collect { event ->
                    AgentSession.onEvent(event)
                    notifyProgress(event, goal)
                }
        }
    }

    private fun stopRun() {
        runJob?.cancel()
        runJob = null
        AgentSession.markFinished()
        stopSelf()
    }

    override fun onDestroy() {
        runJob?.cancel()
        AgentSession.markFinished()
        super.onDestroy()
    }

    // --- notification -------------------------------------------------------

    private fun notifyProgress(event: AgentEvent, goal: String) {
        val line = when (event) {
            is AgentEvent.ActionProposed -> "Step ${event.step}: ${AgentSession.describe(event.action)}"
            is AgentEvent.AwaitingApproval -> "Waiting for your approval"
            is AgentEvent.QuestionAsked -> "Waiting for your answer"
            is AgentEvent.Thinking -> "Step ${event.step}: thinking…"
            is AgentEvent.Finished -> event.summary
            is AgentEvent.Failed -> event.message
            else -> return
        }
        startForegroundNotification(line, goal)
    }

    private fun startForegroundNotification(status: String, goal: String) {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentRunService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(goal)
            .setContentText(status)
            .setStyle(Notification.BigTextStyle().bigText(status))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val CHANNEL_ID = "aios_agent_runs"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_GOAL = "goal"
        private const val ACTION_STOP = "ai.aios.app.STOP_RUN"

        fun start(context: Context, goal: String) {
            AgentSession.startRun(goal)
            val intent = Intent(context, AgentRunService::class.java).putExtra(EXTRA_GOAL, goal)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AgentRunService::class.java).setAction(ACTION_STOP))
        }
    }
}
