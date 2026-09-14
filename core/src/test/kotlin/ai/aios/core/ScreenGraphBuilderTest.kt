package ai.aios.core

import ai.aios.core.device.Affordance
import ai.aios.core.device.Bounds
import ai.aios.core.device.RawNode
import ai.aios.core.device.ScreenGraphBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenGraphBuilderTest {

    private val builder = ScreenGraphBuilder()

    private fun root(vararg children: RawNode) = RawNode(
        className = "android.widget.FrameLayout",
        packageName = "com.example.app",
        bounds = Bounds(0, 0, 1080, 2340),
        children = children.toList(),
    )

    @Test
    fun `lifts the label of a wrapped text view onto its clickable ancestor`() {
        // The shape Android actually produces for a button: a clickable
        // container whose words live two levels down.
        val tree = root(
            RawNode(
                className = "android.widget.FrameLayout",
                bounds = Bounds(0, 100, 1080, 250),
                clickable = true,
                children = listOf(
                    RawNode(
                        className = "android.widget.LinearLayout",
                        bounds = Bounds(0, 100, 1080, 250),
                        children = listOf(
                            RawNode(
                                className = "android.widget.TextView",
                                text = "Save note",
                                bounds = Bounds(20, 120, 400, 230),
                            )
                        ),
                    )
                ),
            )
        )

        val graph = builder.build(tree, 1080, 2340)

        val save = graph.elements.single { it.label == "Save note" }
        assertTrue(Affordance.TAP in save.affordances, "the lifted element must stay tappable")
        // The inner TextView must not survive as a second, untappable copy.
        assertEquals(1, graph.elements.count { it.label == "Save note" })
    }

    @Test
    fun `drops invisible and zero-sized nodes`() {
        val tree = root(
            RawNode(text = "visible", bounds = Bounds(0, 0, 200, 100)),
            RawNode(text = "hidden", bounds = Bounds(0, 100, 200, 200), visible = false),
            RawNode(text = "collapsed", bounds = Bounds(0, 0, 0, 0)),
        )

        val labels = builder.build(tree, 1080, 2340).elements.map { it.label }

        assertEquals(listOf("visible"), labels)
    }

    @Test
    fun `keeps decorative text but marks it non-interactive`() {
        val tree = root(RawNode(className = "android.widget.TextView", text = "Inbox", bounds = Bounds(0, 0, 300, 80)))

        val element = builder.build(tree, 1080, 2340).elements.single()

        assertEquals("text", element.role)
        assertTrue(element.affordances.isEmpty())
    }

    @Test
    fun `classifies roles from platform class names and flags`() {
        val tree = root(
            RawNode(className = "android.widget.EditText", bounds = Bounds(0, 0, 500, 80), editable = true),
            RawNode(className = "android.widget.Switch", text = "Wi-Fi", bounds = Bounds(0, 100, 500, 180), checkable = true, checked = true),
            RawNode(className = "android.widget.Button", text = "OK", bounds = Bounds(0, 200, 300, 280), clickable = true),
        )

        val roles = builder.build(tree, 1080, 2340).elements.associate { it.role to it.checked }

        assertTrue("edittext" in roles.keys)
        assertEquals(true, roles["switch"])
        assertTrue("button" in roles.keys)
    }

    @Test
    fun `caps element count and reports how many were dropped`() {
        val many = (1..120).map {
            RawNode(className = "android.widget.Button", text = "Item $it", bounds = Bounds(0, it * 10, 300, it * 10 + 8), clickable = true)
        }
        val graph = ScreenGraphBuilder(maxElements = 30).build(root(*many.toTypedArray()), 1080, 2340)

        assertEquals(30, graph.elements.size)
        assertEquals(90, graph.truncatedCount)
        assertTrue(graph.render().contains("90 more elements omitted"))
    }

    @Test
    fun `assigns contiguous ids that resolve back to elements`() {
        val tree = root(
            RawNode(text = "one", bounds = Bounds(0, 0, 100, 50), clickable = true),
            RawNode(text = "two", bounds = Bounds(0, 60, 100, 110), clickable = true),
        )

        val graph = builder.build(tree, 1080, 2340)

        assertEquals(listOf(0, 1), graph.elements.map { it.id })
        assertNotNull(graph.element(0))
        assertNull(graph.element(99), "an id outside the snapshot must not resolve")
    }

    @Test
    fun `render is compact and names the app and screen size`() {
        val tree = root(RawNode(className = "android.widget.Button", text = "Go", bounds = Bounds(0, 0, 100, 50), clickable = true))

        val rendered = builder.build(tree, 1080, 2340).render()

        assertTrue(rendered.startsWith("SCREEN 1080x2340 app=com.example.app"), rendered)
        assertTrue(rendered.contains("""[0] button "Go""""), rendered)
    }

    @Test
    fun `content description stands in when there is no text`() {
        val tree = root(
            RawNode(
                className = "android.widget.ImageButton",
                contentDescription = "Compose new message",
                bounds = Bounds(900, 2000, 1040, 2140),
                clickable = true,
            )
        )

        val element = builder.build(tree, 1080, 2340).elements.single()

        assertEquals("Compose new message", element.label)
        assertEquals("imagebutton", element.role)
    }
}
