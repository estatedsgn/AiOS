package ai.aios.core.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** The same scenarios run in Gradle/JUnit and the SDK-free command-line harness. */
object WorkspaceScenarios {
    class Store(var saved: Workspace = Workspace()) : WorkspaceStore {
        var fail = false
        override fun load() = WorkspaceCodec.decode(WorkspaceCodec.encode(saved))
        override fun save(workspace: Workspace) { if (fail) error("disk full"); saved = WorkspaceCodec.decode(WorkspaceCodec.encode(workspace)) }
    }
    class Port(override val route: Route, var enabled: Boolean = true) : ToolPort {
        var calls = 0
        var fails = false
        var outcome = ToolOutcome(ProposalStatus.HANDED_OFF, "Editor launched, save not verified")
        override fun available(tool: String) = enabled
        override suspend fun execute(call: ToolCall): ToolOutcome { calls++; if (fails) error("secret exception must not be persisted"); return outcome }
    }
    private class Fixture {
        val store = Store()
        val native = Port(Route.ANDROID)
        val service = Port(Route.SERVICE_API)
        val ui = Port(Route.ACCESSIBILITY)
        var time = 1_000L
        var sequence = 0
        val router = ToolRouter(listOf(ui, service, native))
        val engine = WorkspaceEngine(store, router, { time }, { "id-${++sequence}" })
        fun proposal(id: String) = engine.state.proposals.first { it.id == id }
        fun calendar() = engine.request(ToolCall("calendar.draft", mapOf("title" to "Позвонить Анне")))
    }

    val cases: List<Pair<String, () -> Unit>> = listOf(
        "offline task survives reload" to { runBlocking {
            val f = Fixture(); val id = f.engine.request(PreviewCommands.parse("Задача: Связаться с Анной")); f.engine.execute(id)
            assertEquals(ProposalStatus.SUCCEEDED, f.proposal(id).status)
            assertEquals("Связаться с Анной", f.store.load().workItems.single().title)
            assertEquals(0, f.native.calls)
        } },
        "local toggle is reversible" to { runBlocking {
            val f = Fixture(); f.engine.execute(f.engine.request(PreviewCommands.parse("Task: hello")))
            val taskId = f.engine.state.workItems.single().id
            repeat(2) { f.engine.execute(f.engine.request(ToolCall("workspace.task.toggle", mapOf("id" to taskId)))) }
            assertFalse(f.engine.state.workItems.single().done)
        } },
        "brief reads normalized records" to { runBlocking {
            val f = Fixture(); f.engine.execute(f.engine.request(PreviewCommands.parse("Task: hello")))
            assertTrue(f.engine.brief().contains("Открытых задач: 1"))
        } },
        "approval classes are registry owned" to {
            assertFalse(PreviewTools.requiresApproval(Risk.READ)); assertFalse(PreviewTools.requiresApproval(Risk.LOCAL))
            assertTrue(PreviewTools.requiresApproval(Risk.EXTERNAL)); assertTrue(PreviewTools.requiresApproval(Risk.SENSITIVE))
            assertFailsWith<IllegalArgumentException> { Fixture().engine.request(ToolCall("calendar.draft", mapOf("title" to "x", "risk" to "READ"))) }
        },
        "no external effect before approval" to { runBlocking {
            val f = Fixture(); val id = f.calendar(); f.engine.execute(id)
            assertEquals(ProposalStatus.WAITING_APPROVAL, f.proposal(id).status); assertEquals(0, f.native.calls)
        } },
        "approval is single use and handoff is not save" to { runBlocking {
            val f = Fixture(); val id = f.calendar(); assertTrue(f.engine.approve(id)); f.engine.execute(id)
            assertFalse(f.engine.approve(id)); f.engine.execute(id)
            assertEquals(1, f.native.calls); assertEquals(ProposalStatus.HANDED_OFF, f.proposal(id).status)
        } },
        "rejection never dispatches" to { runBlocking {
            val f = Fixture(); val id = f.calendar(); f.engine.reject(id); assertFalse(f.engine.approve(id)); f.engine.execute(id)
            assertEquals(ProposalStatus.REJECTED, f.proposal(id).status); assertEquals(0, f.native.calls)
        } },
        "expired approval cannot execute" to {
            val f = Fixture(); val id = f.calendar(); f.time += WorkspaceEngine.APPROVAL_TTL_MS + 1
            assertFalse(f.engine.approve(id)); assertEquals(ProposalStatus.CANCELLED, f.proposal(id).status)
        },
        "approved action expires before delayed execution" to { runBlocking {
            val f = Fixture(); val id = f.calendar(); assertTrue(f.engine.approve(id)); f.time += WorkspaceEngine.APPROVAL_TTL_MS + 1
            f.engine.execute(id); assertEquals(0, f.native.calls); assertEquals(ProposalStatus.CANCELLED, f.proposal(id).status)
        } },
        "clock rollback cannot extend approval" to { val f = Fixture(); val id = f.calendar(); f.time = 0; assertFalse(f.engine.approve(id)) },
        "native route beats service and accessibility" to {
            val f = Fixture(); assertEquals(Route.ANDROID, f.proposal(f.calendar()).route)
        },
        "service preferred over UI when native unavailable" to {
            val f = Fixture(); f.native.enabled = false
            val spec = ToolSpec("synthetic", Risk.READ, emptySet(), setOf(Route.ANDROID, Route.SERVICE_API, Route.ACCESSIBILITY), Verification.SCREEN_METADATA)
            assertEquals(Route.SERVICE_API, f.router.select(spec)); f.service.enabled = false
            assertEquals(Route.ACCESSIBILITY, f.router.select(spec))
        },
        "capability loss does not change approved route" to { runBlocking {
            val f = Fixture(); val id = f.calendar(); f.engine.approve(id); f.native.enabled = false; f.engine.execute(id)
            assertEquals(ProposalStatus.BLOCKED, f.proposal(id).status); assertEquals(0, f.service.calls)
        } },
        "execution error never falls back or stores secret error" to { runBlocking {
            val f = Fixture(); val id = f.calendar(); f.engine.approve(id); f.native.fails = true; f.engine.execute(id)
            assertEquals(ProposalStatus.UNKNOWN, f.proposal(id).status); assertEquals(0, f.service.calls)
            assertFalse(f.engine.state.toString().contains("secret exception"))
        } },
        "calendar verifier rejects fabricated save" to { runBlocking {
            val f = Fixture(); f.native.outcome = ToolOutcome(ProposalStatus.SUCCEEDED, "saved")
            val id = f.calendar(); f.engine.approve(id); f.engine.execute(id)
            assertEquals(ProposalStatus.UNKNOWN, f.proposal(id).status)
        } },
        "stop invalidates pending and approved actions" to { runBlocking {
            val f = Fixture(); val a = f.calendar(); val b = f.calendar(); f.engine.approve(b); f.engine.stop()
            assertFalse(f.engine.approve(a)); f.engine.execute(b); assertEquals(0, f.native.calls)
            assertTrue(f.engine.state.proposals.all { it.status == ProposalStatus.CANCELLED })
        } },
        "cancellation records unknown not success" to { runBlocking {
            val entered = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>(); val store = Store()
            val port = object : ToolPort {
                override val route = Route.ANDROID
                override fun available(tool: String) = true
                override suspend fun execute(call: ToolCall): ToolOutcome { entered.complete(Unit); hold.await(); return ToolOutcome(ProposalStatus.HANDED_OFF, "late") }
            }
            val engine = WorkspaceEngine(store, ToolRouter(listOf(port)))
            val id = engine.request(ToolCall("calendar.draft", mapOf("title" to "x"))); engine.approve(id)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.execute(id) }; entered.await(); engine.stop(); job.cancelAndJoin()
            assertEquals(ProposalStatus.UNKNOWN, engine.state.proposals.single().status)
        } },
        "late result cannot overwrite Stop" to { runBlocking {
            val entered = CompletableDeferred<Unit>(); val hold = CompletableDeferred<Unit>(); val store = Store()
            val port = object : ToolPort {
                override val route = Route.ANDROID
                override fun available(tool: String) = true
                override suspend fun execute(call: ToolCall): ToolOutcome { entered.complete(Unit); hold.await(); return ToolOutcome(ProposalStatus.HANDED_OFF, "late") }
            }
            val engine = WorkspaceEngine(store, ToolRouter(listOf(port)))
            val id = engine.request(ToolCall("calendar.draft", mapOf("title" to "x"))); engine.approve(id)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { engine.execute(id) }; entered.await(); engine.stop(); hold.complete(Unit); job.join()
            assertEquals(ProposalStatus.UNKNOWN, engine.state.proposals.single().status)
        } },
        "restart cancels approvals and never replays running" to {
            val f = Fixture(); val a = f.calendar(); val b = f.calendar(); f.engine.approve(b)
            val running = f.proposal(b).copy(id = "running", status = ProposalStatus.RUNNING)
            f.store.saved = f.engine.state.copy(proposals = f.engine.state.proposals + running)
            val restored = WorkspaceEngine(f.store, f.router)
            assertEquals(ProposalStatus.CANCELLED, restored.state.proposals.first { it.id == a }.status)
            assertEquals(ProposalStatus.CANCELLED, restored.state.proposals.first { it.id == b }.status)
            assertEquals(ProposalStatus.UNKNOWN, restored.state.proposals.first { it.id == "running" }.status)
        },
        "sensitive capability stays blocked even when port exists" to { runBlocking {
            val f = Fixture(); val id = f.engine.request(ToolCall("device.permissions.change"))
            assertFalse(f.engine.approve(id)); f.engine.execute(id); assertEquals(0, f.native.calls)
            assertEquals(ProposalStatus.BLOCKED, f.proposal(id).status)
        } },
        "missing service is explicit, not mock success" to {
            val engine = WorkspaceEngine(Store(), ToolRouter(emptyList()))
            engine.request(ToolCall("crm.task.create", mapOf("title" to "x")))
            assertEquals(ProposalStatus.BLOCKED, engine.state.proposals.single().status)
        },
        "unknown tool and malformed command fail closed" to {
            assertFailsWith<IllegalArgumentException> { Fixture().engine.request(ToolCall("anything")) }
            assertFailsWith<IllegalArgumentException> { PreviewCommands.parse("Отправь деньги") }
            assertFailsWith<IllegalArgumentException> { Fixture().engine.request(ToolCall("workspace.task.create", mapOf("title" to " "))) }
            assertFailsWith<IllegalArgumentException> { Fixture().engine.request(ToolCall("workspace.task.create", mapOf("title" to "x".repeat(257)))) }
        },
        "approved arguments cannot be changed by caller map" to {
            val f = Fixture(); val args = mutableMapOf("title" to "Original")
            val id = f.engine.request(ToolCall("calendar.draft", args)); args["title"] = "Changed"
            assertEquals("Original", f.proposal(id).call.arguments["title"])
        },
        "disk failure prevents dispatch" to { runBlocking {
            val f = Fixture(); val id = f.calendar(); f.engine.approve(id); f.store.fail = true
            assertFailsWith<IllegalStateException> { f.engine.execute(id) }; assertEquals(0, f.native.calls)
        } },
        "disk failure does not publish uncommitted task" to { runBlocking {
            val f = Fixture(); val id = f.engine.request(PreviewCommands.parse("Task: x")); f.store.fail = true
            assertFailsWith<IllegalStateException> { f.engine.execute(id) }; assertTrue(f.engine.state.workItems.isEmpty())
        } },
        "all six entity types round trip" to {
            val s = Workspace(
                contacts = listOf(Contact("c", "Анна", mapOf("email" to "demo@example.invalid"), listOf("demo"))),
                conversations = listOf(Conversation("v", "c", "manual", "ref", 1)),
                workItems = listOf(WorkItem("w", "Задача", "c", 2, true, "follow-up", "ref")),
                deals = listOf(Deal("d", "Deal", "c", "new", 10000, "RUB", "call")),
                proposals = listOf(Proposal("p", ToolCall("calendar.draft", mapOf("title" to "x")), Risk.EXTERNAL, Route.ANDROID, ProposalStatus.WAITING_APPROVAL, 1, "reason")),
                activities = listOf(Activity("a", "p", "manual", "test", "summary", 1, "ref")),
            )
            assertEquals(s, WorkspaceCodec.decode(WorkspaceCodec.encode(s)))
        },
        "corrupt truncated oversized and future snapshots are rejected" to {
            val bytes = WorkspaceCodec.encode(Workspace())
            assertFails { WorkspaceCodec.decode(bytes.copyOf(5)) }
            assertFails { WorkspaceCodec.decode(ByteArray(2 * 1024 * 1024 + 1)) }
            assertFails { WorkspaceCodec.decode(bytes + byteArrayOf(1)) }
            val future = bytes.clone(); future[7] = 2; assertFails { WorkspaceCodec.decode(future) }
        },
    )
    fun runAll() { cases.forEach { (name, body) -> body(); println("PASS $name") }; println("${cases.size} workspace scenarios passed") }
}
fun main() = WorkspaceScenarios.runAll()
