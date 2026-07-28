package dev.pill.dynamicpill.data

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Raw notification stream (CLAUDE.md `data` layer — no parsing/semantics
 * here). Push-based by definition: the system calls these methods as
 * notifications post/clear, never polled. Phase 4 providers (messages,
 * CallStyle) subscribe via [addListener] and decide what's relevant.
 */
class PillNotificationListenerService : NotificationListenerService() {

    fun interface Listener {
        fun onNotificationsChanged(active: List<StatusBarNotification>)
    }

    companion object {
        private val listeners = mutableListOf<Listener>()
        @Volatile var isConnected: Boolean = false
            private set

        fun addListener(listener: Listener) {
            listeners += listener
        }

        fun removeListener(listener: Listener) {
            listeners -= listener
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        notifyListeners()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        notifyListeners()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        notifyListeners()
    }

    private fun notifyListeners() {
        val active = activeNotifications?.toList() ?: emptyList()
        listeners.forEach { it.onNotificationsChanged(active) }
    }
}
