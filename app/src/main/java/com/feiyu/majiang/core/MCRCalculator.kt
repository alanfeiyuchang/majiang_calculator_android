//
//  MCRCalculator.kt
//  国标麻将（MCR / 中国麻将竞赛规则）的和牌、听牌、向听、进张、打牌建议。
//  与 iOS MCRCalculator.swift 逐函数对应。
//
//  牌张：34 种 —— 0–8 万、9–17 筒、18–26 条、27–30 东南西北、31–33 中发白，各 4 张。
//  花牌（春夏秋冬梅兰竹菊）不参与和牌，单独计分，不进 34 下标。
//
//  和牌牌型：
//  ① 标准型：4 面子 + 1 将（字牌只能成刻，不能成顺）
//  ② 七对：七个对子（4 张相同按两对计；算番时可由规则细则改成不许，见 MCROptions）
//  ③ 十三幺：十三种幺九牌各一张 + 其中任一张成对
//  ④ 全不靠：三门数牌分别取 147 / 258 / 369 中互不相同的一组，加字牌，14 张互不相同、无对子
//
//  四川的「缺一门 / 花猪」在国标下完全不适用，本文件不做任何花色数量限制。
//

package com.feiyu.majiang.core

// MARK: - 34 下标工具

const val MCR_TILE_KINDS = 34

fun mcrIsHonor(i: Int): Boolean = i >= 27
fun mcrIsWind(i: Int): Boolean = i in 27..30
fun mcrIsDragon(i: Int): Boolean = i >= 31

/** 0 万 / 1 筒 / 2 条 / 3 字牌 */
fun mcrSuitOf(i: Int): Int = if (i < 27) i / 9 else 3

/** 数牌 1–9；风 1–4（东南西北）；箭 1–3（中发白） */
fun mcrRankOf(i: Int): Int = if (i < 27) i % 9 + 1 else if (i < 31) i - 26 else i - 30

fun mcrIsTerminal(i: Int): Boolean = i < 27 && (i % 9 == 0 || i % 9 == 8)
fun mcrIsTerminalOrHonor(i: Int): Boolean = mcrIsHonor(i) || mcrIsTerminal(i)

/** 顺子起点合法（同花色数牌 1–7） */
fun mcrCanStartChow(i: Int): Boolean = i < 27 && i % 9 <= 6

/** 十三幺的十三种牌 */
val MCR_THIRTEEN_ORPHANS = listOf(0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33)

// MARK: - 手牌 / 副露 → 频率数组

/** 手牌 → 长度 34 的频率数组（花牌被忽略） */
fun handToFrequency34(cards: List<MahjongCard>): IntArray {
    val c = IntArray(MCR_TILE_KINDS)
    for (card in cards) {
        val i = card.mcrIndex
        if (i >= 0) c[i] += 1
    }
    return c
}

/** 副露占用的牌 → 长度 34 的频率数组 */
fun meldsToFrequency34(melds: List<Meld>): IntArray {
    val c = IntArray(MCR_TILE_KINDS)
    for (m in melds) {
        for (tile in m.tiles) {
            val i = tile.mcrIndex
            if (i >= 0) c[i] += 1
        }
    }
    return c
}

/** 手牌里的花牌（不参与和牌，单独计分） */
fun flowerCards(cards: List<MahjongCard>): List<MahjongCard> = cards.filter { it.suit.isFlower }

// MARK: - 牌型：标准型

/** 剩余牌能否恰好拆成若干刻子/顺子（不含将）。字牌只能成刻。 */
private fun mcrMeldsBacktrack(counts: IntArray): Boolean {
    val j = counts.indexOfFirst { it > 0 }
    if (j < 0) return true

    if (counts[j] >= 3) {
        counts[j] -= 3
        if (mcrMeldsBacktrack(counts)) { counts[j] += 3; return true }
        counts[j] += 3
    }
    if (mcrCanStartChow(j) && counts[j + 1] > 0 && counts[j + 2] > 0) {
        counts[j] -= 1; counts[j + 1] -= 1; counts[j + 2] -= 1
        if (mcrMeldsBacktrack(counts)) {
            counts[j] += 1; counts[j + 1] += 1; counts[j + 2] += 1
            return true
        }
        counts[j] += 1; counts[j + 1] += 1; counts[j + 2] += 1
    }
    return false
}

/** 频率数组能否拆成「1 将 + (总数−2)/3 个面子」 */
fun mcrIsStandardForm(freq: IntArray): Boolean {
    val c = freq.copyOf()
    for (i in 0 until MCR_TILE_KINDS) {
        if (c[i] >= 2) {
            c[i] -= 2
            if (mcrMeldsBacktrack(c)) { c[i] += 2; return true }
            c[i] += 2
        }
    }
    return false
}

// MARK: - 牌型：七对 / 十三幺 / 全不靠

/**
 * 14 张是否七对。
 * allowQuadAsTwoPairs = true（默认，与多数国标实现一致）时 4 张相同按两对计；
 * false 时要求正好七种牌各两张。
 */
fun mcrIsSevenPairs(freq: IntArray, allowQuadAsTwoPairs: Boolean = true): Boolean {
    if (freq.sum() != 14) return false
    if (!freq.all { it % 2 == 0 }) return false
    if (allowQuadAsTwoPairs) return true
    return freq.all { it == 0 || it == 2 }
}

/** 连七对：同一花色 7 个连续点数各成对（1–7 / 2–8 / 3–9） */
fun mcrIsSevenShiftedPairs(freq: IntArray): Boolean {
    if (!mcrIsSevenPairs(freq)) return false
    for (suit in 0..2) {
        for (start in 0..2) {
            val base = suit * 9 + start
            val run = base until (base + 7)
            if ((0 until MCR_TILE_KINDS).all { freq[it] == (if (it in run) 2 else 0) }) return true
        }
    }
    return false
}

/** 十三幺：13 种幺九牌齐全，其中一种成对 */
fun mcrIsThirteenOrphans(freq: IntArray): Boolean {
    if (freq.sum() != 14) return false
    for (i in 0 until MCR_TILE_KINDS) {
        if (i !in MCR_THIRTEEN_ORPHANS && freq[i] > 0) return false
    }
    var pairs = 0
    for (i in MCR_THIRTEEN_ORPHANS) {
        if (freq[i] == 0) return false
        if (freq[i] == 2) pairs += 1
        if (freq[i] > 2) return false
    }
    return pairs == 1
}

/** 组合龙的三组「隔三」牌：{1,4,7} {2,5,8} {3,6,9}（花色内偏移） */
private val MCR_KNITTED_OFFSETS = listOf(listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8))

/** 三门数牌分配给三组的 6 种排列 */
private val MCR_SUIT_PERMUTATIONS = listOf(
    listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
    listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0),
)

/** 一副「不靠」牌允许出现的牌集合（给定 花色→组 的分配） */
private fun mcrKnittedAllowed(assign: List<Int>): Set<Int> {
    val s = (27 until 34).toMutableSet()
    for (suit in 0..2) {
        for (off in MCR_KNITTED_OFFSETS[assign[suit]]) s.add(suit * 9 + off)
    }
    return s
}

/** 全不靠：14 张互不相同，数牌按 147/258/369 分门取，其余为字牌 */
fun mcrIsKnittedNoSets(freq: IntArray): Boolean {
    if (freq.sum() != 14 || !freq.all { it <= 1 }) return false
    val present = (0 until MCR_TILE_KINDS).filter { freq[it] > 0 }.toSet()
    return MCR_SUIT_PERMUTATIONS.any { present.all { t -> t in mcrKnittedAllowed(it) } }
}

/** 七星不靠：全不靠 且 七种字牌齐全 */
fun mcrIsSevenStarsKnitted(freq: IntArray): Boolean =
    mcrIsKnittedNoSets(freq) && (27 until 34).all { freq[it] == 1 }

/** 手上是否含完整「组合龙」（147/258/369 分属三门，共 9 张） */
fun mcrHasKnittedStraight(freq: IntArray): Boolean {
    for (perm in MCR_SUIT_PERMUTATIONS) {
        var ok = true
        loop@ for (suit in 0..2) {
            for (off in MCR_KNITTED_OFFSETS[perm[suit]]) {
                if (freq[suit * 9 + off] == 0) { ok = false; break@loop }
            }
        }
        if (ok) return true
    }
    return false
}

/**
 * 组合龙牌型（非全不靠）：暗牌里含完整的 9 张组合龙，其余部分组成「1 将 + 剩下的面子」。
 * 组合龙的 9 张必须在手内，另一副面子可以是副露（最多 1 组）。
 */
fun mcrIsKnittedStraightForm(freq: IntArray, meldCount: Int = 0): Boolean {
    if (meldCount > 1 || freq.sum() + 3 * meldCount != 14) return false
    for (perm in MCR_SUIT_PERMUTATIONS) {
        val c = freq.copyOf()
        var ok = true
        loop@ for (suit in 0..2) {
            for (off in MCR_KNITTED_OFFSETS[perm[suit]]) {
                val i = suit * 9 + off
                if (c[i] == 0) { ok = false; break@loop }
                c[i] -= 1
            }
        }
        if (!ok) continue
        // 剩 5 张（1 面子 + 将）或 2 张（只剩将，第 4 副是副露）
        if (mcrIsStandardForm(c)) return true
    }
    return false
}

// MARK: - 和牌判定

/**
 * 暗牌（含所和那张）+ 副露 是否构成一副国标可和的牌型。
 * 特殊牌型（七对 / 十三幺 / 全不靠 / 组合龙型）要求门清且暗牌恰 14 张。
 */
fun mcrIsCompleteHand(freq: IntArray, melds: List<Meld> = emptyList()): Boolean {
    if (freq.size != MCR_TILE_KINDS) return false
    val sum = freq.sum()
    if (sum < 2 || sum % 3 != 2 || sum + 3 * melds.size > 14) return false

    if (melds.isEmpty() && sum == 14) {
        if (mcrIsSevenPairs(freq)) return true
        if (mcrIsThirteenOrphans(freq)) return true
        if (mcrIsKnittedNoSets(freq)) return true
    }
    // 组合龙型：9 张组合龙在手，第 4 副面子可以是副露
    if (melds.size <= 1 && sum + 3 * melds.size == 14 &&
        mcrIsKnittedStraightForm(freq, melds.size)
    ) {
        return true
    }
    return mcrIsStandardForm(freq)
}

/** 完整一副牌（暗牌 + 副露）是否可和 */
fun mcrIsWinningHand(freq: IntArray, melds: List<Meld> = emptyList()): Boolean =
    freq.sum() + 3 * melds.size == 14 && mcrIsCompleteHand(freq, melds)

// MARK: - 向听数

/** 标准型向听：counts 拆成「n 面子 + 1 将」最少还差几张。-1 已和，0 听牌。 */
private fun mcrStandardShanten(counts: IntArray, neededMelds: Int): Int {
    val n = neededMelds
    var best = 2 * n
    val c = counts.copyOf()

    fun dfs(start: Int, m: Int, t: Int, p: Int) {
        var i = start
        while (i < MCR_TILE_KINDS && c[i] == 0) i += 1
        if (i == MCR_TILE_KINDS) {
            var sh = 2 * n - 2 * m - t
            if (m + t == n + 1 && p == 0) sh += 1
            if (sh < best) best = sh
            return
        }
        if (m < n && c[i] >= 3) {
            c[i] -= 3; dfs(i, m + 1, t, p); c[i] += 3
        }
        if (m < n && mcrCanStartChow(i) && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i] -= 1; c[i + 1] -= 1; c[i + 2] -= 1
            dfs(i, m + 1, t, p)
            c[i] += 1; c[i + 1] += 1; c[i + 2] += 1
        }
        if (m + t < n + 1 && c[i] >= 2) {
            c[i] -= 2; dfs(i, m, t + 1, p + 1); c[i] += 2
        }
        if (m + t < n + 1 && i < 27 && i % 9 <= 7 && c[i + 1] > 0) {
            c[i] -= 1; c[i + 1] -= 1; dfs(i, m, t + 1, p); c[i] += 1; c[i + 1] += 1
        }
        if (m + t < n + 1 && mcrCanStartChow(i) && c[i + 2] > 0) {
            c[i] -= 1; c[i + 2] -= 1; dfs(i, m, t + 1, p); c[i] += 1; c[i + 2] += 1
        }
        c[i] -= 1; dfs(i, m, t, p); c[i] += 1
    }
    dfs(0, 0, 0, 0)
    return best
}

/** 七对向听（整手 13/14 张才有意义）；4 张相同按两对计 */
private fun mcrSevenPairsShanten(counts: IntArray): Int {
    var pairs = 0
    for (x in counts) pairs += x / 2
    return 6 - pairs
}

/** 十三幺向听 */
private fun mcrThirteenOrphansShanten(counts: IntArray): Int {
    var kinds = 0
    var hasPair = false
    for (i in MCR_THIRTEEN_ORPHANS) {
        if (counts[i] > 0) {
            kinds += 1
            if (counts[i] >= 2) hasPair = true
        }
    }
    return 13 - kinds - (if (hasPair) 1 else 0)
}

/** 全不靠向听 */
private fun mcrKnittedShanten(counts: IntArray): Int {
    var best = 13
    for (perm in MCR_SUIT_PERMUTATIONS) {
        val allowed = mcrKnittedAllowed(perm)
        var matched = 0
        for (i in allowed) if (counts[i] > 0) matched += 1
        best = minOf(best, 13 - minOf(matched, 13))
    }
    return best
}

/** 暗牌频率数组的向听数（含副露）。门清整手时并入七对 / 十三幺 / 全不靠。 */
fun mcrShantenOf(freq: IntArray, melds: List<Meld> = emptyList()): Int {
    val size = freq.sum()
    if (size < 1) return 8
    val n = size / 3
    var best = mcrStandardShanten(freq, n)
    if (melds.isEmpty() && (size == 13 || size == 14)) {
        best = minOf(best, mcrSevenPairsShanten(freq))
        best = minOf(best, mcrThirteenOrphansShanten(freq))
        best = minOf(best, mcrKnittedShanten(freq))
    }
    return best
}

fun mcrHandShanten(cards: List<MahjongCard>, melds: List<Meld> = emptyList()): Int =
    mcrShantenOf(handToFrequency34(cards), melds)

// MARK: - 进张 / 打牌建议 / 听牌

/** 3n+1 暗牌的进张 */
fun mcrAcceptanceTiles(cards: List<MahjongCard>, melds: List<Meld> = emptyList()): List<AcceptanceInfo> {
    val base = handToFrequency34(cards)
    val meldFreq = meldsToFrequency34(melds)
    val size = base.sum()
    if (size % 3 != 1 || size + 3 * melds.size > 13) return emptyList()
    val s0 = mcrShantenOf(base, melds)
    val result = mutableListOf<AcceptanceInfo>()
    for (i in 0 until MCR_TILE_KINDS) {
        if (base[i] + meldFreq[i] >= 4) continue
        val trial = base.copyOf()
        trial[i] += 1
        if (mcrShantenOf(trial, melds) < s0) {
            result.add(AcceptanceInfo(MahjongCard.fromMCRIndex(i), 4 - base[i] - meldFreq[i]))
        }
    }
    return result.sortedWith(compareBy(mcrCardComparator) { it.card })
}

/** 3n+2 暗牌的打牌建议 */
fun mcrDiscardSuggestions(cards: List<MahjongCard>, melds: List<Meld> = emptyList()): List<DiscardSuggestion> {
    val base = handToFrequency34(cards)
    val size = base.sum()
    if (size % 3 != 2 || size + 3 * melds.size > 14) return emptyList()

    val out = mutableListOf<DiscardSuggestion>()
    for (d in 0 until MCR_TILE_KINDS) {
        if (base[d] == 0) continue
        base[d] -= 1
        val remainingCards = (0 until MCR_TILE_KINDS).flatMap { idx ->
            List(base[idx]) { MahjongCard.fromMCRIndex(idx) }
        }
        val sh = mcrShantenOf(base, melds)
        val acc = mcrAcceptanceTiles(remainingCards, melds)
        base[d] += 1
        out.add(
            DiscardSuggestion(
                discard = MahjongCard.fromMCRIndex(d),
                resultingShanten = sh,
                acceptance = acc.map { it.card },
                acceptanceCount = acc.sumOf { it.remaining },
            )
        )
    }
    return out.sortedWith(
        compareBy<DiscardSuggestion> { it.resultingShanten }
            .thenByDescending { it.acceptanceCount }
            .thenBy { it.discard.suit.displaySortIndex }
            .thenBy { it.discard.rank }
    )
}

/** 3n+1 暗牌的听牌 */
fun mcrCalculateWaiting(cards: List<MahjongCard>, melds: List<Meld> = emptyList()): List<MahjongCard> {
    val base = handToFrequency34(cards)
    val meldFreq = meldsToFrequency34(melds)
    val n = base.sum()
    if (n % 3 != 1 || n + 3 * melds.size > 13) return emptyList()

    val waits = mutableListOf<MahjongCard>()
    for (i in 0 until MCR_TILE_KINDS) {
        if (base[i] + meldFreq[i] >= 4) continue
        val trial = base.copyOf()
        trial[i] += 1
        if (mcrIsCompleteHand(trial, melds)) waits.add(MahjongCard.fromMCRIndex(i))
    }
    return waits.sortedWith(mcrCardComparator)
}

/** 万 → 条 → 筒 → 风 → 箭 → 花，同门按点数 */
val mcrCardComparator: Comparator<MahjongCard> =
    compareBy({ it.suit.displaySortIndex }, { it.rank })
