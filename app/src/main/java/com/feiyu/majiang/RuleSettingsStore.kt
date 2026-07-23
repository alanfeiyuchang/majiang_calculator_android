//
//  RuleSettingsStore.kt
//  设置存取：SharedPreferences 持久化，变更即保存。
//  JSON 键与 iOS UserDefaults 存档完全一致（含旧键 kongCountsAsGen 迁移、
//  缺新键时用默认值补齐，不整体回退）。
//

package com.feiyu.majiang

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.feiyu.majiang.core.GenMode
import com.feiyu.majiang.core.RuleSettings
import org.json.JSONObject

class RuleSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("majiang.prefs", Context.MODE_PRIVATE)

    var settings: RuleSettings by mutableStateOf(load())
        private set

    fun update(transform: (RuleSettings) -> RuleSettings) {
        settings = transform(settings)
        save(settings)
    }

    fun resetToDefaults() {
        settings = RuleSettings()
        save(settings)
    }

    private fun load(): RuleSettings {
        val json = prefs.getString(STORAGE_KEY, null) ?: return RuleSettings()
        return try {
            decode(JSONObject(json))
        } catch (_: Exception) {
            RuleSettings()
        }
    }

    private fun save(s: RuleSettings) {
        prefs.edit().putString(STORAGE_KEY, encode(s).toString()).apply()
    }

    companion object {
        private const val STORAGE_KEY = "ruleSettings.v1"

        // 手写解码：旧版本存档缺新键时用默认值补齐（与 iOS init(from:) 对应）
        fun decode(o: JSONObject): RuleSettings {
            val d = RuleSettings()
            val genMode = GenMode.fromRaw(o.optString("genMode", ""))
                ?: if (o.has("kongCountsAsGen")) {
                    if (o.optBoolean("kongCountsAsGen")) GenMode.FAN else GenMode.OFF  // 旧「杠计根番」布尔迁移
                } else GenMode.FAN
            return RuleSettings(
                baseStake = if (o.has("baseStake")) o.optDouble("baseStake", d.baseStake) else d.baseStake,
                fanCap = o.optInt("fanCap", d.fanCap),
                selfDrawAddsFan = o.optBoolean("selfDrawAddsFan", d.selfDrawAddsFan),
                pingHuEnabled = o.optBoolean("pingHuEnabled", d.pingHuEnabled),
                pengPengHuEnabled = o.optBoolean("pengPengHuEnabled", d.pengPengHuEnabled),
                qingYiSeEnabled = o.optBoolean("qingYiSeEnabled", d.qingYiSeEnabled),
                qiXiaoDuiEnabled = o.optBoolean("qiXiaoDuiEnabled", d.qiXiaoDuiEnabled),
                haoHuaEnabled = o.optBoolean("haoHuaEnabled", d.haoHuaEnabled),
                menQingEnabled = o.optBoolean("menQingEnabled", d.menQingEnabled),
                duanYaoJiuEnabled = o.optBoolean("duanYaoJiuEnabled", d.duanYaoJiuEnabled),
                goldenHookFan = o.optInt("goldenHookFan", d.goldenHookFan),
                jiangEnabled = o.optBoolean("jiangEnabled", d.jiangEnabled),
                genMode = genMode,
                onlyKongCountsAsGen = o.optBoolean("onlyKongCountsAsGen", d.onlyKongCountsAsGen),
                kongBloomEnabled = o.optBoolean("kongBloomEnabled", d.kongBloomEnabled),
            )
        }

        fun encode(s: RuleSettings): JSONObject = JSONObject().apply {
            put("baseStake", s.baseStake)
            put("fanCap", s.fanCap)
            put("selfDrawAddsFan", s.selfDrawAddsFan)
            put("pingHuEnabled", s.pingHuEnabled)
            put("pengPengHuEnabled", s.pengPengHuEnabled)
            put("qingYiSeEnabled", s.qingYiSeEnabled)
            put("qiXiaoDuiEnabled", s.qiXiaoDuiEnabled)
            put("haoHuaEnabled", s.haoHuaEnabled)
            put("menQingEnabled", s.menQingEnabled)
            put("duanYaoJiuEnabled", s.duanYaoJiuEnabled)
            put("goldenHookFan", s.goldenHookFan)
            put("jiangEnabled", s.jiangEnabled)
            put("genMode", s.genMode.raw)
            put("onlyKongCountsAsGen", s.onlyKongCountsAsGen)
            put("kongBloomEnabled", s.kongBloomEnabled)
        }
    }
}
