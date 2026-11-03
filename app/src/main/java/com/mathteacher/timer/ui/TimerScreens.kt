package com.mathteacher.timer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathteacher.timer.model.TimerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

fun fmt(ms: Long, ceil: Boolean = true): String {
    val t = ms.coerceAtLeast(0)
    val s = if (ceil) (t + 999) / 1000 else t / 1000
    val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

/** Centered, scrollable (landscape/small screens), width-limited (tablets), with slow pixel-shift (OLED burn-in). */
@Composable
fun Screen(shift: Int = 0, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp).offset((shift * 2 - 4).dp, (shift * 3 - 6).dp), Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()),
            Arrangement.spacedBy(16.dp), Alignment.CenterHorizontally, content = content)
    }
}

@Composable
fun HoldButton(label: String, onStep: () -> Unit) {
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    Box(
        Modifier.size(76.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    step()
                    val j = scope.launch { delay(400); while (true) { step(); delay(70) } }
                    tryAwaitRelease(); j.cancel()
                })
            },
        contentAlignment = Alignment.Center
    ) { Text(label, fontSize = 34.sp, color = MaterialTheme.colorScheme.primary) }
}

@Composable
fun MinuteStepper(value: Int, onDelta: (Int) -> Unit) =
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            HoldButton("−") { onDelta(-1) }
            Text(value.toString(), fontSize = 56.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 96.dp))
            HoldButton("+") { onDelta(1) }
        }
    }

@Composable
private fun ConfirmButton(label: String, question: String, onConfirm: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton({ open = true }) { Text(label) }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(question) },
        confirmButton = { TextButton({ open = false; onConfirm() }) { Text("نعم، أنهِ") } },
        dismissButton = { TextButton({ open = false }) { Text("إلغاء") } })
}

@Composable
private fun Stat(label: String, value: String, color: Color = MaterialTheme.colorScheme.onBackground) =
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        Text(value, color = color, fontSize = 34.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
    }

/** Shrinks for h:mm:ss so it never overflows a small screen. */
@Composable
private fun BigClock(text: String, color: Color) =
    Text(text, color = color, maxLines = 1, softWrap = false, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        fontSize = when { text.length <= 5 -> 92.sp; text.length <= 7 -> 64.sp; else -> 52.sp })

@Composable
fun HomeScreen(onClass: (Int) -> Unit, onExam: () -> Unit, onSettings: () -> Unit,
               notifOk: Boolean, onFixNotif: () -> Unit) {
    var rem by rememberSaveable { mutableStateOf(50) }
    val cs = MaterialTheme.colorScheme
    Screen {
        Text("مؤقّت الرياضيات", style = MaterialTheme.typography.headlineLarge, color = cs.primary)
        if (!notifOk) {
            Button(onFixNotif, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = cs.error)) {
                Text("الإشعارات معطّلة — اضغط لتفعيلها")
            }
            Text("بدونها قد يوقف النظام المؤقت عند انطفاء الشاشة.", color = cs.onSurfaceVariant, fontSize = 13.sp)
        }
        Text("الدقائق المتبقية من الحصة", color = cs.onSurfaceVariant)
        MinuteStepper(rem) { d -> rem = (rem + d).coerceIn(5, 60) }
        Text(if (rem == 50) "الحصة كاملة (50 دقيقة)" else "تُضبط مدة كل مرحلة بنسبة ${rem * 2}%", color = cs.secondary)
        Button({ onClass(rem) }, Modifier.fillMaxWidth().height(96.dp)) {
            Text("بدء حصة جديدة", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        FilledTonalButton(onExam, Modifier.fillMaxWidth().height(72.dp)) { Text("الفروض والاختبارات", fontSize = 22.sp) }
        TextButton(onSettings) { Text("الإعدادات", fontSize = 18.sp) }
    }
}

@Composable
fun ClassScreen(s: TimerState, onPrev: () -> Unit, onNext: () -> Unit, onUndo: () -> Unit,
                onTogglePause: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val canPrev = !s.finished && s.phaseIndex > 0
    val canNext = !s.finished && s.phaseIndex < s.phaseCount - 1
    val clock = if (s.finished) cs.error else if (s.paused) cs.secondary else cs.onBackground
    Screen(shift = ((s.elapsedMs / 60_000) % 5).toInt()) {
        Text("المرحلة ${s.phaseIndex + 1} / ${s.phaseCount}", color = cs.onSurfaceVariant, fontSize = 16.sp)
        Text(s.title + if (s.paused) " (متوقف)" else "", color = cs.primary, fontSize = 30.sp,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        BigClock(if (s.finished) "+" + fmt(s.overtimeMs, false) else fmt(s.phaseRemainingMs), clock)
        if (!s.finished) Text("مدة المرحلة: " + fmt(s.phaseDurationMs), color = cs.onSurfaceVariant, fontSize = 16.sp)
        LinearProgressIndicator(s.phaseProgress, Modifier.fillMaxWidth().height(8.dp), color = cs.primary, trackColor = cs.surfaceVariant)
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            Stat("المنقضي", fmt(s.elapsedMs, false))
            Stat("المتبقي", fmt(s.remainingMs))
        }
        s.nextTitle?.let { Text("التالي: $it", color = cs.secondary, fontSize = 18.sp, textAlign = TextAlign.Center) }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(16.dp)) {
                FilledTonalButton(onPrev, Modifier.weight(1f).height(72.dp), enabled = canPrev) { Text("◄ السابق", fontSize = 22.sp) }
                FilledTonalButton(onNext, Modifier.weight(1f).height(72.dp), enabled = canNext) { Text("التالي ►", fontSize = 22.sp) }
            }
        }
        if (s.canUndo) TextButton(onUndo) { Text("تراجع عن الانتقال", color = cs.secondary) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onTogglePause) { Text(if (s.paused) "استئناف" else "إيقاف مؤقت") }
            ConfirmButton("إنهاء الحصة", "هل تريد إنهاء الحصة؟", onStop)
        }
    }
}

@Composable
fun ExamSetupScreen(onStart: (Int) -> Unit, onBack: () -> Unit) {
    var total by rememberSaveable { mutableStateOf(60) }
    val cs = MaterialTheme.colorScheme
    Screen {
        Text("مدة الاختبار", style = MaterialTheme.typography.headlineMedium)
        Text(String.format(Locale.US, "%d:%02d", total / 60, total % 60), fontSize = 72.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = cs.primary)
        Text("$total دقيقة  (ساعة:دقيقة)", color = cs.onSurfaceVariant)
        MinuteStepper(total) { d -> total = (total + d).coerceIn(1, 480) }
        Text("اضغط مطوّلًا على + أو − للتغيير السريع", color = cs.onSurfaceVariant, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 45, 60, 90, 120).forEach { m -> FilledTonalButton({ total = m }) { Text("$m") } }
        }
        Button({ onStart(total) }, Modifier.fillMaxWidth().height(80.dp)) { Text("ابدأ الاختبار", fontSize = 24.sp) }
        TextButton(onBack) { Text("رجوع") }
    }
}

@Composable
fun ExamRunningScreen(s: TimerState, onAdjust: (Int) -> Unit, onTogglePause: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val clock = if (s.finished) cs.error else if (s.paused) cs.secondary else cs.onBackground
    Screen(shift = ((s.elapsedMs / 60_000) % 5).toInt()) {
        Text(s.title + if (s.paused) " (متوقف)" else "", color = if (s.finished) cs.error else cs.primary,
            fontSize = 28.sp, fontWeight = FontWeight.Bold)
        BigClock(fmt(s.remainingMs), clock)
        LinearProgressIndicator(s.phaseProgress, Modifier.fillMaxWidth().height(8.dp), color = cs.primary, trackColor = cs.surfaceVariant)
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly) {
            Stat("الزمن المنقضي", fmt(s.elapsedMs, false))
            if (s.finished) Stat("وقت إضافي", "+" + fmt(s.overtimeMs, false), cs.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton({ onAdjust(-5) }) { Text("−5 د") }
            FilledTonalButton({ onAdjust(1) }) { Text("+1 د") }
            FilledTonalButton({ onAdjust(5) }) { Text("+5 د") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onTogglePause) { Text(if (s.paused) "استئناف" else "إيقاف مؤقت") }
            ConfirmButton("إنهاء الاختبار", "هل تريد إنهاء الاختبار؟", onStop)
        }
    }
}
