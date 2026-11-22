package com.example.core.common

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Rational time utilities to avoid floating-point accumulation errors across timeline edits.
 */
data class RationalTime(
    val numerator: Long,
    val denominator: Long,
) {
    init {
        require(denominator > 0L) { "Denominator must be positive" }
    }

    fun toMillis(): Long = (numerator * 1_000L) / denominator

    fun toFrames(fps: Int): Long = (numerator * fps) / denominator

    companion object {
        fun fromMillis(millis: Long): RationalTime = RationalTime(millis, 1_000L)

        fun fromFrames(frames: Long, fps: Int): RationalTime =
            RationalTime(frames, fps.coerceAtLeast(1).toLong())
    }
}

object TimeFormatting {
    fun formatTimecode(durationMs: Long, fps: Int = 30): String {
        val safeMs = durationMs.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        val safeFps = fps.coerceAtLeast(1)
        val frames = ((safeMs % 1_000L) * safeFps / 1_000L).coerceIn(0L, (safeFps - 1).toLong())
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d:%02d", hours, minutes, seconds, frames)
        } else {
            String.format(Locale.US, "%02d:%02d:%02d", minutes, seconds, frames)
        }
    }

    fun formatDurationLabel(durationMs: Long): String {
        val safeMs = durationMs.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val tenths = (safeMs % 1_000L) / 100L
        return if (minutes >= 60L) {
            val hours = minutes / 60L
            val remMinutes = minutes % 60L
            String.format(Locale.US, "%d:%02d:%02d", hours, remMinutes, seconds)
        } else if (minutes > 0L) {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        } else {
            String.format(Locale.US, "%d.%ds", seconds, tenths)
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%d KB", kb.roundToLong().coerceAtLeast(1L))
        }
    }
}
