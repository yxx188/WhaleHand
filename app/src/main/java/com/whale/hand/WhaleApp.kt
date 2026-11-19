package com.whale.hand

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.whale.hand.agent.AgentConfig
import com.whale.hand.agent.AppRules
import com.whale.hand.agent.ModelStore

class WhaleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentConfig.init(this)
        ModelStore.init(this)
        AppRules.init(this)
        applyTheme(AgentConfig.themeMode)
    }

    companion object {
        /** 0 跟随系统 / 1 浅色 / 2 深色 —— 任何页面都能调 */
        fun applyTheme(mode: Int) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
