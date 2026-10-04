package com.uniomo.wafce.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---------- 品牌与语义色 ----------
val Brand = Color(0xFF2B5CE6)
val BrandDark = Color(0xFF1B3FA8)
val BrandSoft = Color(0xFFE8EEFF)
val Accent = Color(0xFF00C2A8)

val Ok = Color(0xFF17A673)
val OkSoft = Color(0xFFE4F6EF)
val Warn = Color(0xFFF5A623)
val WarnSoft = Color(0xFFFFF3DF)
val Danger = Color(0xFFE5484D)
val DangerSoft = Color(0xFFFFE9EA)
val InfoColor = Color(0xFF3E8FF0)
val InfoSoft = Color(0xFFE7F1FE)
val NeutralSoft = Color(0xFFF1F3F7)

val SurfaceTint = Color(0xFFF7F9FC)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = BrandSoft,
    onPrimaryContainer = BrandDark,
    secondary = Accent,
    tertiary = InfoColor,
    background = Color(0xFFF5F7FA),
    surface = Color.White,
    surfaceVariant = Color(0xFFF5F7FA),
    outline = Color(0xFFD6DCE8),
    error = Danger
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB0FF),
    onPrimary = Color(0xFF0E1F44),
    primaryContainer = Color(0xFF1B3FA8),
    secondary = Color(0xFF4BE0C8),
    tertiary = Color(0xFF8FB0FF),
    background = Color(0xFF0E1320),
    surface = Color(0xFF131A29),
    error = Color(0xFFFF8A8F)
)

private val AppTypography = Typography()

@Composable
fun SafeLineTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
