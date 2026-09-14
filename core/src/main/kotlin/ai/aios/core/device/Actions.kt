package ai.aios.core.device

/** Hardware / system keys the agent may press. */
enum class DeviceKey { BACK, HOME, RECENTS, ENTER, NOTIFICATIONS, LOCK_SCREEN }

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/**
 * Everything the agent can do to the phone. One action per turn keeps the loop
 * observable: every step is a screen read, a decision, and a single effect the
 * user can see happen.
 */
sealed interface AgentAction {
    /** Why the model chose this - shown in the UI log and used in audit trails. */
    val rationale: String

    data class Tap(
        val elementId: Int,
        override val rationale: String,
    ) : AgentAction

    data class LongPress(
        val elementId: Int,
        override val rationale: String,
    ) : AgentAction

    data class TypeText(
        val elementId: Int,
        val text: String,
        val submit: Boolean = false,
        override val rationale: String,
    ) : AgentAction

    data class Swipe(
        val direction: SwipeDirection,
        val elementId: Int? = null,
        override val rationale: String,
    ) : AgentAction

    data class PressKey(
        val key: DeviceKey,
        override val rationale: String,
    ) : AgentAction

    data class LaunchApp(
        val packageName: String,
        override val rationale: String,
    ) : AgentAction

    data class Wait(
        val millis: Long,
        override val rationale: String,
    ) : AgentAction

    /** Hand control back with a question - the loop pauses for a human answer. */
    data class AskUser(
        val question: String,
        override val rationale: String,
    ) : AgentAction

    /** The goal is reached (or provably unreachable); the loop ends. */
    data class Finish(
        val summary: String,
        val success: Boolean,
        override val rationale: String,
    ) : AgentAction
}

/** Outcome of executing one action against the device. */
data class ActionResult(
    val ok: Boolean,
    val message: String,
) {
    companion object {
        fun ok(message: String = "done") = ActionResult(true, message)
        fun failed(message: String) = ActionResult(false, message)
    }
}

data class InstalledApp(val packageName: String, val label: String)

/**
 * The effector side of the agent: the only surface through which it touches the
 * phone. Android implements this with an `AccessibilityService`; tests
 * implement it with an in-memory fake, which is why the whole agent loop is
 * verifiable without a device.
 */
interface DeviceController {
    suspend fun readScreen(): ScreenGraph
    suspend fun tap(element: ScreenElement): ActionResult
    suspend fun longPress(element: ScreenElement): ActionResult
    suspend fun typeText(element: ScreenElement, text: String, submit: Boolean): ActionResult
    suspend fun swipe(direction: SwipeDirection, element: ScreenElement?): ActionResult
    suspend fun pressKey(key: DeviceKey): ActionResult
    suspend fun launchApp(packageName: String): ActionResult
    suspend fun installedApps(): List<InstalledApp>
}
