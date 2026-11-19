package com.whale.hand.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条聊天消息。role: user / assistant / task */
data class ChatMsg(
    val role: String,
    val text: String,
    val ts: Long = 0L,
    /** 非空表示这条回复附带一个可执行目标 */
    val task: String? = null,
    /** 模型思考过程（reasoning_content），用于折叠展示 */
    val reasoning: String? = null
)

/**
 * 聊天记录落盘：filesDir/chats/<sessionId>.jsonl + index.json
 * 单会话最多 200 条，会话最多 20 个，超出按更新时间淘汰。
 * 标题：默认取首条用户消息前 12 字，用户手动改过后标记 titleEdited，不再被自动覆盖。
 */
object ChatStore {

    private const val MAX_MSGS = 200
    private const val MAX_SESSIONS = 20
    private const val PREF = "whale_chat"
    private const val KEY_CURRENT = "current_session"

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "chats")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun fileOf(ctx: Context, sid: String): File = File(dir(ctx), sid + ".jsonl")

    private fun indexFile(ctx: Context): File = File(dir(ctx), "index.json")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun newSessionId(): String = "c_" + System.currentTimeMillis()

    // ---------------- 当前会话 ----------------

    fun currentSessionId(ctx: Context): String? =
        prefs(ctx).getString(KEY_CURRENT, "").orEmpty().takeIf { it.isNotBlank() }

    fun setCurrentSession(ctx: Context, sid: String) {
        prefs(ctx).edit().putString(KEY_CURRENT, sid).apply()
    }

    // ---------------- 消息 ----------------

    fun append(ctx: Context, sid: String, msg: ChatMsg): Boolean = runCatching {
        val ts = if (msg.ts > 0L) msg.ts else System.currentTimeMillis()
        val o = JSONObject()
        o.put("role", msg.role)
        o.put("text", msg.text)
        o.put("ts", ts)
        if (!msg.task.isNullOrBlank()) o.put("task", msg.task)
        if (!msg.reasoning.isNullOrBlank()) o.put("reasoning", msg.reasoning)
        fileOf(ctx, sid).appendText(o.toString() + "\n")
        trimFile(ctx, sid)
        touchIndex(ctx, sid, msg, ts)
        true
    }.getOrDefault(false)

    fun load(ctx: Context, sid: String): List<ChatMsg> {
        val f = fileOf(ctx, sid)
        if (!f.exists()) return emptyList()
        val out = ArrayList<ChatMsg>()
        runCatching {
            f.forEachLine { line ->
                val t = line.trim()
                if (t.isEmpty()) return@forEachLine
                runCatching {
                    val o = JSONObject(t)
                    out.add(
                        ChatMsg(
                            role = o.optString("role", "assistant"),
                            text = o.optString("text", ""),
                            ts = o.optLong("ts", 0L),
                            task = o.optString("task", "").takeIf { it.isNotBlank() },
                            reasoning = o.optString("reasoning", "")
                                .replace("null", "").trim()
                                .takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
        return out
    }

    private fun trimFile(ctx: Context, sid: String) {
        val f = fileOf(ctx, sid)
        if (!f.exists()) return
        runCatching {
            val lines = f.readLines().filter { it.isNotBlank() }
            if (lines.size <= MAX_MSGS) return
            f.writeText(lines.takeLast(MAX_MSGS).joinToString("\n") + "\n")
        }
    }

    // ---------------- 会话索引 ----------------

    fun sessions(ctx: Context): List<JSONObject> {
        val f = indexFile(ctx)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    fun latestSession(ctx: Context): String? =
        sessions(ctx).firstOrNull()?.optString("id")?.takeIf { it.isNotBlank() }

    fun title(ctx: Context, sid: String): String =
        sessions(ctx).firstOrNull { it.optString("id") == sid }
            ?.optString("title")
            .orEmpty()
            .ifBlank { "新对话" }

    fun rename(ctx: Context, sid: String, newTitle: String) {
        val t = newTitle.trim().take(24)
        if (t.isEmpty()) return
        runCatching {
            val list = sessions(ctx).toMutableList()
            val i = list.indexOfFirst { it.optString("id") == sid }
            if (i < 0) return
            list[i].put("title", t)
            list[i].put("titleEdited", true)
            writeIndex(ctx, list)
        }
    }

    fun delete(ctx: Context, sid: String) {
        runCatching {
            val list = sessions(ctx).toMutableList()
            list.removeAll { it.optString("id") == sid }
            writeIndex(ctx, list)
            fileOf(ctx, sid).delete()
        }
    }

    fun clearAll(ctx: Context) {
        runCatching { dir(ctx).listFiles()?.forEach { it.delete() } }
    }

    private fun writeIndex(ctx: Context, list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        runCatching { indexFile(ctx).writeText(arr.toString()) }
    }

    private fun touchIndex(ctx: Context, sid: String, msg: ChatMsg, ts: Long) {
        runCatching {
            val list = sessions(ctx).toMutableList()
            val old = list.firstOrNull { it.optString("id") == sid }
            val oldTitle = old?.optString("title").orEmpty()
            val edited = old?.optBoolean("titleEdited", false) ?: false
            list.removeAll { it.optString("id") == sid }

            val entry = JSONObject()
            entry.put("id", sid)
            entry.put("title", if (edited || oldTitle.isNotBlank()) oldTitle.ifBlank { titleOf(msg) } else titleOf(msg))
            entry.put("titleEdited", edited)
            entry.put("updatedAt", ts)
            list.add(0, entry)

            while (list.size > MAX_SESSIONS) {
                val drop = list.removeAt(list.size - 1)
                runCatching { fileOf(ctx, drop.optString("id")).delete() }
            }
            writeIndex(ctx, list)
        }
    }

    private fun titleOf(msg: ChatMsg): String {
        if (msg.role != "user") return "新对话"
        val t = msg.text.replace("\n", " ").trim()
        return if (t.length <= 12) t else t.substring(0, 12)
    }
}
