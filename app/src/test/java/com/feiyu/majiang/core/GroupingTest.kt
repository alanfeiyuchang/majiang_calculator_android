//
//  GroupingTest.kt
//  拍照识别空间聚类断言（groupTiles / zoomRegion）—— 与 iOS Tests/GroupingTests.swift
//  逐条对应（G1–G11、Z1–Z4）。
//  用合成坐标构造「手牌 + 副露」布局，验证碰/明杠/暗杠/纯手牌的分组正确。
//

package com.feiyu.majiang.core

import org.junit.Assert.assertTrue
import org.junit.Test

class GroupingTest {

    // 一张牌宽 10、高 14，行内相邻牌间隔 1（紧挨）；簇之间用大间隔（≥ 8）分开。
    private val tileW = 10f
    private val tileH = 14f

    /** 从 x 起点顺次摆放同一行的若干牌（间隔 1），返回牌盒与下一个可用 x */
    private fun lay(cards: List<MahjongCard>, from: Float, cy: Float = 0f): Pair<List<TileBox>, Float> {
        var x = from
        val out = mutableListOf<TileBox>()
        for (c in cards) {
            out.add(TileBox(minX = x, maxX = x + tileW, cy = cy, height = tileH, card = c))
            x += tileW + 1
        }
        return out to x
    }

    private fun c(s: String): MahjongCard {
        val rank = s.first().digitToInt()
        val suit = when (s.last()) {
            'm' -> MahjongCard.Suit.WAN
            'p' -> MahjongCard.Suit.TONG
            else -> MahjongCard.Suit.TIAO
        }
        return MahjongCard(suit, rank)
    }

    private fun cards(ss: List<String>): List<MahjongCard> = ss.map { c(it) }
    private fun meldDesc(m: Meld): String = "${m.kind.raw}${m.card.displayText}"

    @Test fun g1_pureHand() {
        val (boxes, _) = lay(cards(listOf("1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "1p", "2p", "3p", "5p")), 0f)
        val r = groupTiles(boxes)
        assertTrue("G1 纯手牌13张无副露 hand=${r.hand.size} melds=${r.melds.map(::meldDesc)}",
            r.hand.size == 13 && r.melds.isEmpty() && !r.guessedConcealedKong)
    }

    @Test fun g2_handPlusPong() {
        var (boxes, x) = lay(cards(listOf("1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "2p")), 0f)
        x += 8 * tileW                                   // 大空档
        val pong = lay(cards(listOf("5p", "5p", "5p")), x)
        boxes = boxes + pong.first
        val r = groupTiles(boxes)
        assertTrue("G2 手牌+碰5筒 hand=${r.hand.size} melds=${r.melds.map(::meldDesc)}",
            r.hand.size == 10 && r.melds.size == 1
                && meldDesc(r.melds[0]) == "碰五筒" && !r.guessedConcealedKong)
    }

    @Test fun g3_handPlusExposedKong() {
        var (boxes, x) = lay(cards(listOf("1m", "2m", "3m", "4m", "5m", "6m", "7m", "2p")), 0f)
        x += 8 * tileW
        val kong = lay(cards(listOf("9p", "9p", "9p", "9p")), x)
        boxes = boxes + kong.first
        val r = groupTiles(boxes)
        assertTrue("G3 手牌+明杠9筒 melds=${r.melds.map(::meldDesc)}",
            r.melds.size == 1 && meldDesc(r.melds[0]) == "明杠九筒" && !r.guessedConcealedKong)
    }

    // 手牌 10 张 + 暗杠 3 张名额 = 13，张数成立，「猜暗杠」才会被采纳——这正是现在判定暗杠的依据
    @Test fun g4_handPlusConcealedKong() {
        var (boxes, x) = lay(cards(listOf("1m", "2m", "3m", "4m", "5m", "6m", "7m", "2p", "3p", "4p")), 0f)
        x += 8 * tileW
        val kong = lay(cards(listOf("3p")), x)           // 只露的那张明牌
        boxes = boxes + kong.first
        val r = groupTiles(boxes)
        assertTrue("G4 手牌+暗杠3筒(单张) melds=${r.melds.map(::meldDesc)}",
            r.melds.size == 1 && meldDesc(r.melds[0]) == "暗杠三筒" && r.guessedConcealedKong)
    }

    @Test fun g5_twoRows() {
        val (hand, _) = lay(cards(listOf("1m", "2m", "3m", "4m", "5m", "6m", "7m")), 0f, cy = 0f)
        var (pong, x2) = lay(cards(listOf("8p", "8p", "8p")), 0f, cy = 40f)   // 下一行
        x2 += 8 * tileW
        val kong = lay(cards(listOf("2m")), x2, cy = 40f)
        val r = groupTiles(hand + pong + kong.first)
        assertTrue("G5 两行：手牌+碰+暗杠 hand=${r.hand.size} melds=${r.melds.map(::meldDesc)}",
            r.hand.size == 7 && r.melds.size == 2
                && r.melds.any { meldDesc(it) == "碰八筒" }
                && r.melds.any { meldDesc(it) == "暗杠二万" }
                && r.guessedConcealedKong)
    }

    @Test fun g6_goldenHookLayout() {
        var (boxes, x) = lay(cards(listOf("5m", "5m")), 0f)   // 手里一对（最大簇之一）
        // 4 个碰，各自间隔
        for (tile in listOf("1m", "2m", "3m", "4m")) {
            x += 8 * tileW
            val p = lay(cards(listOf(tile, tile, tile)), x)
            boxes = boxes + p.first
            x = p.second
        }
        val r = groupTiles(boxes)
        // 4 个碰应被识别为副露；手牌为那一对（或某个 3 张簇被当手牌——取决于最大簇）
        assertTrue("G6 金钩钓4碰多数识别为碰 melds=${r.melds.map(::meldDesc)}",
            r.melds.count { it.kind == Meld.Kind.PONG } >= 3)
    }

    @Test fun g7_unrecognizedSmallCluster() {
        var (boxes, x) = lay(cards(listOf("1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m")), 0f)
        x += 8 * tileW
        val stray = lay(cards(listOf("2p", "4p")), x)       // 2 张不同 → 不是碰/杠
        boxes = boxes + stray.first
        val r = groupTiles(boxes)
        assertTrue("G7 两张不同的小簇并回手牌 hand=${r.hand.size} melds=${r.melds.map(::meldDesc)}",
            r.melds.isEmpty() && r.hand.size == 10)
    }

    // —— 紧挨副露拆分（对应 iOS G8–G11）——

    @Test fun g8_twoAdjacentPongs() {
        var (boxes, x) = lay(cards(listOf("1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "2p")), 0f)
        x += 8 * tileW
        val two = lay(cards(listOf("5p", "5p", "5p", "8p", "8p", "8p")), x)
        boxes = boxes + two.first
        val r = groupTiles(boxes)
        assertTrue("G8 紧挨双碰拆成两组 hand=${r.hand.size} melds=${r.melds.map(::meldDesc)}",
            r.hand.size == 10 && r.melds.size == 2
                && r.melds.any { meldDesc(it) == "碰五筒" }
                && r.melds.any { meldDesc(it) == "碰八筒" })
    }

    @Test fun g9_adjacentPongAndKong() {
        var (boxes, x) = lay(cards(listOf("1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m", "9m", "2p")), 0f)
        x += 8 * tileW
        val mix = lay(cards(listOf("5p", "5p", "5p", "9p", "9p", "9p", "9p")), x)
        boxes = boxes + mix.first
        val r = groupTiles(boxes)
        assertTrue("G9 碰+明杠紧挨拆分 melds=${r.melds.map(::meldDesc)}",
            r.melds.size == 2
                && r.melds.any { meldDesc(it) == "碰五筒" }
                && r.melds.any { meldDesc(it) == "明杠九筒" })
    }

    @Test fun g10_badRunFallsBackToHand() {
        var (boxes, x) = lay(cards(listOf("1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m")), 0f)
        x += 8 * tileW
        val bad = lay(cards(listOf("5p", "5p", "5p", "6p", "6p")), x)   // [3][2]：2 张段认不准
        boxes = boxes + bad.first
        val r = groupTiles(boxes)
        assertTrue("G10 含2张段整簇回手牌 hand=${r.hand.size} melds=${r.melds.map(::meldDesc)}",
            r.melds.isEmpty() && r.hand.size == 13)
    }

    @Test fun g11_adjacentPongAndConcealedKongTile() {
        var (boxes, x) = lay(cards(listOf("1m", "2m", "3m", "4m", "5m", "6m", "7m", "8m")), 0f)
        x += 8 * tileW
        val mix = lay(cards(listOf("7s", "7s", "7s", "2m")), x)
        boxes = boxes + mix.first
        val r = groupTiles(boxes)
        assertTrue("G11 碰+暗杠明牌紧挨 melds=${r.melds.map(::meldDesc)}",
            r.melds.size == 2 && r.guessedConcealedKong
                && r.melds.any { meldDesc(it) == "碰七条" }
                && r.melds.any { meldDesc(it) == "暗杠二万" })
    }

    // —— 整桌入镜 / 张数校验（对应 iOS G12–G16）——

    /** 契约：桌上的牌不会被静默丢弃（丢弃同样会吃掉平摊的碰/杠），而是并回手牌让张数对不上被拦下 */
    @Test fun g12_wholeTableInFrame() {
        val hand = lay(cards(listOf("1m","2m","3m","4m","5m","6m","7m","8m","9m","1p","2p","3p","5p")), 0f, cy = 200f).first
        val table = mutableListOf<TileBox>()
        var tx = 0f
        for (i in 0 until 16) {
            table.add(TileBox(tx, tx + tileW / 2, cy = 20f + (i / 8) * 10f, height = tileH / 2, card = c("7p")))
            tx += tileW / 2 + 1
            if (i == 7) tx = 0f
        }
        val r = groupTiles(hand + table)
        assertTrue("G12 手牌簇选对且张数对不上 hand=${r.hand.size} valid=${r.hasValidTileCount}",
            r.hand.take(13) == cards(listOf("1m","2m","3m","4m","5m","6m","7m","8m","9m","1p","2p","3p","5p"))
                && !r.hasValidTileCount)
    }

    @Test fun g13_farTilesOutnumberHand() {
        val hand = lay(cards(listOf("1m","2m","3m","4m","5m","6m","7m","8m","9m","1p")), 0f, cy = 200f).first
        val table = mutableListOf<TileBox>()
        var tx = 0f
        repeat(30) {
            table.add(TileBox(tx, tx + tileW / 2, cy = 20f, height = tileH / 2, card = c("9s")))
            tx += tileW / 2 + 1
        }
        val r = groupTiles(hand + table)
        assertTrue("G13 远处 30 张多过手牌 10 张，手牌簇仍选对 hand=${r.hand.size}",
            r.hand.take(10) == cards(listOf("1m","2m","3m","4m","5m","6m","7m","8m","9m","1p"))
                && !r.hasValidTileCount)
    }

    /** 手牌摆成两排（大小相当、不同排）→ 合并成一副手牌 */
    @Test fun g14b_twoRowHandMerges() {
        val front = lay(cards(listOf("3m","5m","8m","8m","5p","6p","7p")), 0f, cy = 200f).first
        val back = mutableListOf<TileBox>()
        var bx = 0f
        for (card in cards(listOf("2s","3s","4s","5s","6s","7s"))) {
            back.add(TileBox(bx, bx + tileW * 0.88f, cy = 140f, height = tileH * 0.88f, card = card))
            bx += tileW * 0.88f + 1
        }
        val r = groupTiles(front + back)
        assertTrue("G14b 两排手牌合并成 13 张 hand=${r.hand.size}",
            r.hand.size == 13 && r.melds.isEmpty() && r.hasValidTileCount)
    }

    /** 平摊在桌上的碰被透视压扁（高度只有手牌的一半）→ 必须仍识别成碰。
     *  核心回归测试：任何「按大小丢框」的过滤都会先吃掉这一组。 */
    @Test fun g14c_flatPongStillRecognized() {
        val hand = lay(cards(listOf("1m","2m","3m","4m","5m","6m","7m","8m","9m","2p")), 0f, cy = 200f).first
        val pong = mutableListOf<TileBox>()
        var px = 20 * tileW
        for (card in cards(listOf("5p","5p","5p"))) {
            pong.add(TileBox(px, px + tileW, cy = 120f, height = tileH * 0.5f, card = card))
            px += tileW + 1
        }
        val r = groupTiles(hand + pong)
        assertTrue("G14c 压扁一半的平摊碰仍识别为碰 melds=${r.melds.map(::meldDesc)}",
            r.hand.size == 10 && r.melds.size == 1 && meldDesc(r.melds[0]) == "碰五筒" && r.hasValidTileCount)
    }

    @Test fun g15_tileCountInvariant() {
        var (boxes, x) = lay(cards(listOf("1m","2m","3m","4m","5m","6m","7m","8m","9m","2p")), 0f)
        x += 8 * tileW
        boxes = boxes + lay(cards(listOf("5p","5p","5p")), x).first
        val r = groupTiles(boxes)
        assertTrue("G15 手牌10+碰1组=13 张合法 effective=${r.effectiveTileCount}",
            r.effectiveTileCount == 13 && r.hasValidTileCount)

        val short = lay(cards(listOf("1m","2m","3m","4m","5m")), 0f).first
        assertTrue("G15b 只识别到 5 张 → 张数不合法", !groupTiles(short).hasValidTileCount)
    }

    @Test fun g16_ownTilesOnlyKeepsAll() {
        val boxes = lay(cards(listOf("1m","2m","3m","4m","5m","6m","7m","8m","9m","1p","2p","3p","5p")), 0f).first
        val r = groupTiles(boxes)
        assertTrue("G16 只拍自己的牌一张不少 hand=${r.hand.size}",
            r.hand.size == 13 && r.melds.isEmpty() && r.hasValidTileCount)
    }

    // —— 自动框选区域 myTilesRegion ——

    /** 近处的牌（框高 1.0）+ 远处的弃牌（框高 0.4）→ 只框住近处那批 */
    @Test fun m1_regionExcludesFarTiles() {
        val near = (0 until 13).map { GeoRect(10f + it * 30f, 600f, 26f, 36f) }
        val far = (0 until 30).map { GeoRect(5f + it * 14f, 200f, 11f, 15f) }
        val r = myTilesRegion(near + far, 1000f, 1000f)
        assertTrue("M1 应框住近景 r=$r", r != null && r!!.y > 400f && r.maxY < 1000f)
        assertTrue("M1 不应把远处弃牌框进来", r!!.y > 300f)
    }

    /** 只有自己的牌时不该滤掉任何一张，框要盖住全部 */
    @Test fun m2_regionCoversAllWhenUniform() {
        val boxes = (0 until 13).map { GeoRect(10f + it * 30f, 600f, 26f, 36f) }
        val r = myTilesRegion(boxes, 1000f, 1000f)
        assertTrue("M2 框应盖住所有牌 r=$r",
            r != null && boxes.all { r!!.contains(it) })
    }

    // —— 二次放大区域 zoomRegion（对应 iOS Z1–Z4）——

    @Test fun z1_cornerTilesGetPaddedRegion() {
        val rects = listOf(
            GeoRect(1000f, 800f, 60f, 80f),
            GeoRect(1070f, 800f, 60f, 80f),
        )
        val region = zoomRegion(rects, 4000f, 3000f)
        assertTrue("Z1 一角的牌→外扩区域 region=$region",
            region != null && region.contains(rects[0]) && region.contains(rects[1])
                && region.x >= 0f && region.y >= 0f
                && region.maxX <= 4000f && region.maxY <= 3000f)
    }

    @Test fun z2_fullFrameReturnsNull() {
        val big = listOf(GeoRect(10f, 10f, 3900f, 2900f))
        assertTrue("Z2 占满画面→null", zoomRegion(big, 4000f, 3000f) == null)
    }

    @Test fun z3_emptyReturnsNull() {
        assertTrue("Z3 空→null", zoomRegion(emptyList(), 100f, 100f) == null)
    }

    @Test fun z4_wideStripStillZooms() {
        val strip = listOf(GeoRect(100f, 1400f, 3700f, 120f))
        assertTrue("Z4 横条区域→仍放大", zoomRegion(strip, 4000f, 3000f) != null)
    }
}
