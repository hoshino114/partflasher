package com.partflasher.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * PartFlasher palette.
 *
 * Seed intent: electric blue primary (tools / system), deep teal secondary
 * (stability), warm amber tertiary (caution) and the Material 3 error red.
 * Every color used by screens must come from MaterialTheme or [StatusColors];
 * this file is the only place raw color literals are allowed.
 */

// region Light scheme
private val LightPrimary = Color(0xFF0B57D0)
private val LightOnPrimary = Color(0xFFFFFFFF)
private val LightPrimaryContainer = Color(0xFFD7E3FF)
private val LightOnPrimaryContainer = Color(0xFF001B3F)

private val LightSecondary = Color(0xFF00696D)
private val LightOnSecondary = Color(0xFFFFFFFF)
private val LightSecondaryContainer = Color(0xFF9CF0F5)
private val LightOnSecondaryContainer = Color(0xFF002021)

private val LightTertiary = Color(0xFF8B5000)
private val LightOnTertiary = Color(0xFFFFFFFF)
private val LightTertiaryContainer = Color(0xFFFFDCBE)
private val LightOnTertiaryContainer = Color(0xFF2D1600)

private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color(0xFFFFFFFF)
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightOnErrorContainer = Color(0xFF410002)

private val LightBackground = Color(0xFFF8F9FF)
private val LightOnBackground = Color(0xFF191C20)
private val LightSurface = Color(0xFFF8F9FF)
private val LightOnSurface = Color(0xFF191C20)
private val LightSurfaceVariant = Color(0xFFE0E2EC)
private val LightOnSurfaceVariant = Color(0xFF43474E)
private val LightOutline = Color(0xFF73777F)
private val LightOutlineVariant = Color(0xFFC3C7CF)

private val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
private val LightSurfaceContainerLow = Color(0xFFF2F3FA)
private val LightSurfaceContainer = Color(0xFFECEEF4)
private val LightSurfaceContainerHigh = Color(0xFFE6E8EE)
private val LightSurfaceContainerHighest = Color(0xFFE1E2E8)
private val LightSurfaceDim = Color(0xFFD8DAE0)
private val LightSurfaceBright = Color(0xFFF8F9FF)
private val LightInverseSurface = Color(0xFF2E3036)
private val LightInverseOnSurface = Color(0xFFF0F0F7)
private val LightInversePrimary = Color(0xFFADC6FF)

val PartFlasherLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceTint = LightPrimary,
    inverseSurface = LightInverseSurface,
    inverseOnSurface = LightInverseOnSurface,
    inversePrimary = LightInversePrimary,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = LightSurfaceBright,
    surfaceDim = LightSurfaceDim,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainerLowest = LightSurfaceContainerLowest,
)
// endregion

// region Dark scheme
private val DarkPrimary = Color(0xFFADC6FF)
private val DarkOnPrimary = Color(0xFF002E69)
private val DarkPrimaryContainer = Color(0xFF004494)
private val DarkOnPrimaryContainer = Color(0xFFD7E3FF)

private val DarkSecondary = Color(0xFF80D5DA)
private val DarkOnSecondary = Color(0xFF003739)
private val DarkSecondaryContainer = Color(0xFF004F51)
private val DarkOnSecondaryContainer = Color(0xFF9CF0F5)

private val DarkTertiary = Color(0xFFFFB95C)
private val DarkOnTertiary = Color(0xFF4A2800)
private val DarkTertiaryContainer = Color(0xFF693C00)
private val DarkOnTertiaryContainer = Color(0xFFFFDCBE)

private val DarkError = Color(0xFFFFB4AB)
private val DarkOnError = Color(0xFF690005)
private val DarkErrorContainer = Color(0xFF93000A)
private val DarkOnErrorContainer = Color(0xFFFFDAD6)

private val DarkBackground = Color(0xFF111318)
private val DarkOnBackground = Color(0xFFE2E2E9)
private val DarkSurface = Color(0xFF111318)
private val DarkOnSurface = Color(0xFFE2E2E9)
private val DarkSurfaceVariant = Color(0xFF43474E)
private val DarkOnSurfaceVariant = Color(0xFFC3C7CF)
private val DarkOutline = Color(0xFF8D9199)
private val DarkOutlineVariant = Color(0xFF43474E)

private val DarkSurfaceContainerLowest = Color(0xFF0C0E13)
private val DarkSurfaceContainerLow = Color(0xFF191C20)
private val DarkSurfaceContainer = Color(0xFF1D2024)
private val DarkSurfaceContainerHigh = Color(0xFF282A2F)
private val DarkSurfaceContainerHighest = Color(0xFF33353A)
private val DarkSurfaceDim = Color(0xFF111318)
private val DarkSurfaceBright = Color(0xFF383A3F)
private val DarkInverseSurface = Color(0xFFE2E2E9)
private val DarkInverseOnSurface = Color(0xFF2E3036)
private val DarkInversePrimary = Color(0xFF0B57D0)

val PartFlasherDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceTint = DarkPrimary,
    inverseSurface = DarkInverseSurface,
    inverseOnSurface = DarkInverseOnSurface,
    inversePrimary = DarkInversePrimary,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    scrim = Color(0xFF000000),
    surfaceBright = DarkSurfaceBright,
    surfaceDim = DarkSurfaceDim,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
)
// endregion

/**
 * Semantic colors that Material 3 does not provide out of the box.
 *
 * Screens must never hardcode success/warning colors; they read tokens from
 * `MaterialTheme.statusColors` instead.
 */
@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

val LightStatusColors = StatusColors(
    success = Color(0xFF1B6B3A),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFA8F5C1),
    onSuccessContainer = Color(0xFF00210E),
    warning = Color(0xFF7A5900),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDF9E),
    onWarningContainer = Color(0xFF261A00),
)

val DarkStatusColors = StatusColors(
    success = Color(0xFF8FD9A3),
    onSuccess = Color(0xFF003919),
    successContainer = Color(0xFF00522A),
    onSuccessContainer = Color(0xFFA8F5C1),
    warning = Color(0xFFF2C15E),
    onWarning = Color(0xFF412D00),
    warningContainer = Color(0xFF5C4200),
    onWarningContainer = Color(0xFFFFDF9E),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }
