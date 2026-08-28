//
//  MCRScoring.kt
//  国标麻将（MCR / 中国麻将竞赛规则）81 种番型的算番引擎，起和 8 分。
//  与 iOS MCRScoring.swift 逐函数对应。
//
//  ── 不重复计算原则 ────────────────────────────────────────────────
//  国标算番不是把番型堆起来，必须遵守四条原则，本文件的实现方式如下：
//
//  1. 不可拆分原则：已组成某番的面子不可再拆开去凑另一个番。
//     → 面子结构类番型（一般高/喜相逢/连六/老少副/双同刻/清龙/一色三同顺…）
//       通过「把 4 副面子做集合划分、每块取最高番、取总分最大的划分」来计算，
//       一副面子只可能落进一个块里，天然不可拆分。
//
//  2. 不可重复原则：同一组合只能计一次。
//     → 由 MCR_FAN_EXCLUDES 排除表实现：高番型出现时，把它已经«包含»的
//       低番型整条删掉（如 大三元 出现即删掉 箭刻 / 双箭刻）。
//
//  3. 就高不就低：一种组合能算多个番型时取最高的。
//     → 集合划分里每块取「该块能成立的最高番」；整手牌层面则对所有
//       和牌牌型 × 所有拆解方式 × 和牌张归属 取总分最大者。
//
//  4. 套算一次原则：一副面子与别的面子「配对」只能配一次。
//     → 同样由集合划分保证：两两配对型番（一般高等）在划分中是大小为 2 的块，
//       一副面子不可能同时进两个块。
//
//  ── 各地规则书有分歧的地方 ────────────────────────────────────────
//  五处分歧做成了用户可选项，见 MCROptions；默认值 = 上面描述的行为。
//
//  ── 其余取舍 ──────────────────────────────────────────────────────
//  · 杠的番：四杠 88（独占）；三杠 32（+ 每个杠各自的 明杠 1 / 暗杠 2，可关）；
//    两个及以下时 双明杠 4（吃掉两个明杠）、双暗杠 6（吃掉两个暗杠）。
//  · 无番和：除 花牌、自摸 外没有任何番型时计 8 分。
//  · 和绝张、妙手回春、海底捞月、抢杠和 等场景番无法从牌面推断，由用户勾选。
//

package com.feiyu.majiang.core

// MARK: - 规则细则（各地规则书有分歧的地方）

/**
 * 国标里各地打法不一致、需要用户拍板的五处判定。
 * 字段名与持久化的设置键（RuleSettings 同名字段）严格一致，两端（iOS / Android）通用。
 * 默认值 = 本引擎的既有行为。
 */
data class MCROptions(
    /** 字一色是否同时计混幺九（+32） */
    val mcrZiYiSeCountsHunYaoJiu: Boolean = true,
    /** 九莲宝灯是否同时计双暗刻（+2） */
    val mcrJiuLianCountsShuangAnKe: Boolean = true,
    /** 七对中四张相同是否可当两对 */
    val mcrSevenPairsAllowsQuadAsTwoPairs: Boolean = true,
    /** 三杠时是否再单独计每个杠（明杠 1 / 暗杠 2） */
    val mcrPerKongFanWithThreeKongs: Boolean = true,
    /**
     * 边张 / 坎张 / 单钓将的读法：
     * true  = 就高不就低，跨所有拆解取最优的那种听法；
     * false = 只有当所有拆解读出同一种听法时才计（听法不唯一就不计）。
     */
    val mcrWaitFanHighestReading: Boolean = true,
)

// MARK: - 面子

data class MCRSet(
    val kind: Kind,
    /** 顺子存起始牌下标；刻/杠/将存牌本身 */
    val tile: Int,
    /** 结构上是「暗」的（手内的牌，或暗杠） */
    val concealed: Boolean,
) {
    enum class Kind { CHOW, PUNG, KONG, PAIR }

    val isPungLike: Boolean get() = kind == Kind.PUNG || kind == Kind.KONG
    val suit: Int get() = mcrSuitOf(tile)
    val rank: Int get() = mcrRankOf(tile)

    /** 这副面子覆盖的牌下标 */
    val tiles: List<Int>
        get() = when (kind) {
            Kind.CHOW -> listOf(tile, tile + 1, tile + 2)
            Kind.PUNG -> listOf(tile, tile, tile)
            Kind.KONG -> listOf(tile, tile, tile, tile)
            Kind.PAIR -> listOf(tile, tile)
        }
}

// MARK: - 和牌场景

/** 国标胡牌瞬间的场景信息 */
data class MCRContext(
    val selfDrawn: Boolean = false,
    /** 和的那张牌（34 下标）；-1 表示未知（如仅做打牌建议的粗估） */
    val winningTile: Int = -1,
    /** 圈风 0–3 = 东南西北 */
    val prevalentWind: Int = 0,
    /** 门风 0–3 = 东南西北 */
    val seatWind: Int = 0,
    /** 杠上开花（自摸侧） */
    val kongBloom: Boolean = false,
    /** 妙手回春：自摸牌墙最后一张 */
    val lastTileDraw: Boolean = false,
    /** 海底捞月：和最后一张打出的牌 */
    val lastDiscard: Boolean = false,
    /** 抢杠和 */
    val robbingKong: Boolean = false,
    /** 和绝张：和的这张牌是明面上的第 4 张 */
    val lastTileOfKind: Boolean = false,
    /** 花牌张数 */
    val flowers: Int = 0,
)

// MARK: - 算番结果

data class MCRScore(
    val items: List<FanItem>,
    /** 含花牌的总分 */
    val totalPoints: Int,
    /** 不含花牌的分（起和线按这个算） */
    val scoringPoints: Int,
) {
    /** 是否达到起和 8 分 */
    val meetsMinimum: Boolean get() = scoringPoints >= MCR_MINIMUM_POINTS
}

/** 起和分 */
const val MCR_MINIMUM_POINTS = 8

// MARK: - 番种分值表（81 种）

val MCR_FAN_POINTS: Map<String, Int> = mapOf(
    // 88
    "大四喜" to 88, "大三元" to 88, "绿一色" to 88, "九莲宝灯" to 88,
    "四杠" to 88, "连七对" to 88, "十三幺" to 88,
    // 64
    "清幺九" to 64, "小四喜" to 64, "小三元" to 64, "字一色" to 64,
    "四暗刻" to 64, "一色双龙会" to 64,
    // 48
    "一色四同顺" to 48, "一色四节高" to 48,
    // 32
    "一色四步高" to 32, "三杠" to 32, "混幺九" to 32,
    // 24
    "七对" to 24, "七星不靠" to 24, "全双刻" to 24, "清一色" to 24,
    "一色三同顺" to 24, "一色三节高" to 24, "全大" to 24, "全中" to 24, "全小" to 24,
    // 16
    "清龙" to 16, "三色双龙会" to 16, "一色三步高" to 16, "全带五" to 16,
    "三同刻" to 16, "三暗刻" to 16,
    // 12
    "全不靠" to 12, "组合龙" to 12, "大于五" to 12, "小于五" to 12, "三风刻" to 12,
    // 8
    "花龙" to 8, "推不倒" to 8, "三色三同顺" to 8, "三色三节高" to 8, "无番和" to 8,
    "妙手回春" to 8, "海底捞月" to 8, "杠上开花" to 8, "抢杠和" to 8,
    // 6
    "碰碰和" to 6, "混一色" to 6, "三色三步高" to 6, "五门齐" to 6,
    "全求人" to 6, "双暗杠" to 6, "双箭刻" to 6,
    // 4
    "全带幺" to 4, "不求人" to 4, "双明杠" to 4, "和绝张" to 4,
    // 2
    "箭刻" to 2, "圈风刻" to 2, "门风刻" to 2, "门前清" to 2, "平和" to 2,
    "四归一" to 2, "双同刻" to 2, "双暗刻" to 2, "暗杠" to 2, "断幺" to 2,
    // 1
    "一般高" to 1, "喜相逢" to 1, "连六" to 1, "老少副" to 1, "幺九刻" to 1,
    "明杠" to 1, "缺一门" to 1, "无字" to 1, "边张" to 1, "坎张" to 1,
    "单钓将" to 1, "自摸" to 1, "花牌" to 1,
)

/** 不可重复原则的排除表：key 番型成立时，把 value 里的番型整条删掉 */
val MCR_FAN_EXCLUDES: Map<String, List<String>> = mapOf(
    "大四喜" to listOf("三风刻", "圈风刻", "门风刻", "碰碰和", "幺九刻"),
    "大三元" to listOf("箭刻", "双箭刻"),
    "绿一色" to listOf("缺一门"),
    "九莲宝灯" to listOf("清一色", "门前清", "无字", "幺九刻"),
    "四杠" to listOf("三杠", "双明杠", "双暗杠", "明杠", "暗杠", "碰碰和", "单钓将"),
    "连七对" to listOf("七对", "清一色", "门前清", "无字", "单钓将", "不求人"),
    "十三幺" to listOf("五门齐", "门前清", "单钓将", "不求人"),

    "清幺九" to listOf("碰碰和", "全带幺", "幺九刻", "无字"),
    "小四喜" to listOf("三风刻"),
    "小三元" to listOf("双箭刻", "箭刻"),
    "字一色" to listOf("碰碰和", "全带幺", "幺九刻", "缺一门", "无字"),
    "四暗刻" to listOf("碰碰和", "门前清", "三暗刻", "双暗刻"),
    "一色双龙会" to listOf("清一色", "平和", "一般高", "老少副", "喜相逢"),

    "一色四同顺" to listOf("一色三同顺", "一般高", "四归一"),
    "一色四节高" to listOf("一色三节高", "碰碰和"),

    "一色四步高" to listOf("一色三步高", "连六", "老少副"),
    "三杠" to listOf("双明杠", "双暗杠"),
    "混幺九" to listOf("碰碰和", "全带幺", "幺九刻"),

    "七对" to listOf("门前清", "单钓将"),
    "七星不靠" to listOf("全不靠", "五门齐", "门前清", "不求人"),
    "全双刻" to listOf("碰碰和", "断幺"),
    "清一色" to listOf("无字", "缺一门"),
    "一色三同顺" to listOf("一般高"),
    "全大" to listOf("无字"),
    "全中" to listOf("无字", "断幺"),
    "全小" to listOf("无字"),

    "清龙" to listOf("连六", "老少副"),
    "三色双龙会" to listOf("喜相逢", "老少副", "无字", "平和"),
    "一色三步高" to listOf("连六", "老少副"),
    "全带五" to listOf("断幺", "无字"),
    "三同刻" to listOf("双同刻"),
    "三暗刻" to listOf("双暗刻"),

    "全不靠" to listOf("五门齐", "门前清", "不求人"),
    "大于五" to listOf("无字"),
    "小于五" to listOf("无字"),

    "推不倒" to listOf("缺一门"),
    "三色三同顺" to listOf("喜相逢"),
    "三色三节高" to listOf("双同刻"),
    "妙手回春" to listOf("自摸"),
    "杠上开花" to listOf("自摸"),
    "抢杠和" to listOf("和绝张"),

    "混一色" to listOf("缺一门"),
    "全求人" to listOf("单钓将", "门前清"),
    "双暗杠" to listOf("暗杠"),
    "双箭刻" to listOf("箭刻"),

    "不求人" to listOf("自摸", "门前清"),
    "双明杠" to listOf("明杠"),
)

/** 按规则细则展开后的排除表：关掉「同时计」时，高番型把对应低番型也吃掉 */
fun mcrFanExcludes(options: MCROptions): Map<String, List<String>> {
    if (options.mcrZiYiSeCountsHunYaoJiu && options.mcrJiuLianCountsShuangAnKe) return MCR_FAN_EXCLUDES
    val m = MCR_FAN_EXCLUDES.toMutableMap()
    if (!options.mcrZiYiSeCountsHunYaoJiu) {
        m["字一色"] = (m["字一色"] ?: emptyList()) + "混幺九"
    }
    if (!options.mcrJiuLianCountsShuangAnKe) {
        m["九莲宝灯"] = (m["九莲宝灯"] ?: emptyList()) + "双暗刻"
    }
    return m
}

// MARK: - 一次命中

private data class FanHit(val name: String, val count: Int = 1)

private fun points(name: String): Int = MCR_FAN_POINTS[name] ?: 0

/** 应用排除表并折算成 FanItem 列表 + 总分 */
private fun mcrFinalize(hits: List<FanHit>, flowers: Int, options: MCROptions): MCRScore {
    val merged = LinkedHashMap<String, Int>()
    for (h in hits) {
        if (h.count <= 0) continue
        merged[h.name] = (merged[h.name] ?: 0) + h.count
    }

    // 不可重复原则：按原始命中集合一次性删除被包含的低番型
    val excludes = mcrFanExcludes(options)
    val removed = mutableSetOf<String>()
    for (name in merged.keys) {
        for (victim in excludes[name] ?: emptyList()) {
            if (victim != name) removed.add(victim)
        }
    }
    for (name in removed) merged.remove(name)

    // 无番和：除花牌、自摸外没有任何番型
    val coreTotal = merged.entries.sumOf { (name, n) -> if (name == "自摸") 0 else points(name) * n }
    val order = mutableListOf<String>()
    if (coreTotal == 0) {
        order.add("无番和")
        merged["无番和"] = 1
    }
    order.addAll(merged.keys.filter { it != "无番和" })

    val items = order.map { name ->
        val n = merged[name] ?: 0
        FanItem(name = name, fan = points(name) * n, count = n)
    }.toMutableList()
    val scoringPoints = items.sumOf { it.fan }
    if (flowers > 0) {
        items.add(FanItem(name = "花牌", fan = points("花牌") * flowers, count = flowers))
    }
    return MCRScore(
        items = items,
        totalPoints = scoringPoints + points("花牌") * flowers,
        scoringPoints = scoringPoints,
    )
}

// MARK: - 拆解

/** 把副露转成面子 */
fun mcrMeldSets(melds: List<Meld>): List<MCRSet> = melds.mapNotNull { m ->
    val i = m.card.mcrIndex
    if (i < 0) null else when (m.kind) {
        Meld.Kind.CHOW -> MCRSet(MCRSet.Kind.CHOW, i, concealed = false)
        Meld.Kind.PONG -> MCRSet(MCRSet.Kind.PUNG, i, concealed = false)
        Meld.Kind.EXPOSED_KONG -> MCRSet(MCRSet.Kind.KONG, i, concealed = false)
        Meld.Kind.CONCEALED_KONG -> MCRSet(MCRSet.Kind.KONG, i, concealed = true)
    }
}

/** 暗牌拆解结果：1 将 + 若干手内面子 */
data class MCRDecomposition(val pair: MCRSet, val handSets: List<MCRSet>)

private fun enumerateHandSets(c: IntArray, acc: MutableList<MCRSet>, need: Int, out: MutableList<List<MCRSet>>) {
    if (acc.size == need) {
        if (c.all { it == 0 }) out.add(acc.toList())
        return
    }
    val i = c.indexOfFirst { it > 0 }
    if (i < 0) return
    if (c[i] >= 3) {
        c[i] -= 3
        acc.add(MCRSet(MCRSet.Kind.PUNG, i, concealed = true))
        enumerateHandSets(c, acc, need, out)
        acc.removeAt(acc.lastIndex); c[i] += 3
    }
    if (mcrCanStartChow(i) && c[i + 1] > 0 && c[i + 2] > 0) {
        c[i] -= 1; c[i + 1] -= 1; c[i + 2] -= 1
        acc.add(MCRSet(MCRSet.Kind.CHOW, i, concealed = true))
        enumerateHandSets(c, acc, need, out)
        acc.removeAt(acc.lastIndex)
        c[i] += 1; c[i + 1] += 1; c[i + 2] += 1
    }
}

/** 暗牌的全部「1 将 + n 面子」拆解 */
fun mcrDecompose(concealed: IntArray, meldCount: Int): List<MCRDecomposition> {
    val need = 4 - meldCount
    if (need < 0) return emptyList()
    val out = mutableListOf<MCRDecomposition>()
    val seen = mutableSetOf<String>()
    for (p in 0 until MCR_TILE_KINDS) {
        if (concealed[p] < 2) continue
        val c = concealed.copyOf()
        c[p] -= 2
        val acc = mutableListOf<MCRSet>()
        val sets = mutableListOf<List<MCRSet>>()
        enumerateHandSets(c, acc, need, sets)
        for (s in sets) {
            val key = "$p|" + s.map { "${it.kind}${it.tile}" }.sorted().joinToString(",")
            if (!seen.add(key)) continue
            out.add(MCRDecomposition(MCRSet(MCRSet.Kind.PAIR, p, concealed = true), s))
        }
    }
    return out
}

// MARK: - 整手牌属性判定

private class TileStats(val freq: IntArray) {
    val present: List<Int> = (0 until MCR_TILE_KINDS).filter { freq[it] > 0 }
    val numberedSuits: Set<Int> = present.filter { it < 27 }.map { it / 9 }.toSet()
    val hasHonor: Boolean = present.any { mcrIsHonor(it) }
    val hasWind: Boolean = present.any { mcrIsWind(it) }
    val hasDragon: Boolean = present.any { mcrIsDragon(it) }

    fun allRanksIn(set: Set<Int>): Boolean = !hasHonor && present.all { mcrRankOf(it) in set }
}

/** 2/3/4/6/8 条 + 发 */
private val MCR_GREEN_TILES = setOf(19, 20, 21, 23, 25, 32)

private val MCR_REVERSIBLE_TILES = setOf(
    9, 10, 11, 12, 13, 16, 17,      // 1/2/3/4/5/8/9 筒
    19, 21, 22, 23, 25, 26,         // 2/4/5/6/8/9 条
    33,                              // 白
)

/** 整手牌层面的番（与拆解方式无关的部分） */
private fun mcrWholeHandFan(stats: TileStats): List<FanHit> {
    val hits = mutableListOf<FanHit>()
    val allHonor = stats.present.all { mcrIsHonor(it) }

    if (allHonor) {
        hits.add(FanHit("字一色"))
    } else if (stats.numberedSuits.size == 1) {
        hits.add(FanHit(if (stats.hasHonor) "混一色" else "清一色"))
    }
    if (stats.numberedSuits.size == 2) hits.add(FanHit("缺一门"))
    if (!stats.hasHonor) hits.add(FanHit("无字"))
    if (stats.numberedSuits.size == 3 && stats.hasWind && stats.hasDragon) hits.add(FanHit("五门齐"))
    if (stats.present.none { mcrIsTerminalOrHonor(it) }) hits.add(FanHit("断幺"))
    if (MCR_GREEN_TILES.containsAll(stats.present)) hits.add(FanHit("绿一色"))
    if (MCR_REVERSIBLE_TILES.containsAll(stats.present)) hits.add(FanHit("推不倒"))
    if (stats.allRanksIn(setOf(7, 8, 9))) hits.add(FanHit("全大"))
    if (stats.allRanksIn(setOf(4, 5, 6))) hits.add(FanHit("全中"))
    if (stats.allRanksIn(setOf(1, 2, 3))) hits.add(FanHit("全小"))
    if (stats.allRanksIn(setOf(6, 7, 8, 9))) hits.add(FanHit("大于五"))
    if (stats.allRanksIn(setOf(1, 2, 3, 4))) hits.add(FanHit("小于五"))
    return hits
}

/** 九莲宝灯：门清、一门数牌、1112345678999 + 任意一张 */
private fun mcrIsNineGates(freq: IntArray, melds: List<Meld>): Boolean {
    if (melds.isNotEmpty() || freq.sum() != 14) return false
    val suits = (0 until 27).filter { freq[it] > 0 }.map { it / 9 }.toSet()
    if (suits.size != 1 || (27 until 34).any { freq[it] != 0 }) return false
    val base = suits.first() * 9
    val pattern = intArrayOf(3, 1, 1, 1, 1, 1, 1, 1, 3)
    var extra = 0
    for (r in 0..8) {
        val d = freq[base + r] - pattern[r]
        if (d < 0) return false
        extra += d
    }
    return extra == 1
}

// MARK: - 面子结构番：集合划分

/** {0,1,2,3} 的全部集合划分（Bell(4) = 15） */
private val MCR_PARTITIONS_OF_4: List<List<List<Int>>> = buildList {
    fun build(i: Int, blocks: List<List<Int>>) {
        if (i == 4) { add(blocks); return }
        for (b in blocks.indices) {
            val next = blocks.toMutableList()
            next[b] = next[b] + i
            build(i + 1, next)
        }
        build(i + 1, blocks + listOf(listOf(i)))
    }
    build(0, emptyList())
}

private fun mcrBestFanForBlock(block: List<MCRSet>, pair: MCRSet): Pair<String, Int>? {
    var best: Pair<String, Int>? = null
    fun offer(name: String) {
        val p = points(name)
        if (best == null || p > best!!.second) best = name to p
    }

    val chows = block.filter { it.kind == MCRSet.Kind.CHOW }
    val pungs = block.filter { it.isPungLike }

    when (block.size) {
        4 -> {
            if (chows.size == 4) {
                val suits = chows.map { it.suit }.toSet()
                val starts = chows.map { it.rank }.sorted()
                if (suits.size == 1) {
                    if (starts == listOf(1, 1, 7, 7) && pair.suit == chows[0].suit && pair.rank == 5) {
                        offer("一色双龙会")
                    }
                    if (starts.toSet().size == 1) offer("一色四同顺")
                    val d = starts[1] - starts[0]
                    if ((d == 1 || d == 2) && starts[2] - starts[1] == d && starts[3] - starts[2] == d) {
                        offer("一色四步高")
                    }
                } else if (suits.size == 2) {
                    // 三色双龙会：两门各 123+789，将是第三门的 5
                    var ok = true
                    for (s in suits) {
                        val inSuit = chows.filter { it.suit == s }.map { it.rank }.sorted()
                        if (inSuit != listOf(1, 7)) ok = false
                    }
                    if (ok && pair.rank == 5 && mcrSuitOf(pair.tile) < 3 && pair.suit !in suits) {
                        offer("三色双龙会")
                    }
                }
            }
            if (pungs.size == 4) {
                val suits = pungs.map { it.suit }.toSet()
                val ranks = pungs.map { it.rank }.sorted()
                if (suits.size == 1 && suits.first() != 3 &&
                    ranks[1] == ranks[0] + 1 && ranks[2] == ranks[0] + 2 && ranks[3] == ranks[0] + 3
                ) {
                    offer("一色四节高")
                }
            }
        }
        3 -> {
            if (chows.size == 3) {
                val suits = chows.map { it.suit }.toSet()
                val starts = chows.map { it.rank }.sorted()
                if (suits.size == 1) {
                    if (starts.toSet().size == 1) offer("一色三同顺")
                    if (starts == listOf(1, 4, 7)) offer("清龙")
                    val d = starts[1] - starts[0]
                    if ((d == 1 || d == 2) && starts[2] - starts[1] == d) offer("一色三步高")
                } else if (suits.size == 3) {
                    if (starts.toSet().size == 1) offer("三色三同顺")
                    if (starts == listOf(1, 4, 7)) offer("花龙")
                    if (starts[1] == starts[0] + 1 && starts[2] == starts[0] + 2) offer("三色三步高")
                }
            }
            if (pungs.size == 3) {
                val suits = pungs.map { it.suit }.toSet()
                val ranks = pungs.map { it.rank }.sorted()
                if (suits.size == 1 && suits.first() != 3 &&
                    ranks[1] == ranks[0] + 1 && ranks[2] == ranks[0] + 2
                ) {
                    offer("一色三节高")
                }
                if (suits.size == 3 && 3 !in suits) {
                    if (ranks.toSet().size == 1) offer("三同刻")
                    if (ranks[1] == ranks[0] + 1 && ranks[2] == ranks[0] + 2) offer("三色三节高")
                }
            }
        }
        2 -> {
            if (pungs.size == 2 && pungs[0].suit != pungs[1].suit &&
                pungs.none { it.suit == 3 } && pungs[0].rank == pungs[1].rank
            ) {
                offer("双同刻")
            }
            if (chows.size == 2) {
                val a = chows[0]
                val b = chows[1]
                if (a.suit == b.suit) {
                    if (a.rank == b.rank) offer("一般高")
                    if (kotlin.math.abs(a.rank - b.rank) == 3) offer("连六")
                    if (setOf(a.rank, b.rank) == setOf(1, 7)) offer("老少副")
                } else if (a.rank == b.rank) {
                    offer("喜相逢")
                }
            }
        }
    }
    return best
}

/** 面子结构番：取「总分最高的集合划分」，实现不可拆分 / 套算一次 / 就高不就低 */
private fun mcrStructureFan(sets: List<MCRSet>, pair: MCRSet): List<FanHit> {
    if (sets.size != 4) return emptyList()
    var bestTotal = -1
    var bestHits: List<FanHit> = emptyList()
    for (partition in MCR_PARTITIONS_OF_4) {
        var total = 0
        val hits = mutableListOf<FanHit>()
        for (block in partition) {
            val blockSets = block.map { sets[it] }
            val hit = mcrBestFanForBlock(blockSets, pair)
            if (hit != null) {
                total += hit.second
                hits.add(FanHit(hit.first))
            }
        }
        if (total > bestTotal) {
            bestTotal = total
            bestHits = hits
        }
    }
    return bestHits
}

// MARK: - 字牌番 / 暗刻番 / 杠番

private fun mcrHonorFan(sets: List<MCRSet>, pair: MCRSet, ctx: MCRContext): List<FanHit> {
    val hits = mutableListOf<FanHit>()
    val pungs = sets.filter { it.isPungLike }
    val windPungs = pungs.filter { mcrIsWind(it.tile) }
    val dragonPungs = pungs.filter { mcrIsDragon(it.tile) }

    if (windPungs.size == 4) {
        hits.add(FanHit("大四喜"))
    } else if (windPungs.size == 3) {
        hits.add(FanHit(if (mcrIsWind(pair.tile)) "小四喜" else "三风刻"))
    }
    if (dragonPungs.size == 3) {
        hits.add(FanHit("大三元"))
    } else if (dragonPungs.size == 2) {
        if (mcrIsDragon(pair.tile)) hits.add(FanHit("小三元"))
        hits.add(FanHit("双箭刻"))
    }
    if (dragonPungs.isNotEmpty()) hits.add(FanHit("箭刻", dragonPungs.size))
    if (windPungs.any { it.tile == 27 + ctx.prevalentWind }) hits.add(FanHit("圈风刻"))
    if (windPungs.any { it.tile == 27 + ctx.seatWind }) hits.add(FanHit("门风刻"))
    val yaojiuPungs = pungs.count { mcrIsTerminal(it.tile) || mcrIsWind(it.tile) }
    if (yaojiuPungs > 0) hits.add(FanHit("幺九刻", yaojiuPungs))
    return hits
}

/** 暗刻 / 杠。`concealed` 已把「点炮成刻」降为明刻。 */
private fun mcrConcealmentFan(sets: List<MCRSet>, options: MCROptions): List<FanHit> {
    val hits = mutableListOf<FanHit>()
    when (sets.count { it.isPungLike && it.concealed }) {
        4 -> hits.add(FanHit("四暗刻"))
        3 -> hits.add(FanHit("三暗刻"))
        2 -> hits.add(FanHit("双暗刻"))
    }

    val kongs = sets.filter { it.kind == MCRSet.Kind.KONG }
    val exposed = kongs.count { !it.concealed }
    val hidden = kongs.size - exposed
    when (kongs.size) {
        4 -> hits.add(FanHit("四杠"))
        3 -> {
            hits.add(FanHit("三杠"))
            if (options.mcrPerKongFanWithThreeKongs) {
                if (exposed > 0) hits.add(FanHit("明杠", exposed))
                if (hidden > 0) hits.add(FanHit("暗杠", hidden))
            }
        }
        else -> {
            if (exposed == 2) hits.add(FanHit("双明杠")) else if (exposed == 1) hits.add(FanHit("明杠"))
            if (hidden == 2) hits.add(FanHit("双暗杠")) else if (hidden == 1) hits.add(FanHit("暗杠"))
        }
    }
    return hits
}

// MARK: - 面子构成番（碰碰和 / 平和 / 全带幺 …）

private fun mcrCompositionFan(sets: List<MCRSet>, pair: MCRSet, stats: TileStats): List<FanHit> {
    val hits = mutableListOf<FanHit>()
    val all = sets + pair
    val allPungs = sets.all { it.isPungLike }
    val allChows = sets.all { it.kind == MCRSet.Kind.CHOW }

    if (allPungs) hits.add(FanHit("碰碰和"))
    if (allChows && !mcrIsHonor(pair.tile)) hits.add(FanHit("平和"))

    if (all.all { s -> s.tiles.any { mcrIsTerminalOrHonor(it) } }) hits.add(FanHit("全带幺"))
    if (all.all { s -> s.tiles.any { !mcrIsHonor(it) && mcrRankOf(it) == 5 } }) hits.add(FanHit("全带五"))
    if (allPungs) {
        val tiles = stats.present
        if (tiles.all { mcrIsTerminal(it) }) hits.add(FanHit("清幺九"))
        else if (tiles.all { mcrIsTerminalOrHonor(it) }) hits.add(FanHit("混幺九"))
        if (tiles.all { !mcrIsHonor(it) && mcrRankOf(it) % 2 == 0 }) hits.add(FanHit("全双刻"))
    }
    // 四归一：某张牌 4 张齐，其中 3 张在一副面子里、另 1 张在别处（成杠不算）
    val kongTiles = sets.filter { it.kind == MCRSet.Kind.KONG }.map { it.tile }.toSet()
    var quads = 0
    for (i in 0 until MCR_TILE_KINDS) if (stats.freq[i] == 4 && i !in kongTiles) quads += 1
    if (quads > 0) hits.add(FanHit("四归一", quads))
    return hits
}

// MARK: - 场景番

private fun mcrSituationalFan(
    ctx: MCRContext,
    fullyConcealed: Boolean,
    allMelded: Boolean,
    singleWait: Boolean,
): List<FanHit> {
    val hits = mutableListOf<FanHit>()
    if (ctx.selfDrawn) {
        hits.add(FanHit("自摸"))
        if (fullyConcealed) hits.add(FanHit("不求人"))
        if (ctx.kongBloom) hits.add(FanHit("杠上开花"))
        if (ctx.lastTileDraw) hits.add(FanHit("妙手回春"))
    } else {
        if (fullyConcealed) hits.add(FanHit("门前清"))
        if (allMelded && singleWait) hits.add(FanHit("全求人"))
        if (ctx.lastDiscard) hits.add(FanHit("海底捞月"))
        if (ctx.robbingKong) hits.add(FanHit("抢杠和"))
    }
    if (ctx.lastTileOfKind) hits.add(FanHit("和绝张"))
    return hits
}

/** 和牌张落在哪副面子上 → 边张 / 坎张 / 单钓将（互斥，最多一个）；没有则 null */
private fun mcrWaitFanName(winSet: MCRSet, winningTile: Int): String? {
    if (winningTile < 0) return null
    return when (winSet.kind) {
        MCRSet.Kind.PAIR -> "单钓将"
        MCRSet.Kind.CHOW -> {
            val s = winSet.tile
            when {
                winningTile == s + 1 -> "坎张"
                winningTile == s + 2 && s % 9 == 0 -> "边张"
                winningTile == s && s % 9 == 6 -> "边张"
                else -> null
            }
        }
        else -> null
    }
}

/**
 * 严格读法下的听牌番：把和牌张在所有拆解里的读法都列出来，
 * 只有全部读法一致（且确实是边张/坎张/单钓将）时才计，否则一分不给。
 */
private fun mcrUniqueWaitFan(concealed: IntArray, melds: List<Meld>, winningTile: Int): List<FanHit> {
    if (winningTile < 0) return emptyList()
    val readings = mutableSetOf<String>()
    for (decomp in mcrDecompose(concealed, melds.size)) {
        for (set in decomp.handSets + decomp.pair) {
            if (winningTile !in set.tiles) continue
            readings.add(mcrWaitFanName(set, winningTile) ?: "")
        }
    }
    val only = readings.singleOrNull() ?: return emptyList()
    return if (only.isEmpty()) emptyList() else listOf(FanHit(only))
}

// MARK: - 主入口

/**
 * 对一副完整的国标和牌算番。
 * @param concealed 暗牌频率数组（长度 34，含所和那张，不含花牌）
 * @param melds 副露（吃/碰/明杠/暗杠）
 * @param context 和牌场景（自摸/点炮、圈风门风、场景番、花牌数）
 * @param options 规则细则（各地有分歧的五处判定）
 */
fun scoreMCRHand(
    concealed: IntArray,
    melds: List<Meld>,
    context: MCRContext,
    options: MCROptions = MCROptions(),
): MCRScore {
    val full = concealed.copyOf()
    val meldFreq = meldsToFrequency34(melds)
    for (i in 0 until MCR_TILE_KINDS) full[i] += meldFreq[i]
    val stats = TileStats(full)

    // 门清：没有吃 / 碰 / 明杠（暗杠可）
    val fullyConcealed = melds.all { it.kind == Meld.Kind.CONCEALED_KONG }
    val allMelded = melds.size == 4 && melds.none { it.kind == Meld.Kind.CONCEALED_KONG }
    val flowers = context.flowers

    var best: MCRScore? = null
    fun consider(hits: List<FanHit>) {
        val s = mcrFinalize(hits, flowers, options)
        if (best == null || s.scoringPoints > best!!.scoringPoints) best = s
    }

    val concealedSum = concealed.sum()

    // ── 特殊牌型（门清、暗牌恰 14 张）────────────────────────────
    if (melds.isEmpty() && concealedSum == 14) {
        // 十三幺
        if (mcrIsThirteenOrphans(full)) {
            val hits = mutableListOf(FanHit("十三幺"))
            hits += mcrWholeHandFan(stats)
            hits += mcrSituationalFan(context, fullyConcealed = true, allMelded = false, singleWait = true)
            consider(hits)
        }
        // 七对 / 连七对
        if (mcrIsSevenPairs(full, options.mcrSevenPairsAllowsQuadAsTwoPairs)) {
            val hits = mutableListOf(FanHit(if (mcrIsSevenShiftedPairs(full)) "连七对" else "七对"))
            hits += mcrWholeHandFan(stats)
            hits += mcrSituationalFan(context, fullyConcealed = true, allMelded = false, singleWait = true)
            consider(hits)
        }
        // 全不靠 / 七星不靠（可与组合龙叠加）
        if (mcrIsKnittedNoSets(full)) {
            val hits = mutableListOf(FanHit(if (mcrIsSevenStarsKnitted(full)) "七星不靠" else "全不靠"))
            if (mcrHasKnittedStraight(full)) hits.add(FanHit("组合龙"))
            hits += mcrWholeHandFan(stats)
            hits += mcrSituationalFan(context, fullyConcealed = true, allMelded = false, singleWait = false)
            consider(hits)
        }
        // 九莲宝灯
        if (mcrIsNineGates(full, melds)) {
            val hits = mutableListOf(FanHit("九莲宝灯"))
            hits += mcrWholeHandFan(stats)
            hits += mcrSituationalFan(context, fullyConcealed = true, allMelded = false, singleWait = false)
            consider(hits)
        }
    }

    // ── 组合龙型：9 张组合龙在手 + 1 面子（可副露）+ 1 将 ──────────
    if (melds.size <= 1 && mcrIsKnittedStraightForm(concealed, melds.size)) {
        val hits = mutableListOf(FanHit("组合龙"))
        hits += mcrWholeHandFan(stats)
        hits += mcrSituationalFan(context, fullyConcealed, allMelded = false, singleWait = false)
        consider(hits)
    }

    // ── 标准型：枚举所有拆解 × 和牌张归属 ────────────────────────
    val meldSets = mcrMeldSets(melds)
    // 严格读法下听牌番与拆解无关，先一次算好
    val strictWaitFan =
        if (options.mcrWaitFanHighestReading) emptyList()
        else mcrUniqueWaitFan(concealed, melds, context.winningTile)

    for (decomp in mcrDecompose(concealed, melds.size)) {
        val handAll = decomp.handSets + decomp.pair
        // 和牌张可能落在多副手内面子上，逐一试，取最优
        val winCandidates = if (context.winningTile < 0) listOf(-1) else {
            handAll.indices.filter { context.winningTile in handAll[it].tiles }
                .ifEmpty { listOf(-1) }
        }
        for (winIdx in winCandidates) {
            val sets = (decomp.handSets + meldSets).toMutableList()
            val pair = decomp.pair
            // 点炮成刻算明刻（只影响暗刻类番，门前清不受影响）
            if (!context.selfDrawn && winIdx >= 0 && winIdx < decomp.handSets.size && sets[winIdx].isPungLike) {
                sets[winIdx] = sets[winIdx].copy(concealed = false)
            }
            if (sets.size != 4) continue

            val hits = mutableListOf<FanHit>()
            hits += mcrWholeHandFan(stats)
            if (mcrIsNineGates(full, melds)) hits.add(FanHit("九莲宝灯"))
            hits += mcrStructureFan(sets, pair)
            hits += mcrHonorFan(sets, pair, context)
            hits += mcrConcealmentFan(sets, options)
            hits += mcrCompositionFan(sets, pair, stats)

            val winSet = if (winIdx >= 0) handAll[winIdx] else null
            val singleWait = winSet?.kind == MCRSet.Kind.PAIR
            if (options.mcrWaitFanHighestReading) {
                if (winSet != null) mcrWaitFanName(winSet, context.winningTile)?.let { hits.add(FanHit(it)) }
            } else {
                hits += strictWaitFan
            }
            hits += mcrSituationalFan(context, fullyConcealed, allMelded, singleWait)
            consider(hits)
        }
    }

    // 没有任何和牌拆解成立（手牌不满 14 张的「部分手牌」会走到这里）：
    // 这时候不能给「无番和 8 分」——那是给真正和了却一个番种都没有的牌的。
    return best ?: MCRScore(
        items = if (flowers > 0) listOf(FanItem("花牌", flowers, count = flowers)) else emptyList(),
        totalPoints = flowers,
        scoringPoints = 0,
    )
}

// MARK: - 打牌建议评估（国标）

/** 一个国标候选弃牌的完整评估 */
data class MCREvaluatedDiscard(
    val suggestion: DiscardSuggestion,
    val waitScores: List<Pair<MahjongCard, MCRScore>>,
    /** 听牌里能达到的最高分；未听牌 / 空听 = -1 */
    val maxPoints: Int,
)

/**
 * 给国标打牌建议补上「弃后听牌 + 各自番分」，按「最高分降序 → 向听升序 → 进张降序」重排。
 * 分数按点炮基线（不含自摸等场景番）。
 */
fun mcrEvaluateDiscards(
    suggestions: List<DiscardSuggestion>,
    cards: List<MahjongCard>,
    melds: List<Meld>,
    settings: RuleSettings,
    flowers: Int = 0,
): List<MCREvaluatedDiscard> {
    val options = settings.mcrOptions
    val baseFreq = handToFrequency34(cards)

    val evaluated = suggestions.map { s ->
        val di = s.discard.mcrIndex
        if (s.resultingShanten != 0 || s.acceptance.isEmpty() || di < 0) {
            return@map MCREvaluatedDiscard(s, emptyList(), -1)
        }
        val afterDiscard = baseFreq.copyOf()
        afterDiscard[di] -= 1
        val waitScores = s.acceptance.map { wait ->
            val winning = afterDiscard.copyOf()
            winning[wait.mcrIndex] += 1
            val ctx = MCRContext(
                selfDrawn = false,
                winningTile = wait.mcrIndex,
                prevalentWind = settings.mcrPrevalentWind,
                seatWind = settings.mcrSeatWind,
                flowers = flowers,
            )
            wait to scoreMCRHand(winning, melds, ctx, options)
        }
        MCREvaluatedDiscard(s, waitScores, waitScores.maxOfOrNull { it.second.totalPoints } ?: -1)
    }

    return evaluated.sortedWith(
        compareByDescending<MCREvaluatedDiscard> { it.maxPoints }
            .thenBy { it.suggestion.resultingShanten }
            .thenByDescending { it.suggestion.acceptanceCount }
            .thenBy { it.suggestion.discard.suit.displaySortIndex }
            .thenBy { it.suggestion.discard.rank }
    )
}
