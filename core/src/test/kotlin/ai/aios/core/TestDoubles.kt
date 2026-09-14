package ai.aios.core

import ai.aios.core.agent.Approval
import ai.aios.core.agent.HumanInterface
import ai.aios.core.device.ActionResult
import ai.aios.core.device.AgentAction
import ai.aios.core.device.Bounds
import ai.aios.core.device.DeviceController
import ai.aios.core.device.DeviceKey
import ai.aios.core.device.InstalledApp
import ai.aios.core.device.ScreenElement
import ai.aios.core.device.ScreenGraph
import ai.aios.core.device.SwipeDirection
import ai.aios.core.planner.Decision
import ai.aios.core.planner.Observation
import ai.aios.core.planner.Planner

/** Records everything done to it and hands back scripted screens. */
class FakeDevice(
    private val screens: MutableList<ScreenGraph>,
    private val apps: List<InstalledApp> = listOf(InstalledApp("com.example.notes", "Notes")),
) : DeviceController {

    val performed = mutableListOf<String>()
    var failNext: String? = null

    constructor(vararg screens: ScreenGraph) : this(screens.toMutableList())

    /** Screens are consumed in order; the last one repeats once exhausted. */
    override suspend fun readScreen(): ScreenGraph =
        if (screens.size > 1) screens.removeAt(0) else screens.first()

    private fun record(what: String): ActionResult {
        performed += what
        failNext?.let { failNext = null; return ActionResult.failed(it) }
        return ActionResult.ok("ok")
    }

    override suspend fun tap(element: ScreenElement) = record("tap:${element.id}")
    override suspend fun longPress(element: ScreenElement) = record("long:${element.id}")
    override suspend fun typeText(element: ScreenElement, text: String, submit: Boolean) =
        record("type:${element.id}:$text:$submit")

    override suspend fun swipe(direction: SwipeDirection, element: ScreenElement?) =
        record("swipe:$direction:${element?.id}")

    override suspend fun pressKey(key: DeviceKey) = record("key:$key")
    override suspend fun launchApp(packageName: String) = record("launch:$packageName")
    override suspend fun installedApps(): List<InstalledApp> = apps
}

/** Replays a fixed list of actions, so loop behaviour is tested without a network. */
class ScriptedPlanner(private val actions: MutableList<AgentAction>) : Planner {
    constructor(vararg actions: AgentAction) : this(actions.toMutableList())

    val observations = mutableListOf<Observation>()
    var resetCount = 0

    override fun reset() {
        resetCount++
    }

    override suspend fun decide(observation: Observation): Decision {
        observations += observation
        val action = if (actions.isEmpty()) {
            AgentAction.Finish("ran out of script", success = false, rationale = "script empty")
        } else {
            actions.removeAt(0)
        }
        return Decision(action = action)
    }
}

/** A planner that always proposes the same action - for stuck detection. */
class RepeatingPlanner(private val action: AgentAction) : Planner {
    override fun reset() = Unit
    override suspend fun decide(observation: Observation) = Decision(action = action)
}

class ScriptedHuman(
    private val approvals: MutableList<Approval> = mutableListOf(),
    private val answers: MutableList<String?> = mutableListOf(),
) : HumanInterface {
    val approvalRequests = mutableListOf<String>()
    val questions = mutableListOf<String>()

    override suspend fun requestApproval(action: AgentAction, reason: String): Approval {
        approvalRequests += reason
        return if (approvals.isEmpty()) Approval.APPROVED else approvals.removeAt(0)
    }

    override suspend fun askQuestion(question: String): String? {
        questions += question
        return if (answers.isEmpty()) "yes" else answers.removeAt(0)
    }

    companion object {
        fun approving() = ScriptedHuman()
        fun rejecting() = ScriptedHuman(approvals = mutableListOf(Approval.REJECTED))
    }
}

// --- screen fixtures --------------------------------------------------------

fun element(
    id: Int,
    role: String = "button",
    label: String = "Button $id",
    affordances: Set<ai.aios.core.device.Affordance> = setOf(ai.aios.core.device.Affordance.TAP),
    password: Boolean = false,
) = ScreenElement(
    id = id,
    role = role,
    label = label,
    bounds = Bounds(0, id * 100, 1080, id * 100 + 90),
    affordances = affordances,
    password = password,
)

fun screen(
    vararg elements: ScreenElement,
    pkg: String? = "com.example.notes",
) = ScreenGraph(
    appPackage = pkg,
    screenWidth = 1080,
    screenHeight = 2340,
    elements = elements.toList(),
)
