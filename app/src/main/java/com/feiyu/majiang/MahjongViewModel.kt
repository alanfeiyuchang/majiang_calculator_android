//
//  MahjongViewModel.kt
//  与 iOS MahjongViewModel.swift 一一对应（Compose 状态版）。
//

package com.feiyu.majiang

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.feiyu.majiang.core.DiscardSuggestion
import com.feiyu.majiang.core.MahjongCard
import com.feiyu.majiang.core.Meld
import com.feiyu.majiang.core.RecognitionResult
import com.feiyu.majiang.core.acceptanceTiles
import com.feiyu.majiang.core.calculateWaiting
import com.feiyu.majiang.core.discardSuggestions
import com.feiyu.majiang.core.handShanten
import com.feiyu.majiang.core.handToFrequency27
import com.feiyu.majiang.core.meldsToFrequency27
import com.feiyu.majiang.core.suitCount
import com.feiyu.majiang.recognition.LocalRecognitionException
import com.feiyu.majiang.recognition.LocalTileRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MahjongViewModel : ViewModel() {

    /** 每次选中的实例可区分（同点数同花色多张） */
    data class SelectedTile(val card: MahjongCard, val id: String = UUID.randomUUID().toString())

    /** 一张进张牌及其剩余张数 */
    data class AcceptanceTile(val card: MahjongCard, val remaining: Int)

    val selectedTiles = mutableStateListOf<SelectedTile>()

    /** 桌上的牌（碰 / 明杠 / 暗杠） */
    val melds = mutableStateListOf<Meld>()

    var waitingTiles by mutableStateOf<List<MahjongCard>>(emptyList())
        private set
    var hintMessage by mutableStateOf<String?>(null)

    /** 拍照识别后的非阻塞提示（如「已自动分组 N 副露，请核对」）；不阻断已算出的结果显示 */
    var recognitionNotice by mutableStateOf<String?>(null)
        private set

    /** 正在调用 AI 识别照片 */
    var isRecognizing by mutableStateOf(false)
        private set

    // 分析结果
    /** null = 未计算；-1 = 已和；0 = 听牌；>0 = 向听数 */
    var shantenValue by mutableStateOf<Int?>(null)
        private set

    /** 3n+1 且未听牌时的进张 */
    var acceptance by mutableStateOf<List<AcceptanceTile>>(emptyList())
        private set

    /** 3n+2（带摸牌）时的打牌建议 */
    var discards by mutableStateOf<List<DiscardSuggestion>>(emptyList())
        private set

    /** 听牌但所有可胡牌都已在手中（空听） */
    var isDeadWait by mutableStateOf(false)
        private set

    /** 是否已计算过（用于结果区展示） */
    var hasAnalyzed by mutableStateOf(false)
        private set

    private val maxMelds = 4

    /** 每组副露占掉 3 张的名额，手牌（暗牌）上限随之减少 */
    val maxConcealed: Int get() = 14 - 3 * melds.size

    val canAddMore: Boolean get() = selectedTiles.size < maxConcealed

    /** 是否可分析：暗牌非空且为 3n+1 或 3n+2 张 */
    val canAnalyze: Boolean
        get() {
            val c = selectedTiles.size
            return c > 0 && c % 3 != 0
        }

    /** 某张牌在手牌 + 副露里合计已用张数 */
    fun usedCount(card: MahjongCard): Int =
        selectedTiles.count { it.card == card } +
            melds.sumOf { if (it.card == card) it.tileCount else 0 }

    fun addCard(card: MahjongCard) {
        if (selectedTiles.size >= maxConcealed) return
        if (usedCount(card) >= 4) {
            hintMessage = tr("「%@」在手牌和副露里已用满 4 张。", card.displayText)
            return
        }
        selectedTiles.add(SelectedTile(card))
        clearResult()
    }

    fun removeTile(tile: SelectedTile) {
        selectedTiles.removeAll { it.id == tile.id }
        clearResult()
    }

    // MARK: 副露

    /** 能否加一组该牌的副露（用于键盘禁用态） */
    fun canAddMeld(kind: Meld.Kind, card: MahjongCard): Boolean =
        melds.size < maxMelds &&
            selectedTiles.size <= 14 - 3 * (melds.size + 1) &&
            usedCount(card) + kind.tileCount <= 4

    fun addMeld(kind: Meld.Kind, card: MahjongCard) {
        if (melds.size >= maxMelds) {
            hintMessage = tr("最多 4 组副露。")
            return
        }
        if (selectedTiles.size > 14 - 3 * (melds.size + 1)) {
            hintMessage = tr("手牌太多，放不下这组副露——先删几张手牌（碰/杠会占掉 3 张名额）。")
            return
        }
        if (usedCount(card) + kind.tileCount > 4) {
            hintMessage = tr("「%@」总数会超过 4 张，无法%@。", card.displayText, tr(kind.raw))
            return
        }
        melds.add(Meld(kind, card))
        clearResult()
    }

    fun removeMeld(meld: Meld) {
        melds.removeAll { it.id == meld.id }
        clearResult()
    }

    /** 撤销最近一次选入的牌 */
    fun undoLast() {
        if (selectedTiles.isEmpty()) return
        selectedTiles.removeAt(selectedTiles.lastIndex)
        clearResult()
    }

    /** 按 万 → 条 → 筒，同花色按点数 1–9 排序 */
    fun sortSelected() {
        if (selectedTiles.size <= 1) return
        val sorted = selectedTiles.sortedWith(
            compareBy({ it.card.suit.displaySortIndex }, { it.card.rank })
        )
        selectedTiles.clear()
        selectedTiles.addAll(sorted)
        clearResult()
    }

    fun reset() {
        selectedTiles.clear()
        melds.clear()
        clearResult()
    }

    fun completeCalculation() {
        clearResult()
        if (selectedTiles.isEmpty()) {
            hintMessage = tr("请先选择手牌。")
            return
        }
        val cards = selectedTiles.map { it.card }

        // 缺一门：手牌 + 副露已含三门花色（花猪），无论如何都不能胡
        val combined = handToFrequency27(cards)
        val meldFreq = meldsToFrequency27(melds)
        for (i in 0..26) combined[i] += meldFreq[i]
        if (suitCount(combined) >= 3) {
            hintMessage = tr("手牌与副露合计含万、筒、条三门花色（花猪）。四川麻将需缺一门，请打缺其中一门。")
            return
        }

        when (cards.size % 3) {
            1 -> {
                // 3n+1：算向听 / 听牌
                val sh = handShanten(cards, melds)
                shantenValue = sh
                hasAnalyzed = true
                if (sh == 0) {
                    waitingTiles = calculateWaiting(cards, melds)
                    isDeadWait = waitingTiles.isEmpty()       // 听牌但可胡牌已摸完 → 空听
                } else {
                    acceptance = acceptanceTiles(cards, melds)
                        .map { AcceptanceTile(it.card, it.remaining) }
                }
            }
            2 -> {
                // 3n+2（带摸牌）：判断是否已和，否则给打牌建议
                val sh = handShanten(cards, melds)
                shantenValue = sh
                hasAnalyzed = true
                if (sh != -1) {
                    discards = discardSuggestions(cards, melds)
                }
            }
            else -> {
                // 3n：张数不构成可分析手牌
                val m = melds.size
                val listenCounts = (0..(4 - m)).joinToString("/") { "${3 * it + 1}" }
                hintMessage = tr(
                    "当前副露 %lld 组，手牌需为 %@ 张（听牌）或再多 1 张（打牌建议）。当前手牌 %lld 张。",
                    m, listenCounts, cards.size
                )
            }
        }
    }

    /** 万 → 条 → 筒，同花色按点数排序 */
    private fun sortedCards(cards: List<MahjongCard>): List<MahjongCard> =
        cards.sortedWith(compareBy({ it.suit.displaySortIndex }, { it.rank }))

    /** 直接用一组牌替换当前手牌（用于 AI 识别结果回填），超过上限时截断 */
    fun setHand(cards: List<MahjongCard>) {
        val kept = sortedCards(cards).take(maxConcealed).map { SelectedTile(it) }
        selectedTiles.clear()
        selectedTiles.addAll(kept)
        clearResult()
    }

    /**
     * 回填拍照识别的分组结果：先放副露（限 4 组），再放手牌
     * （限剩余名额、且每张牌手牌+副露合计 ≤ 4）。返回是否发生截断。
     */
    fun applyRecognition(result: RecognitionResult): Boolean {
        melds.clear()
        melds.addAll(result.melds.take(maxMelds))
        val freq = meldsToFrequency27(melds)
        val cap = maxConcealed
        val kept = mutableListOf<MahjongCard>()
        for (card in sortedCards(result.hand)) {
            if (kept.size >= cap) break
            if (freq[card.tileIndex] >= 4) continue
            freq[card.tileIndex] += 1
            kept.add(card)
        }
        selectedTiles.clear()
        selectedTiles.addAll(kept.map { SelectedTile(it) })
        clearResult()
        return kept.size < result.hand.size || result.melds.size > maxMelds
    }

    private var recognizer: LocalTileRecognizer? = null

    /**
     * 本地 AI 识别照片中的麻将牌，回填手牌并自动分析——不需要用户确认。
     * 检测到副露/暗杠靠猜/张数截断时，分析结果照常算出，另附一条不阻断显示的提示。
     */
    fun recognizeAndCalculate(bitmap: Bitmap, createRecognizer: () -> LocalTileRecognizer) {
        if (isRecognizing) return
        isRecognizing = true
        clearResult()
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    val r = recognizer ?: createRecognizer().also { recognizer = it }
                    r.recognize(bitmap)
                }
                val truncated = applyRecognition(result)   // 内部会 clearResult()

                if (canAnalyze) {
                    completeCalculation()   // 内部先 clearResult() 再算；花猪/空手牌等仍会设 hintMessage 阻断
                    if (hintMessage == null) {
                        recognitionNotice = when {
                            result.guessedConcealedKong -> tr(
                                "已自动分组：%lld 副露（含暗杠——只露一张、靠猜，建议核对「桌上的牌」）。",
                                melds.size
                            )
                            melds.isNotEmpty() -> tr("已自动分组：%lld 副露，建议核对「桌上的牌」。", melds.size)
                            truncated -> tr("识别到超过 %lld 张牌，已保留前 %lld 张。", maxConcealed, maxConcealed)
                            else -> null
                        }
                    }
                } else {
                    hintMessage = tr("已识别 %lld 张，张数不构成可分析手牌，请核对后再分析。", selectedTiles.size)
                }
            } catch (e: LocalRecognitionException) {
                selectedTiles.clear()
                melds.clear()
                hintMessage = e.localizedText
            } catch (e: Exception) {
                selectedTiles.clear()
                melds.clear()
                hintMessage = tr("本地识别失败：%@", e.message ?: e.toString())
            } finally {
                isRecognizing = false
            }
        }
    }

    private fun clearResult() {
        waitingTiles = emptyList()
        hintMessage = null
        recognitionNotice = null
        shantenValue = null
        acceptance = emptyList()
        discards = emptyList()
        isDeadWait = false
        hasAnalyzed = false
    }
}
