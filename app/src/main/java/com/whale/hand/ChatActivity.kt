package com.whale.hand

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.whale.hand.agent.AgentConfig
import com.whale.hand.agent.Effort
import com.whale.hand.agent.ModelStore
import com.whale.hand.agent.TaskRunner
import com.whale.hand.agent.VisionClient
import com.whale.hand.chat.BrainReply
import com.whale.hand.chat.ChatBrain
import com.whale.hand.chat.ChatMsg
import com.whale.hand.chat.ChatStore
import com.whale.hand.databinding.ActivityChatBinding
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * V2.0 聊天主页（深海极光重构版）：
 * 顶部：左菜单 / 中对话名 / 右新建；左侧抽屉：会话列表 + 任务台 / 设置 / 关于；
 * 底部 Composer：常驻两行输入 + 附件 / 模型·强度 / 发送。
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GOAL = "extra_goal"
        const val EXTRA_SESSION = "extra_session"
        private const val MAX_ATTACH = 5
    }

    private data class Attachment(val uri: Uri, val name: String, val isImage: Boolean)

    private lateinit var binding: ActivityChatBinding
    private var sessionId: String = ""
    private var thinking = false
    private val attachments = ArrayList<Attachment>()

    // 思考过程实时展示
    private var liveThinkingCard: View? = null
    private var liveThinkingBody: TextView? = null
    private var liveThinkingScroll: ScrollView? = null
    private var liveThinkingHeader: TextView? = null
    private var liveReasoning: String = ""

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { addAttachment(it, true) }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { addAttachment(it, false) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionId = intent.getStringExtra(EXTRA_SESSION)
            ?: ChatStore.currentSessionId(this)
            ?: ChatStore.latestSession(this)
            ?: ChatStore.newSessionId()
        ChatStore.setCurrentSession(this, sessionId)

        binding.btnMenu.setOnClickListener { openDrawer() }
        binding.btnNewChat.setOnClickListener { newChat() }
        binding.tvTitle.setOnLongClickListener { renameSession(sessionId); true }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnPrompt.setOnClickListener { showPromptSheet() }
        binding.btnDesk.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).putExtra(EXTRA_SESSION, sessionId))
        }
        binding.btnAbout.setOnClickListener { showAbout() }
        binding.btnAttach.setOnClickListener { showAttachSheet() }
        binding.btnModelEffort.setOnClickListener { showEffortSheet() }
        binding.btnSend.setOnClickListener { send() }

        binding.etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateSendEnabled()
        })

        binding.tvDrawerFooter.text = "v" + BuildConfig.VERSION_NAME + " · AI 可能出错"
        renderSessions()
        render()
        updateHeaderChip()
        updateSendEnabled()
    }

    override fun onResume() {
        super.onResume()
        renderSessions()
        render()
        updateHeaderChip()
    }

    override fun onBackPressed() {
        if (binding.drawer.isDrawerOpen(GravityCompat.START)) {
            binding.drawer.closeDrawers()
        } else {
            super.onBackPressed()
        }
    }

    // ---------------- 抽屉 / 会话 ----------------

    private fun openDrawer() {
        renderSessions()
        binding.drawer.openDrawer(GravityCompat.START)
    }

    private fun renderSessions() {
        binding.llSessions.removeAllViews()
        val list = ChatStore.sessions(this)
        if (list.isEmpty()) {
            val tv = TextView(this)
            tv.text = "还没有会话，点右上角新建一个"
            tv.textSize = 13f
            tv.setTextColor(color(R.color.text_secondary))
            tv.setPadding(dp(10), dp(20), dp(10), dp(20))
            binding.llSessions.addView(tv)
            return
        }
        for (o in list) {
            val sid = o.optString("id")
            val title = o.optString("title").ifBlank { "新对话" }
            val item = layoutInflater.inflate(R.layout.item_drawer_session, binding.llSessions, false)
            item.findViewById<TextView>(R.id.tvSessionTitle).text = title
            item.findViewById<TextView>(R.id.tvSessionTime).text = formatTime(o.optLong("updatedAt", 0L))
            if (sid == sessionId) item.setBackgroundResource(R.drawable.bg_session_active)
            item.setOnClickListener { switchSession(sid) }
            item.setOnLongClickListener { showSessionMenu(sid, title); true }
            binding.llSessions.addView(item)
        }
    }

    private fun switchSession(sid: String) {
        if (sid == sessionId) {
            binding.drawer.closeDrawers()
            return
        }
        sessionId = sid
        ChatStore.setCurrentSession(this, sid)
        binding.drawer.closeDrawers()
        render()
        renderSessions()
        updateTitle()
    }

    private fun newChat() {
        if (ChatStore.load(this, sessionId).isEmpty()) {
            binding.drawer.closeDrawers()
            toast("当前已经是新对话")
            return
        }
        sessionId = ChatStore.newSessionId()
        ChatStore.setCurrentSession(this, sessionId)
        binding.drawer.closeDrawers()
        attachments.clear()
        renderAttachments()
        render()
        renderSessions()
        updateTitle()
    }

    private fun showSessionMenu(sid: String, title: String) {
        val items = arrayOf("重命名", "删除")
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                if (which == 0) renameSession(sid) else confirmDeleteSession(sid)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renameSession(sid: String) {
        val input = EditText(this)
        input.setText(ChatStore.title(this, sid))
        input.setTextColor(color(R.color.text_primary))
        input.setHintTextColor(color(R.color.text_tertiary))
        input.setSingleLine(true)
        input.setPadding(dp(18), dp(14), dp(18), dp(14))
        input.background = ContextCompat.getDrawable(this, R.drawable.bg_input)
        val holder = FrameLayout(this)
        holder.setPadding(dp(20), dp(8), dp(20), dp(0))
        holder.addView(
            input,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("重命名对话")
            .setView(holder)
            .setPositiveButton("保存") { _, _ ->
                ChatStore.rename(this, sid, input.text?.toString().orEmpty())
                updateTitle()
                renderSessions()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteSession(sid: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除这个对话？")
            .setMessage("删除后聊天记录不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                ChatStore.delete(this, sid)
                if (sid == sessionId) {
                    sessionId = ChatStore.latestSession(this) ?: ChatStore.newSessionId()
                    ChatStore.setCurrentSession(this, sessionId)
                    render()
                }
                renderSessions()
                updateTitle()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatTime(ts: Long): String {
        if (ts <= 0L) return "刚刚"
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val day = 24 * 60 * 60 * 1000L
        return when {
            now - ts < day -> fmt.format(Date(ts))
            now - ts < 2 * day -> "昨天"
            else -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(ts))
        }
    }

    // ---------------- 顶部 ----------------

    private fun updateTitle() {
        binding.tvTitle.text = ChatStore.title(this, sessionId)
    }

    private fun updateHeaderChip() {
        val p = ModelStore.current()
        val effort = ModelStore.effortFor(p?.id ?: "", p?.defaultEffort ?: 1)
        val modelLabel = p?.model?.takeIf { it.isNotBlank() } ?: "未配置模型"
        val text = modelLabel + " · " + Effort.label(effort)
        binding.tvModelEffort.text = text
        binding.tvDrawerMeta.text = text
        updateTitle()
    }

    private fun updateSendEnabled() {
        val hasText = !binding.etInput.text.isNullOrBlank()
        val enabled = !thinking && (hasText || attachments.isNotEmpty())
        binding.btnSend.isEnabled = enabled
        binding.btnSend.alpha = if (enabled) 1f else 0.45f
        binding.btnAttach.alpha = if (thinking) 0.45f else 1f
    }

    // ---------------- 附件 ----------------

    private fun addAttachment(uri: Uri, isImage: Boolean) {
        if (attachments.size >= MAX_ATTACH) {
            toast("最多带 " + MAX_ATTACH + " 个附件")
            return
        }
        attachments.add(Attachment(uri, displayName(uri), isImage))
        renderAttachments()
        updateSendEnabled()
    }

    private fun renderAttachments() {
        binding.llAttachments.removeAllViews()
        if (attachments.isEmpty()) {
            binding.llAttachments.visibility = View.GONE
            return
        }
        binding.llAttachments.visibility = View.VISIBLE
        attachments.forEachIndexed { index, a ->
            val chip = TextView(this)
            chip.text = (if (a.isImage) "🖼 " else "📄 ") + a.name + "  ×"
            chip.textSize = 12f
            chip.setTextColor(color(R.color.text_secondary))
            chip.background = ContextCompat.getDrawable(this, R.drawable.bg_chip)
            chip.setPadding(dp(10), dp(5), dp(10), dp(5))
            chip.maxWidth = dp(200)
            chip.isSingleLine = true
            chip.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = dp(6)
            chip.layoutParams = lp
            chip.setOnClickListener {
                attachments.removeAt(index)
                renderAttachments()
                updateSendEnabled()
            }
            binding.llAttachments.addView(chip)
        }
    }

    private fun showAttachSheet() {
        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_attach, null)
        dialog.setContentView(v)
        v.findViewById<LinearLayout>(R.id.btnPickImage).setOnClickListener {
            dialog.dismiss()
            pickImage.launch("image/*")
        }
        v.findViewById<LinearLayout>(R.id.btnPickFile).setOnClickListener {
            dialog.dismiss()
            pickFile.launch("text/*")
        }
        dialog.show()
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) {
                            val n = c.getString(i)
                            if (!n.isNullOrBlank()) return n
                        }
                    }
                }
            }
        }
        return uri.lastPathSegment ?: "附件"
    }

    private fun describeImage(uri: Uri): String {
        val name = displayName(uri)
        return runCatching {
            if (AgentConfig.visionKey.isBlank()) return "（图片 " + name + "：还没配置视觉 API Key）"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return "（图片读取失败）"
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return "（图片解析失败）"
            val max = 1280
            if (bmp.width > max || bmp.height > max) {
                val s = max.toFloat() / maxOf(bmp.width, bmp.height)
                bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
            }
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
            val desc = VisionClient.describe(out.toByteArray(), "用户聊天里发来的图片", "")
            "（用户发来一张图片 " + name + "，视觉描述：" + desc + "）"
        }.getOrElse { e -> "（图片 " + name + " 无法识别：" + (e.message ?: "未知错误") + "）" }
    }

    private fun readTextFile(uri: Uri): String? = runCatching {
        val name = displayName(uri).lowercase()
        val textLike = name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".text") ||
                name.endsWith(".json") || name.endsWith(".csv") || name.endsWith(".log")
        if (!textLike) return null
        val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        text.take(4000)
    }.getOrNull()

    // ---------------- 发送 ----------------

    private fun send() {
        if (thinking) return
        val text = binding.etInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() && attachments.isEmpty()) return
        val profile = ModelStore.current()
        if (profile == null) {
            toast("先去设置里添加一个模型")
            return
        }
        if (profile.apiKey.isBlank()) {
            toast("先在设置里把 API Key 填好")
            return
        }

        val atts = attachments.toList()
        attachments.clear()
        binding.etInput.setText("")
        renderAttachments()
        updateSendEnabled()

        val display = buildDisplayText(text, atts)
        ChatStore.append(this, sessionId, ChatMsg("user", display))
        thinking = true
        liveReasoning = ""
        liveThinkingCard = null
        updateSendEnabled()
        render()

        thread(name = "whale-chat") {
            var error: String? = null
            var result: BrainReply? = null
            val payload = buildPayloadText(text, atts)
            try {
                val history = ChatStore.load(this, sessionId).dropLast(1)
                result = ChatBrain.replyStreaming(this, history, payload) { reasoning, _ ->
                    liveReasoning = reasoning
                    runOnUiThread { updateLiveThinking(reasoning) }
                }
            } catch (e: Exception) {
                error = e.message
                // 流式失败就退回普通请求，保证消息能发出去
                result = runCatching {
                    val history = ChatStore.load(this, sessionId).dropLast(1)
                    ChatBrain.reply(this, history, payload)
                }.getOrNull()
            }
            val reply = result
            val err = error
            runOnUiThread {
                thinking = false
                liveThinkingCard = null
                liveReasoning = ""
                if (reply == null) {
                    ChatStore.append(this, sessionId, ChatMsg("assistant", "调用失败：" + (err ?: "未知错误")))
                } else {
                    ChatStore.append(
                        this, sessionId,
                        ChatMsg(
                            "assistant", reply.text,
                            task = reply.task,
                            reasoning = reply.reasoning.ifBlank { null }
                        )
                    )
                }
                render()
                renderSessions()
                updateSendEnabled()
                updateTitle()
                val task = reply?.task
                if (!task.isNullOrBlank()) {
                    // AI 给出了可执行任务：直接弹「发起任务」卡片，不再需要点「让它去动手」
                    binding.llMessages.post { showTaskConfirmSheet(task) }
                }
            }
        }
    }

    private fun buildDisplayText(text: String, atts: List<Attachment>): String {
        if (atts.isEmpty()) return text
        val sb = StringBuilder(text)
        atts.forEach { a ->
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("【附件】").append(if (a.isImage) "🖼 " else "📄 ").append(a.name)
        }
        return sb.toString()
    }

    private fun buildPayloadText(text: String, atts: List<Attachment>): String {
        val sb = StringBuilder(text)
        atts.forEach { a ->
            if (a.isImage) {
                sb.append("\n\n").append(describeImage(a.uri))
            } else {
                val t = readTextFile(a.uri)
                if (t != null) {
                    sb.append("\n\n【文件 ").append(a.name).append("】\n").append(t)
                } else {
                    sb.append("\n\n【附件 ").append(a.name).append("：暂只支持 txt / md 文本文件】")
                }
            }
        }
        return sb.toString()
    }

    // ---------------- 强度 / 模型 ----------------

    private fun showEffortSheet() {
        val profile = ModelStore.current() ?: run {
            toast("先去设置里添加一个模型")
            return
        }
        val id = profile.id
        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.dialog_effort, null)
        dialog.setContentView(v)

        val tvName = v.findViewById<TextView>(R.id.tvEffortName)
        val tvModel = v.findViewById<TextView>(R.id.tvEffortModel)
        val sb = v.findViewById<SeekBar>(R.id.sbEffort)
        val labels = Effort.labels

        var level = ModelStore.effortFor(id, profile.defaultEffort)
        tvModel.text = profile.model
        tvName.text = labels[level]
        sb.progress = level

        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                level = progress
                ModelStore.setLastEffort(id, progress)
                tvName.text = labels[progress]
                updateHeaderChip()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        v.findViewById<ImageButton>(R.id.btnEffortReset).setOnClickListener {
            ModelStore.resetLastEffort(id)
            val def = ModelStore.get(id)?.defaultEffort ?: 1
            level = def
            sb.progress = def
            tvName.text = labels[def]
            updateHeaderChip()
            toast("已恢复默认档位")
        }
        v.findViewById<LinearLayout>(R.id.btnPickModel).setOnClickListener {
            dialog.dismiss()
            showModelSheet()
        }
        dialog.show()
    }

    private fun showModelSheet() {
        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_models, null)
        dialog.setContentView(v)
        val box = v.findViewById<LinearLayout>(R.id.llModels)
        val list = ModelStore.textProfiles()
        val currentId = ModelStore.currentId()
        if (list.isEmpty()) {
            val tv = TextView(this)
            tv.text = "还没有模型，去设置里添加"
            tv.setTextColor(color(R.color.text_secondary))
            tv.textSize = 14f
            tv.setPadding(dp(4), dp(12), dp(4), dp(12))
            box.addView(tv)
        }
        for (p in list) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(14), dp(12), dp(14), dp(12))
            row.background = ContextCompat.getDrawable(this, R.drawable.bg_drawer_item)
            row.isClickable = true
            row.isFocusable = true
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(4)
            row.layoutParams = lp

            val name = TextView(this)
            name.text = p.name
            name.textSize = 15f
            name.setTextColor(color(R.color.text_primary))
            row.addView(name)

            val idView = TextView(this)
            idView.text = p.model
            idView.textSize = 12f
            idView.setTextColor(color(R.color.text_secondary))
            idView.setPadding(0, dp(2), 0, 0)
            row.addView(idView)

            if (p.id == currentId) {
                val badge = TextView(this)
                badge.text = "使用中"
                badge.textSize = 11f
                badge.setTextColor(color(R.color.accent))
                badge.setPadding(0, dp(2), 0, 0)
                row.addView(badge)
            }
            row.setOnClickListener {
                ModelStore.setCurrent(p.id)
                updateHeaderChip()
                dialog.dismiss()
                toast("已切换到 " + p.name)
            }
            box.addView(row)
        }
        dialog.show()
    }

    // ---------------- 消息渲染 ----------------

    private fun render() {
        val box = binding.llMessages
        box.removeAllViews()
        val msgs = ChatStore.load(this, sessionId)
        if (msgs.isEmpty() && !thinking) {
            box.addView(hintView("说点什么吧。\n要它动手，就直接说「帮我打开设置看蓝牙」。"))
        }
        for ((idx, m) in msgs.withIndex()) {
            if (m.role == "task") {
                box.addView(taskCard(m))
            } else {
                if (!m.reasoning.isNullOrBlank()) {
                    box.addView(thinkingCard(m.reasoning.orEmpty(), streaming = false))
                }
                val row = bubbleRow(m)
                box.addView(row)
                if (idx == msgs.lastIndex) animateIn(row)
            }
        }
        if (thinking) {
            val card = liveThinkingCard ?: createLiveThinkingCard()
            (card.parent as? ViewGroup)?.removeView(card)
            box.addView(card)
        }
        binding.svMessages.post { binding.svMessages.fullScroll(View.FOCUS_DOWN) }
    }

    private fun hintView(text: String): View {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.setTextColor(color(R.color.text_secondary))
        tv.setLineSpacing(dp(4).toFloat(), 1f)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(28)
        lp.leftMargin = dp(6)
        lp.rightMargin = dp(6)
        tv.layoutParams = lp
        return tv
    }

    private fun animateIn(v: View) {
        v.alpha = 0f
        v.translationY = 10f * resources.displayMetrics.density
        v.animate().alpha(1f).translationY(0f).setDuration(180L).start()
    }

    private fun avatarView(): View {
        val avatar = TextView(this)
        avatar.text = "🐋"
        avatar.textSize = 14f
        avatar.gravity = Gravity.CENTER
        avatar.background = ContextCompat.getDrawable(this, R.drawable.bg_logo)
        val lp = LinearLayout.LayoutParams(dp(28), dp(28))
        lp.marginEnd = dp(8)
        lp.topMargin = dp(2)
        avatar.layoutParams = lp
        return avatar
    }

    private fun bubbleRow(m: ChatMsg): View {
        val mine = m.role == "user"
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = if (mine) Gravity.END else Gravity.START
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

        if (!mine) row.addView(avatarView())

        val tv = TextView(this)
        tv.text = m.text
        tv.textSize = 15f
        tv.setLineSpacing(dp(3).toFloat(), 1f)
        tv.setTextColor(color(if (mine) R.color.on_chat_me else R.color.text_primary))
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        tv.background = ContextCompat.getDrawable(
            this, if (mine) R.drawable.bg_chat_me else R.drawable.bg_chat_ai
        )
        tv.setTextIsSelectable(true)
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        lp.gravity = if (mine) Gravity.END else Gravity.START
        tv.layoutParams = lp
        row.addView(tv)
        return row
    }

    private fun taskCard(m: ChatMsg): View {
        val card = MaterialCardView(this)
        card.setCardBackgroundColor(color(R.color.surface_high))
        card.radius = dp(20).toFloat()
        card.strokeWidth = dp(1)
        card.strokeColor = color(R.color.stroke)
        card.cardElevation = 0f
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(10)
        card.layoutParams = lp

        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.setPadding(dp(16), dp(14), dp(16), dp(14))
        card.addView(inner)

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.ic_task)
        icon.imageTintList = ColorStateList.valueOf(color(R.color.accent))
        val ilp = LinearLayout.LayoutParams(dp(16), dp(16))
        ilp.marginEnd = dp(6)
        icon.layoutParams = ilp
        head.addView(icon)
        val title = TextView(this)
        title.text = "任务结果"
        title.textSize = 12f
        title.setTextColor(color(R.color.accent))
        head.addView(title)
        inner.addView(head)

        val body = TextView(this)
        body.text = m.text
        body.textSize = 14f
        body.setLineSpacing(dp(3).toFloat(), 1f)
        body.setTextColor(color(R.color.text_primary))
        body.setTextIsSelectable(true)
        body.setPadding(0, dp(8), 0, 0)
        inner.addView(body)
        return card
    }

    private fun runButton(goal: String): View {
        val tv = TextView(this)
        tv.text = "让它去动手"
        tv.textSize = 13f
        tv.setTextColor(color(R.color.accent))
        tv.gravity = Gravity.CENTER
        tv.background = ContextCompat.getDrawable(this, R.drawable.bg_chip)
        tv.setPadding(dp(14), dp(8), dp(16), dp(8))
        TextViewCompat.setCompoundDrawableTintList(tv, ColorStateList.valueOf(color(R.color.accent)))
        tv.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0)
        tv.compoundDrawablePadding = dp(6)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.END
        lp.topMargin = dp(6)
        tv.layoutParams = lp
        tv.setOnClickListener { showTaskConfirmSheet(goal) }
        return tv
    }

    /** 发起任务弹窗：可编辑目标 + 开始执行 / 取消（不再跳任务台页面） */
    private fun showTaskConfirmSheet(goal: String) {
        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_task_confirm, null)
        dialog.setContentView(v)

        val etGoal = v.findViewById<EditText>(R.id.etTaskGoal)
        etGoal.setText(goal)
        etGoal.setSelection(etGoal.text?.length ?: 0)

        v.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnRun).setOnClickListener {
            val g = etGoal.text?.toString()?.trim().orEmpty()
            if (g.isEmpty()) {
                toast("先写下要它做什么")
                return@setOnClickListener
            }
            dialog.dismiss()
            val err = TaskRunner.start(this, g, sessionId)
            if (err != null) {
                toast(err)
                when {
                    err.contains("无障碍") ->
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    err.contains("悬浮窗") ->
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + packageName)
                            )
                        )
                }
            } else {
                toast("任务已开始，可以切到别的 App 了")
            }
        }
        v.findViewById<TextView>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * 思考过程卡片：
     * streaming=true 时展开、实时追加并自动滚到底；流式结束后重绘成折叠态（点标题展开）。
     */
    private fun thinkingCard(reasoning: String, streaming: Boolean): View {
        val card = MaterialCardView(this)
        card.setCardBackgroundColor(color(R.color.surface_high))
        card.radius = dp(18).toFloat()
        card.strokeWidth = dp(1)
        card.strokeColor = color(R.color.stroke)
        card.cardElevation = 0f
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(8)
        card.layoutParams = lp

        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.setPadding(dp(14), dp(10), dp(14), dp(10))
        card.addView(inner)

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.isClickable = true
        header.setPadding(0, dp(2), 0, dp(2))

        val bolt = ImageView(this)
        bolt.setImageResource(R.drawable.ic_bolt)
        bolt.imageTintList = ColorStateList.valueOf(color(R.color.accent_3))
        header.addView(bolt, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) })

        val title = TextView(this)
        title.textSize = 12f
        title.setTextColor(color(R.color.text_secondary))
        title.text = if (streaming) "思考中…" else "已深度思考 · " + reasoning.length + " 字"
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val copy = TextView(this)
        copy.text = "复制"
        copy.textSize = 11f
        copy.setTextColor(color(R.color.accent))
        copy.setPadding(dp(8), dp(4), dp(8), dp(4))
        copy.isClickable = true
        copy.isFocusable = true
        copy.setOnClickListener {
            val text = if (streaming) {
                liveThinkingBody?.text?.toString().orEmpty().ifBlank { liveReasoning }
            } else {
                reasoning
            }
            if (text.isBlank()) {
                toast("还没有思考内容")
            } else {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("思考过程", text))
                toast("思考过程已复制")
            }
        }
        header.addView(copy, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val chevron = ImageView(this)
        chevron.setImageResource(R.drawable.ic_expand_more)
        chevron.imageTintList = ColorStateList.valueOf(color(R.color.text_tertiary))
        chevron.rotation = if (streaming) 180f else 0f
        header.addView(chevron, LinearLayout.LayoutParams(dp(16), dp(16)))
        inner.addView(header)

        val scroll = ScrollView(this)
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(170)
        )
        scroll.visibility = if (streaming) View.VISIBLE else View.GONE
        scroll.isNestedScrollingEnabled = true
        // 内层可独立滚动：告诉外层消息列表别抢手势
        scroll.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL ->
                    v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        val body = TextView(this)
        body.textSize = 12f
        body.setTextColor(color(R.color.text_secondary))
        body.setTextIsSelectable(false)
        body.setLineSpacing(dp(2).toFloat(), 1f)
        body.setPadding(0, dp(8), 0, 0)
        body.text = reasoning
        scroll.addView(body)
        inner.addView(scroll)

        header.setOnClickListener {
            val show = scroll.visibility != View.VISIBLE
            scroll.visibility = if (show) View.VISIBLE else View.GONE
            chevron.rotation = if (show) 180f else 0f
            if (show) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        if (streaming) {
            liveThinkingCard = card
            liveThinkingBody = body
            liveThinkingScroll = scroll
            liveThinkingHeader = title
        }
        return card
    }

    private fun createLiveThinkingCard(): View = thinkingCard(liveReasoning, streaming = true)

    /** 每次收到新思考片段：追加文本 + 快速滚到底 */
    private fun updateLiveThinking(text: String) {
        val body = liveThinkingBody ?: return
        body.text = text
        liveThinkingHeader?.text = "思考中…（" + text.length + " 字）"
        val scroll = liveThinkingScroll ?: return
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------------- 工具 ----------------

    /** 对话提示词：附加在每次聊天的系统提示里 */
    private fun showPromptSheet() {
        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_prompt, null)
        dialog.setContentView(v)
        val et = v.findViewById<EditText>(R.id.etPrompt)
        et.setText(AgentConfig.chatPrompt)
        et.setSelection(et.text?.length ?: 0)
        v.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btnSavePrompt).setOnClickListener {
            AgentConfig.chatPrompt = et.text?.toString()?.trim().orEmpty()
            dialog.dismiss()
            toast(if (AgentConfig.chatPrompt.isBlank()) "已清空对话提示词" else "对话提示词已保存，下一条对话生效")
        }
        v.findViewById<TextView>(R.id.btnCancelPrompt).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("WhaleHand v" + BuildConfig.VERSION_NAME)
            .setMessage(
                "让 AI 替你用手点手机。\n\n" +
                        "聊天可以闲聊，也可以让它动手；动手前会先跟你确认目标。\n" +
                        "涉及支付 / 密码 / 验证码的操作，它不会自己做。"
            )
            .setPositiveButton("知道啦", null)
            .show()
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
