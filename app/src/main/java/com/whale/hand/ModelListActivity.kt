package com.whale.hand

import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.whale.hand.agent.ModelProfile
import com.whale.hand.agent.ModelStore
import com.whale.hand.databinding.ActivityModelListBinding
import com.whale.hand.databinding.ItemModelCardBinding

/**
 * 模型档案列表：第一张是「添加模型」，下面是已添加的档案。
 * 点卡片看详情 / 编辑；长按直接设为当前模型。
 */
class ModelListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.cardAddModel.setOnClickListener { v ->
            haptic(v)
            startActivity(
                Intent(this, ModelDetailActivity::class.java)
                    .putExtra(ModelDetailActivity.EXTRA_MODE, ModelDetailActivity.MODE_NEW)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        binding.llModels.removeAllViews()
        val list = ModelStore.profiles()
        val currentId = ModelStore.currentId()
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE

        for (p in list) {
            val item = ItemModelCardBinding.inflate(layoutInflater, binding.llModels, false)
            item.tvModelName.text = p.name.ifBlank { "未命名模型" }
            item.tvModelId.text = p.model.ifBlank { "（没填模型 ID）" }
            item.tvKindChip.text =
                if (p.kind == ModelProfile.KIND_VISION) "视觉模型" else "文本模型"
            val think = thinkLabel(p.thinkingMode)
            item.tvThinkChip.text = think
            item.tvThinkChip.visibility =
                if (p.thinkingMode == ModelProfile.THINK_NONE) View.GONE else View.VISIBLE
            val isCurrent = p.id == currentId && p.kind != ModelProfile.KIND_VISION
            item.tvCurrentBadge.visibility = if (isCurrent) View.VISIBLE else View.GONE

            item.root.setOnClickListener { v ->
                haptic(v)
                startActivity(
                    Intent(this, ModelDetailActivity::class.java)
                        .putExtra(ModelDetailActivity.EXTRA_ID, p.id)
                )
            }
            item.root.setOnLongClickListener { v ->
                haptic(v)
                if (p.kind == ModelProfile.KIND_VISION) {
                    toast("视觉外挂模型不参与主对话")
                } else if (p.id == ModelStore.currentId()) {
                    toast("已经是当前模型了")
                } else {
                    ModelStore.setCurrent(p.id)
                    toast("已设为当前模型：" + p.name.ifBlank { p.model })
                    render()
                }
                true
            }
            binding.llModels.addView(item.root)
        }
    }

    private fun thinkLabel(mode: String): String = when (mode) {
        ModelProfile.THINK_DEEPSEEK -> "DeepSeek 思考"
        ModelProfile.THINK_EFFORT -> "reasoning_effort"
        else -> "无原生思考"
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
