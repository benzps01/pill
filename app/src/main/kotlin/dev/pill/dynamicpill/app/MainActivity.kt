package dev.pill.dynamicpill.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pill.dynamicpill.data.NotificationAccess
import dev.pill.dynamicpill.overlay.PillAccessibilityService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        isAccessibilityEnabled = { isAccessibilityServiceEnabled() },
                        onOpenAccessibilitySettings = { openAccessibilitySettings() },
                        isNotificationAccessEnabled = { NotificationAccess.isGranted(this) },
                        onOpenNotificationAccessSettings = { openNotificationAccessSettings() }
                    )
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${PillAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openNotificationAccessSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }
}

@Composable
private fun MainScreen(
    isAccessibilityEnabled: () -> Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    isNotificationAccessEnabled: () -> Boolean,
    onOpenNotificationAccessSettings: () -> Unit
) {
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityEnabled()) }
    var notificationAccessEnabled by remember { mutableStateOf(isNotificationAccessEnabled()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = isAccessibilityEnabled()
                notificationAccessEnabled = isNotificationAccessEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(
            if (accessibilityEnabled) {
                "Accessibility service enabled — pill is running"
            } else {
                "Accessibility service needed to draw the pill"
            }
        )
        if (!accessibilityEnabled) {
            Button(onClick = onOpenAccessibilitySettings) {
                Text("Open Accessibility settings")
            }
        }

        Text(
            if (notificationAccessEnabled) {
                "Notification access enabled"
            } else {
                "Notification access needed for calls/messages"
            }
        )
        if (!notificationAccessEnabled) {
            Button(onClick = onOpenNotificationAccessSettings) {
                Text("Open Notification access settings")
            }
        }
    }
}
