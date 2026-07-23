//
//  MainScreen.kt
//  主界面：手牌区 / 副露区 / 结果区 / 底部键盘。与 iOS ContentView 一一对应。
//

package com.feiyu.majiang.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feiyu.majiang.L10n
import com.feiyu.majiang.MahjongViewModel
import com.feiyu.majiang.RuleSettingsStore
import com.feiyu.majiang.core.FanItem
import com.feiyu.majiang.core.MahjongCard
import com.feiyu.majiang.core.Meld
import com.feiyu.majiang.core.WinContext
import com.feiyu.majiang.core.WinScore
import com.feiyu.majiang.core.handToFrequency27
import com.feiyu.majiang.core.moneyText
import com.feiyu.majiang.core.scoreWinningHand
import com.feiyu.majiang.tr
import kotlinx.coroutines.launch

// MARK: - 键盘输入去向

enum class InputTarget(val raw: String) {
    HAND("手牌"), PONG("碰"), EXPOSED_KONG("明杠"), CONCEALED_KONG("暗杠");

    val meldKind: Meld.Kind?
        get() = when (this) {
            HAND -> null
            PONG -> Meld.Kind.PONG
            EXPOSED_KONG -> Meld.Kind.EXPOSED_KONG
            CONCEALED_KONG -> Meld.Kind.CONCEALED_KONG
        }
}

// MARK: - 番型文字本地化（与 iOS ContentView 静态函数对应）

fun localizedFanName(key: String): String = tr(key)

fun fanItemText(item: FanItem): String = when {
    item.baseAdd > 0 -> tr("+%lld 底", item.baseAdd)
    item.fan == 0 -> tr("0 番")
    else -> tr("+%lld 番", item.fan)
}

fun fanTotalText(score: WinScore): String =
    if (score.isCapped) tr("%lld 番·封顶 %lld", score.totalFan, score.cappedFan)
    else tr("%lld 番", score.totalFan)

fun fanLine(item: FanItem): String = "${localizedFanName(item.name)} ${fanItemText(item)}"

// MARK: - 主界面

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    viewModel: MahjongViewModel,
    ruleStore: RuleSettingsStore,
    onOpenSettings: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    var keyboardSuit by rememberSaveable { mutableStateOf(MahjongCard.Suit.WAN) }
    var inputTarget by rememberSaveable { mutableStateOf(InputTarget.HAND) }
    var showSelfDraw by rememberSaveable { mutableStateOf(false) }
    // 场景番勾选（自摸侧：杠上开花 / 海底捞月 / 天胡；点炮侧：杠上炮 / 抢杠胡 / 地胡）
    var kongBloom by remember { mutableStateOf(false) }
    var lastTileDraw by remember { mutableStateOf(false) }
    var heavenly by remember { mutableStateOf(false) }
    var kongDischargeWin by remember { mutableStateOf(false) }
    var robbingKong by remember { mutableStateOf(false) }
    var earthly by remember { mutableStateOf(false) }
    /** 点击听牌弹出番型明细 */
    var breakdownCard by remember { mutableStateOf<MahjongCard?>(null) }

    val settings = ruleStore.settings
    val hasKongMeld = viewModel.melds.any { it.kind.isKong }
    val kongBloomAvailable = hasKongMeld && settings.kongBloomEnabled

    fun winContext(selfDrawn: Boolean) = WinContext(
        selfDrawn = selfDrawn,
        kongBloom = selfDrawn && kongBloom && kongBloomAvailable,
        lastTileDraw = selfDrawn && lastTileDraw,
        kongDischargeWin = !selfDrawn && kongDischargeWin,
        robbingKong = !selfDrawn && robbingKong,
        heavenly = selfDrawn && heavenly,
        earthly = !selfDrawn && earthly,
    )

    /** 手牌补上 card 后这副胡牌的番与钱 */
    fun winScore(adding: MahjongCard, selfDrawn: Boolean? = null): WinScore {
        val freq = handToFrequency27(viewModel.selectedTiles.map { it.card })
        freq[adding.tileIndex] += 1
        return scoreWinningHand(
            freq, viewModel.melds.toList(), settings,
            winContext(selfDrawn ?: showSelfDraw)
        )
    }

    /** 3n+2 已和时整副牌的番与钱 */
    fun wonScore(selfDrawn: Boolean): WinScore = scoreWinningHand(
        handToFrequency27(viewModel.selectedTiles.map { it.card }),
        viewModel.melds.toList(), settings, winContext(selfDrawn)
    )

    val scrollState = rememberScrollState()
    var resultOffset by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    // 分析结束后把结果区滚进视野；手牌变了则清空上一局的场景番勾选
    LaunchedEffect(viewModel.hasAnalyzed) {
        if (viewModel.hasAnalyzed) {
            kotlinx.coroutines.delay(250)
            scrollState.animateScrollTo(resultOffset.toInt())
        } else {
            kongBloom = false; lastTileDraw = false; heavenly = false
            kongDischargeWin = false; robbingKong = false; earthly = false
        }
    }
    LaunchedEffect(viewModel.hintMessage) {
        if (viewModel.hintMessage != null) {
            kotlinx.coroutines.delay(250)
            scrollState.animateScrollTo(resultOffset.toInt())
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(tr("听牌计算器"), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    Row {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = tr("规则设置"))
                        }
                        val context = androidx.compose.ui.platform.LocalContext.current
                        TextButton(onClick = { L10n.toggle(context) }) {
                            Icon(Icons.Filled.Language, contentDescription = tr("切换语言"), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(L10n.toggleLabel, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                actions = {
                    val canClear = viewModel.selectedTiles.isNotEmpty() || viewModel.melds.isNotEmpty()
                        || viewModel.waitingTiles.isNotEmpty()
                    IconButton(onClick = { viewModel.reset() }, enabled = canClear) {
                        Icon(Icons.Filled.Delete, contentDescription = tr("清空全部"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
            )
        },
        bottomBar = {
            BottomKeyboard(
                viewModel = viewModel,
                keyboardSuit = keyboardSuit,
                onSuitChange = { keyboardSuit = it },
                inputTarget = inputTarget,
                onTargetChange = { inputTarget = it },
                onPhoto = onTakePhoto,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(Theme.sectionSpacing),
            ) {
                HandSection(viewModel)
                MeldSection(viewModel)
                AnalyzeButton(viewModel, onAnalyze = { viewModel.completeCalculation() })
                RecognitionNoticeBanner(viewModel.recognitionNotice)
                Box(Modifier.onGloballyPositioned { resultOffset = it.positionInParent().y }) {
                    ResultSection(
                        viewModel = viewModel,
                        showSelfDraw = showSelfDraw,
                        onShowSelfDrawChange = { showSelfDraw = it },
                        kongBloomAvailable = kongBloomAvailable,
                        kongBloom = kongBloom, onKongBloom = { kongBloom = it },
                        lastTileDraw = lastTileDraw, onLastTileDraw = { lastTileDraw = it },
                        heavenly = heavenly, onHeavenly = { heavenly = it },
                        kongDischargeWin = kongDischargeWin, onKongDischargeWin = { kongDischargeWin = it },
                        robbingKong = robbingKong, onRobbingKong = { robbingKong = it },
                        earthly = earthly, onEarthly = { earthly = it },
                        winScore = ::winScore,
                        wonScore = ::wonScore,
                        onBreakdown = { breakdownCard = it },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            if (viewModel.isRecognizing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                RoundedCornerShape(20.dp)
                            )
                            .padding(28.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(tr("AI 正在识别牌面…"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    // 番型明细
    val card = breakdownCard
    if (card != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(onDismissRequest = { breakdownCard = null }, sheetState = sheetState) {
            FanBreakdownSheet(
                card = card,
                scoreDiscard = winScore(card, selfDrawn = false),
                scoreSelf = winScore(card, selfDrawn = true),
                onDone = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { breakdownCard = null }
                },
            )
        }
    }
}

// MARK: 手牌区

@Composable
private fun HandSection(viewModel: MahjongViewModel) {
    val n = viewModel.selectedTiles.size
    val (hint, hintColor) = when {
        n == 0 -> tr("选入手牌") to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        n % 3 == 1 -> tr("可算向听 / 听牌") to Theme.hintGreen
        n % 3 == 2 -> tr("可给打牌建议") to Theme.hintGreen
        else -> tr("再选 1 张") to Color(0xFFFF9500)
    }

    SectionCard(
        title = tr("手里的牌"),
        icon = Icons.Filled.GridView,
        accessory = "${viewModel.selectedTiles.size} / ${viewModel.maxConcealed}",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hint,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = hintColor,
                modifier = Modifier
                    .background(hintColor.copy(alpha = 0.12f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { viewModel.sortSelected() }, enabled = viewModel.selectedTiles.size > 1) {
                Icon(Icons.Filled.SwapVert, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text(tr("自动排序"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(
                onClick = { viewModel.undoLast() },
                enabled = viewModel.selectedTiles.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text(tr("撤销"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        if (viewModel.selectedTiles.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
            ) {
                Icon(
                    Icons.Filled.TouchApp, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(tr("尚未选牌"), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    tr("在底部选择花色与点数加入；点击已选牌可删除"),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        } else {
            // 7 列手牌网格（列宽自适应，与 iOS flexible grid 一致）
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.selectedTiles.chunked(7).forEach { rowTiles ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rowTiles.forEach { item ->
                            Box(Modifier.weight(1f)) {
                                MahjongTileChip(
                                    card = item.card,
                                    onTap = { viewModel.removeTile(item) },
                                    fillWidth = true,
                                )
                            }
                        }
                        repeat(7 - rowTiles.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

// MARK: 分析按钮（手牌区 + 桌上副露区之后，结果区之前）

@Composable
private fun AnalyzeButton(viewModel: MahjongViewModel, onAnalyze: () -> Unit) {
    Button(
        onClick = onAnalyze,
        enabled = viewModel.selectedTiles.isNotEmpty() && !viewModel.isRecognizing,
        colors = ButtonDefaults.buttonColors(containerColor = Theme.accent),
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(tr("分析手牌"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 拍照识别后的非阻塞提示（不挡结果，只是提醒核对） */
@Composable
private fun RecognitionNoticeBanner(notice: String?) {
    if (notice == null) return
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFF9500).copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Icon(
            Icons.Filled.Info, contentDescription = null,
            tint = Color(0xFFFF9500), modifier = Modifier.size(16.dp),
        )
        Text(notice, fontSize = 12.sp, color = Color(0xFFFF9500))
    }
}

// MARK: 桌上副露区

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeldSection(viewModel: MahjongViewModel) {
    SectionCard(
        title = tr("桌上的牌（碰 / 杠）"),
        icon = Icons.Filled.Layers,
        accessory = tr("%lld / 4 组", viewModel.melds.size),
    ) {
        if (viewModel.melds.isEmpty()) {
            Text(
                tr("已碰、已杠的牌放这里：底部切到「碰 / 明杠 / 暗杠」后点牌加入。"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                viewModel.melds.forEach { meld ->
                    MeldChipGroup(meld) { viewModel.removeMeld(meld) }
                }
            }
        }
    }
}

// MARK: 结果区

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ResultSection(
    viewModel: MahjongViewModel,
    showSelfDraw: Boolean,
    onShowSelfDrawChange: (Boolean) -> Unit,
    kongBloomAvailable: Boolean,
    kongBloom: Boolean, onKongBloom: (Boolean) -> Unit,
    lastTileDraw: Boolean, onLastTileDraw: (Boolean) -> Unit,
    heavenly: Boolean, onHeavenly: (Boolean) -> Unit,
    kongDischargeWin: Boolean, onKongDischargeWin: (Boolean) -> Unit,
    robbingKong: Boolean, onRobbingKong: (Boolean) -> Unit,
    earthly: Boolean, onEarthly: (Boolean) -> Unit,
    winScore: (MahjongCard, Boolean?) -> WinScore,
    wonScore: (Boolean) -> WinScore,
    onBreakdown: (MahjongCard) -> Unit,
) {
    val hint = viewModel.hintMessage

    @Composable
    fun SpecialFanChips(selfDrawn: Boolean) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (selfDrawn) tr("自摸时") else tr("点炮时"),
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            @Composable
            fun chip(title: String, isOn: Boolean, onToggle: (Boolean) -> Unit) {
                Text(
                    tr(title),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (isOn) Theme.accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier
                        .background(
                            if (isOn) Theme.accent.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                            CircleShape
                        )
                        .border(
                            1.2.dp,
                            if (isOn) Theme.accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            CircleShape
                        )
                        .clickable { onToggle(!isOn) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (selfDrawn) {
                if (kongBloomAvailable) chip("杠上开花", kongBloom, onKongBloom)
                chip("海底捞月", lastTileDraw, onLastTileDraw)
                chip("天胡", heavenly, onHeavenly)
            } else {
                chip("杠上炮", kongDischargeWin, onKongDischargeWin)
                chip("抢杠胡", robbingKong, onRobbingKong)
                chip("地胡", earthly, onEarthly)
            }
        }
    }

    @Composable
    fun SettleColumn(titleKey: String, score: WinScore) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                tr(titleKey), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                "${fanTotalText(score)} ${moneyText(score.money)}",
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Theme.moneyGreen,
            )
        }
    }

    if (hint != null) {
        SectionCard(title = tr("提示"), icon = Icons.Filled.Warning) {
            Text(hint, fontSize = 15.sp, color = Color(0xFFFF9500))
        }
    } else if (viewModel.hasAnalyzed) {
        val sh = viewModel.shantenValue ?: 99
        when {
            sh == -1 -> {
                // 已和（3n+2 且成牌）：番型 + 结算金额
                SectionCard(title = tr("已和！"), icon = Icons.Filled.CheckCircle) {
                    val scoreDiscard = wonScore(false)
                    val scoreSelf = wonScore(true)
                    Text(
                        scoreDiscard.items.joinToString(" · ") { fanLine(it) },
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Theme.moneyGreen,
                    )
                    SpecialFanChips(selfDrawn = false)
                    SpecialFanChips(selfDrawn = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        SettleColumn("点炮（放炮者付）", scoreDiscard)
                        SettleColumn("自摸（三家各付）", scoreSelf)
                    }
                }
            }
            viewModel.discards.isNotEmpty() -> {
                // 打牌建议（3n+2）
                SectionCard(
                    title = tr("打牌建议"),
                    icon = Icons.Filled.TouchApp,
                    accessory = tr("%lld 种", viewModel.discards.size),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        viewModel.discards.take(6).forEach { s ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    tr("打"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                )
                                MahjongTileChip(card = s.discard)
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        if (s.resultingShanten == 0) tr("听牌") else tr("向听 %lld", s.resultingShanten),
                                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (s.resultingShanten == 0) Theme.moneyGreen
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        tr("进张 %lld 张 · %lld 门", s.acceptanceCount, s.acceptance.size),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            sh == 0 -> {
                if (viewModel.isDeadWait) {
                    SectionCard(title = tr("听牌（空听）"), icon = Icons.Filled.Warning) {
                        Text(
                            tr("已听牌，但可胡的牌都已在手中（4 张用尽），无法再胡——空听。"),
                            fontSize = 15.sp, color = Color(0xFFFF9500),
                        )
                    }
                } else {
                    // 听牌：每张听牌标注番数与单家金额
                    SectionCard(
                        title = tr("听牌"),
                        icon = Icons.Filled.CheckCircle,
                        accessory = tr("共 %lld 门", viewModel.waitingTiles.size),
                    ) {
                        // 与 iOS 分段控件一致：无勾选图标
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = !showSelfDraw,
                                onClick = { onShowSelfDrawChange(false) },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                icon = {},
                                label = { Text(tr("点炮")) },
                            )
                            SegmentedButton(
                                selected = showSelfDraw,
                                onClick = { onShowSelfDrawChange(true) },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                icon = {},
                                label = { Text(tr("自摸")) },
                            )
                        }

                        SpecialFanChips(selfDrawn = showSelfDraw)

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            viewModel.waitingTiles.forEach { card ->
                                val score = winScore(card, null)
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    MahjongTileChip(card = card, onTap = { onBreakdown(card) }, large = true)
                                    Text(
                                        tr("%lld 番", score.totalFan),
                                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                    Text(
                                        moneyText(score.money),
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                        color = Theme.moneyGreen,
                                    )
                                }
                            }
                        }

                        Text(
                            if (showSelfDraw) tr("金额为单家：自摸后三家各付这个数。点牌可看番型明细。")
                            else tr("金额为单家：点炮时放炮那家付这个数。点牌可看番型明细。"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                }
            }
            else -> {
                // 向听 + 进张（3n+1 未听牌）
                val total = viewModel.acceptance.sumOf { it.remaining }
                SectionCard(
                    title = tr("向听 %lld", viewModel.shantenValue ?: 0),
                    icon = Icons.Filled.TrackChanges,
                    accessory = tr("进张 %lld 张", total),
                ) {
                    if (viewModel.acceptance.isEmpty()) {
                        Text(
                            tr("无有效进张（受缺一门所限）。"),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            viewModel.acceptance.forEach { item ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    MahjongTileChip(card = item.card, large = true)
                                    Text(
                                        tr("剩%lld", item.remaining),
                                        fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        SectionCard(title = tr("分析结果"), icon = Icons.Filled.Help) {
            Text(
                tr("选牌后点「分析手牌」：手牌 3n+1 张算听牌/向听，3n+2 张给打牌建议；已碰、已杠的牌用底部「碰 / 明杠 / 暗杠」加到桌上。"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

// MARK: 番型明细

@Composable
private fun FanBreakdownSheet(
    card: MahjongCard,
    scoreDiscard: WinScore,
    scoreSelf: WinScore,
    onDone: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 与 iOS 一致：标题居中，「完成」靠右
        Box(Modifier.fillMaxWidth()) {
            Text(
                tr("番型明细"), fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterEnd)) {
                Text(tr("完成"))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MahjongTileChip(card = card, large = true)
            Text(tr("胡「%@」", card.displayText), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Text(
            tr("结算（单家）"), fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        @Composable
        fun settleRow(titleKey: String, score: WinScore) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(titleKey), fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "${fanTotalText(score)} ${moneyText(score.money)}",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Theme.moneyGreen,
                )
            }
        }
        settleRow("点炮 · 放炮者付", scoreDiscard)
        settleRow("自摸 · 三家各付", scoreSelf)

        HorizontalDivider()
        Text(
            tr("番型"), fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        scoreDiscard.items.forEach { item ->
            Row {
                Text(localizedFanName(item.name), fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    fanItemText(item), fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

// MARK: 底部：操作 + 键盘

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomKeyboard(
    viewModel: MahjongViewModel,
    keyboardSuit: MahjongCard.Suit,
    onSuitChange: (MahjongCard.Suit) -> Unit,
    inputTarget: InputTarget,
    onTargetChange: (InputTarget) -> Unit,
    onPhoto: () -> Unit,
) {
    fun keyboardCanAdd(card: MahjongCard): Boolean {
        val kind = inputTarget.meldKind
        return if (kind != null) viewModel.canAddMeld(kind, card)
        else viewModel.canAddMore && viewModel.usedCount(card) < 4
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)),
    ) {
        HorizontalDivider()
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 10.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onPhoto,
                enabled = !viewModel.isRecognizing,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Theme.accent),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr("拍照识别手牌"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tr("点选加入").uppercase(),   // iOS textCase(.uppercase)
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.weight(1f))
                SingleChoiceSegmentedButtonRow(Modifier.width(270.dp)) {
                    InputTarget.entries.forEachIndexed { i, target ->
                        SegmentedButton(
                            selected = inputTarget == target,
                            onClick = { onTargetChange(target) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = InputTarget.entries.size),
                            label = { Text(tr(target.raw), fontSize = 12.sp, maxLines = 1) },
                            icon = {},
                        )
                    }
                }
            }

            // 花色选择
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MahjongCard.Suit.displayOrder.forEach { suit ->
                    val selected = keyboardSuit == suit
                    val color = Theme.suitColor(suit)
                    Box(
                        Modifier
                            .weight(1f)
                            .background(
                                if (selected) color.copy(alpha = 0.22f) else Color.Transparent,
                                RoundedCornerShape(12.dp)
                            )
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onSuitChange(suit) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tr(suit.raw),
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = if (selected) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            // 9 个点数键
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..9).forEach { r ->
                    val card = MahjongCard(keyboardSuit, r)
                    val enabled = keyboardCanAdd(card)
                    Image(
                        painter = painterResource(tileDrawable(card)),
                        contentDescription = card.displayTextCompact,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .alpha(if (enabled) 1f else 0.35f)
                            .clip(RoundedCornerShape(7.dp))
                            .border(1.dp, Color.Black.copy(alpha = 0.28f), RoundedCornerShape(7.dp))
                            .clickable(enabled = enabled) {
                                val kind = inputTarget.meldKind
                                if (kind != null) {
                                    viewModel.addMeld(kind, card)
                                    onTargetChange(InputTarget.HAND)
                                } else {
                                    viewModel.addCard(card)
                                }
                            },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
    }
}
