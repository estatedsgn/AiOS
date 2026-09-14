package ai.aios.app.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ai.aios.core.device.ActionResult
import ai.aios.core.device.Bounds
import ai.aios.core.device.DeviceController
import ai.aios.core.device.DeviceKey
import ai.aios.core.device.InstalledApp
import ai.aios.core.device.RawNode
import ai.aios.core.device.ScreenElement
import ai.aios.core.device.ScreenGraph
import ai.aios.core.device.ScreenGraphBuilder
import ai.aios.core.device.SwipeDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The agent's hands and eyes.
 *
 * This is the only class in AiOS that touches the phone, and it is deliberately
 * thin: it maps the platform's accessibility tree onto [RawNode] and performs
 * the actions the core module asks for. Every decision about *what* to do is
 * made in `:core`, which is why that half can be tested without a device.
 *
 * The user turns this service on by hand in Settings -> Accessibility. Nothing
 * here can grant that access, and `ActionPolicy` refuses to drive the settings
 * screen that controls it.
 */
class AiosAccessibilityService : AccessibilityService(), DeviceController {

    private val graphBuilder = ScreenGraphBuilder()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // The agent polls the screen when it needs it; reacting to every event
    // would burn battery for no gain.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // --- perception ---------------------------------------------------------

    override suspend fun readScreen(): ScreenGraph = withContext(Dispatchers.Default) {
        val root = rootInActiveWindow
            ?: return@withContext ScreenGraph(
                appPackage = null,
                screenWidth = screenWidth(),
                screenHeight = screenHeight(),
                elements = emptyList(),
            )

        graphBuilder.build(root.toRawNode(), screenWidth(), screenHeight())
    }

    /** Depth-limited: a pathological layout must not blow the stack mid-run. */
    private fun AccessibilityNodeInfo.toRawNode(depth: Int = 0): RawNode {
        val rect = Rect().also { getBoundsInScreen(it) }

        val children = if (depth >= MAX_TREE_DEPTH) {
            emptyList()
        } else {
            (0 until childCount).mapNotNull { index ->
                runCatching { getChild(index)?.toRawNode(depth + 1) }.getOrNull()
            }
        }

        return RawNode(
            className = className?.toString(),
            text = text?.toString(),
            contentDescription = contentDescription?.toString(),
            viewIdResourceName = viewIdResourceName,
            packageName = packageName?.toString(),
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            clickable = isClickable,
            longClickable = isLongClickable,
            editable = isEditable,
            scrollable = isScrollable,
            checkable = isCheckable,
            checked = isChecked,
            selected = isSelected,
            enabled = isEnabled,
            focused = isFocused,
            visible = isVisibleToUser,
            password = isPassword,
            children = children,
            handle = this,
        )
    }

    // --- actions ------------------------------------------------------------

    override suspend fun tap(element: ScreenElement): ActionResult {
        // Prefer the node's own click action: it reaches views that sit under
        // an overlay, and it respects the app's own hit targets.
        element.node()?.let { node ->
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return ActionResult.ok("Tapped \"${element.label}\".")
            }
            // A non-clickable node may still have a clickable ancestor.
            node.clickableAncestor()?.let { ancestor ->
                if (ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return ActionResult.ok("Tapped \"${element.label}\".")
                }
            }
        }
        return gestureTap(element.bounds.centerX, element.bounds.centerY, element.label)
    }

    override suspend fun longPress(element: ScreenElement): ActionResult {
        element.node()?.let { node ->
            if (node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                return ActionResult.ok("Long-pressed \"${element.label}\".")
            }
        }
        val path = Path().apply { moveTo(element.bounds.centerX.toFloat(), element.bounds.centerY.toFloat()) }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, LONG_PRESS_MILLIS))
                .build(),
            "Long-pressed \"${element.label}\".",
        )
    }

    override suspend fun typeText(element: ScreenElement, text: String, submit: Boolean): ActionResult {
        val node = element.node()
            ?: return ActionResult.failed("That field is no longer on screen; read the screen again.")

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return ActionResult.failed("The field rejected the text; it may not be a standard input.")
        }

        if (submit) {
            val submitted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            if (!submitted) {
                return ActionResult.ok("Typed the text, but could not press Enter - submit it another way.")
            }
        }
        return ActionResult.ok("Typed \"$text\".")
    }

    override suspend fun swipe(direction: SwipeDirection, element: ScreenElement?): ActionResult {
        val area = element?.bounds ?: Bounds(0, 0, screenWidth(), screenHeight())

        // Swipe across the middle 60% of the area so the gesture starts and
        // ends well inside it, clear of system edge gestures.
        val cx = area.centerX.toFloat()
        val cy = area.centerY.toFloat()
        val dx = area.width * 0.3f
        val dy = area.height * 0.3f

        val (startX, startY, endX, endY) = when (direction) {
            // A finger travelling up scrolls the content down, which is what
            // "swipe up to see more" means to the model.
            SwipeDirection.UP -> listOf(cx, cy + dy, cx, cy - dy)
            SwipeDirection.DOWN -> listOf(cx, cy - dy, cx, cy + dy)
            SwipeDirection.LEFT -> listOf(cx + dx, cy, cx - dx, cy)
            SwipeDirection.RIGHT -> listOf(cx - dx, cy, cx + dx, cy)
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, SWIPE_MILLIS))
                .build(),
            "Swiped ${direction.name.lowercase()}.",
        )
    }

    override suspend fun pressKey(key: DeviceKey): ActionResult {
        val globalAction = when (key) {
            DeviceKey.BACK -> GLOBAL_ACTION_BACK
            DeviceKey.HOME -> GLOBAL_ACTION_HOME
            DeviceKey.RECENTS -> GLOBAL_ACTION_RECENTS
            DeviceKey.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            DeviceKey.LOCK_SCREEN -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    return ActionResult.failed("Locking the screen needs Android 9 or newer.")
                }
                GLOBAL_ACTION_LOCK_SCREEN
            }

            DeviceKey.ENTER -> {
                // Enter is not a global action: it goes to whatever has focus.
                val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: return ActionResult.failed("Nothing has input focus, so Enter has no target.")
                val ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                return if (ok) ActionResult.ok("Pressed Enter.")
                else ActionResult.failed("The focused field would not accept Enter.")
            }
        }

        return if (performGlobalAction(globalAction)) {
            ActionResult.ok("Pressed ${key.name.lowercase()}.")
        } else {
            ActionResult.failed("The system refused the ${key.name.lowercase()} action.")
        }
    }

    override suspend fun launchApp(packageName: String): ActionResult {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return ActionResult.failed(
                "\"$packageName\" is not installed or has no launcher entry. " +
                    "Use a package name from the installed-apps list."
            )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching {
            startActivity(intent)
            ActionResult.ok("Opened $packageName.")
        }.getOrElse { ActionResult.failed("Could not open $packageName: ${it.message}") }
    }

    override suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        packageManager.queryIntentActivities(launcherIntent, 0)
            .mapNotNull { resolved ->
                val activity = resolved.activityInfo ?: return@mapNotNull null
                InstalledApp(
                    packageName = activity.packageName,
                    label = resolved.loadLabel(packageManager).toString(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label }
    }

    // --- plumbing -----------------------------------------------------------

    private fun ScreenElement.node(): AccessibilityNodeInfo? = handle as? AccessibilityNodeInfo

    private fun AccessibilityNodeInfo.clickableAncestor(limit: Int = 4): AccessibilityNodeInfo? {
        var current = parent
        var depth = 0
        while (current != null && depth < limit) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private suspend fun gestureTap(x: Int, y: Int, label: String): ActionResult {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MILLIS))
                .build(),
            "Tapped \"$label\" at ($x, $y).",
        )
    }

    /** Bridges the callback-based gesture API into the suspending world. */
    private suspend fun dispatch(gesture: GestureDescription, success: String): ActionResult =
        suspendCancellableCoroutine { continuation ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(ActionResult.ok(success))
                }

                override fun onCancelled(description: GestureDescription?) {
                    if (continuation.isActive) {
                        continuation.resume(ActionResult.failed("The gesture was cancelled by the system."))
                    }
                }
            }

            val accepted = dispatchGesture(gesture, callback, null)
            if (!accepted && continuation.isActive) {
                continuation.resume(
                    ActionResult.failed("The system would not accept the gesture.")
                )
            }
        }

    private fun screenWidth() = resources.displayMetrics.widthPixels
    private fun screenHeight() = resources.displayMetrics.heightPixels

    companion object {
        private const val MAX_TREE_DEPTH = 40
        private const val TAP_MILLIS = 60L
        private const val LONG_PRESS_MILLIS = 700L
        private const val SWIPE_MILLIS = 320L

        /**
         * Set while the system has the service bound. The UI reads this to tell
         * the user whether the agent actually has hands yet.
         */
        @Volatile
        var instance: AiosAccessibilityService? = null
            private set

        val isEnabled: Boolean get() = instance != null
    }
}
