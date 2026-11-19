package com.whale.hand.agent

import android.content.Context
import android.provider.Settings
import com.whale.hand.chat.ChatMsg
import com.whale.hand.chat.ChatStore
import com.whale.hand.service.AgentAccessibilityService
import com.whale.hand.service.FloatingService

/**
 * 无页面的任务启动器：发起任务不再需要「任务台」页面。
 * 聊天页弹窗点「开始执行」→ 这里起 AgentLoop，状态走悬浮条，结果写回聊天。
 */
object TaskRunner {

    @Volatile
    private var loop: AgentLoop? = null

    @Volatile
    private var logId: String = ""

    @Volatile
    private var activeGoal: String = ""

    @Volatile
    private var sessionId: String? = null

    fun isRunning(): Boolean = loop != null

    fun runningGoal(): String = activeGoal

    /**
     * 启动任务。返回 null 表示已启动；返回非空字符串是失败原因（调用方负责 toast/跳授权）。
     */
    fun start(ctx: Context, goalText: String, sid: String?): String? {
        val goal = goalText.trim()
        if (goal.isEmpty()) return "先写下要它做什么"
        val svc = AgentAccessibilityService.instance ?: return "先去开启 WhaleHand 的无障碍服务"
        if (!Settings.canDrawOverlays(ctx)) return "请先授权悬浮窗权限"
        if (AgentConfig.apiKey.isBlank()) return "先在设置里填好 API Key"
        if (loop != null) return "已经有任务在跑了"

        val app = ctx.applicationContext
        val l = AgentLoop(svc, goal)
        logId = TaskLogStore.begin(app, goal)
        activeGoal = goal
        sessionId = sid

        l.onLog = { msg ->
            TaskLogStore.line(app, msg)
            FloatingService.update(msg)
        }
        l.onFinishDetail = { exitReason, _message, steps ->
            TaskLogStore.line(app, "■ 结束：$exitReason（$steps 步）")
            TaskLogStore.end(app, exitReason, steps)
            val s = sessionId
            if (!s.isNullOrBlank()) {
                if (exitReason == "done") {
                    ChatStore.append(app, s, ChatMsg("task", "任务完成（$steps 步）"))
                } else {
                    val tail = runCatching { TaskLogStore.read(app, logId) }
                        .getOrDefault("")
                        .lines()
                        .filter { it.isNotBlank() }
                        .takeLast(6)
                        .joinToString("\n")
                    val tailBlock = if (tail.isBlank()) "" else "\n最近几步：\n" + tail
                    ChatStore.append(
                        app, s,
                        ChatMsg("task", "任务没做成：" + exitReason + "（" + steps + " 步）" + tailBlock, task = goal)
                    )
                }
            }
        }
        l.onFinish = { reason ->
            TaskLogStore.line(app, "■ $reason")
            loop = null
            FloatingService.stop(app)
        }

        loop = l
        TaskLogStore.line(app, "▶ 目标：$goal")
        FloatingService.onStopClick = { l.stop() }
        FloatingService.start(app)
        l.start()
        return null
    }

    fun stop() {
        loop?.stop()
    }
}
