package com.whale.hand

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.whale.hand.agent.AgentConfig
import com.whale.hand.databinding.ActivityAppearanceSettingsBinding

/**
 * 外观与主题：三张主题卡（跟随系统 / 浅色 / 深色）+ 原「预览样式与颜色」。
 * 主题立即生效（AppCompat 会重建页面）；预览样式与颜色在下次点击前生效。
 */
class AppearanceSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppearanceSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppearanceSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.cardThemeSystem.setOnClickListener { v -> haptic(v); pickTheme(0) }
        binding.cardThemeLight.setOnClickListener { v -> haptic(v); pickTheme(1) }
        binding.cardThemeDark.setOnClickListener { v -> haptic(v); pickTheme(2) }
        binding.rowPreview.setOnClickListener { v -> haptic(v); showAppearanceDialog() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val mode = AgentConfig.themeMode.coerceIn(0, 2)
        styleThemeCard(binding.cardThemeSystem, mode == 0)
        styleThemeCard(binding.cardThemeLight, mode == 1)
        styleThemeCard(binding.cardThemeDark, mode == 2)
        binding.tvPreviewSummary.text =
            STYLE_NAMES[AgentConfig.previewStyle.coerceIn(0, 3)] + " · " +
                colorName(AgentConfig.previewColor)
    }

    private fun styleThemeCard(card: MaterialCardView, selected: Boolean) {
        card.setStrokeColor(color(if (selected) R.color.accent else R.color.stroke))
        card.strokeWidth = dp(if (selected) 2 else 1)
        card.setCardBackgroundColor(
            ColorStateList.valueOf(color(if (selected) R.color.accent_soft else R.color.surface_high))
        )
    }

    private fun pickTheme(mode: Int) {
        if (AgentConfig.themeMode == mode) {
            toast("已经是「" + THEME_NAMES[mode] + "」")
            return
        }
        AgentConfig.themeMode = mode
        WhaleApp.applyTheme(mode)
        toast("已切换为「" + THEME_NAMES[mode] + "」")
    }

    private fun showAppearanceDialog() {
        val styles = arrayOf("圆圈 + 十字", "只有十字", "高亮方框", "不显示预览")
        AlertDialog.Builder(this)
            .setTitle("预览样式")
            .setItems(styles) { _, which ->
                AgentConfig.previewStyle = which
                showColorDialog()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showColorDialog() {
        val names = arrayOf("海洋蓝", "薄荷绿", "琥珀橙")
        val values = intArrayOf(
            0xFF38BDF8.toInt(),
            0xFF34D399.toInt(),
            0xFFFBBF24.toInt()
        )
        AlertDialog.Builder(this)
            .setTitle("预览颜色")
            .setItems(names) { _, which ->
                AgentConfig.previewColor = values[which]
                toast("已保存，下次点击生效")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun colorName(argb: Int): String = when (argb) {
        0xFF38BDF8.toInt() -> "海洋蓝"
        0xFF34D399.toInt() -> "薄荷绿"
        0xFFFBBF24.toInt() -> "琥珀橙"
        else -> "自定义"
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private val THEME_NAMES = arrayOf("跟随系统", "浅色", "深色")
        private val STYLE_NAMES = arrayOf("圆圈 + 十字", "只有十字", "高亮方框", "不显示预览")
    }
}
