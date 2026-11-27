package com.ali.menbaradkshk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ali.menbaradkshk.R

// هوية «محراب الفجر» (اختيار المالك 2026-11-27): المنبر في قلب المسجد، والدرس صوتٌ
// يتردّد تحت القبّة. نهارٌ من حجرٍ جيريّ فاتح وزمرّد ونحاس، وليلٌ زمرّديّ عميق
// بنعناعيّ ونحاسيّ — لا أسود خالص في أيّ وضع.
// ⚠️ أسماء الرموز القديمة (Ink/Gold/Gem/Teal…) باقية لأنّ بقيّة الملفات تستوردها؛
// تغيّرت قيمها فقط لتنتمي إلى الهوية الجديدة.

// ---- الليل الزمرّديّ (الخلفيات والأسطح الداكنة) ----
val Ink900 = Color(0xFF06110E)
val Ink800 = Color(0xFF0B1A16) // ★ خلفية الليل
val Ink700 = Color(0xFF0F211C)
val Ink600 = Color(0xFF12261F)
val Ink500 = Color(0xFF163129)

// ---- النحاس ----
// ⚠️ Gold100–Gold700 للوضع الداكن والرسوم الكبيرة؛ Gold800/Gold900 وحدهما نصّاً على الحجر الفاتح.
val Gold100 = Color(0xFFF6D6B4)
val Gold200 = Color(0xFFEDBB8A)
val Gold300 = Color(0xFFE3A267)
val Gold400 = Color(0xFFD18B4E)
val Gold500 = Color(0xFFC27A3C)
val Gold600 = Color(0xFFB5652B)
val Gold700 = Color(0xFF9E5622)
val Gold800 = Color(0xFF8A4A1C)
val Gold900 = Color(0xFF7A3F17)

// ---- الزمرّد والنعناع ----
// Gem100 (نعناعيّ) نصّاً على الليل؛ Gem500 (زمرّد) نصّاً على الحجر الفاتح.
val Gem100 = Color(0xFF7FD1B4)
val Gem200 = Color(0xFF5FBF9E)
val Gem300 = Color(0xFF2E8C6E)
val Gem400 = Color(0xFF157055)
val Gem500 = Color(0xFF0F5A46)

val Teal = Gem500
val Slate = Color(0xFF0B3B2F)
val Gold = Gold900
val BlueBrand = Color(0xFF1F4E5A)
val GreenBrand = Color(0xFF1B7A5E)
val OrangeBrand = Color(0xFF8A4A1C)
val SecondaryGold = Gold400
val PositiveOnDark = Color(0xFF7FD1B4)
val GoldOnDark = Gold200

// الحجر والحبر: ورقٌ فاتح للقراءة نهاراً، وزمرّد عميق للمشغّل ليلاً.
internal val ArchivePaper = Color(0xFFF6F1E7)   // حجر جيريّ
internal val ArchiveIvory = Color(0xFFFFFDF8)
internal val ArchiveLibraryInk = Color(0xFF1E2B26) // نصّ النهار
internal val ArchivePlayerInk = Color(0xFF0B1A16)
internal val ArchiveBronze = Color(0xFF8A4A1C)
internal val ArchiveAmber = Color(0xFFE3A267)
internal val DawnMint = Color(0xFFDCEFE6)
internal val DawnSand = Color(0xFFF6E3CF)
internal val NightEmerald = Color(0xFF123A2F)

val DarkAppBar = ArchivePlayerInk
val DarkScaffold = ArchivePlayerInk
val DarkSurface = Color(0xFF12261F)
val LightScaffold = ArchivePaper
val LightSurface = ArchiveIvory

private val LightColors = lightColorScheme(
    primary = Gem500,
    onPrimary = Color.White,
    primaryContainer = DawnMint,
    onPrimaryContainer = Color(0xFF0F3B2F),
    secondary = Gold800,
    onSecondary = Color.White,
    secondaryContainer = DawnSand,
    onSecondaryContainer = Color(0xFF4A2A10),
    tertiary = Color(0xFF2E7D6B),
    onTertiary = Color.White,
    background = LightScaffold,
    onBackground = ArchiveLibraryInk,
    surface = LightSurface,
    onSurface = ArchiveLibraryInk,
    surfaceVariant = Color(0xFFEFE6D6),
    onSurfaceVariant = Color(0xFF55625B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAF5EC),
    surfaceContainer = Color(0xFFF2EADB),
    surfaceContainerHigh = Color(0xFFEBE1CF),
    surfaceContainerHighest = Color(0xFFE4D8C3),
    outline = Color(0xFF857B6C),
    outlineVariant = Color(0xFFE2D7C3),
    error = Color(0xFFA62A1E),
    onError = Color.White,
    errorContainer = Color(0xFFFBDCD7),
    onErrorContainer = Color(0xFF5B1109),
)

private val DarkColors = darkColorScheme(
    primary = Gem100,
    onPrimary = Color(0xFF06231A),
    primaryContainer = Color(0xFF1C4A3C),
    onPrimaryContainer = Color(0xFFBFE9D8),
    secondary = Gold300,
    onSecondary = Color(0xFF2A1606),
    secondaryContainer = Color(0xFF4A2F18),
    onSecondaryContainer = Gold100,
    tertiary = Color(0xFF9DD9C4),
    onTertiary = Color(0xFF06231A),
    background = DarkScaffold,
    onBackground = Color(0xFFEAF3EE),
    surface = DarkSurface,
    onSurface = Color(0xFFEAF3EE),
    surfaceVariant = Ink500,
    onSurfaceVariant = Color(0xFF9DB5AA),
    surfaceContainerLowest = Ink900,
    surfaceContainerLow = Color(0xFF0F231D),
    surfaceContainer = Color(0xFF132A23),
    surfaceContainerHigh = Color(0xFF1A372E),
    surfaceContainerHighest = Color(0xFF214238),
    outline = Color(0xFF6E8A7F),
    outlineVariant = Color(0xFF21423A),
    error = Color(0xFFFF9E90),
    onError = ArchivePlayerInk,
    errorContainer = Color(0xFF5B221A),
    onErrorContainer = Color(0xFFFFD9D2),
)

/// الشريط العلوي يتبع الوضع: حجرٌ فاتح نهاراً (فالنهار فاتحٌ حقّاً) وزمرّد عميق ليلاً.
val AppBarBackgroundLight = ArchivePaper
val AppBarBackgroundDark = DarkAppBar
val AppBarForegroundLight = Color(0xFF0F3B2F)
val AppBarForegroundDark = Color(0xFFEAF3EE)
/** لون نصّ الشريط العلوي وأيقوناته للوضع المعطى. */
fun appBarForeground(dark: Boolean): Color = if (dark) AppBarForegroundDark else AppBarForegroundLight

// ---- الخطّ: «ريم كوفي» للعناوين (كوفيّ هندسيّ من روح نقوش المساجد)، و«نوتو نسخ»
// للنصّ (نسخٌ واضح للقراءة الطويلة). كلاهما متغيّر الوزن؛ على API < 26 يُعرض الوزن
// الافتراضيّ للملفّ ثمّ يُغلَّظ صناعياً إن لزم. «أميري» يبقى للمصحف والنصوص المشكولة.
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: FontWeight) = Font(
    res, weight,
    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(
        androidx.compose.ui.text.font.FontVariation.weight(weight.weight),
    ),
)
internal val ReemKufi = FontFamily(
    variable(R.font.reem_kufi, FontWeight.Normal),
    variable(R.font.reem_kufi, FontWeight.Medium),
    variable(R.font.reem_kufi, FontWeight.SemiBold),
    variable(R.font.reem_kufi, FontWeight.Bold),
)
internal val NotoNaskh = FontFamily(
    variable(R.font.noto_naskh_arabic, FontWeight.Normal),
    variable(R.font.noto_naskh_arabic, FontWeight.Medium),
    variable(R.font.noto_naskh_arabic, FontWeight.SemiBold),
    variable(R.font.noto_naskh_arabic, FontWeight.Bold),
)
internal val Amiri = FontFamily(
    Font(R.font.amiri, FontWeight.Normal),
    Font(R.font.amiri_bold, FontWeight.Bold),
)
private fun head(size: Int, line: Int, weight: FontWeight = FontWeight.Bold) = androidx.compose.ui.text.TextStyle(
    fontFamily = ReemKufi, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
)
private fun body(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = androidx.compose.ui.text.TextStyle(
    fontFamily = NotoNaskh, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
)
private val MinbarTypography = Typography(
    displayLarge = head(48, 64), displayMedium = head(40, 54), displaySmall = head(34, 46),
    headlineLarge = head(30, 42), headlineMedium = head(26, 38), headlineSmall = head(23, 34),
    titleLarge = head(21, 30), titleMedium = body(17, 28, FontWeight.SemiBold), titleSmall = body(15, 24, FontWeight.SemiBold),
    bodyLarge = body(17, 30), bodyMedium = body(15, 26), bodySmall = body(13, 22),
    labelLarge = body(15, 22, FontWeight.SemiBold), labelMedium = body(13, 20, FontWeight.Medium), labelSmall = body(12, 18, FontWeight.Medium),
)

/// أشكال «المحراب»: زوايا ليّنة (22dp للبطاقات الكبيرة)، والأقواس الكاملة في الرأس والمشغّل.
private val MinbarShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)
/** رأس مقوّس كالمحراب: قوسٌ كامل في الأعلى وزوايا 22dp في الأسفل. */
val MihrabShape = androidx.compose.foundation.shape.RoundedCornerShape(
    topStartPercent = 50, topEndPercent = 50, bottomStartPercent = 0, bottomEndPercent = 0,
)

/// هل الوضع الحالي داكن؟ يُستخدم لاختيار درجات الإبراز على الأسطح الداكنة.
@Composable
fun isDarkTheme(themeMode: String): Boolean = when (themeMode) {
    "dark" -> true
    "system" -> androidx.compose.foundation.isSystemInDarkTheme()
    else -> false
}

@Composable
fun MinbarTheme(themeMode: String, fontScale: Float, content: @Composable () -> Unit) {
    val dark = isDarkTheme(themeMode)
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * fontScale),
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = MinbarTypography,
            shapes = MinbarShapes,
            content = content,
        )
    }
}
