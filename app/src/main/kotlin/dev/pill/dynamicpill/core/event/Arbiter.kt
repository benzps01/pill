package dev.pill.dynamicpill.core.event

import dev.pill.dynamicpill.core.model.PillEvent

/**
 * Picks a single winner across every [EventProvider] whenever any of them
 * reports a change. Pure Kotlin, no Android deps — unit-testable in
 * isolation (CLAUDE.md `core` rule). Re-evaluates only on provider callback,
 * never on a timer (rule 1).
 */
class Arbiter(private val providers: List<EventProvider>) {

    private var onWinnerChanged: ((PillEvent?) -> Unit)? = null

    init {
        providers.forEach { provider -> provider.setListener { recompute() } }
    }

    fun setOnWinnerChanged(listener: (PillEvent?) -> Unit) {
        onWinnerChanged = listener
        listener(winner())
    }

    private fun recompute() {
        onWinnerChanged?.invoke(winner())
    }

    private fun winner(): PillEvent? =
        providers.mapNotNull { it.currentEvent }.maxByOrNull { it.priority }
}
