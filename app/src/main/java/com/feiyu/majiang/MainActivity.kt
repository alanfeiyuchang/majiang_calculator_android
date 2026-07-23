//
//  MainActivity.kt
//  入口：主界面 / 规则设置 / 拍照 / 裁剪 的简单导航。与 iOS majiang_calculatorApp 对应。
//

package com.feiyu.majiang

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.feiyu.majiang.recognition.CameraScreen
import com.feiyu.majiang.recognition.CropScreen
import com.feiyu.majiang.recognition.ImageSource
import com.feiyu.majiang.recognition.LocalTileRecognizer
import com.feiyu.majiang.ui.MainScreen
import com.feiyu.majiang.ui.MajiangTheme
import com.feiyu.majiang.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L10n.init(this)
        setContent {
            MajiangTheme {
                AppRoot()
            }
        }
    }
}

/** 选中/拍摄后待裁剪的图片（裁剪到只剩自己的手牌再识别） */
private data class PendingImage(val bitmap: Bitmap, val source: ImageSource)

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val viewModel: MahjongViewModel = viewModel()
    val ruleStore = remember { RuleSettingsStore(context.applicationContext) }

    var showSettings by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var pendingCrop by remember { mutableStateOf<PendingImage?>(null) }

    // 相册选择（系统 Photo Picker，无需权限；ImageDecoder 自动按 EXIF 摆正）
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val bmp = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, _, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    d.isMutableRequired = false
                }
            }.getOrNull()
            if (bmp != null) pendingCrop = PendingImage(bmp, ImageSource.LIBRARY)
        }
    }

    // 相机权限
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) showCamera = true }

    fun startCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            showCamera = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun pickPhoto() {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val crop = pendingCrop
    when {
        showCamera -> CameraScreen(
            onCapture = { bmp ->
                showCamera = false
                pendingCrop = PendingImage(bmp, ImageSource.CAMERA)
            },
            onCancel = { showCamera = false },
        )
        crop != null -> CropScreen(
            image = crop.bitmap,
            source = crop.source,
            onCancel = { pendingCrop = null },
            onRetake = {
                val source = crop.source
                pendingCrop = null
                when (source) {
                    ImageSource.CAMERA -> startCamera()
                    ImageSource.LIBRARY -> pickPhoto()
                }
            },
            onCrop = { cropped ->
                pendingCrop = null
                viewModel.recognizeAndCalculate(cropped) { LocalTileRecognizer(context.applicationContext) }
            },
        )
        showSettings -> SettingsScreen(
            store = ruleStore,
            onDone = { showSettings = false },
            onPickFromLibrary = { showSettings = false; pickPhoto() },
        )
        else -> MainScreen(
            viewModel = viewModel,
            ruleStore = ruleStore,
            onOpenSettings = { showSettings = true },
            onTakePhoto = ::startCamera,
        )
    }
}
