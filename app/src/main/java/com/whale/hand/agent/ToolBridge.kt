package com.whale.hand.agent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Termux 回传结果的接收器（AndroidManifest 里注册，exported=false） */
class ToolResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ToolBridge.onResult(intent.extras)
    }
}

/**
 * V1.6 工具通道：把一条 shell 命令交给 Termux 执行，拿回 stdout/stderr/exit code。
 *
 * 前置（两件都得手动做，缺一件这里就会明确报出来）：
 * 1. Termux 里 ~/.termux/termux.properties 加 allow-external-apps=true
 * 2. 系统设置里给 WhaleHand 授予「RUN_COMMAND」权限（Termux 定义的运行时权限）
 */
object ToolBridge {

    private const val TERMUX_PKG = "com.termux"
    private const val ACTION_RUN = "com.termux.RUN_COMMAND"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BG = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_PENDING = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val RES_STDOUT = "com.termux.RUN_COMMAND_STDOUT"
    private const val RES_STDERR = "com.termux.RUN_COMMAND_STDERR"
    private const val RES_EXIT = "com.termux.RUN_COMMAND_EXIT_CODE"

    private const val MAX_OUTPUT = 1200
    private const val MAX_TIMEOUT = 60

    /** Termux 定义的危险权限，必须在运行时申请 */
    const val PERM_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    /** 安全阀二：命令里出现的绝对路径必须落在这些根下面 */
    private val allowedRoots = listOf(
        "/sdcard",
        "/storage/emulated/0",
        "/storage/self/primary",
        "/data/data/com.termux/files/home",
        "/data/data/com.termux/files/usr",
        "/tmp",
        "/data/local/tmp"
    )

    /** 安全阀一：明确不该让模型碰的命令 */
    private val blocked = listOf(
        "reboot", "shutdown", "mkfs", "dd if=", "rm -rf /",
        "su -c", "su root", "pm uninstall", "pm clear", ":(){", "chmod 777 /"
    )

    @Volatile
    private var latch: CountDownLatch? = null

    @Volatile
    private var resultText: String = ""

    fun isBlocked(cmd: String): String? {
        val c = cmd.lowercase()
        blocked.firstOrNull { c.contains(it) }?.let { return "命令被安全阀拦下（含「" + it + "」）" }
        if (cmd.length > 500) return "命令太长（超过 500 字），拆成几条"
        offendingPath(cmd)?.let { return "命令里出现了白名单以外的路径（" + it + "），拒绝执行" }
        return null
    }

    private fun offendingPath(cmd: String): String? {
        val re = Regex("(/[A-Za-z0-9_\\-./]+)")
        for (m in re.findAll(cmd)) {
            val p = m.value
            if (p == "/" || p.startsWith("//")) continue
            if (!underAllowed(p)) return p
        }
        return null
    }

    private fun underAllowed(p: String): Boolean =
        allowedRoots.any { p == it || p.startsWith(it + "/") }

    fun hasPermission(ctx: Context): Boolean = runCatching {
        ctx.checkSelfPermission(PERM_RUN_COMMAND) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isTermuxInstalled(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(TERMUX_PKG, 0) }.isSuccess

    /**
     * 执行一条命令，阻塞到有结果或超时。
     * 返回给模型看的文本（已截断），不会抛异常。
     */
    fun run(ctx: Context, command: String, timeoutSec: Int = 20): String {
        val cmd = command.trim()
        if (cmd.isEmpty()) return "（空命令）"
        isBlocked(cmd)?.let { return it }
        if (!isTermuxInstalled(ctx)) return "这台设备没装 Termux，shell 动作不可用"
        if (!hasPermission(ctx)) {
            return "还没拿到 Termux 的命令权限（RUN_COMMAND），去设置页点「授予 Termux 命令权限」"
        }

        val wait = timeoutSec.coerceIn(5, MAX_TIMEOUT)
        val l = CountDownLatch(1)
        latch = l
        resultText = ""

        return try {
            val pi = PendingIntent.getBroadcast(
                ctx,
                0,
                Intent(ctx, ToolResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val intent = Intent().apply {
                setClassName(TERMUX_PKG, "com.termux.app.RunCommandService")
                action = ACTION_RUN
                putExtra(EXTRA_PATH, "/data/data/com.termux/files/usr/bin/bash")
                putExtra(EXTRA_ARGS, arrayOf("-c", cmd))
                putExtra(EXTRA_WORKDIR, "/data/data/com.termux/files/home")
                putExtra(EXTRA_BG, true)
                putExtra(EXTRA_LABEL, "WhaleHand")
                putExtra(EXTRA_PENDING, pi)
            }
            ctx.startService(intent)

            if (l.await(wait.toLong(), TimeUnit.SECONDS)) {
                resultText
            } else {
                "（超时 " + wait + " 秒，命令可能还在跑）"
            }
        } catch (e: SecurityException) {
            "Termux 拒绝了这次调用：多半是没开 allow-external-apps，或没给 WhaleHand 授予 RUN_COMMAND 权限"
        } catch (e: Exception) {
            "执行失败：" + (e.message ?: e.javaClass.simpleName)
        } finally {
            latch = null
        }
    }

    /** 由 ToolResultReceiver 回调，凑齐 stdout/stderr/exit code */
    fun onResult(bundle: Bundle?) {
        val l = latch ?: return
        val out = bundle?.getString(RES_STDOUT).orEmpty().trim()
        val err = bundle?.getString(RES_STDERR).orEmpty().trim()
        val code = bundle?.getInt(RES_EXIT, -1) ?: -1

        val sb = StringBuilder()
        if (out.isNotEmpty()) sb.append(out)
        if (err.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("[stderr] ").append(err)
        }
        if (code != 0) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("（exit=").append(code).append("）")
        }
        resultText = clip(sb.toString().ifBlank { "（没有输出，exit=" + code + "）" })
        l.countDown()
    }

    private fun clip(s: String): String =
        if (s.length <= MAX_OUTPUT) s else s.take(MAX_OUTPUT) + "\n…（已截断，原长 " + s.length + " 字）"
}
