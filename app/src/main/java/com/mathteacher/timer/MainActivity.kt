package com.mathteacher.timer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mathteacher.timer.model.Mode
import com.mathteacher.timer.ui.*

class MainActivity : ComponentActivity() {
    private var notifOk by mutableStateOf(true)
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshNotif() }

    private fun refreshNotif() { notifOk = NotificationManagerCompat.from(this).areNotificationsEnabled() }
    private fun openNotifSettings() = startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))

    override fun onResume() { super.onResume(); refreshNotif() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            MathTimerTheme {
                val vm: MainViewModel = viewModel()
                val timer by vm.timer.collectAsStateWithLifecycle()
                val settings by vm.settings.collectAsStateWithLifecycle()

                LaunchedEffect(timer.mode) {
                    if (timer.mode != Mode.IDLE) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                BackHandler(enabled = timer.mode == Mode.IDLE && vm.screen != Screen.HOME) { vm.screen = Screen.HOME }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        when {
                            timer.mode == Mode.CLASS -> ClassScreen(timer, vm::prev, vm::next, vm::undo,
                                { vm.togglePause(timer.paused) }, vm::stop)
                            timer.mode == Mode.EXAM -> ExamRunningScreen(timer, vm::adjust,
                                { vm.togglePause(timer.paused) }, vm::stop)
                            vm.screen == Screen.SETTINGS -> SettingsScreen(vm, settings) { vm.screen = Screen.HOME }
                            vm.screen == Screen.EXAM_SETUP -> ExamSetupScreen(vm::startExam) { vm.screen = Screen.HOME }
                            else -> HomeScreen(
                                onClass = vm::startClass,
                                onExam = { vm.screen = Screen.EXAM_SETUP },
                                onSettings = { vm.screen = Screen.SETTINGS },
                                notifOk = notifOk, onFixNotif = ::openNotifSettings)
                        }
                    }
                }
            }
        }
    }
}
