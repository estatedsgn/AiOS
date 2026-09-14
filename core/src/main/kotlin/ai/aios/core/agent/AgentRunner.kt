package ai.aios.core.agent

import ai.aios.core.device.ActionResult
import ai.aios.core.device.AgentAction
import ai.aios.core.device.DeviceController
import ai.aios.core.device.ScreenElement
import ai.aios.core.device.ScreenGraph
import ai.aios.core.planner.Observation
import ai.aios.core.planner.Planner
import ai.aios.core.safety.ActionPolicy
import ai.aios.core.safety.Verdict
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class RunnerConfig(
    /** Hard ceiling on actions per run - a runaway agent costs money and taps. */
    val maxSteps: Int = 40,
    /** Pause after each action so the UI it drives has time to settle. */
    val settleDelayMillis: Long = 600,
    /** Identical action on an unchanged screen this many times aborts the run. */
    val stuckThreshold: Int = 3,
)

/**
 * The agent loop: read the screen, decide, check the decision against policy,
 * execute, repeat.
 *
 * Emitted as a [Flow] so the Android layer can render each step live and so
 * cancelling the collector cancels the run - there is no separate stop flag to
 * get out of sync with reality.
 */
class AgentRunner(
    private val device: DeviceController,
    private val planner: Planner,
    private val policy: ActionPolicy,
    private val human: HumanInterface,
    private val config: RunnerConfig = RunnerConfig(),
) {

    fun run(goal: String): Flow<AgentEvent> = flow {
        planner.reset()
        emit(AgentEvent.Started(goal))

        var lastResult: String? = null
        var pendingAnswer: String? = null
        var firstTurn = true
        val recent = ArrayDeque<String>()

        for (step in 1..config.maxSteps) {
            currentCoroutineContext().ensureActive()

            val screen = runCatching { device.readScreen() }.getOrElse {
                emit(AgentEvent.Failed("Could not read the screen: ${it.message}"))
                return@flow
            }

            emit(AgentEvent.Thinking(step))

            val observation = Observation(
                goal = goal,
                screen = screen,
                lastResult = lastResult,
                installedApps = if (firstTurn) runCatching { device.installedApps() }.getOrNull() else null,
                userAnswer = pendingAnswer,
            )
            firstTurn = false
            pendingAnswer = null

            val decision = runCatching { planner.decide(observation) }.getOrElse {
                emit(AgentEvent.Failed("The model call failed: ${it.message}"))
                return@flow
            }

            decision.usage?.let { emit(AgentEvent.UsageReported(step, it)) }

            val action = decision.action
            if (action == null) {
                // Tell the model what was wrong and let it try again rather
                // than ending the run on one malformed call.
                lastResult = decision.error ?: "No action was produced. Call exactly one tool."
                continue
            }

            emit(AgentEvent.ActionProposed(step, action, decision.note))

            // --- terminal actions -------------------------------------------
            if (action is AgentAction.Finish) {
                emit(AgentEvent.Finished(action.success, action.summary))
                return@flow
            }
            if (action is AgentAction.AskUser) {
                emit(AgentEvent.QuestionAsked(action.question))
                val answer = human.askQuestion(action.question)
                if (answer == null) {
                    emit(AgentEvent.Cancelled)
                    return@flow
                }
                pendingAnswer = answer
                lastResult = "The user answered your question."
                continue
            }

            // --- loop detection ---------------------------------------------
            val signature = signatureOf(action, screen)
            recent.addLast(signature)
            if (recent.size > config.stuckThreshold) recent.removeFirst()
            if (recent.size == config.stuckThreshold && recent.all { it == signature }) {
                emit(
                    AgentEvent.Failed(
                        "Stopped: the same action was repeated ${config.stuckThreshold} times " +
                            "without the screen changing."
                    )
                )
                return@flow
            }

            // --- policy ------------------------------------------------------
            when (val verdict = policy.evaluate(action, screen)) {
                is Verdict.Blocked -> {
                    emit(AgentEvent.ActionRefused(step, action, verdict.reason, byUser = false))
                    lastResult = "BLOCKED: ${verdict.reason} Do not retry this; find another way " +
                        "or call finish."
                    continue
                }

                is Verdict.NeedsConfirmation -> {
                    emit(AgentEvent.AwaitingApproval(step, action, verdict.reason))
                    if (human.requestApproval(action, verdict.reason) == Approval.REJECTED) {
                        emit(AgentEvent.ActionRefused(step, action, "Rejected by the user.", byUser = true))
                        lastResult = "The user rejected that action. Treat it as a refusal: do not " +
                            "attempt the same effect another way."
                        continue
                    }
                }

                Verdict.Allow -> Unit
            }

            // --- execute -----------------------------------------------------
            val result = runCatching { execute(action, screen) }
                .getOrElse { ActionResult.failed("Execution threw: ${it.message}") }

            emit(AgentEvent.ActionExecuted(step, action, result.ok, result.message))
            lastResult = if (result.ok) result.message else "FAILED: ${result.message}"

            if (config.settleDelayMillis > 0) delay(config.settleDelayMillis)
        }

        emit(AgentEvent.Failed("Stopped after ${config.maxSteps} steps without finishing."))
    }

    private suspend fun execute(action: AgentAction, screen: ScreenGraph): ActionResult = when (action) {
        is AgentAction.Tap ->
            withElement(screen, action.elementId) { device.tap(it) }

        is AgentAction.LongPress ->
            withElement(screen, action.elementId) { device.longPress(it) }

        is AgentAction.TypeText ->
            withElement(screen, action.elementId) { device.typeText(it, action.text, action.submit) }

        is AgentAction.Swipe -> {
            val target = action.elementId?.let { screen.element(it) }
            device.swipe(action.direction, target)
        }

        is AgentAction.PressKey -> device.pressKey(action.key)

        is AgentAction.LaunchApp -> device.launchApp(action.packageName)

        is AgentAction.Wait -> {
            delay(action.millis)
            ActionResult.ok("Waited ${action.millis} ms.")
        }

        is AgentAction.AskUser, is AgentAction.Finish ->
            ActionResult.failed("Terminal actions are handled by the loop.")
    }

    /**
     * Resolves an element id against the screen the model was actually shown.
     * A stale or invented id is reported back as a normal failure so the model
     * can correct itself on the next turn.
     */
    private suspend fun withElement(
        screen: ScreenGraph,
        id: Int,
        block: suspend (ScreenElement) -> ActionResult,
    ): ActionResult {
        val element = screen.element(id)
            ?: return ActionResult.failed(
                "There is no element [$id] on this screen. Use only ids from the latest listing."
            )
        return block(element)
    }

    private fun signatureOf(action: AgentAction, screen: ScreenGraph): String {
        val actionKey = when (action) {
            is AgentAction.Tap -> "tap:${action.elementId}"
            is AgentAction.LongPress -> "long:${action.elementId}"
            is AgentAction.TypeText -> "type:${action.elementId}:${action.text}"
            is AgentAction.Swipe -> "swipe:${action.direction}:${action.elementId}"
            is AgentAction.PressKey -> "key:${action.key}"
            is AgentAction.LaunchApp -> "launch:${action.packageName}"
            is AgentAction.Wait -> "wait"
            else -> action.toString()
        }
        // Include the screen so a legitimately repeated action on a changed
        // screen is not mistaken for being stuck.
        return actionKey + "@" + screen.render().hashCode()
    }
}
