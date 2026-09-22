package com.readervoice.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ReaderVoice 设计系统（2026-09-18 UI 重做）。
 *
 * 设计取向：**朗读工作台**——信息密度高、长时间阅读不刺眼、状态一眼可辨。
 *   · 颜色：深墨蓝主色（书卷感）+ 中性面 + 语义色（成功/警告/危险）
 *   · 字阶：Display/Title/Body/Mono（日志与 ID 用等宽，便于逐字核对）
 *   · 间距：4dp 基准栅格（space1..space6）
 *   · 状态栏：所有界面走 [Scaffold] + safeDrawing insets，绝不被状态栏/手势条压住
 */

// ── 颜色 token ────────────────────────────────────────────────
val InkBlue = Color(0xFF2F5D8C)
val InkBlueDark = Color(0xFF9CC5F0)
val Accent = Color(0xFFB4794A)          // 书脊棕，强调用
val SurfaceLight = Color(0xFFFBF9F6)
val SurfaceDark = Color(0xFF12151A)
val OkGreen = Color(0xFF3C8C5A)
val WarnAmber = Color(0xFFB5852F)
val DangerRed = Color(0xFFB3453C)

private val LightScheme = lightColorScheme(
    primary = InkBlue,
    onPrimary = Color.White,
    secondary = Accent,
    onSecondary = Color.White,
    background = SurfaceLight,
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFEDE9E3),
    onSurfaceVariant = Color(0xFF4A4E52),
    error = DangerRed,
)

private val DarkScheme = darkColorScheme(
    primary = InkBlueDark,
    onPrimary = Color(0xFF0B1B2B),
    secondary = Color(0xFFD8B08A),
    background = SurfaceDark,
    onBackground = Color(0xFFE6E8EA),
    surface = Color(0xFF1A1F26),
    onSurface = Color(0xFFE6E8EA),
    surfaceVariant = Color(0xFF2A3138),
    onSurfaceVariant = Color(0xFFC2C8CE),
    error = Color(0xFFFF8A80),
)

// ── 间距栅格（4dp 基准）───────────────────────────────────────
object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 24.dp
    val s6 = 32.dp
}

// ── 字阶 ──────────────────────────────────────────────────────
private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

/** 日志/ID/JSON 用等宽，便于逐字核对（本项目的 Gate 就是逐字判据）。 */
val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)

@Composable
fun ReaderVoiceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}

