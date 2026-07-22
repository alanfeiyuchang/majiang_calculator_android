//
//  Components.kt
//  通用小组件：牌面块 / 分区卡片 / 副露块（与 iOS ContentView 的同名视图对应）。
//

package com.feiyu.majiang.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feiyu.majiang.R
import com.feiyu.majiang.core.MahjongCard
import com.feiyu.majiang.core.Meld
import com.feiyu.majiang.tr

/** 牌面资源 id（tile_<suit>_<rank>） */
fun tileDrawable(card: MahjongCard): Int = when (card.assetName) {
    "tile_man_1" -> R.drawable.tile_man_1; "tile_man_2" -> R.drawable.tile_man_2
    "tile_man_3" -> R.drawable.tile_man_3; "tile_man_4" -> R.drawable.tile_man_4
    "tile_man_5" -> R.drawable.tile_man_5; "tile_man_6" -> R.drawable.tile_man_6
    "tile_man_7" -> R.drawable.tile_man_7; "tile_man_8" -> R.drawable.tile_man_8
    "tile_man_9" -> R.drawable.tile_man_9
    "tile_pin_1" -> R.drawable.tile_pin_1; "tile_pin_2" -> R.drawable.tile_pin_2
    "tile_pin_3" -> R.drawable.tile_pin_3; "tile_pin_4" -> R.drawable.tile_pin_4
    "tile_pin_5" -> R.drawable.tile_pin_5; "tile_pin_6" -> R.drawable.tile_pin_6
    "tile_pin_7" -> R.drawable.tile_pin_7; "tile_pin_8" -> R.drawable.tile_pin_8
    "tile_pin_9" -> R.drawable.tile_pin_9
    "tile_sou_1" -> R.drawable.tile_sou_1; "tile_sou_2" -> R.drawable.tile_sou_2
    "tile_sou_3" -> R.drawable.tile_sou_3; "tile_sou_4" -> R.drawable.tile_sou_4
    "tile_sou_5" -> R.drawable.tile_sou_5; "tile_sou_6" -> R.drawable.tile_sou_6
    "tile_sou_7" -> R.drawable.tile_sou_7; "tile_sou_8" -> R.drawable.tile_sou_8
    else -> R.drawable.tile_sou_9
}

/** 手牌 / 听牌块（3:4 牌面图）；fillWidth = 由外层网格决定宽度（如 7 列手牌网格） */
@Composable
fun MahjongTileChip(
    card: MahjongCard,
    onTap: (() -> Unit)? = null,
    large: Boolean = false,
    fillWidth: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val width = if (large) 46.dp else 38.dp
    val corner = width * 0.16f
    val shape = RoundedCornerShape(corner)
    Image(
        painter = painterResource(tileDrawable(card)),
        contentDescription = card.displayText,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .let {
                if (fillWidth) it.fillMaxWidth().aspectRatio(3f / 4f)
                else it.size(width, width * 4f / 3f)
            }
            .shadow(2.5.dp, shape)
            .clip(shape)
            .border(1.25.dp, Color.Black.copy(alpha = 0.28f), shape)
            .let { if (onTap != null) it.clickable { onTap() } else it },
    )
}

/** 分区卡片（标题 + 图标 + 右侧附注） */
@Composable
fun SectionCard(
    title: String,
    icon: ImageVector? = null,
    accessory: String? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(Theme.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                RoundedCornerShape(Theme.cardRadius)
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = Theme.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (accessory != null) {
                    Text(
                        accessory, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            content()
        }
    }
}

/** 副露块：一组碰/杠的牌面 + 种类标签，点击删除 */
@Composable
fun MeldChipGroup(meld: Meld, onRemove: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(meld.tileCount) {
                MahjongTileChip(card = meld.card, onTap = onRemove)
            }
        }
        Text(
            tr(meld.kind.raw),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
