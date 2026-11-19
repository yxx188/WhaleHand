package com.whale.hand.agent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** 一个模型档案：替换原来「只能存一套模型配置」的单配置文件模型 */
data class ModelProfile(
    val id: String,
    val name: String,
    val apiUrl: String,
    val apiKey: String,
    val model: String,
    val kind: String = KIND_TEXT,
    /** 思考强度原生支持方式：none / deepseek（thinking.type）/ effort（reasoning_effort） */
    val thinkingMode: String = THINK_NONE,
    /** 自定义原生参数（JSON 字符串，暂不使用，留给以后扩展） */
    val nativeParams: String = "",
    /** 该模型默认档位 0..4：轻度/标准/深度/pro/ultra */
    val defaultEffort: Int = 1,
    /** 上次使用的档位，-1 = 跟随默认 */
    val lastEffort: Int = -1
) {
    companion object {
        const val KIND_TEXT = "text"
        const val KIND_VISION = "vision"
        const val THINK_NONE = "none"
        const val THINK_DEEPSEEK = "deepseek"
        const val THINK_EFFORT = "effort"
    }
}

/**
 * 模型档案仓库：SharedPreferences 里存一个 JSON 列表 + currentId。
 * 首次启动会把旧的单模型配置（whale_config 里的 api_url / api_key / model）迁成「默认模型」档案，
 * 视觉外挂配置也迁成一个 kind=vision 的档案，不让用户重填。
 */
object ModelStore {

    private const val PREF = "whale_models"
    private const val KEY_LIST = "profiles"
    private const val KEY_CURRENT = "current_id"

    private lateinit var sp: SharedPreferences
    private val cache = ArrayList<ModelProfile>()
    private var currentId: String = ""

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        load()
        migrateIfEmpty(ctx)
    }

    private fun load() {
        cache.clear()
        currentId = sp.getString(KEY_CURRENT, "").orEmpty()
        val raw = sp.getString(KEY_LIST, "").orEmpty()
        if (raw.isNotBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) cache.add(fromJson(arr.getJSONObject(i)))
            }
        }
    }

    private fun migrateIfEmpty(ctx: Context) {
        if (cache.isNotEmpty()) return
        val old = ctx.getSharedPreferences("whale_config", Context.MODE_PRIVATE)
        val url = old.getString("api_url", "").orEmpty()
        val key = old.getString("api_key", "").orEmpty()
        val model = old.getString("model", "").orEmpty()
        val vUrl = old.getString("vision_url", "").orEmpty()
        val vKey = old.getString("vision_key", "").orEmpty()
        val vModel = old.getString("vision_model", "").orEmpty()

        if (url.isNotBlank() || model.isNotBlank()) {
            val p = ModelProfile(
                id = "p_default",
                name = "默认模型",
                apiUrl = url.ifBlank { "https://api.deepseek.com/chat/completions" },
                apiKey = key,
                model = model.ifBlank { "deepseek-flash" },
                kind = ModelProfile.KIND_TEXT,
                thinkingMode = ModelProfile.THINK_DEEPSEEK,
                defaultEffort = 1
            )
            cache.add(p)
            currentId = p.id
        }
        if (vModel.isNotBlank()) {
            val vp = ModelProfile(
                id = "p_vision",
                name = "视觉外挂",
                apiUrl = vUrl.ifBlank { "https://open.bigmodel.cn/api/paas/v4/chat/completions" },
                apiKey = vKey,
                model = vModel,
                kind = ModelProfile.KIND_VISION,
                thinkingMode = ModelProfile.THINK_NONE
            )
            cache.add(vp)
        }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        cache.forEach { arr.put(toJson(it)) }
        sp.edit().putString(KEY_LIST, arr.toString()).putString(KEY_CURRENT, currentId).apply()
    }

    private fun fromJson(o: JSONObject) = ModelProfile(
        id = o.optString("id"),
        name = o.optString("name"),
        apiUrl = o.optString("apiUrl"),
        apiKey = o.optString("apiKey"),
        model = o.optString("model"),
        kind = o.optString("kind", ModelProfile.KIND_TEXT),
        thinkingMode = o.optString("thinkingMode", ModelProfile.THINK_NONE),
        nativeParams = o.optString("nativeParams", ""),
        defaultEffort = o.optInt("defaultEffort", 1),
        lastEffort = o.optInt("lastEffort", -1)
    )

    private fun toJson(p: ModelProfile) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("apiUrl", p.apiUrl)
        put("apiKey", p.apiKey)
        put("model", p.model)
        put("kind", p.kind)
        put("thinkingMode", p.thinkingMode)
        put("nativeParams", p.nativeParams)
        put("defaultEffort", p.defaultEffort)
        put("lastEffort", p.lastEffort)
    }

    // ---------------- 查询 ----------------

    fun profiles(): List<ModelProfile> = cache.toList()

    fun textProfiles(): List<ModelProfile> =
        cache.filter { it.kind != ModelProfile.KIND_VISION }

    fun visionProfiles(): List<ModelProfile> =
        cache.filter { it.kind == ModelProfile.KIND_VISION }

    fun get(id: String): ModelProfile? = cache.firstOrNull { it.id == id }

    fun current(): ModelProfile? {
        if (cache.isEmpty()) return null
        return get(currentId) ?: textProfiles().firstOrNull() ?: cache.first()
    }

    fun currentId(): String = current()?.id ?: ""

    fun setCurrent(id: String) {
        if (cache.any { it.id == id }) {
            currentId = id
            save()
        }
    }

    // ---------------- 修改 ----------------

    fun newId(): String = "p_" + System.currentTimeMillis()

    fun upsert(p: ModelProfile) {
        val i = cache.indexOfFirst { it.id == p.id }
        if (i >= 0) cache[i] = p else cache.add(p)
        if (currentId.isBlank()) currentId = p.id
        save()
    }

    /** 至少保留一个文本模型；删当前模型会自动切到第一个 */
    fun delete(id: String): Boolean {
        val target = get(id) ?: return false
        if (target.kind != ModelProfile.KIND_VISION && textProfiles().size <= 1) return false
        cache.removeAll { it.id == id }
        if (currentId == id) {
            currentId = textProfiles().firstOrNull()?.id ?: ""
        }
        save()
        return true
    }

    fun ensureAtLeastOne(): Boolean = textProfiles().isNotEmpty()

    // ---------------- 思考强度 ----------------

    /** 该模型应使用的档位：上次用过就用上次的，否则用默认档 */
    fun effortFor(id: String, fallback: Int = 1): Int {
        val p = get(id) ?: return fallback
        return if (p.lastEffort in 0..4) p.lastEffort else p.defaultEffort.coerceIn(0, 4)
    }

    fun setLastEffort(id: String, level: Int) {
        val p = get(id) ?: return
        if (level !in 0..4) return
        upsert(p.copy(lastEffort = level))
    }

    fun resetLastEffort(id: String) {
        val p = get(id) ?: return
        upsert(p.copy(lastEffort = -1))
    }
}
