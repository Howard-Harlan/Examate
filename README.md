# Examate

> ex a mate — 超越一个朋友

Examate 是一款智能学习助手，集成了 AI 对话、Agent 工具调用、校园服务查询等功能。名字取自 "ex a mate"，寓意不只是工具，更是一个超越普通朋友的智能学习伙伴。

## 功能特性

### AI 对话
- 支持多 API 厂商配置（OpenAI / Anthropic / 兼容接口）
- Markdown 渲染与 LaTeX 数学公式支持
- 对话历史管理
- 多 AI 验证模式

### Agent 模式
- 工具调用（function calling）：AI 可查询成绩、课表、考试等信息
- Skills 技能系统：导入 SKILL.md 定义 AI 行为
- 记忆管理：长期记住用户偏好
- 图片生成

### 校园服务
- 教务系统：成绩查询、课表、考试安排、选课、教学评价
- 一卡通：余额查询、账单、缴费
- 第二课堂：成绩明细与汇总
- 内存缓存，减少重复请求

### 悬浮窗
- 无障碍服务读取屏幕内容
- Shizuku 免 Root 截屏（规避检测）
- 防截屏保护
- 框选、问问AI、自动答题、浏览

### 其他
- 检查更新（GitHub Releases）
- 液态玻璃 UI 效果
- 隐私优先：所有数据本地存储

## 技术栈

- **Kotlin + Jetpack Compose** — 声明式 UI
- **OkHttp** — 网络请求
- **Coroutines** — 异步编程
- **Shizuku** — 免 Root 特权操作
- **ViewModel + StateFlow** — 状态管理
- **KaTeX** — LaTeX 公式渲染

## 构建说明

1. 克隆仓库
2. 在 `local.properties` 中配置签名信息（参考 `app/build.gradle`）
3. 运行 `./gradlew assembleRelease` 构建

## 说明

本项目为独立开发，早期基于开源社区方案修改而来，现已发展为功能完整的独立应用。感谢开源社区的贡献。

## License

仅供学习交流使用。
