package ai.aios.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.aios.core.planner.PlannerConfig
import ai.aios.core.safety.AutonomyMode

/**
 * Stores the user's API key and run preferences.
 *
 * The key is the user's own credential and never leaves the device except in
 * the Authorization header of a call to Anthropic, so it is held in
 * [EncryptedSharedPreferences] - backed by a hardware-bound master key where
 * the device has a keystore - rather than plain preferences.
 */
class AgentSettings(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            "aios_secure_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, PlannerConfig.DEFAULT_MODEL) ?: PlannerConfig.DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var autonomyMode: AutonomyMode
        get() = runCatching {
            AutonomyMode.valueOf(prefs.getString(KEY_MODE, null) ?: AutonomyMode.CONFIRM_SENSITIVE.name)
        }.getOrDefault(AutonomyMode.CONFIRM_SENSITIVE)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var maxSteps: Int
        get() = prefs.getInt(KEY_MAX_STEPS, 40)
        set(value) = prefs.edit().putInt(KEY_MAX_STEPS, value.coerceIn(5, 200)).apply()

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    fun clearApiKey() = prefs.edit().remove(KEY_API).apply()

    private companion object {
        const val KEY_API = "anthropic_api_key"
        const val KEY_MODEL = "model"
        const val KEY_MODE = "autonomy_mode"
        const val KEY_MAX_STEPS = "max_steps"
    }
}

/** Available models, newest first. Labels are shown in the settings sheet. */
val SUPPORTED_MODELS: List<Pair<String, String>> = listOf(
    "claude-opus-5" to "Opus 5 - best judgement on unfamiliar screens",
    "claude-sonnet-5" to "Sonnet 5 - cheaper, good for routine flows",
    "claude-haiku-4-5" to "Haiku 4.5 - fastest and cheapest",
)
