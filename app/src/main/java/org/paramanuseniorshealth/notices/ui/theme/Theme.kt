package org.paramanuseniorshealth.notices.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = NavyPrimary,
    onPrimary = NavyOnPrimary,
    primaryContainer = NavyContainer,
    onPrimaryContainer = NavyOnContainer,
    secondary = NavyPrimary,
    onSecondary = NavyOnPrimary,
    secondaryContainer = NavyContainer,
    onSecondaryContainer = NavyOnContainer,
    error = RedAccent,
    onError = RedOnAccent,
    errorContainer = RedContainer,
    onErrorContainer = RedOnContainer,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
)

private val DarkColors = darkColorScheme(
    primary = NavyPrimaryDark,
    onPrimary = NavyOnPrimaryDark,
    primaryContainer = NavyContainerDark,
    onPrimaryContainer = NavyOnContainerDark,
    secondary = NavyPrimaryDark,
    onSecondary = NavyOnPrimaryDark,
    secondaryContainer = NavyContainerDark,
    onSecondaryContainer = NavyOnContainerDark,
    error = RedAccentDark,
    onError = RedOnAccentDark,
    errorContainer = RedContainerDark,
    onErrorContainer = RedOnContainerDark,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
)

/**
 * Follows the device's light/dark setting, and nothing else.
 *
 * Dynamic colour is deliberately **off**. It derives the palette from the user's wallpaper, which
 * means neither the contrast nor the brand survives contact with a real phone -- a pale wallpaper
 * can produce a washed-out primary that is legible on a reviewer's desk and not legible to someone
 * in their nineties. A fixed, checked palette is the right trade for this audience.
 *
 * The [Surface] wrapper matters as much as the colours: it makes Compose paint the window
 * background itself, so the app can never again show one scheme's text on the other scheme's
 * background the way it did when only the platform theme decided.
 */
@Composable
fun ParamanuNoticesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
