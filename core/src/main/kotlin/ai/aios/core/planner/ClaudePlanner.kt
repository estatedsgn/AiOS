package ai.aios.core.planner

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tuning knobs surfaced in the app's settings screen. */
data class PlannerConfig(
    val model: String = DEFAULT_MODEL,
    val maxTokens: Long = 4_096,
    /** Turns of screen history kept before the oldest are dropped. */
    val historyTurns: Int = 12,
) {
    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"
    }
}

/**
 * Drives the decision half of the agent with the Messages API.
 *
 * The loop is manual rather than `BetaToolRunner`-driven because tool calls
 * here are not functions to execute in-process: each one has to pass the safety
 * policy, may wait on a human tap, and then runs against hardware. The runner
 * would execute them for us, which is exactly what must not happen.
 */
class ClaudePlanner(
    private val client: AnthropicClient,
    private val config: PlannerConfig = PlannerConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Planner {

    constructor(apiKey: String, config: PlannerConfig = PlannerConfig()) : this(
        AnthropicOkHttpClient.builder().apiKey(apiKey).build(),
        config,
    )

    private val history = mutableListOf<MessageParam>()
    private var pendingToolUseId: String? = null

    override fun reset() {
        history.clear()
        pendingToolUseId = null
    }

    override suspend fun decide(observation: Observation): Decision {
        history += buildTurnMessage(observation)
        trimHistory()

        val params = MessageCreateParams.builder()
            .model(config.model)
            .maxTokens(config.maxTokens)
            // The system prompt and tool list are identical on every turn of a
            // run; caching them turns a long run's repeated prefix into cache
            // reads, which matters when the user is paying with their own key.
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(SystemPrompt.TEXT)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()
                )
            )
            .apply { AgentTools.all().forEach { addTool(it) } }
            .apply { history.forEach { addMessage(it) } }
            .build()

        val response: Message = withContext(dispatcher) { client.messages().create(params) }

        // Record the assistant turn verbatim so the next tool_result lines up
        // with the tool_use id the model just produced.
        history += MessageParam.builder()
            .role(MessageParam.Role.ASSISTANT)
            .contentOfBlockParams(response.content().map { it.toParam() })
            .build()

        val usage = TokenUsage(
            inputTokens = response.usage().inputTokens(),
            outputTokens = response.usage().outputTokens(),
            cacheReadTokens = response.usage().cacheReadInputTokens().orElse(0L),
        )

        val note = response.content()
            .mapNotNull { it.text().orElse(null)?.text() }
            .joinToString(" ")
            .trim()
            .takeIf { it.isNotEmpty() }

        val toolUse = response.content().firstNotNullOfOrNull { it.toolUse().orElse(null) }
            ?: run {
                pendingToolUseId = null
                return Decision(
                    action = null,
                    note = note,
                    usage = usage,
                    error = "The model replied without calling a tool.",
                )
            }

        pendingToolUseId = toolUse.id()

        @Suppress("UNCHECKED_CAST")
        val input = runCatching {
            toolUse._input().convert(Map::class.java) as Map<String, Any?>
        }.getOrElse { emptyMap() }

        val action = AgentTools.parse(toolUse.name(), input)
        return Decision(
            action = action,
            note = note,
            usage = usage,
            error = if (action == null) "Unusable arguments for tool \"${toolUse.name()}\"." else null,
        )
    }

    /** First turn opens with the goal; later turns answer the previous call. */
    private fun buildTurnMessage(observation: Observation): MessageParam {
        val body = buildString {
            if (history.isEmpty()) {
                append("GOAL: ").append(observation.goal).append("\n\n")
                observation.installedApps?.takeIf { it.isNotEmpty() }?.let { apps ->
                    append("INSTALLED APPS (package - name):\n")
                    apps.sortedBy { it.label }.forEach {
                        append("  ").append(it.packageName).append(" - ").append(it.label).append('\n')
                    }
                    append('\n')
                }
            }
            observation.userAnswer?.let { append("THE USER ANSWERED: ").append(it).append("\n\n") }
            observation.lastResult?.let { append("RESULT OF YOUR LAST ACTION: ").append(it).append("\n\n") }
            append(observation.screen.render())
        }

        val toolUseId = pendingToolUseId
        return if (toolUseId == null) {
            MessageParam.builder().role(MessageParam.Role.USER).content(body).build()
        } else {
            // Continuing a tool call: the result block must come first and must
            // carry the id of the call it answers.
            MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(
                    listOf(
                        ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                .toolUseId(toolUseId)
                                .content(body)
                                .build()
                        )
                    )
                )
                .build()
        }
    }

    /**
     * Keeps the goal message and the most recent turns, dropping older ones in
     * assistant/user pairs so no `tool_result` is ever orphaned from its
     * `tool_use` - the API rejects that, and a long run would otherwise grow
     * until it did.
     */
    private fun trimHistory() {
        val maxMessages = 1 + config.historyTurns * 2
        while (history.size > maxMessages) {
            // Index 0 is the goal; drop the oldest assistant+user pair after it.
            if (history.size < 3) return
            history.removeAt(1)
            history.removeAt(1)
        }
    }
}

/** Kept out of [ClaudePlanner] so its wording can be reviewed on its own. */
internal object SystemPrompt {
    val TEXT: String = """
        You are the agent inside AiOS. You operate a real Android phone on behalf of its owner,
        one action at a time, by calling the tools you have been given.

        HOW EACH TURN WORKS
        Every turn you receive a fresh listing of what is currently on screen. Each line looks like:
          [12] button "Send" @540,1820
        The number in brackets is the element id you pass to tools. These ids are regenerated on
        every single turn - an id from a previous turn is meaningless now. Never pass an id that is
        not in the listing you were just given.

        Choose exactly one tool call per turn. After it runs you will see the new screen and can
        decide again.

        OPERATING RULES
        - Read the screen before acting. If the listing looks empty, half-drawn, or unrelated to
          what you expected, call wait rather than tapping hopefully.
        - Open apps with launch_app and a package name. Do not hunt across home screens for icons.
        - To reach something not in the listing, swipe. Swiping up moves further down the content.
        - If the same approach fails twice, change approach: press back and take another route.
          Repeating an action that just failed will not make it work.
        - Text fields are replaced, not appended to, when you type into them.

        WHEN TO STOP AND ASK
        Call ask_user when the goal is ambiguous, when a detail is missing that only the owner
        knows (a recipient, an amount, which of two accounts), or when you are about to do
        something consequential that the goal did not clearly authorise. Never invent personal
        data, and never type a password or payment detail you were not explicitly given - ask.

        Some actions are gated: the owner sees a prompt and approves or rejects them. A rejection
        is an instruction, not an obstacle. Do not look for another route to the same effect -
        treat it as a no and either find a genuinely different approach to the goal or call finish
        and explain.

        FINISHING
        Call finish as soon as the goal is met, and say briefly what you did. If the goal cannot be
        met, call finish with success=false and explain what blocked you. Do not keep exploring
        once you know the answer, and do not claim success you cannot see evidence for on screen.
    """.trimIndent()
}
