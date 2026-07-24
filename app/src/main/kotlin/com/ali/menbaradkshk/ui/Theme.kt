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
import androidx.compose.ui.unit.sp
import com.ali.menbaradkshk.R

// هوية «منبر»: تركواز عميق مع ذهبي معتّق ودرجات طبيعية مريحة للعين.
// مأخوذة حرفياً من theme.dart في التطبيق الأصلي (Flutter) للحفاظ على الهوية البصرية.
val Teal = Color(0xFF1E6668)
val Slate = Color(0xFF334A57)
val Gold = Color(0xFF8A6724)
val BlueBrand = Color(0xFF416A8A)
val GreenBrand = Color(0xFF3E7661)
val OrangeBrand = Color(0xFFA9602C)
val SecondaryGold = Color(0xFFD4B35C)
val PositiveOnDark = Color(0xFF9AD0B2)
val GoldOnDark = Color(0xFFE0BD69)

// أسطح داكنة/فاتحة مطابقة لـ scaffold/appBar/surface في الأصل.
private val DarkAppBar = Color(0xFF1B2B31)
private val DarkScaffold = Color(0xFF172328)
private val DarkSurface = Color(0xFF1D2A30)
private val LightScaffold = Color(0xFFF6F8F5)
private val LightSurface = Color(0xFFFBFCFA)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBE9),
    onPrimaryContainer = Color(0xFF0A2F30),
    secondary = Gold,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1E6C7),
    onSecondaryContainer = Color(0xFF2C2411),
    tertiary = GreenBrand,
    onTertiary = Color.White,
    background = LightScaffold,
    onBackground = Color(0xFF1A2226),
    surface = LightSurface,
    onSurface = Color(0xFF1A2226),
    surfaceVariant = Color(0xFFE4EBE8),
    onSurfaceVariant = Color(0xFF47555A),
    surfaceContainer = Color(0xFFEEF3F0),
    surfaceContainerHigh = Color(0xFFE8EFEC),
    outline = Color(0xFF6C7A80),
    outlineVariant = Color(0xFFC4D0CE),
)

private val DarkColors = darkColorScheme(
    primary = SecondaryGold,
    onPrimary = Color(0xFF2C2411),
    primaryContainer = Color(0xFF31575A),
    onPrimaryContainer = Color(0xFFDCEBE9),
    secondary = GoldOnDark,
    onSecondary = Color(0xFF2C2411),
    secondaryContainer = Color(0xFF3A3320),
    onSecondaryContainer = Color(0xFFF1E6C7),
    tertiary = PositiveOnDark,
    onTertiary = Color(0xFF0A2F30),
    background = DarkScaffold,
    onBackground = Color(0xFFE5ECEC),
    surface = DarkSurface,
    onSurface = Color(0xFFE5ECEC),
    surfaceVariant = Color(0xFF2A3A40),
    onSurfaceVariant = Color(0xFFA8B5B8),
    surfaceContainer = Color(0xFF22323A),
    surfaceContainerHigh = Color(0xFF27383F),
    outline = Color(0xFF6C7A80),
    outlineVariant = Color(0xFF34444A),
)

/// ألوان شريط التطبيق العلوي (AppBar) — تركواز بنص أبيض كما في الأصل.
val AppBarBackgroundLight = Teal
val AppBarBackgroundDark = DarkAppBar
val AppBarForeground = Color.White

private val Amiri = FontFamily(
    Font(R.font.amiri, FontWeight.Normal),
    Font(R.font.amiri_bold, FontWeight.Bold),
)
private val MinbarTypography = Typography(
    displaySmall = androidx.compose.ui.text.TextStyle(
        fontFamily = Amiri,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
    ),
    headlineMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = Amiri,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = Amiri,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
    ),
    titleMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = Amiri,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = Amiri, fontSize = 18.sp),
    bodyMedium = androidx.compose.ui.text.TextStyle(fontFamily = Amiri, fontSize = 16.sp),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = Amiri,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
    ),
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
            content = content,
        )
    }
}
