package ai.aios.app.workspace

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import ai.aios.app.AgentSession
import ai.aios.app.AgentRunService
import ai.aios.app.ui.MainActivity
import ai.aios.core.workspace.*
import java.text.DateFormat
import java.util.Date

class WorkspaceActivity : ComponentActivity() {
    private val homeRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = WorkspaceSession.get(this)
        setContent { MaterialTheme { PreviewHome(session, ::chooseHome, ::openManual) } }
    }
    override fun onStart() {
        super.onStart()
        if (AgentSession.status.value in setOf(AgentSession.Status.RUNNING, AgentSession.Status.WAITING_FOR_USER)) AgentRunService.stop(this)
        WorkspaceSession.visible = true
    }
    override fun onStop() { WorkspaceSession.visible = false; super.onStop() }
    private fun chooseHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = getSystemService(RoleManager::class.java)
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                homeRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME)); return
            }
        }
        openManual(Intent(Settings.ACTION_HOME_SETTINGS))
    }
    private fun openManual(intent: Intent) {
        try { startActivity(intent) }
        catch (_: Exception) { android.widget.Toast.makeText(this, "Android не смог открыть этот экран.", android.widget.Toast.LENGTH_LONG).show() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewHome(session: WorkspaceSession, chooseHome: () -> Unit, open: (Intent) -> Unit) {
    val context = LocalContext.current
    val workspace by session.workspace.collectAsState()
    val error by session.error.collectAsState()
    val busy by session.busy.collectAsState()
    val stopping by session.stopping.collectAsState()
    val enabled = !busy && !stopping && workspace != null
    var goal by rememberSaveable { mutableStateOf("Задача: Связаться с Анной") }
    var tab by rememberSaveable { mutableStateOf(0) }
    var legacyWarning by remember { mutableStateOf(false) }
    val tabs = listOf("Работа", "Журнал", "Приложения")

    Scaffold(topBar = { TopAppBar(title = { Text("AIS · APK preview") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stock Android • не ROM • без root", style = MaterialTheme.typography.labelMedium)
            if (tab == 0) {
                OutlinedTextField(
                    value = goal, onValueChange = { if (it.length <= 300) goal = it },
                    label = { Text("Офлайн-команда") }, modifier = Modifier.fillMaxWidth().testTag("goal"),
                    enabled = enabled, maxLines = 3,
                )
                Text("«Задача: …», «Сводка», «Открой настройки», «Покажи экран». Не вводите пароли и ключи.", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tab == 0) Button(onClick = { session.goal(goal) }, enabled = enabled && goal.isNotBlank(), modifier = Modifier.testTag("run")) { Text("Выполнить") }
                OutlinedButton(onClick = { session.stop(); AgentRunService.stop(context) }, modifier = Modifier.testTag("stop")) { Text("Стоп") }
                TextButton(onClick = { open(Intent(Settings.ACTION_SETTINGS)) }) { Text("Настройки") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (busy || stopping) LinearProgressIndicator(Modifier.fillMaxWidth())
            TabRow(selectedTabIndex = tab) { tabs.forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }) } }
            when (tab) {
                0 -> WorkspaceList(workspace, enabled, session, Modifier.weight(1f))
                1 -> HistoryList(workspace, Modifier.weight(1f))
                2 -> ManualApps(chooseHome, open, { legacyWarning = true }, Modifier.weight(1f))
            }
        }
    }
    if (legacyWarning) AlertDialog(
        onDismissRequest = { legacyWarning = false }, title = { Text("Отдельный экспериментальный агент") },
        text = { Text("Это прежний LLM + Accessibility режим со своей политикой. Он не использует новый workspace router. Ему нужны отдельный API-ключ и ручное разрешение Accessibility. Для APK-сценария они не нужны.") },
        confirmButton = { TextButton(onClick = { session.stop(); legacyWarning = false; open(Intent(context, MainActivity::class.java)) }) { Text("Открыть эксперимент") } },
        dismissButton = { TextButton(onClick = { legacyWarning = false }) { Text("Назад") } },
    )
}

@Composable
private fun WorkspaceList(workspace: Workspace?, enabled: Boolean, session: WorkspaceSession, modifier: Modifier) {
    val snapshot = workspace ?: Workspace()
    val pending = snapshot.proposals.filter { it.status == ProposalStatus.WAITING_APPROVAL }
    LazyColumn(modifier.fillMaxWidth().testTag("workspace"), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Сегодня", style = MaterialTheme.typography.titleLarge)
            Text("Открытых задач: ${snapshot.workItems.count { !it.done }} · Готово: ${snapshot.workItems.count { it.done }} · Подтверждений: ${pending.size}")
            Text("Только локальный workspace. CRM API не подключена; облачная синхронизация не выполняется.", style = MaterialTheme.typography.bodySmall)
        }
        items(pending, key = { "approval-${it.id}" }) { p ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Требуется подтверждение", style = MaterialTheme.typography.titleMedium)
                    Text(p.call.arguments["title"].orEmpty())
                    Text("${p.risk} · ${p.route} · ${p.call.tool}", style = MaterialTheme.typography.labelMedium)
                    Text(p.reason)
                    Text("Одно действие, срок подтверждения — 5 минут. Сохранение в календаре подтверждается отдельно.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { session.approve(p.id) }, enabled = enabled) { Text("Открыть календарь") }
                        OutlinedButton(onClick = { session.reject(p.id) }, enabled = enabled) { Text("Отклонить") }
                    }
                }
            }
        }
        if (snapshot.workItems.isEmpty()) item { Text("Создайте первую задачу командой выше. Интернет и API-ключ не требуются.") }
        items(snapshot.workItems.asReversed(), key = { "task-${it.id}" }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(if (item.done) "Выполнено локально" else "Открыта · локальная задача", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { session.call(ToolCall("workspace.task.toggle", mapOf("id" to item.id))) }, enabled = enabled) { Text(if (item.done) "Вернуть" else "Готово") }
                        Button(onClick = { session.call(ToolCall("calendar.draft", mapOf("title" to item.title))) }, enabled = enabled) { Text("В календарь") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryList(workspace: Workspace?, modifier: Modifier) {
    val snapshot = workspace ?: Workspace()
    val proposals = snapshot.proposals.associateBy { it.id }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("HANDOFF — это передача в приложение, не доказательство сохранения. UNKNOWN — результат неизвестен. Ни то ни другое не повторяется автоматически.", style = MaterialTheme.typography.bodySmall) }
        if (snapshot.activities.isEmpty()) item { Text("Действий пока нет.") }
        items(snapshot.activities.asReversed(), key = { it.id }) { event ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${event.type} · ${event.source}", style = MaterialTheme.typography.labelLarge)
                    proposals[event.proposalId]?.let { Text(it.call.tool + it.call.arguments["title"]?.let { title -> " · $title" }.orEmpty()) }
                    Text(event.summary)
                    Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.occurredAt)), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ManualApps(chooseHome: () -> Unit, open: (Intent) -> Unit, legacy: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val apps = remember {
        context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .sortedBy { it.loadLabel(context.packageManager).toString().lowercase() }
    }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Ручной режим всегда доступен", style = MaterialTheme.typography.titleMedium)
            Text("Домашний экран выбирается добровольно. Вернуться: Настройки → Приложения → Приложения по умолчанию → Главный экран.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = chooseHome) { Text("Выбрать домашний экран") }
            TextButton(onClick = { open(Intent(Settings.ACTION_HOME_SETTINGS)) }) { Text("Вернуть системный launcher") }
            TextButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility — необязательно") }
            TextButton(onClick = legacy) { Text("Экспериментальный LLM-агент") }
        }
        items(apps, key = { "${it.activityInfo.packageName}/${it.activityInfo.name}" }) { info ->
            OutlinedButton(onClick = {
                open(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(info.activityInfo.packageName, info.activityInfo.name)))
            }, modifier = Modifier.fillMaxWidth()) { Text(info.loadLabel(context.packageManager).toString()) }
        }
    }
}
