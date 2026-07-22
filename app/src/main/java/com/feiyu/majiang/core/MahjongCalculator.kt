//
//  MahjongCalculator.kt
//  四川麻将（血战到底）听牌/胡牌判定。与 iOS MahjongCalculator.swift 一一对应。
//  牌型：仅 万/筒/条，1–9 各 4 张（无字牌、无花牌）。
//  索引 0...8 万、9...17 筒、18...26 条；每张牌 0...4 张。
//
//  胡牌成立需同时满足：
//  ① 牌型成立——标准形「1 将对 + 4 面子（刻子/顺子）」，或 七对（含龙七对）。
//  ② 缺一门——整副胡牌手牌最多只含两门花色；三门齐全即「花猪」，不能胡。
//

package com.feiyu.majiang.core

// MARK: - 手牌 → 长度 27 的频率数组

fun handToFrequency27(cards: List<MahjongCard>): IntArray {
    val c = IntArray(27)
    for (card in cards) {
        val i = card.tileIndex
        if (i in 0..26) c[i] += 1
    }
    return c
}

// MARK: - 缺一门

/** 频率数组中实际出现的花色门数（万 / 筒 / 条 各算一门） */
fun suitCount(freq: IntArray): Int {
    var n = 0
    for (suit in 0..2) {
        val base = suit * 9
        for (r in 0..8) {
            if (freq[base + r] > 0) { n += 1; break }
        }
    }
    return n
}

// MARK: - 牌型：标准形

/** 剩余牌是否恰好拆成若干刻子/顺子（不含将） */
private fun meldsBacktrack(counts: IntArray): Boolean {
    val j = counts.indexOfFirst { it > 0 }
    if (j < 0) return true

    // 优先刻子
    if (counts[j] >= 3) {
        counts[j] -= 3
        if (meldsBacktrack(counts)) {
            counts[j] += 3
            return true
        }
        counts[j] += 3
    }

    // 顺子：同花色，且点数 1–7 可作起点
    val col = j % 9
    if (col <= 6 && counts[j] > 0 && counts[j + 1] > 0 && counts[j + 2] > 0) {
        counts[j] -= 1; counts[j + 1] -= 1; counts[j + 2] -= 1
        if (meldsBacktrack(counts)) {
            counts[j] += 1; counts[j + 1] += 1; counts[j + 2] += 1
            return true
        }
        counts[j] += 1; counts[j + 1] += 1; counts[j + 2] += 1
    }

    return false
}

/** 14 张是否满足标准形「1 将对 + 4 面子」（不含缺一门约束） */
private fun isStandardForm(freq: IntArray): Boolean {
    val c = freq.copyOf()
    for (i in 0..26) {
        if (c[i] >= 2) {
            c[i] -= 2
            if (meldsBacktrack(c)) return true
            c[i] += 2
        }
    }
    return false
}

// MARK: - 牌型：七对

/**
 * 14 张是否为七对——七个对子。
 * 龙七对（4 张同牌）按两对计入，因此判定即「每种牌张数均为偶数」。
 */
private fun isSevenPairs(freq: IntArray): Boolean = freq.all { it % 2 == 0 }

// MARK: - 胡牌判定

/**
 * 暗牌频率数组（可含副露）是否构成一副可胡的牌：牌型成立 且 满足缺一门。
 * 缺一门按「暗牌 + 副露」合并判定；七对要求门清且恰 14 张。
 */
fun isCompleteHand(freq: IntArray, melds: List<Meld> = emptyList()): Boolean {
    if (freq.size != 27) return false
    val sum = freq.sum()
    if (!(sum >= 2 && sum % 3 == 2 && sum + 3 * melds.size <= 14)) return false

    // 缺一门：手牌 + 副露合并后三门齐全为「花猪」，不能胡
    val combined = freq.copyOf()
    for (m in melds) combined[m.card.tileIndex] += m.tileCount
    if (suitCount(combined) > 2) return false

    if (melds.isEmpty() && sum == 14 && isSevenPairs(freq)) return true
    return isStandardForm(freq)
}

/** 完整一副牌（暗牌 + 副露）是否可胡（四川麻将） */
fun isWinningHand(freq: IntArray, melds: List<Meld> = emptyList()): Boolean =
    freq.sum() + 3 * melds.size == 14 && isCompleteHand(freq, melds)

// MARK: - 向听数 / 进张 / 打牌建议

/**
 * 标准型向听数：counts 拆成「n 面子 + 1 将」最少还差几张。
 * -1 表示已和，0 表示听牌。neededMelds = 总张数 / 3。
 */
private fun standardShantenN(counts: IntArray, neededMelds: Int): Int {
    val n = neededMelds
    var best = 2 * n
    val c = counts.copyOf()

    fun dfs(start: Int, m: Int, t: Int, p: Int) {
        var i = start
        while (i < 27 && c[i] == 0) i += 1
        if (i == 27) {
            var sh = 2 * n - 2 * m - t
            if (m + t == n + 1 && p == 0) sh += 1   // n+1 块但无将 → +1
            if (sh < best) best = sh
            return
        }
        val col = i % 9
        // 刻子（最多 n 个面子）
        if (m < n && c[i] >= 3) {
            c[i] -= 3; dfs(i, m + 1, t, p); c[i] += 3
        }
        // 顺子
        if (m < n && col <= 6 && c[i] > 0 && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i] -= 1; c[i + 1] -= 1; c[i + 2] -= 1
            dfs(i, m + 1, t, p)
            c[i] += 1; c[i + 1] += 1; c[i + 2] += 1
        }
        // 搭子（面子+搭子最多 n+1 块）：对子（可作将）
        if (m + t < n + 1 && c[i] >= 2) {
            c[i] -= 2; dfs(i, m, t + 1, p + 1); c[i] += 2
        }
        // 搭子：两面 / 嵌张
        if (m + t < n + 1 && col <= 7 && c[i] > 0 && c[i + 1] > 0) {
            c[i] -= 1; c[i + 1] -= 1; dfs(i, m, t + 1, p); c[i] += 1; c[i + 1] += 1
        }
        if (m + t < n + 1 && col <= 6 && c[i] > 0 && c[i + 2] > 0) {
            c[i] -= 1; c[i + 2] -= 1; dfs(i, m, t + 1, p); c[i] += 1; c[i + 2] += 1
        }
        // 孤张：弃掉一张
        c[i] -= 1; dfs(i, m, t, p); c[i] += 1
    }
    dfs(0, 0, 0, 0)
    return best
}

/**
 * 七对向听数（仅整手 13/14 张有意义）。
 * 四川麻将认龙七对：4 张同牌算两对，故按 Σ⌊张数/2⌋ 计对子数，无「七门」限制。
 */
private fun chiitoiShanten(counts: IntArray): Int {
    var pairs = 0
    for (x in counts) pairs += x / 2
    return 6 - pairs
}

/**
 * 暗牌频率数组的向听数（含缺一门：取「保留两门」的最优；门清整手时并入七对）。
 * 副露的花色已固定在成品牌里，「打缺哪一门」只能选副露没占的花色；
 * 副露若已含三门花色则永远是花猪，不可能胡。
 */
fun shantenOf(freq: IntArray, melds: List<Meld> = emptyList()): Int {
    val size = freq.sum()
    if (size < 1) return 8
    val meldSuits = melds.map { it.card.tileIndex / 9 }.toSet()
    if (meldSuits.size > 2) return 8
    val n = size / 3
    val whole = melds.isEmpty() && (size == 13 || size == 14)
    var best = Int.MAX_VALUE
    for (drop in 0..2) {
        if (drop in meldSuits) continue
        val c = freq.copyOf()
        for (r in 0..8) c[drop * 9 + r] = 0
        best = minOf(best, standardShantenN(c, n))
        if (whole) best = minOf(best, chiitoiShanten(c))
    }
    return best
}

/** 手牌向听数 */
fun handShanten(cards: List<MahjongCard>, melds: List<Meld> = emptyList()): Int =
    shantenOf(handToFrequency27(cards), melds)

/** 一张进张牌及其剩余张数 */
data class AcceptanceInfo(val card: MahjongCard, val remaining: Int)

private val tileDisplayComparator = Comparator<MahjongCard> { a, b ->
    if (a.suit.displaySortIndex != b.suit.displaySortIndex)
        a.suit.displaySortIndex - b.suit.displaySortIndex
    else a.rank - b.rank
}

/**
 * 3n+1 暗牌的进张：加入后能降低向听的牌，
 * 及各自剩余张数（4 − 手中张数 − 副露占用张数）
 */
fun acceptanceTiles(cards: List<MahjongCard>, melds: List<Meld> = emptyList()): List<AcceptanceInfo> {
    val base = handToFrequency27(cards)
    val meldFreq = meldsToFrequency27(melds)
    val size = base.sum()
    if (!(size % 3 == 1 && size + 3 * melds.size <= 13)) return emptyList()
    val s0 = shantenOf(base, melds)
    val result = mutableListOf<AcceptanceInfo>()
    for (i in 0..26) {
        if (base[i] + meldFreq[i] >= 4) continue
        val trial = base.copyOf()
        trial[i] += 1
        if (shantenOf(trial, melds) < s0) {
            result.add(AcceptanceInfo(MahjongCard.fromTileIndex(i), 4 - base[i] - meldFreq[i]))
        }
    }
    return result.sortedWith(compareBy(tileDisplayComparator) { it.card })
}

/** 一个弃牌方案 */
data class DiscardSuggestion(
    val discard: MahjongCard,
    val resultingShanten: Int,
    val acceptance: List<MahjongCard>,
    /** 进张总张数（各进张牌剩余张数之和） */
    val acceptanceCount: Int,
)

/** 3n+2 暗牌的打牌建议：每个可弃的牌 → 弃后向听 + 进张，按「向听升序、进张降序」排序 */
fun discardSuggestions(cards: List<MahjongCard>, melds: List<Meld> = emptyList()): List<DiscardSuggestion> {
    val base = handToFrequency27(cards)
    val size = base.sum()
    if (!(size % 3 == 2 && size + 3 * melds.size <= 14)) return emptyList()

    val out = mutableListOf<DiscardSuggestion>()
    for (d in 0..26) {
        if (base[d] <= 0) continue
        base[d] -= 1
        val remainingCards = (0..26).flatMap { idx ->
            List(base[idx]) { MahjongCard.fromTileIndex(idx) }
        }
        val sh = shantenOf(base, melds)
        val acc = acceptanceTiles(remainingCards, melds)
        base[d] += 1
        out.add(
            DiscardSuggestion(
                discard = MahjongCard.fromTileIndex(d),
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

// MARK: - 听牌

/**
 * 当前 3n+1 张暗牌（副露 m 组时 n = 4 − m，整手即 13 − 3m 张），
 * 枚举加入哪一张后能凑成「1 将对 + n 面子」（标准形 / 七对，且满足缺一门）。
 * 手中 + 副露已用满 4 张的牌不可能再摸到，不计入听牌。
 */
fun calculateWaiting(cards: List<MahjongCard>, melds: List<Meld> = emptyList()): List<MahjongCard> {
    val base = handToFrequency27(cards)
    val meldFreq = meldsToFrequency27(melds)
    val n = base.sum()
    if (!(n % 3 == 1 && n + 3 * melds.size <= 13)) return emptyList()

    val waits = mutableListOf<MahjongCard>()
    for (i in 0..26) {
        if (base[i] + meldFreq[i] >= 4) continue
        val trial = base.copyOf()
        trial[i] += 1
        if (isCompleteHand(trial, melds)) waits.add(MahjongCard.fromTileIndex(i))
    }
    return waits.sortedWith(tileDisplayComparator)
}
