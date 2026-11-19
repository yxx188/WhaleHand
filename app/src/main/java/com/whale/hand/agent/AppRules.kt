package com.whale.hand.agent

import android.content.Context
import android.content.SharedPreferences

/** 应用黑白名单：命中即停止任务 */
object AppRules {

    private const val PREF = "app_rules"
    private lateinit var sp: SharedPreferences

    private val defaultBlocked = setOf(
        "com.eg.android.AlipayGphone",   // 支付宝
        "com.unionpay",                  // 云闪付
        "com.icbc", "com.ccb", "com.abchina", "com.bankcomm",
        "com.cmbchina", "com.chinamworld.main", "com.spdb",
        "com.tencent.androidqqmail"      // 示例，可在界面里删
    )

    fun init(context: Context) {
        sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    var blocked: Set<String>
        get() = sp.getStringSet("blocked", defaultBlocked) ?: defaultBlocked
        set(v) { sp.edit().putStringSet("blocked", v).apply() }

    fun isBlocked(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return blocked.any { pkg == it || pkg.startsWith("$it.") }
    }

    fun add(pkg: String) {
        val p = pkg.trim()
        if (p.isNotBlank()) blocked = blocked + p
    }

    fun remove(pkg: String) {
        blocked = blocked - pkg.trim()
    }

    fun reset() {
        blocked = defaultBlocked
    }
}
