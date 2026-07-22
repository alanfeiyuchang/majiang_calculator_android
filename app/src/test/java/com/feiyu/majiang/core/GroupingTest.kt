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

    @Test fun g4_handPlusConcealedKong() {
        var (boxes, x) = lay(cards(listOf("1m", "2m", "3m", "4m", "5m", "6m", "7m", "2p")), 0f)
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
