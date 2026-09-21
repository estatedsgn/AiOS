package ai.aios.app.preview

import android.app.Activity
import android.content.Context
import android.content.Intent
import ai.aios.app.AgentSession
import ai.aios.app.AgentSettings
import ai.aios.core.preview.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.util.UUID
import ai.aios.core.preview.Activity as ActivityRecord

/** No Activity is retained. Approval and native handoff gates are intentionally memory-only. */
object PreviewSession {
    data class ApprovalRequest(val id: String, val spec: ToolSpec, val call: ToolCall)
    data class HandoffRequest(val id: String, val call: ToolCall)
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _status = MutableStateFlow("Готов")
    val status = _status.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private val _approval = MutableStateFlow<ApprovalRequest?>(null)
    val approval = _approval.asStateFlow()
    private val _handoff = MutableStateFlow<HandoffRequest?>(null)
    val handoff = _handoff.asStateFlow()
    private var approvalGate: CompletableDeferred<Boolean>? = null
    private var handoffGate: CompletableDeferred<ToolResult>? = null
    private var token: String? = null
    private var job: Job? = null

    @Synchronized fun reserve(): String? {
        if (_busy.value || AgentSession.status.value in setOf(AgentSession.Status.RUNNING, AgentSession.Status.WAITING_FOR_USER)) {
            tell("Сначала остановите текущий запуск.")
            return null
        }
        return UUID.randomUUID().toString().also {
            token = it; _busy.value = true; _status.value = "Запуск…"; _notice.value = null
        }
    }
    fun accepts(id: String) = token == id && _busy.value
    fun tell(text: String?) { _notice.value = text }
    fun failedToStart(id: String) {
        if (token != id) return
        token = null; _busy.value = false; _status.value = "Не запущен"
        tell("Android не разрешил запуск. Откройте AIS и проверьте разрешение уведомлений.")
    }

    fun stop() {
        _status.value = "Останавливаю…"
        approvalGate?.cancel(); handoffGate?.cancel()
        _approval.value = null; _handoff.value = null
        if (job == null) { token = null; _busy.value = false; _status.value = "Остановлен" }
        else job?.cancel()
    }

    fun decide(id: String, approved: Boolean) {
        if (_approval.value?.id != id || !_busy.value) return
        _approval.value = null
        approvalGate?.complete(approved)
    }

    private suspend fun askApproval(spec: ToolSpec, call: ToolCall): Boolean {
        val gate = CompletableDeferred<Boolean>()
        approvalGate = gate
        _approval.value = ApprovalRequest(UUID.randomUUID().toString(), spec, call)
        _status.value = "Нужно ваше подтверждение"
        return try { withTimeout(120_000) { gate.await() } } finally {
            if (approvalGate === gate) { approvalGate = null; _approval.value = null }
        }
    }

    private suspend fun requestHandoff(call: ToolCall): ToolResult {
        val gate = CompletableDeferred<ToolResult>()
        handoffGate = gate
        _handoff.value = HandoffRequest(UUID.randomUUID().toString(), call)
        _status.value = "Вернитесь в AIS для открытия приложения"
        return try { withTimeout(120_000) { gate.await() } } finally {
            if (handoffGate === gate) { handoffGate = null; _handoff.value = null }
        }
    }

    /** Called only by a RESUMED Activity, never by a background service. Consumed exactly once. */
    fun dispatchHandoff(id: String, activity: Activity) {
        val request = _handoff.value?.takeIf { it.id == id } ?: return
        val gate = handoffGate?.takeIf { it.isActive } ?: return
        if (!_busy.value || job?.isActive != true) return
        _handoff.value = null
        val result = try {
            when (request.call.name) {
                "app_open" -> {
                    val pkg = request.call.arguments.getValue("package")
                    require(launchableApps(activity).any { it.first == pkg })
                    val intent = activity.packageManager.getLaunchIntentForPackage(pkg)
                        ?: error("No launcher activity")
                    activity.startActivity(intent)
                    ToolResult("Android принял запрос открыть приложение. Содержимое его экрана не проверялось.", pkg, Outcome.HANDOFF)
                }
                "message_draft" -> {
                    val draft = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, request.call.arguments.getValue("text"))
                    activity.startActivity(Intent.createChooser(draft, "Выберите приложение и отправьте вручную"))
                    ToolResult("Открыт выбор приложения с черновиком. Сообщение НЕ отправлено: выберите получателя и отправьте вручную.",
                        "Android ACTION_SEND chooser opened; delivery unverified", Outcome.HANDOFF)
                }
                else -> ToolResult("Неизвестный переход заблокирован.", status = Outcome.FAILED)
            }
        } catch (_: Exception) {
            ToolResult("Android не смог открыть приложение. Действие не повторяется автоматически.", status = Outcome.FAILED)
        }
        gate.complete(result)
    }

    suspend fun run(context: Context, goal: String, runId: String) {
        if (!accepts(runId)) return
        job = currentCoroutineContext()[Job]
        val store = PreviewStore.get(context)
        var provider: PreviewClaudeModel? = null
        var terminal = "Готов"
        try {
            store.load()
            val history = store.state.value.messages
            store.change { it.copy(activeRunId = runId, messages = it.messages + message("user", goal)) }
            val reply: String
            if (goal.trim() == "/сводка") {
                reply = dailyBrief(store.state.value)
            } else {
                val model: PreviewModel = when {
                    goal.startsWith("/задача ") -> localTool("task_add", mapOf("title" to goal.removePrefix("/задача ").trim()))
                    goal.startsWith("/черновик ") -> localTool("message_draft", mapOf("text" to goal.removePrefix("/черновик ")))
                    else -> {
                        val settings = withContext(Dispatchers.IO) { AgentSettings(context) }
                        val consent = context.getSharedPreferences("ais_preview_ui", Context.MODE_PRIVATE)
                            .getBoolean("cloud_consent", false)
                        if (!settings.hasApiKey || !consent) {
                            store.change { it.copy(messages = it.messages + message("assistant",
                                "Сейчас доступен локальный режим: вкладка «Дела», /задача, /сводка и /черновик. Для свободного диалога подключите ИИ в настройках.")) }
                            return
                        }
                        _status.value = "Запрос к ИИ…"
                        PreviewClaudeModel(settings.apiKey, settings.model).also { provider = it }
                    }
                }
                val host = ToolHost { spec, call -> executeLocal(context, store, spec, call) }
                val router = ToolRouter(PreviewTools.all, host, ApprovalGate { spec, call -> askApproval(spec, call) })
                reply = PreviewRuntime(model, router).run(goal, history,
                    onStage = { call, spec, stage ->
                        store.change { state ->
                            val record = ActivityRecord("$runId:${call.id}", runId, spec.name, spec.risk, spec.route,
                                describe(call), stage, System.currentTimeMillis())
                            state.copy(activity = state.activity.filterNot { it.id == record.id } + record)
                        }
                        if (stage == "STARTED") _status.value = "Выполняю: ${spec.name}"
                    },
                    onResult = { exchange ->
                        store.change { state -> state.copy(activity = state.activity.map {
                            if (it.id == "$runId:${exchange.call.id}") it.copy(
                                status = exchange.result.status.name, summary = exchange.result.summary.take(2_000)) else it
                        }) }
                    },
                )
            }
            store.change { it.copy(messages = it.messages + message("assistant", reply)) }
        } catch (_: TimeoutCancellationException) {
            terminal = "Время ожидания истекло"
            withContext(NonCancellable) { runCatching { store.change { it.copy(messages = it.messages + message("system",
                "Время ожидания истекло. Запуск остановлен. Уже выполненные действия смотрите в журнале; автоматического повтора нет.")) } } }
        } catch (e: CancellationException) {
            terminal = "Остановлен"
            withContext(NonCancellable) { runCatching { store.change { it.copy(messages = it.messages + message("system",
                "Остановлено. Уже выполненные действия не отменяются; неподтверждённые не продолжаются.")) } } }
            throw e
        } catch (_: Exception) {
            terminal = "Ошибка"
            val safe = "Не удалось завершить запрос. Проверьте интернет, ключ и модель в настройках. Результат действий смотрите в журнале; повтора нет."
            tell(safe)
            runCatching { store.change { it.copy(messages = it.messages + message("system", safe)) } }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { provider?.close() }
                runCatching { store.change { state ->
                    if (state.activeRunId != runId) state else state.copy(activeRunId = null,
                        activity = state.activity.map {
                            if (it.runId == runId && it.status in setOf("STARTED", "WAITING"))
                                it.copy(status = "INTERRUPTED", summary = "Завершение действия не подтверждено. Проверьте вручную.") else it
                        })
                } }
            }
            if (token == runId) {
                approvalGate?.cancel(); handoffGate?.cancel()
                approvalGate = null; handoffGate = null
                _approval.value = null; _handoff.value = null
                job = null; token = null; _busy.value = false; _status.value = terminal
            }
        }
    }

    private fun localTool(name: String, arguments: Map<String, String>): PreviewModel = object : PreviewModel {
        override suspend fun next(goal: String, history: List<ChatMessage>, exchanges: List<ToolExchange>): ModelTurn =
            if (exchanges.isEmpty()) ModelTurn(calls = listOf(ToolCall("local", name, arguments)))
            else ModelTurn(exchanges.last().result.summary)
    }

    private suspend fun executeLocal(context: Context, store: PreviewStore, spec: ToolSpec, call: ToolCall): ToolResult {
        spec.validate(call.arguments)
        val args = call.arguments
        return when (call.name) {
            "workspace_read" -> {
                val state = store.state.value
                val view = buildJsonObject {
                    put("total_tasks", state.tasks.size); put("total_contacts", state.contacts.size); put("total_deals", state.deals.size)
                    put("limit_per_collection", 20)
                    put("note", "Most recent 20 per collection; full records are available in the local workspace UI.")
                    put("tasks", Json.encodeToJsonElement(state.tasks.takeLast(20)))
                    put("contacts", Json.encodeToJsonElement(state.contacts.takeLast(20)))
                    put("deals", Json.encodeToJsonElement(state.deals.takeLast(20)))
                }
                ToolResult("Прочитаны локальные записи AIS.", view.toString())
            }
            "task_add" -> {
                val id = UUID.randomUUID().toString()
                val next = store.change { it.addTask(args.getValue("title"), args["due_date"].orEmpty(), id) }
                check(next.tasks.any { it.id == id })
                ToolResult("Задача сохранена на телефоне.", "task_id=$id")
            }
            "task_set_status" -> {
                val done = when (args.getValue("status")) { "done" -> true; "open" -> false; else -> error("Invalid status") }
                val id = args.getValue("id")
                val next = store.change { it.setTaskStatus(id, done) }
                check(next.tasks.single { it.id == id }.done == done)
                ToolResult(if (done) "Задача завершена." else "Задача снова открыта.", "task_id=$id; done=$done")
            }
            "contact_add" -> {
                val record = Contact(UUID.randomUUID().toString(), args.getValue("name").trim(), args["channel"].orEmpty().trim())
                val next = store.change { require(it.contacts.size < 500); it.copy(contacts = it.contacts + record) }
                check(record in next.contacts)
                ToolResult("Контакт сохранён в AIS. Адресная книга Android не изменена.", "contact_id=${record.id}")
            }
            "deal_add" -> {
                val record = Deal(UUID.randomUUID().toString(), args.getValue("title").trim())
                val next = store.change { require(it.deals.size < 500); it.copy(deals = it.deals + record) }
                check(record in next.deals)
                ToolResult("Возможность сохранена локально в AIS. Удалённая CRM не изменена.", "deal_id=${record.id}")
            }
            "apps_list" -> ToolResult("Прочитан список приложений.", withContext(Dispatchers.IO) {
                launchableApps(context).take(150).joinToString("\n") { (pkg, label) -> "$pkg — $label" }
            })
            "app_open", "message_draft" -> requestHandoff(call)
            else -> ToolResult("Этот инструмент не реализован в APK.", status = Outcome.FAILED)
        }
    }

    fun describe(call: ToolCall) = call.name + "\n" + call.arguments.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    private fun message(role: String, text: String) = ChatMessage(UUID.randomUUID().toString(), role, text.take(16_000), System.currentTimeMillis())

    fun dailyBrief(state: Workspace): String {
        val today = LocalDate.now().toString()
        val open = state.tasks.filterNot { it.done }
        val due = open.filter { it.dueDate.isNotBlank() && it.dueDate <= today }
        return buildString {
            append("На телефоне: ${open.size} открытых задач, ${state.contacts.size} контактов, ${state.deals.size} возможностей.\n")
            append("На сегодня и просрочено: ${due.size}.\n")
            (due + open.filter { it !in due }).take(8).forEach { append("\n• ${it.title}${if (it.dueDate.isBlank()) "" else " · ${it.dueDate}"}") }
            if (open.isEmpty()) append("\nДобавьте первую задачу во вкладке «Дела».")
            append("\n\nЛокальная сводка, без обращения к ИИ. Даты задач не создают уведомлений.")
        }
    }

    @Suppress("DEPRECATION")
    fun launchableApps(context: Context): List<Pair<String, String>> = context.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
        .filter { it.first != context.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
}
