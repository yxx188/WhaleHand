package com.whale.hand.agent

import java.io.File

/**
 * 彩蛋的 root 门禁。
 *
 * 只看「有没有 su 可用」，不看具体是谁提的权：
 * Magisk、KernelSU、官方 root 都认。
 * 没有 root 就没有开关，口令等于无效。
 */
object RootCheck {

    private val suPaths = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/system/sd/xbin/su",
        "/data/local/bin/su",
        "/data/local/xbin/su",
        "/system/bin/failsafe/su",
        "/data/adb/magisk",
        "/data/adb/ksu"
    )

    fun isRooted(): Boolean {
        for (p in suPaths) {
            if (runCatching { File(p).exists() }.getOrDefault(false)) return true
        }
        return runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            proc.waitFor()
            out.isNotEmpty()
        }.getOrDefault(false)
    }

    /** 给面板显示一句人话 */
    fun summary(): String = if (isRooted()) "已检测到 root" else "没有检测到 root"
}
