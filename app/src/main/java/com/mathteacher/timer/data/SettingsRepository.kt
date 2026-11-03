package com.mathteacher.timer.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.mathteacher.timer.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("settings")

object Keys {
    val ALERT = stringPreferencesKey("alert")
    val SOUND = stringPreferencesKey("sound")
    val URI = stringPreferencesKey("uri")
    val VIBE = stringPreferencesKey("vibe")
    val VOLUME = floatPreferencesKey("volume")
    val RESERVE = booleanPreferencesKey("reserve")
}

private inline fun <reified E : Enum<E>> String?.toEnum(def: E): E =
    runCatching { enumValueOf<E>(this!!) }.getOrDefault(def)

class SettingsRepository(private val ctx: Context) {
    val settings: Flow<AppSettings> = ctx.store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            alertMode = p[Keys.ALERT].toEnum(d.alertMode),
            sound = p[Keys.SOUND].toEnum(d.sound),
            customUri = p[Keys.URI],
            vibe = p[Keys.VIBE].toEnum(d.vibe),
            volume = p[Keys.VOLUME] ?: d.volume,
            reserveApp = p[Keys.RESERVE] ?: d.reserveApp
        )
    }
    suspend fun <T> set(key: Preferences.Key<T>, value: T) { ctx.store.edit { it[key] = value } }
}
