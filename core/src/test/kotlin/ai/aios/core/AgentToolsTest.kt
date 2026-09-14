package ai.aios.core

import ai.aios.core.device.AgentAction
import ai.aios.core.device.DeviceKey
import ai.aios.core.device.SwipeDirection
import ai.aios.core.planner.AgentTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentToolsTest {

    @Test
    fun `every action the runner can execute has a tool`() {
        val names = AgentTools.all().map { it.name() }.toSet()
        assertEquals(
            setOf("tap", "long_press", "type_text", "swipe", "press_key", "launch_app", "wait", "ask_user", "finish"),
            names,
        )
    }

    @Test
    fun `parses a tap`() {
        val action = AgentTools.parse("tap", mapOf("element_id" to 7, "rationale" to "open it"))
        val tap = assertIs<AgentAction.Tap>(action)
        assertEquals(7, tap.elementId)
        assertEquals("open it", tap.rationale)
    }

    @Test
    fun `accepts numeric arguments that arrive as strings or longs`() {
        // Tool inputs come back as loosely typed JSON; both shapes show up.
        assertIs<AgentAction.Tap>(AgentTools.parse("tap", mapOf("element_id" to "7", "rationale" to "r")))
        assertIs<AgentAction.Tap>(AgentTools.parse("tap", mapOf("element_id" to 7L, "rationale" to "r")))
    }

    @Test
    fun `parses type_text and defaults submit to false`() {
        val action = AgentTools.parse("type_text", mapOf("element_id" to 2, "text" to "hi", "rationale" to "r"))
        val type = assertIs<AgentAction.TypeText>(action)
        assertEquals("hi", type.text)
        assertTrue(!type.submit)
    }

    @Test
    fun `parses enums case-insensitively`() {
        val swipe = assertIs<AgentAction.Swipe>(
            AgentTools.parse("swipe", mapOf("direction" to "UP", "rationale" to "r"))
        )
        assertEquals(SwipeDirection.UP, swipe.direction)

        val key = assertIs<AgentAction.PressKey>(
            AgentTools.parse("press_key", mapOf("key" to "Back", "rationale" to "r"))
        )
        assertEquals(DeviceKey.BACK, key.key)
    }

    @Test
    fun `clamps a wait to a sane range`() {
        val tooLong = assertIs<AgentAction.Wait>(
            AgentTools.parse("wait", mapOf("millis" to 999_999, "rationale" to "r"))
        )
        assertEquals(10_000L, tooLong.millis)
    }

    @Test
    fun `returns null for unknown tools and malformed arguments`() {
        assertNull(AgentTools.parse("self_destruct", mapOf("rationale" to "r")))
        assertNull(AgentTools.parse("tap", mapOf("rationale" to "r")), "a tap with no element id is unusable")
        assertNull(AgentTools.parse("swipe", mapOf("direction" to "sideways", "rationale" to "r")))
    }

    @Test
    fun `finish carries its success flag`() {
        val finish = assertIs<AgentAction.Finish>(
            AgentTools.parse("finish", mapOf("success" to true, "summary" to "done", "rationale" to "r"))
        )
        assertTrue(finish.success)
        assertEquals("done", finish.summary)
    }
}
