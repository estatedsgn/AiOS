package ai.aios.core.safety

import ai.aios.core.device.AgentAction
import ai.aios.core.device.ScreenElement
import ai.aios.core.device.ScreenGraph

/** How much rope the user has given the agent. */
enum class AutonomyMode {
    /** Every action waits for a tap on Approve. Good for the first run. */
    CONFIRM_EVERYTHING,

    /** Only actions the policy flags as consequential need approval. */
    CONFIRM_SENSITIVE,

    /** Nothing is gated. Blocked actions are still blocked. */
    AUTONOMOUS,
}

sealed interface Verdict {
    data object Allow : Verdict
    data class NeedsConfirmation(val reason: String) : Verdict
    data class Blocked(val reason: String) : Verdict
}

/**
 * Decides whether an action runs, needs a human tap, or is refused outright.
 *
 * The agent drives a real phone with the user's real accounts, so the cost of a
 * wrong tap is not a failed test - it is money moved or a message sent. The
 * policy errs toward asking: it inspects both the action and the element being
 * acted on, because "Confirm" on a banking screen and "Confirm" in a note app
 * are not the same button.
 */
class ActionPolicy(
    private val mode: AutonomyMode = AutonomyMode.CONFIRM_SENSITIVE,
    /** Packages the agent must never drive, whatever the goal. */
    private val blockedPackages: Set<String> = DEFAULT_BLOCKED_PACKAGES,
    /** Packages where every tap needs a human. */
    private val sensitivePackages: Set<String> = DEFAULT_SENSITIVE_PACKAGES,
) {

    fun evaluate(action: AgentAction, screen: ScreenGraph): Verdict {
        // Refusals first: these hold in every mode, including AUTONOMOUS.
        blockedReason(action, screen)?.let { return Verdict.Blocked(it) }

        // Questions and completions never touch the device.
        if (action is AgentAction.AskUser || action is AgentAction.Finish) return Verdict.Allow

        if (mode == AutonomyMode.CONFIRM_EVERYTHING) {
            return Verdict.NeedsConfirmation("Approval mode: every action is confirmed.")
        }

        sensitiveReason(action, screen)?.let {
            return if (mode == AutonomyMode.AUTONOMOUS) Verdict.Allow else Verdict.NeedsConfirmation(it)
        }

        return Verdict.Allow
    }

    private fun blockedReason(action: AgentAction, screen: ScreenGraph): String? {
        val pkg = when (action) {
            is AgentAction.LaunchApp -> action.packageName
            else -> screen.appPackage
        } ?: return null

        if (blockedPackages.any { pkg.startsWith(it) }) {
            return "\"$pkg\" is on the blocked list and cannot be driven by the agent."
        }

        // Never let the agent hand itself more power or switch itself off.
        if (pkg.startsWith("com.android.settings")) {
            val label = actedLabel(action, screen)?.lowercase().orEmpty()
            if (PRIVILEGE_TERMS.any { it in label }) {
                return "Changing device security or accessibility settings is not allowed."
            }
        }
        return null
    }

    private fun sensitiveReason(action: AgentAction, screen: ScreenGraph): String? {
        val pkg = when (action) {
            is AgentAction.LaunchApp -> action.packageName
            else -> screen.appPackage
        }

        if (pkg != null && sensitivePackages.any { pkg.startsWith(it) }) {
            return "\"$pkg\" handles money or private messages."
        }

        val label = actedLabel(action, screen)?.lowercase()
        if (label != null) {
            CONSEQUENTIAL_TERMS.firstOrNull { it in label }?.let { term ->
                return "This control is labelled \"$term\" - the effect may not be reversible."
            }
        }

        // Typing into a password field is always worth a look.
        if (action is AgentAction.TypeText) {
            val element = screen.element(action.elementId)
            if (element?.password == true) return "This is a password field."
        }

        return null
    }

    private fun actedLabel(action: AgentAction, screen: ScreenGraph): String? {
        val id = when (action) {
            is AgentAction.Tap -> action.elementId
            is AgentAction.LongPress -> action.elementId
            is AgentAction.TypeText -> action.elementId
            else -> return null
        }
        return screen.element(id)?.label?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** Terms that mean an action leaves the device and is hard to undo. */
        val CONSEQUENTIAL_TERMS = listOf(
            "pay", "buy", "purchase", "checkout", "order", "transfer", "send money",
            "delete", "remove", "erase", "uninstall", "factory reset", "wipe",
            "send", "post", "publish", "confirm", "subscribe", "allow", "grant",
        )

        private val PRIVILEGE_TERMS = listOf(
            "accessibility", "device admin", "developer options",
            "screen lock", "unknown sources", "install unknown apps",
        )

        val DEFAULT_SENSITIVE_PACKAGES = setOf(
            "com.android.vending",          // Play Store - purchases
            "com.google.android.apps.walletnfcrel",
            "com.paypal", "com.squareup.cash", "com.venmo",
            "com.android.mms", "com.google.android.apps.messaging",
            "org.telegram", "com.whatsapp", "com.facebook.orca",
        )

        /**
         * Nothing here is driven under any autonomy mode. Dialer is included
         * because a placed call is immediate and cannot be taken back.
         */
        val DEFAULT_BLOCKED_PACKAGES = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.emergency",
        )
    }
}
