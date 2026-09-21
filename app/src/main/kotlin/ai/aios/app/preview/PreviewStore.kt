package ai.aios.app.preview

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.aios.core.preview.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small encrypted, versioned preview store. All disk access is off the UI thread. */
class PreviewStore private constructor(context: Context) {
    private val app = context.applicationContext
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(Workspace())
    val state = _state.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    private fun loadLocked() {
        if (_ready.value) return
        try {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val storage = EncryptedSharedPreferences.create(app, "ais_preview_workspace", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            val saved = storage.getString("workspace_v1", null)?.let { json.decodeFromString<Workspace>(it) } ?: Workspace()
            check(saved.version == 1) { "Unsupported workspace version" }
            val recovered = saved.recover(System.currentTimeMillis())
            if (saved != recovered) {
                check(storage.edit().putString("workspace_v1", json.encodeToString(recovered)).commit())
            }
            prefs = storage
            _state.value = recovered
            _ready.value = true
            _error.value = null
        } catch (e: Exception) {
            _error.value = "Не удалось открыть защищённое хранилище. Данные не сброшены. Закройте и снова откройте AIS."
            throw e
        }
    }

    /** Publish success only after the complete new state is committed to encrypted storage. */
    suspend fun change(transform: (Workspace) -> Workspace): Workspace = withContext(Dispatchers.IO) {
        mutex.withLock {
            loadLocked()
            val next = transform(_state.value)
            val bounded = next.copy(messages = next.messages.takeLast(200), activity = next.activity.takeLast(300))
            val encoded = json.encodeToString(bounded)
            if (prefs?.edit()?.putString("workspace_v1", encoded)?.commit() != true) {
                _error.value = "Запись не подтверждена. Проверьте свободное место и повторно откройте AIS."
                error("Workspace write failed")
            }
            _state.value = bounded
            _error.value = null
            bounded
        }
    }

    companion object {
        @Volatile private var instance: PreviewStore? = null
        fun get(context: Context): PreviewStore = instance ?: synchronized(this) {
            instance ?: PreviewStore(context).also { instance = it }
        }
    }
}
