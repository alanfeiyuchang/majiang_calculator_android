//
//  Meld.kt
//  桌上的牌（副露）：碰 / 明杠 / 暗杠 / 吃。
//  四川麻将无吃，副露只有前三种，且都是同一张牌的 3 或 4 张；
//  国标（MCR）另有「吃」——同花色连续三张的顺子副露，`card` 存最小的那张。
//

package com.feiyu.majiang.core

import java.util.UUID

data class Meld(
    val kind: Kind,
    val card: MahjongCard,
    val id: String = UUID.randomUUID().toString(),
) {
    enum class Kind(val raw: String) {
        PONG("碰"), EXPOSED_KONG("明杠"), CONCEALED_KONG("暗杠"),

        /** 吃：同花色连续三张（仅国标）。Meld.card 是顺子起始牌。 */
        CHOW("吃");

        /** 占用的实体牌张数 */
        val tileCount: Int get() = if (this == PONG || this == CHOW) 3 else 4

        val isKong: Boolean get() = this == EXPOSED_KONG || this == CONCEALED_KONG

        /** 顺子副露（吃） */
        val isChow: Boolean get() = this == CHOW

        companion object {
            /** 四川麻将可用的副露类型（无吃） */
            val sichuanCases = listOf(PONG, EXPOSED_KONG, CONCEALED_KONG)

            /** 国标可用的副露类型 */
            val mcrCases = listOf(CHOW, PONG, EXPOSED_KONG, CONCEALED_KONG)
        }
    }

    val tileCount: Int get() = kind.tileCount

    /** 这组副露实际占用的牌：吃是三张连号，其余是同一张牌重复 */
    val tiles: List<MahjongCard>
        get() = if (kind.isChow) {
            (0..2).map { MahjongCard(card.suit, card.rank + it) }
        } else {
            List(tileCount) { card }
        }
}

/**
 * 副露占用的牌 → 长度 27 的频率数组（碰 3 张、杠 4 张）。
 * 字牌/花牌没有 27 下标，直接跳过（四川麻将本来就不用）。
 */
fun meldsToFrequency27(melds: List<Meld>): IntArray {
    val c = IntArray(27)
    for (m in melds) {
        for (tile in m.tiles) {
            val i = tile.tileIndex
            if (i in 0..26) c[i] += 1
        }
    }
    return c
}
