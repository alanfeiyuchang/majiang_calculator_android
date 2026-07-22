//
//  L10n.kt
//  应用内语言切换（中文 / 英文），默认中文，不跟随系统、无需重启。
//  与 iOS LanguageManager 同语义：key = 中文源文案；英文表由 iOS Localizable.xcstrings 生成。
//  查不到翻译时回落显示 key 本身（与 iOS 行为一致）。
//

package com.feiyu.majiang

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.feiyu.majiang.core.EN_STRINGS

object L10n {
    private const val STORAGE_KEY = "appLanguage"
    private const val PREFS = "majiang.prefs"

    /** "zh-Hans" 或 "en"；Compose 状态，切换即全局重组 */
    var language: String by mutableStateOf("zh-Hans")
        private set

    val isEnglish: Boolean get() = language.startsWith("en")

    /** 切换按钮上显示「要切去的那个语言」 */
    val toggleLabel: String get() = if (isEnglish) "中文" else "EN"

    fun init(context: Context) {
        language = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(STORAGE_KEY, "zh-Hans") ?: "zh-Hans"
    }

    fun toggle(context: Context) {
        language = if (isEnglish) "zh-Hans" else "en"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(STORAGE_KEY, language).apply()
    }

    private val tokenRegex = Regex("%(@|lld|d)")

    /** 本地化 + 顺序格式化（模板占位符：%@ / %lld） */
    fun tr(key: String, vararg args: Any): String {
        val template = if (isEnglish) EN_STRINGS[key] ?: key else key
        if (args.isEmpty()) return template
        var i = 0
        return tokenRegex.replace(template) { m ->
            args.getOrNull(i++)?.toString() ?: m.value
        }
    }
}

/** 简写 */
fun tr(key: String, vararg args: Any): String = L10n.tr(key, *args)
