package app.nageune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import app.nageune.R

/** Pleos 기본 화면(밝은 바탕, 검은 글자)과 어울리는 색. 한곳에서만 바꾸도록 모아 둔다. */
object Palette {
    val bg = Color(0xFFF2F3F5)
    val surface = Color(0xFFFFFFFF)
    val surfaceSoft = Color(0xFFF7F8FA)
    val line = Color(0xFFE2E5E9)
    val text = Color(0xFF111418)
    val muted = Color(0xFF6A717B)
    val faint = Color(0xFFA3A9B1)
    val accent = Color(0xFF2563EB)
    val accentSoft = Color(0xFFE8EFFE)
    val ok = Color(0xFF15803D)
    val okSoft = Color(0xFFE6F4EA)
    val warn = Color(0xFFB45309)
    val warnSoft = Color(0xFFFDF1E3)
}

/**
 * Pleos Connect 시스템 UI 글꼴 Asta Sans(42dot, SIL OFL 1.1). 가변 글꼴 하나를 굵기별로 나눠 쓴다.
 * 라이선스 원문은 assets/licenses/AstaSans-OFL.txt.
 */
@OptIn(ExperimentalTextApi::class)
private fun asta(w: FontWeight) = Font(
    R.font.asta_sans,
    weight = w,
    variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)),
)

val AstaSans = FontFamily(
    asta(FontWeight.Light),
    asta(FontWeight.Normal),
    asta(FontWeight.Medium),
    asta(FontWeight.SemiBold),
    asta(FontWeight.Bold),
    asta(FontWeight.ExtraBold),
)

private fun TextStyle.asta() = copy(fontFamily = AstaSans)

private val AppTypography = Typography().run {
    Typography(
        displayLarge = displayLarge.asta(), displayMedium = displayMedium.asta(), displaySmall = displaySmall.asta(),
        headlineLarge = headlineLarge.asta(), headlineMedium = headlineMedium.asta(), headlineSmall = headlineSmall.asta(),
        titleLarge = titleLarge.asta(), titleMedium = titleMedium.asta(), titleSmall = titleSmall.asta(),
        bodyLarge = bodyLarge.asta(), bodyMedium = bodyMedium.asta(), bodySmall = bodySmall.asta(),
        labelLarge = labelLarge.asta(), labelMedium = labelMedium.asta(), labelSmall = labelSmall.asta(),
    )
}

@Composable
fun NageuneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.accent,
            onPrimary = Color.White,
            background = Palette.bg,
            surface = Palette.surface,
            onSurface = Palette.text,
            outline = Palette.line,
        ),
        typography = AppTypography,
        content = content,
    )
}
