//
//  MahjongCard.kt
//  一张麻将牌：花色 + 点数。与 iOS MahjongCard.swift 一一对应。
//
//  - 数牌（万/筒/条）点数 1–9，四川麻将只用这三门。
//  - 国标（MCR）另需 风牌（东南西北）、箭牌（中发白）、花牌（春夏秋冬梅兰竹菊）。
//    风/箭的 rank 是该门内的序号（风 1–4 = 东南西北，箭 1–3 = 中发白），
//    花牌 rank 1–8 = 春夏秋冬梅兰竹菊。
//

package com.feiyu.majiang.core

data class MahjongCard(val suit: Suit, val rank: Int) {

    enum class Suit(val raw: String) {
        WAN("万"), TONG("筒"), TIAO("条"),

        /** 风牌：东南西北（仅国标） */
        FENG("风"),

        /** 箭牌：中发白（仅国标） */
        JIAN("箭"),

        /** 花牌：春夏秋冬梅兰竹菊（仅国标，不参与和牌） */
        HUA("花");

        /** 界面与手牌排序：万 → 条 → 筒 →（国标）风 → 箭 → 花 */
        val displaySortIndex: Int
            get() = when (this) {
                WAN -> 0
                TIAO -> 1
                TONG -> 2
                FENG -> 3
                JIAN -> 4
                HUA -> 5
            }

        /** 数牌（万/筒/条） */
        val isNumbered: Boolean get() = this == WAN || this == TONG || this == TIAO

        /** 字牌（风 + 箭） */
        val isHonor: Boolean get() = this == FENG || this == JIAN

        /** 花牌 */
        val isFlower: Boolean get() = this == HUA

        /** 该门内的点数上限 */
        val rankCount: Int
            get() = when (this) {
                WAN, TONG, TIAO -> 9
                FENG -> 4
                JIAN -> 3
                HUA -> 8
            }

        companion object {
            /** 四川键盘/排序顺序 */
            val displayOrder = listOf(WAN, TIAO, TONG)

            /** 国标键盘/排序顺序：万 → 条 → 筒 → 风 → 箭 → 花 */
            val mcrDisplayOrder = listOf(WAN, TIAO, TONG, FENG, JIAN, HUA)
        }
    }

    /** 0...8 万，9...17 筒，18...26 条；非数牌返回 -1（四川引擎不认字牌/花牌） */
    val tileIndex: Int
        get() {
            val offset = when (suit) {
                Suit.WAN -> 0
                Suit.TONG -> 9
                Suit.TIAO -> 18
                else -> return -1
            }
            return offset + (rank - 1)
        }

    /**
     * 国标 34 张牌下标：0–8 万、9–17 筒、18–26 条、27–30 东南西北、31–33 中发白。
     * 花牌不参与和牌，返回 -1。
     */
    val mcrIndex: Int
        get() = when (suit) {
            Suit.WAN -> rank - 1
            Suit.TONG -> 9 + rank - 1
            Suit.TIAO -> 18 + rank - 1
            Suit.FENG -> 27 + rank - 1
            Suit.JIAN -> 31 + rank - 1
            Suit.HUA -> -1
        }

    val rankHanDigit: String
        get() = when (suit) {
            Suit.FENG -> han(FENG_HAN, rank)
            Suit.JIAN -> han(JIAN_HAN, rank)
            Suit.HUA -> han(HUA_HAN, rank)
            else -> han(RANK_HAN, rank)
        }

    /** 数牌有牌面图片资源；字牌/花牌没有，界面上用文字牌面代替 */
    val hasImageAsset: Boolean get() = suit.isNumbered

    /** 牌面图片资源名（drawable：tile_<suit>_<rank>）；字牌/花牌为空串 */
    val assetName: String
        get() {
            val suitKey = when (suit) {
                Suit.WAN -> "man"
                Suit.TONG -> "pin"
                Suit.TIAO -> "sou"
                else -> return ""
            }
            return "tile_${suitKey}_$rank"
        }

    /** 单行：数牌如「三万」；字牌/花牌就是单字「东」「中」「春」 */
    val displayText: String get() = if (suit.isNumbered) "$rankHanDigit${suit.raw}" else rankHanDigit

    /** 键盘等：数字 + 花色，避免被裁切；字牌/花牌仍用单字 */
    val displayTextCompact: String get() = if (suit.isNumbered) "$rank${suit.raw}" else rankHanDigit

    companion object {
        private val RANK_HAN = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        private val FENG_HAN = listOf("", "东", "南", "西", "北")
        private val JIAN_HAN = listOf("", "中", "发", "白")
        private val HUA_HAN = listOf("", "春", "夏", "秋", "冬", "梅", "兰", "竹", "菊")

        private fun han(table: List<String>, rank: Int): String =
            if (rank in 1 until table.size) table[rank] else "$rank"

        fun fromTileIndex(index: Int): MahjongCard {
            require(index in 0..26)
            val rank = index % 9 + 1
            return when (index / 9) {
                0 -> MahjongCard(Suit.WAN, rank)
                1 -> MahjongCard(Suit.TONG, rank)
                else -> MahjongCard(Suit.TIAO, rank)
            }
        }

        /** 由国标 34 下标还原一张牌 */
        fun fromMCRIndex(index: Int): MahjongCard {
            require(index in 0..33)
            if (index < 27) return fromTileIndex(index)
            if (index < 31) return MahjongCard(Suit.FENG, index - 27 + 1)
            return MahjongCard(Suit.JIAN, index - 31 + 1)
        }

        /** 四川麻将全部牌张（万/条/筒 各 1–9） */
        fun allTilesInOrder(): List<MahjongCard> =
            Suit.displayOrder.flatMap { suit -> (1..9).map { MahjongCard(suit, it) } }

        /** 国标全部牌张（含风/箭/花） */
        fun allMCRTilesInOrder(): List<MahjongCard> =
            Suit.mcrDisplayOrder.flatMap { suit -> (1..suit.rankCount).map { MahjongCard(suit, it) } }
    }
}
