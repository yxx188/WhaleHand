package com.whale.hand.agent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 按任务分组的日志，最多保留 10 份。
 *
 * 文件：filesDir/tasklogs/<id>.jsonl
 * 第一行是头部 JSON：id / goal / startAt / endAt / steps / exitReason / verdict
 * 之后每行是一句日志正文，原样给人看。
 *
 * verdict：0 未判定，1 满意，2 不满意。人点过之后以它为准。
 */
object TaskLogStore {

    const val VERDICT_NONE = 0
    const val VERDICT_OK = 1
    const val VERDICT_BAD = 2

    private const val MAX_FILES = 10

    private val lock = Any()
    private var currentId = ""

    data class TaskLog(
        val id: String,
        val goal: String,
        val startAt: Long,
        val endAt: Long,
        val steps: Int,
        val exitReason: String,
        val verdict: Int,
        val lineCount: Int
    )

    private fun dir(ctx: Context) = File(ctx.filesDir, "tasklogs")

    private fun file(ctx: Context, id: String) = File(dir(ctx), "$id.jsonl")

    fun current(): String = currentId

    fun begin(ctx: Context, goal: String): String {
        val id = "t_" + System.currentTimeMillis()
        currentId = id
        runCatching { dir(ctx).mkdirs() }
        val head = JSONObject()
        head.put("id", id)
        head.put("goal", goal.take(120))
        head.put("startAt", System.currentTimeMillis())
        head.put("endAt", 0L)
        head.put("steps", 0)
        head.put("exitReason", "")
        head.put("verdict", VERDICT_NONE)
        runCatching { file(ctx, id).writeText(head.toString() + "\n") }
        prune(ctx)
        return id
    }

    fun line(ctx: Context, text: String) {
        val id = currentId
        if (id.isBlank() || text.isBlank()) return
        val body = text.replace('\n', ' ').take(400)
        synchronized(lock) {
            runCatching {
                val f = file(ctx, id)
                if (f.exists()) f.appendText(body + "\n")
            }
        }
    }

    fun end(ctx: Context, exitReason: String, steps: Int) {
        val id = currentId
        if (id.isBlank()) return
        synchronized(lock) {
            runCatching {
                val f = file(ctx, id)
                if (f.exists()) {
                    val lines = f.readLines().toMutableList()
                    if (lines.isNotEmpty()) {
                        val head = runCatching { JSONObject(lines[0]) }.getOrNull()
                        if (head != null) {
                            head.put("endAt", System.currentTimeMillis())
                            head.put("steps", steps)
                            head.put("exitReason", exitReason)
                            lines[0] = head.toString()
                            f.writeText(lines.joinToString("\n") + "\n")
                        }
                    }
                }
            }
        }
        currentId = ""
    }

    fun list(ctx: Context): List<TaskLog> {
        val d = dir(ctx)
        if (!d.isDirectory) return emptyList()
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching {
                val lines = f.readLines()
                val head = if (lines.isNotEmpty()) JSONObject(lines[0]) else JSONObject()
                TaskLog(
                    id = head.optString("id").ifBlank { f.nameWithoutExtension },
                    goal = head.optString("goal"),
                    startAt = head.optLong("startAt", f.lastModified()),
                    endAt = head.optLong("endAt", 0L),
                    steps = head.optInt("steps", 0),
                    exitReason = head.optString("exitReason"),
                    verdict = head.optInt("verdict", VERDICT_NONE),
                    lineCount = (lines.size - 1).coerceAtLeast(0)
                )
            }.getOrNull()
        }.sortedByDescending { it.startAt }
    }

    fun read(ctx: Context, id: String): String {
        val f = file(ctx, id)
        if (!f.exists()) return ""
        return runCatching {
            val lines = f.readLines()
            if (lines.isEmpty()) "" else lines.drop(1).joinToString("\n")
        }.getOrDefault("")
    }

    fun setVerdict(ctx: Context, id: String, verdict: Int) {
        synchronized(lock) {
            runCatching {
                val f = file(ctx, id)
                if (f.exists()) {
                    val lines = f.readLines().toMutableList()
                    if (lines.isNotEmpty()) {
                        val head = runCatching { JSONObject(lines[0]) }.getOrNull()
                        if (head != null) {
                            head.put("verdict", verdict)
                            lines[0] = head.toString()
                            f.writeText(lines.joinToString("\n") + "\n")
                        }
                    }
                }
            }
        }
    }

    fun delete(ctx: Context, id: String) {
        synchronized(lock) {
            runCatching { file(ctx, id).delete() }
        }
    }

    fun prune(ctx: Context) {
        val d = dir(ctx)
        if (!d.isDirectory) return
        val files = d.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES)
            .forEach { old -> runCatching { old.delete() } }
    }

    /** 历史列表里显示的一行字 */
    fun label(log: TaskLog): String {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val mark = when (log.verdict) {
            VERDICT_OK -> "✓ "
            VERDICT_BAD -> "✗ "
            else -> "· "
        }
        val head = when {
            log.verdict == VERDICT_OK -> "完成"
            log.verdict == VERDICT_BAD -> "不满意"
            log.exitReason == "done" -> "完成"
            log.exitReason == "manual_stop" -> "手动停止"
            log.exitReason == "step_limit" -> "步数用尽"
            log.exitReason == "no_progress" -> "没进展"
            log.exitReason == "blocked" -> "被拦下"
            log.exitReason == "error" -> "出错"
            else -> "结束"
        }
        val goal = log.goal.ifBlank { "（没有目标）" }.take(18)
        return "$mark${fmt.format(Date(log.startAt))}  $goal  ${log.steps}步 · $head"
    }
}
