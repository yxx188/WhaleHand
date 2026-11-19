package com.whale.hand.agent

import android.graphics.Point
import android.graphics.Rect
import com.whale.hand.service.AgentAccessibilityService
import com.whale.hand.service.FloatingService
import com.whale.hand.service.OverlayManager
import kotlin.concurrent.thread

class AgentLoop(
    private val svc: AgentAccessibilityService,
    private val goal: String
) {
    @Volatile
    private var running = false

    var onLog: ((String) -> Unit)? = null
    var onFinish: ((String) -> Unit)? = null

    /** V1.3.5 起用于完成/失败/中断三态反馈 */
    var onFinishDetail: ((exitReason: String, message: String, steps: Int) -> Unit)? = null

    private val dismissTexts = listOf(
        "暂不开启", "以后再说", "跳过", "稍后再说", "下次再说", "我知道了"
    )

    private val failedNodeClicks = HashSet<String>()

    /** 上一条 shell 动作的输出，拼进 lastResult 回给模型 */
    private var lastShellOutput = ""

    @Volatile
    private var waitingForUser = false
    private var lastAskUser = ""

    /**
     * 验证类关键词。
     * 只认明确的验证动作，不认「指纹、面部与密码」这种系统设置项的名字，
     * 否则一进设置主页面就会被误判成验证页。
     */
    private val verifyWords = listOf(
        "请验证指纹", "指纹验证", "请按指纹", "请触摸指纹", "使用指纹解锁",
        "人脸识别验证", "请正对屏幕", "验证身份", "输入密码", "图案解锁", "锁屏密码"
    )

    /** 支付类关键词 */
    private val payWords = listOf(
        "确认支付", "立即支付", "支付密码", "免密支付",
        "转账", "付款码", "立即付款", "验证码"
    )

    /** 用户点「继续」后调用 */
    fun resumeFromUser() {
        waitingForUser = false
    }

    fun start() {
        if (running) return
        running = true
        OverlayManager.onResumeClick = { resumeFromUser() }

        thread(name = "whale-agent") {
            val ctx = svc.applicationContext
            val unlimited = AgentConfig.unlimited
            val hardSteps = if (unlimited) HARD_UNLIMITED else HARD_MAX_STEPS
            val hardActions = if (unlimited) HARD_UNLIMITED else HARD_MAX_ACTIONS
            var doneWhen = "目标达成（由你自己判断）"
            var memory = ""
            var plan = ""
            var lastResult = "（第一步，还没有上一步动作）"
            var lastActionDesc = "无"
            var lastSig = ""
            var sameCount = 0
            var stuck = 0
            var blindStreak = 0
            var reason = ""
            var exitReason = ""
            var step = 0
            var stepLimit = if (unlimited) HARD_UNLIMITED else MAX_STEPS
            var actionCount = 0
            var unverifiedDone = 0
            var pageHintShown = false
            var pageWarn = ""
            var blockStreak = 0
            var noteCount = 0
            val notes = ArrayList<Pair<String, String>>()
            val pageWindow = ArrayDeque<Boolean>()
            val recent = ArrayDeque<String>()

            try {
                if (unlimited) log("⚠ 无限制模式")
                svc.dumpTree()
                val startPkg = svc.lastPackage
                log("当前应用：${startPkg.ifBlank { "未知" }}")

                val experiences = ExperienceStore.recall(ctx, goal)
                log("历史相似案例：${if (experiences.isEmpty()) "暂无" else "${experiences.size} 条"}")

                val skill = SkillStore.match(ctx, goal)
                log("技能匹配：${skill?.name ?: "未命中"}")

                log("规划：先明确什么算完成…")
                runCatching {
                    LlmClient.planTask(goal, MemoryStore.recallText(ctx, startPkg), startPkg)
                }
                    .onSuccess { p ->
                        if (p.isNotBlank()) {
                            doneWhen = p.take(120)
                            log("完成判定：$doneWhen")
                        }
                    }
                    .onFailure { log("完成判定获取失败：${it.message}") }

                EpisodeRecorder.start(ctx, goal, doneWhen)

                log("开始执行：$goal")

                while (running) {
                    if (step >= stepLimit) {
                        if (stepLimit >= hardSteps) {
                            exitReason = "step_limit"
                            reason = "达到硬性步数上限 $hardSteps，任务中止"
                            break
                        }
                        stepLimit += 30
                        log("步数上限延长到 $stepLimit")
                    }

                    if (actionCount >= hardActions) {
                        exitReason = "step_limit"
                        reason = "动作数达到上限 $hardActions，任务中止"
                        break
                    }

                    step++
                    val stepStart = System.currentTimeMillis()
                    pageWarn = ""

                    if (!running) {
                        exitReason = "manual_stop"
                        reason = "已被手动停止"
                        break
                    }

                    if (!svc.isAlive()) {
                        exitReason = "error"
                        reason = if (AgentAccessibilityService.instance == null) {
                            "无障碍服务已断开，任务中止"
                        } else {
                            "当前页面拒绝无障碍访问（服务在线，但窗口数为 0）"
                        }
                        log("窗口数：${svc.windowCount()}")
                        break
                    }

                    var tree = svc.dumpStable()
                    var dismissed = 0
                    while (dismissed < 2 && tryDismissPopup()) {
                        dismissed++
                        Thread.sleep(600)
                        tree = svc.dumpStable()
                    }
                    if (dismissed > 0) log("自动关闭了 $dismissed 个弹窗")

                    val dumpOk = svc.lastDumpOk
                    blindStreak = if (dumpOk) 0 else blindStreak + 1

                    if (blindStreak >= 3) {
                        exitReason = "no_progress"
                        reason = "连续 3 步读不到界面，已停止"
                        break
                    }

                    val blockKind = if (unlimited) null else classifyBlockingPage(tree)
                    if (blockKind != null) {
                        blockStreak++
                        if (blockStreak > 3) {
                            exitReason = "blocked"
                            reason = "连续 $blockStreak 次恢复后仍停留在敏感页面，已终止"
                            break
                        }
                        if (!waitForUser(blockKind)) {
                            exitReason = "manual_stop"
                            reason = if (!running) "已被手动停止"
                            else "等待用户操作超时（10 分钟）"
                            break
                        }
                        tree = svc.dumpStable()
                    } else {
                        blockStreak = 0
                    }

                    if (!unlimited && AppRules.isBlocked(svc.lastPackage)) {
                        val staleNote = if (svc.lastPackageStale) "（包名可能过期）" else ""
                        exitReason = "blocked"
                        reason = "当前应用 ${svc.lastPackage}$staleNote 在黑名单中，已停止"
                        break
                    }

                    // V1.7：文字节点够多就省掉视觉调用（省钱省时间）；读不到界面时必须用视觉
                    val needVision = AgentConfig.visionEnabled &&
                        (!dumpOk || svc.lastTextNodeCount < VISION_TEXT_MIN)
                    if (AgentConfig.visionEnabled && !needVision) {
                        log("  （本屏文字节点 ${svc.lastTextNodeCount} 个，跳过视觉）")
                    }

                    val jpeg = if (needVision) svc.screenshotJpeg() else null
                    if (needVision && jpeg == null) {
                        log("截图失败：${svc.lastScreenshotError}")
                    }

                    val desc = if (needVision && jpeg != null) {
                        log("视觉外挂：识别截图中…")
                        runCatching {
                            VisionClient.describe(
                                jpeg,
                                "当前包名：${svc.lastPackage}；上一步动作：$lastActionDesc；结果：$lastResult",
                                doneWhen
                            )
                        }.getOrElse { "（识别失败：${it.message}）" }
                    } else null

                    val context = buildString {
                        appendLine("【长期记忆（按应用分开，只有已验证的可当证据）】")
                        appendLine(
                            MemoryStore.recallText(ctx, svc.lastPackage)
                                .ifBlank { "（还没有）" }
                        )
                        appendLine("【本次任务记忆】")
                        appendLine(memory.ifBlank { "（空）" })
                        appendLine("【计划】")
                        appendLine(plan.ifBlank { "（空）" })
                        appendLine("【完成判定（满足即 done）】")
                        appendLine(doneWhen)
                        appendLine("【历史相似案例】")
                        if (experiences.isEmpty()) {
                            appendLine("（暂无）")
                        } else {
                            experiences.forEach { appendLine("- $it") }
                        }
                        appendLine("【最近动作】")
                        append(if (recent.isEmpty()) "（还没有动作）" else recent.joinToString("\n"))
                        appendLine()
                        appendLine("【当前屏幕信息】")
                        appendLine("包名：${svc.lastPackage.ifBlank { "未知" }}")
                        if (svc.lastPackageStale) {
                            appendLine("（包名是上一次读到的旧值，可能已经切换应用）")
                        }
                        appendLine("可读文字节点数：${svc.lastTextNodeCount}")
                        if (svc.lastTextNodeCount < 5) {
                            appendLine("⚠ 本屏文字极少，请以视觉描述为准")
                        }
                    }

                    log("第 $step 步 · AI 思考中…")

                    var act: Action? = null
                    var tries = 0
                    while (act == null && tries < 3 && running) {
                        tries++
                        act = try {
                            LlmClient.decide(goal, context, tree, null, desc, lastResult)
                        } catch (e: Exception) {
                            log("请求失败（第 $tries 次）：${e.message}")
                            if (tries < 3) Thread.sleep(3000L * tries)
                            null
                        }
                    }

                    if (act == null) {
                        exitReason = if (!running) "manual_stop" else "error"
                        reason = if (!running) "已被手动停止" else "连续 3 次请求失败，任务中止"
                        break
                    }
                    val action = act

                    if (action.memory.isNotBlank()) memory = action.memory
                    if (action.plan.isNotBlank()) plan = action.plan
                    if (action.askUser.isNotBlank()) lastAskUser = action.askUser
                    if (action.thought.isNotBlank()) log("  想法：${action.thought}")
                    if (action.plan.isNotBlank()) log("  计划：${action.plan.take(90)}")

                    if (action.remember.isNotBlank()) {
                        when {
                            step <= 1 ->
                                log("  （第一步还没有结果，跳过记忆写入）")

                            !dumpOk ->
                                log("  （当前读不到界面，跳过记忆写入）")

                            noteCount >= MAX_MEMORY_WRITES ->
                                log("  （本局记忆写入已达上限，跳过）")

                            else -> {
                                val (scopeRaw, bodyRaw) = MemoryStore.splitScope(action.remember)
                                val clean = MemoryStore.sanitize(bodyRaw)
                                if (clean.isBlank()) {
                                    log("  （记忆含坐标/id 或太短，已丢弃）")
                                } else {
                                    val scope = scopeRaw.ifBlank {
                                        svc.lastPackage.ifBlank { MemoryStore.SCOPE_GLOBAL }
                                    }
                                    notes.add(scope to clean)
                                    noteCount++
                                    val where =
                                        if (scope == MemoryStore.SCOPE_GLOBAL) "通用" else scope
                                    log("  ★ 记下[$where]（本局结束提交）：$clean")
                                }
                            }
                        }
                    }

                    if (action.type == "done" && unlimited) {
                        exitReason = "done"
                        reason = "完成（无限制模式，跳过证据核对）：${action.thought}"
                        break
                    }

                    if (action.type == "done") {
                        val ev = normalize(action.evidence)
                        var ok = false
                        var why = ""

                        if (action.evidence.isBlank()) {
                            why = "你没有给 evidence 字段。请在 action 里加 evidence，" +
                                    "从屏幕上抄一段原文（至少 2 个字）"
                        } else if (ev.length < 2) {
                            why = "证据太短（归一化后不足 2 个字），请从屏幕上抄一整句原文"
                        } else if (!dumpOk && desc.isNullOrBlank()) {
                            why = "当前读不到界面，无法核对，请等界面稳定后再确认"
                        } else if (normalize(tree).contains(ev)) {
                            ok = true
                        } else if (!desc.isNullOrBlank() && normalize(desc).contains(ev)) {
                            ok = true
                        } else {
                            why = "你引用的原文不在当前屏幕上，请重新核对，" +
                                    "或换一条屏幕上能直接看到的证据"
                        }

                        if (ok) {
                            exitReason = "done"
                            reason = "完成：${action.thought}"
                            break
                        }

                        unverifiedDone++
                        lastResult = "完成被拒：$why"
                        log("  ⚠ 完成被拒（第 $unverifiedDone 次）：$why")
                        if (unverifiedDone >= 3) {
                            exitReason = "error"
                            reason = "连续 3 次完成证据无效，任务终止"
                            break
                        }
                        continue
                    }

                    unverifiedDone = 0

                    if (action.type == "memory") {
                        val newMem = action.text.orEmpty().trim()
                        if (newMem.isNotEmpty()) {
                            memory = newMem.take(1000)
                            log("  ✎ 工作记忆已更新（${newMem.lines().size} 条 / ${newMem.length} 字）")
                        }
                        continue
                    }

                    val sig = signature(action)
                    sameCount = if (sig == lastSig) sameCount + 1 else 1
                    lastSig = sig

                    val pkgBefore = svc.lastPackage
                    val rep = when (action.type) {
                        "scroll", "swipe" -> action.repeat.coerceIn(1, 5)
                        else -> 1
                    }

                    var point: Point? = null
                    var executed = 0

                    for (i in 1..rep) {
                        if (!running) break
                        val p = execute(action, sig, preview = (i == 1))
                        if (i == 1) point = p
                        executed++
                        actionCount++

                        val extra = when (action.type) {
                            "swipe" -> 500L
                            "home", "back" -> 800L
                            else -> 0L
                        }
                        Thread.sleep(STEP_DELAY + extra)

                        if (i % 2 == 0 && i < rep) {
                            if (svc.dumpStable() == tree) {
                                log("  （连续动作第 $i 次后界面不再变化，提前结束）")
                                break
                            }
                        }
                    }

                    lastActionDesc = describe(action, point)
                    if (executed > 1) lastActionDesc += "（连续 $executed 次）"
                    log("  动作：$lastActionDesc")

                    val after = svc.dumpStable()
                    val pkgAfter = svc.lastPackage
                    val changed = after != tree
                    stuck = if (changed || action.type == "wait") 0 else stuck + 1

                    if (action.type == "tap") {
                        pageWindow.addLast(changed)
                        while (pageWindow.size > PAGE_WINDOW) pageWindow.removeFirst()
                        val hits = pageWindow.count { it }
                        if (!pageHintShown && hits >= PAGE_HINT_THRESHOLD) {
                            pageHintShown = true
                            pageWarn = "注意：最近 ${PAGE_WINDOW} 步里你切换了 $hits 次页面。" +
                                    "确认这是计划内的推进；若任务是 N 次清单且清单未满，" +
                                    "继续执行，不要提前结束。"
                            log("  ⓘ 页面切换频繁，已提示一次")
                        }
                    }

                    if (!changed && action.type == "tap") failedNodeClicks.add(sig)

                    lastResult = buildString {
                        append(if (changed) "界面有变化。" else "界面没有任何变化。")
                        if (pkgBefore.isNotBlank() || pkgAfter.isNotBlank()) {
                            append("包名：${pkgBefore.ifBlank { "未知" }} → ")
                            append("${pkgAfter.ifBlank { "读不到" }}。")
                        }
                        if (sameCount >= 2) append("你已连续 $sameCount 次做同一个动作。")
                        if (action.type == "shell" && lastShellOutput.isNotBlank()) {
                            append("命令输出：")
                            append(lastShellOutput)
                        }
                        if (pageWarn.isNotBlank()) append(pageWarn)
                    }

                    EpisodeRecorder.step(
                        ctx,
                        step,
                        pkgAfter,
                        after,
                        lastActionDesc,
                        when {
                            changed -> "changed"
                            action.type == "wait" -> "wait"
                            else -> "noChange"
                        },
                        System.currentTimeMillis() - stepStart
                    )

                    recent.addLast(
                        "第${step}步 动作=$lastActionDesc 结果=" +
                                (if (changed) "有变化" else "无变化")
                    )
                    while (recent.size > 8) recent.removeFirst()

                    if (stuck == 3 || stuck == 6) {
                        log("连续 $stuck 步没进展，AI 自查中…")
                        runCatching {
                            val r = LlmClient.reflect(goal, context, after, desc)
                            if (r.thought.isNotBlank()) log("  诊断：${r.thought}")
                            if (r.memory.isNotBlank()) memory = r.memory
                            if (r.plan.isNotBlank()) {
                                plan = r.plan
                                log("  新计划：${r.plan.take(120)}")
                            }
                        }.onFailure { log("  自查失败：${it.message}") }
                    }

                    if (stuck >= 8) {
                        exitReason = "no_progress"
                        reason = "连续 $stuck 步界面无变化，放弃"
                        break
                    }
                }
            } catch (e: Exception) {
                exitReason = "error"
                reason = "出错：${e.message}"
            } finally {
                val manualStop = !running
                FloatingService.hideMarker()
                OverlayManager.clearWaiting()
                OverlayManager.onResumeClick = null
                waitingForUser = false
                running = false

                val finalReason = reason.ifBlank {
                    if (manualStop) "已被手动停止" else "任务结束"
                }

                val finalExit = exitReason.ifBlank {
                    when {
                        manualStop -> "manual_stop"
                        finalReason.startsWith("完成") -> "done"
                        else -> "unknown"
                    }
                }

                val epId = EpisodeRecorder.currentEpisodeId()
                EpisodeRecorder.finish(ctx, finalExit == "done", finalReason, finalExit)

                if (notes.isNotEmpty()) {
                    runCatching {
                        val n = MemoryStore.commit(ctx, notes, svc.lastPackage, epId)
                        log("  ✎ 长期记忆已提交 $n 条")
                    }.onFailure { log("  ✎ 长期记忆提交失败：${it.message}") }
                }

                if (epId.isNotBlank() && finalExit != "manual_stop") {
                    thread(name = "whale-learn") {
                        runCatching { ExperienceStore.learn(ctx, epId) }
                            .onFailure { log("  案例生成失败：${it.message}") }
                        runCatching { SkillStore.extract(ctx, epId) }
                            .onFailure { log("  技能抽取失败：${it.message}") }
                    }
                }

                onFinish?.invoke(finalReason)
                onFinishDetail?.invoke(finalExit, finalReason, step)
            }
        }
    }

    fun stop() {
        running = false
    }

    // ---------------- 执行 ----------------

    private fun execute(act: Action, sig: String, preview: Boolean = true): Point? =
        when (act.type) {
            "tap" -> doTap(act, sig)
            "input" -> doInput(act)
            "swipe" -> {
                doSwipe(act, preview)
                null
            }
            "scroll" -> {
                val forward = !act.text.orEmpty().contains("back", true)
                if (!svc.scrollContainer(forward)) {
                    log("  （滚动动作失败，容器可能不支持）")
                }
                null
            }
            "shell" -> {
                val cmd = act.text.orEmpty().trim()
                log("  执行命令：" + cmd)
                lastShellOutput = ToolBridge.run(svc.applicationContext, cmd)
                null
            }
            "wait" -> {
                doWait(act)
                null
            }
            "back" -> {
                if (!svc.globalBack()) log("  （返回动作派发失败）")
                null
            }
            "home" -> {
                if (!svc.globalHome()) log("  （回桌面动作派发失败）")
                null
            }
            else -> null
        }

    private fun doTap(act: Action, sig: String): Point? {
        val id = act.id
        if (id == null && (act.x == null || act.y == null)) {
            log("  ⚠ 点击参数不完整，跳过本步")
            return null
        }

        val target: Point = when {
            id != null -> svc.nodeCenter(id)
            else -> Point(act.x!!, act.y!!)
        } ?: return null

        OverlayManager.beginAgentAction(target.x, target.y)
        Thread.sleep(120)

        if (AgentConfig.previewEnabled) {
            FloatingService.showTap(target.x, target.y, "点击")
            log("预览：即将点击 (${target.x}, ${target.y})")
            Thread.sleep(PREVIEW_DELAY)
        }

        val useNode = id != null && act.tapMode != "coord" && sig !in failedNodeClicks
        val ok = if (useNode && id != null) svc.tapNode(id)
        else svc.tap(target.x.toFloat(), target.y.toFloat())

        if (!ok) log("  （点击手势派发失败）")

        Thread.sleep(250)
        OverlayManager.endAgentAction()
        return target
    }

    private fun doInput(act: Action): Point? {
        val id = act.id
        val text = act.text
        if (id == null || text == null) {
            log("  ⚠ 输入参数不完整，跳过本步")
            return null
        }

        val center = svc.nodeCenter(id)

        OverlayManager.beginAgentAction(center?.x ?: -1, center?.y ?: -1)
        Thread.sleep(120)

        if (AgentConfig.previewEnabled && center != null) {
            FloatingService.showTap(center.x, center.y, "输入")
            Thread.sleep(PREVIEW_DELAY)
        }

        var ok = svc.setText(id, text)
        Thread.sleep(250)
        if (!ok || svc.nodeText(id).isNullOrBlank()) {
            Thread.sleep(300)
            ok = svc.setText(id, text)
            if (!ok) log("  ⚠ 输入可能没有写进去（已重试一次）")
        }

        OverlayManager.endAgentAction()
        return center
    }

    private fun doSwipe(act: Action, showPreview: Boolean = true) {
        val x1 = act.x
        val y1 = act.y
        val x2 = act.x2
        val y2 = act.y2

        if (x1 == null || y1 == null || x2 == null || y2 == null) {
            log("  ⚠ 滑动参数不完整，改用默认纵向滑动")
            defaultSwipe()
            return
        }

        OverlayManager.beginAgentAction(x1, y1)
        Thread.sleep(120)

        if (AgentConfig.previewEnabled && showPreview) {
            FloatingService.showSwipe(x1, y1, x2, y2, "滑动")
            Thread.sleep(PREVIEW_DELAY)
        }
        if (!svc.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())) {
            log("  （滑动手势派发失败）")
        }

        Thread.sleep(250)
        OverlayManager.endAgentAction()
    }

    private fun defaultSwipe() {
        OverlayManager.beginAgentAction(-1, -1)
        Thread.sleep(120)
        val dm = svc.resources.displayMetrics
        val x = dm.widthPixels / 2f
        svc.swipe(x, dm.heightPixels * 0.75f, x, dm.heightPixels * 0.35f)
        Thread.sleep(250)
        OverlayManager.endAgentAction()
    }

    private fun doWait(act: Action) {
        val total = parseDuration(act.text)
        val human = if (total >= 60) "${total / 60} 分 ${total % 60} 秒"
        else "${total} 秒"
        log("  等待 $human（可随时停止）")

        var left = total * 1000L
        while (left > 0 && running) {
            val chunk = minOf(200L, left)
            Thread.sleep(chunk)
            left -= chunk
        }
    }

    private fun parseDuration(text: String?): Int {
        val t = text?.trim().orEmpty()
        val mmss = Regex("""^(\d{1,2}):(\d{2})$""").find(t)
        if (mmss != null) {
            val min = mmss.groupValues[1].toInt()
            val sec = mmss.groupValues[2].toInt()
            return (min * 60 + sec).coerceIn(1, 1800)
        }
        return t.toIntOrNull()?.coerceIn(1, 1800) ?: 2
    }

    /** 判断当前屏幕是否属于需要用户介入的页面 */
    private fun classifyBlockingPage(tree: String): String? {
        if (verifyWords.any { tree.contains(it) }) return KIND_VERIFY
        if (payWords.any { tree.contains(it) }) return KIND_PAY
        return null
    }

    /**
     * 暂停，等用户操作完点「继续」。
     * 返回 true 表示可以继续，false 表示任务应终止。
     */
    private fun waitForUser(kind: String): Boolean {
        val hint = lastAskUser.ifBlank {
            if (kind == KIND_VERIFY) "需要你验证身份，验完点继续"
            else "需要你操作，完成点继续"
        }
        log("⏸ $hint")
        OverlayManager.showWaiting(hint)

        waitingForUser = true
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        while (waitingForUser && running && System.currentTimeMillis() < deadline) {
            Thread.sleep(300)
        }

        OverlayManager.clearWaiting()
        lastAskUser = ""

        if (!running) return false
        if (System.currentTimeMillis() >= deadline) return false
        return true
    }

    private fun tryDismissPopup(): Boolean {
        val dm = svc.resources.displayMetrics
        val maxWidth = dm.widthPixels * 0.7f
        for ((id, node) in svc.nodeMap) {
            val raw = (node.text?.toString() ?: "") + " " +
                    (node.contentDescription?.toString() ?: "")
            val label = raw.trim()
            if (label.isBlank()) continue

            val hit = dismissTexts.firstOrNull { label.contains(it) }
            val isExactClose = label == "关闭"

            if (hit == null && !isExactClose) continue
            if (isExactClose && svc.lastTextNodeCount >= 12) continue

            val rect = Rect().also { node.getBoundsInScreen(it) }
            if (rect.width() <= 0 || rect.height() <= 0) continue
            if (rect.width() > maxWidth) continue

            log("  自动关闭弹窗：${label.take(20)}")
            svc.tapNode(id)
            return true
        }
        return false
    }

    /**
     * 证据核对用归一化：去所有空白（含全角空格）、全角转半角、
     * 去掉标点、英文转小写。只保留汉字、字母、数字。
     */
    private fun normalize(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val sb = StringBuilder(s.length)
        for (ch in s) {
            var c = ch
            if (c.code in 0xFF01..0xFF5E) c = (c.code - 0xFEE0).toChar()
            if (c == '\u3000') continue
            if (c.isWhitespace()) continue
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    private fun signature(act: Action): String = when (act.type) {
        "tap" -> "tap:${act.id ?: "${act.x},${act.y}"}:${act.tapMode}"
        "input" -> "input:${act.id}:${act.text}"
        "swipe" -> "swipe:${act.x},${act.y},${act.x2},${act.y2}"
        "shell" -> "shell:" + act.text.orEmpty()
        else -> act.type
    }

    private fun describe(act: Action, p: Point?): String = when (act.type) {
        "tap" -> {
            val label = act.id?.let { svc.nodeText(it) }.orEmpty().trim()
            if (label.isNotBlank()) {
                "点击「${label.take(14)}」(${p?.x}, ${p?.y}) [${act.tapMode}]"
            } else {
                "点击 (${p?.x}, ${p?.y}) [${act.tapMode}]"
            }
        }
        "input" -> "在 (${p?.x}, ${p?.y}) 输入「${act.text.orEmpty().take(12)}」"
        "swipe" -> "滑动 (${act.x},${act.y}) → (${act.x2},${act.y2})"
        "scroll" -> "滚动 ${act.text ?: "forward"}"
        "wait" -> "等待 ${act.text ?: "2"} 秒"
        "back" -> "返回"
        "home" -> "回桌面"
        "shell" -> "执行命令「" + act.text.orEmpty().take(24) + "」"
        else -> act.type
    }

    private fun log(s: String) {
        onLog?.invoke(s)
    }

    companion object {
        private const val MAX_STEPS = 30
        private const val HARD_MAX_STEPS = 120
        private const val HARD_MAX_ACTIONS = 150
        private const val HARD_UNLIMITED = 999
        private const val STEP_DELAY = 900L
        /** 文字节点少于这个数才调视觉外挂 */
        private const val VISION_TEXT_MIN = 8
        private const val PREVIEW_DELAY = 900L
        private const val MAX_MEMORY_WRITES = 6
        private const val PAGE_WINDOW = 6
        private const val PAGE_HINT_THRESHOLD = 4
        private const val WAIT_TIMEOUT_MS = 10 * 60 * 1000L
        private const val KIND_VERIFY = "verify"
        private const val KIND_PAY = "pay"
    }
}
