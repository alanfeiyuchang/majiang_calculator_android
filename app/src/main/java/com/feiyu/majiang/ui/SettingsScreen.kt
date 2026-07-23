//
//  SettingsScreen.kt
//  麻将规则设置：各地规则差异在这里配置，持久化并即时影响算番/金额。
//  「番型一览」列出当前规则下支持的全部番型与番数。与 iOS SettingsView.swift 一一对应。
//

package com.feiyu.majiang.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.feiyu.majiang.RuleSettingsStore
import com.feiyu.majiang.core.GenMode
import com.feiyu.majiang.core.RuleSettings
import com.feiyu.majiang.tr

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: RuleSettingsStore,
    onDone: () -> Unit,
    onPickFromLibrary: () -> Unit = {},
) {
    var showFanReference by remember { mutableStateOf(false) }

    if (showFanReference) {
        FanReferenceScreen(settings = store.settings, onBack = { showFanReference = false })
        return
    }

    BackHandler { onDone() }
    val s = store.settings

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(tr("规则设置"), fontWeight = FontWeight.SemiBold) },
                actions = { TextButton(onClick = onDone) { Text(tr("完成")) } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 计钱
            SettingsGroup(header = tr("计钱"), footer = tr("金额 = 底分 × 2^番数（封顶截断），为单家输赢：点炮由放炮者付，自摸三家各付。")) {
                // 与 iOS 一致：右对齐无边框输入 + 「元」
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 12.dp)) {
                    Text(tr("底分"), fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    var stakeText by remember(s.baseStake) {
                        mutableStateOf(
                            if (s.baseStake == s.baseStake.toLong().toDouble()) "${s.baseStake.toLong()}"
                            else "${s.baseStake}"
                        )
                    }
                    BasicTextField(
                        value = stakeText,
                        onValueChange = { text ->
                            stakeText = text
                            text.toDoubleOrNull()?.let { v -> store.update { st -> st.copy(baseStake = v) } }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 16.sp,
                            textAlign = TextAlign.End,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.width(90.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(tr("元"), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                SettingsDivider()
                PickerRow(
                    title = tr("封顶"),
                    options = RuleSettings.fanCapChoices.map { tr(RuleSettings.fanCapLabel(it)) },
                    selectedIndex = RuleSettings.fanCapChoices.indexOf(s.fanCap).coerceAtLeast(0),
                    onSelect = { i -> store.update { st -> st.copy(fanCap = RuleSettings.fanCapChoices[i]) } },
                )
            }

            // 计法
            SettingsGroup(
                header = tr("计法"),
                footer = tr("根 = 4 张同牌。默认「碰 + 手里第 4 张」「手握 4 张」也算根；开启「只有杠才算根」后仅明杠/暗杠算。加底/关闭时，杠只按刮风下雨即时结算，不再翻倍进胡牌金额。"),
            ) {
                PickerRow(
                    title = tr("自摸"),
                    options = listOf(tr("加番（+1 番）"), tr("加底（+1 底分）")),
                    selectedIndex = if (s.selfDrawAddsFan) 0 else 1,
                    onSelect = { i -> store.update { st -> st.copy(selfDrawAddsFan = i == 0) } },
                )
                SettingsDivider()
                PickerRow(
                    title = tr("根"),
                    options = GenMode.entries.map { tr(it.label) },
                    selectedIndex = GenMode.entries.indexOf(s.genMode),
                    onSelect = { i -> store.update { st -> st.copy(genMode = GenMode.entries[i]) } },
                )
                SettingsDivider()
                ToggleRow(
                    title = tr("只有杠才算根"),
                    checked = s.onlyKongCountsAsGen,
                    enabled = s.genMode != GenMode.OFF,
                    onChange = { v -> store.update { st -> st.copy(onlyKongCountsAsGen = v) } },
                )
            }

            // 番型开关
            SettingsGroup(
                header = tr("番型开关"),
                footer = tr("门清 = 没有碰、没有明杠（暗杠可），点炮/自摸都算。断幺九 = 整副牌完全没有 1 和 9。七小对关闭后，七对牌型不计基础 2 番；豪华关闭则龙七对按平七小对计。将对 = 碰碰胡且全是 2/5/8（关闭时按普通碰碰胡/七小对计）。金钩钓已含碰碰胡，不叠加。"),
            ) {
                ToggleRow(tr("碰碰胡（1 番）"), s.pengPengHuEnabled) { v -> store.update { it.copy(pengPengHuEnabled = v) } }
                SettingsDivider()
                ToggleRow(tr("清一色（2 番）"), s.qingYiSeEnabled) { v -> store.update { it.copy(qingYiSeEnabled = v) } }
                SettingsDivider()
                ToggleRow(tr("七小对（2 番）"), s.qiXiaoDuiEnabled) { v -> store.update { it.copy(qiXiaoDuiEnabled = v) } }
                SettingsDivider()
                ToggleRow(tr("豪华七小对（每龙 +1 番）"), s.haoHuaEnabled) { v -> store.update { it.copy(haoHuaEnabled = v) } }
                SettingsDivider()
                ToggleRow(tr("门清（+1 番）"), s.menQingEnabled) { v -> store.update { it.copy(menQingEnabled = v) } }
                SettingsDivider()
                ToggleRow(tr("断幺九（+1 番）"), s.duanYaoJiuEnabled) { v -> store.update { it.copy(duanYaoJiuEnabled = v) } }
                SettingsDivider()
                PickerRow(
                    title = tr("金钩钓"),
                    options = listOf(tr("1 番"), tr("2 番")),
                    selectedIndex = if (s.goldenHookFan == 1) 0 else 1,
                    onSelect = { i -> store.update { st -> st.copy(goldenHookFan = i + 1) } },
                )
                SettingsDivider()
                ToggleRow(tr("将对 / 将七对（3 / 4 番）"), s.jiangEnabled) { v -> store.update { it.copy(jiangEnabled = v) } }
                SettingsDivider()
                ToggleRow(tr("杠上开花（+1 番）"), s.kongBloomEnabled) { v -> store.update { it.copy(kongBloomEnabled = v) } }
            }

            // 番型一览
            SettingsGroup {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFanReference = true }
                        .padding(vertical = 12.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null, tint = Theme.accent)
                    Spacer(Modifier.width(10.dp))
                    Text(tr("番型一览"), fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                }
            }

            // 恢复默认
            SettingsGroup {
                Text(
                    tr("恢复默认规则"),
                    color = Color(0xFFE53935),
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { store.resetToDefaults() }
                        .padding(vertical = 12.dp),
                )
            }

            // 从相册选择识别手牌
            SettingsGroup(footer = tr("拍照识别在主界面底部；这里是从相册里选一张已有的照片来识别。")) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickFromLibrary() }
                        .padding(vertical = 12.dp),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, tint = Theme.accent)
                    Spacer(Modifier.width(10.dp))
                    Text(tr("从相册选择识别手牌"), fontSize = 16.sp)
                }
            }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun SettingsGroup(
    header: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column {
        if (header != null) {
            Text(
                header, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
            )
        }
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
        }
        if (footer != null) {
            Text(
                footer, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp),
            )
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Text(title, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759)),   // iOS 绿色
        )
    }
}

/** iOS 菜单选择行：标题在左，当前值 + 上下箭头在右，点击弹出菜单 */
@Composable
private fun PickerRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(vertical = 14.dp),
        ) {
            Text(title, fontSize = 16.sp)
            Spacer(Modifier.weight(1f))
            Text(
                options.getOrElse(selectedIndex) { "" },
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.UnfoldMore, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = {
                        if (i == selectedIndex) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = { expanded = false; onSelect(i) },
                )
            }
        }
    }
}

// MARK: - 番型一览

/** 当前规则下支持的全部番型与番数（随设置动态变化） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FanReferenceScreen(settings: RuleSettings, onBack: () -> Unit) {
    BackHandler { onBack() }

    data class Row(
        val name: String,
        val fan: String,
        val note: String? = null,
        val enabled: Boolean = true,
    )

    val patternRows = listOf(
        Row(tr("平胡"), tr("0 番"), tr("兜底")),
        Row(tr("碰碰胡"), tr("1 番"), enabled = settings.pengPengHuEnabled),
        Row(tr("清一色"), tr("2 番"), enabled = settings.qingYiSeEnabled),
        Row(tr("七小对"), tr("2 番"), enabled = settings.qiXiaoDuiEnabled),
        Row(tr("豪华七小对（豪七）"), tr("3 番"), tr("每多一龙再 +1：双豪华 4 番、三豪华 5 番"),
            enabled = settings.qiXiaoDuiEnabled && settings.haoHuaEnabled),
        Row(tr("金钩钓"), tr("%lld 番", settings.goldenHookFan), tr("已含碰碰胡，不叠加")),
        Row(tr("将对"), tr("3 番"), tr("碰碰胡且全是 2/5/8"), enabled = settings.jiangEnabled),
        Row(tr("将七对"), tr("4 番"), tr("七小对且全是 2/5/8"), enabled = settings.jiangEnabled),
        Row(tr("十八罗汉"), tr("%lld 番", settings.goldenHookFan + 4), tr("金钩钓 + 4 杠（含 4 根）"),
            enabled = settings.genMode == GenMode.FAN),
    )
    val comboRows = listOf(
        Row(tr("清对（清一色 + 碰碰胡）"), tr("3 番")),
        Row(tr("清七对"), tr("4 番")),
        Row(tr("清豪七"), tr("5 番")),
        Row(tr("清金钩钓"), tr("%lld 番", settings.goldenHookFan + 2)),
    )
    val genFan = when (settings.genMode) {
        GenMode.FAN -> tr("每个 +1 番")
        GenMode.BASE -> tr("每个 +1 底")
        GenMode.OFF -> tr("不计")
    }
    val extraRows = listOf(
        Row(tr("门清"), tr("+1 番"), tr("没有碰、没有明杠（暗杠可），点炮/自摸都算"), enabled = settings.menQingEnabled),
        Row(tr("断幺九"), tr("+1 番"), tr("整副牌完全没有 1 和 9"), enabled = settings.duanYaoJiuEnabled),
        Row(
            tr("根"), genFan,
            if (settings.onlyKongCountsAsGen) tr("只有杠：明杠 / 暗杠")
            else tr("4 张同牌：杠 / 手握 4 张 / 碰 + 手里第 4 张"),
            enabled = settings.genMode != GenMode.OFF,
        ),
    )
    val situationalRows = listOf(
        Row(tr("自摸"), if (settings.selfDrawAddsFan) tr("+1 番") else tr("+1 底")),
        Row(tr("杠上开花"), tr("+1 番"), tr("自摸侧"), enabled = settings.kongBloomEnabled),
        Row(tr("海底捞月"), tr("+1 番"), tr("自摸侧")),
        Row(tr("杠上炮"), tr("+1 番"), tr("点炮侧")),
        Row(tr("抢杠胡"), tr("+1 番"), tr("点炮侧")),
        Row(tr("天胡"), tr("+4 番"), tr("庄家起手胡")),
        Row(tr("地胡"), tr("+4 番"), tr("胡第一张打出的牌")),
    )

    @Composable
    fun section(title: String, rows: List<Row>) {
        Column {
            Text(
                title, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
            )
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    rows.forEachIndexed { i, row ->
                        if (i > 0) SettingsDivider()
                        Column(
                            Modifier
                                .padding(vertical = 8.dp)
                                .alpha(if (row.enabled) 1f else 0.55f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(row.name, fontSize = 15.sp)
                                if (!row.enabled) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        tr("已关闭"),
                                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                                                CircleShape
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                Text(
                                    row.fan, fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = if (row.enabled) 0.6f else 0.3f
                                    ),
                                )
                            }
                            if (row.note != null) {
                                Text(
                                    row.note, fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(tr("番型一览"), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            section(tr("基础牌型（自动识别，可叠加）"), patternRows)
            section(tr("常见组合（自动叠出，不单列）"), comboRows)
            section(tr("附加番"), extraRows)
            section(tr("场景番（胡牌时勾选）"), situationalRows)
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}
