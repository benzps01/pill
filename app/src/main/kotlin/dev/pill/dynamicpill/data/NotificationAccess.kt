package dev.pill.dynamicpill.data

import android.content.Context
import androidx.core.app.NotificationManagerCompat

object NotificationAccess {
    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}
