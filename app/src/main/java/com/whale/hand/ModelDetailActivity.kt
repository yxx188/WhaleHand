package com.whale.hand

import android.app.AlertDialog
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.whale.hand.agent.ConfigStore
import com.whale.hand.agent.Effort
import com.whale.hand.agent.ModelProfile
import com.whale.hand.agent.ModelStore
import com.whale.hand.databinding.ActivityModelDetailBinding

/**
 * 模型详情：一个页面三种状态。
 * - 新建：Intent 带 EXTRA_MODE = MODE_NEW
 * - 查看 / 编辑：Intent 带 EXTRA_ID；默认查看态，右上角「编辑」切编辑态
 */
class ModelDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_ID = "extra_id"
        const val MODE_NEW = "new"

        private const val ST_NEW = 0
        private const val ST_VIEW = 1
        private const val ST_EDIT = 2
    }

    private lateinit var binding: ActivityModelDetailBinding
    private var state = ST_VIEW
    private var profileId = ""
    private var kind = ModelProfile.KIND_TEXT
    private var thinkingMode = ModelProfile.THINK_NONE
    private var defaultEffort = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnEdit.setOnClickListener { v -> haptic(v); enterEdit() }
        binding.btnSave.setOnClickListener { v -> haptic(v); save() }
        binding.btnCancel.setOnClickListener { v -> haptic(v); cancel() }
        binding.btnSetCurrent.setOnClickListener { v -> haptic(v); setCurrent() }
        binding.btnDelete.setOnClickListener { v -> haptic(v); confirmDelete() }

        binding.chipKindText.setOnClickListener { if (editable()) { kind = ModelProfile.KIND_TEXT; refreshChips() } }
        binding.chipKindVision.setOnClickListener { if (editable()) { kind = ModelProfile.KIND_VISION; refreshChips() } }
        binding.chipThinkNone.setOnClickListener { if (editable()) { thinkingMode = ModelProfile.THINK_NONE; refreshChips() } }
        binding.chipThinkDeepseek.setOnClickListener { if (editable()) { thinkingMode = ModelProfile.THINK_DEEPSEEK; refreshChips() } }
        binding.chipThinkEffort.setOnClickListener { if (editable()) { thinkingMode = ModelProfile.THINK_EFFORT; refreshChips() } }

        binding.sbEffort.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvEffortValue.text = Effort.label(progress)
                if (fromUser) defaultEffort = progress
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val mode = intent.getStringExtra(EXTRA_MODE)
        profileId = intent.getStringExtra(EXTRA_ID).orEmpty()

        if (mode == MODE_NEW || profileId.isBlank()) {
            state = ST_NEW
            profileId = ModelStore.newId()
            kind = ModelProfile.KIND_TEXT
            thinkingMode = ModelProfile.THINK_NONE
            defaultEffort = 1
            binding.btnEdit.visibility = View.INVISIBLE
        } else {
            val p = ModelStore.get(profileId)
            if (p == null) {
                toast("这个模型档案不存在了")
                finish()
                return
            }
            state = ST_VIEW
            kind = p.kind
            thinkingMode = p.thinkingMode
            defaultEffort = p.defaultEffort.coerceIn(0, 4)
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (state == ST_VIEW) refresh()
    }

    // ---------------- 渲染 ----------------

    private fun editable(): Boolean = state != ST_VIEW

    private fun refresh() {
        val p = ModelStore.get(profileId)
        binding.tvTitle.text = when (state) {
            ST_NEW -> "新建模型"
            ST_EDIT -> "编辑模型"
            else -> p?.name?.ifBlank { "模型详情" } ?: "模型详情"
        }
        binding.btnEdit.visibility = if (state == ST_VIEW) View.VISIBLE else View.INVISIBLE

        val name = p?.name.orEmpty()
        val model = p?.model.orEmpty()
        val apiUrl = p?.apiUrl.orEmpty()
        val apiKey = p?.apiKey.orEmpty()

        if (state == ST_VIEW) {
            binding.etName.setText(name.ifBlank { "未命名模型" })
            binding.etModel.setText(model)
            binding.etApiUrl.setText(apiUrl)
            binding.etApiKey.setText(maskKey(apiKey))
        } else {
            binding.etName.setText(name)
            binding.etModel.setText(model)
            binding.etApiUrl.setText(apiUrl.ifBlank { ConfigStore.DS_URL })
            binding.etApiKey.setText(apiKey)
        }

        binding.tilName.isEnabled = editable()
        binding.tilModel.isEnabled = editable()
        binding.tilApiUrl.isEnabled = editable()
        binding.tilApiKey.isEnabled = editable()
        binding.etName.isEnabled = editable()
        binding.etModel.isEnabled = editable()
        binding.etApiUrl.isEnabled = editable()
        binding.etApiKey.isEnabled = editable()
        binding.sbEffort.isEnabled = editable()

        binding.sbEffort.progress = defaultEffort.coerceIn(0, 4)
        binding.tvEffortValue.text = Effort.label(defaultEffort)
        refreshChips()

        if (state == ST_VIEW && p != null) {
            binding.cardState.visibility = View.VISIBLE
            val isCurrent = ModelStore.currentId() == p.id
            binding.tvCurrentBadge.text = if (isCurrent) "当前使用" else "未使用"
            binding.tvStateHint.text = when {
                p.kind == ModelProfile.KIND_VISION -> "视觉外挂模型，不参与主对话"
                isCurrent -> "聊天正在用这个模型"
                else -> "聊天正在用别的模型"
            }
        } else {
            binding.cardState.visibility = View.GONE
        }

        binding.btnSave.visibility = if (editable()) View.VISIBLE else View.GONE
        binding.btnCancel.visibility = if (editable()) View.VISIBLE else View.GONE
        binding.btnSetCurrent.visibility = if (state == ST_VIEW) View.VISIBLE else View.GONE
        binding.btnDelete.visibility = if (state == ST_VIEW) View.VISIBLE else View.GONE
        binding.btnCancel.text = if (state == ST_NEW) "取消" else "取消编辑"
        binding.btnSetCurrent.isEnabled = p != null && p.kind != ModelProfile.KIND_VISION
        binding.btnDelete.isEnabled = p != null
    }

    private fun refreshChips() {
        styleChip(binding.chipKindText, kind == ModelProfile.KIND_TEXT)
        styleChip(binding.chipKindVision, kind == ModelProfile.KIND_VISION)
        styleChip(binding.chipThinkNone, thinkingMode == ModelProfile.THINK_NONE)
        styleChip(binding.chipThinkDeepseek, thinkingMode == ModelProfile.THINK_DEEPSEEK)
        styleChip(binding.chipThinkEffort, thinkingMode == ModelProfile.THINK_EFFORT)
    }

    private fun styleChip(tv: TextView, selected: Boolean) {
        tv.setBackgroundResource(if (selected) R.drawable.bg_session_active else R.drawable.bg_chip)
        tv.setTextColor(color(if (selected) R.color.accent else R.color.text_secondary))
        tv.isSelected = selected
        tv.alpha = if (editable() || selected) 1f else 0.55f
    }

    // ---------------- 交互 ----------------

    private fun enterEdit() {
        if (state != ST_VIEW) return
        state = ST_EDIT
        refresh()
        binding.etName.requestFocus()
    }

    private fun cancel() {
        if (state == ST_NEW) {
            finish()
            return
        }
        state = ST_VIEW
        ModelStore.get(profileId)?.let {
            kind = it.kind
            thinkingMode = it.thinkingMode
            defaultEffort = it.defaultEffort.coerceIn(0, 4)
        }
        refresh()
    }

    private fun save() {
        val name = binding.etName.text?.toString()?.trim().orEmpty().ifBlank { "未命名模型" }
        val model = binding.etModel.text?.toString()?.trim().orEmpty()
        if (model.isBlank()) {
            toast("模型 ID 不能空着")
            return
        }
        val url = binding.etApiUrl.text?.toString()?.trim().orEmpty()
            .ifBlank { ConfigStore.DS_URL }
        val key = binding.etApiKey.text?.toString()?.trim().orEmpty()
        val old = ModelStore.get(profileId)
        val p = ModelProfile(
            id = profileId,
            name = name,
            apiUrl = url,
            apiKey = key,
            model = model,
            kind = kind,
            thinkingMode = thinkingMode,
            defaultEffort = defaultEffort,
            lastEffort = old?.lastEffort ?: -1
        )
        ModelStore.upsert(p)
        toast("已保存：" + name)
        finish()
    }

    private fun setCurrent() {
        val p = ModelStore.get(profileId) ?: return
        if (p.kind == ModelProfile.KIND_VISION) {
            toast("视觉外挂模型不能设为当前对话模型")
            return
        }
        ModelStore.setCurrent(p.id)
        toast("已设为当前模型：" + p.name.ifBlank { p.model })
        refresh()
    }

    private fun confirmDelete() {
        val p = ModelStore.get(profileId) ?: return
        AlertDialog.Builder(this)
            .setTitle("删除模型")
            .setMessage(
                "会删掉「" + p.name.ifBlank { p.model } +
                    "」这个档案，删了要重新填地址和 Key。"
            )
            .setPositiveButton("删除") { _, _ ->
                if (ModelStore.delete(profileId)) {
                    toast("已删除")
                    finish()
                } else {
                    toast("至少要保留一个文本模型，这个删不了")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun maskKey(key: String): String {
        if (key.isBlank()) return "（未填写）"
        if (key.length <= 8) return "****"
        return key.take(4) + "****" + key.takeLast(4)
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
