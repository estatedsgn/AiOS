package ai.aios.core.workspace

import java.util.UUID
import java.util.concurrent.CancellationException

// APK-neutral records. Credentials belong in a separate credential store, never here.
data class Contact(val id: String, val displayName: String, val channels: Map<String, String> = emptyMap(), val tags: List<String> = emptyList())
data class Conversation(val id: String, val contactId: String, val source: String, val externalId: String, val updatedAt: Long)
data class WorkItem(val id: String, val title: String, val contactId: String? = null, val dueAt: Long? = null, val done: Boolean = false, val kind: String = "task", val sourceRef: String? = null)
data class Deal(val id: String, val title: String, val contactId: String? = null, val stage: String = "new", val amountMinor: Long? = null, val currency: String? = null, val nextAction: String? = null)
enum class Risk { READ, LOCAL, EXTERNAL, SENSITIVE }
enum class Route { WORKSPACE, ANDROID, SERVICE_API, ACCESSIBILITY }
enum class ProposalStatus { READY, WAITING_APPROVAL, RUNNING, SUCCEEDED, HANDED_OFF, REJECTED, CANCELLED, BLOCKED, FAILED, UNKNOWN }
data class ToolCall(val tool: String, val arguments: Map<String, String> = emptyMap())
data class Proposal(val id: String, val call: ToolCall, val risk: Risk, val route: Route?, val status: ProposalStatus, val createdAt: Long, val reason: String, val evidence: String = "")
data class Activity(val id: String, val proposalId: String, val source: String, val type: String, val summary: String, val occurredAt: Long, val sourceRef: String? = null)
data class Workspace(val contacts: List<Contact> = emptyList(), val conversations: List<Conversation> = emptyList(), val workItems: List<WorkItem> = emptyList(), val deals: List<Deal> = emptyList(), val proposals: List<Proposal> = emptyList(), val activities: List<Activity> = emptyList())

interface WorkspaceStore {
    fun load(): Workspace
    /** Must atomically replace the snapshot or throw; never report a partial write as success. */
    fun save(workspace: Workspace)
}

enum class Verification { LOCAL_READBACK, HANDOFF_ONLY, SCREEN_METADATA, REMOTE_RECEIPT }
data class ToolSpec(val id: String, val risk: Risk, val fields: Set<String>, val routes: Set<Route>, val verification: Verification)
object PreviewTools {
    val specs = listOf(
        ToolSpec("workspace.task.create", Risk.LOCAL, setOf("title"), setOf(Route.WORKSPACE), Verification.LOCAL_READBACK),
        ToolSpec("workspace.task.toggle", Risk.LOCAL, setOf("id"), setOf(Route.WORKSPACE), Verification.LOCAL_READBACK),
        ToolSpec("workspace.brief", Risk.READ, emptySet(), setOf(Route.WORKSPACE), Verification.LOCAL_READBACK),
        ToolSpec("calendar.draft", Risk.EXTERNAL, setOf("title"), setOf(Route.ANDROID, Route.SERVICE_API), Verification.HANDOFF_ONLY),
        ToolSpec("android.settings", Risk.LOCAL, emptySet(), setOf(Route.ANDROID), Verification.HANDOFF_ONLY),
        ToolSpec("screen.inspect", Risk.READ, emptySet(), setOf(Route.ACCESSIBILITY), Verification.SCREEN_METADATA),
        ToolSpec("crm.task.create", Risk.EXTERNAL, setOf("title"), setOf(Route.SERVICE_API), Verification.REMOTE_RECEIPT),
        ToolSpec("device.permissions.change", Risk.SENSITIVE, emptySet(), setOf(Route.ANDROID), Verification.REMOTE_RECEIPT),
    ).associateBy { it.id }

    fun validate(call: ToolCall): ToolSpec {
        val spec = requireNotNull(specs[call.tool]) { "Неизвестный инструмент" }
        require(call.arguments.keys == spec.fields) { "Неверные аргументы инструмента" }
        require(call.arguments.values.all { it.isNotBlank() && it.length <= 256 && it.none { c -> c.isISOControl() } }) { "Аргумент должен содержать 1–256 символов без управляющих знаков" }
        return spec
    }

    fun requiresApproval(risk: Risk) = risk == Risk.EXTERNAL || risk == Risk.SENSITIVE
}

/** Deterministic offline entry point, deliberately not advertised as a general LLM planner. */
object PreviewCommands {
    private val task = Regex("^(?:задача|создай задачу|task|create task)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
    fun parse(input: String): ToolCall {
        val text = input.trim()
        task.matchEntire(text)?.let { return ToolCall("workspace.task.create", mapOf("title" to it.groupValues[1].trim())) }
        return when (text.lowercase(java.util.Locale.ROOT)) {
            "сводка", "brief" -> ToolCall("workspace.brief")
            "открой настройки", "open settings" -> ToolCall("android.settings")
            "покажи экран", "inspect screen" -> ToolCall("screen.inspect")
            else -> throw IllegalArgumentException("Офлайн-команды: «Задача: …», «Сводка», «Открой настройки», «Покажи экран».")
        }
    }
}

data class ToolOutcome(val status: ProposalStatus, val evidence: String)
interface ToolPort {
    val route: Route
    /** Capability probing only: no I/O side effects or permission prompts. */
    fun available(tool: String): Boolean
    suspend fun execute(call: ToolCall): ToolOutcome
}

/** Route once, approve that exact route and payload, and never retry through another channel. */
class ToolRouter(private val ports: List<ToolPort>) {
    init { require(ports.map { it.route }.distinct().size == ports.size) { "Duplicate route" } }
    fun select(spec: ToolSpec): Route? {
        if (Route.WORKSPACE in spec.routes) return Route.WORKSPACE
        return listOf(Route.ANDROID, Route.SERVICE_API, Route.ACCESSIBILITY).firstOrNull { route ->
            route in spec.routes && ports.any { it.route == route && it.available(spec.id) }
        }
    }
    internal suspend fun dispatch(proposal: Proposal): ToolOutcome {
        val spec = PreviewTools.validate(proposal.call)
        require(proposal.status == ProposalStatus.RUNNING && proposal.risk == spec.risk && spec.risk != Risk.SENSITIVE)
        require(proposal.route in spec.routes)
        val port = ports.singleOrNull { it.route == proposal.route && it.available(spec.id) }
            ?: return ToolOutcome(ProposalStatus.BLOCKED, "Маршрут недоступен. Автоматической замены нет.")
        val outcome = port.execute(proposal.call)
        require(outcome.status in setOf(ProposalStatus.SUCCEEDED, ProposalStatus.HANDED_OFF, ProposalStatus.FAILED, ProposalStatus.UNKNOWN, ProposalStatus.BLOCKED))
        require(outcome.evidence.isNotBlank() && outcome.evidence.length <= 1024)
        if (spec.verification == Verification.HANDOFF_ONLY && outcome.status == ProposalStatus.SUCCEEDED) {
            return ToolOutcome(ProposalStatus.UNKNOWN, "Запуск редактора не доказывает сохранение данных.")
        }
        return outcome
    }
}

/**
 * One process-wide instance owns a bounded, atomically persisted workspace.
 * Mutations are serialized; port execution is outside the lock so Stop remains available.
 * A RUNNING claim is durable BEFORE dispatch. Neither crashes nor repeated approval replay it.
 */
class WorkspaceEngine(
    private val store: WorkspaceStore,
    private val router: ToolRouter,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val lock = Any()
    @Volatile var state: Workspace = store.load()
        private set

    init {
        // A restart is not consent to execute. Even previously approved READY entries expire.
        val recovered = state.proposals.map { p ->
            when (p.status) {
                ProposalStatus.RUNNING -> p.copy(status = ProposalStatus.UNKNOWN, evidence = "Процесс прерван. Результат неизвестен; повторного запуска нет.")
                ProposalStatus.READY, ProposalStatus.WAITING_APPROVAL -> p.copy(status = ProposalStatus.CANCELLED, evidence = "После перезапуска создайте новое предложение.")
                else -> p
            }
        }
        if (recovered != state.proposals) {
            var next = state.copy(proposals = recovered)
            recovered.filter { p -> state.proposals.first { it.id == p.id }.status != p.status }.forEach { p ->
                next = withActivity(next, p, p.status.name, p.evidence)
            }
            commit(next)
        }
    }

    fun request(call: ToolCall): String = synchronized(lock) {
        val frozen = call.copy(arguments = call.arguments.toMap())
        val spec = PreviewTools.validate(frozen)
        // Bound storage without discarding pending approvals or unfinished work items.
        val removable = state.proposals.firstOrNull { it.status !in activeStatuses }
        val kept = if (state.proposals.size < 100) state.proposals else {
            requireNotNull(removable) { "Очередь заполнена. Остановите ожидающие действия." }
            state.proposals.filterNot { it.id == removable.id }
        }
        val route = if (spec.risk == Risk.SENSITIVE) null else router.select(spec)
        val status = when {
            spec.risk == Risk.SENSITIVE || route == null -> ProposalStatus.BLOCKED
            PreviewTools.requiresApproval(spec.risk) -> ProposalStatus.WAITING_APPROVAL
            else -> ProposalStatus.READY
        }
        val reason = when {
            spec.risk == Risk.SENSITIVE -> "SENSITIVE отключён в APK-срезе: права и безопасность не изменяются."
            route == null -> "Нет подключённого маршрута. CRM API / автоматизация не имитируются."
            status == ProposalStatus.WAITING_APPROVAL -> if (spec.id == "calendar.draft") "Открыть редактор календаря с этим заголовком. Дату, время и сохранение подтверждаете в календаре." else "Подтвердите внешнее действие, точные аргументы и выбранный маршрут."
            else -> "Офлайн developer preview"
        }
        val p = Proposal(newId(), frozen, spec.risk, route, status, now(), reason)
        commit(withActivity(state.copy(proposals = kept + p), p, status.name, reason))
        p.id
    }

    fun approve(id: String): Boolean = synchronized(lock) {
        val p = state.proposals.find { it.id == id } ?: return false
        if (p.status != ProposalStatus.WAITING_APPROVAL) return false
        // Clock rollback must not extend an approval lease indefinitely.
        if (now() < p.createdAt || now() - p.createdAt > APPROVAL_TTL_MS) {
            replace(p.copy(status = ProposalStatus.CANCELLED, evidence = "Подтверждение истекло. Создайте новое предложение."))
            return false
        }
        replace(p.copy(status = ProposalStatus.READY, evidence = "Однократное подтверждение точного действия и маршрута."))
        true
    }

    fun reject(id: String) = synchronized(lock) {
        state.proposals.find { it.id == id && it.status == ProposalStatus.WAITING_APPROVAL }?.let {
            replace(it.copy(status = ProposalStatus.REJECTED, evidence = "Отклонено пользователем."))
        }
        Unit
    }

    fun stop() = synchronized(lock) {
        val changed = state.proposals.filter { it.status in activeStatuses }
        if (changed.isEmpty()) return@synchronized
        var next = state
        changed.forEach { p ->
            val stopped = p.copy(
                status = if (p.status == ProposalStatus.RUNNING) ProposalStatus.UNKNOWN else ProposalStatus.CANCELLED,
                evidence = if (p.status == ProposalStatus.RUNNING) "Остановлено во время передачи. Уже выполненное внешнее действие отозвать нельзя." else "Отменено до выполнения.",
            )
            next = withActivity(next.copy(proposals = next.proposals.map { if (it.id == p.id) stopped else it }), stopped, stopped.status.name, stopped.evidence)
        }
        commit(next)
    }

    suspend fun execute(id: String) {
        val claimed = synchronized(lock) {
            val p = state.proposals.find { it.id == id && it.status == ProposalStatus.READY } ?: return
            if (PreviewTools.requiresApproval(p.risk) && (now() < p.createdAt || now() - p.createdAt > APPROVAL_TTL_MS)) {
                replace(p.copy(status = ProposalStatus.CANCELLED, evidence = "Срок подтверждения истёк до выполнения."))
                return
            }
            if (p.route == Route.WORKSPACE) {
                executeLocal(p)
                return
            }
            val running = p.copy(status = ProposalStatus.RUNNING)
            replace(running) // save failure here prevents any external effect
            running
        }
        val outcome = try {
            router.dispatch(claimed)
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                if (state.proposals.any { it.id == id && it.status == ProposalStatus.RUNNING }) {
                    replace(claimed.copy(status = ProposalStatus.UNKNOWN, evidence = "Выполнение прервано. Автоматического повтора нет."))
                }
            }
            throw cancelled
        } catch (_: Exception) {
            // Do not leak provider exception text (which may contain credentials) into records.
            ToolOutcome(ProposalStatus.UNKNOWN, "Ошибка инструмента; результат неизвестен. Без автоматического повтора.")
        }
        synchronized(lock) {
            if (state.proposals.any { it.id == id && it.status == ProposalStatus.RUNNING }) {
                replace(claimed.copy(status = outcome.status, evidence = outcome.evidence))
            }
        }
    }

    fun brief(): String = synchronized(lock) { briefOf(state) }

    private fun executeLocal(p: Proposal) {
        var next = state
        val outcome = when (p.call.tool) {
            "workspace.task.create" -> {
                if (state.workItems.size >= 100) ToolOutcome(ProposalStatus.BLOCKED, "Лимит preview: 100 локальных задач.")
                else {
                    val item = WorkItem(id = newId(), title = p.call.arguments.getValue("title"))
                    next = next.copy(workItems = next.workItems + item)
                    check(next.workItems.any { it.id == item.id && it.title == item.title })
                    ToolOutcome(ProposalStatus.SUCCEEDED, "Локальная задача сохранена: ${item.id}")
                }
            }
            "workspace.task.toggle" -> {
                val item = next.workItems.find { it.id == p.call.arguments["id"] }
                if (item == null) ToolOutcome(ProposalStatus.FAILED, "Задача не найдена.")
                else {
                    next = next.copy(workItems = next.workItems.map { if (it.id == item.id) it.copy(done = !it.done) else it })
                    check(next.workItems.first { it.id == item.id }.done != item.done)
                    ToolOutcome(ProposalStatus.SUCCEEDED, "Статус локальной задачи изменён; действие обратимо.")
                }
            }
            "workspace.brief" -> ToolOutcome(ProposalStatus.SUCCEEDED, briefOf(next))
            else -> ToolOutcome(ProposalStatus.BLOCKED, "Неизвестная локальная операция.")
        }
        val done = p.copy(status = outcome.status, evidence = outcome.evidence)
        // Entity, proposal and audit event are one durable transaction.
        commit(withActivity(next.copy(proposals = next.proposals.map { if (it.id == p.id) done else it }), done, done.status.name, done.evidence))
    }

    private fun briefOf(s: Workspace): String = "Открытых задач: ${s.workItems.count { !it.done }}; выполнено: ${s.workItems.count { it.done }}; ожидают подтверждения: ${s.proposals.count { it.status == ProposalStatus.WAITING_APPROVAL }}. Только локальные записи; CRM не подключена."
    private fun replace(p: Proposal) = commit(withActivity(state.copy(proposals = state.proposals.map { if (it.id == p.id) p else it }), p, p.status.name, p.evidence.ifBlank { p.reason }))
    private fun withActivity(s: Workspace, p: Proposal, type: String, summary: String) = s.copy(activities = (s.activities + Activity(newId(), p.id, p.route?.name ?: "UNAVAILABLE", type, summary, now())).takeLast(200))
    private fun commit(next: Workspace) { store.save(next); state = next }
    companion object {
        const val APPROVAL_TTL_MS = 5 * 60 * 1000L
        val activeStatuses = setOf(ProposalStatus.READY, ProposalStatus.WAITING_APPROVAL, ProposalStatus.RUNNING)
    }
}
