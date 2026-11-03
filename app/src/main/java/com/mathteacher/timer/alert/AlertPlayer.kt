package com.mathteacher.timer.alert

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.*
import com.mathteacher.timer.model.*
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays alerts on the ALARM audio stream (not the notification/ringtone streams), using
 * synthesised high-pitched tones (2-3 kHz) that sound unlike any stock phone notification.
 * No audio assets required.
 */
class AlertPlayer(private val ctx: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, ShortArray>()
    private val vibrator = ctx.getSystemService(Vibrator::class.java)
    private val alarmAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    private val am = ctx.getSystemService(AudioManager::class.java)

    /** Briefly lowers music/video while the alert plays, then gives focus back. */
    private fun focus(durationMs: Long) {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(alarmAttrs).build()
        if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            handler.postDelayed({ am.abandonAudioFocusRequest(req) }, durationMs)
    }

    fun play(kind: AlertKind, s: AppSettings) {
        if (s.alertMode == AlertMode.SILENT) return
        if (s.alertMode == AlertMode.SOUND || s.alertMode == AlertMode.BOTH) playSound(kind, s)
        if (s.alertMode == AlertMode.VIBRATION || s.alertMode == AlertMode.BOTH) vibrate(kind, s.vibe)
    }

    // ---------- vibration ----------
    @Suppress("DEPRECATION")
    private fun vibrate(kind: AlertKind, v: VibePattern) {
        if (!vibrator.hasVibrator()) return
        val one = when {
            kind == AlertKind.PING -> longArrayOf(0, 60)
            v == VibePattern.SINGLE -> longArrayOf(0, 450)
            v == VibePattern.DOUBLE -> longArrayOf(0, 200, 120, 200)
            else -> longArrayOf(0, 150, 100, 150, 100, 150)
        }
        val p = if (kind == AlertKind.END) one + longArrayOf(500) + one.copyOfRange(1, one.size) else one
        vibrator.vibrate(VibrationEffect.createWaveform(p, -1), alarmAttrs)
    }

    // ---------- sound ----------
    private fun playSound(kind: AlertKind, s: AppSettings) {
        if (s.sound == SoundPreset.CUSTOM && s.customUri != null && kind != AlertKind.PING) {
            val r = RingtoneManager.getRingtone(ctx, Uri.parse(s.customUri)) ?: return
            r.audioAttributes = alarmAttrs
            if (Build.VERSION.SDK_INT >= 28) r.volume = s.volume.coerceIn(0f, 1f)
            focus(if (kind == AlertKind.END) 5000L else 3000L)
            r.play()
            handler.postDelayed({ r.stop() }, if (kind == AlertKind.END) 5000 else 3000)
            return
        }
        val pcm = pcm(kind, s.sound)
        val track = AudioTrack.Builder()
            .setAudioAttributes(alarmAttrs)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        track.write(pcm, 0, pcm.size)
        track.setVolume(s.volume.coerceIn(0f, 1f))   // app-level gain, independent of media volume
        focus(pcm.size * 1000L / SR + 250)
        track.play()
        handler.postDelayed({ track.release() }, pcm.size * 1000L / SR + 250)
    }

    private fun pcm(kind: AlertKind, preset: SoundPreset): ShortArray = cache.getOrPut("$kind$preset") {
        if (kind == AlertKind.PING) return@getOrPut tone(1500.0, 70, 0.30)   // subtle, short
        val one = when (preset) {
            SoundPreset.DOUBLE_BEEP -> tone(2400.0, 110, 0.9) + silence(80) + tone(2400.0, 110, 0.9)
            SoundPreset.SHARP_CHIME -> mix(tone(1760.0, 650, 0.55, true), tone(2637.0, 650, 0.45, true))
            else -> tone(3200.0, 50, 0.9) + silence(70) + tone(3200.0, 50, 0.9) + silence(70) + tone(3200.0, 50, 0.9)
        }
        if (kind == AlertKind.END) one + silence(350) + one else one
    }

    private fun tone(f: Double, ms: Int, vol: Double, decay: Boolean = false): ShortArray {
        val n = SR * ms / 1000
        return ShortArray(n) { i ->
            val env = if (decay) 1.0 - i.toDouble() / n
                      else (minOf(i, n - i) / (SR * 0.005)).coerceAtMost(1.0)   // 5 ms ramps avoid clicks
            (sin(2 * PI * f * i / SR) * env * vol * Short.MAX_VALUE).toInt().toShort()
        }
    }
    private fun silence(ms: Int) = ShortArray(SR * ms / 1000)
    private fun mix(a: ShortArray, b: ShortArray) = ShortArray(a.size) { (a[it] + b[it]).toShort() }

    private companion object { const val SR = 44100 }
}
