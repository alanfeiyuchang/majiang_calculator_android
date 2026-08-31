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
/**
 * 各地规则书写法不一致的几处。**默认值对齐官方竞赛算番器**
 * （北大 Botzone / ChineseOfficialMahjongHelper）——前两项里字一色那条默认关，
 * 因为官方不这么算；想要本地变体的把对应开关打开。
 */
data class MCROptions(
    /** 字一色是否同时计混幺九（+32）。官方不计 */
    val mcrZiYiSeCountsHunYaoJiu: Boolean = false,
    /** 九莲宝灯是否同时计双暗刻（+2）。官方计 */
    val mcrJiuLianCountsShuangAnKe: Boolean = true,
    /** 七对中四张相同是否可当两对 */
    val mcrSevenPairsAllowsQuadAsTwoPairs: Boolean = true,
    /** 三杠时是否再单独计每个杠（明杠 1 / 暗杠 2）。官方：三杠 32 分已涵盖，不再单计 */
    val mcrPerKongFanWithThreeKongs: Boolean = false,
    /**
     * 边张 / 坎张 / 单钓将的读法：
     * true  = 就高不就低，跨所有拆解取最优的那种听法；
     * false = 只有当所有拆解读出同一种听法时才计（听法不唯一就不计）。
     */
    val mcrWaitFanHighestReading: Boolean = true,
    /**
     * 一明杠 + 一暗杠算不算「明暗杠」5 分。
     * true = 现行通行（官方竞赛算番器就是这么算的）；false = 严格 98，拆成明杠 1 + 暗杠 2
     */
    val mcrOneOpenOneConcealedKong: Boolean = true,
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
        // 非官方 81 番之一：一明杠 + 一暗杠。严格 98 规则拆成 明杠1 + 暗杠2 = 3 分，
    // 现行通行（及官方竞赛算番器）作为一个独立番种计 5 分，由开关控制。
    "明暗杠" to 5,
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
    // 无字被这些吸收（官方 §2.4）：平和、断幺、清一色、全大/全中/全小、
    // 大于五/小于五、全双刻、全带五、三色双龙会、一色双龙会
    "平和" to listOf("无字"),
    "断幺" to listOf("无字"),
    "大四喜" to listOf("三风刻", "圈风刻", "门风刻", "碰碰和", "幺九刻"),
    "大三元" to listOf("箭刻", "双箭刻"),
    "绿一色" to listOf("缺一门", "混一色"),
    "九莲宝灯" to listOf("清一色", "门前清", "无字", "不求人"),
    "四杠" to listOf("三杠", "双明杠", "双暗杠", "明杠", "暗杠", "碰碰和", "单钓将"),
    "连七对" to listOf("七对", "清一色", "门前清", "无字", "单钓将", "不求人"),
    "十三幺" to listOf("五门齐", "门前清", "单钓将", "不求人", "混幺九", "清幺九"),

    "清幺九" to listOf("碰碰和", "全带幺", "幺九刻", "无字", "双同刻"),
    "小四喜" to listOf("三风刻", "幺九刻"),
    "小三元" to listOf("双箭刻", "箭刻"),
    "字一色" to listOf("碰碰和", "全带幺", "幺九刻", "缺一门", "无字"),
    "四暗刻" to listOf("碰碰和", "门前清", "三暗刻", "双暗刻", "不求人"),
    "一色双龙会" to listOf("清一色", "平和", "无字", "缺一门"),

    "一色四同顺" to listOf("一色三同顺", "四归一"),
    "一色四节高" to listOf("一色三节高", "碰碰和"),

    "一色四步高" to listOf("一色三步高"),
    "三杠" to listOf("双明杠", "双暗杠"),
    "混幺九" to listOf("碰碰和", "全带幺", "幺九刻"),

    "七对" to listOf("门前清", "单钓将", "不求人"),
    "七星不靠" to listOf("全不靠", "五门齐", "门前清", "不求人", "单钓将"),
    "全双刻" to listOf("碰碰和", "断幺", "无字"),
    "清一色" to listOf("无字", "缺一门"),
    "一色三同顺" to listOf(),
    "全大" to listOf("无字", "大于五"),
    "全中" to listOf("无字", "断幺"),
    "全小" to listOf("无字", "小于五"),

    "清龙" to listOf(),
    "三色双龙会" to listOf("无字", "平和"),
    "一色三步高" to listOf(),
    "全带五" to listOf("断幺", "无字"),
    "三同刻" to listOf("双同刻"),
    "三暗刻" to listOf("双暗刻"),

    "全不靠" to listOf("五门齐", "门前清", "不求人", "单钓将"),
    "大于五" to listOf("无字"),
    "小于五" to listOf("无字"),

    "推不倒" to listOf("缺一门"),
    "三色三同顺" to listOf(),
    "三色三节高" to listOf(),
    "妙手回春" to listOf("自摸"),
    "杠上开花" to listOf("自摸"),
    "抢杠和" to listOf("和绝张"),

    "混一色" to listOf("缺一门"),
    "全求人" to listOf("单钓将", "门前清"),
    "明暗杠" to listOf("明杠", "暗杠"),
    "双暗杠" to listOf("暗杠", "双暗刻"),
    "双箭刻" to listOf("箭刻"),

    "不求人" to listOf("自摸", "门前清"),
    "双明杠" to listOf("明杠"),
)

/** 按规则细则展开后的排除表：关掉「同时计」时，高番型把对应低番型也吃掉 */
fun mcrFanExcludes(options: MCROptions): Map<String, List<String>> {
    if (options.mcrZiYiSeCountsHunYaoJiu && options.mcrJiuLianCountsShuangAnKe
        && options.mcrPerKongFanWithThreeKongs) return MCR_FAN_EXCLUDES
    val m = MCR_FAN_EXCLUDES.toMutableMap()
    if (!options.mcrZiYiSeCountsHunYaoJiu) {
        m["字一色"] = (m["字一色"] ?: emptyList()) + "混幺九"
    }
    if (!options.mcrJiuLianCountsShuangAnKe) {
        m["九莲宝灯"] = (m["九莲宝灯"] ?: emptyList()) + "双暗刻"
    }
    // 官方：三杠 32 分已经涵盖那三个杠，不再单独计。开关打开才额外计。
    if (!options.mcrPerKongFanWithThreeKongs) {
        m["三杠"] = (m["三杠"] ?: emptyList()) + listOf("明杠", "暗杠")
    }
    return m
}

// MARK: - 一次命中

private data class FanHit(val name: String, val count: Int = 1)

private fun points(name: String): Int = MCR_FAN_POINTS[name] ?: 0

/** 应用排除表并折算成 FanItem 列表 + 总分 */
private fun mcrFinalize(hits: List<FanHit>, rawFlowers: Int, options: MCROptions): MCRScore {
    // 花牌数不可能为负；不设防的话 totalPoints 会小于 scoringPoints
    val flowers = maxOf(0, rawFlowers)
    val merged = LinkedHashMap<String, Int>()
    for (h in hits) {
        if (h.count <= 0) continue
        merged[h.name] = (merged[h.name] ?: 0) + h.count
    }

    // 九莲宝灯把幺九刻**减 1**，不是整个吸收——和三风刻减 3 是同一个套路。
    // 九种和牌张实测：手里有 111 和 999 两个幺九刻时官方给 1 个，只有一个时给 0 个。
    if (merged.containsKey("九莲宝灯")) {
        val n = merged["幺九刻"]
        if (n != null) { if (n > 1) merged["幺九刻"] = n - 1 else merged.remove("幺九刻") }
    }

    // 不可重复原则。从**高番到低番**依次处理，而且**已经被删掉的番不再有排除权**——
    // 十三幺删掉不求人之后，不求人就不该再把自摸一起带走（十三幺自摸要计那 1 分）。
    val excludes = mcrFanExcludes(options)
    val alive = merged.keys.toMutableSet()
    for (name in merged.keys.sortedByDescending { points(it) }) {
        if (name !in alive) continue
        for (victim in excludes[name] ?: emptyList()) {
            if (victim != name) alive.remove(victim)
        }
    }
    for (name in merged.keys.toList()) if (name !in alive) merged.remove(name)

    // 无番和：一个番种都没有。自摸本身就是 1 分番，不能排除在外——
    // 否则「只有自摸」的牌会被判成 无番和8 + 自摸1 = 9 分，凭空够到起和线。
    val coreTotal = merged.entries.sumOf { (name, n) -> points(name) * n }
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
/**
 * 特殊牌型（七对 / 十三幺 / 全不靠 / 组合龙）用的补充番。
 * 这些牌型拆不出 4 副面子，走不到 mcrCompositionFan，但下面这几个番
 * 本来就是整手牌属性，从频率数组就能判——漏掉它们正是「计番不全」的来源。
 * 注意不能在这里判「全双刻」：它要求全部由 2/4/6/8 的**刻子**组成，七对没有刻子。
 */
private fun mcrFreqOnlyFan(stats: TileStats): List<FanHit> {
    val hits = mutableListOf<FanHit>()
    val tiles = stats.present
    if (tiles.all { mcrIsTerminal(it) }) hits.add(FanHit("清幺九"))
    else if (tiles.all { mcrIsTerminalOrHonor(it) }) hits.add(FanHit("混幺九"))
    var quads = 0
    for (i in 0 until MCR_TILE_KINDS) if (stats.freq[i] == 4) quads++
    if (quads > 0) hits.add(FanHit("四归一", quads))
    return hits
}


/**
 * 组合龙型里除了那 9 张组合龙之外的部分：1 副面子 + 1 对将。
 * 拿它去算刻子番与听牌番——组合龙分支本身给不出面子，漏的正是「计番不全」。
 */
/** 手里那 9 张组合龙「龙身」是哪些牌。找不到组合龙就返回 null。 */
private fun mcrKnittedBodyTiles(concealed: IntArray): Set<Int>? {
    val patterns = listOf(listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8))
    for (i in 0..2) for (j in 0..2) {
        if (j == i) continue
        val k = 3 - i - j
        val t = concealed.copyOf()
        val body = mutableSetOf<Int>()
        var okAll = true
        for ((suit, pat) in listOf(0 to patterns[i], 1 to patterns[j], 2 to patterns[k])) {
            for (r in pat) {
                val idx = suit * 9 + r
                if (t[idx] > 0) { t[idx]--; body.add(idx) } else okAll = false
            }
        }
        if (okAll) return body
    }
    return null
}

/**
 * 组合龙型的听牌番。官方（`adjust_by_waiting_form`）只看**第 4 副面子和将**这两处，
 * 副露过的那副不算，而且边张 / 坎张 / 单钓将三者最多取一个，优先级 边张 > 坎张 > 单钓将。
 * 门清时才按位置判；有副露时听在龙身外必是单钓将，听在龙身上只有攥了 3 张才算。
 */
private fun mcrKnittedWaitFan(
    extraSets: List<MCRSet>, extraPair: MCRSet,
    concealed: IntArray, melds: List<Meld>, w: Int,
): List<FanHit> {
    if (w < 0) return emptyList()
    if (melds.isEmpty()) {
        var edge = false; var closed = false; var single = false
        for (set in extraSets + extraPair) {
            when (set.kind) {
                MCRSet.Kind.CHOW ->
                    if (w == set.tile + 1) closed = true
                    else if (w == set.tile || w == set.tile + 2) edge = true
                MCRSet.Kind.PAIR -> if (w == set.tile) single = true
                else -> {}
            }
        }
        return when {
            edge -> listOf(FanHit("边张"))
            closed -> listOf(FanHit("坎张"))
            single -> listOf(FanHit("单钓将"))
            else -> emptyList()
        }
    }
    val inBody = mcrKnittedBodyTiles(concealed)?.contains(w) ?: false
    return if (!inBody || concealed[w] == 3) listOf(FanHit("单钓将")) else emptyList()
}

private fun mcrKnittedExtraSets(concealed: IntArray, melds: List<Meld>): Pair<List<MCRSet>, MCRSet>? {
    val body = mcrKnittedBodyTiles(concealed) ?: return null
    val rest = concealed.copyOf()
    for (idx in body) rest[idx]--
    val meldSets = mcrMeldSets(melds)
    // 摘掉 9 张后只剩「1 面子 + 将」= 5 张（或副露占了那副面子时只剩将 = 2 张）。
    // mcrDecompose 按「14 − 3×副露数」校验张数，所以要按 3 + melds.size 组副露去调。
    for (d in mcrDecompose(rest, 3 + melds.size)) {
        if (d.handSets.size + meldSets.size == 1) return (d.handSets + meldSets) to d.pair
    }
    if (rest.sum() == 2) {
        val i = (0 until MCR_TILE_KINDS).firstOrNull { rest[it] == 2 } ?: return null
        return meldSets to MCRSet(MCRSet.Kind.PAIR, i, true)
    }
    return null
}

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
private fun mcrIsNineGates(freq: IntArray, melds: List<Meld>, winningTile: Int): Boolean {
    if (melds.isNotEmpty() || freq.sum() != 14) return false
    val suits = (0 until 27).filter { freq[it] > 0 }.map { it / 9 }.toSet()
    if (suits.size != 1 || (27 until 34).any { freq[it] != 0 }) return false
    if (winningTile < 0 || winningTile >= 27 || freq[winningTile] == 0) return false
    val base = suits.first() * 9
    if (winningTile / 9 != base / 9) return false
    // 九莲宝灯看的是**和牌前那 13 张**要正好是 1112345678999。只看和牌后的 14 张，
    // 会把 11112345678999 也判成九莲——摘掉和的 2 万后剩 1111345678999，
    // 官方按普通清一色算。
    val before = freq.copyOf(); before[winningTile] -= 1
    val pattern = intArrayOf(3, 1, 1, 1, 1, 1, 1, 1, 3)
    for (r in 0..8) if (before[base + r] != pattern[r]) return false
    return true
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
/** 两副顺子能凑成的 1 分番（一般高 / 喜相逢 / 连六 / 老少副） */
private fun mcrPairChowFan(a: MCRSet, b: MCRSet): String? {
    if (a.kind != MCRSet.Kind.CHOW || b.kind != MCRSet.Kind.CHOW) return null
    if (a.suit == b.suit) {
        if (a.rank == b.rank) return "一般高"
        if (kotlin.math.abs(a.rank - b.rank) == 3) return "连六"
        if (setOf(a.rank, b.rank) == setOf(1, 7)) return "老少副"
        return null
    }
    return if (a.rank == b.rank) "喜相逢" else null
}

/**
 * 一般高 / 喜相逢 / 连六 / 老少副 的计数上限。
 *
 * 规则第 3.9.1.5 条的原则 3（不得相同）与原则 5（套算一次）合起来，等价于在
 * 「顺子为点、可成番的两副顺子为边」这张图上挑一个边集，满足：
 *   ① 无环（n 副顺子最多 n−1 条边）—— 原则 5：新的一副只能和已组合过的套算一次
 *   ② 同一个番种的边彼此不共点 —— 原则 3：已组过某番种的牌不能再组相同番种
 * 大结构番（清龙/花龙等）占用的面子先并成一个分量，否则会多算。
 *
 * 规则书的两个判例正好卡住这两条：
 *   234567筒 234567条 → 只能「喜相逢×2 + 连六×1」或「喜相逢×1 + 连六×2」
 *   234条 223344567筒 → 只能「一般高 + 喜相逢 + 连六」，不能「喜相逢×2 + 连六」
 *
 * 顺子最多 4 副 ⇒ 最多 6 条边 ⇒ 直接枚举 64 个子集。
 */
private fun mcrChowPairFans(sets: List<MCRSet>, alreadyUsed: List<Int> = emptyList()): List<FanHit> {
    val idx = sets.indices.filter { sets[it].kind == MCRSet.Kind.CHOW }
    if (idx.size < 2) return emptyList()
    data class Edge(val a: Int, val b: Int, val name: String, val pts: Int)
    val edges = mutableListOf<Edge>()
    for (i in idx.indices) for (j in i + 1 until idx.size) {
        mcrPairChowFan(sets[idx[i]], sets[idx[j]])?.let {
            edges.add(Edge(idx[i], idx[j], it, points(it)))
        }
    }
    if (edges.isEmpty()) return emptyList()

    var bestPts = -1
    var bestNames: List<String> = emptyList()
    val components = idx.size - maxOf(0, alreadyUsed.size - 1)
    for (mask in 0 until (1 shl edges.size)) {
        val chosen = edges.indices.filter { mask and (1 shl it) != 0 }.map { edges[it] }
        if (chosen.size > components - 1) continue

        val parent = HashMap<Int, Int>()
        fun find(x: Int): Int {
            parent.putIfAbsent(x, x)
            if (parent[x] == x) return x
            val r = find(parent[x]!!); parent[x] = r; return r
        }
        alreadyUsed.firstOrNull()?.let { anchor ->
            for (u in alreadyUsed.drop(1)) {
                val ra = find(anchor); val ru = find(u)
                if (ra != ru) parent[ru] = ra
            }
        }
        var acyclic = true
        for (e in chosen) {
            val ra = find(e.a); val rb = find(e.b)
            if (ra == rb) { acyclic = false; break }
            parent[ra] = rb
        }
        if (!acyclic) continue

        var okMatching = true
        val usedByName = HashMap<String, MutableSet<Int>>()
        for (e in chosen) {
            val used = usedByName.getOrPut(e.name) { mutableSetOf() }
            if (e.a in used || e.b in used) { okMatching = false; break }
            used.add(e.a); used.add(e.b)
        }
        if (!okMatching) continue

        val pts = chosen.sumOf { it.pts }
        if (pts > bestPts) { bestPts = pts; bestNames = chosen.map { it.name } }
    }
    return bestNames.map { FanHit(it) }
}

private fun mcrStructureFan(sets: List<MCRSet>, pair: MCRSet): List<FanHit> {
    if (sets.size != 4) return emptyList()

    // 大结构番（3–4 副面子）仍按划分取最优
    var bestTotal = -1
    var bestHits: List<FanHit> = emptyList()
    var bestUsed: List<Int> = emptyList()
    for (partition in MCR_PARTITIONS_OF_4) {
        var total = 0
        val hits = mutableListOf<FanHit>()
        val used = mutableListOf<Int>()
        for (block in partition) {
            if (block.size < 3) continue
            val hit = mcrBestFanForBlock(block.map { sets[it] }, pair)
            if (hit != null) { total += hit.second; hits.add(FanHit(hit.first)); used.addAll(block) }
        }
        if (total > bestTotal) { bestTotal = total; bestHits = hits; bestUsed = used }
    }

    val out = bestHits.toMutableList()
    // 双同刻（两副刻子）走原逻辑：刻子番不受顺子那套约束
    for (i in 0 until 4) for (j in i + 1 until 4) {
        val hit = mcrBestFanForBlock(listOf(sets[i], sets[j]), pair)
        if (hit != null && hit.first == "双同刻") out.add(FanHit(hit.first))
    }
    // 两副顺子的 1 分番独立结算：可以和大结构番共用同一副顺子
    out.addAll(mcrChowPairFans(sets, bestUsed))
    return out
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
    // 圈风刻 / 门风刻：同一副风刻既是圈风又是门风时两个都计（官方如此），
    // 但**已计过的那副刻子不再计幺九刻**——同一组面子不重复得分。
    val windsScored = mutableSetOf<Int>()
    if (windPungs.any { it.tile == 27 + ctx.prevalentWind }) {
        hits.add(FanHit("圈风刻")); windsScored.add(27 + ctx.prevalentWind)
    }
    if (windPungs.any { it.tile == 27 + ctx.seatWind }) {
        hits.add(FanHit("门风刻")); windsScored.add(27 + ctx.seatWind)
    }
    // 幺九刻扣掉两类已经另行计分的：① 已计圈风刻/门风刻的那副；
    // ② 三风刻成立时组成它的 3 副整体减掉（官方明写「减 3」，手里其它的照常计）
    var yaojiuPungs = pungs.count { mcrIsTerminal(it.tile) || mcrIsWind(it.tile) }
    yaojiuPungs -= windPungs.count { it.tile in windsScored }
    if (windPungs.size == 3) yaojiuPungs -= windPungs.count { it.tile !in windsScored }
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
            // 一明一暗：现行通行（含官方竞赛算番器）作为「明暗杠」5 分整体计，
            // 而不是拆成 明杠 1 + 暗杠 2。严格 98 规则把开关关掉即可。
            if (exposed == 1 && hidden == 1 && options.mcrOneOpenOneConcealedKong) {
                hits.add(FanHit("明暗杠"))
            } else {
                if (exposed == 2) hits.add(FanHit("双明杠")) else if (exposed == 1) hits.add(FanHit("明杠"))
                if (hidden == 2) hits.add(FanHit("双暗杠")) else if (hidden == 1) hits.add(FanHit("暗杠"))
            }
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
    // 和牌张未知（界面上的「已和！」卡片就是这种情形）：逐张试，取最高的那种读法。
    // 不这么做的话，凡是要看和牌张才成立的番全都拿不到——九莲宝灯、全求人、
    // 边张/坎张/单钓将——九莲宝灯会从 92 分掉到 31 分，全求人少 6 分还会
    // 被误判成「不够起和」。同时场景番的牌面校正也要跟着逐张判：
    // 只要没有任何一张说得通，那个勾就得撤销（否则抢杠和能凭空多 8 分）。
    if (context.winningTile < 0) {
        var best: MCRScore? = null
        for (w in 0 until MCR_TILE_KINDS) {
            if (concealed[w] == 0) continue
            val s = scoreMCRHand(concealed, melds, context.copy(winningTile = w), options)
            val b = best
            if (b == null || s.scoringPoints > b.scoringPoints) best = s
        }
        best?.let { return it }
    }

    val full = concealed.copyOf()
    val meldFreq = meldsToFrequency34(melds)
    for (i in 0 until MCR_TILE_KINDS) full[i] += meldFreq[i]
    val stats = TileStats(full)

    // 门清：没有吃 / 碰 / 明杠（暗杠可）
    val fullyConcealed = melds.all { it.kind == Meld.Kind.CONCEALED_KONG }
    val allMelded = melds.size == 4 && melds.none { it.kind == Meld.Kind.CONCEALED_KONG }
    val flowers = context.flowers

    // 场景番的三个勾选项，牌面能证伪的一律以牌面为准（官方同样这么校正）。
    // 少了这层校正，用户点错一个勾就能凭空多出 4～8 分。
    @Suppress("NAME_SHADOWING")
    val context = run {
        val w = context.winningTile
        if (w < 0) return@run context
        var c = context
        // 和绝张：立牌里除了和的这张还有别的，说明第 4 张不在明面上，撤销；
        // 反过来另外 3 张明着在副露里，那必然是绝张，不必等用户勾。
        if (concealed[w] != 1) c = c.copy(lastTileOfKind = false)
        if (meldFreq[w] == 3) c = c.copy(lastTileOfKind = true)
        c = if (c.selfDrawn) {
            // 杠上开花：手里根本没有杠
            if (melds.none { it.kind == Meld.Kind.EXPOSED_KONG || it.kind == Meld.Kind.CONCEALED_KONG })
                c.copy(kongBloom = false) else c
        } else {
            // 抢杠和：抢的是别人补杠的第 4 张，自己手上不可能还有同一张
            if (meldFreq[w] != 0 || concealed[w] != 1) c.copy(robbingKong = false) else c
        }
        c
    }

    // 独听：和牌前那 13 张只等这一张。官方的边张/坎张/单钓将都要求独听。
    // 用「听牌形状」判，不排除已经用满 4 张的牌：4567条 是两头听，哪怕 7 条
    // 已经被自己碰光，形状仍然不是独听。（mcrCalculateWaiting 会滤掉摸不到的牌，
    // 那是给界面显示用的，这里不能用。）
    val isUniqueWait: Boolean = run {
        val w = context.winningTile
        if (w < 0 || concealed[w] == 0) return@run false
        val before = concealed.copyOf(); before[w] -= 1
        var cnt = 0
        for (i in 0 until MCR_TILE_KINDS) {
            val t = before.copyOf(); t[i] += 1
            if (mcrIsCompleteHand(t, melds)) { cnt++; if (cnt > 1) break }
        }
        cnt == 1
    }
    val waitFanNames = setOf("边张", "坎张", "单钓将")

    // 分数打平时选哪个拆解：官方保留**先算出来的**那个，只有两种例外——
    // 后来的拆解里有一色三同顺或三同刻，以及「标准型打平七对时优先标准型」。
    // 其余并列（比如同为 1 分的一般高 / 连六）官方取决于它自己的枚举顺序，
    // 那不是规则、是实现细节，我们不去模仿，总分一样、只是番种名不同。
    fun tieBreakPrefersNew(new: MCRScore, old: MCRScore): Boolean {
        val n = new.items.map { it.name }.toSet()
        val o = old.items.map { it.name }.toSet()
        if ("一色三同顺" in n || "三同刻" in n) return true
        if ("七对" in o && "七对" !in n) return true
        return false
    }

    var best: MCRScore? = null
    fun consider(hits: List<FanHit>) {
        val filtered = if (isUniqueWait) hits else hits.filter { it.name !in waitFanNames }
        val s = mcrFinalize(filtered, flowers, options)
        val b = best
        if (b == null || s.scoringPoints > b.scoringPoints ||
            (s.scoringPoints == b.scoringPoints && tieBreakPrefersNew(s, b))) best = s
    }

    val concealedSum = concealed.sum()

    // ── 特殊牌型（门清、暗牌恰 14 张）────────────────────────────
    if (melds.isEmpty() && concealedSum == 14) {
        // 十三幺
        if (mcrIsThirteenOrphans(full)) {
            val hits = mutableListOf(FanHit("十三幺"))
            hits += mcrWholeHandFan(stats)
            hits.addAll(mcrFreqOnlyFan(stats))
            hits += mcrSituationalFan(context, fullyConcealed = true, allMelded = false, singleWait = true)
            consider(hits)
        }
        // 七对 / 连七对
        if (mcrIsSevenPairs(full, options.mcrSevenPairsAllowsQuadAsTwoPairs)) {
            val hits = mutableListOf(FanHit(if (mcrIsSevenShiftedPairs(full)) "连七对" else "七对"))
            hits += mcrWholeHandFan(stats)
            hits.addAll(mcrFreqOnlyFan(stats))
            hits += mcrSituationalFan(context, fullyConcealed = true, allMelded = false, singleWait = true)
            consider(hits)
        }
        // 全不靠 / 七星不靠（可与组合龙叠加）
        if (mcrIsKnittedNoSets(full)) {
            val hits = mutableListOf(FanHit(if (mcrIsSevenStarsKnitted(full)) "七星不靠" else "全不靠"))
            if (mcrHasKnittedStraight(full)) hits.add(FanHit("组合龙"))
            hits += mcrWholeHandFan(stats)
            hits.addAll(mcrFreqOnlyFan(stats))
            hits += mcrSituationalFan(context, fullyConcealed = true, allMelded = false, singleWait = false)
            consider(hits)
        }
        // 九莲宝灯
        if (mcrIsNineGates(full, melds, context.winningTile)) {
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
        // 这里**不能**用 mcrFreqOnlyFan：官方对组合龙的四归一有严格限定，
        // 只有第 4 副面子那张（还得不是杠）或者将牌能成四归一，不是「凡 4 张就算」。
        // 龙身上的牌哪怕攥了 4 张也不算。清幺九 / 混幺九则天然不可能——
        // 龙身必然含 2…8，一定不是清一色幺九。
        mcrKnittedExtraSets(concealed, melds)?.let { (extraSets, extraPair) ->
            val set = extraSets.firstOrNull()
            val pairTile = extraPair.tile
            if (set != null && set.kind == MCRSet.Kind.CHOW) {
                // 第 4 副是顺子：将是数牌就是平和（平和顺带把无字吸收掉）
                if (!mcrIsHonor(pairTile)) hits.add(FanHit("平和"))
                // 顺子型只有将可能凑成四归一
                if (full[pairTile] == 4) hits.add(FanHit("四归一"))
            } else if (set != null) {
                // 第 4 副是刻子/杠：照常算刻子番（幺九刻 / 箭刻 / 圈风刻 / 门风刻）
                hits += mcrHonorFan(listOf(set), extraPair, context)
                hits += mcrConcealmentFan(listOf(set), options)
                if (!mcrIsHonor(set.tile) && set.kind != MCRSet.Kind.KONG && full[set.tile] == 4) {
                    hits.add(FanHit("四归一"))
                }
            }
            hits += mcrKnittedWaitFan(extraSets, extraPair, concealed, melds, context.winningTile)
        }
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
        // 同一个拆解里和牌张可能同时读成好几种听法（比如 67788 + 和 7，
        // 既在 789 的边上、又在 678 的中间、还能当将）。官方在一个拆解内部
        // 只取一种，优先级 边张 > 坎张 > 单钓将——把候选按这个次序排好，
        // 后面打平保留先算出来的那个，效果就等同于官方的取法。
        // 注意这条优先级只在**同一个拆解内**成立，跨拆解打平时官方是按
        // 自己的枚举顺序取的，那是实现细节，不在这里模仿。
        val waitOrder = mapOf("边张" to 0, "坎张" to 1, "单钓将" to 2)
        val winCandidates = if (context.winningTile < 0) listOf(-1) else {
            handAll.indices.filter { context.winningTile in handAll[it].tiles }
                .sortedBy { waitOrder[mcrWaitFanName(handAll[it], context.winningTile)] ?: 3 }
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
            if (mcrIsNineGates(full, melds, context.winningTile)) hits.add(FanHit("九莲宝灯"))
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
