package ai.aios.core.planner

import ai.aios.core.device.AgentAction
import ai.aios.core.device.InstalledApp
import ai.aios.core.device.ScreenGraph

/** What the agent knows at the moment it has to choose an action. */
data class Observation(
    val goal: String,
    val screen: ScreenGraph,
    /** Result of the previous action, fed back so the model can self-correct. */
    val lastResult: String? = null,
    /** Sent once, on the first turn, so the model knows what it can open. */
    val installedApps: List<InstalledApp>? = null,
    /** Set when the run resumes after an [AgentAction.AskUser]. */
    val userAnswer: String? = null,
)

data class TokenUsage(val inputTokens: Long, val outputTokens: Long, val cacheReadTokens: Long = 0)

/** The model's choice for this turn. */
data class Decision(
    val action: AgentAction?,
    /** Prose the model emitted alongside the call, if any. */
    val note: String? = null,
    val usage: TokenUsage? = null,
    /** Set when no usable action came back. */
    val error: String? = null,
)

/**
 * Chooses the next action. Separating this from the loop lets tests drive the
 * agent with a scripted planner and no network.
 */
interface Planner {
    suspend fun decide(observation: Observation): Decision
    fun reset()
}
