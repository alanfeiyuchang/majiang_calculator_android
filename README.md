# 麻将听牌计算器 · Android

四川麻将（血战到底）与 **国标麻将（MCR）** 的听牌 / 算番计算器 Android 版，与
[iOS 版](https://github.com/alanfeiyuchang/majiang_calculator) **完全对等移植**：同一套算法、
同一套番型规则、同一份中英文案、同一个牌面美术、同一个本地识别模型。两端永远不应算出不同结果。

## 下载

[**mahjong-calculator.apk**](https://github.com/alanfeiyuchang/majiang_calculator_android/releases/download/latest-apk/mahjong-calculator.apk)
—— 直接装的侧载包，跟着 `main` 一起更新（[release 页面](https://github.com/alanfeiyuchang/majiang_calculator_android/releases/tag/latest-apk)）。

手机上用浏览器打开上面的链接下载，系统会提示「允许从此来源安装应用」，开了就能装。
debug 签名，不走 Play Store；覆盖安装不会丢设置。

- Kotlin + Jetpack Compose（Material 3），单 `:app` 模块
- 纯逻辑层 `core/`（无 Android 依赖）：`MahjongCard` / `Meld` / `MahjongCalculator`（胡牌·向听·进张·打牌建议）/ `MahjongScoring`（川麻算番计钱）/ `TileGrouping`（拍照分组 + 二次放大区域估计）——与 iOS 同名 Swift 文件逐函数对应
- **手牌 + 桌上的牌**：碰 / 明杠 / 暗杠单独建模，参与算番与听牌枚举（如碰两组后正确剩 7 张手牌可听，而不是 13 张）
- **算番 + 计钱**：碰碰胡、清一色、七小对/豪华七小对、金钩钓、十八罗汉、将对/将七对、门清、断幺九、根、杠上开花/杠上炮/抢杠胡/海底捞月/天胡/地胡等，点番型可看含义弹窗
- **两种玩法**：四川麻将（血战到底）/ 国标麻将（MCR），在设置页切换并持久化；键盘、副露种类、
  和牌牌型与算番引擎随之整套切换
- **规则设置**：底分、封顶、自摸/根计法（加番/加底/关闭，及「只有杠才算根」）、各番型开关，均持久化并即时生效；内置番型一览
- **拍照识别，不用划区域**：CameraX 拍摄（`FIT_CENTER` 预览，取景=实拍画幅，不再裁边）→ ONNX Runtime 跑 `mahjong_yolov8.onnx`（`app/src/main/assets/`，模型无明确许可证，与 iOS 相同来源）→ 两遍推理（整图低阈值定位牌区 → 裁剪放大精识别）→ 按位置分组为手牌 / 桌上副露（紧挨的多组副露也能按相邻同牌切段拆开）→ **识别完自动分析，不需要确认**；仍支持手动裁剪应对杂乱桌面
- 牌面图：复用 iOS 资源（FluffyStuff CC0 SVG 渲染的 PNG），在 `res/drawable-nodpi/`
- 设置持久化：SharedPreferences，JSON 键与 iOS UserDefaults 存档一致（含旧键 `kongCountsAsGen` 迁移）
- 双语：`core/EnStrings.kt` 由 iOS `Localizable.xcstrings` 生成（key = 中文源文案）；应用内「中文 / EN」切换，默认中文、即切即生效，与 iOS `LanguageManager` 同语义

## 国标麻将（MCR）

设置页把玩法切到「国标麻将」后，整个引擎换成中国麻将竞赛规则：

- **牌张**：三门数牌之外多出 **风（东南西北）**、**箭（中发白）**、**花（春夏秋冬梅兰竹菊）**。
  花牌不参与和牌，单独持有、每张 1 分。字牌花牌没有牌面图资源，界面上用象牙底 + 单字的
  文字牌面画。
- **副露**：碰 / 明杠 / 暗杠之外多出 **吃**——点起始牌自动配成连续三张。吃只能吃上家，
  本工具作为分析器不强制这一条，副露区里写明了。
- **和牌牌型**：标准型（4 面子 + 1 将，字牌只能成刻）、七对 / 连七对、十三幺、
  全不靠 / 七星不靠、组合龙（第 4 副面子可以是副露）、九莲宝灯。四川的缺一门 / 花猪
  在国标下完全禁用。
- **算番**：**81 种番型**，起和 8 分，花牌计分但不进起和分。**不重复计算原则**是真的实现了
  而不是近似（见 `core/MCRScoring.kt`）：
  - *不可拆分 / 套算一次 / 就高不就低* —— 面子结构番（一般高、喜相逢、清龙、一色三同顺、
    双同刻…）取四副面子的**总分最高集合划分**，一副面子不可能被两个番重复用。
  - *不可重复* —— 排除表 `MCR_FAN_EXCLUDES` 把已被高番型包含的低番型整条删掉
    （大三元删箭刻/双箭刻、清一色删无字/缺一门、四暗刻删碰碰和/三暗刻/双暗刻…）。
  - 整手牌对 所有和牌牌型 × 所有拆解 × 和牌张的所有归属 取最优。
  - 点炮成的刻子算明刻，所以同一副牌自摸是四暗刻、点炮只有三暗刻。
- **圈风 / 门风** 在设置页选，喂给圈风刻 / 门风刻。
- **规则细则**：各地规则书有分歧的 5 处做成用户可选项（见下）。
- **拍照识别在这里是残缺的**：内置 YOLO 模型只认 27 类数牌，风牌、箭牌、花牌**永远认不出来**。
  国标模式下每次识别都会附一条提示说明这点；回填识别结果时**保留**用户已经手动补进去的花牌。

### 规则细则（用户可选）

设置页国标专属的「规则细则」分组，5 项，**默认值 = 最常见的算法**，键名与 iOS 存档完全一致：

| 设置键 | 默认 | 开 / 关 |
|---|---|---|
| `mcrZiYiSeCountsHunYaoJiu` | 开 | 字一色之外是否再计 32 分的混幺九 |
| `mcrJiuLianCountsShuangAnKe` | 开 | 九莲宝灯之外是否再计 2 分的双暗刻 |
| `mcrSevenPairsAllowsQuadAsTwoPairs` | 开 | 七对里四张相同能否拆成两对 |
| `mcrPerKongFanWithThreeKongs` | 开 | 三杠 32 分之外是否再单独计每个杠（明杠 1 / 暗杠 2） |
| `mcrWaitFanHighestReading` | 开 | 边张/坎张/单钓将：就高跨解法取最优，还是只在听法唯一时才计 |

## 构建 / 测试

需要 JDK 17+（`gradle.properties` 里指向 Android Studio 自带 JBR）与 Android SDK（`local.properties`）。

```bash
./gradlew :app:assembleDebug        # 构建 APK
./gradlew :app:testDebugUnitTest    # 断言测试
```

测试与 iOS `Tests/ScoringTests.swift`（T1–T34）、`Tests/GroupingTests.swift`（G1–G11、Z1–Z4）、
`Tests/MCRScoringTests.swift`（M/W/S/T/F/P/Q/O/E/R 各组）逐条对应：
`app/src/test/java/.../ScoringTest.kt`、`GroupingTest.kt`、`MCRScoringTest.kt`，
另有 `RuleSettingsCodecTest.kt` 验证设置存档兼容、`RealPhotoParityTest.kt` 锁识别几何的跨端一致。
国标测试对 5 项规则细则的开 / 关两种状态都断言了具体分数值，数值与 iOS 完全相同。

## 重新生成英文对照表

iOS 侧改了 `Localizable.xcstrings` 后，重跑（在两仓库的共同父目录）：

```bash
python3 - <<'PY'
import json, re
src = json.load(open('majiang calculator/majiang calculator/Localizable.xcstrings'))
def esc(s):
    return (s.replace('\\', '\\\\').replace('"', '\\"')
             .replace('$', '\\$').replace('\n', '\\n'))
def english(v):
    en = v.get('localizations', {}).get('en')
    if not en:
        return None
    if 'stringUnit' in en:
        return en['stringUnit']['value']
    # 复数变体：Android 侧没有 plural 机制，取 other（通用形）
    plural = en.get('variations', {}).get('plural', {})
    for key in ('other', 'many', 'one'):
        if key in plural:
            return plural[key]['stringUnit']['value']
    return None
pairs = [(k, e) for k, v in sorted(src['strings'].items())
         if (e := english(v)) is not None]
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
