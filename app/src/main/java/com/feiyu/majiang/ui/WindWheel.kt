//
//  WindWheel.kt
//  圈风 / 门风的滚轮选择。Compose 没有内置的 wheel picker，这里用 LazyColumn +
//  吸附（snapFlingBehavior）自己拼一个：上下各垫一格空白，让选中项正好停在中间。
//

package com.feiyu.majiang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ROW = 32.dp        // 每格高度
private const val VISIBLE = 3  // 可见格数（上一格 + 选中 + 下一格）

/**
 * 一列风位滚轮。[selected] 0…3 = 东南西北。
 * 滑动停下后把中间那格回调出去；[selected] 从外部变化时也会滚过去。
 */
@Composable
fun WindWheel(
    label: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    // 顶端那一项就是中间那一项——因为上下各垫了一格空白
    val centered by remember {
        derivedStateOf {
            if (state.firstVisibleItemScrollOffset > 0) state.firstVisibleItemIndex + 1
            else state.firstVisibleItemIndex
        }
    }

    LaunchedEffect(selected) {
        if (!state.isScrollInProgress && centered != selected) state.scrollToItem(selected)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val i = centered.coerceIn(options.indices)
                if (i != selected) onSelect(i)
            }
        }
    }

    Column(modifier) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(bottom = 2.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(ROW * VISIBLE)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                .semantics { contentDescription = "$label ${options.getOrNull(selected).orEmpty()}" },
        ) {
            LazyColumn(
                state = state,
                flingBehavior = fling,
                // 上下各垫一格，让选中项停在正中间
                contentPadding = PaddingValues(vertical = ROW),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(options) { name ->
                    val i = options.indexOf(name)
                    Box(
                        Modifier.fillMaxWidth().height(ROW),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            name,
                            fontSize = if (i == selected) 19.sp else 17.sp,
                            fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = if (i == selected) 1f else 0.35f),
                        )
                    }
                }
            }
        }
    }
}
