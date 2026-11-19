package com.whale.hand.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条可复用技能（由成功轨迹自动抽取） */
data class Skill(
    val id: String,
    val name: String,
    val keywords: List<String>,
    val steps: List<String>,
    val successCount: Int
)

/**
 * 技能库。
 * 文件：filesDir/skills.json
 *
 * 规则：
 * - 同一 goal + 同一动作序列，成功 3 次才写入
 * - match 命中只打日志，不注入 prompt、不直接执行
 *   （技能是用旧界面学的，界面一改就全是毒）
 */
object SkillStore {

    private const val TAG = "SkillStore"
    private const val PROMOTE_COUNT = 3
    private const val MAX_SKILLS = 30

    private fun file(ctx: Context) = File(ctx.filesDir, "skills.json")

    // ---------------- 读取 ----------------

    private fun loadSkills(ctx: Context): List<Skill> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: return@mapNotNull null
                val steps = o.optJSONArray("steps")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }
                }.orEmpty()
                val kws = o.optJSONArray("keywords")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }
                }.orEmpty()
                Skill(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    keywords = kws,
                    steps = steps,
                    successCount = o.optInt("successCount", 0)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveSkills(ctx: Context, list: List<Skill>) {
        runCatching {
            val arr = JSONArray()
            for (s in list) {
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("keywords", JSONArray(s.keywords))
                    put("steps", JSONArray(s.steps))
                    put("successCount", s.successCount)
                })
            }
            file(ctx).writeText(arr.toString(2))
        }
    }

    // ---------------- 匹配（只打日志） ----------------

    fun match(ctx: Context, goal: String): Skill? {
        val hit = loadSkills(ctx).firstOrNull { s ->
            s.keywords.any { k -> k.length >= 2 && goal.contains(k, ignoreCase = true) }
        } ?: return null

        Log.d(TAG, "命中技能：${hit.name}（成功 ${hit.successCount} 次）")
        Log.d(TAG, "步骤序列：${hit.steps.joinToString(" → ")}")
        return hit
    }

    // ---------------- 抽取 ----------------

    /**
     * 成功轨迹才抽取。同一 goal + 同一动作序列累加成功次数，
     * 达到 3 次后固化为技能。
     */
    fun extract(ctx: Context, episodeId: String) {
        val events = EpisodeRecorder.loadEpisode(ctx, episodeId)
        if (events.isEmpty()) return

        val start = events.firstOrNull { it.optString("type") == "start" } ?: return
        val end = events.lastOrNull { it.optString("type") == "end" } ?: return

        val exitReason = end.optString("exitReason").ifBlank {
            if (end.optBoolean("success")) "done" else "unknown"
        }
        if (exitReason != "done") return

        val goal = end.optString("goal", start.optString("goal", "")).trim()
        if (goal.length < 2) return

        val steps = events.filter { it.optString("type") == "step" }
            .map { it.optString("action").trim() }
            .filter { it.isNotBlank() }
        if (steps.size < 2) return

        val list = loadSkills(ctx).toMutableList()
        val idx = list.indexOfFirst { it.name == goal && it.steps == steps }

        if (idx >= 0) {
            val old = list[idx]
            val bumped = old.copy(successCount = old.successCount + 1)
            list[idx] = bumped
            if (bumped.successCount == PROMOTE_COUNT) {
                Log.d(TAG, "技能固化：${bumped.name}（${bumped.steps.size} 步）")
            }
        } else {
            list.add(
                Skill(
                    id = "sk_" + System.currentTimeMillis(),
                    name = goal.take(40),
                    keywords = keywordsOf(goal),
                    steps = steps.take(12),
                    successCount = 1
                )
            )
        }

        val trimmed = list
            .sortedByDescending { it.successCount }
            .take(MAX_SKILLS)

        saveSkills(ctx, trimmed)
    }

    /** 从目标里切关键词，只保留 2~6 个字的片段，最多 4 个 */
    private fun keywordsOf(goal: String): List<String> {
        val cleaned = goal.replace(Regex("""[\s\p{P}]+"""), " ")
        return cleaned.split(" ")
            .map { it.trim() }
            .filter { it.length in 2..6 }
            .distinct()
            .take(4)
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}
