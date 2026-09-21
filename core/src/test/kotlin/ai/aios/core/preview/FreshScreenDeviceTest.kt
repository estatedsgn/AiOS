package ai.aios.core.preview

import ai.aios.core.device.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FreshScreenDeviceTest {
    private val element = ScreenElement(1, "button", "Open", Bounds(1, 2, 100, 80), setOf(Affordance.TAP), handle = Any())
    private val original = ScreenGraph("com.example", 1080, 2400, listOf(element))
    private class Fake(var screen: ScreenGraph) : DeviceController {
        var taps = 0
        var received: ScreenElement? = null
        override suspend fun readScreen() = screen
        override suspend fun tap(element: ScreenElement): ActionResult { taps++; received = element; return ActionResult.ok() }
        override suspend fun longPress(element: ScreenElement) = tap(element)
        override suspend fun typeText(element: ScreenElement, text: String, submit: Boolean) = tap(element)
        override suspend fun swipe(direction: SwipeDirection, element: ScreenElement?) = ActionResult.ok()
        override suspend fun pressKey(key: DeviceKey) = ActionResult.ok()
        override suspend fun launchApp(packageName: String) = ActionResult.ok()
        override suspend fun installedApps() = emptyList<InstalledApp>()
    }

    @Test fun `switching to approval app prevents tapping old screen`() = runTest {
        val fake = Fake(original)
        val guarded = FreshScreenDevice(fake)
        guarded.readScreen()
        fake.screen = original.copy(appPackage = "ai.aios.app")
        assertFalse(guarded.tap(element).ok)
        assertEquals(0, fake.taps)
    }

    @Test fun `changed bounds with same ID cannot reuse old coordinates`() = runTest {
        val fake = Fake(original)
        val guarded = FreshScreenDevice(fake)
        guarded.readScreen()
        fake.screen = original.copy(elements = listOf(element.copy(bounds = Bounds(300, 400, 500, 600))))
        assertFalse(guarded.tap(element).ok)
        assertEquals(0, fake.taps)
    }

    @Test fun `unchanged screen executes using newly read handle not old object`() = runTest {
        val fake = Fake(original)
        val guarded = FreshScreenDevice(fake)
        guarded.readScreen()
        val freshHandle = Any()
        fake.screen = original.copy(elements = listOf(element.copy(handle = freshHandle)))
        assertTrue(guarded.tap(element).ok)
        assertEquals(1, fake.taps)
        assertSame(freshHandle, fake.received?.handle)
    }

    @Test fun `no observation means no element action`() = runTest {
        val fake = Fake(original)
        assertFalse(FreshScreenDevice(fake).tap(element).ok)
        assertEquals(0, fake.taps)
    }
}
