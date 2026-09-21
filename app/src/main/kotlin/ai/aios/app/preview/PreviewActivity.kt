package ai.aios.app.preview

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.aios.app.AgentSettings
import ai.aios.app.ui.MainActivity
import ai.aios.core.preview.Contact
import ai.aios.core.preview.Deal
import ai.aios.core.preview.Risk
import ai.aios.core.preview.Route
import ai.aios.core.preview.Workspace
import ai.aios.core.preview.Activity as ActivityRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Stock-Android app and OPTIONAL home role. Never changes the bootloader or default home silently. */
class PreviewActivity : ComponentActivity() {
    private val resumed = MutableStateFlow(false)
    private val sharedText = MutableStateFlow<String?>(null)
    private var pendingGoal: String? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val goal = pendingGoal
        pendingGoal = null
        if (granted && goal != null) PreviewService.start(this, goal)
        else PreviewSession.tell("Агент не запущен. Уведомления нужны для кнопки остановки вне AIS. Локальные дела доступны без них.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) acceptSharedText(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                val active by resumed.collectAsState()
                val shared by sharedText.collectAsState()
                val handoff by PreviewSession.handoff.collectAsState()
                LaunchedEffect(active, handoff?.id) {
                    if (active) handoff?.let { PreviewSession.dispatchHandoff(it.id, this@PreviewActivity) }
                }
                PreviewHome(active, shared, onSharedConsumed = { sharedText.value = null }, onSend = ::sendGoal,
                    onHome = ::chooseHome, onPhoneLab = {
                        if (PreviewSession.busy.value) PreviewSession.tell("Сначала остановите текущий запуск.")
                        else startActivity(Intent(this, MainActivity::class.java))
                    })
            }
        }
    }
    override fun onResume() { super.onResume(); resumed.value = true }
    override fun onPause() { resumed.value = false; super.onPause() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptSharedText(intent) }

    private fun acceptSharedText(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            // An incoming share is an editable draft, NEVER an instruction to run automatically.
            sharedText.value = intent.getStringExtra(Intent.EXTRA_TEXT)?.take(4_000)
        }
    }
    private fun sendGoal(goal: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingGoal = goal
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else PreviewService.start(this, goal)
    }
    private fun chooseHome() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val roles = getSystemService(RoleManager::class.java)
                if (roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                    startActivity(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
                    return
                }
            }
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }.onFailure { PreviewSession.tell("Откройте Настройки Android → Приложения → Приложения по умолчанию → Главный экран.") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewHome(resumed: Boolean, shared: String?, onSharedConsumed: () -> Unit,
    onSend: (String) -> Unit, onHome: () -> Unit, onPhoneLab: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PreviewStore.get(context) }
    val workspace by store.state.collectAsState()
    val ready by store.ready.collectAsState()
    val storageError by store.error.collectAsState()
    val busy by PreviewSession.busy.collectAsState()
    val status by PreviewSession.status.collectAsState()
    val notice by PreviewSession.notice.collectAsState()
    val approval by PreviewSession.approval.collectAsState()
    var tab by rememberSaveable { mutableStateOf("agent") }
    var draft by rememberSaveable { mutableStateOf("") }
    var installed by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(Unit) { runCatching { store.load() } }
    LaunchedEffect(resumed) {
        if (resumed) installed = withContext(Dispatchers.IO) { runCatching { PreviewSession.launchableApps(context) }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(shared) {
        if (shared != null) { draft = shared; tab = "agent"; onSharedConsumed() }
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == ComponentActivity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                draft = (draft + " " + it).trim().take(4_000)
            }
        }
    }
    // Protect the API-key page from screenshots and the Android recents thumbnail.
    DisposableEffect(tab) {
        val window = (context as? ComponentActivity)?.window
        if (tab == "settings") window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (tab == "settings") window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Scaffold(modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(title = { Column {
                Text("AIS", fontWeight = FontWeight.Bold)
                Text(if (busy) status else "Developer preview · APK, не ROM", style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            } }, actions = {
                if (busy) TextButton(onClick = { PreviewSession.stop() }, modifier = Modifier.testTag("stop")) { Text("Стоп") }
                TextButton(onClick = { tab = if (tab == "settings") "agent" else "settings" }) {
                    Text(if (tab == "settings") "Готово" else "Настройки")
                }
            })
        },
        bottomBar = {
            NavigationBar {
                listOf("agent" to "Агент", "work" to "Дела", "apps" to "Приложения", "history" to "Журнал").forEachIndexed { index, item ->
                    NavigationBarItem(selected = tab == item.first, onClick = { tab = item.first },
                        icon = { Text(listOf("✦", "✓", "▦", "≡")[index]) }, label = { Text(item.second) },
                        modifier = Modifier.testTag("tab_${item.first}"))
                }
            }
        },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 16.dp)) {
            (storageError ?: notice)?.let { message ->
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message, style = MaterialTheme.typography.bodySmall)
                        if (storageError == null) TextButton(onClick = { PreviewSession.tell(null) }) { Text("Закрыть") }
                    }
                }
            }
            when (tab) {
                "agent" -> {
                    if (workspace.messages.isEmpty()) {
                        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Чем займёмся?", style = MaterialTheme.typography.headlineSmall)
                                Text("Дела и история — на телефоне. ИИ — после подключения ключа. Управление экраном включается отдельно.")
                                Text("Открытых задач: ${workspace.tasks.count { !it.done }}", style = MaterialTheme.typography.labelLarge)
                                TextButton(onClick = { tab = "work" }) { Text("Добавить первую задачу") }
                            }
                        }
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("chat_history"), reverseLayout = true,
                        verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(workspace.messages.asReversed(), key = { it.id }) { entry ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (entry.role == "user") Arrangement.End else Arrangement.Start) {
                                Card(Modifier.widthIn(max = 380.dp), colors = CardDefaults.cardColors(
                                    containerColor = if (entry.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(when (entry.role) { "user" -> "Вы"; "assistant" -> "AIS"; else -> "Система" },
                                            style = MaterialTheme.typography.labelSmall)
                                        SelectionContainer { Text(entry.text) }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { draft = "/сводка" }, label = { Text("Мой день") })
                        AssistChip(onClick = { draft = "/задача " }, label = { Text("Задача без ИИ") })
                        AssistChip(onClick = { draft = "/черновик " }, label = { Text("Черновик") })
                    }
                    OutlinedTextField(value = draft, onValueChange = { draft = it.take(4_000) },
                        placeholder = { Text("Напишите задачу или сообщение…") }, maxLines = 5,
                        modifier = Modifier.fillMaxWidth().testTag("chat_input"), enabled = !busy)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Продиктуйте запрос; отправка только после проверки"))
                            }.onFailure { PreviewSession.tell("Системная диктовка недоступна. Используйте микрофон клавиатуры или текст.") }
                        }, enabled = !busy) { Text("Голос") }
                        Button(onClick = { onSend(draft) }, enabled = ready && !busy && draft.isNotBlank(),
                            modifier = Modifier.weight(1f).testTag("send")) { Text("Отправить") }
                    }
                }
                "work" -> WorkspacePage(store, workspace, ready)
                "apps" -> {
                    var search by rememberSaveable { mutableStateOf("") }
                    Text("Ручной режим", style = MaterialTheme.typography.headlineSmall)
                    Text("Работает независимо от ИИ и сети.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Найти приложение") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } },
                        modifier = Modifier.fillMaxWidth()) { Text("Настройки Android") }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(installed.filter { it.second.contains(search, true) || it.first.contains(search, true) }, key = { it.first }) { (pkg, label) ->
                            OutlinedButton(onClick = {
                                runCatching { context.startActivity(context.packageManager.getLaunchIntentForPackage(pkg) ?: error("Unavailable")) }
                                    .onFailure { PreviewSession.tell("Приложение недоступно. Вернитесь в AIS и обновите список.") }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
                        }
                    }
                }
                "history" -> {
                    Text("Журнал действий", style = MaterialTheme.typography.headlineSmall)
                    Text("VERIFIED — проверено локально. HANDOFF — только переход в Android, не внешнее выполнение.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                    if (workspace.activity.isEmpty()) Text("Пока нет действий. Здесь будут реальные результаты, а не демонстрационные данные.")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(workspace.activity.asReversed(), key = { it.id }) { entry ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("${entry.status} · ${entry.risk} · ${entry.route}", style = MaterialTheme.typography.labelSmall)
                                    Text(entry.tool, fontWeight = FontWeight.SemiBold)
                                    SelectionContainer { Text(entry.summary, style = MaterialTheme.typography.bodyMedium) }
                                }
                            }
                        }
                    }
                }
                "settings" -> PreviewSettings(onHome, onPhoneLab, onSaved = { tab = "agent" })
            }
        }
    }
    approval?.let { request ->
        AlertDialog(onDismissRequest = { PreviewSession.decide(request.id, false) },
            title = { Text("Разрешить действие?") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${request.spec.risk} · ${request.spec.route}", style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(PreviewSession.describe(request.call)) }
                Text("Проверьте точный текст. Черновик откроется в выбранном вами приложении; получателя и отправку вы подтверждаете там.")
            } },
            confirmButton = { Button(onClick = { PreviewSession.decide(request.id, true) }, modifier = Modifier.testTag("approve")) { Text("Разрешить один раз") } },
            dismissButton = { OutlinedButton(onClick = { PreviewSession.decide(request.id, false) }, modifier = Modifier.testTag("reject")) { Text("Отклонить") } })
    }
}

@Composable
private fun WorkspacePage(store: PreviewStore, state: Workspace, ready: Boolean) {
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf("Задачи") }
    var title by rememberSaveable { mutableStateOf("") }
    var detail by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    Text("Мои дела", style = MaterialTheme.typography.headlineSmall)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Задачи", "Контакты", "Сделки").forEach { label ->
            FilterChip(selected = section == label, onClick = { section = label; title = ""; detail = "" }, label = { Text(label) })
        }
    }
    Text("Только на этом телефоне. Синхронизации с внешней CRM пока нет.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(value = title, onValueChange = { title = it.take(if (section == "Контакты") 200 else 500) },
        label = { Text(if (section == "Контакты") "Имя" else "Название") },
        modifier = Modifier.fillMaxWidth().testTag("work_title"), singleLine = true, enabled = !saving)
    if (section != "Сделки") OutlinedTextField(value = detail, onValueChange = { detail = it.take(if (section == "Задачи") 10 else 200) },
        label = { Text(if (section == "Задачи") "Срок YYYY-MM-DD (необязательно, не будильник)" else "Телефон / почта / username (необязательно)") },
        modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
    Button(onClick = {
        val savedTitle = title.trim(); val savedDetail = detail.trim(); val savedSection = section
        saving = true
        scope.launch {
            try {
                store.change { before ->
                    val id = UUID.randomUUID().toString()
                    val next = when (savedSection) {
                        "Задачи" -> before.addTask(savedTitle, savedDetail, id)
                        "Контакты" -> { require(before.contacts.size < 500); before.copy(contacts = before.contacts + Contact(id, savedTitle, savedDetail)) }
                        else -> { require(before.deals.size < 500); before.copy(deals = before.deals + Deal(id, savedTitle)) }
                    }
                    next.copy(activity = next.activity + ActivityRecord(id, "manual", "Добавлено вручную: $savedSection", Risk.LOCAL,
                        Route.WORKSPACE, savedTitle, "VERIFIED", System.currentTimeMillis()))
                }
                title = ""; detail = ""
            } catch (e: IllegalArgumentException) { PreviewSession.tell(e.message ?: "Проверьте название и дату.") }
            catch (_: Exception) { PreviewSession.tell("Не удалось сохранить запись. Данные не сбрасывались.") }
            finally { saving = false }
        }
    }, enabled = ready && title.isNotBlank() && !saving, modifier = Modifier.fillMaxWidth().testTag("work_add")) { Text("Сохранить на телефоне") }
    OutlinedTextField(value = search, onValueChange = { search = it }, label = { Text("Поиск в записях") },
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), singleLine = true)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("work_list")) {
        when (section) {
            "Задачи" -> items(state.tasks.filter { it.title.contains(search, true) }.sortedBy { it.done }, key = { it.id }) { task ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = task.done, onCheckedChange = { done -> scope.launch {
                            runCatching { store.change { before ->
                                val next = before.setTaskStatus(task.id, done)
                                next.copy(activity = next.activity + ActivityRecord(UUID.randomUUID().toString(), "manual", "task_set_status",
                                    Risk.LOCAL, Route.WORKSPACE, "${task.title}: ${if (done) "готово" else "открыто"}", "VERIFIED", System.currentTimeMillis()))
                            } }.onFailure { PreviewSession.tell("Не удалось изменить задачу. Проверьте хранилище.") }
                        } }, modifier = Modifier.testTag("task_${task.id}"))
                        Column(Modifier.weight(1f)) {
                            Text(task.title, fontWeight = if (task.done) FontWeight.Normal else FontWeight.SemiBold)
                            Text(if (task.done) "Завершена" else task.dueDate.ifBlank { "Без срока" }, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            "Контакты" -> items(state.contacts.filter { it.displayName.contains(search, true) || it.channel.contains(search, true) }, key = { it.id }) { contact ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
                    Text(contact.displayName, fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(contact.channel) }
                } }
            }
            else -> items(state.deals.filter { it.title.contains(search, true) }, key = { it.id }) { deal ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Text(deal.title); Text("Локальная возможность · ${deal.stage}") } }
            }
        }
    }
}

@Composable
private fun PreviewSettings(onHome: () -> Unit, onPhoneLab: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") } // Never rememberSaveable a credential.
    var model by remember { mutableStateOf("claude-sonnet-5") }
    var consent by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { AgentSettings(context).let { it.apiKey to it.model } } }
            .onSuccess { key = it.first; model = it.second; loaded = true }
            .onFailure { PreviewSession.tell("Не удалось открыть защищённые настройки. Ключ не сбрасывался.") }
        consent = context.getSharedPreferences("ais_preview_ui", android.content.Context.MODE_PRIVATE).getBoolean("cloud_consent", false)
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Подключение ИИ", style = MaterialTheme.typography.headlineSmall)
        Text("Без ключа доступны локальные дела, сводка и черновики. Сохранённый ключ ещё не означает проверенное соединение.")
        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Anthropic API key") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = loaded && !saving)
        OutlinedTextField(value = model, onValueChange = { model = it.take(200) }, label = { Text("Модель") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), enabled = loaded && !saving)
        Text("Например: claude-sonnet-5 или claude-haiku-4-5. API оплачивается вашим аккаунтом Anthropic.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = consent, onCheckedChange = { consent = it }, enabled = !saving)
            Text("Разрешаю отправлять мои запросы, контекст диалога и запрошенные записи AIS в Anthropic. Пароли в чат не ввожу.", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = {
            saving = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) {
                    val settings = AgentSettings(context)
                    settings.apiKey = key.trim(); settings.model = model.trim()
                    check(context.getSharedPreferences("ais_preview_ui", android.content.Context.MODE_PRIVATE).edit()
                        .putBoolean("cloud_consent", consent && key.isNotBlank()).commit())
                } }.onSuccess { PreviewSession.tell("Настройки сохранены. Напишите запрос, чтобы проверить ИИ."); onSaved() }
                    .onFailure { PreviewSession.tell("Настройки не удалось сохранить.") }
                saving = false
            }
        }, enabled = loaded && !saving && model.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Сохранить настройки") }
        HorizontalDivider()
        Text("Телефон остаётся обычным Android", style = MaterialTheme.typography.titleMedium)
        Text("Для APK не нужны root, OEM unlock, прошивка или сброс данных. AIS можно просто открыть как приложение.")
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("Выбрать / вернуть главный экран") }
        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }, modifier = Modifier.fillMaxWidth()) { Text("Настройки Android") }
        HorizontalDivider()
        Text("Управление экраном · эксперимент", style = MaterialTheme.typography.titleMedium)
        Text("Отдельный режим использует Accessibility. Он видит доступный текст открытых экранов и передаёт его модели. В preview каждое действие требует подтверждения. Не используйте для банков, паролей и важных отправок.")
        OutlinedButton(onClick = onPhoneLab, modifier = Modifier.fillMaxWidth()) { Text("Открыть экспериментальный режим") }
        Text("Пока нет: ROM, root-команд, удалённой CRM-синхронизации, подключения OpenClaw Gateway, фоновых расписаний и автономного управления всеми приложениями.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp))
    }
}
