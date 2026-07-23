//
//  MahjongScoring.kt
//  传统四川麻将算番与计钱。与 iOS MahjongScoring.swift 一一对应。
//  金额 = 底分 × 2^min(总番, 封顶)，显示为「单家」输赢：
//  点炮 = 放炮那家付这个数；自摸 = 其余三家各付这个数。
//  杠的即时结算（刮风下雨）是杠时另算的账，不计入胡牌金额；
//  设置里的「杠计根番」只控制杠要不要作为根进入胡牌倍数。
//

package com.feiyu.majiang.core

import java.math.BigDecimal
import kotlin.math.min
import kotlin.math.pow

// MARK: - 规则设置

/** 根的计法：加番（每根 +1 番）/ 加底（每根 +1 底分）/ 关闭（不计入胡牌倍数） */
enum class GenMode(val raw: String) {
    FAN("fan"),    // 加番
    BASE("base"),  // 加底
    OFF("off");    // 关闭

    val label: String
        get() = when (this) {
            FAN -> "加番（+1 番）"
            BASE -> "加底（+1 底分）"
            OFF -> "关闭"
        }

    companion object {
        fun fromRaw(raw: String?): GenMode? = entries.firstOrNull { it.raw == raw }
    }
}

/** 可因地区而异的规则项，全部持久化 */
data class RuleSettings(
    /** 底分（0 番平胡的单家金额） */
    val baseStake: Double = 1.0,
    /** 封顶番数；0 = 不封顶（默认） */
    val fanCap: Int = 0,
    /** true = 自摸加番（+1 番）；false = 自摸加底（+1 个底分） */
    val selfDrawAddsFan: Boolean = true,

    // 基础牌型开关（默认全开）：关闭则该番型不计番
    /** 平胡（0 番，兜底；开关对金额无实际影响） */
    val pingHuEnabled: Boolean = true,
    /** 碰碰胡 +1 番 */
    val pengPengHuEnabled: Boolean = true,
    /** 清一色 +2 番 */
    val qingYiSeEnabled: Boolean = true,
    /** 七小对 +2 番（含豪华家族的基础） */
    val qiXiaoDuiEnabled: Boolean = true,
    /** 豪华七小对：每龙 +1 番。关闭则龙七对按平七小对 2 番计 */
    val haoHuaEnabled: Boolean = true,
    /** 门清（无碰、无明杠；暗杠可）+1 番 */
    val menQingEnabled: Boolean = true,
    /** 断幺九（整副牌完全不含 1 和 9）+1 番 */
    val duanYaoJiuEnabled: Boolean = true,

    /** 金钩钓番数（1 或 2；含碰碰胡，不再叠加） */
    val goldenHookFan: Int = 2,
    /** 将对（碰碰胡全 2/5/8，3 番）/ 将七对（七小对全 2/5/8，4 番） */
    val jiangEnabled: Boolean = false,

    /** 根的计法：加番 / 加底 / 关闭 */
    val genMode: GenMode = GenMode.FAN,
    /** true = 只有杠（明杠/暗杠）算根；false（默认）= 碰+第4张、手握4张 也算根 */
    val onlyKongCountsAsGen: Boolean = false,

    /** 杠上开花 +1 番 */
    val kongBloomEnabled: Boolean = true,
) {
    companion object {
        val fanCapChoices = listOf(0, 3, 4, 5)

        fun fanCapLabel(cap: Int): String =
            if (cap == 0) "不封顶" else "$cap 番（${1 shl cap} 倍）"
    }
}

// MARK: - 胡牌场景

/**
 * 胡牌瞬间的场景信息，决定场景番。
 * 自摸侧：杠上开花 / 海底捞月 / 天胡；点炮侧：杠上炮 / 抢杠胡 / 地胡。
 */
data class WinContext(
    val selfDrawn: Boolean,
    /** 杠上开花（自摸 +1 番，受规则开关控制） */
    val kongBloom: Boolean = false,
    /** 海底捞月（摸最后一张自摸，+1 番） */
    val lastTileDraw: Boolean = false,
    /** 杠上炮（胡别家杠后打出的牌，+1 番） */
    val kongDischargeWin: Boolean = false,
    /** 抢杠胡（+1 番） */
    val robbingKong: Boolean = false,
    /** 天胡（庄家起手胡，+4 番） */
    val heavenly: Boolean = false,
    /** 地胡（胡第一张打出的牌，+4 番） */
    val earthly: Boolean = false,
)

// MARK: - 算番结果

/** 一项番型 */
data class FanItem(
    /** 番型名（中文，同时作本地化 key） */
    val name: String,
    /** 番数（进胡牌倍数） */
    val fan: Int,
    /** 额外加底单位数（加底类：根加底 / 自摸加底，进金额不进番） */
    val baseAdd: Int = 0,
) {
    /** 中文加成文字（日志/测试用；UI 显示走本地化格式） */
    val fanText: String
        get() = when {
            baseAdd > 0 -> "+$baseAdd 底"
            fan == 0 -> "0 番"
            else -> "+$fan 番"
        }
}

data class WinScore(
    val items: List<FanItem>,
    val totalFan: Int,
    /** 封顶后的番数 */
    val cappedFan: Int,
    /** 单家金额 */
    val money: Double,
) {
    val isCapped: Boolean get() = cappedFan < totalFan
}

// MARK: - 牌型拆解

/** 暗牌能否拆成「1 将对 + 若干刻子」（全刻子，无顺子）——判碰碰胡 / 将对 */
private fun canBeAllTriplets(freq: IntArray): Boolean {
    val c = freq.copyOf()

    fun rec(start: Int): Boolean {
        var j = start
        while (j < 27 && c[j] == 0) j += 1
        if (j == 27) return true
        if (c[j] < 3) return false   // 剩零散牌无法成刻子
        c[j] -= 3
        val ok = rec(j)
        c[j] += 3
        return ok
    }

    for (i in 0..26) {
        if (c[i] >= 2) {
            c[i] -= 2
            val ok = rec(0)
            c[i] += 2
            if (ok) return true
        }
    }
    return false
}

// MARK: - 算番

/**
 * 对一副完整胡牌算番与金额。
 * concealed：暗牌部分频率数组（含所胡那张，共 14 − 3 × 副露组数 张）
 */
fun scoreWinningHand(
    concealed: IntArray,
    melds: List<Meld>,
    settings: RuleSettings,
    context: WinContext,
): WinScore {
    val combined = concealed.copyOf()
    for (m in melds) combined[m.card.tileIndex] += m.tileCount
    val sum = concealed.sum()

    val items = mutableListOf<FanItem>()
    fun add(name: String, fan: Int) {
        items.add(FanItem(name, fan))
    }

    /** 按 genMode 结算 count 个根：加番记番、加底记底、关闭不计 */
    fun applyGen(count: Int) {
        if (count <= 0) return
        when (settings.genMode) {
            GenMode.FAN -> items.add(FanItem("根", count))
            GenMode.BASE -> items.add(FanItem("根", 0, baseAdd = count))
            GenMode.OFF -> {}
        }
    }

    val isQingYiSe = suitCount(combined) == 1
    // 门清：无碰、无明杠（暗杠可，空副露也算）
    val isMenQing = melds.all { it.kind == Meld.Kind.CONCEALED_KONG }
    // 断幺九 / 将牌都按整副牌（暗牌 + 副露）判
    val ranksPresent = (0..26).filter { combined[it] > 0 }.map { it % 9 + 1 }
    val noTerminals = ranksPresent.all { it != 1 && it != 9 }
    val allJiangRanks = ranksPresent.all { it == 2 || it == 5 || it == 8 }  // 将牌 2/5/8

    if (melds.isEmpty() && sum == 14 && (0..26).all { concealed[it] % 2 == 0 }) {
        // 七小对家族：4 张同牌算一「龙」
        val dragons = concealed.count { it == 4 }
        if (settings.jiangEnabled && allJiangRanks) {
            add("将七对", 4)
            applyGen(dragons)               // 龙作根，按 genMode 结算
        } else if (settings.qiXiaoDuiEnabled) {
            if (settings.haoHuaEnabled && dragons > 0) {
                val names = listOf("", "豪华七小对", "双豪华七小对", "三豪华七小对")
                add(names[min(dragons, 3)], 2 + dragons)
            } else {
                add("七小对", 2)             // 豪华关或无龙 → 平七小对
            }
        }
        // 七小对关且非将七对：七对牌型不计基础番（门清 / 断幺九 等仍照常叠加）
    } else {
        // 标准形
        val isGoldenHook = melds.size == 4   // 金钩钓：单钓将，手里只剩 2 张
        val isAllTriplets = canBeAllTriplets(concealed) && !isGoldenHook  // 金钩钓已含碰碰胡
        val pengFanValue = if (settings.jiangEnabled && allJiangRanks) 3 else 1

        // 根：默认「凑齐 4 张同牌」（杠 / 手握 4 张 / 碰 + 手里第 4 张）；
        // 开启「只有杠才算根」时仅数明杠/暗杠
        var genCount = if (settings.onlyKongCountsAsGen)
            melds.count { it.kind.isKong }
        else
            (0..26).count { combined[it] == 4 }

        // 十八罗汉：金钩钓 + 4 个杠，且根计加番时才合并命名（4 根并入名内）
        val isEighteenArhats = isGoldenHook && melds.all { it.kind.isKong }
            && settings.genMode == GenMode.FAN
        if (isEighteenArhats) {
            add("十八罗汉", settings.goldenHookFan + 4)
            genCount = maxOf(0, genCount - melds.count { it.kind.isKong })
        } else if (isGoldenHook) {
            add("金钩钓", settings.goldenHookFan)
        }
        if (isAllTriplets) {
            if (pengFanValue == 3) {
                add("将对", 3)                              // 将对：将牌 2/5/8 的碰碰胡
            } else if (settings.pengPengHuEnabled) {
                add("碰碰胡", 1)
            }
        }
        applyGen(genCount)
    }

    if (isQingYiSe && settings.qingYiSeEnabled) add("清一色", 2)
    if (isMenQing && settings.menQingEnabled) add("门清", 1)
    if (noTerminals && settings.duanYaoJiuEnabled) add("断幺九", 1)

    // 场景番
    if (context.selfDrawn) {
        if (context.kongBloom && settings.kongBloomEnabled) add("杠上开花", 1)
        if (context.lastTileDraw) add("海底捞月", 1)
        if (context.heavenly) add("天胡", 4)
        if (settings.selfDrawAddsFan) {
            add("自摸", 1)
        } else {
            items.add(FanItem("自摸", 0, baseAdd = 1))   // 自摸加底
        }
    } else {
        if (context.kongDischargeWin) add("杠上炮", 1)
        if (context.robbingKong) add("抢杠胡", 1)
        if (context.earthly) add("地胡", 4)
    }

    if (items.isEmpty()) {
        add("平胡", 0)   // 兜底；平胡开关对金额无影响
    }

    val totalFan = items.sumOf { it.fan }
    val totalBaseAdd = items.sumOf { it.baseAdd }
    val cappedFan = if (settings.fanCap > 0) min(totalFan, settings.fanCap) else totalFan
    var money = settings.baseStake * 2.0.pow(cappedFan.toDouble())
    money += settings.baseStake * totalBaseAdd   // 加底类（根加底 / 自摸加底）

    return WinScore(items = items, totalFan = totalFan, cappedFan = cappedFan, money = money)
}

/** 金额显示：整数不带小数，非整数按需保留（如 ¥0.5），对齐 iOS 的 "%g" */
fun moneyText(value: Double): String {
    val s = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    return "¥$s"
}
