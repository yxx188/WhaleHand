package com.whale.hand

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.whale.hand.agent.AgentConfig
import com.whale.hand.agent.AppRules
import com.whale.hand.agent.ConfigStore
import com.whale.hand.agent.MemoryStore
import com.whale.hand.agent.ToolBridge
import com.whale.hand.chat.ChatStore
import com.whale.hand.databinding.ActivityDataSettingsBinding
import com.whale.hand.service.OverlayManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 数据与工具：长期记忆编辑 / 记忆导入导出 / 配置导入导出 / 一键配置 /
 * Termux 命令权限 / 清空聊天记录。
 * 逻辑全部从 2.0 的 SettingsActivity 原样搬过来。
 */
class DataSettingsActivity : AppCompatActivity() {

    companion object {
        private const val REQ_TERMUX = 1001
    }

    private lateinit var binding: ActivityDataSettingsBinding
    private var memoryEdit: EditText? = null

    private val exportJsonLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(MemoryStore.exportJson(this).toByteArray(Charsets.UTF_8))
                    }
                }.onSuccess { toast("已导出 JSON") }
                    .onFailure { toast("导出失败：" + it.message) }
            }
        }

    private val exportMdLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(MemoryStore.exportMarkdown(this).toByteArray(Charsets.UTF_8))
                    }
                }.onSuccess { toast("已导出 Markdown") }
                    .onFailure { toast("导出失败：" + it.message) }
            }
        }

    private val exportEpisodeLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            thread(name = "whale-export") {
                val ok = runCatching {
                    val text = buildEpisodeText()
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("无法打开输出流")
                }.isSuccess
                runOnUiThread { toast(if (ok) "已导出过程记录" else "导出失败") }
            }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val text = runCatching {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                if (text.isNullOrBlank()) toast("读取文件失败") else askImportMode(text)
            }
        }

    private val exportConfigLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(ConfigStore.exportJson(true).toByteArray(Charsets.UTF_8))
                } ?: error("no stream")
            }.isSuccess
            toast(if (ok) "已导出完整配置（含 Key，注意保管）" else "导出失败")
        }

    private val exportConfigMaskedLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(ConfigStore.exportJson(false).toByteArray(Charsets.UTF_8))
                } ?: error("no stream")
            }.isSuccess
            toast(if (ok) "已导出脱敏配置（不含 Key）" else "导出失败")
        }

    private val importConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val text = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                toast("读取文件失败")
                return@registerForActivityResult
            }
            val n = runCatching { ConfigStore.importJson(text) }.getOrDefault(0)
            if (n <= 0) {
                toast("这份文件里没有认识的配置字段")
            } else {
                OverlayManager.setBarEnabled(AgentConfig.floatingInput)
                toast("已导入 " + n + " 项配置")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ConfigStore.refreshFlag(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnMemory.setOnClickListener { v -> haptic(v); showMemoryDialog() }
        binding.btnConfigIO.setOnClickListener { v -> haptic(v); showConfigDialog() }
        binding.btnOneTap.setOnClickListener { v -> haptic(v); confirmOneTap() }
        binding.btnPerm.setOnClickListener { v ->
            haptic(v)
            if (ToolBridge.hasPermission(this)) {
                toast("已经有 Termux 命令权限了")
            } else {
                requestPermissions(arrayOf(ToolBridge.PERM_RUN_COMMAND), REQ_TERMUX)
            }
        }
        binding.btnClearChat.setOnClickListener { v -> haptic(v); confirmClearChat() }
    }

    override fun onResume() {
        super.onResume()
        refreshMemorySummary()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_TERMUX) {
            val ok = grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            toast(if (ok) "已授予，shell 动作可用了" else "没授予，shell 动作会一直失败")
        }
    }

    private fun refreshMemorySummary() {
        binding.tvMemorySummary.text = "当前 " + MemoryStore.count(this) + " 条记忆"
    }

    // ---------------- 配置导入导出 / 一键配置 ----------------

    private fun confirmOneTap() {
        if (!ConfigStore.canOneTap()) {
            toast("已经有 Key 了，一键配置不再可用")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("一键配置")
            .setMessage(
                "只填地址和模型，Key 还是你自己填：\n\n" +
                    "地址：" + ConfigStore.DS_URL + "\n" +
                    "模型：" + ConfigStore.DS_MODEL + "\n\n" +
                    "视觉外挂默认指向 " + ConfigStore.DS_VISION_MODEL + "。"
            )
            .setPositiveButton("填好") { _, _ ->
                val ok = ConfigStore.applyOneTap(this)
                toast(if (ok) "已填好，去模型页补 API Key" else "一键配置不可用")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showConfigDialog() {
        val items = arrayOf(
            "导出完整配置（含 Key）",
            "导出脱敏配置（不含 Key）",
            "导入配置文件"
        )
        AlertDialog.Builder(this)
            .setTitle("配置")
            .setItems(items) { _, which ->
                val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                when (which) {
                    0 -> AlertDialog.Builder(this)
                        .setTitle("注意")
                        .setMessage("完整配置里是明文的 API Key，存哪里、发给谁，自己拿捏。")
                        .setPositiveButton("继续导出") { _, _ ->
                            exportConfigLauncher.launch("whale-config-" + stamp + ".json")
                        }
                        .setNegativeButton("取消", null)
                        .show()

                    1 -> exportConfigMaskedLauncher.launch("whale-config-safe-" + stamp + ".json")

                    else -> importConfigLauncher
                        .launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmClearChat() {
        val n = ChatStore.sessions(this).size
        AlertDialog.Builder(this)
            .setTitle("清空聊天记录")
            .setMessage(
                "会删掉全部 " + n + " 个会话和其中的消息。\n" +
                    "长期记忆和过程记录不受影响。删了找不回来。"
            )
            .setPositiveButton("清空") { _, _ ->
                ChatStore.clearAll(this)
                ChatStore.setCurrentSession(this, "")
                toast("聊天记录已清空")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 长期记忆 ----------------

    private fun showMemoryDialog() {
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val edit = EditText(this).apply {
            setText(MemoryStore.editText(this@DataSettingsActivity))
            hint = "包名 :: 内容；没有 :: 的算通用记忆。等级由系统按印证次数自动判"
            minLines = 8
            gravity = Gravity.TOP
        }
        val scroll = ScrollView(this).apply { addView(edit) }

        val btnJson = Button(this).apply { text = "导出 JSON"; textSize = 12f }
        val btnMd = Button(this).apply { text = "导出 MD"; textSize = 12f }
        val btnImport = Button(this).apply { text = "导入"; textSize = 12f }
        val btnEpisode = Button(this).apply { text = "导出过程记录"; textSize = 12f }
        val btnRules = Button(this).apply { text = "黑名单管理"; textSize = 13f }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(btnJson, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(btnMd, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(btnImport, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(btnEpisode, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(btnRules, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (220 * d).toInt()
            ))
            addView(row1)
            addView(row2)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("长期记忆（" + MemoryStore.count(this) + " 条）")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val n = MemoryStore.saveFromText(this, edit.text.toString())
                toast("已保存 " + n + " 条")
                refreshMemorySummary()
            }
            .setNeutralButton("清空") { _, _ ->
                MemoryStore.clear(this)
                toast("记忆已清空")
                refreshMemorySummary()
            }
            .setNegativeButton("取消", null)
            .show()

        memoryEdit = edit
        dialog.setOnDismissListener { memoryEdit = null }

        btnJson.setOnClickListener { v -> haptic(v); exportJsonLauncher.launch("whale-memory.json") }
        btnMd.setOnClickListener { v -> haptic(v); exportMdLauncher.launch("whale-memory.md") }
        btnImport.setOnClickListener { v ->
            haptic(v)
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        btnEpisode.setOnClickListener { v ->
            haptic(v)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            exportEpisodeLauncher.launch("whale-episodes-" + stamp + ".txt")
        }
        btnRules.setOnClickListener { v ->
            haptic(v)
            dialog.dismiss()
            showRulesDialog()
        }
    }

    private fun askImportMode(text: String) {
        val modes = arrayOf("覆盖（用文件替换现有记忆）", "合并（只加入新条目）", "追加（全部加到末尾）")
        AlertDialog.Builder(this)
            .setTitle("导入方式")
            .setItems(modes) { _, which ->
                val mode = when (which) {
                    0 -> MemoryStore.IMPORT_REPLACE
                    1 -> MemoryStore.IMPORT_MERGE
                    else -> MemoryStore.IMPORT_APPEND
                }
                runCatching {
                    val n = MemoryStore.importJson(this, text, mode)
                    toast("已写入 " + n + " 条")
                    memoryEdit?.setText(MemoryStore.editText(this))
                    refreshMemorySummary()
                }.onFailure { toast("导入失败：" + it.message) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRulesDialog() {
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val edit = EditText(this).apply {
            setText(AppRules.blocked.joinToString("\n"))
            hint = "一行一个包名"
            gravity = Gravity.TOP
            minLines = 6
        }
        val wrap = ScrollView(this).apply {
            setPadding(pad, pad, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle("黑名单（这些应用里不执行操作）")
            .setView(wrap)
            .setPositiveButton("保存") { _, _ ->
                val pkgs = edit.text.toString().lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                AppRules.blocked = pkgs
                toast("已保存 " + pkgs.size + " 个包名")
            }
            .setNeutralButton("恢复默认") { _, _ ->
                AppRules.reset()
                toast("已恢复默认")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 过程记录导出 ----------------

    private fun buildEpisodeText(): String {
        val sb = StringBuilder()
        sb.appendLine("# WhaleHand 过程记录")
        sb.appendLine("# 时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()))
        sb.appendLine()
        val dir = File(filesDir, "episodes")
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) {
            sb.appendLine("（还没有过程记录）")
        } else {
            for (f in files) {
                sb.appendLine("===== " + f.name + " =====")
                sb.appendLine(runCatching { f.readText() }.getOrDefault("（读取失败）"))
                sb.appendLine()
            }
        }
        for (name in arrayOf("cases.json", "skills.json")) {
            val f = File(filesDir, name)
            if (f.exists()) {
                sb.appendLine("===== " + name + " =====")
                sb.appendLine(runCatching { f.readText() }.getOrDefault("（读取失败）"))
            }
        }
        return sb.toString()
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
