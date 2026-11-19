package com.whale.hand.agent

import org.json.JSONObject

/**
 * 思考强度：5 档通用方案。
 * 通用底座 = 提示词后缀 + max_tokens 上限（任何 OpenAI 兼容模型都生效）；
 * 原生参数 = 只有模型档案声明了 thinkingMode 才叠加，失败也不影响可用性。
 */
object Effort {

    const val LIGHT = 0
    const val STANDARD = 1
    const val DEEP = 2
    const val PRO = 3
    const val ULTRA = 4

    val labels = listOf("轻度", "标准", "深度", "pro", "ultra")

    private val maxTokensList = listOf(512, 1024, 2048, 4096, 8192)

    private val promptSuffixes = listOf(
        "【思考强度：轻度】直接给出结论，不要展示推理过程，回答尽量不超过三句话。",
        "",
        "【思考强度：深度】先分析再回答，给出判断依据。",
        "【思考强度：pro】多步验证，关键结论自我复核一遍再给出。",
        "【思考强度：ultra】穷尽思路，给出方案对比、风险和边界条件；信息不足时先反问。"
    )

    fun coerce(level: Int): Int = level.coerceIn(LIGHT, ULTRA)

    fun label(level: Int): String = labels[coerce(level)]

    fun maxTokens(level: Int): Int = maxTokensList[coerce(level)]

    fun promptSuffix(level: Int): String = promptSuffixes[coerce(level)]

    /**
     * 原生参数叠加（可选）：
     * - deepseek：thinking: {type: disabled / adaptive / enabled}
     * - effort：reasoning_effort: minimal / low / medium / high / high
     * - none：不附加
     */
    fun nativeBody(thinkingMode: String, level: Int): JSONObject? {
        if (level !in LIGHT..ULTRA) return null
        return when (thinkingMode) {
            ModelProfile.THINK_DEEPSEEK -> JSONObject().put(
                "thinking",
                JSONObject().put(
                    "type",
                    when (level) {
                        LIGHT -> "disabled"
                        STANDARD -> "adaptive"
                        else -> "enabled"
                    }
                )
            )
            ModelProfile.THINK_EFFORT -> JSONObject().put(
                "reasoning_effort",
                listOf("minimal", "low", "medium", "high", "high")[level]
            )
            else -> null
        }
    }
}
