package com.whale.hand.agent

import android.content.Context
import android.content.SharedPreferences

object AgentConfig {

    private const val PREF = "whale_config"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    /** 当前模型档案（ModelStore）优先；档案还没初始化时退回旧单配置 */
    var apiUrl: String
        get() = ModelStore.current()?.apiUrl?.takeIf { it.isNotBlank() }
            ?: sp.getString("api_url", "https://api.deepseek.com/v1/chat/completions")!!
        set(v) { sp.edit().putString("api_url", v).apply() }

    var apiKey: String
        get() = ModelStore.current()?.apiKey?.takeIf { it.isNotBlank() }
            ?: sp.getString("api_key", "")!!
        set(v) { sp.edit().putString("api_key", v).apply() }

    var model: String
        get() = ModelStore.current()?.model?.takeIf { it.isNotBlank() }
            ?: sp.getString("model", "deepseek-chat")!!
        set(v) { sp.edit().putString("model", v).apply() }

    /** 动作前是否显示预览光标 */
    var previewEnabled: Boolean
        get() = sp.getBoolean("preview_enabled", true)
        set(v) { sp.edit().putBoolean("preview_enabled", v).apply() }

    /** 预览样式：0 圆圈+十字，1 只有十字，2 高亮方框，3 不显示 */
    var previewStyle: Int
        get() = sp.getInt("preview_style", 0)
        set(v) { sp.edit().putInt("preview_style", v).apply() }

    /** 预览颜色（ARGB） */
    var previewColor: Int
        get() = sp.getInt("preview_color", 0xFF38BDF8.toInt())
        set(v) { sp.edit().putInt("preview_color", v).apply() }

    var visionEnabled: Boolean
        get() = sp.getBoolean("vision_enabled", false)
        set(v) { sp.edit().putBoolean("vision_enabled", v).apply() }

    var visionUrl: String
        get() = sp.getString(
            "vision_url",
            "https://open.bigmodel.cn/api/paas/v4/chat/completions"
        )!!
        set(v) { sp.edit().putString("vision_url", v).apply() }

    var visionKey: String
        get() = sp.getString("vision_key", "")!!
        set(v) { sp.edit().putString("vision_key", v).apply() }

    var visionModel: String
        get() = sp.getString("vision_model", "glm-4v-flash")!!
        set(v) { sp.edit().putString("vision_model", v).apply() }

    /** 是否显示悬浮输入框 */
    var floatingInput: Boolean
        get() = sp.getBoolean("floating_input", false)
        set(v) { sp.edit().putBoolean("floating_input", v).apply() }

    /** 收纳小圆距状态栏的高度（dp，0~80，默认 0） */
    var dotY: Int
        get() = sp.getInt("dot_y", 0)
        set(v) { sp.edit().putInt("dot_y", v.coerceIn(0, 80)).apply() }

    /** 对话提示词：附加在每次聊天的系统提示里，留空表示不加 */
    var chatPrompt: String
        get() = sp.getString("chat_prompt", "")!!
        set(v) {
            sp.edit().putString("chat_prompt", v).apply()
        }

    /** 主题：0 跟随系统，1 浅色，2 深色 */
    var themeMode: Int
        get() = sp.getInt("theme_mode", 0)
        set(v) { sp.edit().putInt("theme_mode", v).apply() }

    /** 是否记录 Episode（V1.3 数据闭环用） */
    var recordEpisodes: Boolean
        get() = sp.getBoolean("record_episodes", true)
        set(v) { sp.edit().putBoolean("record_episodes", v).apply() }

    // ---------------- 彩蛋 ----------------

    /** 无限制模式：持久化，重启保持，不会自己关掉 */
    var unlimited: Boolean
        get() = sp.getBoolean("unlimited", false)
        set(v) { sp.edit().putBoolean("unlimited", v).apply() }

    /** 口令解锁只在本次进程有效，重启要重新输 */
    @Volatile
    var unlockedInProcess: Boolean = false
        private set

    fun unlockInProcess() {
        unlockedInProcess = true
    }

    /** 口令明文不进代码，存 Base64 */
    private const val PASS_B64 = "SGkyMDI2"

    fun isEasterCode(input: String): Boolean {
        val t = input.trim()
        if (t.isEmpty()) return false
        val code = runCatching {
            String(
                android.util.Base64.decode(PASS_B64, android.util.Base64.DEFAULT),
                Charsets.UTF_8
            )
        }.getOrDefault("")
        return code.isNotEmpty() && t == code
    }
}
