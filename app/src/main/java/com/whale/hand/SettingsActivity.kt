package com.whale.hand

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.whale.hand.agent.AgentConfig
import com.whale.hand.agent.MemoryStore
import com.whale.hand.agent.ModelStore
import com.whale.hand.databinding.ActivitySettingsBinding

/**
 * 设置主页（V2.1）：只做入口和摘要，具体配置各自有一页。
 * 分组：模型与服务 / 行为与外观 / 数据与工具 / 关于。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.rowModels.setOnClickListener { v ->
            haptic(v)
            startActivity(Intent(this, ModelListActivity::class.java))
        }
        binding.rowVision.setOnClickListener { v ->
            haptic(v)
            startActivity(Intent(this, VisionSettingsActivity::class.java))
        }
        binding.rowBehavior.setOnClickListener { v ->
            haptic(v)
            startActivity(Intent(this, BehaviorSettingsActivity::class.java))
        }
        binding.rowAppearance.setOnClickListener { v ->
            haptic(v)
            startActivity(Intent(this, AppearanceSettingsActivity::class.java))
        }
        binding.rowData.setOnClickListener { v ->
            haptic(v)
            startActivity(Intent(this, DataSettingsActivity::class.java))
        }
        binding.rowAbout.setOnClickListener { v ->
            haptic(v)
            showAbout()
        }

        binding.tvVersion.text = "WhaleHand v" + BuildConfig.VERSION_NAME + " · 深海极光"
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
    }

    private fun refreshSummaries() {
        val profiles = ModelStore.profiles()
        val cur = ModelStore.current()
        binding.tvModelsSummary.text = if (cur == null) {
            "还没有模型，点进去添加"
        } else {
            cur.name.ifBlank { "未命名模型" } + " · " + cur.model +
                "（共 " + profiles.size + " 个）"
        }

        binding.tvVisionSummary.text = if (AgentConfig.visionEnabled) {
            "已开启 · " + AgentConfig.visionModel
        } else {
            "已关闭 · " + AgentConfig.visionModel
        }

        binding.tvBehaviorSummary.text = "预览光标" + onOff(AgentConfig.previewEnabled) +
            " · 悬浮输入" + onOff(AgentConfig.floatingInput) +
            " · 过程记录" + onOff(AgentConfig.recordEpisodes)

        binding.tvAppearanceSummary.text =
            THEME_NAMES[AgentConfig.themeMode.coerceIn(0, 2)] +
                " · 预览" + STYLE_NAMES[AgentConfig.previewStyle.coerceIn(0, 3)]

        binding.tvDataSummary.text = "记忆 " + MemoryStore.count(this) + " 条 · 配置 / 权限 / 清理"

        binding.tvAboutSummary.text = "v" + BuildConfig.VERSION_NAME + " · 让 AI 替你用手点手机"
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("WhaleHand v" + BuildConfig.VERSION_NAME)
            .setMessage(
                "让 AI 替你用手点手机。\n\n" +
                    "聊天可以闲聊，也可以让它动手；动手前会先跟你确认目标。\n" +
                    "涉及支付 / 密码 / 验证码的操作，它不会自己做。\n\n" +
                    "界面：深海极光 · V2.0"
            )
            .setPositiveButton("知道啦", null)
            .show()
    }

    private fun onOff(b: Boolean): String = if (b) "开" else "关"

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }


    companion object {
        private val THEME_NAMES = arrayOf("跟随系统", "浅色", "深色")
        private val STYLE_NAMES = arrayOf("圆圈 + 十字", "只有十字", "高亮方框", "不显示预览")
    }
}
