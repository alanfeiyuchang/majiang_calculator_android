//
//  LocalTileRecognizer.kt
//  On-device 麻将牌识别：用 ONNX Runtime 跑 YOLOv8 模型（mahjong_yolov8.onnx，与 iOS 同一模型）。
//  纯本地推理，不联网、不需要 API Key。与 iOS LocalTileRecognizer.swift 一一对应。
//
//  类别命名：后缀 C=Characters(万)、D=Dots(筒)、B=Bamboo(条)，点数 1–9；
//  其余（花/季/风/箭）四川麻将用不到，识别时忽略。
//

package com.feiyu.majiang.recognition

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.feiyu.majiang.core.GeoRect
import com.feiyu.majiang.core.MahjongCard
import com.feiyu.majiang.core.RecognitionResult
import com.feiyu.majiang.core.TileBox
import com.feiyu.majiang.core.groupTiles
import com.feiyu.majiang.core.zoomRegion
import com.feiyu.majiang.tr
import java.nio.FloatBuffer

class LocalRecognitionException(val localizedText: String) : Exception(localizedText)

class LocalTileRecognizer(private val context: Context) {

    // YOLOv8 模型参数（与 iOS 相同）
    private val inputSize = 640
    private val confidenceThreshold = 0.5f
    /** 第一遍估计「牌所在区域」用的低阈值（多找些框，只用于定位，不进结果） */
    private val regionThreshold = 0.35f
    private val iouThreshold = 0.45f

    /** class_names.txt 的 42 个类别（顺序与模型输出通道一致） */
    private val classNames = listOf(
        "1B", "1C", "1D", "1F", "1S", "2B", "2C", "2D", "2F", "2S",
        "3B", "3C", "3D", "3F", "3S", "4B", "4C", "4D", "4F", "4S",
        "5B", "5C", "5D", "6B", "6C", "6D", "7B", "7C", "7D", "8B",
        "8C", "8D", "9B", "9C", "9D", "EW", "GD", "NW", "RD", "SW", "WD", "WW"
    )

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Synchronized
    private fun loadSessionIfNeeded(): OrtSession {
        session?.let { return it }
        val bytes = try {
            context.assets.open("mahjong_yolov8.onnx").use { it.readBytes() }
        } catch (_: Exception) {
            throw LocalRecognitionException(tr("未找到本地识别模型文件。"))
        }
        try {
            val e = OrtEnvironment.getEnvironment()
            val s = e.createSession(bytes, OrtSession.SessionOptions())
            env = e
            session = s
            return s
        } catch (ex: Exception) {
            throw LocalRecognitionException(tr("本地识别失败：%@", ex.message ?: ex.toString()))
        }
    }

    fun recognize(bitmap: Bitmap): RecognitionResult {
        loadSessionIfNeeded()

        // 第一遍：整图识别。低阈值的框只用来估计「牌所在区域」，达标（≥ 正式阈值）的作候选结果
        val first = detect(bitmap, regionThreshold)
        var finalDets = first.dets.filter { it.score >= confidenceThreshold }

        // 第二遍：牌只占画面一小部分时（整图缩到 640 后每张牌太小），
        // 把牌区裁出来放大重新识别，用更清晰的结果替换
        val boxesInImage = first.dets.map { d ->
            GeoRect(
                x = (d.x1 - first.dw) / first.scale,
                y = (d.y1 - first.dh) / first.scale,
                width = (d.x2 - d.x1) / first.scale,
                height = (d.y2 - d.y1) / first.scale,
            )
        }
        val region = zoomRegion(boxesInImage, bitmap.width.toFloat(), bitmap.height.toFloat())
        if (region != null) {
            val x = region.x.toInt().coerceIn(0, bitmap.width - 1)
            val y = region.y.toInt().coerceIn(0, bitmap.height - 1)
            val w = region.width.toInt().coerceAtMost(bitmap.width - x)
            val h = region.height.toInt().coerceAtMost(bitmap.height - y)
            if (w > 0 && h > 0) {
                val cropped = Bitmap.createBitmap(bitmap, x, y, w, h)
                val second = detect(cropped, confidenceThreshold)
                if (second.dets.size >= finalDets.size) {   // 保底：第二遍反而更差就保留第一遍
                    finalDets = second.dets
                }
            }
        }

        // 只保留 万/筒/条（忽略风/箭等），转成牌盒后做空间聚类分组
        val boxes = finalDets.mapNotNull { d ->
            cardFor(d.classId)?.let { c ->
                TileBox(minX = d.x1, maxX = d.x2, cy = d.cy, height = d.h, card = c)
            }
        }
        if (boxes.isEmpty()) {
            throw LocalRecognitionException(tr("未能从图片中识别到麻将牌，请确保牌面清晰、正对镜头、光线充足。"))
        }
        val result = groupTiles(boxes)
        if (result.hand.isEmpty() && result.melds.isEmpty()) {
            throw LocalRecognitionException(tr("未能从图片中识别到麻将牌，请确保牌面清晰、正对镜头、光线充足。"))
        }
        return result
    }

    /**
     * 一次完整推理：letterbox → ONNX → 解码 → NMS。
     * 检测框在 640 空间；scale/dw/dh 用于映射回原图像素。
     */
    private data class DetectPass(
        val dets: List<Detection>,
        val scale: Float,
        val dw: Float,
        val dh: Float,
    )

    private fun detect(bitmap: Bitmap, threshold: Float): DetectPass {
        val session = loadSessionIfNeeded()
        val pre = preprocess(bitmap) ?: throw LocalRecognitionException(tr("无法读取所选图片，请换一张试试。"))

        val detections: List<Detection>
        try {
            val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
            OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(pre.tensor), shape).use { input ->
                session.run(mapOf("images" to input)).use { outputs ->
                    val output = outputs.get("output0").orElse(null)
                        ?: throw LocalRecognitionException(tr("本地识别失败：%@", tr("缺少输出")))
                    val tensor = output as OnnxTensor
                    val outShape = tensor.info.shape.map { it.toInt() }   // [1, 4+numClasses, anchors]
                    val fb = tensor.floatBuffer
                    val floats = FloatArray(fb.remaining()).also { fb.get(it) }
                    detections = decode(floats, outShape, threshold)
                }
            }
        } catch (e: LocalRecognitionException) {
            throw e
        } catch (e: Exception) {
            throw LocalRecognitionException(tr("本地识别失败：%@", e.message ?: e.toString()))
        }
        return DetectPass(nms(detections), pre.scale, pre.dw, pre.dh)
    }

    // MARK: - 预处理（letterbox → RGB/255 → CHW）

    private data class Preprocessed(
        val tensor: FloatArray,
        val scale: Float,
        val dw: Float,
        val dh: Float,
    )

    private fun preprocess(bitmap: Bitmap): Preprocessed? {
        val imgW = bitmap.width
        val imgH = bitmap.height
        if (imgW <= 0 || imgH <= 0) return null

        val s = inputSize.toFloat()
        val scale = minOf(s / imgW, s / imgH)
        val newW = Math.round(imgW * scale)
        val newH = Math.round(imgH * scale)
        val dw = (inputSize - newW) / 2f
        val dh = (inputSize - newH) / 2f

        // 灰色填充 (114,114,114) 的 letterbox 画布
        val canvasBmp = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            bitmap, null,
            Rect(dw.toInt(), dh.toInt(), dw.toInt() + newW, dh.toInt() + newH),
            paint
        )

        val plane = inputSize * inputSize
        val pixels = IntArray(plane)
        canvasBmp.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        canvasBmp.recycle()

        val tensor = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            tensor[i] = ((p shr 16) and 0xFF) / 255f            // R
            tensor[plane + i] = ((p shr 8) and 0xFF) / 255f     // G
            tensor[2 * plane + i] = (p and 0xFF) / 255f         // B
        }
        return Preprocessed(tensor, scale, dw, dh)
    }

    // MARK: - 解码 YOLOv8 输出

    private data class Detection(
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val score: Float, val classId: Int,
    ) {
        val cy: Float get() = (y1 + y2) / 2
        val h: Float get() = y2 - y1
    }

    /** floats 布局为 [1, channels, anchors]，元素 (c,a) 索引 = c*anchors + a */
    private fun decode(floats: FloatArray, shape: List<Int>, threshold: Float): List<Detection> {
        if (shape.size != 3) return emptyList()
        val channels = shape[1]
        val anchors = shape[2]
        val numClasses = channels - 4
        if (numClasses <= 0 || floats.size < channels * anchors) return emptyList()

        val dets = mutableListOf<Detection>()
        for (a in 0 until anchors) {
            var bestId = 0
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val v = floats[(4 + c) * anchors + a]
                if (v > bestScore) { bestScore = v; bestId = c }
            }
            if (bestScore < threshold) continue

            val cx = floats[a]
            val cy = floats[anchors + a]
            val w = floats[2 * anchors + a]
            val hh = floats[3 * anchors + a]
            dets.add(Detection(cx - w / 2, cy - hh / 2, cx + w / 2, cy + hh / 2, bestScore, bestId))
        }
        return dets
    }

    // MARK: - NMS

    private fun nms(input: List<Detection>): List<Detection> {
        val sorted = input.sortedByDescending { it.score }
        val kept = mutableListOf<Detection>()
        for (d in sorted) {
            if (kept.all { iou(it, d) <= iouThreshold }) kept.add(d)
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = maxOf(a.x1, b.x1); val y1 = maxOf(a.y1, b.y1)
        val x2 = minOf(a.x2, b.x2); val y2 = minOf(a.y2, b.y2)
        val iw = maxOf(0f, x2 - x1); val ih = maxOf(0f, y2 - y1)
        val inter = iw * ih
        val areaA = maxOf(0f, a.x2 - a.x1) * maxOf(0f, a.y2 - a.y1)
        val areaB = maxOf(0f, b.x2 - b.x1) * maxOf(0f, b.y2 - b.y1)
        val union = areaA + areaB - inter
        return if (union <= 0) 0f else inter / union
    }

    // MARK: - 类别 → MahjongCard（仅 万/筒/条）

    private fun cardFor(classId: Int): MahjongCard? {
        if (classId < 0 || classId >= classNames.size) return null
        val name = classNames[classId]          // 如 "5C"
        if (name.length != 2) return null
        val rank = name[0].digitToIntOrNull() ?: return null
        if (rank !in 1..9) return null
        return when (name[1]) {
            'C' -> MahjongCard(MahjongCard.Suit.WAN, rank)   // Characters 万
            'D' -> MahjongCard(MahjongCard.Suit.TONG, rank)  // Dots 筒
            'B' -> MahjongCard(MahjongCard.Suit.TIAO, rank)  // Bamboo 条
            else -> null                                      // F/S/风/箭：忽略
        }
    }
}
