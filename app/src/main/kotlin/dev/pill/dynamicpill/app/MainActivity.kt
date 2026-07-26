package dev.pill.dynamicpill.app

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import dev.pill.dynamicpill.overlay.OverlayService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        hasOverlayPermission = { hasOverlayPermission() },
                        onRequestPermission = { requestOverlayPermission() },
                        onStart = { startService(Intent(this, OverlayService::class.java)) },
                        onStop = { stopService(Intent(this, OverlayService::class.java)) }
                    )
                }
            }
        }
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }
}

@Composable
private fun MainScreen(
    hasOverlayPermission: () -> Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    var granted by remember { mutableStateOf(hasOverlayPermission()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasOverlayPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(if (granted) "Overlay permission granted" else "Overlay permission needed")

        if (!granted) {
            Button(onClick = {
                onRequestPermission()
                granted = hasOverlayPermission()
            }) {
                Text("Grant overlay permission")
            }
        } else {
            Button(onClick = onStart) { Text("Start pill") }
            Button(onClick = onStop) { Text("Stop pill") }
        }
    }
}
