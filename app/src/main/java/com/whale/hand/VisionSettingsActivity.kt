package com.whale.hand

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.whale.hand.agent.AgentConfig
import com.whale.hand.databinding.ActivityVisionSettingsBinding

/** 视觉外挂：开关 + 独立的一套地址 / Key / 模型名（仍写 AgentConfig.vision*） */
class VisionSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVisionSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVisionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { v -> haptic(v); save() }

        // 开关和原设置页一样即时生效（不点保存也记住）
        binding.swVision.setOnCheckedChangeListener { _, c -> AgentConfig.visionEnabled = c }

        load()
    }

    private fun load() {
        binding.etVisionUrl.setText(AgentConfig.visionUrl)
        binding.etVisionKey.setText(AgentConfig.visionKey)
        binding.etVisionModel.setText(AgentConfig.visionModel)
        binding.swVision.isChecked = AgentConfig.visionEnabled
    }

    /** 与原 SettingsActivity.saveConfig 的视觉部分一致 */
    private fun save() {
        AgentConfig.visionUrl = binding.etVisionUrl.text?.toString()?.trim().orEmpty()
            .ifBlank { "https://open.bigmodel.cn/api/paas/v4/chat/completions" }
        AgentConfig.visionKey = binding.etVisionKey.text?.toString()?.trim().orEmpty()
        AgentConfig.visionModel = binding.etVisionModel.text?.toString()?.trim().orEmpty()
            .ifBlank { "glm-4v-flash" }
        AgentConfig.visionEnabled = binding.swVision.isChecked
        toast("视觉配置已保存")
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
