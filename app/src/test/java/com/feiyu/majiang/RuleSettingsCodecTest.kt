//
//  RuleSettingsCodecTest.kt
//  设置持久化编解码断言：JSON 键与 iOS 存档一致；缺键用默认值补齐；
//  旧键 kongCountsAsGen 迁移（与 iOS init(from:) 行为对应）。
//

package com.feiyu.majiang

import com.feiyu.majiang.core.GenMode
import com.feiyu.majiang.core.RuleSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleSettingsCodecTest {

    @Test
    fun roundTrip() {
        val s = RuleSettings(
            baseStake = 0.5, fanCap = 4, selfDrawAddsFan = false,
            pengPengHuEnabled = false, qingYiSeEnabled = false, qiXiaoDuiEnabled = false,
            haoHuaEnabled = false, menQingEnabled = false, duanYaoJiuEnabled = false,
            goldenHookFan = 1, jiangEnabled = true, genMode = GenMode.BASE,
            onlyKongCountsAsGen = true, jueZhangEnabled = false, kongBloomEnabled = false,
        )
        val decoded = RuleSettingsStore.decode(RuleSettingsStore.encode(s))
        assertEquals(s, decoded)
    }

    @Test
    fun missingKeysFallBackToDefaults() {
        // 旧版本存档只有部分键：其余用默认值补齐，不整体回退
        val decoded = RuleSettingsStore.decode(JSONObject("""{"baseStake": 2, "fanCap": 3}"""))
        assertEquals(2.0, decoded.baseStake, 0.0)
        assertEquals(3, decoded.fanCap)
        assertEquals(RuleSettings().copy(baseStake = 2.0, fanCap = 3), decoded)
    }

    @Test
    fun legacyKongCountsAsGenMigration() {
        // 旧「杠计根番」布尔迁移：true → 加番，false → 关闭
        assertEquals(GenMode.FAN, RuleSettingsStore.decode(JSONObject("""{"kongCountsAsGen": true}""")).genMode)
        assertEquals(GenMode.OFF, RuleSettingsStore.decode(JSONObject("""{"kongCountsAsGen": false}""")).genMode)
        // 新键优先于旧键
        assertEquals(
            GenMode.BASE,
            RuleSettingsStore.decode(JSONObject("""{"genMode": "base", "kongCountsAsGen": false}""")).genMode
        )
        // 都没有 → 默认加番
        assertEquals(GenMode.FAN, RuleSettingsStore.decode(JSONObject("{}")).genMode)
    }
}
