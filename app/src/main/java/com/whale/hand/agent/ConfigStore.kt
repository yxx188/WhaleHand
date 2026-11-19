package com.whale.hand.agent

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置的导出、导入、首次一键配置。
 *
 * 约定：
 * - 一键配置只在 apiKey 还空着的时候可用，永远不覆盖已有 Key
 * - 导出分两种：完整版带 Key（弹警告）、脱敏版不带 Key
 * - 导入只认认识字段，未知字段忽略，不会把 App 搞崩
 */
object ConfigStore {

    const val DS_URL = "https://api.deepseek.com/v1/chat/completions"
    const val DS_MODEL = "deepseek-chat"
    const val DS_VISION_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    const val DS_VISION_MODEL = "glm-4v-flash"

    private const val PREF_DONE_ONETAP = "onetap_done"

    @Volatile
    private var oneTapDoneFlag: Boolean = false

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("whale_config_extra", Context.MODE_PRIVATE)

    /** 启动时读一次，后面 canOneTap 不用再碰磁盘 */
    fun refreshFlag(ctx: Context) {
        oneTapDoneFlag = prefs(ctx).getBoolean(PREF_DONE_ONETAP, false)
    }

    fun canOneTap(): Boolean = AgentConfig.apiKey.isBlank() && !oneTapDoneFlag

    /**
     * 只填地址和模型，不动 Key。
     * @return false 表示已经有 Key 或已经用过，什么都没改
     */
    fun applyOneTap(ctx: Context): Boolean {
        if (!canOneTap()) return false
        AgentConfig.apiUrl = DS_URL
        AgentConfig.model = DS_MODEL
        AgentConfig.visionUrl = DS_VISION_URL
        AgentConfig.visionModel = DS_VISION_MODEL
        prefs(ctx).edit().putBoolean(PREF_DONE_ONETAP, true).apply()
        oneTapDoneFlag = true
        return true
    }

    fun exportJson(includeKeys: Boolean): String {
        val root = JSONObject()
        root.put("app", "WhaleHand")
        root.put("kind", "config")
        root.put("version", 1)
        root.put("device", "${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        root.put(
            "exportedAt",
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        )
        root.put("keysIncluded", includeKeys)

        root.put("apiUrl", AgentConfig.apiUrl)
        root.put("apiKey", if (includeKeys) AgentConfig.apiKey else "")
        root.put("model", AgentConfig.model)

        root.put("visionUrl", AgentConfig.visionUrl)
        root.put("visionKey", if (includeKeys) AgentConfig.visionKey else "")
        root.put("visionModel", AgentConfig.visionModel)
        root.put("visionEnabled", AgentConfig.visionEnabled)

        root.put("previewEnabled", AgentConfig.previewEnabled)
        root.put("previewStyle", AgentConfig.previewStyle)
        root.put("previewColor", AgentConfig.previewColor)

        root.put("floatingInput", AgentConfig.floatingInput)
        root.put("recordEpisodes", AgentConfig.recordEpisodes)

        val rules = JSONArray()
        for (p in AppRules.blocked) rules.put(p)
        root.put("blocked", rules)

        return root.toString(2)
    }

    /**
     * @return 实际写入的字段数，0 表示这份文件一个认识的字段都没有
     */
    fun importJson(raw: String): Int {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return 0
        var n = 0

        if (o.has("apiUrl")) {
            val v = o.optString("apiUrl").trim()
            if (v.isNotEmpty()) {
                AgentConfig.apiUrl = v
                n++
            }
        }
        if (o.has("apiKey")) {
            val v = o.optString("apiKey").trim()
            if (v.isNotEmpty()) {
                AgentConfig.apiKey = v
                n++
            }
        }
        if (o.has("model")) {
            val v = o.optString("model").trim()
            if (v.isNotEmpty()) {
                AgentConfig.model = v
                n++
            }
        }
        if (o.has("visionUrl")) {
            val v = o.optString("visionUrl").trim()
            if (v.isNotEmpty()) {
                AgentConfig.visionUrl = v
                n++
            }
        }
        if (o.has("visionKey")) {
            val v = o.optString("visionKey").trim()
            if (v.isNotEmpty()) {
                AgentConfig.visionKey = v
                n++
            }
        }
        if (o.has("visionModel")) {
            val v = o.optString("visionModel").trim()
            if (v.isNotEmpty()) {
                AgentConfig.visionModel = v
                n++
            }
        }
        if (o.has("visionEnabled")) {
            AgentConfig.visionEnabled = o.optBoolean("visionEnabled", AgentConfig.visionEnabled)
            n++
        }
        if (o.has("previewEnabled")) {
            AgentConfig.previewEnabled = o.optBoolean("previewEnabled", AgentConfig.previewEnabled)
            n++
        }
        if (o.has("previewStyle")) {
            AgentConfig.previewStyle = o.optInt("previewStyle", AgentConfig.previewStyle)
            n++
        }
        if (o.has("previewColor")) {
            AgentConfig.previewColor = o.optInt("previewColor", AgentConfig.previewColor)
            n++
        }
        if (o.has("floatingInput")) {
            AgentConfig.floatingInput = o.optBoolean("floatingInput", AgentConfig.floatingInput)
            n++
        }

        if (o.has("recordEpisodes")) {
            AgentConfig.recordEpisodes = o.optBoolean("recordEpisodes", AgentConfig.recordEpisodes)
            n++
        }
        val arr = o.optJSONArray("blocked")
        if (arr != null) {
            val set = HashSet<String>()
            for (i in 0 until arr.length()) {
                val p = arr.optString(i).trim()
                if (p.isNotEmpty()) set.add(p)
            }
            AppRules.blocked = set
            n++
        }
        return n
    }
}
