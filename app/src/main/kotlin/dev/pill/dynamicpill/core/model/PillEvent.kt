package dev.pill.dynamicpill.core.model

/**
 * A single candidate thing the pill could be showing. Providers (calls,
 * messages, Spotify — Phase 4) each expose at most one of these at a time;
 * the Arbiter picks the highest-priority one across all providers.
 */
data class PillEvent(
    val providerId: String,
    val type: EventType,
    val priority: Int,
    val title: String,
    val subtitle: String? = null,
)

enum class EventType {
    CALL, MESSAGE, MEDIA
}
