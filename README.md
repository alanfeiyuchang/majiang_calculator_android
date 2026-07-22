# 麻将听牌计算器 · Android

四川麻将（血战到底）听牌 / 算番计算器的 Android 版，与 iOS 版（`../majiang calculator`）**完全对等移植**：
同一套算法、同一套番型规则、同一份中英文案、同一个牌面美术、同一个本地识别模型。

- Kotlin + Jetpack Compose（Material 3），单 `:app` 模块
- 纯逻辑层 `core/`（无 Android 依赖）：`MahjongCard` / `Meld` / `MahjongCalculator`（胡牌·向听·进张·打牌建议）/ `MahjongScoring`（川麻算番计钱）/ `TileGrouping`（拍照分组）——与 iOS 同名 Swift 文件逐函数对应
- 拍照识别：CameraX 拍摄 → Compose 裁剪页 → ONNX Runtime 跑同一个 `mahjong_yolov8.onnx`（`app/src/main/assets/`，模型无明确许可证，与 iOS 相同来源）
- 牌面图：复用 iOS 资源（FluffyStuff CC0 SVG 渲染的 PNG），在 `res/drawable-nodpi/`
- 设置持久化：SharedPreferences，JSON 键与 iOS UserDefaults 存档一致（含旧键 `kongCountsAsGen` 迁移）
- 双语：`core/EnStrings.kt` 由 iOS `Localizable.xcstrings` 生成（key = 中文源文案）；应用内「中文 / EN」切换，默认中文、即切即生效，与 iOS `LanguageManager` 同语义

## 构建 / 测试

需要 JDK 17+（`gradle.properties` 里指向 Android Studio 自带 JBR）与 Android SDK（`local.properties`）。

```bash
./gradlew :app:assembleDebug        # 构建 APK
./gradlew :app:testDebugUnitTest    # 断言测试
```

测试与 iOS `Tests/ScoringTests.swift`（T1–T34）、`Tests/GroupingTests.swift`（G1–G7）逐条对应：
`app/src/test/java/.../ScoringTest.kt`、`GroupingTest.kt`，另有 `RuleSettingsCodecTest.kt` 验证设置存档兼容。

## 重新生成英文对照表

iOS 侧改了 `Localizable.xcstrings` 后，重跑（在两仓库的共同父目录）：

```bash
python3 - <<'PY'
import json, re
src = json.load(open('majiang calculator/majiang calculator/Localizable.xcstrings'))
def esc(s):
    return (s.replace('\\', '\\\\').replace('"', '\\"')
             .replace('$', '\\$').replace('\n', '\\n'))
pairs = [(k, v['localizations']['en']['stringUnit']['value'])
         for k, v in sorted(src['strings'].items())
         if 'en' in v.get('localizations', {}) and 'stringUnit' in v['localizations']['en']]
out = open('majiang-calculator-android/app/src/main/java/com/feiyu/majiang/core/EnStrings.kt', 'w')
out.write('''//
//  EnStrings.kt
//  由 iOS Localizable.xcstrings 生成的英文对照表（key = 中文源文案，与 iOS 完全同源）。
//  重新生成：见 android 仓库 README。请勿手改——改 iOS xcstrings 后重新生成。
//

package com.feiyu.majiang.core

val EN_STRINGS: Map<String, String> = mapOf(
''')
for k, e in pairs:
    out.write(f'    "{esc(k)}" to "{esc(e)}",\n')
out.write(')\n')
PY
```
