package ai.aios.core.planner

import ai.aios.core.device.AgentAction
import ai.aios.core.device.DeviceKey
import ai.aios.core.device.SwipeDirection
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool

/**
 * The agent's entire action surface, expressed as Claude tools.
 *
 * Every tool takes a `rationale`: it costs a few tokens and buys an auditable
 * log line explaining why the phone just did something, which matters when the
 * thing being automated is someone's actual device.
 */
object AgentTools {

    const val TAP = "tap"
    const val LONG_PRESS = "long_press"
    const val TYPE_TEXT = "type_text"
    const val SWIPE = "swipe"
    const val PRESS_KEY = "press_key"
    const val LAUNCH_APP = "launch_app"
    const val WAIT = "wait"
    const val ASK_USER = "ask_user"
    const val FINISH = "finish"

    fun all(): List<Tool> = listOf(
        tool(
            name = TAP,
            description = "Tap one element on the current screen. Use the [id] shown in the " +
                "screen listing. Only elements listed as tappable can be tapped.",
            properties = mapOf(
                "element_id" to intProp("The [id] of the element to tap."),
                "rationale" to stringProp("One short sentence: why this element advances the goal."),
            ),
            required = listOf("element_id", "rationale"),
        ),
        tool(
            name = LONG_PRESS,
            description = "Press and hold an element, for context menus and multi-select.",
            properties = mapOf(
                "element_id" to intProp("The [id] of the element to press and hold."),
                "rationale" to stringProp("Why a long press is needed here."),
            ),
            required = listOf("element_id", "rationale"),
        ),
        tool(
            name = TYPE_TEXT,
            description = "Type into a text field. The field must be listed as editable. " +
                "This replaces any text already in the field.",
            properties = mapOf(
                "element_id" to intProp("The [id] of the editable field."),
                "text" to stringProp("The exact text to enter."),
                "submit" to boolProp("Press Enter / the keyboard action key afterwards. Default false."),
                "rationale" to stringProp("Why this text, in this field."),
            ),
            required = listOf("element_id", "text", "rationale"),
        ),
        tool(
            name = SWIPE,
            description = "Swipe the screen or a scrollable element. Swiping UP scrolls further " +
                "down the content. Use this to reach elements marked as omitted.",
            properties = mapOf(
                "direction" to enumProp(
                    listOf("up", "down", "left", "right"),
                    "Direction of finger travel.",
                ),
                "element_id" to intProp(
                    "Optional [id] of the scrollable element to swipe inside. " +
                        "Omit to swipe the whole screen.",
                ),
                "rationale" to stringProp("What you expect to reveal."),
            ),
            required = listOf("direction", "rationale"),
        ),
        tool(
            name = PRESS_KEY,
            description = "Press a system key.",
            properties = mapOf(
                "key" to enumProp(
                    listOf("back", "home", "recents", "enter", "notifications", "lock_screen"),
                    "Which system key to press.",
                ),
                "rationale" to stringProp("Why this key."),
            ),
            required = listOf("key", "rationale"),
        ),
        tool(
            name = LAUNCH_APP,
            description = "Open an app by its package name. Prefer this over hunting for an icon " +
                "on the home screen. Package names come from the installed-apps list.",
            properties = mapOf(
                "package_name" to stringProp("Exact package name, e.g. com.android.settings."),
                "rationale" to stringProp("Why this app is the right place to go."),
            ),
            required = listOf("package_name", "rationale"),
        ),
        tool(
            name = WAIT,
            description = "Wait for the screen to settle - after a launch, a network call, or an " +
                "animation. Use this instead of guessing at a screen that is still loading.",
            properties = mapOf(
                "millis" to intProp("How long to wait, 200-10000 ms."),
                "rationale" to stringProp("What you are waiting for."),
            ),
            required = listOf("millis", "rationale"),
        ),
        tool(
            name = ASK_USER,
            description = "Stop and ask the person a question. Use this when the goal is " +
                "ambiguous, when you need information only they have (a recipient, an amount, a " +
                "password), or when you are about to do something you are not sure they want.",
            properties = mapOf(
                "question" to stringProp("A direct question, answerable in one line."),
                "rationale" to stringProp("Why you cannot decide this yourself."),
            ),
            required = listOf("question", "rationale"),
        ),
        tool(
            name = FINISH,
            description = "End the run. Call this when the goal is done, or when it cannot be " +
                "done and you have said why.",
            properties = mapOf(
                "success" to boolProp("True if the goal was achieved."),
                "summary" to stringProp("What you did, or what stopped you."),
                "rationale" to stringProp("Why the run is over."),
            ),
            required = listOf("success", "summary", "rationale"),
        ),
    )

    /**
     * Maps a tool call onto an [AgentAction]. Returns null for an unknown tool
     * or malformed arguments, so the loop can tell the model what went wrong
     * instead of crashing on the user's phone.
     */
    fun parse(toolName: String, input: Map<String, Any?>): AgentAction? {
        val rationale = input.str("rationale") ?: "(no rationale given)"
        return when (toolName) {
            TAP -> input.int("element_id")?.let { AgentAction.Tap(it, rationale) }

            LONG_PRESS -> input.int("element_id")?.let { AgentAction.LongPress(it, rationale) }

            TYPE_TEXT -> {
                val id = input.int("element_id") ?: return null
                val text = input.str("text") ?: return null
                AgentAction.TypeText(id, text, input.bool("submit") ?: false, rationale)
            }

            SWIPE -> {
                val dir = when (input.str("direction")?.lowercase()) {
                    "up" -> SwipeDirection.UP
                    "down" -> SwipeDirection.DOWN
                    "left" -> SwipeDirection.LEFT
                    "right" -> SwipeDirection.RIGHT
                    else -> return null
                }
                AgentAction.Swipe(dir, input.int("element_id"), rationale)
            }

            PRESS_KEY -> {
                val key = when (input.str("key")?.lowercase()) {
                    "back" -> DeviceKey.BACK
                    "home" -> DeviceKey.HOME
                    "recents" -> DeviceKey.RECENTS
                    "enter" -> DeviceKey.ENTER
                    "notifications" -> DeviceKey.NOTIFICATIONS
                    "lock_screen" -> DeviceKey.LOCK_SCREEN
                    else -> return null
                }
                AgentAction.PressKey(key, rationale)
            }

            LAUNCH_APP -> input.str("package_name")?.let { AgentAction.LaunchApp(it, rationale) }

            WAIT -> {
                val ms = (input.int("millis") ?: 1000).coerceIn(200, 10_000)
                AgentAction.Wait(ms.toLong(), rationale)
            }

            ASK_USER -> input.str("question")?.let { AgentAction.AskUser(it, rationale) }

            FINISH -> AgentAction.Finish(
                summary = input.str("summary") ?: "(no summary)",
                success = input.bool("success") ?: false,
                rationale = rationale,
            )

            else -> null
        }
    }

    // --- schema helpers -----------------------------------------------------

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, Map<String, Any>>,
        required: List<String>,
    ): Tool {
        val props = Tool.InputSchema.Properties.builder().apply {
            properties.forEach { (key, schema) -> putAdditionalProperty(key, JsonValue.from(schema)) }
        }.build()

        return Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(props)
                    .required(required)
                    .build()
            )
            .build()
    }

    private fun stringProp(desc: String) = mapOf("type" to "string", "description" to desc)
    private fun intProp(desc: String) = mapOf("type" to "integer", "description" to desc)
    private fun boolProp(desc: String) = mapOf("type" to "boolean", "description" to desc)
    private fun enumProp(values: List<String>, desc: String) =
        mapOf("type" to "string", "enum" to values, "description" to desc)
}

// Tool arguments arrive as loosely typed JSON; read them defensively.
private fun Map<String, Any?>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }

private fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
    is Int -> v
    is Long -> v.toInt()
    is Number -> v.toInt()
    is String -> v.toIntOrNull()
    else -> null
}

private fun Map<String, Any?>.bool(key: String): Boolean? = when (val v = this[key]) {
    is Boolean -> v
    is String -> v.toBooleanStrictOrNull()
    else -> null
}
