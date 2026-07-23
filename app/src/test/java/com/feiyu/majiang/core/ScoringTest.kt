//
//  ScoringTest.kt
//  四川川麻算番引擎断言 —— 与 iOS Tests/ScoringTests.swift 逐条对应（T1–T34）。
//  覆盖：基础番开关、门清、断幺九、根三选一、十八罗汉/金钩钓、将对/将七对、
//        场景番、清一色回归、封顶、综合叠加。
//

package com.feiyu.majiang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {

    // 手牌简写："123m45s" → 万/条/筒（m 万 / s 条 / p 筒），与 iOS 测试相同
    private fun tiles(s: String): List<MahjongCard> {
        val out = mutableListOf<MahjongCard>()
        val digits = mutableListOf<Int>()
        for (ch in s) {
            if (ch.isDigit()) digits.add(ch.digitToInt())
            else if (ch != ' ') {
                val suit = when (ch) {
                    'm' -> MahjongCard.Suit.WAN
                    'p' -> MahjongCard.Suit.TONG
                    else -> MahjongCard.Suit.TIAO
                }
                out.addAll(digits.map { MahjongCard(suit, it) })
                digits.clear()
            }
        }
        return out
    }

    private fun freq(s: String): IntArray = handToFrequency27(tiles(s))
    private fun m(k: Meld.Kind, s: String): Meld = Meld(k, tiles(s).first())
    private fun names(s: WinScore): List<String> = s.items.map { it.name }

    private val point = WinContext(selfDrawn = false)
    private val draw = WinContext(selfDrawn = true)

    private fun sc(
        hand: String,
        melds: List<Meld> = emptyList(),
        ctx: WinContext = point,
        tweak: (RuleSettings) -> RuleSettings = { it },
    ): WinScore = scoreWinningHand(freq(hand), melds, tweak(RuleSettings()), ctx)

    private fun expect(
        label: String, s: WinScore, fan: Int, money: Double,
        has: List<String> = emptyList(), hasnt: List<String> = emptyList(),
    ) {
        assertEquals("$label totalFan (got ${names(s)})", fan, s.totalFan)
        assertEquals("$label money (got ${names(s)})", money, s.money, 0.0)
        for (n in has) assertTrue("$label 应含 $n（got ${names(s)}）", names(s).contains(n))
        for (n in hasnt) assertTrue("$label 不应含 $n（got ${names(s)}）", !names(s).contains(n))
    }

    @Test fun t1_defaults() {
        val d = RuleSettings()
        assertTrue("T1 计钱/根/金钩钓/将/杠花默认",
            d.fanCap == 0 && d.selfDrawAddsFan && d.genMode == GenMode.FAN
                && d.goldenHookFan == 2 && !d.jiangEnabled && d.kongBloomEnabled)
        assertTrue("T1 基础番+门清+断幺 默认全开",
            d.pingHuEnabled && d.pengPengHuEnabled && d.qingYiSeEnabled && d.qiXiaoDuiEnabled
                && d.haoHuaEnabled && d.menQingEnabled && d.duanYaoJiuEnabled)
    }

    @Test fun t2_pingHu() =
        expect("T2 平胡基线", sc("123456789m55s", listOf(m(Meld.Kind.PONG, "7s"))),
            fan = 0, money = 1.0, has = listOf("平胡"))

    @Test fun t3_pengPengHu() {
        expect("T3 碰碰胡", sc("222333m999s88s", listOf(m(Meld.Kind.PONG, "1m"))),
            fan = 1, money = 2.0, has = listOf("碰碰胡"))
        expect("T3a 碰碰胡关", sc("222333m999s88s", listOf(m(Meld.Kind.PONG, "1m"))) { it.copy(pengPengHuEnabled = false) },
            fan = 0, money = 1.0, has = listOf("平胡"), hasnt = listOf("碰碰胡"))
    }

    @Test fun t4_qingYiSe() {
        expect("T4 清一色", sc("234567m234m99m", listOf(m(Meld.Kind.PONG, "1m"))),
            fan = 2, money = 4.0, has = listOf("清一色"))
        expect("T4a 清一色关", sc("234567m234m99m", listOf(m(Meld.Kind.PONG, "1m"))) { it.copy(qingYiSeEnabled = false) },
            fan = 0, money = 1.0, hasnt = listOf("清一色"))
    }

    @Test fun t5_qiXiaoDui() {
        expect("T5 七小对", sc("11m88m99m22s55s66s77s"),
            fan = 3, money = 8.0, has = listOf("七小对", "门清"))
        expect("T5a 七小对关", sc("11m88m99m22s55s66s77s") { it.copy(qiXiaoDuiEnabled = false) },
            fan = 1, money = 2.0, has = listOf("门清"), hasnt = listOf("七小对"))
    }

    @Test fun t6_haoHua() {
        expect("T6 豪华七小对", sc("1111m88m99m22s55s66s"),
            fan = 4, money = 16.0, has = listOf("豪华七小对", "门清"))
        expect("T6a 豪华关(七小对开)", sc("1111m88m99m22s55s66s") { it.copy(haoHuaEnabled = false) },
            fan = 3, money = 8.0, has = listOf("七小对", "门清"), hasnt = listOf("豪华七小对"))
    }

    @Test fun t7to12_menQing() {
        expect("T7 门清标准", sc("123456789m234s55s"),
            fan = 1, money = 2.0, has = listOf("门清"), hasnt = listOf("平胡"))
        expect("T8 带碰无门清", sc("123456789m22s", listOf(m(Meld.Kind.PONG, "5s"))),
            fan = 0, money = 1.0, hasnt = listOf("门清"))
        expect("T9 明杠无门清(根关)", sc("123456789m22m", listOf(m(Meld.Kind.EXPOSED_KONG, "5s"))) { it.copy(genMode = GenMode.OFF) },
            fan = 0, money = 1.0, hasnt = listOf("门清"))
        expect("T10 仅暗杠有门清(根关)", sc("123456789m22m", listOf(m(Meld.Kind.CONCEALED_KONG, "5s"))) { it.copy(genMode = GenMode.OFF) },
            fan = 1, money = 2.0, has = listOf("门清"))
        expect("T11 门清关", sc("123456789m234s55s") { it.copy(menQingEnabled = false) },
            fan = 0, money = 1.0, hasnt = listOf("门清"))
        expect("T12 门清自摸", sc("123456789m234s55s", ctx = draw),
            fan = 2, money = 4.0, has = listOf("门清", "自摸"))
    }

    @Test fun t13_duanYaoJiu() {
        expect("T13 断幺九", sc("234m567m234s567s88m"),
            fan = 2, money = 4.0, has = listOf("门清", "断幺九"))
        expect("T13a 断幺关", sc("234m567m234s567s88m") { it.copy(duanYaoJiuEnabled = false) },
            fan = 1, money = 2.0, hasnt = listOf("断幺九"))
    }

    @Test fun t14_genModes() {
        val genHand = "234567m234s88s"
        val genMeld = listOf(m(Meld.Kind.CONCEALED_KONG, "5s"))
        expect("T14 根加番", sc(genHand, genMeld),
            fan = 3, money = 8.0, has = listOf("门清", "断幺九", "根"))
        expect("T14b 根加底", sc(genHand, genMeld) { it.copy(genMode = GenMode.BASE) },
            fan = 2, money = 5.0, has = listOf("门清", "断幺九", "根"))
        expect("T14c 根关闭", sc(genHand, genMeld) { it.copy(genMode = GenMode.OFF) },
            fan = 2, money = 4.0, hasnt = listOf("根"))
    }

    private val arhat = listOf(
        Meld(Meld.Kind.CONCEALED_KONG, MahjongCard(MahjongCard.Suit.WAN, 1)),
        Meld(Meld.Kind.CONCEALED_KONG, MahjongCard(MahjongCard.Suit.WAN, 2)),
        Meld(Meld.Kind.CONCEALED_KONG, MahjongCard(MahjongCard.Suit.WAN, 3)),
        Meld(Meld.Kind.CONCEALED_KONG, MahjongCard(MahjongCard.Suit.WAN, 4)),
    )
    private val gold = listOf(
        Meld(Meld.Kind.PONG, MahjongCard(MahjongCard.Suit.WAN, 1)),
        Meld(Meld.Kind.PONG, MahjongCard(MahjongCard.Suit.WAN, 2)),
        Meld(Meld.Kind.PONG, MahjongCard(MahjongCard.Suit.WAN, 3)),
        Meld(Meld.Kind.PONG, MahjongCard(MahjongCard.Suit.WAN, 4)),
    )

    @Test fun t15_eighteenArhats() {
        expect("T15 十八罗汉(根加番)", sc("55m", arhat),
            fan = 9, money = 512.0, has = listOf("十八罗汉", "清一色", "门清"))
        expect("T15b 根关闭降级", sc("55m", arhat) { it.copy(genMode = GenMode.OFF) },
            fan = 5, money = 32.0, has = listOf("金钩钓", "清一色", "门清"), hasnt = listOf("十八罗汉"))
        expect("T15c 根加底", sc("55m", arhat) { it.copy(genMode = GenMode.BASE) },
            fan = 5, money = 36.0, has = listOf("金钩钓", "清一色", "门清"))
    }

    @Test fun t16_goldenHook() {
        expect("T16 金钩钓", sc("55m", gold),
            fan = 4, money = 16.0, has = listOf("金钩钓", "清一色"), hasnt = listOf("碰碰胡"))
        expect("T16a 金钩钓番=1", sc("55m", gold) { it.copy(goldenHookFan = 1) },
            fan = 3, money = 8.0, has = listOf("金钩钓", "清一色"))
    }

    @Test fun t17t18_jiang() {
        expect("T17 将对关", sc("222555888m555s22s"),
            fan = 3, money = 8.0, has = listOf("碰碰胡", "门清", "断幺九"))
        expect("T17b 将对开", sc("222555888m555s22s") { it.copy(jiangEnabled = true) },
            fan = 5, money = 32.0, has = listOf("将对", "门清", "断幺九"), hasnt = listOf("碰碰胡"))
        expect("T18 将七对关", sc("2222m55m88m22s55s88s"),
            fan = 5, money = 32.0, has = listOf("豪华七小对", "门清", "断幺九"))
        expect("T18b 将七对开", sc("2222m55m88m22s55s88s") { it.copy(jiangEnabled = true) },
            fan = 7, money = 128.0, has = listOf("将七对", "根", "门清", "断幺九"))
    }

    @Test fun t31t32_onlyKongGen() {
        // 用户示例：碰 555万 + 手里第 4 张 5万（在 345 顺子里）→ 默认算 1 根
        val userMeld = listOf(m(Meld.Kind.PONG, "5m"))
        expect("T31 碰+第4张=根(默认)", sc("12334566677m", userMeld),
            fan = 3, money = 8.0, has = listOf("根", "清一色"))
        expect("T31b 只有杠才算根→无根", sc("12334566677m", userMeld) { it.copy(onlyKongCountsAsGen = true) },
            fan = 2, money = 4.0, hasnt = listOf("根"))
        // 暗杠两种模式都算根
        expect("T31c 暗杠始终算根", sc("234567m234s88s", listOf(m(Meld.Kind.CONCEALED_KONG, "5s"))) { it.copy(onlyKongCountsAsGen = true) },
            fan = 3, money = 8.0, has = listOf("根"))
        // 龙七对：龙走「豪华」命名，不另计根（不与根重复）
        expect("T32 龙七对无重复根", sc("1111m22m33m44m55m66m"),
            fan = 6, money = 64.0, has = listOf("豪华七小对", "清一色"), hasnt = listOf("根"))
        expect("T32b 龙七对+只有杠 仍无根", sc("1111m22m33m44m55m66m") { it.copy(onlyKongCountsAsGen = true) },
            fan = 6, money = 64.0, has = listOf("豪华七小对"), hasnt = listOf("根"))
    }

    @Test fun t19to26_sceneFans() {
        val base = "123456789m234s55s"
        expect("T19 点炮", sc(base), fan = 1, money = 2.0, has = listOf("门清"))
        expect("T20 自摸加番", sc(base, ctx = draw), fan = 2, money = 4.0, has = listOf("门清", "自摸"))
        expect("T20a 自摸加底", sc(base, ctx = draw) { it.copy(selfDrawAddsFan = false) },
            fan = 1, money = 3.0, has = listOf("门清"))
        expect("T21 杠上开花", sc(base, ctx = WinContext(selfDrawn = true, kongBloom = true)),
            fan = 3, money = 8.0, has = listOf("门清", "杠上开花", "自摸"))
        expect("T22 海底捞月", sc(base, ctx = WinContext(selfDrawn = true, lastTileDraw = true)),
            fan = 3, money = 8.0, has = listOf("门清", "海底捞月", "自摸"))
        expect("T23 天胡", sc(base, ctx = WinContext(selfDrawn = true, heavenly = true)),
            fan = 6, money = 64.0, has = listOf("门清", "天胡", "自摸"))
        expect("T24 杠上炮", sc(base, ctx = WinContext(selfDrawn = false, kongDischargeWin = true)),
            fan = 2, money = 4.0, has = listOf("门清", "杠上炮"))
        expect("T25 抢杠胡", sc(base, ctx = WinContext(selfDrawn = false, robbingKong = true)),
            fan = 2, money = 4.0, has = listOf("门清", "抢杠胡"))
        expect("T26 地胡", sc(base, ctx = WinContext(selfDrawn = false, earthly = true)),
            fan = 5, money = 32.0, has = listOf("门清", "地胡"))
    }

    @Test fun t27t28t30_regressionCapCombo() {
        assertTrue("T27a 清一色暗杠副露",
            names(sc("111222333p55p", listOf(m(Meld.Kind.CONCEALED_KONG, "9p")))).contains("清一色"))
        assertTrue("T27b 清一色金钩钓", names(sc("55m", gold)).contains("清一色"))
        assertTrue("T27c 清一色七对", names(sc("11p22p33p44p55p66p77p")).contains("清一色"))
        expect("T28 封顶3(十八罗汉)", sc("55m", arhat) { it.copy(fanCap = 3) }, fan = 9, money = 8.0)
        expect("T28b 封顶4", sc("55m", arhat) { it.copy(fanCap = 4) }, fan = 9, money = 16.0)
        expect("T30 清对", sc("222333888m99m", listOf(m(Meld.Kind.PONG, "1m"))),
            fan = 3, money = 8.0, has = listOf("清一色", "碰碰胡"))
    }

    // 补充：金额显示与 iOS "%g" 对齐
    @Test fun moneyTextFormat() {
        assertEquals("¥1", moneyText(1.0))
        assertEquals("¥0.5", moneyText(0.5))
        assertEquals("¥512", moneyText(512.0))
        assertEquals("¥2.5", moneyText(2.5))
    }
}
