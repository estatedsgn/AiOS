package ai.aios.core.agent

import ai.aios.core.device.AgentAction
import ai.aios.core.planner.TokenUsage

/** Everything the UI needs to render a run as it happens. */
sealed interface AgentEvent {
    data class Started(val goal: String) : AgentEvent
    data class Thinking(val step: Int) : AgentEvent

    data class ActionProposed(
        val step: Int,
        val action: AgentAction,
        val note: String?,
    ) : AgentEvent

    /** The run is parked until [AgentRunner] receives an approval decision. */
    data class AwaitingApproval(
        val step: Int,
        val action: AgentAction,
        val reason: String,
    ) : AgentEvent

    data class ActionExecuted(
        val step: Int,
        val action: AgentAction,
        val ok: Boolean,
        val detail: String,
    ) : AgentEvent

    data class ActionRefused(
        val step: Int,
        val action: AgentAction,
        val reason: String,
        val byUser: Boolean,
    ) : AgentEvent

    data class QuestionAsked(val question: String) : AgentEvent
    data class UsageReported(val step: Int, val usage: TokenUsage) : AgentEvent

    data class Finished(val success: Boolean, val summary: String) : AgentEvent
    data class Failed(val message: String) : AgentEvent
    data object Cancelled : AgentEvent
}

/** How a gated action was resolved. */
enum class Approval { APPROVED, REJECTED }

/**
 * The human in the loop. Android backs this with the notification / UI prompt;
 * tests back it with a canned script.
 */
interface HumanInterface {
    /** Blocks the run until the owner approves or rejects. */
    suspend fun requestApproval(action: AgentAction, reason: String): Approval

    /** Answers an `ask_user` call. Returning null ends the run. */
    suspend fun askQuestion(question: String): String?
}
