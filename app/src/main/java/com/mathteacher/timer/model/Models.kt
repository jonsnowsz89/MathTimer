package com.mathteacher.timer.model

enum class Mode { IDLE, CLASS, EXAM }
enum class AlertMode { SOUND, VIBRATION, BOTH, SILENT }
enum class SoundPreset { DOUBLE_BEEP, SHARP_CHIME, TRIPLE_TICK, CUSTOM }
enum class VibePattern { SINGLE, DOUBLE, TRIPLE }
enum class AlertKind { PHASE, PING, END }

data class Phase(val name: String, val durationMs: Long)

data class AppSettings(
    val alertMode: AlertMode = AlertMode.BOTH,
    val sound: SoundPreset = SoundPreset.DOUBLE_BEEP,
    val customUri: String? = null,
    val vibe: VibePattern = VibePattern.DOUBLE,
    val volume: Float = 1f,
    val reserveApp: Boolean = false
)

data class TimerState(
    val mode: Mode = Mode.IDLE,
    val title: String = "",
    val phaseIndex: Int = 0,
    val phaseCount: Int = 0,
    val phaseRemainingMs: Long = 0,
    val phaseDurationMs: Long = 0,
    val elapsedMs: Long = 0,
    val remainingMs: Long = 0,
    val overtimeMs: Long = 0,
    val finished: Boolean = false,
    val nextTitle: String? = null,
    val phaseProgress: Float = 0f,
    val paused: Boolean = false,
    val canUndo: Boolean = false
)
