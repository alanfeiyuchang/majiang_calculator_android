//
//  TileGrouping.kt
//  拍照识别后的空间聚类：把识别到的牌盒分成「手牌」与「桌上副露（碰/明杠/暗杠）」。
//  纯几何逻辑，不依赖 Android / ONNX，便于独立断言测试。与 iOS TileGrouping.swift 一一对应。
//
//  川麻无吃，副露只有：碰（3 张同牌）/ 明杠（4 张同牌）/ 暗杠（1 明 3 暗，只识别到那张明牌）。
//
//  整桌入镜时，桌子中央的弃牌堆、对家的牌也会被模型检出。这里**不**按大小把它们滤掉——
//  任何「按大小丢框」的过滤一旦阈值偏了就会静默吃掉自己的碰/杠。它们由裁剪页的自动框
//  在像素层面排除；没排干净时张数会对不上，由 hasValidTileCount 提示用户，而不是偷偷删掉。
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
    /** 花牌（国标）。花牌摆在一边、不参与和牌，因此**不进** hand，也不计入张数不变量。 */
    val flowers: List<MahjongCard> = emptyList(),
) {
    /**
     * 换算成「手牌张数」：一组副露占 3 张名额。合法值为 13（未摸牌）或 14（已摸牌）。
     * 花牌不算——国标里花牌是额外亮出来的，13/14 张之外。
     */
    val effectiveTileCount: Int get() = hand.size + 3 * melds.size
    val hasValidTileCount: Boolean get() = effectiveTileCount == 13 || effectiveTileCount == 14
}

// MARK: - 近景判断（**只**用于给裁剪页自动画框，绝不参与识别结果的取舍）
//
// 判据用**框高**。实拍数据显示：属于自己的牌，框高集中在 0.049–0.110（相对图高）；
// 桌上的弃牌在 0.019–0.054，12 张里 11 张能干净分开。原因是弃牌**又远又平摊**，
// 被透视双重压缩；自己的牌要么立着、要么就在眼前。

/** 低于「基准牌高」这个比例的框视为桌上的牌。0.70 是在实拍数据集上扫出来的甜点。 */
private const val NEAR_FIELD_RATIO = 0.70f

/** 区域在牌框并集之外再放出去的余量，单位是「平均牌高」的倍数。给大了薄薄的单排手牌 IoU 掉得很快。 */
private const val REGION_PAD_TILES = 0.20f

/**
 * 行内切簇的间距阈值，单位是「平均牌宽」的倍数。
 *
 * 在 12 张实拍上量过（只统计「自己的牌」那些行，n=133 个间距）：
 *   组内相邻牌      -0.35 … +0.05   （牌挨着摆，检测框还略有重叠）
 *   组与组之间      +0.72、+1.18    （照片 04，一行摆了几副副露）
 * 中间 0.05–0.72 是一片空白，分界很干净。
 *
 * 原值 0.9 **比观测到的最小真实组间间距（0.72）还大**，等于那条边界根本没被切开，
 * 只能靠簇内的贪心解析去救；国标的吃摆得离手牌近一点就会被并进手牌。
 * 0.35 取在空白区间里：高于组内上限 0.05（也高于合成测试里的 0.10）7 倍，
 * 又比 0.72 低一半。改成 0.35 后 12 张实拍走完整链路的结果逐字节不变。
 */
private const val COL_GAP_TILES = 0.35f

/** 闭包吸收阈值：已经有这么大比例压在框内的检测框会被并进来。 */
private const val CLOSURE_OVERLAP = 0.5f

/**
 * 基准牌高 = 高度的第 90 百分位。
 * 不用最大值（单个虚框就会把基准抬高），也不用中位数（桌上的牌可能比自己的还多）。
 */
fun referenceTileHeight(heights: List<Float>): Float {
    if (heights.isEmpty()) return 0f
    val sorted = heights.sortedDescending()
    return sorted[minOf(sorted.size - 1, sorted.size / 10)]
}

/**
 * 把识别到的牌盒按空间聚类分成手牌与副露。
 *
 * 先「严格」解析：只有 3/4 张同牌才算副露，孤立单张**不**猜暗杠。
 * 「单张 = 暗杠」这条猜测会让几乎任何簇都能「解析成副露」，实拍里因此凭空造出
 * 好几组暗杠、张数暴涨。张数对不上时再试着猜——只有这样能让 13/14 成立才采纳。
 */
fun groupTiles(boxes: List<TileBox>, mode: GameMode = GameMode.SICHUAN): RecognitionResult {
    // 花牌先摘出去再聚类。花牌不参与和牌、也不占 13/14 的名额，
    // 留在里面会把「手牌簇」撑大、还会让张数不变量永远对不上。
    // 它们通常也不和手牌摆在一排（亮在自己面前），本来就不该和手牌聚成一簇。
    val flowers = boxes.filter { it.card.suit.isFlower }.sortedBy { it.minX }.map { it.card }
    val playable = boxes.filter { !it.card.suit.isFlower }
    return groupPlayable(playable, mode).copy(flowers = flowers)
}

private fun groupPlayable(boxes: List<TileBox>, mode: GameMode): RecognitionResult {
    val strict = group(boxes, guessConcealedKong = false, mode = mode)
    if (strict.hasValidTileCount) return strict
    val lenient = group(boxes, guessConcealedKong = true, mode = mode)
    return if (lenient.hasValidTileCount) lenient else strict
}

private fun group(boxes: List<TileBox>, guessConcealedKong: Boolean, mode: GameMode): RecognitionResult {
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

    // ② 行内按横向间距切簇（阈值见 COL_GAP_TILES）
    val avgW = boxes.map { it.width }.sum() / boxes.size
    val colGap = maxOf(avgW * COL_GAP_TILES, 1f)
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

    // ③ 每个簇先试着解析成副露；解析不成的才可能是手牌。
    //    （手牌里混着单张和顺子，解析必然失败；整齐的 3/4 张同牌则会解析成功。）
    val parsed = clusters.map { parseMeldRuns(it.sortedBy { b -> b.minX }.map { b -> b.card }, guessConcealedKong, mode.isMCR) }
    val handCandidates = clusters.indices.filter { parsed[it] == null }
    val pool = if (handCandidates.isEmpty()) clusters.indices.toList() else handCandidates

    // 手牌簇：张数为主，牌面大小加权——张数相近时，离镜头更近（框更高）的那簇才是手牌
    val hRef = referenceTileHeight(boxes.map { it.height })
    fun clusterScore(cluster: List<TileBox>): Float {
        val med = cluster.map { it.height }.sorted()[cluster.size / 2]
        val sizeRatio = if (hRef > 0) med / hRef else 1f
        return sizeRatio * minOf(cluster.size, 14)
    }
    val handIdx = pool.maxByOrNull { clusterScore(clusters[it]) }
        ?: return RecognitionResult(emptyList(), emptyList(), false)

    val hand = mutableListOf<MahjongCard>()
    hand.addAll(clusters[handIdx].sortedBy { it.minX }.map { it.card })
    val melds = mutableListOf<Meld>()
    var guessed = false

    for ((i, cluster) in clusters.withIndex()) {
        if (i == handIdx) continue
        val cards = cluster.sortedBy { it.minX }.map { it.card }
        val ms = parsed[i]
        if (ms != null) {
            melds.addAll(ms)
            if (ms.any { it.kind == Meld.Kind.CONCEALED_KONG }) guessed = true
        } else {
            // 认不准 → 并回手牌。宁可多出来让用户看见并删掉，也不能静默丢弃：
            // 真是桌上的牌时张数会对不上，由 hasValidTileCount 拦下并提示核对。
            hand.addAll(cards)
        }
    }
    return RecognitionResult(hand, melds, guessed)
}

/**
 * 把一个非手牌簇解析成一组或多组副露（桌上几组碰/杠可能紧挨着没有空隙）。
 * 按「相邻同牌」切段：3 张 = 碰、4 张 = 明杠；
 * 单张只有在 guessConcealedKong = true 时才算「只露一张的暗杠」，否则让整簇解析失败。
 * 出现 2 张同牌等认不准的段、或整簇没有一个 3/4 张的段，返回 null（交给调用方并回手牌）。
 */
/** 三张能不能凑成一副吃：同花色、数牌、点数连号。摆放次序不限（有人会摆成 5-4-6）。 */
private fun chowStart(three: List<MahjongCard>): MahjongCard? {
    if (three.size != 3) return null
    val suit = three[0].suit
    // 字牌没有顺子；花牌更不参与（这里本来也摘掉了）
    if (suit != MahjongCard.Suit.WAN && suit != MahjongCard.Suit.TONG && suit != MahjongCard.Suit.TIAO) return null
    if (three.any { it.suit != suit }) return null
    val ranks = three.map { it.rank }.sorted()
    if (ranks[1] != ranks[0] + 1 || ranks[2] != ranks[1] + 1) return null
    return MahjongCard(suit, ranks[0])
}

/**
 * 把一簇牌解析成若干副露。
 *
 * 从左往右**贪心消费**，而不是「按相同牌分段」——因为吃是三张不同的牌，
 * 分段法根本表达不了。桌上的副露本来就是一副挨一副摆的，位置次序就是分组依据。
 *
 * 匹配优先级：4 张同牌（杠）→ 3 张同牌（碰）→ 3 张连号（吃）。
 * 杠排在碰前面，否则 4 张同牌会被吃掉前 3 张当成碰、剩一张落单。
 *
 * @param allowChow 只有国标开。川麻无吃，开了会把手牌里的顺子误判成副露。
 */
private fun parseMeldRuns(
    cards: List<MahjongCard>,
    guessConcealedKong: Boolean,
    allowChow: Boolean,
): List<Meld>? {
    // 只露一张的暗杠：整簇就一张牌。仅在允许猜的时候成立。
    if (guessConcealedKong && cards.size == 1) {
        return listOf(Meld(Meld.Kind.CONCEALED_KONG, cards[0]))
    }

    val melds = mutableListOf<Meld>()
    var sawRealMeld = false      // 是否见到「实打实 3/4 张」的一副，而不是靠猜的暗杠
    var i = 0
    while (i < cards.size) {
        val rest = cards.size - i
        if (rest >= 4 && cards[i] == cards[i + 1] && cards[i] == cards[i + 2] && cards[i] == cards[i + 3]) {
            melds.add(Meld(Meld.Kind.EXPOSED_KONG, cards[i])); sawRealMeld = true; i += 4; continue
        }
        if (rest >= 3 && cards[i] == cards[i + 1] && cards[i] == cards[i + 2]) {
            melds.add(Meld(Meld.Kind.PONG, cards[i])); sawRealMeld = true; i += 3; continue
        }
        if (allowChow && rest >= 3) {
            val start = chowStart(cards.subList(i, i + 3))
            if (start != null) {
                melds.add(Meld(Meld.Kind.CHOW, start)); sawRealMeld = true; i += 3; continue
            }
        }
        // 并排两张同牌凑不成任何一副。别把它拆成两个「暗杠」——
        // 那正是实拍里凭空造出成堆杠的老毛病。
        if (rest >= 2 && cards[i] == cards[i + 1]) return null
        if (!guessConcealedKong) return null      // 不猜暗杠时，落单让整簇解析失败
        melds.add(Meld(Meld.Kind.CONCEALED_KONG, cards[i])); i += 1
    }

    // 至少要有一副真看见 3/4 张的。整簇全是「猜出来的暗杠」不作数。
    if (!sawRealMeld) return null
    return melds
}

// MARK: - 「自己的牌」所在区域

/** 纯几何矩形（不依赖 android.graphics，便于 JVM 单测）。与 iOS 的 CGRect 用法对应。 */
data class GeoRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val maxX: Float get() = x + width
    val maxY: Float get() = y + height

    fun contains(other: GeoRect): Boolean =
        other.x >= x && other.y >= y && other.maxX <= maxX && other.maxY <= maxY

    /** 相交面积；不相交为 0 */
    fun intersectionArea(other: GeoRect): Float {
        val w = minOf(maxX, other.maxX) - maxOf(x, other.x)
        val h = minOf(maxY, other.maxY) - maxOf(y, other.y)
        return if (w <= 0f || h <= 0f) 0f else w * h
    }
}

/**
 * 从检测框里圈出「自己的牌（手牌 + 碰/杠）」所在区域，相对整图返回。
 *
 * ① 按框高选出种子（见 NEAR_FIELD_RATIO）；
 * ② 闭包：反复把「已有一半以上压在当前框内」的框吸收进来，再重算框，直到不变。
 *    只吸收压在框上的，所以不会顺着弃牌堆一路蔓延出去。
 * ③ 并集外扩 REGION_PAD_TILES 个牌高。
 *
 * 与 iOS myTilesRegion 一一对应。
 */
fun myTilesRegion(boxes: List<GeoRect>, imageWidth: Float, imageHeight: Float): GeoRect? {
    if (boxes.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null

    val hRef = referenceTileHeight(boxes.map { it.height })
    if (hRef <= 0f) return null
    val minH = hRef * NEAR_FIELD_RATIO
    var keep = boxes.filter { it.height >= minH }
    if (keep.isEmpty()) return null

    for (i in 0 until 4) {
        val r = paddedUnion(keep, imageWidth, imageHeight) ?: return null
        val grown = boxes.filter { b ->
            val area = b.width * b.height
            area > 0f && r.intersectionArea(b) >= CLOSURE_OVERLAP * area
        }
        if (grown.size == keep.size) break     // 收敛（repeat + return@repeat 只会跳过本次迭代）
        keep = grown
    }
    return paddedUnion(keep, imageWidth, imageHeight)
}

/** 并集 + 按平均牌高外扩，钳在图内 */
private fun paddedUnion(boxes: List<GeoRect>, imageWidth: Float, imageHeight: Float): GeoRect? {
    if (boxes.isEmpty()) return null
    var minX = boxes[0].x
    var minY = boxes[0].y
    var maxX = boxes[0].maxX
    var maxY = boxes[0].maxY
    for (b in boxes.drop(1)) {
        minX = minOf(minX, b.x); minY = minOf(minY, b.y)
        maxX = maxOf(maxX, b.maxX); maxY = maxOf(maxY, b.maxY)
    }
    val pad = (boxes.map { it.height }.sum() / boxes.size) * REGION_PAD_TILES
    val px1 = maxOf(0f, minX - pad)
    val py1 = maxOf(0f, minY - pad)
    val px2 = minOf(imageWidth, maxX + pad)
    val py2 = minOf(imageHeight, maxY + pad)
    if (px2 - px1 <= 0f || py2 - py1 <= 0f) return null
    return GeoRect(px1, py1, px2 - px1, py2 - py1)
}

/**
 * 二次放大识别用的区域：所有达标检测框的并集 + 外扩。
 * 不做按大小的筛选——裁掉已经认出来的牌会得不偿失。
 * 区域已占满画面（放大无意义）或没有检测框时返回 null。
 */
fun zoomRegion(boxes: List<GeoRect>, imageWidth: Float, imageHeight: Float): GeoRect? {
    val r = paddedUnion(boxes, imageWidth, imageHeight) ?: return null
    if (r.width > imageWidth * 0.85f && r.height > imageHeight * 0.85f) return null
    return r
}
