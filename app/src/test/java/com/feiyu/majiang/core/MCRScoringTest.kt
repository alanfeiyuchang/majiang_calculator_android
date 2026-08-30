//
//  MCRScoringTest.kt
//  国标麻将（MCR）引擎断言 —— 与 iOS Tests/MCRScoringTests.swift 逐条对应。
//  覆盖：牌张模型、和牌牌型、向听/听牌/进张、81 番型、不重复计算原则、
//        起和 8 分、5 项规则细则的开/关两种状态、打牌建议、四川侧回归护栏。
//
//  牌面写法：数字 + 花色字母
//    m 万 / p 筒 / s 条 / z 字（1–4 = 东南西北，5–7 = 中发白）/ f 花（1–8 = 春夏秋冬梅兰竹菊）
//

package com.feiyu.majiang.core

import com.feiyu.majiang.RuleSettingsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MCRScoringTest {

    // MARK: 辅助

    private fun mt(s: String): List<MahjongCard> {
        val out = mutableListOf<MahjongCard>()
        val digits = mutableListOf<Int>()
        for (ch in s) {
            if (ch.isDigit()) { digits.add(ch.digitToInt()); continue }
            when (ch) {
                'm' -> out.addAll(digits.map { MahjongCard(MahjongCard.Suit.WAN, it) })
                'p' -> out.addAll(digits.map { MahjongCard(MahjongCard.Suit.TONG, it) })
                's' -> out.addAll(digits.map { MahjongCard(MahjongCard.Suit.TIAO, it) })
                'z' -> out.addAll(digits.map {
                    if (it <= 4) MahjongCard(MahjongCard.Suit.FENG, it)
                    else MahjongCard(MahjongCard.Suit.JIAN, it - 4)
                })
                'f' -> out.addAll(digits.map { MahjongCard(MahjongCard.Suit.HUA, it) })
            }
            digits.clear()
        }
        return out
    }

    private fun mfreq(s: String): IntArray = handToFrequency34(mt(s))

    /** 副露；吃传起始牌（如 mm(CHOW, "4p") = 吃 456 筒） */
    private fun mm(k: Meld.Kind, s: String): Meld = Meld(k, mt(s).first())

    private fun names(s: MCRScore): List<String> = s.items.map { it.name }

    /** 算一副国标和牌 */
    private fun msc(
        hand: String,
        melds: List<Meld> = emptyList(),
        win: String,
        selfDrawn: Boolean = false,
        flowers: Int = 0,
        options: MCROptions = MCROptions(),
        tweak: (MCRContext) -> MCRContext = { it },
    ): MCRScore {
        val ctx = tweak(
            MCRContext(
                selfDrawn = selfDrawn,
                winningTile = mt(win).first().mcrIndex,
                flowers = flowers,
            )
        )
        return scoreMCRHand(mfreq(hand), melds, ctx, options)
    }

    private fun detail(s: MCRScore) =
        s.items.joinToString("+") { it.name + if (it.count > 1) "×${it.count}" else "" }

    /** 断言：分数 + 必须含 / 必须不含 的番型 */
    private fun expect(
        label: String,
        s: MCRScore,
        points: Int? = null,
        has: List<String> = emptyList(),
        hasnt: List<String> = emptyList(),
    ) {
        val got = "got ${s.scoringPoints}分 [${detail(s)}]"
        if (points != null) assertEquals("$label $got", points, s.scoringPoints)
        for (n in has) assertTrue("$label 缺少「$n」 $got", names(s).contains(n))
        for (n in hasnt) assertFalse("$label 不该有「$n」 $got", names(s).contains(n))
    }

    // MARK: - 牌张模型

    @Test
    fun m1_honorIndices() {
        assertEquals(27, MahjongCard(MahjongCard.Suit.FENG, 1).mcrIndex)
        assertEquals(30, MahjongCard(MahjongCard.Suit.FENG, 4).mcrIndex)
        assertEquals(31, MahjongCard(MahjongCard.Suit.JIAN, 1).mcrIndex)
        assertEquals(33, MahjongCard(MahjongCard.Suit.JIAN, 3).mcrIndex)
    }

    @Test
    fun m2_flowersHaveNoIndex() {
        assertEquals(-1, MahjongCard(MahjongCard.Suit.HUA, 1).mcrIndex)
        assertEquals(-1, MahjongCard(MahjongCard.Suit.HUA, 1).tileIndex)
    }

    @Test
    fun m3_indexRoundTrip() {
        assertTrue((0..33).all { MahjongCard.fromMCRIndex(it).mcrIndex == it })
    }

    @Test
    fun m4_faceText() {
        assertEquals("东", MahjongCard(MahjongCard.Suit.FENG, 1).displayText)
        assertEquals("发", MahjongCard(MahjongCard.Suit.JIAN, 2).displayText)
        assertEquals("梅", MahjongCard(MahjongCard.Suit.HUA, 5).displayText)
        assertEquals("三万", MahjongCard(MahjongCard.Suit.WAN, 3).displayText)
    }

    @Test
    fun m5_allMCRTiles() {
        assertEquals(34 + 8, MahjongCard.allMCRTilesInOrder().size)
    }

    @Test
    fun m6_onlyNumberedHaveArtwork() {
        assertFalse(MahjongCard(MahjongCard.Suit.FENG, 1).hasImageAsset)
        assertTrue(MahjongCard(MahjongCard.Suit.WAN, 1).hasImageAsset)
    }

    @Test
    fun m7_chowIsThreeConsecutive() {
        val chow = mm(Meld.Kind.CHOW, "4p")
        assertEquals(listOf(4, 5, 6), chow.tiles.map { it.rank })
        assertEquals(3, chow.tileCount)
        assertFalse(chow.kind.isKong)
    }

    @Test
    fun m8_chowFrequency() {
        val f = IntArray(34).also { it[12] = 1; it[13] = 1; it[14] = 1 }
        assertArrayEqualsInt(f, meldsToFrequency34(listOf(mm(Meld.Kind.CHOW, "4p"))))
    }

    @Test
    fun m9_honorsSkip27Index() {
        assertEquals(0, meldsToFrequency27(listOf(mm(Meld.Kind.PONG, "1z"))).sum())
    }

    private fun assertArrayEqualsInt(a: IntArray, b: IntArray) =
        assertEquals(a.toList(), b.toList())

    // MARK: - 和牌牌型

    @Test
    fun w_winningShapes() {
        assertTrue("W1 标准型含字牌", mcrIsWinningHand(mfreq("123m456p789s11122z")))
        assertTrue("W2 三门齐全也能和", mcrIsWinningHand(mfreq("123m456m789m123p55p")))
        assertTrue("W3 七对", mcrIsWinningHand(mfreq("1133557799m1133p")))
        assertTrue("W4 连七对", mcrIsSevenShiftedPairs(mfreq("11223344556677m")))
        assertFalse("W4a 普通七对不是连七对", mcrIsSevenShiftedPairs(mfreq("1133557799m1133p")))
        assertTrue("W5 十三幺", mcrIsThirteenOrphans(mfreq("19m19p19s12345677z")))
        assertFalse("W5a 多一张不是十三幺", mcrIsThirteenOrphans(mfreq("19m19p19s11234567z1z")))
        assertTrue("W6 全不靠", mcrIsKnittedNoSets(mfreq("147m258p369s12345z")))
        assertTrue("W7 七星不靠", mcrIsSevenStarsKnitted(mfreq("147m25p36s1234567z")))
        assertTrue("W8 组合龙型", mcrIsKnittedStraightForm(mfreq("147m258p369s555m11p")))
        assertTrue("W9 九莲宝灯是标准型", mcrIsWinningHand(mfreq("11123456789995m")))
        assertFalse("W10 缺一张不算和", mcrIsWinningHand(mfreq("123m456p789s1112z")))
        assertFalse("W11 东南西不成顺子", mcrIsWinningHand(mfreq("123z456p789s11122m")))
        assertTrue(
            "W12 吃 + 碰 参与和牌",
            mcrIsWinningHand(mfreq("123m456m11p"), listOf(mm(Meld.Kind.CHOW, "7m"), mm(Meld.Kind.PONG, "1z")))
        )
    }

    // MARK: - 向听 / 听牌 / 进张

    @Test
    fun s_shantenAndWaits() {
        assertEquals("S1 已和 = -1", -1, mcrHandShanten(mt("123m456m789m123p55p")))
        assertEquals("S2 单钓字牌听牌", 0, mcrHandShanten(mt("123m456m789m123p1z")))

        val w = mcrCalculateWaiting(mt("123m456m789m123p1z"))
        assertEquals("S3 听东风", listOf(MahjongCard(MahjongCard.Suit.FENG, 1)), w)

        // 十三幺听 13 面
        assertEquals("S4 十三幺听十三面", 13, mcrCalculateWaiting(mt("19m19p19s1234567z")).size)

        assertEquals(
            "S5 带吃/碰 单钓一筒", listOf("一筒"),
            mcrCalculateWaiting(mt("123m456m1p"), listOf(mm(Meld.Kind.CHOW, "7m"), mm(Meld.Kind.PONG, "1z")))
                .map { it.displayText }
        )

        assertEquals(
            "S6 进张含字牌", setOf("东", "南"),
            mcrAcceptanceTiles(mt("123m456m789m11z22z")).map { it.card.displayText }.toSet()
        )

        assertEquals(
            "S7 打牌建议：最优弃牌听牌", 0,
            mcrDiscardSuggestions(mt("123m456m789m123p1z2z")).first().resultingShanten
        )

        assertTrue("S8 七对向听可达 0", mcrHandShanten(mt("1122334455667m8m")) <= 0)
    }

    // MARK: - 番种表完整性

    @Test
    fun t_fanTableIntegrity() {
        // 官方 81 番 + 明暗杠。明暗杠不在 98 规则的 81 番里，但现行通行（含官方竞赛
        // 算番器）把「一明杠 + 一暗杠」当独立番种计 5 分，由 mcrOneOpenOneConcealedKong 控制。
        assertEquals("T1 番种表 = 官方 81 种 + 明暗杠", 82, MCR_FAN_POINTS.size)
        assertEquals("T1b 明暗杠 5 分", 5, MCR_FAN_POINTS["明暗杠"])

        val unknown = MCR_FAN_EXCLUDES.flatMap { listOf(it.key) + it.value }
            .filter { MCR_FAN_POINTS[it] == null }
        assertTrue("T2 排除表里没有拼错的番型名：$unknown", unknown.isEmpty())

        val selfRef = MCR_FAN_EXCLUDES.filter { it.key in it.value }.keys
        val cycles = MCR_FAN_EXCLUDES.filter { kv ->
            kv.value.any { MCR_FAN_EXCLUDES[it]?.contains(kv.key) == true }
        }.keys
        assertTrue("T3 排除表无自指 / 互斥环：$selfRef $cycles", selfRef.isEmpty() && cycles.isEmpty())

        val bad = MCR_FAN_EXCLUDES.flatMap { kv ->
            kv.value.filter { (MCR_FAN_POINTS[it] ?: 0) > (MCR_FAN_POINTS[kv.key] ?: 0) }
                .map { "${kv.key}→$it" }
        }
        assertTrue("T4 只排除分值不更高的番型：$bad", bad.isEmpty())

        assertEquals("T5 起和线 8 分", 8, MCR_MINIMUM_POINTS)
    }

    @Test
    fun t6_everyFanHasAnExplanationAndAGroup() {
        val missing = MCR_FAN_POINTS.keys.filter { MCRFanInfo.table[it] == null }
        assertTrue("每个番型都要有一句话含义：$missing", missing.isEmpty())
        val grouped = MCRFanInfo.groups.flatMap { it.second }
        assertEquals("分档表覆盖全部番种", MCR_FAN_POINTS.keys.sorted(), grouped.sorted())
        val wrong = MCRFanInfo.groups.flatMap { (p, ns) -> ns.filter { MCR_FAN_POINTS[it] != p } }
        assertTrue("分档表的分值要对得上：$wrong", wrong.isEmpty())
    }

    // MARK: - 88 分

    @Test
    fun f88() {
        expect(
            "F88-1 大四喜", msc("111222333444z55m", win = "5m"),
            has = listOf("大四喜"), hasnt = listOf("三风刻", "圈风刻", "门风刻", "碰碰和", "幺九刻")
        )
        expect(
            "F88-2 大三元", msc("555666777z123m11p", win = "1p"),
            has = listOf("大三元"), hasnt = listOf("箭刻", "双箭刻")
        )
        expect(
            "F88-3 绿一色", msc("222333444666s88s", win = "8s"),
            has = listOf("绿一色"), hasnt = listOf("缺一门")
        )
        expect(
            // 九莲宝灯把幺九刻**减 1**（不是整个吸收）：手里 111/999 两个幺九刻时官方留 1 个。
            // 官方 91 = 九莲宝灯 88 + 双暗刻 2 + 幺九刻 1。
            "F88-4 九莲宝灯", msc("11123456789995m", win = "5m"), points = 91,
            has = listOf("九莲宝灯", "幺九刻"), hasnt = listOf("清一色", "门前清", "无字")
        )
        expect(
            "F88-5 四杠",
            msc(
                "11m",
                melds = listOf(
                    mm(Meld.Kind.EXPOSED_KONG, "2m"), mm(Meld.Kind.EXPOSED_KONG, "3m"),
                    mm(Meld.Kind.CONCEALED_KONG, "4m"), mm(Meld.Kind.CONCEALED_KONG, "5m")
                ),
                win = "1m"
            ),
            has = listOf("四杠"),
            hasnt = listOf("三杠", "双明杠", "双暗杠", "明杠", "暗杠", "碰碰和", "单钓将")
        )
        expect(
            "F88-6 连七对", msc("11223344556677m", win = "7m"), points = 88,
            has = listOf("连七对"), hasnt = listOf("七对", "清一色", "门前清", "无字", "单钓将")
        )
        expect(
            "F88-7 十三幺", msc("19m19p19s12345677z", win = "7z"), points = 88,
            has = listOf("十三幺"), hasnt = listOf("五门齐", "门前清", "单钓将")
        )
    }

    // MARK: - 64 分

    @Test
    fun f64() {
        expect(
            "F64-1 清幺九", msc("111999m111999p11s", win = "1s"),
            has = listOf("清幺九", "四暗刻"), hasnt = listOf("碰碰和", "全带幺", "幺九刻", "无字")
        )
        expect(
            "F64-2 小四喜", msc("111222333z44z123m", win = "3m"),
            has = listOf("小四喜"), hasnt = listOf("三风刻")
        )
        expect(
            "F64-3 小三元", msc("555666z77z123m456m", win = "6m"),
            has = listOf("小三元"), hasnt = listOf("双箭刻", "箭刻")
        )
        expect(
            "F64-4 字一色", msc("111222333z55566z", win = "6z"),
            has = listOf("字一色"), hasnt = listOf("碰碰和", "全带幺", "幺九刻", "缺一门", "无字")
        )
        expect(
            "F64-5 四暗刻", msc("111m333p555s777z99m", win = "9m", selfDrawn = true),
            has = listOf("四暗刻"), hasnt = listOf("碰碰和", "门前清", "三暗刻", "双暗刻")
        )
        expect(
            "F64-6 一色双龙会", msc("123123789789m55m", win = "5m"),
            has = listOf("一色双龙会"), hasnt = listOf("清一色", "平和", "一般高", "老少副")
        )
    }

    // MARK: - 48 / 32 分

    @Test
    fun f48and32() {
        expect(
            "F48-1 一色四同顺", msc("123123123123m55m", win = "5m"),
            has = listOf("一色四同顺"), hasnt = listOf("一色三同顺", "一般高", "四归一")
        )
        expect(
            "F48-2 一色四节高", msc("111222333444m55m", win = "5m"),
            has = listOf("一色四节高"), hasnt = listOf("一色三节高", "碰碰和")
        )
        expect(
            "F32-1 一色四步高", msc("123234345456m11m", win = "1m"),
            has = listOf("一色四步高"), hasnt = listOf("一色三步高", "连六", "老少副")
        )
        expect(
            "F32-2 三杠",
            msc(
                "123m11p",
                melds = listOf(
                    mm(Meld.Kind.EXPOSED_KONG, "2s"), mm(Meld.Kind.EXPOSED_KONG, "3s"),
                    mm(Meld.Kind.CONCEALED_KONG, "4s")
                ),
                win = "1p"
            ),
            has = listOf("三杠"), hasnt = listOf("双明杠", "双暗杠")
        )
        expect(
            "F32-3 混幺九", msc("111m999m111z999s55z", win = "5z"),
            has = listOf("混幺九"), hasnt = listOf("碰碰和", "全带幺", "幺九刻")
        )
    }

    // MARK: - 24 分

    @Test
    fun f24() {
        expect("F24-1 七对", msc("1133557799m1133p", win = "3p"), has = listOf("七对"), hasnt = listOf("门前清", "单钓将"))
        expect(
            "F24-2 七星不靠", msc("147m25p36s1234567z", win = "7z"),
            has = listOf("七星不靠"), hasnt = listOf("全不靠", "五门齐", "门前清")
        )
        expect(
            "F24-3 全双刻", msc("222m444m666p888s22s", win = "2s"),
            has = listOf("全双刻"), hasnt = listOf("碰碰和", "断幺")
        )
        expect("F24-4 三门齐不是清一色", msc("123456789m123p55p", win = "5p"), hasnt = listOf("清一色"))
        expect(
            "F24-4b 清一色", msc("222333444567m99m", win = "9m"),
            has = listOf("清一色"), hasnt = listOf("无字", "缺一门")
        )
        expect(
            "F24-5 一色三同顺",
            msc(
                "456m55m",
                melds = listOf(mm(Meld.Kind.CHOW, "1m"), mm(Meld.Kind.CHOW, "1m"), mm(Meld.Kind.CHOW, "1m")),
                win = "5m"
            ),
            has = listOf("一色三同顺"), hasnt = listOf("一般高")
        )
        // 同一手牌若拆成 111/222/333 刻子分更高，引擎应选刻子读法（就高不就低）
        expect(
            "F24-5b 就高：111222333 读作三节高", msc("123123123m789m55m", win = "5m"),
            has = listOf("一色三节高"), hasnt = listOf("一般高", "一色三同顺")
        )
        expect("F24-6 一色三节高", msc("111222333m789m55m", win = "5m"), has = listOf("一色三节高"))
        expect("F24-7 全大", msc("789m789p789s777m99m", win = "9m"), has = listOf("全大"), hasnt = listOf("无字"))
        expect(
            "F24-8 全中", msc("456m456p456s444m55m", win = "5m"),
            has = listOf("全中"), hasnt = listOf("无字", "断幺")
        )
        expect("F24-9 全小", msc("123m123p123s111m22m", win = "2m"), has = listOf("全小"), hasnt = listOf("无字"))
    }

    // MARK: - 16 分

    @Test
    fun f16() {
        expect("F16-1 清龙", msc("123456789m11p234p", win = "4p"), has = listOf("清龙"), hasnt = listOf("连六", "老少副"))
        expect(
            "F16-2 三色双龙会", msc("123789m123789p55s", win = "5s"),
            has = listOf("三色双龙会"), hasnt = listOf("喜相逢", "老少副", "无字", "平和")
        )
        expect(
            "F16-3 一色三步高", msc("123234345m789m55m", win = "5m"), points = 45,
            has = listOf("一色三步高", "清一色", "老少副")
        )
        expect(
            "F16-4 全带五", msc("345m456p567s555m55s", win = "5s"),
            has = listOf("全带五"), hasnt = listOf("断幺", "无字")
        )
        expect("F16-5 三同刻", msc("111m111p111s234m55m", win = "5m"), has = listOf("三同刻"), hasnt = listOf("双同刻"))
        expect("F16-6 三暗刻", msc("111m333m555p789s99s", win = "9s"), has = listOf("三暗刻"), hasnt = listOf("双暗刻"))
    }

    // MARK: - 12 分

    @Test
    fun f12() {
        expect(
            "F12-1 全不靠", msc("147m258p369s12345z", win = "5z"),
            has = listOf("全不靠", "组合龙"), hasnt = listOf("五门齐", "门前清")
        )
        expect("F12-2 组合龙", msc("147m258p369s555m11p", win = "1p"), has = listOf("组合龙"))
        expect("F12-3 大于五", msc("678m789p999s666m88m", win = "8m"), has = listOf("大于五"), hasnt = listOf("无字"))
        expect("F12-4 小于五", msc("123m234p111s444m22m", win = "2m"), has = listOf("小于五"), hasnt = listOf("无字"))
        expect("F12-5 三风刻", msc("111222333z55m111p", win = "1p"), has = listOf("三风刻"))
    }

    // MARK: - 8 分

    @Test
    fun f8() {
        expect("F8-1 花龙", msc("123m456p789s11m234s", win = "4s"), has = listOf("花龙"))
        expect("F8-2 推不倒", msc("123p234p456s777z99p", win = "9p"), has = listOf("推不倒"), hasnt = listOf("缺一门"))
        expect(
            "F8-3 三色三同顺", msc("123m123p123s456m55m", win = "5m"),
            has = listOf("三色三同顺"), hasnt = listOf("喜相逢")
        )
        expect(
            "F8-4 三色三节高", msc("111m222p333s456m55m", win = "5m"),
            has = listOf("三色三节高"), hasnt = listOf("双同刻")
        )
        expect(
            "F8-5 杠上开花",
            msc(
                "123m11p",
                melds = listOf(mm(Meld.Kind.CHOW, "4m"), mm(Meld.Kind.CHOW, "7m"), mm(Meld.Kind.EXPOSED_KONG, "2s")),
                win = "1p", selfDrawn = true
            ) { it.copy(kongBloom = true) },
            has = listOf("杠上开花"), hasnt = listOf("自摸")
        )
        expect(
            "F8-6 妙手回春",
            msc("123m456m789m123p11p", win = "1p", selfDrawn = true) { it.copy(lastTileDraw = true) },
            has = listOf("妙手回春"), hasnt = listOf("自摸")
        )
        expect(
            "F8-7 海底捞月",
            msc("123m456m789m123p11p", win = "1p") { it.copy(lastDiscard = true) },
            has = listOf("海底捞月")
        )
        expect(
            "F8-8 抢杠和",
            msc("123m456m789m123p11p", win = "1p") { it.copy(robbingKong = true, lastTileOfKind = true) },
            has = listOf("抢杠和"), hasnt = listOf("和绝张")
        )
    }

    // MARK: - 6 分

    @Test
    fun f6() {
        expect(
            "F6-1 碰碰和", msc("222m555p888s99s", melds = listOf(mm(Meld.Kind.PONG, "3m")), win = "9s"),
            has = listOf("碰碰和")
        )
        expect(
            "F6-2 混一色", msc("123m456m789m11m", melds = listOf(mm(Meld.Kind.PONG, "1z")), win = "1m"),
            has = listOf("混一色"), hasnt = listOf("缺一门")
        )
        expect("F6-3 三色三步高", msc("123m234p345s789m55m", win = "5m"), has = listOf("三色三步高"))
        expect(
            "F6-4 五门齐", msc("123m456p789s11z", melds = listOf(mm(Meld.Kind.PONG, "5z")), win = "1z"),
            has = listOf("五门齐")
        )
        expect(
            "F6-5 全求人",
            msc(
                "11p",
                melds = listOf(
                    mm(Meld.Kind.CHOW, "1m"), mm(Meld.Kind.CHOW, "4m"),
                    mm(Meld.Kind.PONG, "5p"), mm(Meld.Kind.CHOW, "7s")
                ),
                win = "1p"
            ),
            has = listOf("全求人"), hasnt = listOf("单钓将", "门前清")
        )
        expect(
            "F6-6 双暗杠",
            msc(
                "123m456p11s",
                melds = listOf(mm(Meld.Kind.CONCEALED_KONG, "1z"), mm(Meld.Kind.CONCEALED_KONG, "5z")),
                win = "1s"
            ),
            has = listOf("双暗杠"), hasnt = listOf("暗杠")
        )
        expect("F6-7 双箭刻", msc("555666z123m456m11p", win = "1p"), has = listOf("双箭刻"), hasnt = listOf("箭刻"))
    }

    // MARK: - 4 分

    @Test
    fun f4() {
        expect("F4-1 全带幺", msc("123m789p111s111z99m", win = "9m"), has = listOf("全带幺"))
        expect(
            "F4-2 不求人", msc("123m456m789m123p11p", win = "1p", selfDrawn = true),
            has = listOf("不求人"), hasnt = listOf("自摸", "门前清")
        )
        expect(
            "F4-3 双明杠",
            msc(
                "123m456p11s",
                melds = listOf(mm(Meld.Kind.EXPOSED_KONG, "1z"), mm(Meld.Kind.EXPOSED_KONG, "5z")),
                win = "1s"
            ),
            has = listOf("双明杠"), hasnt = listOf("明杠")
        )
        expect(
            "F4-4 和绝张",
            msc("123m456m789m123p11p", win = "1p") { it.copy(lastTileOfKind = true) },
            has = listOf("和绝张")
        )
    }

    // MARK: - 2 分

    @Test
    fun f2() {
        expect("F2-1 箭刻", msc("555z123m456m789m11p", win = "1p"), has = listOf("箭刻"))
        expect(
            "F2-2 圈风刻/门风刻",
            // 同一副风刻已按圈风刻/门风刻计过，不再重复计幺九刻（官方 24 分）
            msc("111z123m456m789m11p", win = "1p") { it.copy(prevalentWind = 0, seatWind = 0) },
            points = 24, has = listOf("圈风刻", "门风刻"), hasnt = listOf("幺九刻")
        )
        expect(
            "F2-2b 只有圈风",
            msc("222z123m456m789m11p", win = "1p") { it.copy(prevalentWind = 1, seatWind = 0) },
            has = listOf("圈风刻"), hasnt = listOf("门风刻")
        )
        expect("F2-3 门前清", msc("123m456m789m123p11p", win = "1p"), has = listOf("门前清"))
        expect("F2-4 平和", msc("234m567m234p678p55s", win = "5s"), has = listOf("平和"))
        expect("F2-4b 字牌将不算平和", msc("123m456m789m123p11z", win = "1z"), hasnt = listOf("平和"))
        expect("F2-5 四归一", msc("111m123m456p789s55m", win = "5m"), has = listOf("四归一"))
        expect("F2-6 双同刻", msc("111m111p234m567m99s", win = "9s"), has = listOf("双同刻"))
        expect("F2-7 双暗刻", msc("111m333m456p789s99s", win = "9s"), has = listOf("双暗刻"))
        expect(
            "F2-8 暗杠",
            msc("123m456m789m11p", melds = listOf(mm(Meld.Kind.CONCEALED_KONG, "1z")), win = "1p"),
            has = listOf("暗杠")
        )
        expect("F2-9 断幺", msc("234m567m234p678p55s", win = "5s"), has = listOf("断幺"))
    }

    // MARK: - 1 分

    @Test
    fun f1() {
        expect("F1-1 一般高", msc("123123m456m789p55s", win = "5s"), has = listOf("一般高"))
        expect("F1-2 喜相逢", msc("123567m123p23455s", win = "5s"), has = listOf("喜相逢"))
        expect("F1-3 连六", msc("123456m789p234s55s", win = "5s"), has = listOf("连六"))
        expect("F1-4 老少副", msc("123789m456p234s55s", win = "5s"), has = listOf("老少副"))
        expect("F1-5 幺九刻", msc("111m234m567p345s99s", win = "9s"), has = listOf("幺九刻"))
        expect(
            "F1-6 明杠",
            msc("123m456m789m11p", melds = listOf(mm(Meld.Kind.EXPOSED_KONG, "1z")), win = "1p"),
            has = listOf("明杠")
        )
        expect("F1-7 缺一门", msc("123m456m789m123p55p", win = "5p"), has = listOf("缺一门"))
        // 平和会吸收无字，所以举例得挑个非平和的牌型（官方：四暗刻+幺九刻+缺一门+无字+单钓将 = 69）
        expect("F1-8 无字", msc("111m444m777m999m22p", win = "2p"), has = listOf("无字"))
        expect("F1-9 边张", msc("123456789m23455p", win = "3m"), has = listOf("边张"))
        expect("F1-10 坎张", msc("123456789m23455p", win = "2m"), has = listOf("坎张"))
        // 单钓将要求独听。123456789m2345p 听 2m/5m/5筒 不止一张，官方也不给——换成真独听的
        expect("F1-11 单钓将", msc("111m444m777m999m22p", win = "2p"), has = listOf("单钓将"))
        expect(
            "F1-12 自摸",
            msc(
                "123m11p",
                melds = listOf(mm(Meld.Kind.CHOW, "4m"), mm(Meld.Kind.CHOW, "7m"), mm(Meld.Kind.PONG, "1z")),
                win = "1p", selfDrawn = true
            ),
            has = listOf("自摸")
        )
        val s = msc("123m456m789m123p55p", win = "5p", flowers = 3)
        assertTrue("F1-13 花牌每张 1 分", names(s).contains("花牌"))
        assertEquals("F1-13 花牌不进起和分", s.scoringPoints + 3, s.totalPoints)
    }

    // MARK: - 不重复计算原则

    @Test
    fun p_nonRepeatPrinciples() {
        // 不可拆分 + 就高不就低
        val p1 = msc("123123456789m55p", win = "5p")
        // 官方算番器：清龙 16 + 门前清 2 + 平和 2 + 一般高 1 + 缺一门 1 + 单钓将 1 = 23。
        // 一般高**照计**——原则 5 允许尚未组合过的那副牌同已组合过的套算一次。
        expect("P1 清龙与一般高并存", p1, points = 23, has = listOf("清龙", "一般高"))

        // 套算一次：一副面子只能配一次
        val p2 = msc("123123m123p456s55m", win = "5m")
        val pairFan = p2.items.filter { it.name in listOf("一般高", "喜相逢") }.sumOf { it.fan }
        // 官方算番器：门前清 2 + 平和 2 + 一般高 1 + 喜相逢 1 + 单钓将 1 = 7。
        // 4 副顺子最多 3 个配对番，这里 3 副参与、拿到 2 个。
        assertEquals("P2 套算一次：一般高 + 喜相逢 = 2 分", 2, pairFan)
        assertEquals("P2b 官方总分 7", 7, p2.scoringPoints)

        val p3 = msc(
            "456m55m",
            melds = listOf(mm(Meld.Kind.CHOW, "1m"), mm(Meld.Kind.CHOW, "1m"), mm(Meld.Kind.CHOW, "1m")),
            win = "5m"
        )
        assertTrue("P3 一色三同顺不再计一般高", names(p3).contains("一色三同顺") && !names(p3).contains("一般高"))

        val p4 = msc("111m333p555s777z99m", win = "9m", selfDrawn = true)
        assertTrue(
            "P4 四暗刻吃掉三暗刻/双暗刻/碰碰和",
            names(p4).contains("四暗刻") && !names(p4).contains("三暗刻") &&
                !names(p4).contains("双暗刻") && !names(p4).contains("碰碰和")
        )

        // 点炮成刻算明刻
        val draw = msc("111m333p555s777z99m", win = "1m", selfDrawn = true)
        val disc = msc("111m333p555s777z99m", win = "1m", selfDrawn = false)
        assertTrue("P5 自摸四暗刻", names(draw).contains("四暗刻"))
        assertTrue("P5 点炮降为三暗刻", names(disc).contains("三暗刻") && !names(disc).contains("四暗刻"))

        val p6 = msc("222333444567m99m", win = "9m")
        assertTrue(
            "P6 清一色吃掉无字/缺一门",
            names(p6).contains("清一色") && !names(p6).contains("无字") && !names(p6).contains("缺一门")
        )

        val p7 = msc("123m456m789m123p11p", win = "1p", selfDrawn = true)
        assertTrue(
            "P7 不求人吃掉自摸/门前清",
            names(p7).contains("不求人") && !names(p7).contains("自摸") && !names(p7).contains("门前清")
        )

        assertFalse("P8 一色四同顺不再计四归一", names(msc("123123123123m55m", win = "5m")).contains("四归一"))
    }

    // MARK: - 起和 8 分

    @Test
    fun q_minimumPoints() {
        val q1 = msc("678m345p567s55p", melds = listOf(mm(Meld.Kind.CHOW, "2m")), win = "7s")
        // 平和2 + 断幺2 = 4 分（无字被平和吸收——官方算番器同样是 4 分）
        assertEquals("Q1 4 分不到起和线", 4, q1.scoringPoints)
        assertFalse("Q1 不够起和", q1.meetsMinimum)

        val q2 = msc("678m345p567s55p", melds = listOf(mm(Meld.Kind.CHOW, "2m")), win = "7s", flowers = 3)
        assertEquals("Q2 花牌不算起和分（总分）", 7, q2.totalPoints)
        assertEquals("Q2 花牌不算起和分（起和分）", 4, q2.scoringPoints)
        assertFalse("Q2 仍不够起和", q2.meetsMinimum)

        assertTrue("Q3 门前清+清龙 达到起和线", msc("123m456m789m123p55p", win = "5p").meetsMinimum)

        val q4 = msc("567m234p678s11z", melds = listOf(mm(Meld.Kind.CHOW, "1m")), win = "5m")
        assertTrue("Q4 无番和", names(q4).contains("无番和"))
        assertEquals("Q4 无番和 8 分", 8, q4.scoringPoints)
        assertTrue(q4.meetsMinimum)

        // 部分手牌（不满 14 张）牌型能成立，但不该给「无番和 8 分」
        val q5 = scoreMCRHand(mfreq("123m456p789s11z"), emptyList(), MCRContext(winningTile = 27))
        assertEquals("Q5 部分手牌 0 分", 0, q5.scoringPoints)
        assertFalse(q5.meetsMinimum)
        assertFalse("Q5 部分手牌不算无番和", names(q5).contains("无番和"))
    }

    // MARK: - 规则细则（各地规则书有分歧，用户可选）

    @Test
    fun o0_defaultsMatchEngineDefaults() {
        assertEquals("O0 设置默认值 = 引擎默认", MCROptions(), RuleSettings().mcrOptions)
        val d = RuleSettings()
        assertTrue(
            // 默认值对齐官方算番器：字一色计混幺九、三杠再计每个杠这两项官方不这么算，默认关。
            "O0b 规则细则默认值 = 官方算番器",
            !d.mcrZiYiSeCountsHunYaoJiu && d.mcrJiuLianCountsShuangAnKe &&
                d.mcrSevenPairsAllowsQuadAsTwoPairs && !d.mcrPerKongFanWithThreeKongs &&
                d.mcrWaitFanHighestReading && d.mcrOneOpenOneConcealedKong
        )
    }

    @Test
    fun o0c_optionsRoundTripAndLegacyArchive() {
        val s = RuleSettings(
            gameMode = GameMode.MCR,
            mcrPrevalentWind = 2, mcrSeatWind = 3,
            mcrPerKongFanWithThreeKongs = false,
            mcrWaitFanHighestReading = false,
        )
        assertEquals("O0c 规则细则持久化往返", s, RuleSettingsStore.decode(RuleSettingsStore.encode(s)))

        // 老存档没有这些键：按默认值补齐，行为不变
        val old = RuleSettingsStore.decode(JSONObject("""{"baseStake":1,"gameMode":"mcr"}"""))
        assertEquals("O0d 老存档缺键按默认补齐", MCROptions(), old.mcrOptions)
        assertEquals(GameMode.MCR, old.gameMode)
        // 完全没有 gameMode 的老存档仍是四川
        assertEquals(GameMode.SICHUAN, RuleSettingsStore.decode(JSONObject("{}")).gameMode)
    }

    /** 存档迁移：1.3 及以前那三项国标默认值与官方算番器不符，升级时一次性纠正 */
    @Test
    fun mig_legacyArchiveGetsOfficialMCRDefaults() {
        val legacy = RuleSettingsStore.decode(
            JSONObject(
                """{"baseStake":1,"gameMode":"mcr",
                    "mcrZiYiSeCountsHunYaoJiu":true,
                    "mcrJiuLianCountsShuangAnKe":true,
                    "mcrPerKongFanWithThreeKongs":true}"""
            )
        )
        assertFalse("MIG1a 字一色不再计混幺九", legacy.mcrZiYiSeCountsHunYaoJiu)
        assertTrue("MIG1b 九莲计双暗刻（官方就这么算）", legacy.mcrJiuLianCountsShuangAnKe)
        assertFalse("MIG1c 三杠不再单计每个杠", legacy.mcrPerKongFanWithThreeKongs)
        // 迁移只做一次：重新编码后带上版本号，再解码时用户自己的选择要保住
        val custom = legacy.copy(mcrZiYiSeCountsHunYaoJiu = true)   // 用户主动打开
        val round = RuleSettingsStore.decode(RuleSettingsStore.encode(custom))
        assertTrue("MIG2 迁移后用户自己的选择不再被覆盖", round.mcrZiYiSeCountsHunYaoJiu)
        // 迁移不碰其它设置
        assertEquals("MIG3 迁移不影响其它设置", GameMode.MCR, legacy.gameMode)
        assertEquals(1.0, legacy.baseStake, 1e-9)
    }

    /** ① 字一色是否同时计混幺九（+32） */
    @Test
    fun o1_ziYiSeCountsHunYaoJiu() {
        val hand = "222333444z55566z"
        val on = msc(hand, win = "6z", options = MCROptions(mcrZiYiSeCountsHunYaoJiu = true))
        val off = msc(hand, win = "6z", options = MCROptions(mcrZiYiSeCountsHunYaoJiu = false))
        expect("O1a 字一色计混幺九（开）= 175 分", on, points = 175, has = listOf("字一色", "混幺九"))
        expect("O1b 字一色不计混幺九（关）= 143 分", off, points = 143, has = listOf("字一色"), hasnt = listOf("混幺九"))
    }

    /** ② 九莲宝灯是否同时计双暗刻（+2） */
    @Test
    fun o2_jiuLianCountsShuangAnKe() {
        val hand = "11123455678999m"
        val on = msc(hand, win = "5m", options = MCROptions(mcrJiuLianCountsShuangAnKe = true))
        val off = msc(hand, win = "5m", options = MCROptions(mcrJiuLianCountsShuangAnKe = false))
        expect("O2a 九莲宝灯计双暗刻（开）= 91 分", on, points = 91, has = listOf("九莲宝灯", "双暗刻"))
        expect("O2b 九莲宝灯不计双暗刻（关）= 89 分", off, points = 89, has = listOf("九莲宝灯"), hasnt = listOf("双暗刻"))
    }

    /** ③ 七对里「4 张相同」是否可当两对 */
    @Test
    fun o3_sevenPairsAllowsQuadAsTwoPairs() {
        // 1111 22 33 44 55 66 万：当两对 → 七对 24 + 清一色 24 = 48；
        // 不当两对 → 退回标准型 123/123/456/456 + 11 将 = 32 分
        val on = msc("11112233445566m", win = "6m")
        val off = msc("11112233445566m", win = "6m", options = MCROptions(mcrSevenPairsAllowsQuadAsTwoPairs = false))
        // 官方 50：七对 24 + 清一色 24 + **四归一 2**。七对可计四归一。
        expect("O3a 4 张可当两对（开）= 50 分", on, points = 50, has = listOf("七对", "清一色", "四归一"))
        // 关掉后退回标准型：清一色24 + 一般高1 + 连六×2 + 平和2 + 四归一2 + 门前清2 = 33
        expect(
            "O3b 4 张不可当两对（关）= 33 分", off, points = 33,
            has = listOf("清一色", "一般高", "平和", "四归一"), hasnt = listOf("七对")
        )

        // 退不回标准型的牌：关掉后这副牌在这套规则下根本不成和，0 分
        val on2 = msc("1111335577m1133p", win = "3p")
        val off2 = msc("1111335577m1133p", win = "3p", options = MCROptions(mcrSevenPairsAllowsQuadAsTwoPairs = false))
        // 官方 28：七对 24 + 缺一门 1 + 无字 1 + **四归一 2**
        expect("O3c 无标准型可退（开）= 28 分", on2, points = 28, has = listOf("七对", "四归一"))
        assertEquals("O3d 无标准型可退（关）= 0 分（不成和）", 0, off2.scoringPoints)
        assertTrue(off2.items.isEmpty())
        assertFalse(off2.meetsMinimum)
    }

    /** ④ 三杠时是否再单独计每个杠（明杠 1 / 暗杠 2） */
    @Test
    fun o4_perKongFanWithThreeKongs() {
        val kongs = listOf(
            mm(Meld.Kind.EXPOSED_KONG, "2s"), mm(Meld.Kind.EXPOSED_KONG, "3s"),
            mm(Meld.Kind.CONCEALED_KONG, "4s")
        )
        val on = msc("123m11p", melds = kongs, win = "1p", options = MCROptions(mcrPerKongFanWithThreeKongs = true))
        val off = msc("123m11p", melds = kongs, win = "1p", options = MCROptions(mcrPerKongFanWithThreeKongs = false))
        expect("O4a 三杠再计每个杠（开）= 73 分", on, points = 73, has = listOf("三杠", "明杠", "暗杠"))
        expect("O4b 三杠不再计每个杠（关）= 69 分", off, points = 69, has = listOf("三杠"), hasnt = listOf("明杠", "暗杠"))
    }

    /** ⑤ 边张 / 坎张 / 单钓将：跨解法就高 vs 听法唯一才计 */
    @Test
    fun o5_waitFanHighestReading() {
        // 123456789万 + 345筒 + 55筒，和 5 筒：既能读成单钓将，也能读成 345 筒里的一张
        val on = msc("123m456m789m34555p", win = "5p")
        val off = msc("123m456m789m34555p", win = "5p", options = MCROptions(mcrWaitFanHighestReading = false))
        // 官方：这手听法有歧义 → 不是独听 → 边张/坎张/单钓将一个都不给（21 分）。
        // 「就高」开关只在**独听**成立、但拆解读法不唯一时才起作用。
        expect("O5a 听法有歧义时不给听牌番（开）= 21 分", on, points = 21, hasnt = listOf("单钓将", "边张", "坎张"))
        expect("O5b 听法有歧义，不计（关）= 21 分", off, points = 21, hasnt = listOf("单钓将", "边张", "坎张"))

        // 听法唯一的边张 / 坎张：两种设置下都照计，关掉不等于永远不给
        val strict = MCROptions(mcrWaitFanHighestReading = false)
        expect("O5c 唯一边张（开）= 22 分", msc("123456789m23455p", win = "3m"), points = 22, has = listOf("边张"))
        expect(
            "O5d 唯一边张（关）= 22 分", msc("123456789m23455p", win = "3m", options = strict),
            points = 22, has = listOf("边张")
        )
        expect(
            "O5e 唯一坎张（关）= 22 分", msc("123456789m23455p", win = "2m", options = strict),
            points = 22, has = listOf("坎张")
        )
    }

    // MARK: - 打牌建议（国标）

    @Test
    fun e_discardEvaluation() {
        val hand = mt("112233445566m78m")
        val e = mcrEvaluateDiscards(
            mcrDiscardSuggestions(hand), hand, emptyList(), RuleSettings()
        )
        assertTrue("E1 清一色一色三同顺路线排第一", e.isNotEmpty() && e[0].maxPoints >= 24)

        // 国标不受缺一门限制：三门齐全的手牌照样有听牌建议
        val hand2 = mt("123m456p789s111z22z")
        val e2 = mcrEvaluateDiscards(
            mcrDiscardSuggestions(hand2), hand2, emptyList(), RuleSettings()
        )
        assertTrue("E2 三门齐全仍能听牌", e2.any { it.suggestion.resultingShanten == 0 })
    }

    // MARK: - 四川侧不受影响（回归护栏）

    @Test
    fun r_sichuanRegressionGuards() {
        assertEquals("R1 川麻频率数组忽略字牌", 3, handToFrequency27(mt("123m1z5z")).sum())
        assertEquals("R2 默认玩法 = 四川", GameMode.SICHUAN, RuleSettings().gameMode)
        assertEquals("R3 四川键盘 3 门", 3, GameMode.SICHUAN.suits.size)
        assertEquals("R3 国标键盘 6 门", 6, GameMode.MCR.suits.size)
        assertEquals("R4 四川只有 3 种副露", 3, GameMode.SICHUAN.meldKinds.size)
        assertTrue("R4 只有国标有吃", GameMode.MCR.meldKinds.contains(Meld.Kind.CHOW))
    }
}
