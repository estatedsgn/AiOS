package ai.aios.app.preview

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.aios.core.preview.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import java.io.FileInputStream

/** No network keys or mock success screens: exercise the actual APK, store, service and approvals. */
class PreviewSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<PreviewActivity>()
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val store: PreviewStore get() = PreviewStore.get(context)

    @Before fun resetTestWorkspace() {
        PreviewSession.stop()
        compose.waitUntil(10_000) { !PreviewSession.busy.value }
        runBlocking { store.load(); store.change { Workspace() } }
        context.getSharedPreferences("ais_preview_ui", Context.MODE_PRIVATE).edit().putBoolean("cloud_consent", false).commit()
        if (Build.VERSION.SDK_INT >= 33) {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("pm grant ai.aios.app android.permission.POST_NOTIFICATIONS")
            descriptor.use { FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() } }
        }
        compose.waitForIdle()
    }

    @After fun stopAnyRemainingRun() {
        PreviewSession.stop()
        compose.waitUntil(10_000) { !PreviewSession.busy.value }
    }

    @Test fun offlineTaskPersistsAcrossActivityRecreationAndEncryptedStoreRead() {
        compose.onNodeWithTag("tab_work").performClick()
        compose.onNodeWithTag("work_title").performTextInput("Pixel preview smoke task")
        compose.onNodeWithTag("work_add").performClick()
        compose.waitUntil(10_000) { store.state.value.tasks.size == 1 }
        val task = store.state.value.tasks.single()
        assertEquals("Pixel preview smoke task", task.title)
        // Re-open the encrypted file through a separate preferences object, not the in-memory flow.
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val disk = EncryptedSharedPreferences.create(context, "ais_preview_workspace", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        val saved = Json { ignoreUnknownKeys = true }.decodeFromString<Workspace>(disk.getString("workspace_v1", null)!!)
        assertEquals(task, saved.tasks.single())
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab_work").performClick()
        compose.onNodeWithText(task.title).assertExists()
        compose.onNodeWithTag("task_${task.id}").performClick()
        compose.waitUntil(10_000) { store.state.value.tasks.single().done }
        compose.onNodeWithTag("task_${task.id}").performClick()
        compose.waitUntil(10_000) { !store.state.value.tasks.single().done }
    }

    @Test fun incomingSharedTextOnlyFillsDraftAndNeverStartsAgent() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.startActivity(Intent(activity, PreviewActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Shared text is untrusted input")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        compose.onNodeWithTag("chat_input").assertTextContains("Shared text is untrusted input")
        assertFalse(PreviewSession.busy.value)
        assertTrue(store.state.value.messages.isEmpty())
        assertTrue(store.state.value.activity.isEmpty())
    }

    @Test fun rejectingDraftStopsWithoutAndroidHandoff() {
        compose.onNodeWithTag("chat_input").performTextInput("/черновик Never send this automatically")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15_000) { PreviewSession.approval.value != null }
        compose.onNodeWithTag("reject").performClick()
        compose.waitUntil(15_000) { !PreviewSession.busy.value }
        assertNull(PreviewSession.handoff.value)
        assertEquals("REJECTED", store.state.value.activity.single().status)
        assertNull(store.state.value.activeRunId)
        assertTrue(store.state.value.messages.last().text.contains("отклонили"))
    }

    @Test fun stopNotificationActionCancelsPendingApprovalAndStaleApproveIsIgnored() {
        compose.onNodeWithTag("chat_input").performTextInput("/черновик Cancel before any handoff")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15_000) { PreviewSession.approval.value != null }
        val pendingId = PreviewSession.approval.value!!.id
        // Exercise the exact private-service action used by the notification's Stop PendingIntent.
        compose.activityRule.scenario.onActivity { activity ->
            activity.startService(Intent(activity, PreviewService::class.java).setAction("ai.aios.app.PREVIEW_STOP"))
        }
        compose.waitUntil(15_000) { !PreviewSession.busy.value }
        compose.runOnIdle { PreviewSession.decide(pendingId, true) }
        assertNull(PreviewSession.approval.value)
        assertNull(PreviewSession.handoff.value)
        assertEquals("INTERRUPTED", store.state.value.activity.single().status)
        assertNull(store.state.value.activeRunId)
    }

    @Test fun duplicateStartCannotRunTwoAgentsOrCreateTwoTasks() {
        compose.activityRule.scenario.onActivity { activity ->
            PreviewService.start(activity, "/задача Exactly once")
            PreviewService.start(activity, "/задача Must not start in parallel")
        }
        compose.waitUntil(15_000) { store.state.value.tasks.isNotEmpty() && !PreviewSession.busy.value }
        assertEquals(listOf("Exactly once"), store.state.value.tasks.map { it.title })
        assertEquals(1, store.state.value.activity.count { it.status == "VERIFIED" })
    }

    @Test fun localBriefUsesActualWorkspaceWithoutCloudConsent() {
        runBlocking { store.change { it.addTask("Review a real record", id = "brief-test") } }
        compose.onNodeWithTag("chat_input").performTextInput("/сводка")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(15_000) { store.state.value.messages.size >= 2 && !PreviewSession.busy.value }
        assertTrue(store.state.value.messages.last().text.contains("Review a real record"))
        assertTrue(store.state.value.messages.last().text.contains("без обращения к ИИ"))
    }
}
