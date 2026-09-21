package ai.aios.core.preview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.UUID

@Serializable data class Contact(val id: String, val displayName: String, val channel: String = "")
@Serializable data class WorkItem(val id: String, val title: String, val dueDate: String = "", val done: Boolean = false)
@Serializable data class Deal(val id: String, val title: String, val stage: String = "new")
@Serializable data class ChatMessage(val id: String, val role: String, val text: String, val at: Long)
@Serializable data class Activity(
    val id: String, val runId: String, val tool: String, val risk: Risk,
    val route: Route, val summary: String, val status: String, val at: Long,
)
@Serializable data class Workspace(
    val version: Int = 1,
    val contacts: List<Contact> = emptyList(),
    val tasks: List<WorkItem> = emptyList(),
    val deals: List<Deal> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val activity: List<Activity> = emptyList(),
    val activeRunId: String? = null,
) {
    fun addTask(title: String, dueDate: String = "", id: String = UUID.randomUUID().toString()): Workspace {
        require(title.isNotBlank() && title.length <= 500) { "Название задачи: от 1 до 500 символов." }
        require(dueDate.isEmpty() || runCatching { LocalDate.parse(dueDate) }.isSuccess) { "Дата должна быть YYYY-MM-DD." }
        require(tasks.size < 1_000) { "Лимит preview: 1000 задач." }
        require(tasks.none { it.id == id }) { "Повторный ID задачи." }
        return copy(tasks = tasks + WorkItem(id, title.trim(), dueDate))
    }

    fun setTaskStatus(id: String, done: Boolean): Workspace {
        require(tasks.any { it.id == id }) { "Задача не найдена. Обновите список." }
        return copy(tasks = tasks.map { if (it.id == id) it.copy(done = done) else it })
    }

    /** Never replay an action or restore an approval after process death. */
    fun recover(now: Long): Workspace {
        if (activeRunId == null) return this
        return copy(
            activeRunId = null,
            activity = activity.map {
                if (it.runId == activeRunId && it.status in setOf("STARTED", "WAITING")) {
                    it.copy(status = "INTERRUPTED", summary = "Прервано. Результат не подтверждён; автоматического повтора нет.")
                } else it
            },
            messages = (messages + ChatMessage(UUID.randomUUID().toString(), "system",
                "Предыдущая сессия прервалась. Проверьте результат действий перед повтором.", now)).takeLast(200),
        )
    }
}

@Serializable enum class Risk { READ, LOCAL, EXTERNAL, SENSITIVE }
@Serializable enum class Route { WORKSPACE, ANDROID, SERVICE_API, ACCESSIBILITY }
data class Argument(val description: String, val required: Boolean = true, val maxLength: Int = 500)
data class ToolSpec(
    val name: String, val description: String, val risk: Risk, val route: Route,
    val arguments: Map<String, Argument> = emptyMap(),
) {
    val requiresApproval: Boolean get() = risk == Risk.EXTERNAL || risk == Risk.SENSITIVE
    fun validate(values: Map<String, String>) {
        require(values.keys.all { it in arguments }) { "Неизвестные аргументы инструмента." }
        arguments.forEach { (key, spec) ->
            require(!spec.required || !values[key].isNullOrBlank()) { "Нужен аргумент: $key" }
            require((values[key]?.length ?: 0) <= spec.maxLength) { "Слишком длинный аргумент: $key" }
        }
    }
}

/** The model cannot provide a risk level, arbitrary URI, shell command or API endpoint. */
object PreviewTools {
    val all = listOf(
        ToolSpec("workspace_read", "Read local AIS tasks, contacts and deals, including their IDs. Not a remote CRM sync.", Risk.READ, Route.WORKSPACE),
        ToolSpec("task_add", "Create a local task. Dates are device-local calendar dates; this is not a notification/alarm.", Risk.LOCAL, Route.WORKSPACE,
            mapOf("title" to Argument("Task title"), "due_date" to Argument("Optional YYYY-MM-DD date", false, 10))),
        ToolSpec("task_set_status", "Mark an existing local task done or reopen it. Reversible.", Risk.LOCAL, Route.WORKSPACE,
            mapOf("id" to Argument("Exact task ID from workspace_read", maxLength = 80), "status" to Argument("done or open", maxLength = 4))),
        ToolSpec("contact_add", "Save a contact in AIS only. Does not change the Android address book.", Risk.LOCAL, Route.WORKSPACE,
            mapOf("name" to Argument("Display name", maxLength = 200), "channel" to Argument("Optional email, phone or username", false, 200))),
        ToolSpec("deal_add", "Create a local opportunity in AIS, not in a remote CRM.", Risk.LOCAL, Route.WORKSPACE,
            mapOf("title" to Argument("Opportunity title"))),
        ToolSpec("apps_list", "List launchable Android apps and package names. Only use when needed for the user's goal.", Risk.READ, Route.ANDROID),
        ToolSpec("app_open", "Ask Android to open an installed app. Handoff only; does not operate its UI or verify its screen.", Risk.LOCAL, Route.ANDROID,
            mapOf("package" to Argument("Exact package from apps_list", maxLength = 200))),
        ToolSpec("message_draft", "After approval, open Android's share chooser with this exact draft. User chooses recipient and sends manually. Never claim it was sent.", Risk.EXTERNAL, Route.ANDROID,
            mapOf("text" to Argument("Exact draft text", maxLength = 4_000))),
    )
}

data class ToolCall(val id: String, val name: String, val arguments: Map<String, String>)
data class ToolResult(val summary: String, val evidence: String = "", val status: Outcome = Outcome.VERIFIED)
enum class Outcome { VERIFIED, HANDOFF, FAILED, REJECTED }
data class ToolExchange(val call: ToolCall, val result: ToolResult)
data class ModelTurn(val text: String = "", val calls: List<ToolCall> = emptyList())

interface PreviewModel {
    suspend fun next(goal: String, history: List<ChatMessage>, exchanges: List<ToolExchange>): ModelTurn
}
fun interface ToolHost { suspend fun execute(spec: ToolSpec, call: ToolCall): ToolResult }
fun interface ApprovalGate { suspend fun approve(spec: ToolSpec, call: ToolCall): Boolean }

/** Single execution path. A failed/denied native action never silently falls back to UI automation. */
class ToolRouter(
    specs: List<ToolSpec>, private val host: ToolHost, private val approval: ApprovalGate,
) {
    private val registry = specs.associateBy { it.name }
    init { require(registry.size == specs.size) { "Duplicate tool names" } }

    suspend fun execute(call: ToolCall, onStage: suspend (ToolSpec, String) -> Unit): ToolResult {
        val spec = registry[call.name] ?: return ToolResult("Неизвестный инструмент; действие заблокировано.", status = Outcome.FAILED)
        val frozen = call.copy(arguments = call.arguments.toMap())
        try { spec.validate(frozen.arguments) } catch (e: IllegalArgumentException) {
            return ToolResult(e.message ?: "Неверные аргументы.", status = Outcome.FAILED)
        }
        currentCoroutineContext().ensureActive()
        if (spec.requiresApproval) {
            onStage(spec, "WAITING")
            if (!approval.approve(spec, frozen)) {
                return ToolResult("Вы отклонили действие. Оно не выполнено.", status = Outcome.REJECTED)
            }
        }
        // Stop while the approval dialog is closing must still prevent execution.
        currentCoroutineContext().ensureActive()
        onStage(spec, "STARTED")
        currentCoroutineContext().ensureActive()
        return try {
            host.execute(spec, frozen)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // SDK/server exception bodies may contain credentials: never persist them.
            ToolResult("Инструмент завершился ошибкой. Результат не подтверждён; автоматического повтора нет.", status = Outcome.FAILED)
        }
    }
}

/** Small bounded agent loop; network failure cannot make the launcher unusable. */
class PreviewRuntime(
    private val model: PreviewModel,
    private val router: ToolRouter,
    private val maxTurns: Int = 8,
) {
    init { require(maxTurns in 1..20) }
    suspend fun run(
        goal: String, history: List<ChatMessage>,
        onStage: suspend (ToolCall, ToolSpec, String) -> Unit,
        onResult: suspend (ToolExchange) -> Unit,
    ): String {
        require(goal.isNotBlank() && goal.length <= 4_000)
        val exchanges = mutableListOf<ToolExchange>()
        val ids = mutableSetOf<String>()
        val writes = mutableSetOf<Pair<String, Map<String, String>>>()
        repeat(maxTurns) {
            currentCoroutineContext().ensureActive()
            val turn = withTimeout(60_000) { model.next(goal, history.takeLast(16), exchanges.toList()) }
            currentCoroutineContext().ensureActive()
            if (turn.calls.isEmpty()) {
                return turn.text.take(16_000).ifBlank { "Модель вернула пустой ответ. Действий не выполнено." }
            }
            // Reject the whole malformed batch before its first effect.
            if (turn.calls.size > 4 || turn.calls.any { it.id.isBlank() || it.id in ids } ||
                turn.calls.map { it.id }.distinct().size != turn.calls.size) {
                return "Некорректный или повторный набор действий модели. Выполнение остановлено."
            }
            for (call in turn.calls) {
                ids += call.id
                val spec = PreviewTools.all.find { it.name == call.name }
                if (spec?.risk != Risk.READ && !writes.add(call.name to call.arguments.toMap())) {
                    return "Повторная запись остановлена. Проверьте журнал перед новым запросом."
                }
                val result = router.execute(call) { tool, stage -> onStage(call, tool, stage) }
                val exchange = ToolExchange(call, result)
                onResult(exchange)
                exchanges += exchange
                // A handoff is not remote completion. A refusal/error stops the whole run,
                // including later calls in the same batch: there is no alternative-route retry.
                if (result.status != Outcome.VERIFIED) return result.summary
            }
        }
        return "Достигнут лимит шагов. Выполнение остановлено; уже выполненные действия показаны в журнале."
    }
}
