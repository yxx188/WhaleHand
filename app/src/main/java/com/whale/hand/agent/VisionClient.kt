package com.whale.hand.agent

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

object VisionClient {

    /**
     * @param context    上一步动作与结果，给识别器一点背景
     * @param lookingFor 本次关心的目标（完成判定），让它重点看有没有出现
     */
    fun describe(jpeg: ByteArray, context: String = "", lookingFor: String = ""): String {
        val key = AgentConfig.visionKey
        require(key.isNotBlank()) { "视觉外挂的 Key 没填" }
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)

        val focus = if (lookingFor.isBlank()) "" else
            "本次重点：" + lookingFor + "\n如果屏幕上已出现它的证据，把原文逐字抄下来；没出现就明确写「没有看到」。\n"

        val prompt = """
你是手机屏幕图像识别器。看这张截图，用中文简洁输出：
1. 当前是哪个 App 的什么页面（桌面 / 文件夹 / 应用内 / 加载画面）
2. 可点击的按钮/图标/输入框分别是什么，在屏幕的大致方位（上/中/下、左/中/右）
3. 页面上有没有"加载中""正在启动""更新"之类的提示
${focus}${if (context.isBlank()) "" else "补充背景：" + context}
只描述你看到的内容，不要给操作建议。200 字以内。
""".trimIndent()

        val content = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", prompt))
            put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            )
        }

        val body = JSONObject().apply {
            put("model", AgentConfig.visionModel)
            put("temperature", 0.1)
            put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", content)
            ))
        }.toString()

        val raw = LlmClient.postJson(AgentConfig.visionUrl, key, body)
        return JSONObject(raw)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
            .trim()
    }
}