package ai.aios.core.preview

import ai.aios.core.device.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Conservative guard for the experimental screen controller. Approval can change the foreground
 * app: never send an old node or its old coordinates to Android. This does not make accessibility
 * atomic; the UI can still change after validation, so it remains an explicit experiment.
 */
class FreshScreenDevice(private val delegate: DeviceController) : DeviceController by delegate {
    private var observed: ScreenGraph? = null

    override suspend fun readScreen(): ScreenGraph = delegate.readScreen().also { observed = comparable(it) }

    private fun comparable(screen: ScreenGraph) = screen.copy(elements = screen.elements.map { it.copy(handle = null) })
    private suspend fun fresh(): ScreenGraph? {
        currentCoroutineContext().ensureActive()
        val current = delegate.readScreen()
        currentCoroutineContext().ensureActive()
        return current.takeIf { observed != null && comparable(it) == observed }
    }
    private fun changed() = ActionResult.failed("Экран изменился после планирования/подтверждения. Старые элементы и координаты не использованы.")

    override suspend fun tap(element: ScreenElement): ActionResult {
        val target = fresh()?.element(element.id) ?: return changed()
        currentCoroutineContext().ensureActive()
        return delegate.tap(target)
    }
    override suspend fun longPress(element: ScreenElement): ActionResult {
        val target = fresh()?.element(element.id) ?: return changed()
        currentCoroutineContext().ensureActive()
        return delegate.longPress(target)
    }
    override suspend fun typeText(element: ScreenElement, text: String, submit: Boolean): ActionResult {
        val target = fresh()?.element(element.id) ?: return changed()
        currentCoroutineContext().ensureActive()
        return delegate.typeText(target, text, submit)
    }
    override suspend fun swipe(direction: SwipeDirection, element: ScreenElement?): ActionResult {
        val current = fresh() ?: return changed()
        val target = element?.let { current.element(it.id) ?: return changed() }
        currentCoroutineContext().ensureActive()
        return delegate.swipe(direction, target)
    }
    override suspend fun pressKey(key: DeviceKey): ActionResult {
        fresh() ?: return changed()
        currentCoroutineContext().ensureActive()
        return delegate.pressKey(key)
    }
    override suspend fun launchApp(packageName: String): ActionResult {
        // A named Android intent does not rely on the old screen's element IDs or coordinates.
        currentCoroutineContext().ensureActive()
        return delegate.launchApp(packageName)
    }
}
