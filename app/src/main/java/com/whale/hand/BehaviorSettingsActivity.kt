package com.whale.hand

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.whale.hand.agent.AgentConfig
import com.whale.hand.databinding.ActivityBehaviorSettingsBinding
import com.whale.hand.service.OverlayManager

/**
 * 行为设置：动作前预览光标、悬浮输入、收纳小圆高度、记录 Episode。
 * 另外把原设置页的「无限制模式」彩蛋开关收在这一页（解锁后才可见）。
 */
class BehaviorSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBehaviorSettingsBinding
    private var syncingSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBehaviorSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { v ->
            haptic(v)
            applyOverlay()
            toast("行为设置已保存")
        }

        binding.swPreview.setOnCheckedChangeListener { _, c ->
            AgentConfig.previewEnabled = c
        }
        binding.swRecordEpisode.setOnCheckedChangeListener { _, c ->
            AgentConfig.recordEpisodes = c
        }
        binding.swInput.setOnCheckedChangeListener { _, c ->
            AgentConfig.floatingInput = c
            OverlayManager.setBarEnabled(c)
        }
        binding.sbDotY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvDotYValue.text = "悬浮小圆高度：" + progress + "dp"
                if (fromUser) {
                    AgentConfig.dotY = progress
                    OverlayManager.refreshDotPosition()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.swUnlimited.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitch) return@setOnCheckedChangeListener
            if (!AgentConfig.unlockedInProcess) {
                syncingSwitch = true
                binding.swUnlimited.isChecked = !checked
                syncingSwitch = false
                toast("要重新输一次口令才能改")
                return@setOnCheckedChangeListener
            }
            AgentConfig.unlimited = checked
            OverlayManager.setDanger(checked)
            toast(if (checked) "无限制模式：开" else "无限制模式：关")
        }

        load()
    }

    override fun onResume() {
        super.onResume()
        syncUnlimitedUi()
    }

    private fun load() {
        binding.swPreview.isChecked = AgentConfig.previewEnabled
        binding.swInput.isChecked = AgentConfig.floatingInput
        binding.swRecordEpisode.isChecked = AgentConfig.recordEpisodes
        binding.sbDotY.progress = AgentConfig.dotY.coerceIn(0, 80)
        binding.tvDotYValue.text = "悬浮小圆高度：" + AgentConfig.dotY.coerceIn(0, 80) + "dp"
    }

    /** 把当前配置推给悬浮窗（原设置页保存时的行为） */
    private fun applyOverlay() {
        OverlayManager.setBarEnabled(AgentConfig.floatingInput)
        OverlayManager.setDanger(AgentConfig.unlimited)
        OverlayManager.refreshDotPosition()
    }

    private fun syncUnlimitedUi() {
        val visible = AgentConfig.unlimited || AgentConfig.unlockedInProcess
        binding.cardDanger.visibility = if (visible) View.VISIBLE else View.GONE
        syncingSwitch = true
        binding.swUnlimited.isChecked = AgentConfig.unlimited
        syncingSwitch = false
        OverlayManager.setDanger(AgentConfig.unlimited)
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
