package com.mathteacher.timer.data

import android.content.Context
import com.mathteacher.timer.model.Mode

/** Persists the running session so the service can resume after being killed. */
object SessionStore {
    data class Session(
        val mode: Mode, val minutes: Int, val reserve: Boolean, val startAt: Long,
        val snap: String, val lastEvent: Long, val paused: Boolean, val pausedAt: Long)

    private fun sp(c: Context) = c.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun save(c: Context, s: Session) = sp(c).edit()
        .putString("mode", s.mode.name).putInt("minutes", s.minutes).putBoolean("reserve", s.reserve)
        .putLong("startAt", s.startAt).putString("snap", s.snap).putLong("lastEvent", s.lastEvent)
        .putBoolean("paused", s.paused).putLong("pausedAt", s.pausedAt).apply()

    fun load(c: Context): Session? {
        val p = sp(c)
        val m = p.getString("mode", null) ?: return null
        return Session(Mode.valueOf(m), p.getInt("minutes", 50), p.getBoolean("reserve", false),
            p.getLong("startAt", 0), p.getString("snap", "") ?: "", p.getLong("lastEvent", 0),
            p.getBoolean("paused", false), p.getLong("pausedAt", 0))
    }

    fun clear(c: Context) { sp(c).edit().clear().apply() }
}
