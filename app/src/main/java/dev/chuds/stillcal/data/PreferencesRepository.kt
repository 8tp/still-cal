package dev.chuds.stillcal.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.preferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "still_cal_settings",
)

private val FONT_PRESET_KEY = stringPreferencesKey("font_preset")
private val DEFAULT_VIEW_KEY = stringPreferencesKey("default_view")
private val WEEK_START_KEY = stringPreferencesKey("week_start")
private val TIME_FORMAT_KEY = stringPreferencesKey("time_format")

enum class FontPreset { System, Editorial, Terminal, Grotesk }

enum class DefaultView { Month, Week }

enum class WeekStart { System, Sunday, Monday }

enum class TimeFormat { System, Hour12, Hour24 }

data class CalSettings(
    val fontPreset: FontPreset = FontPreset.System,
    val defaultView: DefaultView = DefaultView.Month,
    val weekStart: WeekStart = WeekStart.System,
    val timeFormat: TimeFormat = TimeFormat.System,
)

class PreferencesRepository(private val context: Context) {

    val settings: Flow<CalSettings> = context.preferencesDataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { prefs ->
            CalSettings(
                fontPreset = prefs[FONT_PRESET_KEY]
                    ?.let { runCatching { FontPreset.valueOf(it) }.getOrNull() }
                    ?: FontPreset.System,
                defaultView = prefs[DEFAULT_VIEW_KEY]
                    ?.let { runCatching { DefaultView.valueOf(it) }.getOrNull() }
                    ?: DefaultView.Month,
                weekStart = prefs[WEEK_START_KEY]
                    ?.let { runCatching { WeekStart.valueOf(it) }.getOrNull() }
                    ?: WeekStart.System,
                timeFormat = prefs[TIME_FORMAT_KEY]
                    ?.let { runCatching { TimeFormat.valueOf(it) }.getOrNull() }
                    ?: TimeFormat.System,
            )
        }

    suspend fun setFontPreset(preset: FontPreset) {
        context.preferencesDataStore.edit { it[FONT_PRESET_KEY] = preset.name }
    }

    suspend fun setDefaultView(view: DefaultView) {
        context.preferencesDataStore.edit { it[DEFAULT_VIEW_KEY] = view.name }
    }

    suspend fun setWeekStart(start: WeekStart) {
        context.preferencesDataStore.edit { it[WEEK_START_KEY] = start.name }
    }

    suspend fun setTimeFormat(format: TimeFormat) {
        context.preferencesDataStore.edit { it[TIME_FORMAT_KEY] = format.name }
    }
}
