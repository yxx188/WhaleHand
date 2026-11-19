package com.whale.hand

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.whale.hand.agent.AgentConfig
import com.whale.hand.agent.AgentLoop
import com.whale.hand.agent.AppRules
import com.whale.hand.agent.ConfigStore
import com.whale.hand.agent.MemoryStore
import com.whale.hand.agent.RootCheck
import com.whale.hand.agent.TaskLogStore
import com.whale.hand.chat.ChatMsg
import com.whale.hand.chat.ChatStore
import com.whale.hand.databinding.ActivityMainBinding
import com.whale.hand.service.AgentAccessibilityService
import com.whale.hand.service.FloatingService
import com.whale.hand.service.OverlayManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var instance: MainActivity? = null
    }

    private lateinit var binding: ActivityMainBinding
    private var loop: AgentLoop? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var memoryEdit: EditText? = null

    /** 从聊天页进来的会话 id，任务结束后往这个会话回填一条结果 */
    private var chatSession: String? = null

    private val notifPerm =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        OverlayManager.onSubmit = { goal -> startGoalFromOverlay(goal) }

        ConfigStore.refreshFlag(this)
        binding.tvVersion.text = "让 AI 替你用手点手机 · v${BuildConfig.VERSION_NAME}"

        binding.btnBack.setOnClickListener { v ->
            haptic(v)
            finish()
        }
        binding.btnStart.setOnClickListener { v -> haptic(v); startAgent() }
        binding.btnStop.setOnClickListener { v ->
            haptic(v)
            loop?.stop()
            appendLog("已请求停止")
        }
        binding.btnAcc.setOnClickListener { v ->
            haptic(v)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnOverlay.setOnClickListener { v ->
            haptic(v)
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        binding.btnSettings.setOnClickListener { v ->
            haptic(v)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnClearLog.setOnClickListener { v -> haptic(v); binding.tvLog.text = "" }
        binding.btnCopyLog.setOnClickListener { v -> copyLog(v as TextView) }
        binding.btnHistory.setOnClickListener { v -> haptic(v); showHistoryDialog() }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        applyLaunchIntent(intent)
    }

    /** 从聊天页带目标进来：填进目标框并直接开跑 */
    private fun applyLaunchIntent(intent: Intent?) {
        val goal = intent?.getStringExtra(ChatActivity.EXTRA_GOAL).orEmpty().trim()
        if (goal.isEmpty()) return
        chatSession = intent?.getStringExtra(ChatActivity.EXTRA_SESSION)
        val editOnly = intent?.getBooleanExtra("extra_edit_only", false) ?: false
        binding.etGoal.post {
            if (isFinishing || isDestroyed) return@post
            binding.etGoal.setText(goal)
            if (editOnly) return@post
            if (loop != null) {
                toast("已经有任务在跑，目标已填入")
                return@post
            }
            if (AgentAccessibilityService.instance == null) {
                toast("先去开启 WhaleHand 的无障碍服务")
                return@post
            }
            if (AgentConfig.apiKey.isBlank()) {
                toast("先填写并保存 API Key")
                return@post
            }
            startAgentInternal()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        OverlayManager.setBarEnabled(AgentConfig.floatingInput)
        OverlayManager.setDanger(AgentConfig.unlimited)
    }


    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
    }

    private fun haptic(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun refreshStatus() {
        val acc = AgentAccessibilityService.instance != null
        tintDot(binding.vAccDot, acc)
        binding.tvAccStatus.text =
            if (acc) "无障碍服务：已开启" else "无障碍服务：未开启（点下方按钮开启）"

        val ov = Settings.canDrawOverlays(this)
        tintDot(binding.vOverlayDot, ov)
        binding.tvOverlayStatus.text =
            if (ov) "悬浮窗权限：已授权" else "悬浮窗权限：未授权（点下方按钮授权）"

        binding.btnStart.isEnabled = loop == null
        binding.btnStop.isEnabled = loop != null
        // 新主/次按钮是 drawable 背景，没有内置禁用着色，补一层透明度表示不可点
        binding.btnStart.alpha = if (loop == null) 1f else 0.38f
        binding.btnStop.alpha = if (loop == null) 0.38f else 1f
    }

    private fun tintDot(v: View, on: Boolean) {
        val color = ContextCompat.getColor(this, if (on) R.color.success else R.color.danger)
        v.backgroundTintList = ColorStateList.valueOf(color)
    }

    // ---------------- 启动任务 ----------------

    private fun startAgent() {
        if (isInMultiWindowMode) {
            AlertDialog.Builder(this)
                .setTitle("检测到小窗 / 分屏")
                .setMessage(
                    "在小窗或分屏模式下，无障碍服务可能读不到目标应用的界面。\n\n" +
                    "建议先退出小窗，全屏打开 WhaleHand 后再开始。"
                )
                .setPositiveButton("仍然开始") { _, _ -> startAgentInternal() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        startAgentInternal()
    }

    private fun startAgentInternal() {
        val goal = binding.etGoal.text?.toString()?.trim().orEmpty()

        if (AgentConfig.isEasterCode(goal)) {
            binding.etGoal.setText("")
            if (!RootCheck.isRooted()) {
                toast("口令无效")
                return
            }
            AgentConfig.unlockInProcess()
            toast("已解锁，去设置里打开「无限制模式」开关")
            return
        }

        if (goal.isEmpty()) {
            toast("先写下你要它做什么")
            return
        }

        val svc = AgentAccessibilityService.instance
        if (svc == null) {
            toast("先去开启 WhaleHand 的无障碍服务")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("请先授权悬浮窗")
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (AgentConfig.apiKey.isBlank()) {
            toast("先填写并保存 API Key")
            return
        }

        val l = AgentLoop(svc, goal)
        val logId = TaskLogStore.begin(this, goal)
        l.onLog = { msg ->
            runOnUiThread {
                appendLog(msg)
                TaskLogStore.line(this, msg)
            }
            FloatingService.update(msg)
        }
        l.onFinishDetail = { exitReason, _message, steps ->
            TaskLogStore.line(this, "■ 结束：$exitReason（$steps 步）")
            TaskLogStore.end(this, exitReason, steps)
            val sid = chatSession
            if (!sid.isNullOrBlank()) {
                val ok = exitReason == "done"
                if (ok) {
                    ChatStore.append(this, sid, ChatMsg("task", "任务完成（" + steps + " 步）"))
                } else {
                    val tail = runCatching { TaskLogStore.read(this, logId) }
                        .getOrDefault("")
                        .lines()
                        .filter { it.isNotBlank() }
                        .takeLast(6)
                        .joinToString("\n")
                    val tailBlock = if (tail.isBlank()) "" else "\n最近几步：\n" + tail
                    val body = "任务没做成：" + exitReason + "（" + steps + " 步）" + tailBlock
                    ChatStore.append(this, sid, ChatMsg("task", body, task = goal))
                }
            }
        }
        l.onFinish = { reason ->
            runOnUiThread {
                appendLog("■ $reason")
                loop = null
                FloatingService.stop(this)
                refreshStatus()
            }
        }
        loop = l
        appendLog("▶ 目标：$goal")
        TaskLogStore.line(this, "▶ 目标：$goal")
        FloatingService.onStopClick = { l.stop() }
        FloatingService.start(this)
        l.start()
        refreshStatus()
    }

    /** 悬浮输入框发来的目标 */
    fun startGoalFromOverlay(goal: String) {
        if (isFinishing || isDestroyed) return

        runOnUiThread {
            binding.etGoal.setText(goal)

            if (loop != null) {
                toast("任务正在运行，目标已填入")
                return@runOnUiThread
            }
            if (AgentAccessibilityService.instance == null) {
                toast("无障碍服务未开启")
                return@runOnUiThread
            }
            if (AgentConfig.apiKey.isBlank()) {
                toast("先配置 API Key")
                return@runOnUiThread
            }
            startAgentInternal()
        }
    }

    private fun appendLog(line: String) {
        binding.tvLog.append("${timeFmt.format(Date())}  $line\n")
    }

    // ---------------- 复制 ----------------

    private fun copyLog(v: TextView) {
        val text = binding.tvLog.text.toString()
        if (text.isBlank()) {
            v.text = "日志是空的"
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            v.postDelayed({ v.text = "复制" }, 1500)
            return
        }

        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("WhaleHand 日志", text))

        v.text = "已复制 ✓"
        v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        v.postDelayed({ v.text = "复制" }, 1500)
    }

    // ---------------- 外观 ----------------

    // ---------------- 历史任务 ----------------

    private fun showHistoryDialog() {
        val list = TaskLogStore.list(this)
        if (list.isEmpty()) {
            toast("还没有任务日志，跑一个任务再来")
            return
        }
        val labels = list.map { TaskLogStore.label(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("历史任务（最近 ${list.size} 份）")
            .setItems(labels) { _, which ->
                showLogDialog(list[which])
            }
            .setNeutralButton("清空全部") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("确认清空")
                    .setMessage("会删掉现有的 ${list.size} 份任务日志，删了找不回来。")
                    .setPositiveButton("删") { _, _ ->
                        list.forEach { TaskLogStore.delete(this, it.id) }
                        toast("已清空")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showLogDialog(log: TaskLogStore.TaskLog) {
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val body = TaskLogStore.read(this, log.id).ifBlank { "（这份日志是空的）" }

        val tv = TextView(this).apply {
            text = body
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply {
            setPadding(pad, pad, pad, 0)
            addView(tv)
        }

        AlertDialog.Builder(this)
            .setTitle(log.goal.ifBlank { "任务日志" })
            .setView(scroll)
            .setPositiveButton("✓ 满意") { _, _ ->
                TaskLogStore.setVerdict(this, log.id, TaskLogStore.VERDICT_OK)
                toast("已记为满意")
            }
            .setNeutralButton("✗ 不满意") { _, _ ->
                TaskLogStore.setVerdict(this, log.id, TaskLogStore.VERDICT_BAD)
                toast("已记为不满意")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
