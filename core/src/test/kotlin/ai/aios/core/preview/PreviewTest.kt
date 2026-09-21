package ai.aios.core.preview

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PreviewTest {
    private fun call(name: String, vararg args: Pair<String, String>, id: String = "one") = ToolCall(id, name, args.toMap())
    private fun model(vararg turns: ModelTurn): PreviewModel = object : PreviewModel {
        var index = 0
        override suspend fun next(goal: String, history: List<ChatMessage>, exchanges: List<ToolExchange>) = turns[index++]
    }
    private suspend fun run(runtime: PreviewRuntime): String = runtime.run("test", emptyList(), { _, _, _ -> }, { })

    @Test fun `workspace changes are reversible and survive serialization`() {
        val state = Workspace().addTask("Call Alex", "2026-09-22", "task-1")
        val completed = state.setTaskStatus("task-1", true)
        assertTrue(completed.tasks.single().done)
        val decoded = Json.decodeFromString<Workspace>(Json.encodeToString(completed))
        assertEquals(completed, decoded)
        assertEquals(state, decoded.setTaskStatus("task-1", false))
    }

    @Test fun `invalid task dates IDs and empty titles are rejected`() {
        assertFailsWith<IllegalArgumentException> { Workspace().addTask(" ") }
        assertFailsWith<IllegalArgumentException> { Workspace().addTask("x", "tomorrow") }
        assertFailsWith<IllegalArgumentException> { Workspace().addTask("x", "2026-02-30") }
        assertFailsWith<IllegalArgumentException> { Workspace().setTaskStatus("missing", true) }
        assertFailsWith<IllegalArgumentException> { Workspace().addTask("x", id = "1").addTask("y", id = "1") }
    }

    @Test fun `crash recovery clears active run and never replays pending actions`() {
        val state = Workspace(activeRunId = "run", activity = listOf(
            Activity("a", "run", "message_draft", Risk.EXTERNAL, Route.ANDROID, "draft", "WAITING", 1),
            Activity("b", "run", "task_add", Risk.LOCAL, Route.WORKSPACE, "saved", "VERIFIED", 1),
        ))
        val recovered = state.recover(2)
        assertNull(recovered.activeRunId)
        assertEquals("INTERRUPTED", recovered.activity[0].status)
        assertEquals("VERIFIED", recovered.activity[1].status)
        assertEquals(1, recovered.messages.size)
        assertEquals(recovered, recovered.recover(3))
    }

    @Test fun `READ and LOCAL execute without approval`() = runTest {
        var approvals = 0
        var effects = 0
        val router = ToolRouter(PreviewTools.all, ToolHost { _, _ -> effects++; ToolResult("ok") },
            ApprovalGate { _, _ -> approvals++; false })
        assertEquals(Outcome.VERIFIED, router.execute(call("workspace_read")) { _, _ -> }.status)
        assertEquals(Outcome.VERIFIED, router.execute(call("task_add", "title" to "x")) { _, _ -> }.status)
        assertEquals(0, approvals)
        assertEquals(2, effects)
    }

    @Test fun `external and sensitive always require explicit approval`() = runTest {
        var approvals = 0
        var effects = 0
        val sensitive = ToolSpec("sensitive_test", "Test only", Risk.SENSITIVE, Route.SERVICE_API)
        val router = ToolRouter(PreviewTools.all + sensitive, ToolHost { _, _ -> effects++; ToolResult("ok") },
            ApprovalGate { _, _ -> approvals++; false })
        assertEquals(Outcome.REJECTED, router.execute(call("message_draft", "text" to "hello")) { _, _ -> }.status)
        assertEquals(Outcome.REJECTED, router.execute(call("sensitive_test")) { _, _ -> }.status)
        assertEquals(2, approvals)
        assertEquals(0, effects)
    }

    @Test fun `model cannot downgrade risk or inject an endpoint through arguments`() = runTest {
        var effects = 0
        var approvals = 0
        val router = ToolRouter(PreviewTools.all, ToolHost { _, _ -> effects++; ToolResult("ok") },
            ApprovalGate { _, _ -> approvals++; true })
        val malformed = listOf(
            call("message_draft", "text" to "hello", "risk" to "READ"),
            call("task_add", "title" to "x", "endpoint" to "https://untrusted.invalid"),
            call("task_add"), call("task_add", "title" to "x".repeat(501)),
            call("shell", "command" to "anything"),
        )
        malformed.forEach { assertEquals(Outcome.FAILED, router.execute(it) { _, _ -> }.status) }
        assertEquals(0, approvals)
        assertEquals(0, effects)
    }

    @Test fun `approval uses frozen arguments`() = runTest {
        val arguments = mutableMapOf("text" to "approved text")
        var executed = ""
        val router = ToolRouter(PreviewTools.all,
            ToolHost { _, call -> executed = call.arguments.getValue("text"); ToolResult("ok") },
            ApprovalGate { _, call ->
                assertEquals("approved text", call.arguments["text"])
                arguments["text"] = "changed after approval"
                true
            })
        router.execute(ToolCall("one", "message_draft", arguments)) { _, _ -> }
        assertEquals("approved text", executed)
    }

    @Test fun `Stop while waiting for approval prevents every effect`() = runTest {
        var effects = 0
        val waiting = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Boolean>()
        val router = ToolRouter(PreviewTools.all, ToolHost { _, _ -> effects++; ToolResult("ok") },
            ApprovalGate { _, _ -> waiting.complete(Unit); gate.await() })
        val job = async { router.execute(call("message_draft", "text" to "hello")) { _, _ -> } }
        waiting.await()
        job.cancelAndJoin()
        gate.complete(true)
        runCurrent()
        assertEquals(0, effects)
    }

    @Test fun `cancellation immediately after approval still prevents execution`() = runTest {
        var effects = 0
        val router = ToolRouter(PreviewTools.all, ToolHost { _, _ -> effects++; ToolResult("ok") },
            ApprovalGate { _, _ -> currentCoroutineContext().cancel(); true })
        val job = async { router.execute(call("message_draft", "text" to "hello")) { _, _ -> } }
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(0, effects)
    }

    @Test fun `rejection ends entire batch without alternate route`() = runTest {
        var effects = 0
        val router = ToolRouter(PreviewTools.all, ToolHost { _, _ -> effects++; ToolResult("ok") }, ApprovalGate { _, _ -> false })
        val runtime = PreviewRuntime(model(ModelTurn(calls = listOf(
            call("message_draft", "text" to "hello", id = "1"), call("task_add", "title" to "x", id = "2"),
        ))), router)
        assertTrue(run(runtime).contains("отклонили"))
        assertEquals(0, effects)
    }

    @Test fun `native handoff is not treated as remote success or continued`() = runTest {
        var effects = 0
        val router = ToolRouter(PreviewTools.all, ToolHost { _, _ -> effects++; ToolResult("handoff only", status = Outcome.HANDOFF) },
            ApprovalGate { _, _ -> true })
        val runtime = PreviewRuntime(model(ModelTurn(calls = listOf(
            call("app_open", "package" to "com.example", id = "1"), call("task_add", "title" to "x", id = "2"),
        ))), router)
        assertEquals("handoff only", run(runtime))
        assertEquals(1, effects)
    }

    @Test fun `duplicate IDs reject whole batch before first write`() = runTest {
        var effects = 0
        val router = ToolRouter(PreviewTools.all, ToolHost { _, _ -> effects++; ToolResult("ok") }, ApprovalGate { _, _ -> true })
        val runtime = PreviewRuntime(model(ModelTurn(calls = listOf(
            call("task_add", "title" to "one"), call("task_add", "title" to "two"),
        ))), router)
        assertTrue(run(runtime).contains("остановлено"))
        assertEquals(0, effects)
    }

    @Test fun `repeated write with new ID is still not replayed`() = runTest {
        var effects = 0
        val router = ToolRouter(PreviewTools.all, ToolHost { _, _ -> effects++; ToolResult("ok") }, ApprovalGate { _, _ -> true })
        val runtime = PreviewRuntime(model(
            ModelTurn(calls = listOf(call("task_add", "title" to "same", id = "1"))),
            ModelTurn(calls = listOf(call("task_add", "title" to "same", id = "2"))),
        ), router)
        assertTrue(run(runtime).contains("Повторная запись"))
        assertEquals(1, effects)
    }

    @Test fun `failed tool is not retried and exception details never reach output`() = runTest {
        var effects = 0
        val router = ToolRouter(PreviewTools.all, ToolHost { _, _ -> effects++; error("secret-token-do-not-log") },
            ApprovalGate { _, _ -> true })
        val runtime = PreviewRuntime(model(ModelTurn(calls = listOf(call("task_add", "title" to "x")))), router)
        val result = run(runtime)
        assertEquals(1, effects)
        assertFalse(result.contains("secret-token"))
        assertTrue(result.contains("ошибкой"))
    }

    @Test fun `plain conversation performs no tool calls`() = runTest {
        val router = ToolRouter(PreviewTools.all, ToolHost { _, _ -> error("Must not execute") }, ApprovalGate { _, _ -> error("Must not ask") })
        assertEquals("Привет", run(PreviewRuntime(model(ModelTurn("Привет")), router)))
    }

    @Test fun `bounded runtime terminates repeated reads`() = runTest {
        var effects = 0
        val router = ToolRouter(PreviewTools.all, ToolHost { _, _ -> effects++; ToolResult("read") }, ApprovalGate { _, _ -> true })
        val runtime = PreviewRuntime(model(
            ModelTurn(calls = listOf(call("workspace_read", id = "1"))),
            ModelTurn(calls = listOf(call("workspace_read", id = "2"))),
        ), router, maxTurns = 2)
        assertTrue(run(runtime).contains("лимит"))
        assertEquals(2, effects)
    }
}
