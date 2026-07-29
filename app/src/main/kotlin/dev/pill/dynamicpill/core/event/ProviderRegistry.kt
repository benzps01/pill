package dev.pill.dynamicpill.core.event

/**
 * The set of providers the app is configured to run.
 *
 * Exists to give system-instantiated components (the AccessibilityService)
 * a way to receive their dependencies. They can't be constructed by us, so
 * they can't be given a provider list directly; instead the `app` layer's
 * Application implements this, and the service reads the list back through
 * this `core` interface. That keeps `overlay` depending on `core` alone
 * while composition — which providers exist, and whether prerequisites like
 * Notification Access are satisfied — stays in `app` where the module table
 * puts it.
 */
interface ProviderRegistry {
    val providers: List<EventProvider>
}
