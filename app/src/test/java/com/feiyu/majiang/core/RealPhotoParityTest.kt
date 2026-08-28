package com.feiyu.majiang.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 实拍回归：12 张真实照片的**模型原始检测框**（`real_dets.json`，由 iOS 端
 * `LocalTileRecognizer.debugDetections()` 导出，只有坐标和类别，不含图像）喂进
 * 几何层，锁住 `myTilesRegion` / `groupTiles` 的输出。
 *
 * 期望值不是我拍脑袋写的，而是把**同一份输入**跑过 iOS 的 `TileGrouping.swift`
 * 得到的结果——这条测试的作用是保证两端逐张一致，任何一端改算法都会在这里断掉。
 *
 * 覆盖不到第二遍「裁剪放大重识别」（那要真跑 ONNX），只锁几何与分组。
 */
class RealPhotoParityTest {

    private val classNames = listOf(
        "1B", "1C", "1D", "1F", "1S", "2B", "2C", "2D", "2F", "2S",
        "3B", "3C", "3D", "3F", "3S", "4B", "4C", "4D", "4F", "4S",
        "5B", "5C", "5D", "6B", "6C", "6D", "7B", "7C", "7D", "8B",
        "8C", "8D", "9B", "9C", "9D", "EW", "GD", "NW", "RD", "SW", "WD", "WW"
    )

    private fun cardFor(classId: Int): MahjongCard? {
        if (classId < 0 || classId >= classNames.size) return null
        val name = classNames[classId]
        if (name.length != 2) return null
        val rank = name[0].digitToIntOrNull() ?: return null
        if (rank !in 1..9) return null
        return when (name[1]) {
            'C' -> MahjongCard(MahjongCard.Suit.WAN, rank)
            'D' -> MahjongCard(MahjongCard.Suit.TONG, rank)
            'B' -> MahjongCard(MahjongCard.Suit.TIAO, rank)
            else -> null
        }
    }

    /** preview 图统一是 1050×1400；换尺寸会改变外扩量，期望值也要跟着重算 */
    private val imgW = 1050f
    private val imgH = 1400f

    private fun loadDets(): Map<String, List<FloatArray>> {
        val text = javaClass.classLoader!!.getResourceAsStream("real_dets.json")!!
            .bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        return root.keys().asSequence().associateWith { key ->
            val arr = root.getJSONArray(key)
            (0 until arr.length()).map { i ->
                val d = arr.getJSONArray(i)
                FloatArray(6) { j -> d.getDouble(j).toFloat() }
            }
        }
    }

    private fun rects(dets: List<FloatArray>): List<GeoRect> =
        dets.map { GeoRect(it[0] * imgW, it[1] * imgH, it[2] * imgW, it[3] * imgH) }

    private fun boxes(dets: List<FloatArray>): List<TileBox> =
        dets.filter { it[4] >= 0.5f }.mapNotNull { d ->
            cardFor(d[5].toInt())?.let { c ->
                val x = d[0] * imgW
                val y = d[1] * imgH
                val w = d[2] * imgW
                val h = d[3] * imgH
                TileBox(minX = x, maxX = x + w, cy = y + h / 2, height = h, card = c)
            }
        }

    private fun fmtRegion(r: GeoRect?): String =
        if (r == null) "nil"
        else String.format(
            "%.4f %.4f %.4f %.4f",
            r.x / imgW, r.y / imgH, r.width / imgW, r.height / imgH
        )

    private fun fmtGroup(g: RecognitionResult): String {
        val hand = g.hand.joinToString(" ") { "${it.rank}${it.suit.raw}" }
        val melds = g.melds.joinToString(" ") { "${it.kind.raw}:${it.card.rank}${it.card.suit.raw}" }
        return "hand=[$hand] melds=[$melds] guessed=${g.guessedConcealedKong} valid=${g.hasValidTileCount}"
    }

    // ---- 期望值：iOS TileGrouping.swift 在同一份输入上的输出 ----

    private val expectedRegion = mapOf(
        "01" to "0.0290 0.5135 0.8653 0.2097",
        "02" to "0.0834 0.5121 0.7684 0.2785",
        "03" to "0.0214 0.5660 0.9640 0.1730",
        "04" to "0.0436 0.4329 0.8426 0.3417",
        "05" to "0.0754 0.5328 0.9241 0.2292",
        "06" to "0.0526 0.4794 0.9077 0.1969",
        "07" to "0.0326 0.5677 0.9260 0.1189",
        "08" to "0.0915 0.4963 0.7828 0.2935",
        "09" to "0.1248 0.5430 0.7796 0.2917",
        "10" to "0.0299 0.4624 0.8806 0.2526",
        "11" to "0.1054 0.4515 0.7683 0.2575",
        "12" to "0.0343 0.5703 0.9157 0.0889",
    )

    private val expectedGroup = mapOf(
        "01" to "hand=[2万 2万 6万 7万 8万 8万 7条 7条 8条 8条] melds=[碰:4条] guessed=false valid=true",
        "02" to "hand=[2万 2万 2万 4条 4条 6条 4筒 2条 9筒 8万 7万 8万 9万 7条 7条 8条 8条] melds=[] guessed=false valid=false",
        "03" to "hand=[5万 7万 3万 2万 2万 3万 4条 4条 4条 8条 8条 8条 8条 7条] melds=[] guessed=false valid=true",
        "04" to "hand=[4条 6条 7万 7万 6万] melds=[暗杠:7条 碰:3万 明杠:8条] guessed=true valid=true",
        "05" to "hand=[2筒 4筒 5筒 6筒] melds=[明杠:1筒 碰:4条 明杠:8条] guessed=false valid=true",
        "06" to "hand=[1筒 1筒 1筒 2筒 4筒 5筒 6筒 6条 6条 6条 8条 8条 8条 5万 6条 1筒 2条 8条] melds=[] guessed=false valid=false",
        "07" to "hand=[4筒 5筒 6筒 7筒 8筒 6条 6条 8条 8条 8条 6条 8条 6条 1筒] melds=[碰:1筒] guessed=false valid=false",
        "08" to "hand=[4筒 5筒 6筒 7筒 8筒 4条 4条 3筒 6条 5筒 1筒 2筒 8条] melds=[碰:1筒 碰:8条] guessed=false valid=false",
        "09" to "hand=[4筒 4筒 5筒 5筒 6筒 7筒 8筒 1筒 2条 7条 2筒 8条] melds=[碰:1筒 碰:8条] guessed=false valid=false",
        "10" to "hand=[4筒 4筒 4筒 4筒 5筒 5筒 5筒 6筒 7筒 8筒 7条 1条 1筒 2条 6条 2筒 8条] melds=[碰:1筒] guessed=false valid=false",
        "11" to "hand=[3筒 4筒 4筒 4筒 6筒 7筒 8筒 1筒 2条 6条 4筒 8条] melds=[碰:1筒 碰:5筒] guessed=false valid=false",
        "12" to "hand=[4万 5万 6万 7万 7万 8万 8万 4筒 4筒 4筒 6筒 7筒 8筒 1筒 9条 6条 4筒 7条 4筒 8条] melds=[] guessed=false valid=false",
    )

    @Test
    fun fixtureCoversAllTwelvePhotos() {
        val dets = loadDets()
        assertEquals(12, dets.size)
        assertEquals(expectedRegion.keys.sorted(), dets.keys.sorted())
    }

    @Test
    fun autoFrameRegionMatchesIos() {
        val dets = loadDets()
        for ((key, expected) in expectedRegion.toSortedMap()) {
            val r = myTilesRegion(rects(dets.getValue(key)), imgW, imgH)
            assertNotNull("照片 $key 没能框出手牌区域", r)
            assertEquals("照片 $key 的自动框与 iOS 不一致", expected, fmtRegion(r))
        }
    }

    @Test
    fun groupingMatchesIos() {
        val dets = loadDets()
        for ((key, expected) in expectedGroup.toSortedMap()) {
            val g = groupTiles(boxes(dets.getValue(key)))
            assertEquals("照片 $key 的分组与 iOS 不一致", expected, fmtGroup(g))
        }
    }
}
