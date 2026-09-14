package ai.aios.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aios.app.AgentRunService
import ai.aios.app.AgentSession
import ai.aios.app.AgentSettings
import ai.aios.app.SUPPORTED_MODELS
import ai.aios.app.device.AiosAccessibilityService
import ai.aios.core.safety.AutonomyMode

class MainActivity : ComponentActivity() {

    // Registered unconditionally: the result API requires registration before
    // the activity is STARTED, so this cannot sit behind a version check.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme { AiosScreen() }
        }
    }

    /** Without this the run notification - and its Stop button - is silent on 13+. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiosScreen() {
    val context = LocalContext.current
    val settings = remember { AgentSettings(context) }

    val status by AgentSession.status.collectAsState()
    val log by AgentSession.log.collectAsState()
    val approval by AgentSession.pendingApproval.collectAsState()
    val question by AgentSession.pendingQuestion.collectAsState()
    val (inputTokens, outputTokens) = AgentSession.tokensUsed.collectAsState().value

    var goal by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(!settings.hasApiKey) }

    val running = status == AgentSession.Status.RUNNING || status == AgentSession.Status.WAITING_FOR_USER

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AiOS") },
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("Settings") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!AiosAccessibilityService.isEnabled) {
                AccessibilityPrompt(
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            }

            OutlinedTextField(
                value = goal,
                onValueChange = { goal = it },
                label = { Text("What should the agent do?") },
                placeholder = { Text("e.g. open Settings and turn on battery saver") },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { AgentRunService.start(context, goal.trim()) },
                    enabled = !running && goal.isNotBlank() &&
                        settings.hasApiKey && AiosAccessibilityService.isEnabled,
                ) { Text("Run") }

                OutlinedButton(onClick = { AgentRunService.stop(context) }, enabled = running) {
                    Text("Stop")
                }

                if (!running && log.isNotEmpty()) {
                    OutlinedButton(onClick = { AgentSession.clear() }) { Text("Clear") }
                }
            }

            if (inputTokens > 0 || outputTokens > 0) {
                Text(
                    "Tokens this run: $inputTokens in / $outputTokens out",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RunLog(log, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }

    approval?.let { request ->
        ApprovalDialog(
            summary = AgentSession.describe(request.action),
            rationale = request.action.rationale,
            reason = request.reason,
            onApprove = { AgentSession.submitApproval(true) },
            onReject = { AgentSession.submitApproval(false) },
        )
    }

    question?.let { request ->
        QuestionDialog(
            question = request.question,
            onAnswer = { AgentSession.submitAnswer(it) },
            onDismiss = { AgentSession.submitAnswer(null) },
        )
    }

    if (showSettings) {
        SettingsDialog(settings = settings, onDismiss = { showSettings = false })
    }
}

@Composable
private fun AccessibilityPrompt(onOpenSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("The agent has no hands yet", fontWeight = FontWeight.SemiBold)
            Text(
                "AiOS drives the phone through an accessibility service. Turn on " +
                    "\"AiOS agent control\" in Settings > Accessibility. Only you can grant this.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onOpenSettings) { Text("Open accessibility settings") }
        }
    }
}

@Composable
private fun RunLog(log: List<AgentSession.LogEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // Keep the newest step in view without fighting a user who scrolls back.
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.lastIndex)
    }

    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(log) { _, entry ->
            Column {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = entry.step?.let { "$it" }.orEmpty().padStart(2),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        text = entry.text,
                        fontSize = 14.sp,
                        color = entry.kind.color(),
                        fontWeight = if (entry.kind == AgentSession.LogEntry.Kind.GOAL) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                    )
                }
                entry.detail?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 26.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentSession.LogEntry.Kind.color(): Color = when (this) {
    AgentSession.LogEntry.Kind.ERROR,
    AgentSession.LogEntry.Kind.REFUSED -> MaterialTheme.colorScheme.error

    AgentSession.LogEntry.Kind.DONE -> MaterialTheme.colorScheme.primary
    AgentSession.LogEntry.Kind.THINKING -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun ApprovalDialog(
    summary: String,
    rationale: String,
    reason: String,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        // Not dismissible by tapping away: an approval must be a deliberate act.
        onDismissRequest = { },
        title = { Text("Approve this action?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(summary, fontWeight = FontWeight.SemiBold)
                Text(reason, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "The agent says: $rationale",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onApprove) { Text("Approve") } },
        dismissButton = { OutlinedButton(onClick = onReject) { Text("Reject") } },
    )
}

@Composable
private fun QuestionDialog(question: String, onAnswer: (String) -> Unit, onDismiss: () -> Unit) {
    var answer by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("The agent needs to know") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(question)
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it },
                    label = { Text("Your answer") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onAnswer(answer) }, enabled = answer.isNotBlank()) { Text("Send") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Stop the run") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(settings: AgentSettings, onDismiss: () -> Unit) {
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var model by remember { mutableStateOf(settings.model) }
    var mode by remember { mutableStateOf(settings.autonomyMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Anthropic API key") },
                    placeholder = { Text("sk-ant-…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Stored encrypted on this device and sent only to Anthropic.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Model", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SUPPORTED_MODELS.forEach { (id, label) ->
                        FilterChip(
                            selected = model == id,
                            onClick = { model = id },
                            label = { Text(label) },
                        )
                    }
                }

                Text("How much should it confirm?", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AutonomyMode.entries.forEach { option ->
                        FilterChip(
                            selected = mode == option,
                            onClick = { mode = option },
                            label = { Text(option.describe()) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Calls, and the settings screen that controls the agent's own access, " +
                        "are refused in every mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                settings.apiKey = apiKey
                settings.model = model
                settings.autonomyMode = mode
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun AutonomyMode.describe(): String = when (this) {
    AutonomyMode.CONFIRM_EVERYTHING -> "Confirm every action"
    AutonomyMode.CONFIRM_SENSITIVE -> "Confirm only consequential ones (recommended)"
    AutonomyMode.AUTONOMOUS -> "Don't ask (blocked actions still refused)"
}
