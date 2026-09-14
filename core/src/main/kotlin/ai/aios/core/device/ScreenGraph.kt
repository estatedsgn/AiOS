package ai.aios.core.device

/** Screen-space rectangle in device pixels. */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val area: Long get() = width.toLong() * height.toLong()

    fun isEmpty(): Boolean = width <= 0 || height <= 0

    fun intersects(other: Bounds): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

/**
 * A node exactly as the platform reported it, before any interpretation.
 *
 * The Android layer maps `AccessibilityNodeInfo` onto this and does nothing
 * else; every decision about what is worth showing the model lives in
 * [ScreenGraphBuilder], which is pure Kotlin and therefore testable without a
 * device.
 */
data class RawNode(
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val viewIdResourceName: String? = null,
    val packageName: String? = null,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val visible: Boolean = true,
    val password: Boolean = false,
    val children: List<RawNode> = emptyList(),
)

/** What the model is allowed to do with a node. */
enum class Affordance { TAP, LONG_PRESS, TYPE, SCROLL, TOGGLE }

/**
 * One addressable element on screen. [id] is the handle the model uses; it is
 * stable only within a single [ScreenGraph] snapshot.
 */
data class ScreenElement(
    val id: Int,
    val role: String,
    val label: String,
    val bounds: Bounds,
    val affordances: Set<Affordance>,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val checked: Boolean? = null,
    val password: Boolean = false,
    val resourceId: String? = null,
) {
    val isInteractive: Boolean get() = affordances.isNotEmpty()
}

/** A single snapshot of what is on the device screen. */
data class ScreenGraph(
    val appPackage: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val elements: List<ScreenElement>,
    val truncatedCount: Int = 0,
) {
    fun element(id: Int): ScreenElement? = elements.firstOrNull { it.id == id }

    /**
     * Compact text rendering handed to the model. Optimised for tokens: one
     * line per element, no JSON punctuation, coordinates only where they add
     * information the label does not already carry.
     */
    fun render(): String = buildString {
        append("SCREEN ").append(screenWidth).append('x').append(screenHeight)
        appPackage?.let { append(" app=").append(it) }
        append('\n')

        if (elements.isEmpty()) {
            append("(no readable elements - the screen may still be loading)\n")
            return@buildString
        }

        for (e in elements) {
            append('[').append(e.id).append("] ").append(e.role)
            if (e.label.isNotEmpty()) {
                append(" \"").append(e.label.replace("\"", "'")).append('"')
            }
            val flags = buildList {
                if (!e.enabled) add("disabled")
                if (e.focused) add("focused")
                if (e.password) add("password")
                when (e.checked) {
                    true -> add("checked")
                    false -> add("unchecked")
                    null -> {}
                }
                if (Affordance.SCROLL in e.affordances) add("scrollable")
                if (Affordance.TYPE in e.affordances) add("editable")
            }
            if (flags.isNotEmpty()) append(' ').append(flags.joinToString(" "))
            append(" @").append(e.bounds.centerX).append(',').append(e.bounds.centerY)
            append('\n')
        }

        if (truncatedCount > 0) {
            append("(+").append(truncatedCount)
            append(" more elements omitted - scroll or narrow the view to reach them)\n")
        }
    }
}
