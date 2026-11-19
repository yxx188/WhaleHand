package com.whale.hand.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Action(
    val type: String,
    val id: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val text: String? = null,
    val tapMode: String = "node",
    val thought: String = "",
    val memory: String = "",
    val plan: String = "",
    val remember: String = "",
    /** done 时引用的屏幕原文，不超过 20 字 */
    val evidence: String = "",
    /** 仅 scroll / swipe 生效，范围 1 到 5 */
    val repeat: Int = 1,
    /** 需要用户介入时显示在悬浮条上的一句话，不超过 20 字 */
    val askUser: String = ""
)

object LlmClient {

    // ---------------- 决策 ----------------

    /**
     * jpeg 参数目前恒为 null（视觉已改走 VisionClient）。
     * 删除它需要同步改 AgentLoop 的调用，留到 V1.4 清理。
     */
    fun decide(
        goal: String,
        history: String,
        tree: String,
        jpeg: ByteArray?,
        screenDesc: String? = null,
        feedback: String? = null,
        reflecting: Boolean = false
    ): Action {
        val visionBlock = if (screenDesc.isNullOrBlank()) "" else
            "视觉外挂对截图的描述（可能不准，以控件清单为准）：\n$screenDesc\n\n"

        val prompt = if (reflecting) """
你卡住了，需要自己复盘。

目标：$goal

你的记忆、计划和最近动作：
$history

上一步结果：${feedback ?: "（无）"}

当前屏幕控件清单：
$tree

视觉外挂描述：
${screenDesc ?: "（无视觉描述）"}

请分析：
1. 为什么一直没进展？
2. 之前的判断哪里错了？
3. 换什么新思路？

输出 JSON（不要 markdown）：
{"thought":"诊断结论","memory":"更新后的完整记忆","plan":"新计划","action":{"type":"wait"}}
""".trimIndent() else """
你是一个操作安卓手机的 AI。请独立思考、自己判断，不要机械重复。

目标：$goal

你自己的记忆、当前计划和最近动作（memory 和 plan 由你自己维护，会原样带回给你）：
$history

上一步动作的结果（重要）：
${feedback ?: "（第一步，还没有上一步）"}

当前屏幕控件清单（id / 类型 / 文字 / 中心坐标 / 大小）：
$tree

${visionBlock}Rules:
- 只输出一个 JSON，不要 markdown，不要解释。
- 必须包含 thought（你的分析）、memory（本次记忆）、plan（接下来 1~3 步）、action。
- 如果上一步没让界面变化，先判断是"没点中"还是"应用在加载"，再决定 wait / 换元素 / 换路径，不要无脑重复。
- 同一动作反复失败时，把 tapMode 从 node 换成 coord，或者换一条完全不同的路径。
- 如果目标要求在"当前页面"操作，不要回桌面、不要切换应用，直接在当前界面执行。
- 如果当前不在目标应用里，先用 home 回桌面，再从桌面或全局搜索进入目标应用。
- 如果"当前屏幕信息"里的可读文字节点数很少（H5、游戏、图片页），说明控件树不可靠，请以视觉描述为准。
- 只有当"完成判定"确实满足时才输出 done；满足最小条件就立即结束，不要为了"多确认几个"而延长任务。
- 完成判定里的每条都必须是屏幕上能直接看到的证据；不能写"应该正在播放""可能已加载"这类推测。
- 输出 done 时，action 里必须带 evidence 字段。缺这个字段会被直接拒绝，不是警告。格式：{"type":"done","thought":"为什么算完成","evidence":"屏幕原文"}。
- evidence 从当前屏幕抄一段原文，至少 2 个字，不超过 20 字，不要改写、不要概括、不要加引号。抄不出来就说明还没完成，继续操作。连续 3 次无效将任务终止。
- 「本应用」指任务开始时记录的那个包名，不是当前屏幕上碰巧在前台的其他应用。判断是否已返回本应用，只看「当前屏幕信息」里的包名。
- 如果当前屏幕需要用户本人操作（指纹、密码、验证码、支付确认），在 action 里带 askUser，用一句人话说明要他做什么，不超过 20 字。例如 {"type":"wait","askUser":"请按指纹后点继续"}。不要自己尝试完成这些操作。- 做 N 次的任务（看 N 个视频、刷 N 条等）：必须在 memory 里维护清单，例如「已看：A、B、C（3/5）」，每完成一个就更新；清单长度不够 N 时禁止 done。
- 涉及看视频时：先从屏幕读出视频总时长（如 06:13）或进度条，用它决定等待多久；读不到时长就用 60 秒。禁止用固定几秒敷衍。
- 滑动没有让界面变化时，改用 scroll 动作。
- 连续翻多屏时，可以给 scroll 或 swipe 加 repeat（1~5），例如 {"type":"scroll","text":"forward","repeat":3}；不确定要滑几屏就省略 repeat。
- shell：{"type":"shell","text":"命令"} 交给 Termux 执行一条命令，超时 20 秒，输出截断到 1200 字。只在必须用命令行、或界面读不到信息时才用；界面操作一律用 tap/swipe/input，不要用 shell 点界面。
- shell 不能跑 reboot/shutdown/mkfs/dd/rm -rf / 这类命令，会被安全阀拦下，拦了就换个办法。
- input 动作会整段覆盖输入框内容，不需要先清空旧文字。
- remember：只写"稳定的操作知识"——入口在哪个界面、路径怎么走、哪条路走不通。禁止写 id、坐标、#编号，这些下次会失效。没有就填 ""。
- remember 默认只记在当前应用名下；如果这条知识换任何应用都成立（例如系统返回手势、通知栏位置），开头写 global: ，例如 "global: 系统返回手势在屏幕左右边缘上滑"。
- remember 是长期记忆，一局最多 6 条，任务结束才落盘。本局马上要用的事写进 memory，不要塞 remember。
- 长期记忆的等级由系统判定：[已验证] 是三次任务都印证过，[观察] 是两次，[假设] 只出现过一次。只有 [已验证] 可以当证据，[假设] 只能当线索。
- memory：{"type":"memory","text":"本次任务的工作记忆，整份替换"}，只影响本局，不当证据用。
- tap：{"type":"tap","id":3} 或 {"type":"tap","x":540,"y":1200,"tapMode":"coord"}
- input：{"type":"input","id":5,"text":"你好"}
- swipe：{"type":"swipe","x":540,"y":1600,"x2":540,"y2":700}（四个坐标必须给全，缺一个就会被丢弃）
- scroll：{"type":"scroll","text":"forward"} 表示内容向上滚（看更早/更多内容），"backward" 相反
- wait：{"type":"wait","text":"5"}，text 可以是秒数或 mm:ss，最长 1800 秒
- 其他：{"type":"back"} / {"type":"home"}
- 完成：{"type":"done","thought":"为什么算完成","evidence":"屏幕上抄来的原文"}
- 绝不点击支付、银行、转账、密码确认相关的东西。

字段示例：
{"thought":"分析","memory":"本次记忆","plan":"下一步计划","remember":"一条稳定知识，没有就空字符串","action":{"type":"tap","id":3}}
""".trimIndent()

        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        val body = JSONObject().apply {
            put("model", AgentConfig.model)
            put("temperature", 0.1)
            put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", content)
            ))
        }.toString()

        val raw = postJson(AgentConfig.apiUrl, AgentConfig.apiKey, body)
        val text = JSONObject(raw)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        return parseAction(text)
    }

    /** 复盘：复用 decide 的复盘模式 */
    fun reflect(goal: String, history: String, tree: String, screenDesc: String?): Action =
        decide(goal, history, tree, null, screenDesc = screenDesc, reflecting = true)

    // ---------------- 聊天（V2.0） ----------------

    /**
     * 通用聊天补全：messages 原样发给模型，返回 content 原文。
     * 与 decide 共用同一条 postJson 通道（重试 / 超时 / 鉴权都一样）。
     */
    fun chat(messages: JSONArray, temperature: Double = 0.7): String =
        chat(messages, temperature, ModelStore.current(), -1)

    /**
     * 带模型档案与思考强度的聊天补全（只作用于聊天，任务台仍走 decide）。
     * 通用底座：提示词后缀（ChatBrain 拼）+ max_tokens 上限；
     * 原生参数：档案声明了 thinkingMode 才叠加，失败不影响可用性。
     */
    fun chat(
        messages: JSONArray,
        temperature: Double,
        profile: ModelProfile?,
        effort: Int
    ): String {
        require(messages.length() > 0) { "messages 不能为空" }

        val p = profile ?: ModelStore.current()
        val url = p?.apiUrl?.takeIf { it.isNotBlank() } ?: AgentConfig.apiUrl
        val key = p?.apiKey?.takeIf { it.isNotBlank() } ?: AgentConfig.apiKey
        val modelName = p?.model?.takeIf { it.isNotBlank() } ?: AgentConfig.model

        val body = JSONObject().apply {
            put("model", modelName)
            put("temperature", temperature)
            put("messages", messages)
            if (effort in 0..4) {
                put("max_tokens", Effort.maxTokens(effort))
                Effort.nativeBody(p?.thinkingMode ?: ModelProfile.THINK_NONE, effort)?.let { extra ->
                    val it = extra.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        put(k, extra.get(k))
                    }
                }
            }
        }.toString()

        val raw = postJson(url, key, body)
        return JSONObject(raw)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
            .replace("```", "")
            .trim()
    }

    // ---------------- 流式聊天（思考过程实时上屏） ----------------

    /**
     * 流式聊天补全：逐块回调（累计的思考文本 + 累计的正文），返回 (reasoning, content)。
     * 与 chat() 走同一套模型档案 / 思考强度 / 原生参数逻辑，额外带 stream=true。
     */
    fun chatStream(
        messages: JSONArray,
        temperature: Double,
        profile: ModelProfile?,
        effort: Int,
        onDelta: (reasoning: String, content: String) -> Unit
    ): Pair<String, String> {
        require(messages.length() > 0) { "messages 不能为空" }

        val p = profile ?: ModelStore.current()
        val url = p?.apiUrl?.takeIf { it.isNotBlank() } ?: AgentConfig.apiUrl
        val key = p?.apiKey?.takeIf { it.isNotBlank() } ?: AgentConfig.apiKey
        val modelName = p?.model?.takeIf { it.isNotBlank() } ?: AgentConfig.model

        val body = JSONObject().apply {
            put("model", modelName)
            put("temperature", temperature)
            put("messages", messages)
            put("stream", true)
            if (effort in 0..4) {
                put("max_tokens", Effort.maxTokens(effort))
                Effort.nativeBody(p?.thinkingMode ?: ModelProfile.THINK_NONE, effort)?.let { extra ->
                    val it = extra.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        put(k, extra.get(k))
                    }
                }
            }
        }.toString()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Connection", "close")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer $key")
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP $code: " + err.take(200))
            }
            val sbReason = StringBuilder()
            val sbContent = StringBuilder()
            var lastLoopCheck = 0
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val t = line.trim()
                    if (!t.startsWith("data:")) continue
                    val payload = t.substring(5).trim()
                    if (payload == "[DONE]") break
                    runCatching {
                        val j = JSONObject(payload)
                        val delta = j.optJSONArray("choices")
                            ?.optJSONObject(0)?.optJSONObject("delta") ?: return@runCatching
                        val r = if (delta.isNull("reasoning_content")) "" else delta.optString("reasoning_content", "")
                        val c = if (delta.isNull("content")) "" else delta.optString("content", "")
                        if (r.isNotEmpty()) sbReason.append(r)
                        if (c.isNotEmpty()) sbContent.append(c)
                        if (r.isNotEmpty() || c.isNotEmpty()) {
                            onDelta(sbReason.toString(), sbContent.toString())
                        }
                        // 思考退化保护：只掐「复读」，不限制正常的长思考
                        if (sbReason.length > 1200 && sbContent.isEmpty() &&
                            sbReason.length - lastLoopCheck >= 400
                        ) {
                            lastLoopCheck = sbReason.length
                            val tail = sbReason.takeLast(1200)
                            // 1) 自我催促词在近 1200 字里密集出现
                            val hits = Regex("输出|好。|done|finish|stop|ok\\.").findAll(tail).count()
                            // 2) 12 字片段反复出现
                            val grams = HashMap<String, Int>()
                            var maxRepeat = 0
                            var i = 0
                            while (i + 12 <= tail.length) {
                                val g = tail.substring(i, i + 12)
                                val c = (grams[g] ?: 0) + 1
                                grams[g] = c
                                if (c > maxRepeat) maxRepeat = c
                                i += 6
                            }
                            if (hits >= 40 || maxRepeat >= 10) {
                                throw IllegalStateException("思考陷入重复，已自动中断")
                            }
                        }
                    }
                }
            }
            return Pair(sbReason.toString(), sbContent.toString())
        } finally {
            conn.disconnect()
        }
    }

    // ---------------- 任务规划 ----------------

    /** 任务开始前，先让 AI 写清楚"什么算完成" */
    fun planTask(goal: String, memory: String, currentApp: String): String {
        val prompt = """
你要执行一个手机操作任务。动手之前，先想清楚"什么算完成"。

目标：$goal

当前所在应用包名：${currentApp.ifBlank { "未知" }}

你已有的设备记忆：
${memory.ifBlank { "（还没有）" }}

请输出一句完成判定，要求：
- 必须能用屏幕上的文字或数量直接验证
- 如果目标提到"当前页面""这个页面""继续"，完成判定必须限定在当前应用内，不要要求回桌面或打开别的应用
- 涉及"N 次 / N 个"的，写明数量和"不同"
- 涉及看视频的，写明"每个视频按其完整时长播放"
- 涉及购物的，写明价格或条件
- 不要写"进度条在推进""应该正在加载"这类看不见的东西
- 不超过 60 字，只输出这一句

示例：
目标：在当前页面从下往上阅读
完成判定：当前应用页面已滚动到顶部，继续上滑不再出现新内容

目标：刷5个视频
完成判定：打开并各按完整时长播完的 5 个不同视频，当前停在第 5 个

目标：打开朋友圈
完成判定：屏幕上出现朋友圈动态列表，顶部标题为"朋友圈"
""".trimIndent()
        return askText(prompt)
    }

    // ---------------- 通用请求 ----------------

    /** 发一个纯文本请求，返回模型输出原文 */
    private fun askText(prompt: String): String {
        val body = JSONObject().apply {
            put("model", AgentConfig.model)
            put("temperature", 0.3)
            put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)
            ))
        }.toString()

        val raw = postJson(AgentConfig.apiUrl, AgentConfig.apiKey, body)
        return JSONObject(raw)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
            .replace("```", "")
            .trim()
    }

    /**
     * 发 POST 请求。
     * 强制不复用长连接；只对网络异常重试，HTTP 4xx/5xx 直接抛。
     */
    internal fun postJson(url: String, apiKey: String, body: String): String {
        require(apiKey.isNotBlank()) { "还没有填写 API Key，先在首页保存配置" }

        var lastError: Exception? = null

        repeat(3) { attempt ->
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 90_000
                doOutput = true
                setRequestProperty("Connection", "close")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (code !in 200..299) {
                    throw IllegalStateException("HTTP $code: ${text.take(200)}")
                }
                return text
            } catch (e: java.io.IOException) {
                lastError = e
            } finally {
                conn.disconnect()
            }

            if (attempt < 2) Thread.sleep(700L * (attempt + 1))
        }

        throw IllegalStateException("网络请求失败（已重试 3 次）：${lastError?.message}")
    }

    private fun parseAction(raw: String): Action {
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        require(start >= 0) { "模型没有返回 JSON：${raw.take(200)}" }

        var depth = 0
        var end = -1
        for (i in start until cleaned.length) {
            when (cleaned[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        require(end > start) { "模型返回的 JSON 不完整：${raw.take(200)}" }

        val obj = JSONObject(cleaned.substring(start, end + 1))
        val a = obj.optJSONObject("action") ?: JSONObject()
        fun pos(name: String) = a.optInt(name, -1).takeIf { it >= 0 }

        // evidence 可能在 action 里，也可能在根级，两处都认
        val ev = a.optString("evidence").ifBlank { obj.optString("evidence") }

        return Action(
            type = a.optString("type", "wait"),
            id = pos("id"),
            x = pos("x"),
            y = pos("y"),
            x2 = pos("x2"),
            y2 = pos("y2"),
            text = a.optString("text").takeIf { it.isNotBlank() && it != "null" },
            tapMode = a.optString("tapMode", "node"),
            thought = obj.optString("thought", ""),
            memory = obj.optString("memory", ""),
            plan = obj.optString("plan", ""),
            remember = obj.optString("remember", ""),
            evidence = ev.trim().take(40),
            repeat = a.optInt("repeat", 1).coerceIn(1, 5),
            askUser = a.optString("askUser").ifBlank { obj.optString("askUser") }
                .trim().take(40)
        )    }
}
