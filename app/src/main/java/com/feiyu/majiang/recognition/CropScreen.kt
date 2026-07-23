//
//  CropScreen.kt
//  裁剪到手牌：让用户把裁剪框拖到「只剩自己的手牌」，再交给模型识别，
//  避免把牌桌上其他人的牌、牌墙、弃牌也识别进来。与 iOS CropView 一一对应。
//

package com.feiyu.majiang.recognition

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feiyu.majiang.tr
import com.feiyu.majiang.ui.Theme
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ImageSource { CAMERA, LIBRARY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(
    image: Bitmap,
    source: ImageSource,
    onCancel: () -> Unit,
    onRetake: () -> Unit,
    onCrop: (Bitmap) -> Unit,
) {
    BackHandler { onCancel() }

    var working by remember { mutableStateOf(image) }         // 当前（可旋转后）的图片
    var cropRect by remember { mutableStateOf<Rect?>(null) }  // 裁剪框；null = 未框选，识别整张
    var dragBase by remember { mutableStateOf<Rect?>(null) }  // 移动/缩放手势开始时的快照

    val handleSize = 28.dp
    val minCrop = 44f * androidx.compose.ui.platform.LocalDensity.current.density

    // 首次进入裁剪页时弹出一次：教用户拖框圈出自己的手牌
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("majiang.prefs", android.content.Context.MODE_PRIVATE)
    }
    var showTutorialPopup by remember {
        mutableStateOf(!prefs.getBoolean("hasSeenCropTutorial", false))
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (cropRect == null) tr("框选自己的手牌 · 可旋转") else tr("调整选区"),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text(tr("取消")) }
                },
                actions = {
                    IconButton(onClick = {
                        // 逆时针旋转 90°
                        val m = Matrix().apply { postRotate(-90f) }
                        working = Bitmap.createBitmap(working, 0, 0, working.width, working.height, m, true)
                        cropRect = null
                    }) {
                        Icon(Icons.Filled.RotateLeft, contentDescription = tr("旋转"))
                    }
                    TextButton(onClick = onRetake) {
                        Text(if (source == ImageSource.CAMERA) tr("重拍") else tr("换一张"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
                    .padding(horizontal = 20.dp)
                    .padding(top = 12.dp, bottom = 8.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (cropRect != null) {
                    TextButton(onClick = { cropRect = null }) {
                        Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("清除框选"), fontSize = 14.sp)
                    }
                }
                val recognizeLabel: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (cropRect == null) tr("识别整张照片") else tr("识别选中区域"),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                val onRecognize = {
                    val bmp = working
                    val rect = cropRect
                    // performCrop 在下方 BoxWithConstraints 外无法拿 imageRect —— 用保存的比例
                    onCrop(performCrop(bmp, rect, lastImageRect))
                }
                // 未框选时降为次要样式，引导优先框选；已框选则是推荐操作，用主色实心
                if (cropRect == null) {
                    OutlinedButton(
                        onClick = onRecognize,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Theme.accent),
                        border = BorderStroke(1.dp, Theme.accent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        content = recognizeLabel,
                    )
                } else {
                    Button(
                        onClick = onRecognize,
                        colors = ButtonDefaults.buttonColors(containerColor = Theme.accent),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        content = recognizeLabel,
                    )
                }
                if (cropRect == null) {
                    Text(
                        tr("不划区域也能识别，但整桌入镜时牌小、易混入别人的牌，准确率会下降；建议先圈出自己的手牌。"),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val containerW = constraints.maxWidth.toFloat()
            val containerH = constraints.maxHeight.toFloat()

            // 图片在视图中的实际显示区域（scaledToFit 居中）
            val iw = working.width.toFloat()
            val ih = working.height.toFloat()
            val scale = minOf(containerW / iw, containerH / ih)
            val w = iw * scale
            val h = ih * scale
            val imageRect = Rect((containerW - w) / 2, (containerH - h) / 2,
                (containerW - w) / 2 + w, (containerH - h) / 2 + h)
            lastImageRect = imageRect

            fun clampPoint(p: Offset) = Offset(
                p.x.coerceIn(imageRect.left, imageRect.right),
                p.y.coerceIn(imageRect.top, imageRect.bottom),
            )

            Image(
                bitmap = working.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            // 在图片上拖动以画出选区
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(working) {
                        var start = Offset.Zero
                        detectDragGesturesCompat(
                            onDragStart = { start = clampPoint(it) },
                            onDrag = { pos ->
                                val a = start
                                val b = clampPoint(pos)
                                cropRect = Rect(
                                    minOf(a.x, b.x), minOf(a.y, b.y),
                                    maxOf(a.x, b.x), maxOf(a.y, b.y),
                                )
                            },
                            onDragEnd = {
                                // 太小当作误触，回到「未框选」
                                val r = cropRect
                                if (r != null && (r.width < minCrop || r.height < minCrop)) cropRect = null
                            },
                        )
                    }
            )

            // 顶部：未框选时的常驻提示——教用户圈出自己的手牌以提高识别准确率
            if (cropRect == null) {
                CropTutorialBanner(modifier = Modifier.align(Alignment.TopCenter))
            }

            val rect = cropRect
            if (rect != null) {
                // 框外压暗
                Canvas(Modifier.fillMaxSize()) {
                    val dim = Color.Black.copy(alpha = 0.55f)
                    drawRect(dim, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, rect.top))
                    drawRect(dim, Offset(0f, rect.bottom),
                        androidx.compose.ui.geometry.Size(size.width, size.height - rect.bottom))
                    drawRect(dim, Offset(0f, rect.top),
                        androidx.compose.ui.geometry.Size(rect.left, rect.height))
                    drawRect(dim, Offset(rect.right, rect.top),
                        androidx.compose.ui.geometry.Size(size.width - rect.right, rect.height))
                    // 白色边框
                    drawRect(
                        Color.White, Offset(rect.left, rect.top),
                        androidx.compose.ui.geometry.Size(rect.width, rect.height),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }

                // 移动手势（框内拖动）
                Box(
                    Modifier
                        .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                        .size(
                            with(androidx.compose.ui.platform.LocalDensity.current) { rect.width.toDp() },
                            with(androidx.compose.ui.platform.LocalDensity.current) { rect.height.toDp() },
                        )
                        .pointerInput(working) {
                            detectDragGesturesCompat(
                                onDragStart = { dragBase = cropRect },
                                onDragBy = { delta ->
                                    val base = dragBase ?: return@detectDragGesturesCompat
                                    val cur = cropRect ?: return@detectDragGesturesCompat
                                    var nx = cur.left + delta.x
                                    var ny = cur.top + delta.y
                                    nx = nx.coerceIn(imageRect.left, imageRect.right - base.width)
                                    ny = ny.coerceIn(imageRect.top, imageRect.bottom - base.height)
                                    cropRect = Rect(nx, ny, nx + base.width, ny + base.height)
                                },
                                onDragEnd = { dragBase = null },
                            )
                        }
                )

                // 四角把手
                val corners = listOf(
                    Corner.TL to Offset(rect.left, rect.top),
                    Corner.TR to Offset(rect.right, rect.top),
                    Corner.BL to Offset(rect.left, rect.bottom),
                    Corner.BR to Offset(rect.right, rect.bottom),
                )
                val density = androidx.compose.ui.platform.LocalDensity.current
                val half = with(density) { handleSize.toPx() } / 2
                corners.forEach { (corner, pos) ->
                    Box(
                        Modifier
                            .offset { IntOffset((pos.x - half).roundToInt(), (pos.y - half).roundToInt()) }
                            .size(handleSize)
                            .background(Color.White, androidx.compose.foundation.shape.CircleShape)
                            .pointerInput(working, corner) {
                                detectDragGesturesCompat(
                                    onDragStart = { dragBase = cropRect },
                                    onDragBy = { delta ->
                                        val cur = cropRect ?: return@detectDragGesturesCompat
                                        var minX = cur.left; var minY = cur.top
                                        var maxX = cur.right; var maxY = cur.bottom
                                        when (corner) {
                                            Corner.TL -> { minX += delta.x; minY += delta.y }
                                            Corner.TR -> { maxX += delta.x; minY += delta.y }
                                            Corner.BL -> { minX += delta.x; maxY += delta.y }
                                            Corner.BR -> { maxX += delta.x; maxY += delta.y }
                                        }
                                        // 限制在图片范围内
                                        minX = maxOf(minX, imageRect.left); minY = maxOf(minY, imageRect.top)
                                        maxX = minOf(maxX, imageRect.right); maxY = minOf(maxY, imageRect.bottom)
                                        // 最小尺寸（防止翻转/过小）
                                        if (maxX - minX < minCrop) {
                                            if (corner == Corner.TL || corner == Corner.BL) minX = maxX - minCrop
                                            else maxX = minX + minCrop
                                        }
                                        if (maxY - minY < minCrop) {
                                            if (corner == Corner.TL || corner == Corner.TR) minY = maxY - minCrop
                                            else maxY = minY + minCrop
                                        }
                                        cropRect = Rect(minX, minY, maxX, maxY)
                                    },
                                    onDragEnd = { dragBase = null },
                                )
                            }
                    )
                }
            }
        }
    }

    if (showTutorialPopup) {
        CropTutorialPopup(
            onDismiss = {
                prefs.edit().putBoolean("hasSeenCropTutorial", true).apply()
                showTutorialPopup = false
            },
        )
    }
    }
}

/** 顶部：未框选时的常驻提示——教用户圈出自己的手牌以提高识别准确率 */
@Composable
private fun CropTutorialBanner(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(
            Icons.Filled.PanTool, contentDescription = null,
            tint = Color.White, modifier = Modifier.size(16.dp),
        )
        Text(
            tr("圈出自己的手牌（含碰/杠），排除别人的牌和弃牌堆，识别更准"),
            fontSize = 12.sp, fontWeight = FontWeight.Medium,
            color = Color.White,
        )
    }
}

/** 首次进入裁剪页时弹出一次：教用户拖框圈出自己的手牌 */
@Composable
private fun CropTutorialPopup(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .widthIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                .padding(24.dp),
        ) {
            Icon(
                Icons.Filled.PanTool, contentDescription = null,
                tint = Theme.accent, modifier = Modifier.size(34.dp),
            )
            Text(
                tr("圈出自己的手牌"),
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                tr("在照片上按住并拖动，画一个框只圈住你自己的手牌（含碰/杠），排除别人的牌和弃牌堆，识别会更准。"),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Theme.accent),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(tr("知道了"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private enum class Corner { TL, TR, BL, BR }

/** 最近一次布局的图片显示区域（供裁剪换算；单一裁剪页可用简单共享） */
private var lastImageRect: Rect = Rect.Zero

/** 视图坐标裁剪框 → 位图像素裁剪 */
private fun performCrop(bitmap: Bitmap, rect: Rect?, imageRect: Rect): Bitmap {
    if (rect == null || imageRect.width <= 0 || imageRect.height <= 0) return bitmap
    val iw = bitmap.width.toFloat()
    val ih = bitmap.height.toFloat()
    val relX = (rect.left - imageRect.left) / imageRect.width
    val relY = (rect.top - imageRect.top) / imageRect.height
    val relW = rect.width / imageRect.width
    val relH = rect.height / imageRect.height
    val x = (relX * iw).roundToInt().coerceIn(0, bitmap.width - 1)
    val y = (relY * ih).roundToInt().coerceIn(0, bitmap.height - 1)
    val w = (relW * iw).roundToInt().coerceIn(1, bitmap.width - x)
    val h = (relH * ih).roundToInt().coerceIn(1, bitmap.height - y)
    return try {
        Bitmap.createBitmap(bitmap, x, y, w, h)
    } catch (_: Exception) {
        bitmap
    }
}

/**
 * detectDragGestures 的便捷封装：
 * onDrag 给「当前指针位置」（用于画框），onDragBy 给「位移增量」（用于移动/缩放）。
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDragGesturesCompat(
    onDragStart: (Offset) -> Unit = {},
    onDrag: ((Offset) -> Unit)? = null,
    onDragBy: ((Offset) -> Unit)? = null,
    onDragEnd: () -> Unit = {},
) {
    detectDragGestures(
        onDragStart = onDragStart,
        onDragEnd = onDragEnd,
        onDragCancel = onDragEnd,
    ) { change, dragAmount ->
        change.consume()
        onDrag?.invoke(change.position)
        onDragBy?.invoke(dragAmount)
    }
}
