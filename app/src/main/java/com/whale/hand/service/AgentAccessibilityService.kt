package com.whale.hand.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null

        const val MAX_NODES = 900
        const val MAX_DEPTH = 40
    }

    val nodeMap = HashMap<Int, AccessibilityNodeInfo>()

    @Volatile var lastPackage: String = ""
    /** 最近一次 dumpTree 没拿到活跃窗口，lastPackage 是上一次的旧值 */
    @Volatile var lastPackageStale: Boolean = false
    @Volatile var lastTextNodeCount: Int = 0
    @Volatile var lastDumpOk: Boolean = false
    @Volatile var lastScreenshotError: String = ""

    // ---------------- 生命周期 ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        OverlayManager.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        OverlayManager.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        OverlayManager.detach()
        super.onDestroy()
    }

    /** 当前能访问到的窗口数量 */
    fun windowCount(): Int = runCatching { windows.size }.getOrDefault(0)

    /** 心跳：连续查 3 次，避免切换瞬间误判 */
    fun isAlive(): Boolean {
        repeat(3) {
            if (windowCount() > 0) return true
            Thread.sleep(200)
        }
        return false
    }

    // ---------------- 截屏 ----------------

    fun screenshotJpeg(quality: Int = 70): ByteArray? {
        var out: ByteArray? = null
        var err = ""
        val latch = CountDownLatch(1)

        val wasVisible = OverlayManager.isStatusVisible()
        if (wasVisible) {
            OverlayManager.setStatusVisible(false)
            Thread.sleep(120)
        }

        takeScreenshot(
            Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        r.hardwareBuffer.close()
                        if (soft != null) {
                            val bos = ByteArrayOutputStream()
                            soft.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                            out = bos.toByteArray()
                        } else {
                            err = "wrapHardwareBuffer 返回空"
                        }
                    } catch (e: Exception) {
                        err = e.message ?: "压缩失败"
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    err = "takeScreenshot 失败 code=$errorCode"
                    latch.countDown()
                }
            })

        latch.await(5, TimeUnit.SECONDS)

        if (wasVisible) OverlayManager.setStatusVisible(true)

        lastScreenshotError = if (out == null) err.ifBlank { "截图超时" } else ""
        return out
    }

    // ---------------- 控件树 ----------------

    fun dumpTree(): String {
        nodeMap.clear()
        lastTextNodeCount = 0

        val root = rootInActiveWindow
        if (root == null) {
            lastDumpOk = false
            lastPackageStale = true
            val guessed = guessPackageFromWindows()
            if (guessed.isNotBlank()) lastPackage = guessed
            return ""
        }

        lastDumpOk = true
        lastPackage = root.packageName?.toString().orEmpty()
        lastPackageStale = false

        val sb = StringBuilder()
        sb.append("package: ").append(lastPackage).append('\n')

        var id = 0
        var visited = 0

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null) return
            if (visited >= MAX_NODES || depth > MAX_DEPTH) return
            visited++

            val text = n.text?.toString()?.take(60)
            val desc = n.contentDescription?.toString()?.take(60)
            val interesting = n.isClickable || n.isEditable ||
                    !text.isNullOrBlank() || !desc.isNullOrBlank()

            if (interesting) {
                val rect = Rect().also { n.getBoundsInScreen(it) }
                if (rect.width() > 0 && rect.height() > 0) {
                    val label = ((text ?: "") + " " + (desc ?: "")).trim()
                        .replace("\"", "'")
                    if (label.isNotBlank()) lastTextNodeCount++

                    sb.append('#').append(id)
                        .append(
                            if (n.isEditable) " [INPUT]"
                            else if (n.isClickable) " [TAP]"
                            else " [TEXT]"
                        )
                        .append(" text=\"").append(label).append('"')
                        .append(" center=(").append(rect.centerX()).append(',')
                        .append(rect.centerY()).append(')')
                        .append(" size=").append(rect.width()).append('x').append(rect.height())
                        .append('\n')

                    nodeMap[id] = n
                    id++
                }
            }

            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }

        walk(root, 0)

        if (visited >= MAX_NODES) sb.append("(节点数达到上限，已截断)\n")
        return sb.toString()
    }

    /**
     * 拿不到活跃窗口时的兜底：从 windows 列表里猜前台包名。
     * 猜出来的值不可靠，调用方仍需把 lastPackageStale 置 true。
     */
    private fun guessPackageFromWindows(): String {
        val wins = runCatching { windows }.getOrNull() ?: return ""
        var fallback = ""
        for (w in wins) {
            val type = w.type
            if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                type == AccessibilityWindowInfo.TYPE_SYSTEM
            ) continue

            val node = runCatching { w.root }.getOrNull() ?: continue
            val pkg = node.packageName?.toString().orEmpty()
            if (pkg.isBlank()) continue

            if (w.isActive) return pkg
            if (fallback.isBlank()) fallback = pkg
        }
        return fallback
    }

    /** 等界面稳定：连续两次 dump 一致才返回 */
    fun dumpStable(maxWaitMs: Long = 1500L): String {
        var prev = dumpTree()
        val deadline = System.currentTimeMillis() + maxWaitMs

        while (System.currentTimeMillis() < deadline) {
            if (!lastDumpOk) {
                Thread.sleep(150)
                prev = dumpTree()
                continue
            }
            Thread.sleep(220)
            val cur = dumpTree()
            if (cur == prev) return cur
            prev = cur
        }
        return prev
    }

    // ---------------- 动作 ----------------

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(g, null, null)
    }

    /** 单段滑动：按距离自动算时长 */
    private fun swipeSimple(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val dist = kotlin.math.hypot(
            (x2 - x1).toDouble(),
            (y2 - y1).toDouble()
        ).toFloat()
        val dur = (dist / 1.8f).toLong().coerceIn(180L, 520L)

        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
            .build()
        return dispatchGesture(g, null, null)
    }

    /** 快速横向手势：翻页用 */
    private fun swipeGesture(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 180))
            .build()
        return dispatchGesture(g, null, null)
    }

    /**
     * 纵向单段滑动，距离封顶 900px；
     * 横向走快速手势。
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 300): Boolean {
        val dx = kotlin.math.abs(x2 - x1)
        val dy = kotlin.math.abs(y2 - y1)

        if (dy >= dx) {
            val maxDy = 900f
            var ex = x2
            var ey = y2
            if (dy > maxDy) {
                val k = maxDy / dy
                ex = x1 + (x2 - x1) * k
                ey = y1 + (y2 - y1) * k
            }
            return swipeSimple(x1, y1, ex, ey)
        }

        return swipeGesture(x1, y1, x2, y2)
    }

    /**
     * 无障碍滚动：直接让可滚动容器滚，不依赖手势。
     * 手势滑不动时的兜底。
     */
    fun scrollContainer(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findScrollable(root) ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return target.performAction(action)
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val r = findScrollable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    /** 优先点节点本身；父节点超过半屏视为广告容器，不点它 */
    fun tapNode(id: Int): Boolean {
        val node = nodeMap[id] ?: return false
        val r = Rect().also { node.getBoundsInScreen(it) }
        val dm = resources.displayMetrics
        val screenArea = dm.widthPixels.toLong() * dm.heightPixels

        var p: AccessibilityNodeInfo? = node
        var depth = 0
        while (p != null && depth < 3) {
            if (p.isClickable) {
                val pr = Rect().also { p.getBoundsInScreen(it) }
                val area = pr.width().toLong() * pr.height()
                if (area <= screenArea / 2) {
                    if (p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
                break
            }
            p = p.parent
            depth++
        }
        return tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun nodeCenter(id: Int): Point? {
        val node = nodeMap[id] ?: return null
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return Point(rect.centerX(), rect.centerY())
    }

    /** 读节点当前文字，用于输入结果校验 */
    fun nodeText(id: Int): String? = nodeMap[id]?.text?.toString()

    fun setText(id: Int, content: String): Boolean {
        val node = nodeMap[id] ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}
