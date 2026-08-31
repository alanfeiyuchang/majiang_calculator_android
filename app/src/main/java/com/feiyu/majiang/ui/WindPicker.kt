//
//  WindPicker.kt
//  圈风 / 门风的选择控件：点一下弹菜单，比滚轮省地方（一行高度就够）。
//

package com.feiyu.majiang.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一个风位选择器：`标签 [东 ▾]`，点按钮弹出东南西北。
 * [selected] 0…3 = 东南西北。
 */
@Composable
fun WindPicker(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val current = options.getOrNull(selected).orEmpty()

    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
        Box {
            OutlinedButton(
                onClick = { open = true },
                contentPadding = PaddingValues(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                // 无障碍标签在这里给全，否则读屏只念得到「东」这一个字
                modifier = Modifier.semantics { contentDescription = "$label $current" },
            ) {
                Text(current, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, name ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                name,
                                fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = { open = false; if (i != selected) onSelect(i) },
                    )
                }
            }
        }
    }
}
