package com.whale.hand.agent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 任务过程记录：每个任务一行 start、每步一行 step、结尾一行 end。
 * 文件位置：filesDir/episodes/yyyyMMdd.jsonl
 * V1.2 只负责记录，V1.3 用它做回放、评估、案例生成。
 */
object EpisodeRecorder {

    private val lock = Any()

    private var episodeId: String = ""
    private var startedAt: Long = 0L
    private var goal: String = ""
    private var doneWhen: String = ""
    private var stepCount: Int = 0

    fun start(ctx: Context, goal: String, doneWhen: String) {
        if (!AgentConfig.recordEpisodes) return

        episodeId = "ep_" + System.currentTimeMillis()
        startedAt = System.currentTimeMillis()
        this.goal = goal
        this.doneWhen = doneWhen
        stepCount = 0

        append(ctx, JSONObject().apply {
            put("ep", episodeId)
            put("ts", startedAt)
            put("type", "start")
            put("goal", goal)
            put("doneWhen", doneWhen)
        })
    }

    fun step(
        ctx: Context,
        step: Int,
        pkg: String,
        tree: String,
        action: String,
        result: String,
        durationMs: Long
    ) {
        if (!AgentConfig.recordEpisodes) return

        stepCount = step
        append(ctx, JSONObject().apply {
            put("ep", episodeId)
            put("ts", System.currentTimeMillis())
            put("type", "step")
            put("step", step)
            put("pkg", pkg)
            put("tree", tree.replace("\n", " ").take(200))
            put("action", action)
            put("result", result)
            put("ms", durationMs)
        })
    }

    /**
     * @param success 兼容旧字段，仍写入
     * @param exitReason V1.3 枚举值；空则由 success 推导
     */
    fun finish(
        ctx: Context,
        success: Boolean,
        reason: String,
        exitReason: String = ""
    ) {
        if (!AgentConfig.recordEpisodes) return

        val er = exitReason.ifBlank { if (success) "done" else "unknown" }
        val end = System.currentTimeMillis()
        append(ctx, JSONObject().apply {
            put("ep", episodeId)
            put("ts", end)
            put("type", "end")
            put("goal", goal)
            put("doneWhen", doneWhen)
            put("success", success)
            put("exitReason", er)
            put("reason", reason)
            put("steps", stepCount)
            put("startedAt", startedAt)
            put("endedAt", end)
        })
        episodeId = ""
    }

    /** 给 V1.3 用 */
    fun currentEpisodeId(): String = episodeId

    /**
     * 按 epId 读取一整局的记录。
     * 不猜日期：扫 episodes/ 最近 3 个文件按 ep 匹配，天然兼容跨零点任务。
     * 只读盘，不碰任何单例字段，可安全在子线程调用。
     */
    fun loadEpisode(ctx: Context, epId: String): List<JSONObject> {
        if (epId.isBlank()) return emptyList()

        val dir = File(ctx.filesDir, "episodes")
        if (!dir.isDirectory) return emptyList()

        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(3)
            ?: return emptyList()

        val out = ArrayList<JSONObject>()
        for (f in files) {
            runCatching {
                f.forEachLine { line ->
                    val t = line.trim()
                    if (t.isBlank()) return@forEachLine
                    val o = runCatching { JSONObject(t) }.getOrNull()
                        ?: return@forEachLine
                    if (o.optString("ep") == epId) out.add(o)
                }
            }
        }
        out.sortBy { it.optLong("ts") }
        return out
    }

    private fun append(ctx: Context, obj: JSONObject) {
        synchronized(lock) {
            runCatching {
                val dir = File(ctx.filesDir, "episodes").apply { mkdirs() }
                val name = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                File(dir, "$name.jsonl").appendText(obj.toString() + "\n")
            }
        }
    }
}
