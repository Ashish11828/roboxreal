package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val IndustrialXrColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF002029),
    primaryContainer = DeepCyanContainer,
    onPrimaryContainer = ElectricCyan,
    secondary = SafetyEmerald,
    onSecondary = Color(0xFF002114),
    secondaryContainer = SafetyEmeraldContainer,
    onSecondaryContainer = SafetyEmerald,
    tertiary = HitlAmber,
    onTertiary = Color(0xFF2B1700),
    tertiaryContainer = HitlAmberContainer,
    onTertiaryContainer = HitlAmber,
    error = InterlockCrimson,
    onError = Color(0xFFFFFFFF),
    errorContainer = InterlockCrimsonContainer,
    onErrorContainer = Color(0xFFFFB4BE),
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = CarbonSurface,
    onSurface = TextPrimary,
    surfaceVariant = CarbonSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = HudOutlineSubtle,
    outlineVariant = HudBorderCyan
)

// Pure black background makes XREAL Birdbath OLED lenses optically see-through over real CNC cells
private val XrealOpticalColorScheme = IndustrialXrColorScheme.copy(
    background = OpticalPureBlack,
    surface = Color(0xFF050A12),
    surfaceVariant = Color(0xFF0A1424),
    outline = ElectricCyanDim,
    outlineVariant = ElectricCyan
)

val RoboXrShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun RoboXrTheme(
    opticalSeeThroughMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (opticalSeeThroughMode) {
        XrealOpticalColorScheme
    } else {
        IndustrialXrColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = RoboXrShapes,
        content = content
    )
}
