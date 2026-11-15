package com.nwbort.gview.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nwbort.gview.data.Sport

/**
 * Light and airy: warm paper, white cards, quiet ink, and one clear colour
 * per metric so a number and its chart always match.
 */
object G {
    val paper = Color(0xFFF7F6F2)
    val card = Color(0xFFFFFFFF)
    val line = Color(0xFFECEAE4)
    val wash = Color(0xFFF1EFEA)
    val ink = Color(0xFF1A1A18)
    val ink2 = Color(0xFF5E5D57)
    val ink3 = Color(0xFF9C9A92)

    val sleep = Color(0xFF6250D6)
    val hrv = Color(0xFF1BAF7A)
    val battery = Color(0xFF2A78D6)
    val heart = Color(0xFFE34948)
    val steps = Color(0xFFEB6834)
    val stress = Color(0xFFEDA100)
    val weight = Color(0xFFE87BA4)
    val fitness = Color(0xFF008300)

    // Sleep stages, deepest darkest.
    val deep = Color(0xFF3E2FA0)
    val light = Color(0xFFA79EF0)
    val rem = Color(0xFFE87BA4)
    val awake = Color(0xFFE4E1D9)

    fun sport(s: Sport): Color = when (s) {
        Sport.RUN -> Color(0xFFEB6834)
        Sport.RIDE -> Color(0xFF2A78D6)
        Sport.SWIM -> Color(0xFF1BAF7A)
        Sport.WALK -> Color(0xFF008300)
        Sport.STRENGTH -> Color(0xFF6250D6)
        Sport.OTHER -> Color(0xFF8A877E)
    }
}

/** Big, light numbers with tabular figures so columns of values line up. */
val Numbers = TextStyle(fontWeight = FontWeight.Light, fontFeatureSettings = "tnum", letterSpacing = (-0.5).sp)

private val base = Typography()

private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Light, fontSize = 52.sp, letterSpacing = (-1.5).sp, fontFeatureSettings = "tnum"),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Light, fontSize = 40.sp, letterSpacing = (-1).sp, fontFeatureSettings = "tnum"),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Light, fontSize = 30.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum"),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Normal),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp),
)

private val scheme = lightColorScheme(
    primary = G.ink,
    onPrimary = Color.White,
    primaryContainer = G.wash,
    onPrimaryContainer = G.ink,
    secondary = G.ink2,
    secondaryContainer = G.line,
    onSecondaryContainer = G.ink,
    background = G.paper,
    onBackground = G.ink,
    surface = G.paper,
    onSurface = G.ink,
    surfaceVariant = G.wash,
    onSurfaceVariant = G.ink2,
    surfaceContainerLowest = G.card,
    surfaceContainerLow = G.card,
    surfaceContainer = G.paper,
    surfaceContainerHigh = G.card,
    surfaceContainerHighest = G.wash,
    outline = G.ink3,
    outlineVariant = G.line,
    error = Color(0xFFC23B3A),
)

@Composable
fun GviewTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
