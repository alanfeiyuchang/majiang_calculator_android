//
//  CameraScreen.kt
//  自定义拍摄（CameraX），拍完直接进裁剪，无「重拍/使用照片」确认步骤。
//  与 iOS CameraView 对应；朝向用 OrientationEventListener（等价于 iOS 的重力判定，
//  界面锁定竖屏时也有效）。
//

package com.feiyu.majiang.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.feiyu.majiang.tr

@Composable
fun CameraScreen(onCapture: (Bitmap) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // 手机实际朝向 → 输出旋转（等价 iOS 重力判定；平放/角度模糊时保持上一次朝向）
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                // FIT_CENTER：完整显示拍摄画幅（按比例留黑边），避免默认 FILL_CENTER 裁边导致
                // 取景框比实际拍到的画面「更窄」——拍完看裁剪页会发现四周多出内容（与 iOS .resizeAspect 一致）
                previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                            preview, imageCapture
                        )
                    } catch (_: Exception) {
                        // 被拒绝/相机占用：预览为黑
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
                .background(Color.Black.copy(alpha = 0.4f), CircleShape),
        ) {
            Icon(Icons.Filled.Close, contentDescription = tr("取消"), tint = Color.White)
        }

        // 快门
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 36.dp)
                .size(76.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
                .semantics { contentDescription = tr("拍照") },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(76.dp)) {
                drawCircle(Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5.dp.toPx()))
                drawCircle(Color.White, radius = 31.dp.toPx())
            }
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .clickable {
                        imageCapture.takePicture(
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val bitmap = image.toUprightBitmap()
                                    image.close()
                                    if (bitmap != null) onCapture(bitmap)
                                }

                                override fun onError(exception: ImageCaptureException) {}
                            }
                        )
                    }
            )
        }
    }
}

/** ImageProxy(JPEG) → 已按拍摄朝向摆正的 Bitmap（等价 iOS normalizedUp） */
private fun ImageProxy.toUprightBitmap(): Bitmap? {
    val buffer = planes.firstOrNull()?.buffer ?: return null
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return bmp
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
}
