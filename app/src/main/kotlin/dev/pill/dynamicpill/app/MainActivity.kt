package dev.pill.dynamicpill.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.pill.dynamicpill.data.NotificationAccess
import dev.pill.dynamicpill.overlay.PillAccessibilityService
import dev.pill.dynamicpill.settings.MainScreen

/**
 * Wiring only: hosts `settings`' UI and supplies it the concrete permission
 * checks it isn't allowed to reach for itself (`settings` may depend on
 * `core` alone, while `app` may depend on everything).
 */
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
