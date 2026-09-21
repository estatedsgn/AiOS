package ai.aios.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.aios.app.AgentRunService
import ai.aios.app.AgentSession
import ai.aios.app.AgentSettings
import ai.aios.app.device.AiosAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** The pre-existing screen agent is a lab, not the default mobile experience. */
class MainActivity : ComponentActivity() {
    private val refresh = MutableStateFlow(0)
    private var pendingGoal: String? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val goal = pendingGoal; pendingGoal = null
        if (granted && goal != null) AgentRunService.start(this, goal)
    }
    override fun onResume() { super.onResume(); refresh.value++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val tick by refresh.collectAsState()
                LabScreen(tick, onBack = { finish() }, onRun = { goal ->
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        pendingGoal = goal; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else AgentRunService.start(this, goal)
                }, onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LabScreen(refresh: Int, onBack: () -> Unit, onRun: (String) -> Unit, onAccessibility: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val log by AgentSession.log.collectAsState()
    val status by AgentSession.status.collectAsState()
    val approval by AgentSession.pendingApproval.collectAsState()
    val question by AgentSession.pendingQuestion.collectAsState()
    var goal by rememberSaveable { mutableStateOf("") }
    var keyReady by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("ais_preview_ui", Context.MODE_PRIVATE) }
    var consent by remember { mutableStateOf(prefs.getBoolean("screen_consent", false)) }
    LaunchedEffect(refresh) {
        enabled = AiosAccessibilityService.isEnabled
        keyReady = withContext(Dispatchers.IO) { runCatching { AgentSettings(context).hasApiKey }.getOrDefault(false) }
    }
    val busy = status == AgentSession.Status.RUNNING || status == AgentSession.Status.WAITING_FOR_USER
    Scaffold(topBar = { TopAppBar(title = { Text("Контроль экрана · лаборатория") }, actions = {
        TextButton(onClick = onBack) { Text("В AIS") }
    }) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Это эксперимент, не автономное управление всем телефоном. Каждый шаг требует подтверждения. Отказ прекращает запуск.")
                Text("Если после подтверждения экран изменился, действие блокируется: старые координаты не используются. Проверяйте результат вручную.", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Row {
                    Checkbox(checked = consent, enabled = !busy, onCheckedChange = {
                        consent = it; prefs.edit().putBoolean("screen_consent", it).apply()
                    })
                    Text("Разрешаю передавать доступный текст открытых экранов в Anthropic во время моего запуска. Не открываю банки, пароли и другие чувствительные экраны.")
                }
            }
            if (!keyReady) item { Text("Сначала добавьте ключ в основных настройках AIS.") }
            if (!enabled) item {
                OutlinedButton(onClick = onAccessibility) { Text("Вручную включить Accessibility") }
            }
            item {
                OutlinedTextField(value = goal, onValueChange = { goal = it.take(4_000) }, enabled = !busy,
                    label = { Text("Что проверить на телефоне?") }, modifier = Modifier.fillMaxWidth(), maxLines = 4)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onRun(goal) }, enabled = !busy && keyReady && enabled && consent && goal.isNotBlank()) { Text("Запустить") }
                    OutlinedButton(onClick = { AgentRunService.stop(context) }, enabled = busy) { Text("Остановить") }
                }
            }
            itemsIndexed(log) { _, entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(entry.text)
                        entry.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
    approval?.let { request ->
        AlertDialog(onDismissRequest = { AgentSession.submitApproval(false) }, title = { Text("Разрешить один шаг?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(AgentSession.describe(request.action)); Text(request.reason)
                Text("Текст агента не является разрешением: ${request.action.rationale}", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { Button(onClick = { AgentSession.submitApproval(true) }) { Text("Разрешить") } },
            dismissButton = { OutlinedButton(onClick = { AgentSession.submitApproval(false) }) { Text("Отклонить и остановить") } })
    }
    question?.let { request ->
        var answer by remember(request.question) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { AgentSession.submitAnswer(null) }, title = { Text("Нужно уточнение") },
            text = { Column { Text(request.question); OutlinedTextField(value = answer, onValueChange = { answer = it.take(4_000) }) } },
            confirmButton = { Button(onClick = { AgentSession.submitAnswer(answer) }, enabled = answer.isNotBlank()) { Text("Ответить") } },
            dismissButton = { OutlinedButton(onClick = { AgentSession.submitAnswer(null) }) { Text("Остановить") } })
    }
}
