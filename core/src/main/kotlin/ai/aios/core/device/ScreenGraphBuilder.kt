package ai.aios.core.device

/**
 * Turns a raw accessibility tree into the compact [ScreenGraph] the model sees.
 *
 * Android's accessibility trees are noisy: a single visible button is often a
 * clickable `FrameLayout` wrapping an unlabelled `LinearLayout` wrapping the
 * `TextView` that actually holds the words. Feeding that to a model wastes
 * tokens and invites taps on the wrong node, so the builder does three things:
 *
 *  1. drops nodes that are invisible, empty, or off-screen,
 *  2. lifts a descendant's text onto the clickable ancestor that owns it, so
 *     one real button becomes one element rather than three,
 *  3. keeps only elements that are either actionable or carry readable text.
 */
class ScreenGraphBuilder(
    private val maxElements: Int = 80,
    private val maxLabelLength: Int = 120,
) {

    fun build(root: RawNode, screenWidth: Int, screenHeight: Int): ScreenGraph {
        val screen = Bounds(0, 0, screenWidth, screenHeight)
        val collected = mutableListOf<Candidate>()
        collect(root, screen, collected)

        val deduped = dedupe(collected)
        val kept = deduped.take(maxElements)
        val elements = kept.mapIndexed { index, c -> c.toElement(index) }

        return ScreenGraph(
            appPackage = root.packageName,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            elements = elements,
            truncatedCount = (deduped.size - kept.size).coerceAtLeast(0),
        )
    }

    private data class Candidate(
        val node: RawNode,
        val label: String,
        val role: String,
        val affordances: Set<Affordance>,
    ) {
        fun toElement(id: Int) = ScreenElement(
            id = id,
            role = role,
            label = label,
            bounds = node.bounds,
            affordances = affordances,
            enabled = node.enabled,
            focused = node.focused,
            checked = if (node.checkable) node.checked else null,
            password = node.password,
            resourceId = node.viewIdResourceName?.substringAfterLast('/'),
            handle = node.handle,
        )
    }

    private fun collect(node: RawNode, screen: Bounds, out: MutableList<Candidate>) {
        if (!node.visible || node.bounds.isEmpty() || !node.bounds.intersects(screen)) {
            // An invisible or off-screen subtree cannot be acted on, but a
            // scrollable container may still host children worth reaching, so
            // only prune when the container itself is degenerate.
            if (node.bounds.isEmpty() || !node.visible) return
        }

        val affordances = affordancesOf(node)
        val ownLabel = labelOf(node)

        // A clickable wrapper with no words of its own borrows the text of the
        // subtree it wraps - that is the element a person actually sees.
        val label = when {
            ownLabel.isNotEmpty() -> ownLabel
            affordances.isNotEmpty() -> borrowLabel(node)
            else -> ""
        }

        val worthShowing = affordances.isNotEmpty() || label.isNotEmpty()
        if (worthShowing) {
            out += Candidate(node, truncate(label), roleOf(node, affordances), affordances)
        }

        // Children whose text was lifted onto this node are still traversed:
        // dedupe below removes the ones that became redundant.
        for (child in node.children) collect(child, screen, out)
    }

    private fun affordancesOf(node: RawNode): Set<Affordance> = buildSet {
        if (node.editable) add(Affordance.TYPE)
        if (node.clickable) add(Affordance.TAP)
        if (node.longClickable) add(Affordance.LONG_PRESS)
        if (node.scrollable) add(Affordance.SCROLL)
        if (node.checkable) add(Affordance.TOGGLE)
    }

    private fun labelOf(node: RawNode): String {
        val text = node.text?.trim().orEmpty()
        if (text.isNotEmpty()) return text
        return node.contentDescription?.trim().orEmpty()
    }

    /** Depth-first search for the nearest descendant that carries words. */
    private fun borrowLabel(node: RawNode): String {
        val parts = mutableListOf<String>()
        fun walk(n: RawNode, depth: Int) {
            if (depth > 4 || parts.size >= 2) return
            for (child in n.children) {
                if (!child.visible) continue
                // Stop at a nested interactive node: its text belongs to it.
                if (affordancesOf(child).isNotEmpty()) continue
                val l = labelOf(child)
                if (l.isNotEmpty()) parts += l else walk(child, depth + 1)
                if (parts.size >= 2) return
            }
        }
        walk(node, 0)
        return parts.joinToString(" ")
    }

    private fun roleOf(node: RawNode, affordances: Set<Affordance>): String {
        val cls = node.className?.substringAfterLast('.').orEmpty()
        return when {
            node.editable -> "edittext"
            node.checkable && cls.contains("Switch", true) -> "switch"
            node.checkable -> "checkbox"
            // Checked before the plain Button case: "ImageButton" matches both,
            // and the icon reading is the more useful one for the model.
            cls.contains("Image", true) && Affordance.TAP in affordances -> "imagebutton"
            cls.contains("Image", true) -> "image"
            cls.contains("Button", true) -> "button"
            Affordance.SCROLL in affordances -> "list"
            Affordance.TAP in affordances -> "button"
            cls.contains("EditText", true) -> "edittext"
            else -> "text"
        }
    }

    /**
     * Removes elements made redundant by label lifting: a child whose label and
     * position were absorbed by an interactive ancestor, and exact duplicates.
     */
    private fun dedupe(candidates: List<Candidate>): List<Candidate> {
        val result = mutableListOf<Candidate>()
        for (c in candidates) {
            val redundant = result.any { kept ->
                kept.label.isNotEmpty() &&
                    kept.label == c.label &&
                    // The kept element is interactive and geometrically contains
                    // this one, so tapping either would do the same thing.
                    kept.affordances.isNotEmpty() &&
                    kept.node.bounds.contains(c.node.bounds)
            }
            if (!redundant) result += c
        }
        return result
    }

    private fun truncate(s: String): String =
        if (s.length <= maxLabelLength) s else s.take(maxLabelLength - 1) + "…"
}

private fun Bounds.contains(other: Bounds): Boolean =
    left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
