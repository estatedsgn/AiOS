package ai.aios.app.workspace

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.Settings
import android.util.AtomicFile
import ai.aios.app.device.AiosAccessibilityService
import ai.aios.core.workspace.*
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AtomicWorkspaceStore(context: Context) : WorkspaceStore {
    private val base = File(context.filesDir, "workspace-v1.bin")
    private val file = AtomicFile(base)
    override fun load(): Workspace = try {
        WorkspaceCodec.decode(file.openRead().use { input ->
            // Bound allocation even when a developer has replaced the file with corrupt data.
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= 2 * 1024 * 1024) { "Workspace too large" }
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        })
    } catch (error: FileNotFoundException) {
        if (base.exists() || File(base.path + ".bak").exists()) throw error
        Workspace()
    }
    override fun save(workspace: Workspace) {
        val bytes = WorkspaceCodec.encode(workspace)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}

private class AndroidPreviewPort(private val context: Context) : ToolPort {
    override val route = Route.ANDROID
    private fun intent(tool: String): Intent? = when (tool) {
        "calendar.draft" -> Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
        "android.settings" -> Intent(Settings.ACTION_SETTINGS)
        else -> null
    }
    override fun available(tool: String) = intent(tool)?.resolveActivity(context.packageManager) != null
    override suspend fun execute(call: ToolCall): ToolOutcome = withContext(Dispatchers.Main.immediate) {
        ensureActive()
        if (!WorkspaceSession.visible) return@withContext ToolOutcome(ProposalStatus.BLOCKED, "APK не на переднем плане; действие не запущено.")
        val target = intent(call.tool) ?: return@withContext ToolOutcome(ProposalStatus.BLOCKED, "Неподдерживаемый Android tool.")
        if (call.tool == "calendar.draft") target.putExtra(CalendarContract.Events.TITLE, call.arguments.getValue("title"))
        try {
            context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolOutcome(ProposalStatus.HANDED_OFF, if (call.tool == "calendar.draft") "Android принял запрос на открытие редактора календаря. Сохранение встречи не проверено." else "Запрос передан настройкам Android; параметры не изменялись.")
        } catch (_: ActivityNotFoundException) {
            ToolOutcome(ProposalStatus.BLOCKED, "Подходящее приложение не найдено. Автозамены нет.")
        } catch (_: SecurityException) {
            ToolOutcome(ProposalStatus.BLOCKED, "Android отклонил запрос. Права не расширяются.")
        }
    }
}

/** Real read-only accessibility adapter. Never stores screen text or notification bodies. */
private class ScreenMetadataPort : ToolPort {
    override val route = Route.ACCESSIBILITY
    override fun available(tool: String) = tool == "screen.inspect" && AiosAccessibilityService.isEnabled
    override suspend fun execute(call: ToolCall): ToolOutcome = withContext(Dispatchers.Main.immediate) {
        ensureActive()
        if (!WorkspaceSession.visible) return@withContext ToolOutcome(ProposalStatus.BLOCKED, "Нет активного запроса с экрана APK.")
        val root = AiosAccessibilityService.instance?.rootInActiveWindow
            ?: return@withContext ToolOutcome(ProposalStatus.FAILED, "Дерево экрана недоступно. Accessibility включается только вручную.")
        try {
            ToolOutcome(ProposalStatus.SUCCEEDED, "Пакет: ${root.packageName?.toString()?.take(200) ?: "неизвестен"}; дочерних узлов: ${root.childCount}. Текст экрана не сохраняется.")
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }
}

/** One writer per process. All disk work runs off the UI thread; no background agent loop. */
class WorkspaceSession private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var engine: WorkspaceEngine? = null
    private var job: Job? = null
    private val _workspace = MutableStateFlow<Workspace?>(null)
    private val _error = MutableStateFlow<String?>(null)
    private val _busy = MutableStateFlow(true)
    private val _stopping = MutableStateFlow(false)
    val workspace = _workspace.asStateFlow()
    val error = _error.asStateFlow()
    val busy = _busy.asStateFlow()
    val stopping = _stopping.asStateFlow()

    init {
        scope.launch {
            try {
                engine = WorkspaceEngine(AtomicWorkspaceStore(context), ToolRouter(listOf(AndroidPreviewPort(context), ScreenMetadataPort())))
                refresh()
            } catch (_: Exception) {
                _error.value = "Workspace не прочитан. Файл не сброшен и не перезаписан. Ручной запуск приложений доступен."
            } finally { _busy.value = false }
        }
    }
    fun goal(input: String) = run { execute(PreviewCommands.parse(input)) }
    fun call(call: ToolCall) = run { execute(call) }
    fun approve(id: String) = run { if (requireNotNull(engine).approve(id)) { refresh(); requireNotNull(engine).execute(id) } }
    fun reject(id: String) = run { requireNotNull(engine).reject(id) }
    private suspend fun execute(call: ToolCall) {
        currentCoroutineContext().ensureActive()
        val current = requireNotNull(engine)
        val id = current.request(call)
        refresh()
        currentCoroutineContext().ensureActive()
        current.execute(id)
    }
    private fun run(block: suspend () -> Unit) {
        if (_busy.value || _stopping.value || engine == null) return
        _busy.value = true
        _error.value = null
        job = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: IllegalArgumentException) { _error.value = error.message ?: "Неверный запрос." }
            catch (_: Exception) { _error.value = "Операция прервана. Проверьте журнал и свободное место; автоматического повтора нет." }
            finally { refresh(); _busy.value = false }
        }
    }
    fun stop() {
        if (_stopping.value) return
        _stopping.value = true
        val previous = job
        previous?.cancel()
        scope.launch {
            try { previous?.join(); engine?.stop() }
            catch (_: Exception) { _error.value = "Не удалось сохранить остановку. Закройте APK; после перезапуска действия не воспроизводятся." }
            finally { refresh(); _stopping.value = false }
        }
    }
    private fun refresh() { _workspace.value = engine?.state }
    companion object {
        @Volatile var visible = false
        @Volatile private var instance: WorkspaceSession? = null
        fun get(context: Context): WorkspaceSession = instance ?: synchronized(this) {
            instance ?: WorkspaceSession(context.applicationContext).also { instance = it }
        }
    }
}
