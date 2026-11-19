package com.whale.hand.service

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import com.whale.hand.R
import com.whale.hand.agent.AgentConfig

/**
 * 悬浮条：顶部居中固定，四态显示。
 * 空闲 → 「等待您的请求」，点一下变成输入框并弹出键盘。
 * 输入 → 输入框 + 发送按钮。
 * 运行 → 显示当前步骤，右侧停止。
 * 等待 → 显示 AI 的提示，右侧变「继续」。
 */
object OverlayManager {

    private const val MODE_IDLE = 0
    private const val MODE_INPUT = 1
    private const val MODE_RUNNING = 2
    private const val MODE_WAITING = 3

    @Volatile
    var onStopClick: (() -> Unit)? = null

    @Volatile
    var onResumeClick: (() -> Unit)? = null

    /** 用户从悬浮条提交目标 */
    @Volatile
    var onSubmit: ((String) -> Unit)? = null

    private var service: AccessibilityService? = null
    private var wm: WindowManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var barView: View? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var markerView: ClickMarkerView? = null

    @Volatile private var mode = MODE_IDLE
    @Volatile private var wantVisible = false
    @Volatile private var statusVisible = false
    @Volatile private var barRect: Rect? = null

    private var lastText = "等待您的请求"

    /** 彩蛋：无限制模式下悬浮条描边变红，一局一换 */
    @Volatile private var dangerMode = false

    // ---------------- 生命周期 ----------------

    fun attach(svc: AccessibilityService) {
        service = svc
        wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    fun detach() {
        mainHandler.post {
            removeBar(false)
            markerView?.let { runCatching { wm?.removeView(it) } }
            markerView = null
            wantVisible = false
            service = null
            wm = null
        }
    }

    // ---------------- 开关 / 任务状态 ----------------

    fun setBarEnabled(enabled: Boolean) {
        mainHandler.post {
            wantVisible = enabled
            if (enabled) {
                if (barView == null) addBar()
                applyMode()
            } else {
                if (mode == MODE_INPUT) restoreNotFocusable()
                mode = MODE_IDLE
                removeBar(true)
            }
        }
    }

    /** 彩蛋：无限制模式时把悬浮条换成红描边；关掉再换回来 */
    fun setDanger(on: Boolean) {
        dangerMode = on
        mainHandler.post {
            barView?.setBackgroundResource(
                if (on) R.drawable.bg_overlay_danger else R.drawable.bg_overlay
            )
        }
    }

    fun showStatus() {
        mainHandler.post {
            if (mode == MODE_INPUT) restoreNotFocusable()
            wantVisible = true
            mode = MODE_RUNNING
            lastText = "AI 正在操作…"
            removeDot()
            if (barView == null) addBar()
            applyMode()
        }
    }

    fun hideStatus() {
        mainHandler.post {
            if (mode == MODE_INPUT) restoreNotFocusable()
            mode = MODE_IDLE
            lastText = "等待您的请求"
            if (AgentConfig.floatingInput) {
                wantVisible = true
                if (barView == null) addBar()
                applyMode()
            } else {
                wantVisible = false
                removeBar(true)
            }
        }
    }

    fun update(text: String) {
        mainHandler.post {
            if (mode != MODE_RUNNING) return@post
            lastText = text
            barView?.findViewById<TextView>(R.id.tvOverlayText)?.text = text
        }
    }

    // ---------------- 输入态 ----------------

    /**
     * 进入输入态：把悬浮窗切成可聚焦、弹键盘。
     * 注意不能换成 Activity —— 那会把用户当前用的 App 切走。
     */
    fun enterInputMode() {
        mainHandler.post {
            val ctx = service ?: return@post
            val v = barView ?: return@post
            val w = wm ?: return@post
            val p = barParams ?: return@post
            if (mode != MODE_IDLE) return@post

            cancelAutoCollapse()
            imeSeen = false
            mode = MODE_INPUT
            wantVisible = true

            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            runCatching { w.updateViewLayout(v, p) }

            applyMode()

            val et = v.findViewById<EditText>(R.id.etOverlayInput)
            et?.postDelayed({
                if (mode != MODE_INPUT) return@postDelayed
                et.requestFocus()
                et.setSelection(et.text.length)
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? InputMethodManager
                imm?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
            }, 80L)
        }
    }

    /** 退出输入态，恢复不可聚焦 */
    fun exitInputMode() {
        if (mode != MODE_INPUT) return
        val v = barView
        val et = v?.findViewById<EditText>(R.id.etOverlayInput)
        val imm = service?.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager

        imm?.hideSoftInputFromWindow(et?.windowToken, 0)
        et?.clearFocus()
        et?.setText("")
        v?.clearFocus()
        v?.isFocusableInTouchMode = false

        restoreNotFocusable()
        mode = MODE_IDLE
        lastText = "等待您的请求"
        mainHandler.post { applyMode() }
    }
    private fun restoreNotFocusable() {
        val v = barView ?: return
        val w = wm ?: return
        val p = barParams ?: return
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
        runCatching { w.updateViewLayout(v, p) }
    }
    private fun submitInput() {
        val v = barView ?: return
        val et = v.findViewById<EditText>(R.id.etOverlayInput) ?: return
        val text = et.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) {
            exitInputMode()
            return
        }
        exitInputMode()
        onSubmit?.invoke(text)
    }


    // ---------------- 截图时临时隐藏 ----------------

    fun setStatusVisible(visible: Boolean) {        mainHandler.post {
            if (visible) {
                if (wantVisible) {
                    if (barView == null) addBar()
                    applyMode()
                }
            } else {
                removeBar(false)
            }
        }
    }

    fun isStatusVisible(): Boolean = statusVisible

    // ---------------- AI 动作保护 ----------------

    fun beginAgentAction(x: Int, y: Int) {
        mainHandler.post {
            val r = barRect
            if (r != null && x >= 0 && y >= 0 && r.contains(x, y)) removeBar(false)
        }
    }

    fun endAgentAction() {
        mainHandler.post {
            if (wantVisible) {
                if (barView == null) addBar()
                applyMode()
            }
        }
    }

    // ---------------- 等待用户 ----------------

    fun showWaiting(text: String) {
        mainHandler.post {
            if (mode == MODE_INPUT) restoreNotFocusable()
            wantVisible = true
            mode = MODE_WAITING
            lastText = text.ifBlank { "需要你操作" }.take(24)
            if (barView == null) addBar()
            applyMode()
        }
    }

    fun clearWaiting() {
        mainHandler.post {
            if (mode != MODE_WAITING) return@post
            mode = MODE_RUNNING
            lastText = "AI 正在操作…"
            applyMode()
        }
    }

    // ---------------- 悬浮条本身 ----------------

    private fun addBar() {
        if (barView != null) return
        val ctx = service ?: return
        val w = wm ?: return
        removeDot()

        val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_status, null)
        if (dangerMode) v.setBackgroundResource(R.drawable.bg_overlay_danger)
        val tv = v.findViewById<TextView>(R.id.tvOverlayText)
        val et = v.findViewById<EditText>(R.id.etOverlayInput)
        val btn = v.findViewById<TextView>(R.id.btnOverlayStop)

        tv?.text = lastText

        v.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus && mode == MODE_INPUT) {
                mainHandler.postDelayed({ if (mode == MODE_INPUT) exitInputMode() }, 200L)
            }
        }

        v.setOnApplyWindowInsetsListener { _, insets ->
            val imeVisible = insets.isVisible(WindowInsets.Type.ime())
            if (imeVisible) {
                imeSeen = true
            } else if (mode == MODE_INPUT && imeSeen) {
                mainHandler.postDelayed({ if (mode == MODE_INPUT) exitInputMode() }, 250L)
            }
            insets
        }

        v.setOnLongClickListener {
            collapseToDot()
            true
        }

        tv?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (mode == MODE_IDLE) enterInputMode()
        }

        btn?.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            when (mode) {
                MODE_INPUT -> submitInput()
                MODE_WAITING -> {
                    lastText = "继续中…"
                    tv?.text = lastText
                    onResumeClick?.invoke()
                }
                else -> {
                    lastText = "正在停止…"
                    tv?.text = lastText
                    onStopClick?.invoke()
                }
            }
        }

        et?.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                submitInput()
                true
            } else {
                false
            }
        }

        et?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && mode == MODE_INPUT) {
                mainHandler.postDelayed({
                    if (mode == MODE_INPUT) exitInputMode()
                }, 250L)
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = statusBarHeight(ctx) + dp(8)
        }
        barParams = params

        v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            barRect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
        }

        v.alpha = 0f
        v.scaleX = 0.92f
        v.scaleY = 0.92f

        if (runCatching { w.addView(v, params) }.isFailure) return
        barView = v
        applyMode()

        v.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    // ---------------- 空闲自动收纳 ----------------

    @Volatile
    private var autoCollapse: Runnable? = null

    /** 键盘是否真的出现过（用来避免刚进输入态就被误判成「键盘已收起」） */
    @Volatile
    private var imeSeen = false

    /** 空闲 8 秒后自动收成小圆 */
    private fun scheduleAutoCollapse() {
        cancelAutoCollapse()
        if (mode != MODE_IDLE || !AgentConfig.floatingInput || barView == null) return
        val r = Runnable { if (mode == MODE_IDLE) collapseToDot() }
        autoCollapse = r
        mainHandler.postDelayed(r, 8000L)
    }

    private fun cancelAutoCollapse() {
        autoCollapse?.let { mainHandler.removeCallbacks(it) }
        autoCollapse = null
    }

    // ---------------- 收纳成小圆（P2-⑤） ----------------

    @Volatile
    private var dotView: View? = null
    private var dotParams: WindowManager.LayoutParams? = null

    /** 条 → 小圆：可上下拖动改高度，点一下展开 */
    fun collapseToDot() {
        mainHandler.post {
            val ctx = service ?: return@post
            val w = wm ?: return@post
            cancelAutoCollapse()
            // 必须立即移除：带动画会有 140ms 的「条和小圆同时存在」
            removeBar(false)
            if (dotView != null) return@post

            val v = LayoutInflater.from(ctx).inflate(R.layout.overlay_dot, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                // 顶部居中：默认压在摄像头挖孔上；dotY 从屏幕顶算起，0 就是最上面
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = dp(AgentConfig.dotY)
                // 关键：不再被状态栏 inset 往下推，也允许画进挖孔区域
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            dotParams = params

            var downRawY = 0f
            var startY = 0
            var moved = false
            v.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawY = e.rawY
                        startY = params.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - downRawY
                        if (kotlin.math.abs(dy) > dp(6)) moved = true
                        params.y = (startY + dy.toInt()).coerceIn(0, dp(80))
                        runCatching { w.updateViewLayout(v, params) }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            AgentConfig.dotY = (params.y / dp(1).coerceAtLeast(1)).coerceIn(0, 80)
                        } else {
                            expandFromDot()
                        }
                        true
                    }
                    else -> false
                }
            }

            if (runCatching { w.addView(v, params) }.isFailure) return@post
            dotView = v
        }
    }

    /** 设置页滑杆改动后，把已经在屏上的小圆挪过去 */
    fun refreshDotPosition() {
        mainHandler.post {
            val w = wm ?: return@post
            val v = dotView ?: return@post
            val p = dotParams ?: return@post
            p.y = dp(AgentConfig.dotY)
            runCatching { w.updateViewLayout(v, p) }
        }
    }

    /** 小圆 → 条 */
    fun expandFromDot() {
        mainHandler.post {
            removeDot()
            wantVisible = true
            if (barView == null) addBar()
            applyMode()
        }
    }

    private fun removeDot() {
        val v = dotView ?: return
        dotView = null
        dotParams = null
        runCatching { wm?.removeView(v) }
    }

    private fun removeBar(animate: Boolean) {
        val v = barView ?: return
        barView = null
        barParams = null
        barRect = null
        statusVisible = false

        if (animate) {
            v.animate()
                .alpha(0f)
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(140L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { runCatching { wm?.removeView(v) } }
                .start()
        } else {
            runCatching { wm?.removeView(v) }
        }
    }

    private fun applyMode() {
        val v = barView ?: return
        val tv = v.findViewById<TextView>(R.id.tvOverlayText)
        val et = v.findViewById<EditText>(R.id.etOverlayInput)
        val btn = v.findViewById<TextView>(R.id.btnOverlayStop)

        tv?.visibility = if (mode == MODE_INPUT) View.GONE else View.VISIBLE
        et?.visibility = if (mode == MODE_INPUT) View.VISIBLE else View.GONE

        if (mode != MODE_INPUT) tv?.text = lastText

        btn?.visibility = if (mode == MODE_IDLE) View.GONE else View.VISIBLE
        if (mode == MODE_IDLE) scheduleAutoCollapse() else cancelAutoCollapse()

        btn?.text = when (mode) {
            MODE_INPUT -> "发送"
            MODE_WAITING -> "继续"
            else -> "停止"
        }
        statusVisible = true
    }

    private fun statusBarHeight(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) {
            runCatching { ctx.resources.getDimensionPixelSize(id) }.getOrDefault(dp(28))
        } else {
            dp(28)
        }
    }

    private fun dp(v: Int): Int {
        val d = service?.resources?.displayMetrics?.density ?: 3f
        return (v * d).toInt()
    }

    // ---------------- 动作预览 ----------------

    private fun ensureMarker(): ClickMarkerView? {
        markerView?.let { return it }
        val ctx = service ?: return null
        val w = wm ?: return null

        val v = ClickMarkerView(ctx)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        if (runCatching { w.addView(v, params) }.isFailure) return null
        markerView = v
        return v
    }

    fun showTap(x: Int, y: Int, label: String? = null) {
        if (AgentConfig.previewStyle == 3) return
        mainHandler.post {
            ensureMarker()?.setTap(x.toFloat(), y.toFloat(), label)
        }
    }

    fun showSwipe(x1: Int, y1: Int, x2: Int, y2: Int, label: String? = null) {
        if (AgentConfig.previewStyle == 3) return
        mainHandler.post {
            ensureMarker()?.setSwipe(
                x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), label
            )
        }
    }

    fun hideMarker() {
        mainHandler.post {
            val v = markerView ?: return@post
            runCatching { wm?.removeView(v) }
            markerView = null
        }
    }
}

/**
 * 全屏半透明预览层。
 * 样式：0 圆圈+十字，1 只有十字，2 高亮方框，3 不显示。
 */
class ClickMarkerView(context: Context) : View(context) {

    private var tapPoint: PointF? = null
    private var label: String? = null
    private var swipeFrom: PointF? = null
    private var swipeTo: PointF? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        setShadowLayer(8f, 0f, 2f, Color.parseColor("#B3000000"))
    }

    private var pulse = 0f
    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setTap(x: Float, y: Float, text: String?) {
        tapPoint = PointF(x, y)
        label = text
        swipeFrom = null
        swipeTo = null
        startPulse()
        invalidate()
    }

    fun setSwipe(x1: Float, y1: Float, x2: Float, y2: Float, text: String?) {
        swipeFrom = PointF(x1, y1)
        swipeTo = PointF(x2, y2)
        label = text
        tapPoint = null
        startPulse()
        invalidate()
    }

    private fun startPulse() {
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 600
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    pulse = it.animatedValue as Float
                    invalidate()
                }
            }
        }
        animator?.takeIf { !it.isRunning }?.start()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val style = AgentConfig.previewStyle
        if (style == 3) return

        val base = AgentConfig.previewColor
        fillPaint.color = Color.argb(0x4D, Color.red(base), Color.green(base), Color.blue(base))
        strokePaint.color = Color.argb(0xCC, Color.red(base), Color.green(base), Color.blue(base))

        tapPoint?.let { p ->
            when (style) {
                0 -> {
                    val r = 46f + 12f * pulse
                    canvas.drawCircle(p.x, p.y, r, fillPaint)
                    canvas.drawCircle(p.x, p.y, r, strokePaint)
                    drawCross(canvas, p.x, p.y, 16f)
                    label?.let { canvas.drawText(it, p.x, p.y - r - 18f, textPaint) }
                }

                1 -> {
                    drawCross(canvas, p.x, p.y, 30f)
                    label?.let { canvas.drawText(it, p.x, p.y - 46f, textPaint) }
                }

                else -> {
                    val half = 40f + 6f * pulse
                    val rect = RectF(p.x - half, p.y - half, p.x + half, p.y + half)
                    canvas.drawRoundRect(rect, 10f, 10f, fillPaint)
                    canvas.drawRoundRect(rect, 10f, 10f, strokePaint)
                    label?.let { canvas.drawText(it, p.x, p.y - half - 18f, textPaint) }
                }
            }
        }

        val f = swipeFrom
        val t = swipeTo
        if (f != null && t != null) {
            strokePaint.strokeWidth = 10f
            canvas.drawLine(f.x, f.y, t.x, t.y, strokePaint)
            strokePaint.strokeWidth = 4f
            canvas.drawCircle(f.x, f.y, 24f, fillPaint)
            canvas.drawCircle(t.x, t.y, 24f, fillPaint)
            label?.let {
                canvas.drawText(it, (f.x + t.x) / 2f, minOf(f.y, t.y) - 30f, textPaint)
            }
        }
    }

    private fun drawCross(canvas: Canvas, x: Float, y: Float, half: Float) {
        strokePaint.strokeWidth = 3f
        canvas.drawLine(x - half, y, x + half, y, strokePaint)
        canvas.drawLine(x, y - half, x, y + half, strokePaint)
        strokePaint.strokeWidth = 4f
    }
}
