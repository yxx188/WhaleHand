# 贡献指南

感谢你愿意帮这只鲸鱼。

## 反馈 Bug

开 issue 时请带上：

- 手机型号 + Android 版本 + 系统（OriginOS / HyperOS / ...）
- WhaleHand 版本（「设置 → 关于」里能看到）
- 任务台日志里失败前最后 10 行（**先删掉 API Key、手机号、地址等隐私**）
- 是否开启视觉外挂 / 无限制模式

## 提交 PR

- 从 `main` 切分支，一次改动只做一件事
- 本地构建通过：`./gradlew assembleDebug`（或 Android Studio 直接 Run）
- 新增依赖请说明理由，别把包体做大
- 不要提交 `release.properties`、`release.keystore` 或任何 API Key
- Windows 上工程路径别含中文/非 ASCII 字符（AGP 会直接拒绝构建）
- 中文 / 英文注释、提交信息都可以

## 特别欢迎

- 机型适配（不同系统的无障碍节点差异很大）
- 降本优化：增量 dump、视觉调用节流、上下文压缩
- 文档、截图、英文翻译
