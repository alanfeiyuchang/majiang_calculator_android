# 麻将听牌计算器 · Android

四川麻将（血战到底）听牌 / 算番计算器的 Android 版，与 [iOS 版](https://github.com/alanfeiyuchang/majiang_calculator)
**完全对等移植**：同一套算法、同一套番型规则、同一份中英文案、同一个牌面美术、同一个本地识别模型。

- Kotlin + Jetpack Compose（Material 3），单 `:app` 模块
- 纯逻辑层 `core/`（无 Android 依赖）：`MahjongCard` / `Meld` / `MahjongCalculator`（胡牌·向听·进张·打牌建议）/ `MahjongScoring`（川麻算番计钱）/ `TileGrouping`（拍照分组 + 二次放大区域估计）——与 iOS 同名 Swift 文件逐函数对应
- **手牌 + 桌上的牌**：碰 / 明杠 / 暗杠单独建模，参与算番与听牌枚举（如碰两组后正确剩 7 张手牌可听，而不是 13 张）
- **算番 + 计钱**：碰碰胡、清一色、七小对/豪华七小对、金钩钓、十八罗汉、将对/将七对、门清、断幺九、根、杠上开花/杠上炮/抢杠胡/海底捞月/天胡/地胡等，点番型可看含义弹窗
- **规则设置**：底分、封顶、自摸/根计法（加番/加底/关闭，及「只有杠才算根」）、各番型开关，均持久化并即时生效；内置番型一览
- **拍照识别，不用划区域**：CameraX 拍摄（`FIT_CENTER` 预览，取景=实拍画幅，不再裁边）→ ONNX Runtime 跑 `mahjong_yolov8.onnx`（`app/src/main/assets/`，模型无明确许可证，与 iOS 相同来源）→ 两遍推理（整图低阈值定位牌区 → 裁剪放大精识别）→ 按位置分组为手牌 / 桌上副露（紧挨的多组副露也能按相邻同牌切段拆开）→ **识别完自动分析，不需要确认**；仍支持手动裁剪应对杂乱桌面
- 牌面图：复用 iOS 资源（FluffyStuff CC0 SVG 渲染的 PNG），在 `res/drawable-nodpi/`
- 设置持久化：SharedPreferences，JSON 键与 iOS UserDefaults 存档一致（含旧键 `kongCountsAsGen` 迁移）
- 双语：`core/EnStrings.kt` 由 iOS `Localizable.xcstrings` 生成（key = 中文源文案）；应用内「中文 / EN」切换，默认中文、即切即生效，与 iOS `LanguageManager` 同语义

## 构建 / 测试

需要 JDK 17+（`gradle.properties` 里指向 Android Studio 自带 JBR）与 Android SDK（`local.properties`）。

```bash
./gradlew :app:assembleDebug        # 构建 APK
./gradlew :app:testDebugUnitTest    # 断言测试
```

测试与 iOS `Tests/ScoringTests.swift`（T1–T34）、`Tests/GroupingTests.swift`（G1–G11、Z1–Z4）逐条对应：
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
