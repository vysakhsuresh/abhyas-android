package com.layerbit.abhyas.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.layerbit.abhyas.R

/**
 * Deep indigo with warm saffron - lamplight, and studying late.
 *
 * LayerLink is cool blue-black because it is a tool; Deja is warm charcoal because it is a
 * memory. Abhyas keeps the family's dark base but takes the accent warm, because the audience is
 * students rather than developers and the app should not feel like an IDE.
 */
object AbhyasColors {
    val Background = Color(0xFF17122B)
    val Surface = Color(0xFF211B3A)
    val SurfaceDim = Color(0xFF1C1633)
    val Border = Color(0xFF2E2750)
    val BorderStrong = Color(0xFF3A3161)
    val Text = Color(0xFFF3F0FA)
    val Muted = Color(0xFFA79FC4)
    val Dim = Color(0xFF6E6690)
    val Saffron = Color(0xFFF5A524)
    val SaffronBright = Color(0xFFFFD166)
    val SaffronDim = Color(0xFF3D2E10)
    val OnSaffron = Color(0xFF1A1206)

    /**
     * The four review answers, and the only place these colours are defined. A grade means the
     * same thing on the study screen, in the deck list and in the stats, so it has to look the
     * same in all three.
     */
    val Again = Color(0xFFE0705F)
    val Hard = Color(0xFFE8A33D)
    val Good = Color(0xFF58C08C)
    val Easy = Color(0xFF5BA9E8)
}

/** Space Grotesk, the same face LayerLink, Deja and layerbit.co.in use. */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold)
)

private val AbhyasTypography = Typography(
    displayLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.8).sp),
    displayMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.4).sp),
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 13.5.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 12.5.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

private val AbhyasColorScheme = darkColorScheme(
    primary = AbhyasColors.Saffron,
    onPrimary = AbhyasColors.OnSaffron,
    background = AbhyasColors.Background,
    onBackground = AbhyasColors.Text,
    surface = AbhyasColors.Surface,
    onSurface = AbhyasColors.Text,
    outline = AbhyasColors.Border
)

@Composable
fun AbhyasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AbhyasColorScheme,
        typography = AbhyasTypography
    ) {
        // Most screens style text inline, which merges with whatever LocalTextStyle carries -
        // setting the family here is what makes Space Grotesk the default everywhere rather than
        // something each call site has to remember.
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(
                fontFamily = SpaceGrotesk,
                color = AbhyasColors.Text
            ),
            content = content
        )
    }
}
