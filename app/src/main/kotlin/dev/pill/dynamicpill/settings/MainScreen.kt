package dev.pill.dynamicpill.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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

/**
 * Onboarding / permission status screen. Phase 5 will grow the rest of the
 * settings UI around this package.
 *
 * Takes its state as lambdas rather than reaching for `NotificationAccess` or
 * the accessibility service directly — per the module table `settings` may
 * depend on `core` only, so anything that needs `data` or `overlay` is
 * supplied by the `app` layer that hosts this (see MainActivity).
 */
@Composable
fun MainScreen(
    isAccessibilityEnabled: () -> Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    isNotificationAccessEnabled: () -> Boolean,
    onOpenNotificationAccessSettings: () -> Unit
) {
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityEnabled()) }
    var notificationAccessEnabled by remember { mutableStateOf(isNotificationAccessEnabled()) }

    // Both permissions are granted in system settings, so the only reliable
    // moment to re-check is when the user comes back to us (rule 11 — a
    // revoked grant must surface, never fail silently).
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
