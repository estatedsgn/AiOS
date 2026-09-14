package ai.aios.app

import ai.aios.core.agent.AgentEvent
import ai.aios.core.agent.Approval
import ai.aios.core.agent.HumanInterface
import ai.aios.core.device.AgentAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The single source of truth for a run, shared by the foreground service that
 * executes it and the UI that watches it.
 *
 * It is also the [HumanInterface]: when the safety policy gates an action, the
 * runner suspends on a [CompletableDeferred] here until the user taps Approve
 * or Reject in the activity or the notification.
 */
object AgentSession : HumanInterface {

    enum class Status { IDLE, RUNNING, WAITING_FOR_USER, DONE }

    data class LogEntry(
        val step: Int?,
        val kind: Kind,
        val text: String,
        val detail: String? = null,
    ) {
        enum class Kind { GOAL, THINKING, ACTION, APPROVAL, REFUSED, RESULT, QUESTION, DONE, ERROR }
    }

    data class ApprovalRequest(val action: AgentAction, val reason: String)
    data class QuestionRequest(val question: String)

    private val _log = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = _log.asStateFlow()

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _pendingApproval = MutableStateFlow<ApprovalRequest?>(null)
    val pendingApproval: StateFlow<ApprovalRequest?> = _pendingApproval.asStateFlow()

    private val _pendingQuestion = MutableStateFlow<QuestionRequest?>(null)
    val pendingQuestion: StateFlow<QuestionRequest?> = _pendingQuestion.asStateFlow()

    /** Running total for the current run, so the cost of the user's key is visible. */
    private val _tokensUsed = MutableStateFlow(0L to 0L)
    val tokensUsed: StateFlow<Pair<Long, Long>> = _tokensUsed.asStateFlow()

    private var approvalGate: CompletableDeferred<Approval>? = null
    private var answerGate: CompletableDeferred<String?>? = null

    fun startRun(goal: String) {
        _log.value = listOf(LogEntry(null, LogEntry.Kind.GOAL, goal))
        _status.value = Status.RUNNING
        _tokensUsed.value = 0L to 0L
        _pendingApproval.value = null
        _pendingQuestion.value = null
    }

    fun clear() {
        if (_status.value == Status.RUNNING || _status.value == Status.WAITING_FOR_USER) return
        _log.value = emptyList()
        _status.value = Status.IDLE
    }

    /** Called when the run's coroutine ends, however it ended. */
    fun markFinished() {
        _status.value = Status.DONE
        releaseGates()
    }

    /** Frees anything still suspended, so a cancelled run cannot leave the loop parked. */
    private fun releaseGates() {
        approvalGate?.takeIf { !it.isCompleted }?.complete(Approval.REJECTED)
        answerGate?.takeIf { !it.isCompleted }?.complete(null)
        approvalGate = null
        answerGate = null
        _pendingApproval.value = null
        _pendingQuestion.value = null
    }

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Started ->
                Unit // startRun already logged the goal.

            is AgentEvent.Thinking ->
                append(LogEntry(event.step, LogEntry.Kind.THINKING, "Thinking…"))

            is AgentEvent.ActionProposed ->
                append(LogEntry(event.step, LogEntry.Kind.ACTION, describe(event.action), event.action.rationale))

            is AgentEvent.AwaitingApproval ->
                append(LogEntry(event.step, LogEntry.Kind.APPROVAL, "Waiting for your approval", event.reason))

            is AgentEvent.ActionExecuted ->
                append(
                    LogEntry(
                        event.step,
                        if (event.ok) LogEntry.Kind.RESULT else LogEntry.Kind.ERROR,
                        event.detail,
                    )
                )

            is AgentEvent.ActionRefused ->
                append(
                    LogEntry(
                        event.step,
                        LogEntry.Kind.REFUSED,
                        if (event.byUser) "You rejected this action" else "Blocked by policy",
                        event.reason,
                    )
                )

            is AgentEvent.QuestionAsked ->
                append(LogEntry(null, LogEntry.Kind.QUESTION, event.question))

            is AgentEvent.UsageReported ->
                _tokensUsed.update { (input, output) ->
                    (input + event.usage.inputTokens) to (output + event.usage.outputTokens)
                }

            is AgentEvent.Finished -> {
                append(
                    LogEntry(
                        null,
                        if (event.success) LogEntry.Kind.DONE else LogEntry.Kind.ERROR,
                        event.summary,
                    )
                )
                markFinished()
            }

            is AgentEvent.Failed -> {
                append(LogEntry(null, LogEntry.Kind.ERROR, event.message))
                markFinished()
            }

            AgentEvent.Cancelled -> {
                append(LogEntry(null, LogEntry.Kind.ERROR, "Run cancelled."))
                markFinished()
            }
        }
    }

    // --- HumanInterface -----------------------------------------------------

    override suspend fun requestApproval(action: AgentAction, reason: String): Approval {
        val gate = CompletableDeferred<Approval>()
        approvalGate = gate
        _pendingApproval.value = ApprovalRequest(action, reason)
        _status.value = Status.WAITING_FOR_USER

        val decision = gate.await()

        _pendingApproval.value = null
        approvalGate = null
        _status.value = Status.RUNNING
        return decision
    }

    override suspend fun askQuestion(question: String): String? {
        val gate = CompletableDeferred<String?>()
        answerGate = gate
        _pendingQuestion.value = QuestionRequest(question)
        _status.value = Status.WAITING_FOR_USER

        val answer = gate.await()

        _pendingQuestion.value = null
        answerGate = null
        _status.value = Status.RUNNING
        return answer
    }

    // --- called from the UI -------------------------------------------------

    fun submitApproval(approved: Boolean) {
        approvalGate?.takeIf { !it.isCompleted }
            ?.complete(if (approved) Approval.APPROVED else Approval.REJECTED)
    }

    fun submitAnswer(answer: String?) {
        answerGate?.takeIf { !it.isCompleted }?.complete(answer?.takeIf { it.isNotBlank() })
    }

    private fun append(entry: LogEntry) = _log.update { current ->
        // Collapse consecutive "Thinking..." lines so a long run stays readable.
        if (entry.kind == LogEntry.Kind.THINKING && current.lastOrNull()?.kind == LogEntry.Kind.THINKING) {
            current.dropLast(1) + entry
        } else {
            current + entry
        }
    }

    /** Human-readable one-liner for the log and the approval prompt. */
    fun describe(action: AgentAction): String = when (action) {
        is AgentAction.Tap -> "Tap element [${action.elementId}]"
        is AgentAction.LongPress -> "Press and hold element [${action.elementId}]"
        is AgentAction.TypeText ->
            "Type \"${action.text}\"" + if (action.submit) " and submit" else ""

        is AgentAction.Swipe -> "Swipe ${action.direction.name.lowercase()}"
        is AgentAction.PressKey -> "Press ${action.key.name.lowercase().replace('_', ' ')}"
        is AgentAction.LaunchApp -> "Open ${action.packageName}"
        is AgentAction.Wait -> "Wait ${action.millis} ms"
        is AgentAction.AskUser -> "Ask: ${action.question}"
        is AgentAction.Finish -> action.summary
    }
}
