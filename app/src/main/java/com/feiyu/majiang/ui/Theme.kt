//
//  Theme.kt
//  设计常量：与 iOS ContentView 的 Theme / 颜色一致。
//

package com.feiyu.majiang.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.feiyu.majiang.core.MahjongCard

object Theme {
    val accent = Color(red = 0.22f, green = 0.48f, blue = 0.96f)
    val moneyGreen = Color(red = 0.16f, green = 0.65f, blue = 0.40f)
    val hintGreen = Color(red = 0.2f, green = 0.72f, blue = 0.45f)
    val cardRadius = 20.dp
    val sectionSpacing = 16.dp

    fun suitColor(suit: MahjongCard.Suit): Color = when (suit) {
        MahjongCard.Suit.WAN -> Color(red = 0.88f, green = 0.28f, blue = 0.24f)
        MahjongCard.Suit.TONG -> Color(red = 0.18f, green = 0.48f, blue = 0.88f)
        MahjongCard.Suit.TIAO -> Color(red = 0.15f, green = 0.62f, blue = 0.36f)
    }
}

/** 近似 iOS systemGroupedBackground / secondarySystemGroupedBackground 的浅/深配色 */
@Composable
fun MajiangTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme = if (dark) {
        darkColorScheme(
            primary = Theme.accent,
            background = Color(0xFF000000),
            surface = Color(0xFF1C1C1E),
            surfaceVariant = Color(0xFF2C2C2E),
        )
    } else {
        lightColorScheme(
            primary = Theme.accent,
            background = Color(0xFFF2F2F7),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE5E5EA),
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
