# 架构说明

> 面向想改代码、想抄思路的人。对应 2.0 版本。README 里是给用户看的，这里讲实现。

## 1. 模块分层

| 层 | 关键类 | 职责 |
|---|---|---|
| UI | `ChatActivity` `MainActivity` `SettingsActivity` + 各二级设置页 | 聊天、任务台、设置 |
| 聊天决策 | `ChatBrain` `ChatStore` | 组装聊天 prompt、解析 `{reply, task}`、会话持久化 |
| 任务编排 | `TaskRunner` | 不依赖页面的启动 / 停止，持有运行状态 |
| 决策循环 | `AgentLoop` | 单步：dump → prompt → action → 执行 → 判定 |
| 模型 | `LlmClient` `VisionClient` | OpenAI 兼容请求、SSE、动作解析、视觉描述 |
| 设备能力 | `AgentAccessibilityService` `OverlayManager` `FloatingService` | 节点 dump、手势、截图、悬浮状态条 |
| 存储 | `MemoryStore` `ExperienceStore` `SkillStore` `TaskLogStore` `EpisodeRecorder` `ModelStore` `AgentConfig` `AppRules` | 本地数据 |
| 工具通道 | `ToolBridge` | Termux RUN_COMMAND |

## 2. 聊天链路

1. `ChatActivity` 收集最近 20 条消息（含任务汇报），交给 `ChatBrain.replyStreaming`。
2. `ChatBrain` 组装 system prompt：身份设定 + 设备信息 + 当前模型 + 长期记忆摘要（最多 800 字）+ 动手规则 + `{"reply", "task"}` 的 JSON 输出格式。
3. 流式返回：正文上屏，`reasoning_content` 进思考卡片；思考超过 1200 字后开始做滑动窗口复读检测，发现复读就断开，避免烧钱。
4. 解析出非空 `task` → 弹「发起任务」卡片 → 用户确认 / 修改目标 → `TaskRunner.start(ctx, goal, sid)`。
5. 任务结束后，结果作为一条 `task` 消息写回 `ChatStore`。

聊天侧唯一的硬规则：**涉及支付、转账、密码、验证码、指纹的目标，绝不允许放进 task**。

## 3. 任务循环（`AgentLoop`）

```text
step = 0
feedback = null
while step < limit:
    tree = service.dumpStable()                  // 节点树文本，最多 900 节点 / 40 层
    screenDesc = null
    if visionEnabled and tree 的可读文字节点 < 8:
        screenDesc = VisionClient.describe(截图)  // 可选视觉外挂
    action = LlmClient.decide(goal, history, tree, screenDesc, feedback)
    if action.type == "done":
        校验 action.evidence 是否真的出现在 tree 里
        出现 → 成功结束；连续 3 次不出现 → 终止
    else:
        执行 action（tap / input / swipe / scroll / wait / back / home / shell）
    feedback = 执行结果 + 界面变化描述
    step++
```

### 卡住时

- 同一动作 / 同一路径反复失败 → 触发一次 `reflect` prompt，让模型自己复盘「为什么没进展、换什么思路」，不新增步数上限。
- 界面没变化 → 提示区分「没点中」和「应用在加载」，让模型决定 wait / 换元素 / 换路径。

### 阻断页

- `classifyBlockingPage(tree)` 检测指纹 / 密码 / 人脸 / 验证码 / 支付等页面。
- 命中后暂停循环，在悬浮条上显示一句话，等用户点「继续」或停止。
- 这是安全阀里最重要的一条：**模型再想点，也点不过去**。

### 步数限制

| 模式 | 上限 |
|---|---|
| 常规 | 30 步（超过后每步提示，接近硬上限终止） |
| 硬上限 | 120 步 / 150 个动作 |
| 无限制模式（root + 口令） | 999 步 |

### 动作协议

模型每一步只输出一个 JSON，包含 `thought` / `memory` / `plan` / `remember` / `action`（`action` 里可有 `type`、`id`、`x`、`y`、`x2`、`y2`、`text`、`tapMode`、`repeat`、`evidence`、`askUser`）。`AgentLoop` 负责校验、执行与回填结果；解析失败 / 字段缺失直接丢弃重试。

## 4. 本地存储

| 路径 | 内容 | 上限 |
|---|---|---|
| `filesDir/memory.jsonl` | 长期记忆，一行一条 | 200 条，30 天未命中遗忘 |
| `filesDir/cases.json` | 经验卡片（任务复盘） | 50 张 |
| `filesDir/skills.json` | 技能（反复成功的路径） | 30 条 |
| `filesDir/episodes/` | 每次任务的完整过程记录 | 由清理入口控制 |
| `filesDir/tasklogs/` | 任务日志 | 10 份 |
| `filesDir/chats/` | 会话 jsonl + 索引 | 20 个会话 × 200 条 |
| `SharedPreferences` | `whale_config` `whale_models` `whale_chat` `app_rules` | — |

记忆系统的细节单独写在 [MEMORY.md](MEMORY.md)。

## 5. 上下文预算（成本都在这）

单步 prompt 里塞了：完整节点树、最近动作历史、工作记忆、计划、注入的长期记忆、可选视觉描述。这是「每步都能看懂屏幕」的代价，也是单任务成本高的根因。已有的省钱设计：

- 节点树最多 900 个、深度最多 40 层；
- 可读文字节点 ≥ 8 个时跳过视觉调用；
- 长期记忆注入最多 24 条、每条 80 字；
- 聊天历史最多 20 条、记忆摘要最多 800 字；
- 思考复读守卫，防止越写越长。

## 6. 扩展点

- **新动作类型**：`LlmClient.parseAction` 解析 + `AgentLoop.execute` 执行 + prompt 里补说明，三处都要改。
- **新模型服务商**：只要是 OpenAI 兼容的 `/chat/completions`，在「模型档案」里新建即可，无需改代码。
- **新记忆层**：参考 `SkillStore`——加载、匹配、上限、淘汰，四个点想清楚再接进 prompt。
