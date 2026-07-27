package dev.pill.dynamicpill.core.device

/**
 * Fallback cutout geometry, used only when the live DisplayCutout API reports
 * nothing (CLAUDE.md rule 7 — no concrete device math inline in `overlay`).
 * Pure Kotlin, no Android deps.
 */
interface DeviceProfile {
    val fallbackTopOffsetDp: Float
    val fallbackCenterXFraction: Float
}

object Pixel8ProProfile : DeviceProfile {
    override val fallbackTopOffsetDp = 12f
    override val fallbackCenterXFraction = 0.5f
}
