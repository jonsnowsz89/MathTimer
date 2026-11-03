package com.mathteacher.timer.ui

import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.mathteacher.timer.model.*

@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        options.forEach { (v, label) ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(v) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(v == selected, { onSelect(v) }); Text(label)
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: MainViewModel, s: AppSettings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            ?.let { vm.setCustomSound(it.toString()) }
    }
    val ignoring = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    var vol by remember(s.volume) { mutableFloatStateOf(s.volume) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), Arrangement.spacedBy(20.dp)) {
        Text("الإعدادات", style = MaterialTheme.typography.headlineMedium)

        Choice("نوع التنبيه", listOf(AlertMode.SOUND to "صوت فقط", AlertMode.VIBRATION to "اهتزاز فقط",
            AlertMode.BOTH to "صوت واهتزاز", AlertMode.SILENT to "صامت"), s.alertMode, vm::setAlertMode)

        Column {
            Text("مستوى صوت التنبيه", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Slider(value = vol, onValueChange = { vol = it },
                onValueChangeFinished = { vm.setVolume(vol); vm.test(AlertKind.PHASE, vol) })
            Text("${(vol * 100).toInt()}%  — مستقل عن صوت الوسائط (يتبع مستوى صوت المنبّه في النظام)",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({
                ctx.getSystemService(AudioManager::class.java)
                    .adjustStreamVolume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            }) { Text("ضبط صوت المنبّه في النظام") }
        }

        Choice("نغمة التنبيه", listOf(SoundPreset.DOUBLE_BEEP to "بيب مزدوج حاد", SoundPreset.SHARP_CHIME to "رنين حاد",
            SoundPreset.TRIPLE_TICK to "ثلاث نقرات سريعة", SoundPreset.CUSTOM to "نغمة منبّه مخصصة"), s.sound, vm::setSound)
        if (s.sound == SoundPreset.CUSTOM)
            OutlinedButton({
                picker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    .apply { s.customUri?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(it)) } })
            }) { Text(if (s.customUri == null) "اختيار نغمة" else "تغيير النغمة") }

        Choice("نمط الاهتزاز", listOf(VibePattern.SINGLE to "نبضة واحدة", VibePattern.DOUBLE to "نبضتان",
            VibePattern.TRIPLE to "ثلاث نبضات"), s.vibe, vm::setVibe)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ vm.test(AlertKind.PHASE) }) { Text("تجربة تنبيه المرحلة") }
            FilledTonalButton({ vm.test(AlertKind.PING) }) { Text("تجربة نبضة 10د") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(s.reserveApp, vm::setReserve)
            Spacer(Modifier.width(12.dp))
            Text("حجز ~10% من الحصة للتمرين التطبيقي (يقلّص باقي المراحل بدل الصيغة الدقيقة)")
        }

        if (!ignoring) OutlinedButton({
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }) { Text("استثناء التطبيق من توفير البطارية") }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("لضمان التنبيه مع انطفاء الشاشة (سامسونج One UI)", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary)
                Text("1) اسمح للتطبيق بالإشعارات.\n" +
                    "2) الإعدادات ← العناية بالجهاز ← البطارية ← حدود استخدام الخلفية: عطّل «وضع نوم التطبيقات غير المستخدمة» وأضف التطبيق إلى «التطبيقات التي لا تنام أبدًا».\n" +
                    "3) من «إعدادات التطبيق» ← البطارية ← اختر «غير مقيّد».\n" +
                    "4) لا تُغلق التطبيق من «إغلاق الكل» أثناء الحصة إن أمكن؛ حتى لو أُغلق سيُعيد منبّه النظام تشغيل المؤقت.")
                OutlinedButton({
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }) { Text("فتح إعدادات التطبيق") }
            }
        }

        Button(onBack, Modifier.fillMaxWidth()) { Text("رجوع") }
    }
}
