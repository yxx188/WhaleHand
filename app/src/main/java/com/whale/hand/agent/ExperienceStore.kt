package com.whale.hand.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 经验库（案例卡）。
 * 文件：filesDir/cases.json
 *
 * 结构：[{"goal","doneWhen","path","pitfall","exitReason","verified","successCount","updatedAt"}]
 *
 * 规则：
 * - 只有 verified=true 的卡会参与 recall（注入上下文）
 * - 总数上限 50，verified 保底 70%，草稿最多 30%
 * - 同 goal（归一化后）合并，successCount 累加
 */
object ExperienceStore {

    private const val MAX_CASES = 50
    private const val MIN_SIMILARITY = 0.15
    private const val TAG = "ExperienceStore"

    private fun file(ctx: Context) = File(ctx.filesDir, "cases.json")

    // ---------------- 读取 ----------------

    private fun loadCards(ctx: Context): List<JSONObject> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                runCatching { arr.getJSONObject(i) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 检索相似案例。只返回 verified=true 的卡。
     * 相似度用 2-gram Jaccard，中文不需要分词。
     */
    fun recall(ctx: Context, goal: String, limit: Int = 3): List<String> {
        val cards = loadCards(ctx).filter { it.optBoolean("verified") }
        if (cards.isEmpty()) return emptyList()

        val g = bigrams(goal)
        if (g.isEmpty()) return emptyList()

        return cards.map { c ->
            val sim = jaccard(g, bigrams(c.optString("goal")))
            val weight = 1.0 + 0.1 * c.optInt("successCount", 0)
            c to (sim * weight)
        }
            .filter { it.second >= MIN_SIMILARITY }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (c, _) ->
                "目标：${c.optString("goal")}｜路径：${c.optString("path")}｜坑：${c.optString("pitfall")}"
            }
    }

    // ---------------- 学习 ----------------

    /**
     * 从 Episode 生成案例卡。只吃 epId，不碰 EpisodeRecorder 的单例字段。
     * 失败任务也生成，但 verified=false，path 标「未走通」。
     */
    fun learn(ctx: Context, episodeId: String) {
        val events = EpisodeRecorder.loadEpisode(ctx, episodeId)
        if (events.isEmpty()) return

        val start = events.firstOrNull { it.optString("type") == "start" } ?: return
        val end = events.lastOrNull { it.optString("type") == "end" } ?: return

        val goal = end.optString("goal", start.optString("goal", "")).trim()
        if (goal.length < 2) return

        val exitReason = end.optString("exitReason").ifBlank {
            if (end.optBoolean("success")) "done" else "unknown"
        }
        if (exitReason == "manual_stop" || exitReason == "unknown") return

        val steps = events.filter { it.optString("type") == "step" }
        val digest = compress(steps)
        val verified = exitReason == "done"

        val prompt = buildLearnPrompt(
            goal = goal,
            doneWhen = end.optString("doneWhen", start.optString("doneWhen", "")),
            exitReason = exitReason,
            digest = digest,
            success = verified
        )

        val raw = runCatching { askModel(prompt) }.getOrNull() ?: return
        val card = extractJson(raw) ?: return

        val cleanGoal = card.optString("goal").trim()
        if (cleanGoal.length < 2) return

        card.put("goal", cleanGoal.take(40))
        card.put("doneWhen", card.optString("doneWhen").take(60))
        card.put("path", sanitizePath(card.optString("path")))
        card.put("pitfall", card.optString("pitfall").take(80))
        card.put("exitReason", exitReason)

        mergeAndSave(ctx, card, verified)

        if (verified) {
            runCatching {
                val pkg = steps.lastOrNull()?.optString("pkg").orEmpty()
                val n = MemoryStore.reinforceByCase(
                    ctx,
                    pkg,
                    card.optString("path"),
                    card.optString("pitfall")
                )
                if (n > 0) Log.d(TAG, "长期记忆被印证 $n 条")
            }
        }
    }

    /** 分层压缩：骨架 + 每 3 步 1 条 + 全部 noChange + 最后 3 步，去重后上限 25 行 */
    private fun compress(steps: List<JSONObject>): String {
        if (steps.isEmpty()) return "（没有步骤记录）"

        val keep = LinkedHashSet<Int>()
        steps.firstOrNull()?.let { keep.add(it.optInt("step")) }
        steps.filterIndexed { i, _ -> i % 3 == 0 }.forEach { keep.add(it.optInt("step")) }
        steps.filter { it.optString("result") == "noChange" }.forEach { keep.add(it.optInt("step")) }
        steps.takeLast(3).forEach { keep.add(it.optInt("step")) }

        val sb = StringBuilder()
        for (s in keep.sorted()) {
            val o = steps.firstOrNull { it.optInt("step") == s } ?: continue
            sb.append("第").append(s).append("步 ")
                .append(o.optString("action").take(60))
                .append(" → ").append(o.optString("result"))
                .append('\n')
            if (sb.count { it == '\n' } >= 25) break
        }
        return sb.toString().trim()
    }

    private fun buildLearnPrompt(
        goal: String,
        doneWhen: String,
        exitReason: String,
        digest: String,
        success: Boolean
    ): String = """
你在为一个安卓手机 AI 总结任务经验，输出一张案例卡。

目标：$goal
完成判定：$doneWhen
结束原因：$exitReason
本次是否成功：${if (success) "是" else "否"}

实际操作轨迹（已压缩，noChange 的步骤全部保留）：
$digest

输出一个 JSON 对象，不要 markdown，不要解释：
{"goal":"目标（20 字内）","doneWhen":"可验证的完成判定（30 字内）","path":"有效路径，用 → 连接（60 字内）","pitfall":"踩过的坑或要注意的点（60 字内）"}

要求：
- path 里禁止出现任何数字坐标、控件 id、#编号，这些下次一定失效
- path 只写界面和按钮的名字，例如「首页 → 我的 → 设置」
- 如果本次没成功：path 前面加「未走通」，pitfall 必须写清楚卡在哪一步、为什么
- 如果本次成功：pitfall 写这次绕过的弯路，没有就填空字符串
- 只输出 JSON
""".trimIndent()

    private fun askModel(prompt: String): String {
        val body = JSONObject().apply {
            put("model", AgentConfig.model)
            put("temperature", 0.3)
            put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)
            ))
        }.toString()

        val raw = LlmClient.postJson(AgentConfig.apiUrl, AgentConfig.apiKey, body)
        return JSONObject(raw)
            .getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
            .replace("```json", "")
            .replace("```", "")
            .trim()
    }

    private fun extractJson(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until raw.length) {
            when (raw[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching {
                            JSONObject(raw.substring(start, i + 1))
                        }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    /** 去掉坐标、id、#编号，防止把一次性信息写进案例 */
    private fun sanitizePath(path: String): String {
        var t = path.trim()
        t = t.replace(Regex("""\(\s*\d{1,4}\s*,\s*\d{1,4}\s*\)"""), "")
        t = t.replace(Regex("""\bid\s*[=:]\s*\d+""", RegexOption.IGNORE_CASE), "")
        t = t.replace(Regex("""#\d+"""), "")
        t = t.replace(Regex("""\s{2,}"""), " ")
        return t.take(120)
    }

    // ---------------- 合并 / 淘汰 ----------------

    private fun mergeAndSave(ctx: Context, card: JSONObject, verified: Boolean) {
        val list = loadCards(ctx).toMutableList()
        val key = normalizeGoal(card.optString("goal"))
        val idx = list.indexOfFirst { normalizeGoal(it.optString("goal")) == key }

        if (idx >= 0) {
            val old = list[idx]
            val now = System.currentTimeMillis()

            val oldPit = old.optString("pitfall")
            val newPit = card.optString("pitfall")
            if (newPit.isNotBlank() && !oldPit.contains(newPit)) {
                old.put(
                    "pitfall",
                    listOf(oldPit, newPit).filter { it.isNotBlank() }.joinToString("；").take(160)
                )
            }

            if (verified) {
                old.put("successCount", old.optInt("successCount", 0) + 1)
                old.put("verified", true)
                val oldPath = old.optString("path")
                if (oldPath.startsWith("未走通")) old.put("path", card.optString("path"))
            }

            old.put("updatedAt", now)
            list[idx] = old
        } else {
            card.put("successCount", if (verified) 1 else 0)
            card.put("verified", verified)
            card.put("updatedAt", System.currentTimeMillis())
            list.add(card)
        }

        saveCases(ctx, trim(list))
    }

    /**
     * 淘汰策略：verified 保底 70%，草稿最多 30%。
     * 这样在 done 判定还没修好时，草稿也不会把库塞满。
     */
    private fun trim(list: List<JSONObject>): List<JSONObject> {
        if (list.size <= MAX_CASES) return list

        val verified = list.filter { it.optBoolean("verified") }
            .sortedByDescending { it.optLong("updatedAt") }
        val drafts = list.filter { !it.optBoolean("verified") }
            .sortedByDescending { it.optLong("updatedAt") }

        val keepVerifiedCount = (MAX_CASES * 7 / 10).coerceAtLeast(1)
        val keepVerified = verified.take(keepVerifiedCount)
        val keepDrafts = drafts.take((MAX_CASES - keepVerified.size).coerceAtLeast(0))

        return (keepVerified + keepDrafts).sortedByDescending { it.optLong("updatedAt") }
    }

    // ---------------- 2-gram ----------------

    private fun normalizeGoal(s: String): String =
        s.replace(Regex("""\s+"""), "").lowercase()

    private fun bigrams(s: String): Set<String> {
        val t = s.replace(Regex("""[\s\p{P}]+"""), "")
        if (t.isEmpty()) return emptySet()
        if (t.length < 2) return setOf(t)
        val out = HashSet<String>(t.length)
        for (i in 0 until t.length - 1) out.add(t.substring(i, i + 2))
        return out
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var inter = 0
        for (x in a) if (b.contains(x)) inter++
        val union = a.size + b.size - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union
    }

    // ---------------- 存储 ----------------

    fun saveCases(ctx: Context, cases: List<JSONObject>) {
        runCatching {
            file(ctx).writeText(JSONArray(cases).toString(2))
        }
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}
