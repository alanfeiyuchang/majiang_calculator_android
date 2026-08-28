//
//  GameMode.kt
//  玩法选择：四川麻将（血战到底）/ 国标麻将（MCR）。
//  玩法决定牌张集合、副露种类、和牌牌型与算番引擎，界面随之变化。
//  与 iOS GameMode.swift 一一对应。
//

package com.feiyu.majiang.core

enum class GameMode(val raw: String) {
    /** 四川麻将（血战到底）：只有万/筒/条，缺一门，无吃 */
    SICHUAN("sichuan"),

    /** 国标麻将（MCR）：全套牌张 + 花牌，有吃，81 种番型，起和 8 分 */
    MCR("mcr");

    /** 设置页/标题上的名字（中文 key，走本地化） */
    val label: String
        get() = when (this) {
            SICHUAN -> "四川麻将（血战到底）"
            MCR -> "国标麻将"
        }

    /** 一句话说明 */
    val summary: String
        get() = when (this) {
            SICHUAN -> "只用万/筒/条，必须缺一门，无吃；按番数翻倍算钱。"
            MCR -> "含风/箭/花，可吃，81 种番型，起和 8 分。"
        }

    /** 键盘上可用的花色 */
    val suits: List<MahjongCard.Suit>
        get() = if (this == MCR) MahjongCard.Suit.mcrDisplayOrder else MahjongCard.Suit.displayOrder

    /** 可用的副露种类 */
    val meldKinds: List<Meld.Kind>
        get() = if (this == MCR) Meld.Kind.mcrCases else Meld.Kind.sichuanCases

    val isMCR: Boolean get() = this == MCR

    companion object {
        fun fromRaw(raw: String?): GameMode? = entries.firstOrNull { it.raw == raw }
    }
}
