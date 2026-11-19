package app.aipiano.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "ai_piano_settings")

/** Speichert die Einstellungen dauerhaft (DataStore). */
class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            inputSource = prefs[KEY_INPUT].toEnum(InputSourceType.USB_MIDI),
            tempoPercent = (prefs[KEY_TEMPO] ?: 100).coerceIn(50, 150),
            practiceHands = prefs[KEY_HANDS].toEnum(PracticeHands.BOTH),
            waitMode = prefs[KEY_WAIT_MODE] ?: true,
            micSensitivity = (prefs[KEY_MIC_SENSITIVITY] ?: 0.6f).coerceIn(0f, 1f),
        )
    }

    suspend fun save(settings: AppSettings) {
        context.settingsStore.edit { prefs ->
            prefs[KEY_INPUT] = settings.inputSource.name
            prefs[KEY_TEMPO] = settings.tempoPercent
            prefs[KEY_HANDS] = settings.practiceHands.name
            prefs[KEY_WAIT_MODE] = settings.waitMode
            prefs[KEY_MIC_SENSITIVITY] = settings.micSensitivity.coerceIn(0f, 1f)
        }
    }

    private companion object {
        val KEY_INPUT = stringPreferencesKey("input_source")
        val KEY_TEMPO = intPreferencesKey("tempo_percent")
        val KEY_HANDS = stringPreferencesKey("practice_hands")
        val KEY_WAIT_MODE = booleanPreferencesKey("wait_mode")
        val KEY_MIC_SENSITIVITY = floatPreferencesKey("mic_sensitivity")

        inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
            this?.let { name -> runCatching { enumValueOf<T>(name) }.getOrNull() } ?: default
    }
}
