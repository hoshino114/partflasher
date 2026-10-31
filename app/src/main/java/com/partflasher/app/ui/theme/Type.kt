package com.partflasher.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography tuned for a technical tool: slightly larger body text for
 * readability in bright outdoor light, semibold titles for scanability and a
 * monospace face for partition paths, hashes and logs.
 */
val PartFlasherTypography: Typography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
        ),
        titleLarge = base.titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 21.sp,
            lineHeight = 27.sp,
        ),
        titleMedium = base.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        titleSmall = base.titleSmall.copy(
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        bodyLarge = base.bodyLarge.copy(
            fontSize = 16.sp,
            lineHeight = 24.sp,
        ),
        bodyMedium = base.bodyMedium.copy(
            fontSize = 14.sp,
            lineHeight = 21.sp,
        ),
        bodySmall = base.bodySmall.copy(
            fontSize = 12.sp,
            lineHeight = 17.sp,
        ),
        labelLarge = base.labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        labelMedium = base.labelMedium.copy(
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        labelSmall = base.labelSmall.copy(
            fontSize = 11.sp,
            lineHeight = 16.sp,
        ),
    )
}

/** Monospace body text for paths, commands and log messages. */
val Typography.monoBody: TextStyle
    get() = bodySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)

/** Small monospace text for hashes and byte counters. */
val Typography.monoSmall: TextStyle
    get() = labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)

/** Monospace text at body size, used for primary technical values. */
val Typography.monoMedium: TextStyle
    get() = bodyMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.sp)
