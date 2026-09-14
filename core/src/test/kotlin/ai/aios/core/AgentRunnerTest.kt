package ai.aios.core

import ai.aios.core.agent.AgentEvent
import ai.aios.core.agent.AgentRunner
import ai.aios.core.agent.RunnerConfig
import ai.aios.core.device.Affordance
import ai.aios.core.device.AgentAction
import ai.aios.core.safety.ActionPolicy
import ai.aios.core.safety.AutonomyMode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentRunnerTest {

    private fun runner(
        device: FakeDevice,
        planner: ai.aios.core.planner.Planner,
        human: ScriptedHuman = ScriptedHuman.approving(),
        policy: ActionPolicy = ActionPolicy(mode = AutonomyMode.AUTONOMOUS),
        config: RunnerConfig = RunnerConfig(settleDelayMillis = 0),
    ) = AgentRunner(device, planner, policy, human, config)

    @Test
    fun `executes actions in order then finishes`() = runTest {
        val device = FakeDevice(screen(element(0, label = "New note"), element(1, label = "Title")))
        val planner = ScriptedPlanner(
            AgentAction.Tap(0, "open the composer"),
            AgentAction.TypeText(1, "Groceries", submit = false, rationale = "set the title"),
            AgentAction.Finish("Created the note.", success = true, rationale = "goal met"),
        )

        val events = runner(device, planner).run("make a note").toList()

        assertEquals(listOf("tap:0", "type:1:Groceries:false"), device.performed)
        val finished = assertIs<AgentEvent.Finished>(events.last())
        assertTrue(finished.success)
        assertEquals(1, planner.resetCount, "each run must start from a clean conversation")
    }

    @Test
    fun `sends the installed app list only on the first turn`() = runTest {
        val device = FakeDevice(screen(element(0)))
        val planner = ScriptedPlanner(
            AgentAction.PressKey(ai.aios.core.device.DeviceKey.HOME, "go home"),
            AgentAction.Finish("done", true, "ok"),
        )

        runner(device, planner).run("goal").toList()

        assertEquals(2, planner.observations.size)
        assertTrue(planner.observations[0].installedApps?.isNotEmpty() == true)
        assertEquals(null, planner.observations[1].installedApps, "the app list must not be resent every turn")
    }

    @Test
    fun `a rejected action is reported as a refusal and the run continues`() = runTest {
        val device = FakeDevice(screen(element(0, label = "Send")))
        val planner = ScriptedPlanner(
            AgentAction.Tap(0, "send it"),
            AgentAction.Finish("stopped at the user's request", success = false, rationale = "refused"),
        )

        val events = runner(
            device,
            planner,
            human = ScriptedHuman.rejecting(),
            policy = ActionPolicy(mode = AutonomyMode.CONFIRM_SENSITIVE),
        ).run("send the message").toList()

        assertTrue(device.performed.isEmpty(), "a rejected action must never reach the device")
        val refusal = events.filterIsInstance<AgentEvent.ActionRefused>().single()
        assertTrue(refusal.byUser)
        // The model has to be told a rejection is a decision, not an obstacle.
        val feedback = planner.observations[1].lastResult.orEmpty()
        assertContains(feedback, "rejected")
        assertContains(feedback, "do not attempt the same effect another way", ignoreCase = true)
    }

    @Test
    fun `a blocked action never reaches the device and is fed back`() = runTest {
        val device = FakeDevice(screen(element(0), pkg = "com.example.notes"))
        val planner = ScriptedPlanner(
            AgentAction.LaunchApp("com.android.dialer", "call mum"),
            AgentAction.Finish("cannot place calls", success = false, rationale = "blocked"),
        )

        val events = runner(device, planner, policy = ActionPolicy()).run("call mum").toList()

        assertTrue(device.performed.isEmpty())
        val refusal = events.filterIsInstance<AgentEvent.ActionRefused>().single()
        assertTrue(!refusal.byUser)
        assertContains(planner.observations[1].lastResult.orEmpty(), "BLOCKED")
    }

    @Test
    fun `an element id that is not on screen is reported back instead of crashing`() = runTest {
        val device = FakeDevice(screen(element(0)))
        val planner = ScriptedPlanner(
            AgentAction.Tap(99, "tap something that is not there"),
            AgentAction.Finish("recovered", success = true, rationale = "done"),
        )

        val events = runner(device, planner).run("goal").toList()

        assertTrue(device.performed.isEmpty())
        val executed = events.filterIsInstance<AgentEvent.ActionExecuted>().single()
        assertTrue(!executed.ok)
        assertContains(executed.detail, "no element [99]")
        assertIs<AgentEvent.Finished>(events.last())
    }

    @Test
    fun `stops when the same action repeats on an unchanged screen`() = runTest {
        val device = FakeDevice(screen(element(0, label = "Retry")))
        val planner = RepeatingPlanner(AgentAction.Tap(0, "try again"))

        val events = runner(device, planner, config = RunnerConfig(settleDelayMillis = 0, stuckThreshold = 3))
            .run("goal").toList()

        val failure = assertIs<AgentEvent.Failed>(events.last())
        assertContains(failure.message, "repeated 3 times")
        assertEquals(2, device.performed.size, "the third repeat must be stopped before it runs")
    }

    @Test
    fun `a question pauses the run and the answer reaches the next turn`() = runTest {
        val device = FakeDevice(screen(element(0)))
        val human = ScriptedHuman(answers = mutableListOf("the blue one"))
        val planner = ScriptedPlanner(
            AgentAction.AskUser("which account?", "two accounts are signed in"),
            AgentAction.Finish("used the blue account", success = true, rationale = "answered"),
        )

        val events = runner(device, planner, human = human).run("send it").toList()

        assertEquals(listOf("which account?"), human.questions)
        assertEquals("the blue one", planner.observations[1].userAnswer)
        assertTrue(events.any { it is AgentEvent.QuestionAsked })
    }

    @Test
    fun `an unanswered question cancels the run`() = runTest {
        val device = FakeDevice(screen(element(0)))
        val human = ScriptedHuman(answers = mutableListOf(null))
        val planner = ScriptedPlanner(AgentAction.AskUser("which one?", "ambiguous"))

        val events = runner(device, planner, human = human).run("goal").toList()

        assertIs<AgentEvent.Cancelled>(events.last())
    }

    @Test
    fun `a device failure is fed back so the model can recover`() = runTest {
        val device = FakeDevice(screen(element(0))).apply { failNext = "the view was gone" }
        val planner = ScriptedPlanner(
            AgentAction.Tap(0, "first try"),
            AgentAction.Finish("recovered", success = true, rationale = "done"),
        )

        runner(device, planner).run("goal").toList()

        assertContains(planner.observations[1].lastResult.orEmpty(), "FAILED: the view was gone")
    }

    @Test
    fun `the step ceiling ends a run that never finishes`() = runTest {
        val device = FakeDevice(
            screen(element(0, label = "A")),
            screen(element(0, label = "B")),
            screen(element(0, label = "C")),
        )
        // Distinct screens each turn, so stuck detection never fires.
        val planner = RepeatingPlanner(AgentAction.Swipe(ai.aios.core.device.SwipeDirection.UP, null, "keep looking"))

        val events = runner(device, planner, config = RunnerConfig(maxSteps = 3, settleDelayMillis = 0))
            .run("goal").toList()

        val failure = assertIs<AgentEvent.Failed>(events.last())
        assertContains(failure.message, "after 3 steps")
    }

    @Test
    fun `gated actions emit an approval request before executing`() = runTest {
        val device = FakeDevice(screen(element(0, label = "Delete everything")))
        val human = ScriptedHuman.approving()
        val planner = ScriptedPlanner(
            AgentAction.Tap(0, "the goal asked for this"),
            AgentAction.Finish("deleted", success = true, rationale = "done"),
        )

        val events = runner(device, planner, human = human, policy = ActionPolicy()).run("delete everything").toList()

        val awaiting = events.filterIsInstance<AgentEvent.AwaitingApproval>().single()
        assertContains(awaiting.reason, "delete")
        assertEquals(listOf("tap:0"), device.performed, "approval must let the action through")
    }

    @Test
    fun `typing into a listed editable field reaches the device verbatim`() = runTest {
        val device = FakeDevice(
            screen(element(0, role = "edittext", label = "", affordances = setOf(Affordance.TYPE)))
        )
        val planner = ScriptedPlanner(
            AgentAction.TypeText(0, "hello, world", submit = true, rationale = "fill the field"),
            AgentAction.Finish("typed", success = true, rationale = "done"),
        )

        runner(device, planner).run("goal").toList()

        assertEquals(listOf("type:0:hello, world:true"), device.performed)
    }
}
