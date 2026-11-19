package com.whale.hand.agent

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 三层记忆里的 L3 语义层：结构化、永久保存、上限 200 条。
 *
 * 文件：filesDir/memory.jsonl，一行一条 JSON。
 * 条目：scope / text / hits / updatedAt / ep
 * - scope：global 或某个应用的包名
 * - hits：这条知识被不同任务重复印证的次数，验证权在系统，不在模型
 * - 等级由 hits 推导：1 假设、2 观察、3 及以上已验证
 *
 * 旧版 filesDir/AgentMemory.txt 在首次启动时自动迁移，旧文件保留不动。
 */
object MemoryStore {

    const val SCOPE_GLOBAL = "global"

    const val IMPORT_REPLACE = 0
    const val IMPORT_MERGE = 1
    const val IMPORT_APPEND = 2

    private const val FILE_NAME = "memory.jsonl"
    private const val LEGACY_NAME = "AgentMemory.txt"
    private const val MAX_ENTRIES = 200
    private const val MAX_HITS = 9
    private const val MAX_INJECT = 24
    private const val TEXT_LIMIT = 80
    private const val FORGET_MS = 30L * 24L * 60L * 60L * 1000L

    private val lock = Any()

    private val idPattern = Regex("""\bid\s*=\s*\d+""", RegexOption.IGNORE_CASE)
    private val coordPattern = Regex("""\(\s*\d{2,4}\s*,\s*\d{2,4}\s*\)""")
    private val hashPattern = Regex("""#\d+""")

    data class Entry(
        val scope: String,
        val text: String,
        val hits: Int,
        val updatedAt: Long,
        val ep: String
    ) {
        val level: String get() = MemoryStore.levelOf(hits)
    }

    fun levelOf(hits: Int): String = when {
        hits >= 3 -> "verified"
        hits == 2 -> "observed"
        else -> "assumed"
    }

    fun levelTag(level: String): String = when (level) {
        "verified" -> "[已验证]"
        "observed" -> "[观察]"
        else -> "[假设]"
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    // ---------------- 读取 ----------------

    fun load(ctx: Context): List<Entry> = synchronized(lock) {
        migrateIfNeeded(ctx)
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val out = ArrayList<Entry>()
        runCatching {
            f.forEachLine { line ->
                val t = line.trim()
                if (t.isNotEmpty()) {
                    val o = runCatching { JSONObject(t) }.getOrNull()
                    if (o != null) {
                        val text = o.optString("text").trim()
                        if (text.isNotEmpty()) {
                            out.add(
                                Entry(
                                    scope = o.optString("scope", SCOPE_GLOBAL)
                                        .ifBlank { SCOPE_GLOBAL },
                                    text = text,
                                    hits = o.optInt("hits", 1).coerceIn(1, MAX_HITS),
                                    updatedAt = o.optLong("updatedAt", 0L),
                                    ep = o.optString("ep")
                                )
                            )
                        }
                    }
                }
            }
        }
        out
    }

    private fun saveAll(ctx: Context, list: List<Entry>) {
        runCatching {
            val sb = StringBuilder()
            for (e in list) {
                val o = JSONObject()
                o.put("scope", e.scope)
                o.put("text", e.text)
                o.put("hits", e.hits)
                o.put("updatedAt", e.updatedAt)
                o.put("ep", e.ep)
                sb.append(o.toString()).append('\n')
            }
            file(ctx).writeText(sb.toString())
        }
    }

    private fun migrateIfNeeded(ctx: Context) {
        val f = file(ctx)
        if (f.exists()) return
        val legacy = File(ctx.filesDir, LEGACY_NAME)
        if (!legacy.exists()) return

        val now = System.currentTimeMillis()
        val list = ArrayList<Entry>()
        runCatching {
            legacy.readLines().forEach { raw ->
                var t = raw.trim().trimStart('-', '•').trim()
                var hits = 1
                if (t.startsWith("[已验证]")) {
                    hits = 3
                    t = t.removePrefix("[已验证]").trim()
                } else if (t.startsWith("[观察]")) {
                    hits = 2
                    t = t.removePrefix("[观察]").trim()
                } else if (t.startsWith("[假设]")) {
                    hits = 1
                    t = t.removePrefix("[假设]").trim()
                }
                if (t.length >= 2) {
                    list.add(Entry(SCOPE_GLOBAL, t.take(TEXT_LIMIT), hits, now, ""))
                }
            }
        }
        saveAll(ctx, trim(list, now))
    }

    /**
     * 按当前包名取记忆：通用条目 + 这个应用的条目。
     * 排序：hits 高的优先，其次新的优先。
     */
    fun recall(ctx: Context, pkg: String, limit: Int = MAX_INJECT): List<Entry> {
        val p = pkg.trim()
        return load(ctx)
            .filter { it.scope == SCOPE_GLOBAL || (p.isNotEmpty() && it.scope == p) }
            .sortedWith(compareByDescending<Entry> { it.hits }.thenByDescending { it.updatedAt })
            .take(limit)
    }

    /** 注入 prompt 的正文，空串表示还没有记忆 */
    fun recallText(ctx: Context, pkg: String): String {
        val list = recall(ctx, pkg)
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        for (e in list) {
            val scopeTag = if (e.scope == SCOPE_GLOBAL) "（通用）" else "（本应用）"
            sb.append("- ").append(levelTag(e.level)).append(scopeTag)
                .append(' ').append(e.text).append('\n')
        }
        return sb.toString().trim()
    }

    // ---------------- 写入 ----------------

    /** 去掉坐标、id、#编号；太短也丢弃 */
    fun sanitize(raw: String): String {
        val t = raw.trim().replace('\n', ' ').trim()
        if (t.length < 3) return ""
        val bad = idPattern.containsMatchIn(t) ||
            coordPattern.containsMatchIn(t) ||
            hashPattern.containsMatchIn(t)
        if (bad) return ""
        return t.take(TEXT_LIMIT)
    }

    /** 模型可以写 global: 前缀表示这条知识任何应用都成立 */
    fun splitScope(raw: String): Pair<String, String> {
        var t = raw.trim()
        val lower = t.lowercase(Locale.US)
        if (lower.startsWith("global:")) {
            t = t.substring(7).trim()
            return SCOPE_GLOBAL to t
        }
        if (t.startsWith("global：")) {
            t = t.substring(7).trim()
            return SCOPE_GLOBAL to t
        }
        if (t.startsWith("通用:")) {
            t = t.substring(3).trim()
            return SCOPE_GLOBAL to t
        }
        if (t.startsWith("通用：")) {
            t = t.substring(3).trim()
            return SCOPE_GLOBAL to t
        }
        return "" to t
    }

    /**
     * 一局结束时提交。同一 scope 同一内容再出现就是 hits+1，
     * 换了一个 episode 才算：本局自己重复写不涨 hits。
     * @return 实际写入或刷新的条数
     */
    fun commit(ctx: Context, notes: List<Pair<String, String>>, pkg: String, episode: String): Int {
        if (notes.isEmpty()) return 0
        synchronized(lock) {
            val list = load(ctx).toMutableList()
            val now = System.currentTimeMillis()
            var touched = 0
            for ((scopeRaw, textRaw) in notes) {
                val text = sanitize(textRaw)
                if (text.isEmpty()) continue
                val scope = scopeRaw.ifBlank { pkg.trim().ifBlank { SCOPE_GLOBAL } }
                val key = norm(text)
                val idx = list.indexOfFirst { it.scope == scope && norm(it.text) == key }
                if (idx >= 0) {
                    val old = list[idx]
                    val sameEpisode = episode.isNotBlank() && old.ep == episode
                    val hits = if (sameEpisode) old.hits else (old.hits + 1).coerceAtMost(MAX_HITS)
                    list[idx] = old.copy(
                        hits = hits,
                        updatedAt = now,
                        ep = old.ep.ifBlank { episode }
                    )
                    touched++
                } else {
                    list.add(Entry(scope, text, 1, now, episode))
                    touched++
                }
            }
            saveAll(ctx, trim(list, now))
            return touched
        }
    }

    /**
     * 案例卡生成后回来对一遍 L3：卡里出现过的知识 hits+1，
     * 同一批知识被三次不同任务走通，就自动升到已验证。
     * @return 被提升的条数
     */
    fun reinforceByCase(ctx: Context, pkg: String, path: String, pitfall: String): Int {
        val p = pkg.trim()
        val probe = norm("$path $pitfall")
        if (probe.length < 2) return 0
        val probeGrams = bigrams(probe)
        if (probeGrams.isEmpty()) return 0

        synchronized(lock) {
            val list = load(ctx).toMutableList()
            val now = System.currentTimeMillis()
            var bumped = 0
            for (i in list.indices) {
                val e = list[i]
                if (e.scope != SCOPE_GLOBAL && (p.isEmpty() || e.scope != p)) continue
                val sim = jaccard(probeGrams, bigrams(norm(e.text)))
                if (sim >= 0.2) {
                    list[i] = e.copy(
                        hits = (e.hits + 1).coerceAtMost(MAX_HITS),
                        updatedAt = now
                    )
                    bumped++
                }
            }
            if (bumped > 0) saveAll(ctx, trim(list, now))
            return bumped
        }
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            runCatching { file(ctx).delete() }
        }
    }

    fun count(ctx: Context): Int = load(ctx).size

    // ---------------- 手工编辑 ----------------

    /** 面板里显示的文本，一行一条：scope :: 内容 */
    fun editText(ctx: Context): String {
        val list = load(ctx)
            .sortedWith(compareByDescending<Entry> { it.hits }.thenByDescending { it.updatedAt })
        val sb = StringBuilder()
        for (e in list) {
            sb.append(e.scope).append(" :: ").append(e.text).append('\n')
        }
        return sb.toString().trim()
    }

    /** 面板保存：没有 :: 的行按通用记忆算，hits 相同的保留原有等级 */
    fun saveFromText(ctx: Context, text: String): Int {
        synchronized(lock) {
            val old = load(ctx)
            val now = System.currentTimeMillis()
            val out = ArrayList<Entry>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val sep = line.indexOf("::")
                val scope: String
                var body: String
                if (sep > 0) {
                    scope = line.substring(0, sep).trim().ifBlank { SCOPE_GLOBAL }
                    body = line.substring(sep + 2).trim()
                } else {
                    scope = SCOPE_GLOBAL
                    body = line
                }
                if (body.length < 2) continue
                body = body.take(TEXT_LIMIT)
                val key = norm(body)
                val prev = old.firstOrNull { it.scope == scope && norm(it.text) == key }
                out.add(
                    Entry(
                        scope = scope,
                        text = body,
                        hits = prev?.hits ?: 1,
                        updatedAt = prev?.updatedAt ?: now,
                        ep = prev?.ep ?: ""
                    )
                )
            }
            saveAll(ctx, trim(out, now))
            return out.size
        }
    }

    // ---------------- 导出 / 导入 ----------------

    fun exportJson(ctx: Context): String {
        val arr = JSONArray()
        for (e in load(ctx)) {
            val o = JSONObject()
            o.put("scope", e.scope)
            o.put("text", e.text)
            o.put("hits", e.hits)
            o.put("level", e.level)
            o.put("updatedAt", e.updatedAt)
            arr.put(o)
        }
        val root = JSONObject()
        root.put("app", "WhaleHand")
        root.put("version", 2)
        root.put("device", "${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
        root.put("updated", System.currentTimeMillis())
        root.put("entries", arr)
        return root.toString(2)
    }

    fun exportMarkdown(ctx: Context): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val list = load(ctx)
            .sortedWith(compareByDescending<Entry> { it.hits }.thenByDescending { it.updatedAt })
        val body = if (list.isEmpty()) {
            "（空）"
        } else {
            list.joinToString("\n") { e ->
                val scopeTag = if (e.scope == SCOPE_GLOBAL) "通用" else e.scope
                "- ${levelTag(e.level)} [$scopeTag] ${e.text}"
            }
        }
        return "# WhaleHand 设备记忆\n" +
            "\n" +
            "- 设备：${Build.MODEL} / Android ${Build.VERSION.RELEASE}\n" +
            "- 导出时间：$stamp\n" +
            "- 条数：${list.size}\n" +
            "\n" +
            body + "\n"
    }

    /**
     * 兼容两种格式：V1.5 的 entries 数组，和 V1.4 及以前的 memory 纯文本。
     * @return 实际写入或合并的条数
     */
    fun importJson(ctx: Context, raw: String, mode: Int): Int {
        val incoming = ArrayList<Entry>()
        val root = runCatching { JSONObject(raw) }.getOrNull()

        if (root != null && root.has("entries")) {
            val arr = root.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("text").trim()
                if (t.length < 2) continue
                incoming.add(
                    Entry(
                        scope = o.optString("scope", SCOPE_GLOBAL).ifBlank { SCOPE_GLOBAL },
                        text = t.take(TEXT_LIMIT),
                        hits = o.optInt("hits", 1).coerceIn(1, MAX_HITS),
                        updatedAt = System.currentTimeMillis(),
                        ep = ""
                    )
                )
            }
        } else {
            val text = root?.optString("memory").orEmpty().ifBlank { raw }
            for (line in text.lines()) {
                var t = line.trim().trimStart('-', '•').trim()
                var hits = 1
                if (t.startsWith("[已验证]")) {
                    hits = 3
                    t = t.removePrefix("[已验证]").trim()
                } else if (t.startsWith("[观察]")) {
                    hits = 2
                    t = t.removePrefix("[观察]").trim()
                } else if (t.startsWith("[假设]")) {
                    hits = 1
                    t = t.removePrefix("[假设]").trim()
                }
                if (t.length >= 2) {
                    incoming.add(
                        Entry(SCOPE_GLOBAL, t.take(TEXT_LIMIT), hits, System.currentTimeMillis(), "")
                    )
                }
            }
        }

        if (incoming.isEmpty()) return 0

        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (mode == IMPORT_REPLACE) {
                saveAll(ctx, trim(incoming, now))
                return incoming.size
            }
            val list = load(ctx).toMutableList()
            var added = 0
            for (e in incoming) {
                val key = norm(e.text)
                val idx = list.indexOfFirst { it.scope == e.scope && norm(it.text) == key }
                if (idx >= 0) {
                    val old = list[idx]
                    if (mode == IMPORT_MERGE && e.hits > old.hits) {
                        list[idx] = old.copy(hits = e.hits, updatedAt = now)
                        added++
                    }
                } else {
                    list.add(e.copy(updatedAt = now))
                    added++
                }
            }
            saveAll(ctx, trim(list, now))
            return added
        }
    }

    // ---------------- 淘汰与工具 ----------------

    /** 30 天没用过且 hits 小于 2 的丢掉，总量超过 200 时按 hits 和新鲜度砍 */
    private fun trim(list: List<Entry>, now: Long): List<Entry> {
        val alive = list.filter { e -> e.hits >= 2 || now - e.updatedAt < FORGET_MS }
        if (alive.size <= MAX_ENTRIES) return alive
        return alive
            .sortedWith(compareByDescending<Entry> { it.hits }.thenByDescending { it.updatedAt })
            .take(MAX_ENTRIES)
    }

    private fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            var c = ch
            if (c.code in 0xFF01..0xFF5E) c = (c.code - 0xFEE0).toChar()
            if (c.isWhitespace()) continue
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    private fun bigrams(s: String): Set<String> {
        if (s.isEmpty()) return emptySet()
        if (s.length < 2) return setOf(s)
        val out = HashSet<String>(s.length)
        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        return out
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var inter = 0
        for (x in a) if (b.contains(x)) inter++
        val union = a.size + b.size - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union
    }
}
