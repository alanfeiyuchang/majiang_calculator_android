//
//  TileGrouping.kt
//  拍照识别后的空间聚类：把识别到的牌盒分成「手牌」与「桌上副露（碰/明杠/暗杠）」。
//  纯几何逻辑，不依赖 Android / ONNX，便于独立断言测试。与 iOS TileGrouping.swift 一一对应。
//
//  川麻无吃，副露只有：碰（3 张同牌）/ 明杠（4 张同牌）/ 暗杠（1 明 3 暗，只识别到那张明牌）。
//

package com.feiyu.majiang.core

/** 识别到的一张牌：横向范围 + 行位置（纵向中心）+ 牌面 */
data class TileBox(
    val minX: Float,
    val maxX: Float,
    /** 纵向中心（分行用） */
    val cy: Float,
    val height: Float,
    val card: MahjongCard,
) {
    val width: Float get() = maxX - minX
    val cx: Float get() = (minX + maxX) / 2
}

/** 分组结果：手牌 + 桌上副露；guessedConcealedKong 标记是否含「靠单张明牌猜出的暗杠」（需重点核对） */
data class RecognitionResult(
    val hand: List<MahjongCard>,
    val melds: List<Meld>,
    val guessedConcealedKong: Boolean,
)

/**
 * 把识别到的牌盒按空间聚类分成手牌与副露。
 *
 * 步骤：① 按纵向中心分行；② 行内按横向间距切成「簇」（间距 > 阈值即断开）；
 * ③ 最大的簇视为手牌，其余簇按「相邻同牌」切段判定（几组副露紧挨也能拆）：
 *    每段 3 张同牌 → 碰、4 张同牌 → 明杠、孤立单张 → 暗杠、认不准 → 并回手牌。
 */
fun groupTiles(boxes: List<TileBox>): RecognitionResult {
    if (boxes.isEmpty()) return RecognitionResult(emptyList(), emptyList(), false)

    // ① 分行
    val avgH = boxes.map { it.height }.sum() / boxes.size
    val rowGap = maxOf(avgH * 0.6f, 1f)
    val rows = mutableListOf<MutableList<TileBox>>()
    for (b in boxes.sortedBy { it.cy }) {
        val last = rows.lastOrNull()
        if (last != null && kotlin.math.abs(b.cy - last.first().cy) <= rowGap) {
            last.add(b)
        } else {
            rows.add(mutableListOf(b))
        }
    }

    // ② 行内按横向间距切簇（间距 > 约半张牌宽 → 断开）
    val avgW = boxes.map { it.width }.sum() / boxes.size
    val colGap = maxOf(avgW * 0.55f, 1f)
    val clusters = mutableListOf<List<TileBox>>()
    for (row in rows) {
        val sorted = row.sortedBy { it.minX }
        var current = mutableListOf<TileBox>()
        var prevMaxX = -Float.MAX_VALUE
        for (b in sorted) {
            if (current.isNotEmpty() && b.minX - prevMaxX > colGap) {
                clusters.add(current)
                current = mutableListOf()
            }
            current.add(b)
            prevMaxX = maxOf(prevMaxX, b.maxX)
        }
        if (current.isNotEmpty()) clusters.add(current)
    }

    // ③ 最大簇为手牌，其余判定副露
    val handIdx = clusters.indices.maxByOrNull { clusters[it].size }
        ?: return RecognitionResult(emptyList(), emptyList(), false)

    val hand = mutableListOf<MahjongCard>()
    val melds = mutableListOf<Meld>()
    var guessed = false
    for ((i, cluster) in clusters.withIndex()) {
        val cards = cluster.sortedBy { it.minX }.map { it.card }
        if (i == handIdx) {
            hand.addAll(cards)
            continue
        }
        val parsed = if (cards.size == 1) {
            listOf(Meld(Meld.Kind.CONCEALED_KONG, cards[0]))   // 暗杠：只露的那张明牌
        } else {
            parseMeldRuns(cards)
        }
        if (parsed != null) {
            melds.addAll(parsed)
            if (parsed.any { it.kind == Meld.Kind.CONCEALED_KONG }) guessed = true
        } else {
            hand.addAll(cards)   // 认不准 → 并回手牌
        }
    }
    return RecognitionResult(hand, melds, guessed)
}

/**
 * 把一个非手牌簇解析成一组或多组副露（桌上几组碰/杠可能紧挨着没有空隙）。
 * 按「相邻同牌」切段：3 张 = 碰、4 张 = 明杠、单张 = 暗杠只露的那张明牌；
 * 出现 2 张同牌等认不准的段、或整簇没有一个 3/4 张的段，返回 null（并回手牌）。
 */
private fun parseMeldRuns(cards: List<MahjongCard>): List<Meld>? {
    val runs = mutableListOf<MutableList<MahjongCard>>()
    for (c in cards) {
        val last = runs.lastOrNull()
        if (last != null && last.last() == c) last.add(c) else runs.add(mutableListOf(c))
    }
    if (runs.none { it.size == 3 || it.size == 4 }) return null

    val melds = mutableListOf<Meld>()
    for (run in runs) {
        when (run.size) {
            3 -> melds.add(Meld(Meld.Kind.PONG, run[0]))
            4 -> melds.add(Meld(Meld.Kind.EXPOSED_KONG, run[0]))
            1 -> melds.add(Meld(Meld.Kind.CONCEALED_KONG, run[0]))
            else -> return null
        }
    }
    return melds
}

// MARK: - 二次放大识别的区域估计

/** 纯几何矩形（不依赖 android.graphics，便于 JVM 单测）。与 iOS 的 CGRect 用法对应。 */
data class GeoRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val maxX: Float get() = x + width
    val maxY: Float get() = y + height

    fun contains(other: GeoRect): Boolean =
        other.x >= x && other.y >= y && other.maxX <= maxX && other.maxY <= maxY
}

/**
 * 第一遍识别出的牌框（原图像素坐标）的并集区域，外扩后返回，用于裁剪放大再识别。
 * 区域已占满画面（放大无意义）或没有检测框时返回 null。与 iOS zoomRegion 一一对应。
 */
fun zoomRegion(boxes: List<GeoRect>, imageWidth: Float, imageHeight: Float): GeoRect? {
    if (boxes.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
    var minX = boxes[0].x
    var minY = boxes[0].y
    var maxX = boxes[0].maxX
    var maxY = boxes[0].maxY
    for (b in boxes.drop(1)) {
        minX = minOf(minX, b.x); minY = minOf(minY, b.y)
        maxX = maxOf(maxX, b.maxX); maxY = maxOf(maxY, b.maxY)
    }

    // 外扩：约 1.5 张牌高 + 并集的 12%，容忍第一遍漏检的边缘牌
    val avgH = boxes.map { it.height }.sum() / boxes.size
    val pad = maxOf(avgH * 1.5f, maxOf(maxX - minX, maxY - minY) * 0.12f)
    val px1 = maxOf(0f, minX - pad)
    val py1 = maxOf(0f, minY - pad)
    val px2 = minOf(imageWidth, maxX + pad)
    val py2 = minOf(imageHeight, maxY + pad)
    if (px2 - px1 <= 0 || py2 - py1 <= 0) return null

    // 两个方向都已接近整图 → 放大没有收益
    if (px2 - px1 > imageWidth * 0.85f && py2 - py1 > imageHeight * 0.85f) return null
    return GeoRect(px1, py1, px2 - px1, py2 - py1)
}
