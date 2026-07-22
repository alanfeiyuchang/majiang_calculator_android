//
//  Meld.kt
//  桌上的牌（副露）：碰 / 明杠 / 暗杠。
//  四川麻将无吃，副露只有这三种，且都是同一张牌的 3 或 4 张。
//

package com.feiyu.majiang.core

import java.util.UUID

data class Meld(
    val kind: Kind,
    val card: MahjongCard,
    val id: String = UUID.randomUUID().toString(),
) {
    enum class Kind(val raw: String) {
        PONG("碰"), EXPOSED_KONG("明杠"), CONCEALED_KONG("暗杠");

        /** 占用的实体牌张数 */
        val tileCount: Int get() = if (this == PONG) 3 else 4

        val isKong: Boolean get() = this != PONG
    }

    val tileCount: Int get() = kind.tileCount
}

/** 副露占用的牌 → 长度 27 的频率数组（碰 3 张、杠 4 张） */
fun meldsToFrequency27(melds: List<Meld>): IntArray {
    val c = IntArray(27)
    for (m in melds) c[m.card.tileIndex] += m.tileCount
    return c
}
