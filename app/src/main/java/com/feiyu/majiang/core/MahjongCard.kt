//
//  MahjongCard.kt
//  一张麻将牌：花色 + 点数（1–9）。与 iOS MahjongCard.swift 一一对应。
//

package com.feiyu.majiang.core

data class MahjongCard(val suit: Suit, val rank: Int) {

    enum class Suit(val raw: String) {
        WAN("万"), TONG("筒"), TIAO("条");

        /** 界面与手牌排序：万 → 条 → 筒（与算法下标 万/筒/条 无关） */
        val displaySortIndex: Int
            get() = when (this) {
                WAN -> 0
                TIAO -> 1
                TONG -> 2
            }

        companion object {
            val displayOrder = listOf(WAN, TIAO, TONG)
        }
    }

    /** 0...8 万，9...17 筒，18...26 条 */
    val tileIndex: Int
        get() {
            val offset = when (suit) {
                Suit.WAN -> 0
                Suit.TONG -> 9
                Suit.TIAO -> 18
            }
            return offset + (rank - 1)
        }

    val rankHanDigit: String
        get() = if (rank in 1..9) RANK_HAN[rank] else "$rank"

    /** 牌面图片资源名（drawable：tile_<suit>_<rank>） */
    val assetName: String
        get() {
            val suitKey = when (suit) {
                Suit.WAN -> "man"
                Suit.TONG -> "pin"
                Suit.TIAO -> "sou"
            }
            return "tile_${suitKey}_$rank"
        }

    /** 单行：如 「三万」 */
    val displayText: String get() = "$rankHanDigit${suit.raw}"

    /** 键盘等：数字 + 花色 */
    val displayTextCompact: String get() = "$rank${suit.raw}"

    companion object {
        private val RANK_HAN = listOf("", "一", "二", "三", "四", "五", "六", "七", "八", "九")

        fun fromTileIndex(index: Int): MahjongCard {
            require(index in 0..26)
            val rank = index % 9 + 1
            return when (index / 9) {
                0 -> MahjongCard(Suit.WAN, rank)
                1 -> MahjongCard(Suit.TONG, rank)
                else -> MahjongCard(Suit.TIAO, rank)
            }
        }

        fun allTilesInOrder(): List<MahjongCard> =
            Suit.displayOrder.flatMap { suit -> (1..9).map { MahjongCard(suit, it) } }
    }
}
