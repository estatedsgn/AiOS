package ai.aios.core

import ai.aios.core.device.Affordance
import ai.aios.core.device.AgentAction
import ai.aios.core.safety.ActionPolicy
import ai.aios.core.safety.AutonomyMode
import ai.aios.core.safety.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ActionPolicyTest {

    private fun tap(id: Int = 0) = AgentAction.Tap(id, "because")

    @Test
    fun `ordinary taps pass in the default mode`() {
        val policy = ActionPolicy()
        val verdict = policy.evaluate(tap(), screen(element(0, label = "Compose")))
        assertIs<Verdict.Allow>(verdict)
    }

    @Test
    fun `confirm everything mode gates even a harmless tap`() {
        val policy = ActionPolicy(mode = AutonomyMode.CONFIRM_EVERYTHING)
        val verdict = policy.evaluate(tap(), screen(element(0, label = "Compose")))
        assertIs<Verdict.NeedsConfirmation>(verdict)
    }

    @Test
    fun `a button labelled with a consequential word needs confirmation`() {
        val policy = ActionPolicy()
        val verdict = policy.evaluate(tap(), screen(element(0, label = "Delete all notes")))
        val confirmation = assertIs<Verdict.NeedsConfirmation>(verdict)
        assertTrue(confirmation.reason.contains("delete"), confirmation.reason)
    }

    @Test
    fun `payment apps need confirmation for every action`() {
        val policy = ActionPolicy()
        val verdict = policy.evaluate(tap(), screen(element(0, label = "Continue"), pkg = "com.paypal.android"))
        assertIs<Verdict.NeedsConfirmation>(verdict)
    }

    @Test
    fun `the dialer is blocked outright in every mode`() {
        for (mode in AutonomyMode.entries) {
            val policy = ActionPolicy(mode = mode)
            val verdict = policy.evaluate(
                AgentAction.LaunchApp("com.android.dialer", "call someone"),
                screen(element(0)),
            )
            assertIs<Verdict.Blocked>(verdict, "mode $mode must still block the dialer")
        }
    }

    @Test
    fun `autonomous mode skips confirmation but never skips a block`() {
        val policy = ActionPolicy(mode = AutonomyMode.AUTONOMOUS)

        assertIs<Verdict.Allow>(
            policy.evaluate(tap(), screen(element(0, label = "Delete all notes")))
        )
        assertIs<Verdict.Blocked>(
            policy.evaluate(AgentAction.LaunchApp("com.android.dialer", "x"), screen(element(0)))
        )
    }

    @Test
    fun `the agent cannot be sent to change accessibility settings`() {
        val policy = ActionPolicy(mode = AutonomyMode.AUTONOMOUS)
        val verdict = policy.evaluate(
            tap(),
            screen(element(0, label = "Accessibility"), pkg = "com.android.settings"),
        )
        assertIs<Verdict.Blocked>(verdict)
    }

    @Test
    fun `typing into a password field is gated`() {
        val policy = ActionPolicy()
        val verdict = policy.evaluate(
            AgentAction.TypeText(0, "hunter2", rationale = "log in"),
            screen(element(0, role = "edittext", label = "", affordances = setOf(Affordance.TYPE), password = true)),
        )
        val confirmation = assertIs<Verdict.NeedsConfirmation>(verdict)
        assertEquals("This is a password field.", confirmation.reason)
    }

    @Test
    fun `asking and finishing are never gated`() {
        val policy = ActionPolicy(mode = AutonomyMode.CONFIRM_EVERYTHING)

        assertIs<Verdict.Allow>(policy.evaluate(AgentAction.AskUser("which one?", "unclear"), screen(element(0))))
        assertIs<Verdict.Allow>(policy.evaluate(AgentAction.Finish("done", true, "goal met"), screen(element(0))))
    }
}
