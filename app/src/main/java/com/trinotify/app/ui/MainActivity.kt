package com.trinotify.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.trinotify.app.data.Prefs

class MainActivity : FragmentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val authed = mutableStateOf(false)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // При включённом замке прячем содержимое из «Недавних» и от скриншотов —
        // иначе тексты архива видны в превью без разблокировки.
        if (Prefs.get(this).appLock) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (dark) darkColorScheme(primary = Color(0xFF80CBC4))
            else lightColorScheme(primary = Color(0xFF00695C))
            MaterialTheme(colorScheme = scheme) {
                if (Prefs.get(this).appLock && !authed.value) {
                    LockScreen(onUnlock = { showPrompt() })
                } else {
                    MainScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val locked = Prefs.get(this).appLock
        if (locked) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Повторный замок только после минуты вне приложения, чтобы системные
        // пикеры (мелодии, файлы) не требовали биометрию на каждый возврат.
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 60_000) {
            authed.value = false
        }
        if (locked && !authed.value) showPrompt()
    }

    override fun onStop() {
        stoppedAt = System.currentTimeMillis()
        super.onStop()
    }

    private fun showPrompt() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authed.value = true
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Trinotify")
            .setSubtitle("Подтвердите личность")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }
}

@androidx.compose.runtime.Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Lock, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Text("Trinotify заблокирован", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onUnlock, modifier = Modifier.padding(top = 16.dp)) {
            Text("Разблокировать")
        }
    }
}

private data class Tab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@androidx.compose.runtime.Composable
fun MainScreen() {
    val tabs = listOf(
        Tab("Архив", Icons.Filled.Archive),
        Tab("Приложения", Icons.Filled.Apps),
        Tab("Правила", Icons.Filled.Rule),
        Tab("Настройки", Icons.Filled.Settings),
    )
    var selected by rememberSaveable { mutableIntStateOf(3) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                    )
                }
            }
        }
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (selected) {
            0 -> ArchiveScreen(modifier)
            1 -> AppsScreen(modifier)
            2 -> RulesScreen(modifier)
            else -> SettingsScreen(modifier)
        }
    }
}
