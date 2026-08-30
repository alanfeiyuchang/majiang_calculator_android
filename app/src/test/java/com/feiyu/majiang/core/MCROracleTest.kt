package com.feiyu.majiang.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 国标算番的官方对照回归集 —— 与 iOS `Tests/MCROracleTests.swift` 用**同一份语料**。
 *
 * 期望值来自北大 Botzone 国标麻将比赛官方算番器（PyMahjongGB）。
 * 431 条覆盖 69 个番种、含 60 条自摸，同时取自普通牌与稀有型加权两批语料
 * （七对/十三幺/九莲宝灯/连七对/全不靠/七星不靠/组合龙/清幺九/混幺九/绿一色/字牌大牌）。
 *
 * 挂了先假定是**我们错了**，除非能拿出规则原文推翻官方算番器。
 */
class MCROracleTest {

    private fun mt(s: String): List<MahjongCard> {
        val out = mutableListOf<MahjongCard>()
        val digits = mutableListOf<Int>()
        for (ch in s) {
            val d = ch.digitToIntOrNull()
            if (d != null) { digits.add(d); continue }
            when (ch) {
                'm' -> digits.forEach { out.add(MahjongCard(MahjongCard.Suit.WAN, it)) }
                'p' -> digits.forEach { out.add(MahjongCard(MahjongCard.Suit.TONG, it)) }
                's' -> digits.forEach { out.add(MahjongCard(MahjongCard.Suit.TIAO, it)) }
                'z' -> digits.forEach {
                    out.add(if (it <= 4) MahjongCard(MahjongCard.Suit.FENG, it)
                            else MahjongCard(MahjongCard.Suit.JIAN, it - 4))
                }
            }
            digits.clear()
        }
        return out
    }

    private fun norm(n: String) = when (n) {
        "独听・嵌张" -> "坎张"; "独听・单钓" -> "单钓将"; "独听・边张" -> "边张"; else -> n
    }

    @Test
    fun matchesOfficialScorer() {
        val text = javaClass.classLoader!!.getResourceAsStream("mcr_oracle_cases.txt")!!
            .bufferedReader().use { it.readText() }
        val scoreFails = mutableListOf<String>()
        val fanFails = mutableListOf<String>()
        var n = 0
        for (line in text.lineSequence()) {
            val p = line.split("|")
            if (p.size != 8) continue
            n++
            val melds = if (p[1] == "-") emptyList() else p[1].split(";").mapNotNull { part ->
                val kv = part.split(","); if (kv.size != 2) return@mapNotNull null
                val card = mt(kv[1]).firstOrNull() ?: return@mapNotNull null
                Meld(when (kv[0]) {
                    "chow" -> Meld.Kind.CHOW
                    "pung" -> Meld.Kind.PONG
                    "ekong" -> Meld.Kind.EXPOSED_KONG
                    else -> Meld.Kind.CONCEALED_KONG
                }, card)
            }
            val ctx = MCRContext(
                selfDrawn = p[5] == "1",
                winningTile = mt(p[2]).first().mcrIndex,
                seatWind = p[3].toInt() - 1,
                prevalentWind = p[4].toInt() - 1,
            )
            val s = scoreMCRHand(handToFrequency34(mt(p[0])), melds, ctx)
            if (s.scoringPoints != p[6].toInt()) {
                scoreFails.add("${p[0]} 期望${p[6]} 实得${s.scoringPoints}")
            }
            if (p[7].split(",").map { norm(it) }.toSet() != s.items.map { it.name }.toSet()) {
                fanFails.add("${p[0]} 期望[${p[7]}] 实得[${s.items.joinToString(",") { it.name }}]")
            }
        }
        assertTrue("用例载入失败，只读到 $n 条", n >= 400)
        assertTrue("与官方算番器总分不一致 ${scoreFails.size}/$n 条:\n" +
                   scoreFails.take(12).joinToString("\n"), scoreFails.isEmpty())
        assertTrue("与官方算番器番种集合不一致 ${fanFails.size}/$n 条:\n" +
                   fanFails.take(8).joinToString("\n"), fanFails.isEmpty())
    }
}
