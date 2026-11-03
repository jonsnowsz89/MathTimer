package com.mathteacher.timer.ui

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mathteacher.timer.alert.AlertPlayer
import com.mathteacher.timer.data.Keys
import com.mathteacher.timer.data.SettingsRepository
import com.mathteacher.timer.model.*
import com.mathteacher.timer.service.TimerService
import com.mathteacher.timer.service.TimerStateHolder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Screen { HOME, EXAM_SETUP, SETTINGS }

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)
    private val player by lazy { AlertPlayer(app) }

    val timer = TimerStateHolder.state
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    var screen by mutableStateOf(Screen.HOME)

    private fun svc(action: String, min: Int = 0) = Intent(app, TimerService::class.java)
        .setAction(action).putExtra(TimerService.EXTRA_MIN, min)

    fun startClass(remainingMin: Int) = ContextCompat.startForegroundService(app, svc(TimerService.ACTION_CLASS, remainingMin))
    fun startExam(minutes: Int) = ContextCompat.startForegroundService(app, svc(TimerService.ACTION_EXAM, minutes))
    fun stop() { app.startService(svc(TimerService.ACTION_STOP)) }
    fun next() { app.startService(svc(TimerService.ACTION_NEXT)) }
    fun prev() { app.startService(svc(TimerService.ACTION_PREV)) }
    fun undo() { app.startService(svc(TimerService.ACTION_UNDO)) }
    fun togglePause(isPaused: Boolean) {
        app.startService(svc(if (isPaused) TimerService.ACTION_RESUME else TimerService.ACTION_PAUSE))
    }
    fun adjust(deltaMin: Int) { app.startService(svc(TimerService.ACTION_ADJUST, deltaMin)) }

    private fun <T> put(k: Preferences.Key<T>, v: T) { viewModelScope.launch { repo.set(k, v) } }
    fun setAlertMode(v: AlertMode) = put(Keys.ALERT, v.name)
    fun setSound(v: SoundPreset) = put(Keys.SOUND, v.name)
    fun setVibe(v: VibePattern) = put(Keys.VIBE, v.name)
    fun setCustomSound(uri: String) { put(Keys.URI, uri); put(Keys.SOUND, SoundPreset.CUSTOM.name) }
    fun setVolume(v: Float) = put(Keys.VOLUME, v)
    fun setReserve(v: Boolean) = put(Keys.RESERVE, v)
    fun test(k: AlertKind, volume: Float? = null) =
        player.play(k, settings.value.let { if (volume != null) it.copy(volume = volume) else it })
}
