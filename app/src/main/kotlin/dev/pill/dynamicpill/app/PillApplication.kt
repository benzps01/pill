package dev.pill.dynamicpill.app

import android.app.Application
import android.content.ComponentName
import dev.pill.dynamicpill.core.event.EventProvider
import dev.pill.dynamicpill.core.event.ProviderRegistry
import dev.pill.dynamicpill.data.NotificationAccess
import dev.pill.dynamicpill.data.PillNotificationListenerService
import dev.pill.dynamicpill.providers.spotify.SpotifyProvider

/**
 * The composition root — the one place that names concrete provider classes
 * and knows what they need to be built. Per the module table `app` is
 * "wiring/DI only, may depend on all"; everything else in the app talks to
 * `EventProvider` and never to a provider by name.
 *
 * This exists as an Application because [PillAccessibilityService] is
 * instantiated by the system, so it can't be handed its dependencies through
 * a constructor. It reads them back through [ProviderRegistry], a `core`
 * interface, which is what lets `overlay` depend on `core` alone.
 */
class PillApplication : Application(), ProviderRegistry {

    /**
     * Built lazily rather than in `onCreate` because Notification Access can
     * be granted after launch — deciding at process start would mean the
     * engine stayed empty until the next restart. The accessibility service
     * reads this when it connects, which is after the user returns from the
     * settings screen.
     */
    override val providers: List<EventProvider>
        get() = buildProviders()

    private var cached: List<EventProvider>? = null

    private fun buildProviders(): List<EventProvider> {
        cached?.let { return it }
        // Rule 11 — never assume the grant is there. Without it
        // MediaSessionManager.getActiveSessions has no authorised component
        // to check against, so there is nothing a provider could observe.
        if (!NotificationAccess.isGranted(this)) return emptyList()

        val notificationListenerComponent =
            ComponentName(this, PillNotificationListenerService::class.java)
        return listOf(SpotifyProvider(this, notificationListenerComponent))
            .also { cached = it }
    }
}
